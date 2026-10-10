package com.yp.luminote.app.effects.model
import com.yp.luminote.app.data.settings.HaloMotion

/**
 * Backend-neutral render instruction emitted once for a resolved motion session.
 * It deliberately describes only the existing effect primitives, rather than a graphics API.
 */
internal enum class MotionRenderCommandKind { SPECIALIZED_FIELD, FULL_CONTOUR, LUMINOUS_SEGMENT, BLADE }

internal enum class MotionBladeVariant { AZURE, CRIMSON, CLASH }

internal class MotionRenderFrame {
    var phase: Float = 0f
    var alpha: Int = 0
    var gradientPhase: Float = 0f
    /** Monotonic VSYNC timestamp supplied by the host; Canvas never reads a clock itself. */
    var frameTimeNanos: Long = 0L
}

/** Reused frame output: emitters allocate neither commands nor perform registry discovery on VSYNC. */
internal class MotionRenderCommand {
    var kind: MotionRenderCommandKind = MotionRenderCommandKind.SPECIALIZED_FIELD
    var segmentStartFraction: Float = 0f
    var segmentLengthFraction: Float = 0f
    var bladeVariant: MotionBladeVariant = MotionBladeVariant.AZURE
}

internal fun interface MotionRenderEmitter {
    fun emit(frame: MotionRenderFrame, out: MotionRenderCommand)
}

/** Motion-to-emitter resolution happens at session/config update time, never during drawing. */
internal object MotionRenderEmitters {
    private const val SNAKE_SEGMENT_FRACTION = 0.18f

    private val impulse = MotionRenderEmitter { _, out -> out.kind = MotionRenderCommandKind.SPECIALIZED_FIELD }
    private val pulse = MotionRenderEmitter { _, out -> out.kind = MotionRenderCommandKind.FULL_CONTOUR }
    private val snake = MotionRenderEmitter { frame, out ->
        out.kind = MotionRenderCommandKind.LUMINOUS_SEGMENT
        out.segmentStartFraction = normalizedFraction(frame.phase)
        out.segmentLengthFraction = SNAKE_SEGMENT_FRACTION
    }
    private fun blade(variant: MotionBladeVariant) = MotionRenderEmitter { _, out ->
        out.kind = MotionRenderCommandKind.BLADE
        out.bladeVariant = variant
    }

    private val byMotion = mapOf(
        HaloMotion.IMPULSE to impulse,
        HaloMotion.PULSE to pulse,
        HaloMotion.SNAKE to snake,
        HaloMotion.AZURE_BLADE to blade(MotionBladeVariant.AZURE),
        HaloMotion.CRIMSON_BLADE to blade(MotionBladeVariant.CRIMSON),
        HaloMotion.FORCE_CLASH to blade(MotionBladeVariant.CLASH)
    )

    fun resolve(motion: HaloMotion): MotionRenderEmitter = byMotion.getValue(motion)

    private fun normalizedFraction(value: Float): Float = (value % 1f + 1f) % 1f
}
