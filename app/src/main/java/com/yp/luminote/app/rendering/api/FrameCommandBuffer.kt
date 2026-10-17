package com.yp.luminote.app.rendering.api

import com.yp.luminote.app.animation.plan.GeometryRequest
import com.yp.luminote.app.animation.plan.AppearancePayload
import com.yp.luminote.app.animation.plan.PrimaryVisualOperation
import com.yp.luminote.app.animation.plan.VisualEmitterSpec
import com.yp.luminote.app.animation.plan.LuminousSegmentAppearance
import com.yp.luminote.app.animation.plan.VisualModifier
import com.yp.luminote.app.animation.plan.VisualPlanSpec
import com.yp.luminote.app.animation.plan.intensityPulseMultiplier
import kotlin.math.roundToInt

/**
 * UI-thread-confined reusable command storage. Its entries borrow immutable plan
 * data and host frame values; callers must consume it before compiling again.
 */
internal class FrameCommandBuffer {
    private val commands = ArrayList<FrameVisualCommand>()
    var size: Int = 0
        private set

    operator fun get(index: Int): FrameVisualCommand {
        check(index in 0 until size)
        return commands[index]
    }

    internal fun next(): FrameVisualCommand {
        val command = commands.getOrNull(size) ?: FrameVisualCommand().also(commands::add)
        size++
        return command
    }

    internal fun clear() {
        size = 0
    }
}

internal class FrameVisualCommand {
    lateinit var geometry: GeometryRequest
    lateinit var appearance: AppearancePayload
    lateinit var operation: PrimaryVisualOperation
    lateinit var modifiers: List<VisualModifier>
    var luminousSegmentAppearance: LuminousSegmentAppearance = LuminousSegmentAppearance.DEFAULT
    var phase: Float = 0f
    var gradientPhase: Float = 0f
    var animationProgress: Float = 0f
    var alpha: Int = 0
    /** Pulse is compiled once with the command so specialized sources can consume it directly. */
    var intensityPulseMultiplier: Float = 1f
    var frameTimeNanos: Long = 0L
}

/** Stateless compiler; ordering exactly follows VisualPlanSpec.emitters. */
internal object FrameCommandCompiler {
    fun compile(plan: VisualPlanSpec, frame: RenderFrame, out: FrameCommandBuffer): FrameCommandBuffer {
        out.clear()
        var index = 0
        while (index < plan.emitters.size) {
            append(plan.emitters[index], frame, out.next())
            index++
        }
        return out
    }

    private fun append(emitter: VisualEmitterSpec, frame: RenderFrame, out: FrameVisualCommand) {
        out.geometry = emitter.geometry
        out.appearance = emitter.appearance
        out.operation = emitter.operation
        out.modifiers = emitter.modifiers
        out.luminousSegmentAppearance = emitter.luminousSegmentAppearance
        out.phase = emitter.transform.phaseFor(frame.effectPhase)
        out.gradientPhase = emitter.transform.phaseFor(frame.gradientPhase)
        out.animationProgress = frame.animationProgress
        out.intensityPulseMultiplier = intensityPulseMultiplier(emitter.modifiers, frame.frameTimeNanos)
        out.alpha = resolvedAlpha(emitter, frame.animationProgress, out.intensityPulseMultiplier)
        out.frameTimeNanos = frame.frameTimeNanos
    }

    private fun resolvedAlpha(emitter: VisualEmitterSpec, progress: Float, pulseMultiplier: Float): Int {
        var opacityMultiplier = 1f
        // Avoid a filtered list/fold allocation on every host frame.
        var index = 0
        while (index < emitter.modifiers.size) {
            val modifier = emitter.modifiers[index]
            when (modifier) {
                is VisualModifier.Opacity -> opacityMultiplier *= modifier.multiplier
                is VisualModifier.IntensityPulse -> Unit
                is VisualModifier.Blur, is VisualModifier.Glow -> Unit
            }
            index++
        }
        return (255f * emitter.appearance.intensity * progress.coerceIn(0f, 1f) *
            opacityMultiplier * pulseMultiplier)
            .roundToInt().coerceIn(0, 255)
    }

}
