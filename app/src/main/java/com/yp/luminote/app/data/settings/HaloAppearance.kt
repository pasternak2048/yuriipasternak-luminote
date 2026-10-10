package com.yp.luminote.app.data.settings

import androidx.annotation.StringRes
import com.yp.luminote.app.R

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

/** Stable catalog key; the executable policy is resolved by animation at session start. */
enum class AmbientPolicyKey { CONTINUOUS, PULSE_WITH_SILENCE }

/** Immutable pre-resolved metadata and timing entry. */
data class HaloMotionDefinition internal constructor(
    val motion: HaloMotion,
    @get:StringRes val titleRes: Int,
    val baseDurationSeconds: Float,
    val ambientEligible: Boolean,
    val fadeInMs: Long,
    val fadeOutMs: Long,
    val ambientPolicyKey: AmbientPolicyKey
)

/** Sole ordered catalog. Its derived lists are cached and never built during a frame. */
object HaloAnimationRegistry {
    val definitions: List<HaloMotionDefinition> = listOf(
        definition(HaloMotion.IMPULSE, R.string.motion_impulse, 2.2f, false, 0L, 0L),
        definition(HaloMotion.PULSE, R.string.motion_pulse, 2.5f, true, 250L, 350L, AmbientPolicyKey.PULSE_WITH_SILENCE),
        definition(HaloMotion.SNAKE, R.string.motion_snake, 2.5f, true, 160L, 460L),
        definition(HaloMotion.AZURE_BLADE, R.string.motion_azure_blade, 3.4f, false, 80L, 220L),
        definition(HaloMotion.CRIMSON_BLADE, R.string.motion_crimson_blade, 3.0f, false, 55L, 160L),
        definition(HaloMotion.FORCE_CLASH, R.string.motion_force_clash, 3.6f, false, 70L, 180L)
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

    private fun definition(motion: HaloMotion, @StringRes titleRes: Int, duration: Float, ambientEligible: Boolean, fadeInMs: Long, fadeOutMs: Long, ambientPolicyKey: AmbientPolicyKey = AmbientPolicyKey.CONTINUOUS) =
        HaloMotionDefinition(motion, titleRes, duration, ambientEligible, fadeInMs, fadeOutMs, ambientPolicyKey)
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
