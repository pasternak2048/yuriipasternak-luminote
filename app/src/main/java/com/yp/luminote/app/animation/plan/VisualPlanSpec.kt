package com.yp.luminote.app.animation.plan

import com.yp.luminote.app.animation.definitions.BladeVariant
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.data.settings.HaloFrame

/**
 * Immutable, backend-neutral visual description resolved once for an animation
 * session. It contains no Canvas/GPU objects, clocks, callbacks, or geometry.
 */
internal data class VisualPlanSpec(
    val emitters: List<VisualEmitterSpec>
) {
    init {
        require(emitters.isNotEmpty()) { "A visual plan needs at least one emitter." }
    }
}

internal data class VisualEmitterSpec(
    val geometry: GeometryRequest,
    val transform: EmitterTransform = EmitterTransform(),
    val appearance: AppearancePayload,
    val operation: PrimaryVisualOperation,
    val modifiers: List<VisualModifier> = emptyList()
) {
    init {
        modifiers.forEach { modifier ->
            require(modifier.isCompatibleWith(operation)) {
                "${modifier::class.simpleName} is not compatible with ${operation::class.simpleName}."
            }
        }
    }
}

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
internal data class AppearancePayload(
    val color: Int,
    val intensity: Float,
    val thickness: Float,
    val colorMode: HaloColorMode,
    val palette: List<Int>
)

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
    data class Opacity(val multiplier: Float) : VisualModifier {
        init {
            require(multiplier.isFinite() && multiplier >= 0f) {
                "Opacity multiplier must be finite and non-negative."
            }
        }
    }

    fun isCompatibleWith(operation: PrimaryVisualOperation): Boolean = when (this) {
        // Light Impulse owns its dedicated alpha envelope; changing it would be
        // an approximation until it receives an explicit compatible modifier.
        is Opacity -> operation !is PrimaryVisualOperation.LightImpulse
    }
}

internal fun normalizedFraction(value: Float): Float = (value % 1f + 1f) % 1f
