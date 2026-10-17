package com.yp.luminote.app.animation.plan

import com.yp.luminote.app.animation.definitions.BladeVariant
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.data.settings.HaloFrame
import java.util.Collections
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Immutable, backend-neutral visual description resolved once for an animation
 * session. It contains no Canvas/GPU objects, clocks, callbacks, or geometry.
 */
internal class VisualPlanSpec(
    emitters: List<VisualEmitterSpec>
) {
    /** An admitted plan never retains a caller-owned mutable collection. */
    val emitters: List<VisualEmitterSpec> = immutableList(emitters)
    init {
        require(emitters.isNotEmpty()) { "A visual plan needs at least one emitter." }
    }
}

internal class VisualEmitterSpec(
    val geometry: GeometryRequest,
    val transform: EmitterTransform = EmitterTransform(),
    val appearance: AppearancePayload,
    val operation: PrimaryVisualOperation,
    val modifierProfile: VisualModifierProfile = VisualModifierProfile.DEFAULT,
    modifiers: List<VisualModifier> = emptyList()
) {
    /** Closed, ordered effect stack captured at the plan admission boundary. */
    val modifiers: List<VisualModifier> = immutableList(modifiers)

    init {
        this.modifiers.forEach { modifier ->
            require(modifier.isCompatibleWith(operation, modifierProfile)) {
                "${modifier::class.simpleName} is not compatible with ${operation::class.simpleName}."
            }
        }
        require(this.modifiers.zipWithNext().all { (first, second) ->
            first.canonicalOrder < second.canonicalOrder
        }) { "Modifiers must be unique and declared in canonical compositor order." }
    }

    fun copy(
        geometry: GeometryRequest = this.geometry,
        transform: EmitterTransform = this.transform,
        appearance: AppearancePayload = this.appearance,
        operation: PrimaryVisualOperation = this.operation,
        modifierProfile: VisualModifierProfile = this.modifierProfile,
        modifiers: List<VisualModifier> = this.modifiers
    ) = VisualEmitterSpec(geometry, transform, appearance, operation, modifierProfile, modifiers)
}

/**
 * A profile is selected once at plan admission, never inferred by the Canvas
 * executor. It keeps the reminder's shared LightImpulse operation optically
 * closed while allowing the one normal registry entry to opt in explicitly.
 */
internal enum class VisualModifierProfile { DEFAULT, NORMAL_IMPULSE }

/** Geometry is resolved only by DisplayOutline and EdgePathGeometryProvider at draw time. */
internal sealed interface GeometryRequest {
    data class DisplayOutlineContour(
        val thickness: Float,
        val frame: HaloFrame,
        val edgeCalibrationDp: Float,
        val cornerCalibrationDp: Float,
        val cornerShape: Float
    ) : GeometryRequest
}

/** Pure mapping of host-provided phase; this never owns animation time. */
internal data class EmitterTransform(
    val phaseOffset: Float = 0f,
    val rate: Float = 1f,
    val reverse: Boolean = false
) {
    init {
        require(rate.isFinite()) { "Emitter rate must be finite." }
        require(phaseOffset.isFinite()) { "Emitter phase offset must be finite." }
    }

    fun phaseFor(hostPhase: Float): Float {
        // The compatibility mapper's identity transform must preserve the host
        // float exactly; normalization here would perturb existing renderer input.
        if (!reverse && rate == 1f && phaseOffset == 0f) return hostPhase
        val directedPhase = if (reverse) -hostPhase else hostPhase
        return normalizedFraction(directedPhase * rate + phaseOffset)
    }
}

/** Session-resolved appearance; Canvas remains authoritative for its rendering treatment. */
internal class AppearancePayload(
    val color: Int,
    val intensity: Float,
    val thickness: Float,
    val colorMode: HaloColorMode,
    palette: List<Int>
) {
    val palette: List<Int> = immutableList(palette)

    override fun equals(other: Any?): Boolean = other is AppearancePayload &&
        color == other.color && intensity == other.intensity && thickness == other.thickness &&
        colorMode == other.colorMode && palette == other.palette

    override fun hashCode(): Int = arrayOf(color, intensity, thickness, colorMode, palette).contentHashCode()
}

/** One family-specific operation per emitter keeps existing specialized renderers intact. */
internal sealed interface PrimaryVisualOperation {
    object FullContourPulse : PrimaryVisualOperation
    data class TravelingLuminousSegment(val lengthFraction: Float) : PrimaryVisualOperation {
        init {
            require(lengthFraction.isFinite() && lengthFraction > 0f) {
                "Segment length must be finite and positive."
            }
        }
    }
    object LightImpulse : PrimaryVisualOperation
    object Supernova : PrimaryVisualOperation
    data class ForceBlade(val variant: BladeVariant) : PrimaryVisualOperation
}

/**
 * Deliberately closed modifier set. The only Phase 2 modifier has exact Canvas
 * semantics: it multiplies the emitter alpha before the established renderer runs.
 */
internal sealed interface VisualModifier {
    /** Canonical declaration and evaluation order; never infer it from caller order. */
    val canonicalOrder: Int

    data class Opacity(val multiplier: Float) : VisualModifier {
        override val canonicalOrder: Int = 0
        init {
            require(multiplier.isFinite() && multiplier >= 0f) {
                "Opacity multiplier must be finite and non-negative."
            }
        }
    }

    /** A frame-time-driven multiplier in [1 - amplitude, 1], with no new clock. */
    data class IntensityPulse(
        val amplitude: Float,
        val frequencyHz: Float = 1f
    ) : VisualModifier {
        override val canonicalOrder: Int = 1
        init {
            require(amplitude.isFinite() && amplitude in 0f..1f) {
                "Pulse amplitude must be finite and within 0..1."
            }
            require(frequencyHz.isFinite() && frequencyHz in 0f..120f && frequencyHz > 0f) {
                "Pulse frequency must be finite and within 0..120 Hz."
            }
        }
    }

    /** Softens the source stroke itself; it is intentionally distinct from [Glow]. */
    data class Blur(val radiusPx: Float) : VisualModifier {
        override val canonicalOrder: Int = 2
        init {
            require(radiusPx.isFinite() && radiusPx in 0f..64f) {
                "Blur radius must be finite and within 0..64 px."
            }
        }
    }

    /** Adds a separately rendered, expanded envelope behind the source. */
    data class Glow(val radiusPx: Float, val alphaMultiplier: Float = 1f) : VisualModifier {
        override val canonicalOrder: Int = 3
        init {
            require(radiusPx.isFinite() && radiusPx in 0f..64f) {
                "Glow radius must be finite and within 0..64 px."
            }
            require(alphaMultiplier.isFinite() && alphaMultiplier in 0f..1f) {
                "Glow alpha multiplier must be finite and within 0..1."
            }
        }
    }

    fun isCompatibleWith(
        operation: PrimaryVisualOperation,
        profile: VisualModifierProfile = VisualModifierProfile.DEFAULT
    ): Boolean = VisualOperationModifierPolicy.allows(operation, this, profile)
}

/**
 * The single admission policy for typed visual operations. Keeping it beside the
 * closed operation/modifier contracts prevents a renderer from advertising a
 * modifier that its actual Canvas source cannot execute.
 */
internal object VisualOperationModifierPolicy {
    fun allows(
        operation: PrimaryVisualOperation,
        modifier: VisualModifier,
        profile: VisualModifierProfile = VisualModifierProfile.DEFAULT
    ): Boolean = when (modifier) {
        // LightImpulse owns an alpha envelope. Its only modifier-capable profile
        // is the exact normal registry Impulse admitted by the mapper.
        is VisualModifier.Opacity -> operation !is PrimaryVisualOperation.LightImpulse
        is VisualModifier.IntensityPulse -> operation !is PrimaryVisualOperation.LightImpulse ||
            profile == VisualModifierProfile.NORMAL_IMPULSE
        is VisualModifier.Blur, is VisualModifier.Glow -> operation is PrimaryVisualOperation.FullContourPulse ||
            operation is PrimaryVisualOperation.TravelingLuminousSegment ||
            operation is PrimaryVisualOperation.Supernova ||
            (operation is PrimaryVisualOperation.LightImpulse &&
                profile == VisualModifierProfile.NORMAL_IMPULSE)
    }
}

/** Shared by command compilation and the dedicated Light Impulse source path. */
internal fun intensityPulseMultiplier(modifiers: List<VisualModifier>, frameTimeNanos: Long): Float {
    var multiplier = 1f
    var index = 0
    while (index < modifiers.size) {
        val modifier = modifiers[index]
        if (modifier is VisualModifier.IntensityPulse) {
            // Reduce before trig so long-running sessions retain deterministic sub-frame phase.
            val periodNanos = (NANOS_PER_SECOND / modifier.frequencyHz).roundToLong()
            val nanosIntoPeriod = Math.floorMod(frameTimeNanos, periodNanos)
            val wave = (0.5 + 0.5 * sin(TWO_PI * nanosIntoPeriod.toDouble() / periodNanos)).toFloat()
            multiplier *= 1f - modifier.amplitude + modifier.amplitude * wave
        }
        index++
    }
    return multiplier
}

private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val TWO_PI = 2.0 * PI

internal fun normalizedFraction(value: Float): Float = (value % 1f + 1f) % 1f

/** Kotlin's [List] is read-only only at the type level. */
private fun <T> immutableList(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
