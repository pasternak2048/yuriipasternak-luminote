package com.yp.luminote.app.data.settings

import androidx.annotation.StringRes
import com.yp.luminote.app.R
import kotlin.math.PI
import kotlin.math.sin

enum class HaloFrame { CLASSIC }

/** Stable persisted/external identifiers. Behaviour deliberately lives in [HaloAnimationRegistry]. */
enum class HaloMotion {
    PULSE, IMPULSE, SNAKE, AZURE_BLADE, CRIMSON_BLADE, FORCE_CLASH;

    companion object {
        /** Parses retained IDs and known historical IDs, without granting historical IDs runtime behaviour. */
        fun fromStorage(value: String?): HaloMotion? = when (value) {
            "RIPPLE_EDGE" -> IMPULSE
            "CORNER_PULSE", "RAIN", null -> null
            else -> entries.firstOrNull { it.name == value }
        }
    }
}

data class HaloFrameDefinition(val frame: HaloFrame, @get:StringRes val titleRes: Int, val supportedMotions: List<HaloMotion>)

internal data class HaloAnimationEnvelopePolicy(val fadeInMs: Long, val fadeOutMs: Long)

/** Definition-owned policy; selected once when an ambient session starts. */
internal fun interface AmbientProgressPolicy { fun alphaAt(elapsedSeconds: Double): Float }

internal object AmbientProgressPolicies {
    val continuous = AmbientProgressPolicy { 1f }
    val pulseWithSilence = AmbientProgressPolicy { elapsedSeconds ->
        val position = elapsedSeconds % PULSE_CYCLE_SECONDS
        if (position < PULSE_SILENCE_SECONDS) 0f
        else sin(PI * ((position - PULSE_SILENCE_SECONDS) / PULSE_DURATION_SECONDS)).toFloat()
    }

    private const val PULSE_SILENCE_SECONDS = 10.0
    private const val PULSE_DURATION_SECONDS = 2.5
    private const val PULSE_CYCLE_SECONDS = PULSE_SILENCE_SECONDS + PULSE_DURATION_SECONDS
}

/** Reused render-session payload. Definitions invoke their bound delegate directly on VSYNC. */
internal class HaloRenderFrame {
    var phase: Float = 0f
    var alpha: Int = 0
    var gradientPhase: Float = 0f
}

/** Implemented by the renderer's cached surface; it contains no motion identifier. */
internal interface HaloRenderSurface {
    /** Compose a source and paired travelling beams into the shared GPU effect frame. */
    fun composeImpulse(frame: HaloRenderFrame)
    /** Compose one closed contour emitter into the shared GPU effect frame. */
    fun composeClosedPulse(frame: HaloRenderFrame)
    /** Compose one directed contour beam into the shared GPU effect frame. */
    fun composeBeam(frame: HaloRenderFrame, startFraction: Float, lengthFraction: Float)
    /** Compose the laser family without handing a Canvas/path to the registry. */
    fun composeLaser(frame: HaloRenderFrame, variant: HaloBladeVariant)
}

internal enum class HaloBladeVariant { AZURE, CRIMSON, CLASH }

internal fun interface HaloRenderDelegate {
    fun draw(surface: HaloRenderSurface, frame: HaloRenderFrame)
}

private object HaloRenderDelegates {
    val impulse = HaloRenderDelegate { surface, frame -> surface.composeImpulse(frame) }
    val pulse = HaloRenderDelegate { surface, frame -> surface.composeClosedPulse(frame) }
    val snake = HaloRenderDelegate { surface, frame ->
        surface.composeBeam(frame, normalizedFraction(frame.phase), SNAKE_SEGMENT_FRACTION)
    }
    val azureBlade = HaloRenderDelegate { surface, frame -> surface.composeLaser(frame, HaloBladeVariant.AZURE) }
    val crimsonBlade = HaloRenderDelegate { surface, frame -> surface.composeLaser(frame, HaloBladeVariant.CRIMSON) }
    val forceClash = HaloRenderDelegate { surface, frame -> surface.composeLaser(frame, HaloBladeVariant.CLASH) }
}

private fun normalizedFraction(value: Float): Float = (value % 1f + 1f) % 1f
private const val SNAKE_SEGMENT_FRACTION = 0.18f

/** Immutable pre-resolved metadata and timing entry. */
data class HaloMotionDefinition internal constructor(
    val motion: HaloMotion,
    @get:StringRes val titleRes: Int,
    val baseDurationSeconds: Float,
    val ambientEligible: Boolean,
    internal val envelope: HaloAnimationEnvelopePolicy,
    internal val ambientProgressPolicy: AmbientProgressPolicy,
    /** Pre-bound choreography; no ID dispatch is permitted during rendering. */
    internal val renderDelegate: HaloRenderDelegate
)

/** Sole ordered catalog. Its derived lists are cached and never built during a frame. */
object HaloAnimationRegistry {
    val definitions: List<HaloMotionDefinition> = listOf(
        definition(HaloMotion.IMPULSE, R.string.motion_impulse, 2.2f, false, 0L, 0L, delegate = HaloRenderDelegates.impulse),
        definition(HaloMotion.PULSE, R.string.motion_pulse, 2.5f, true, 250L, 350L, AmbientProgressPolicies.pulseWithSilence, HaloRenderDelegates.pulse),
        definition(HaloMotion.SNAKE, R.string.motion_snake, 2.5f, true, 160L, 460L, delegate = HaloRenderDelegates.snake),
        definition(HaloMotion.AZURE_BLADE, R.string.motion_azure_blade, 3.4f, false, 80L, 220L, delegate = HaloRenderDelegates.azureBlade),
        definition(HaloMotion.CRIMSON_BLADE, R.string.motion_crimson_blade, 3.0f, false, 55L, 160L, delegate = HaloRenderDelegates.crimsonBlade),
        definition(HaloMotion.FORCE_CLASH, R.string.motion_force_clash, 3.6f, false, 70L, 180L, delegate = HaloRenderDelegates.forceClash)
    )
    private val byMotion = definitions.associateBy(HaloMotionDefinition::motion)

    /** UI-facing immutable catalog in contract order. */
    val normalDefinitions: List<HaloMotionDefinition> = definitions
    val ambientDefinitions: List<HaloMotionDefinition> = definitions.filter(HaloMotionDefinition::ambientEligible)

    fun definition(motion: HaloMotion): HaloMotionDefinition = byMotion.getValue(motion)
    /** Legacy/unknown/removed normal values converge to the new normal default. */
    fun resolveNormal(value: String?): HaloMotion = HaloMotion.fromStorage(value) ?: HaloMotion.IMPULSE
    /** Ambient only accepts ambient definitions; invalid/non-ambient IDs keep Ambient safe. */
    fun resolveAmbient(value: String?): HaloMotion = HaloMotion.fromStorage(value)?.takeIf { definition(it).ambientEligible } ?: HaloMotion.PULSE

    private fun definition(motion: HaloMotion, @StringRes titleRes: Int, duration: Float, ambientEligible: Boolean, fadeInMs: Long, fadeOutMs: Long, ambientPolicy: AmbientProgressPolicy = AmbientProgressPolicies.continuous, delegate: HaloRenderDelegate) =
        HaloMotionDefinition(motion, titleRes, duration, ambientEligible, HaloAnimationEnvelopePolicy(fadeInMs, fadeOutMs), ambientPolicy, delegate)
}

/** Compatibility facade while callers migrate to the registry's definition lists. */
object HaloEffectCatalog {
    private val frames = listOf(HaloFrameDefinition(HaloFrame.CLASSIC, R.string.frame_edge, HaloAnimationRegistry.normalDefinitions.map(HaloMotionDefinition::motion)))
    fun frame(frame: HaloFrame): HaloFrameDefinition = frames.first { it.frame == frame }
    fun motion(motion: HaloMotion): HaloMotionDefinition = HaloAnimationRegistry.definition(motion)
    fun supports(frame: HaloFrame, motion: HaloMotion): Boolean = motion in frame(frame).supportedMotions
}

val HaloFrame.definition: HaloFrameDefinition get() = HaloEffectCatalog.frame(this)
val HaloMotion.definition: HaloMotionDefinition get() = HaloAnimationRegistry.definition(this)
fun HaloMotion.durationFor(effectSpeed: Float): Float = definition.baseDurationSeconds / effectSpeed.coerceIn(MIN_EFFECT_SPEED, MAX_EFFECT_SPEED)
private const val MIN_EFFECT_SPEED = 0.25f
private const val MAX_EFFECT_SPEED = 2f

enum class HaloColorMode { SOLID, GRADIENT }
enum class HaloColorSource { CUSTOM, APP_ICON, GRADIENT }
enum class GradientPalette { LUMINOTE, NOTIFICATION_APPS }
