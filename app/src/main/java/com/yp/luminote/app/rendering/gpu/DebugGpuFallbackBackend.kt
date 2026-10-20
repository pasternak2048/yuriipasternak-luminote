package com.yp.luminote.app.rendering.gpu

import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.animation.plan.VisualPlanSpec
import com.yp.luminote.app.rendering.api.HaloRenderBackend
import com.yp.luminote.app.rendering.api.HaloRenderTarget
import com.yp.luminote.app.rendering.api.RenderFrame
import com.yp.luminote.app.rendering.api.RenderOutcome

/** Host-level policy: a GPU failure is never reported as GPU success after Canvas fallback. */
internal class DebugGpuFallbackBackend(
    private val gpu: HaloRenderBackend,
    private val canvas: HaloRenderBackend,
    initialConfig: HaloConfig,
    private val gpuGeneration: Long? = null,
    private val onCanvasFallback: ((reason: String, canvasRendered: Boolean) -> Unit)? = null
) : HaloRenderBackend {
    init {
        // Selection may happen between frames. Both backends must have the current plan before
        // the first draw, otherwise a newly selected witness silently falls through to Canvas.
        updateConfig(initialConfig)
    }
    override fun updateConfig(config: HaloConfig) {
        gpu.updateConfig(config)
        canvas.updateConfig(config)
    }

    override fun updateDebugVisualPlan(plan: VisualPlanSpec) {
        gpu.updateDebugVisualPlan(plan)
        canvas.updateDebugVisualPlan(plan)
    }

    override fun invalidateSurface() {
        gpu.invalidateSurface()
        canvas.invalidateSurface()
    }

    override fun render(target: HaloRenderTarget, frame: RenderFrame): RenderOutcome {
        return when (val gpuOutcome = gpu.render(target, frame)) {
            RenderOutcome.Rendered -> gpuOutcome
            is RenderOutcome.Unsupported -> fallback(target, frame, "unsupported:${gpuOutcome.reason}")
            is RenderOutcome.Error -> if (gpuOutcome.fallbackEligible) {
                val classified = (gpuOutcome.cause as? GpuPulsePreSubmitFailure)?.classification
                    ?: "pre-submit-error:${gpuOutcome.cause.javaClass.simpleName}"
                fallback(target, frame, classified)
            } else {
                GpuWitnessDiagnostics.record("post-submit-error", gpuOutcome.cause.javaClass.simpleName)
                gpuOutcome
            }
        }
    }

    private fun fallback(target: HaloRenderTarget, frame: RenderFrame, reason: String): RenderOutcome {
        GpuWitnessDiagnostics.record("canvas-fallback", reason)
        return canvas.render(target, frame).also { outcome ->
            val canvasRendered = outcome == RenderOutcome.Rendered
            if (canvasRendered) GpuWitnessDiagnostics.record("actual-backend", "Canvas")
            gpuGeneration?.let { generation ->
                GpuWitnessEvidence.recordCanvasFallback(generation, reason, canvasRendered)
            }
            onCanvasFallback?.invoke(reason, canvasRendered)
        }
    }

    override fun dispose() {
        gpu.dispose()
        canvas.dispose()
    }
}
