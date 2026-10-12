package com.yp.luminote.app.rendering

import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.rendering.api.HaloRenderBackend
import com.yp.luminote.app.rendering.api.HaloRenderTarget
import com.yp.luminote.app.rendering.api.RenderFrame
import com.yp.luminote.app.rendering.api.RenderOutcome

/**
 * Small lifecycle facade between the host and a replaceable rendering backend. It owns neither
 * timing nor targets; those remain respectively with HaloView and each render call.
 */
internal class HaloRenderingPipeline(
    initialBackend: HaloRenderBackend,
    initialConfig: HaloConfig = HaloConfig(),
    private val canvasFallback: ((HaloConfig) -> HaloRenderBackend)? = null
) {
    private var backend = initialBackend
    private var disposed = false
    private var canvasLatched = false
    /** Captured for an early GPU failure before the host has issued its first update. */
    private var currentConfig = initialConfig

    fun updateConfig(config: HaloConfig) {
        currentConfig = config
        if (!disposed) backend.updateConfig(currentConfig)
    }

    fun invalidateSurface() {
        if (!disposed) backend.invalidateSurface()
    }

    fun render(target: HaloRenderTarget, frame: RenderFrame): RenderOutcome {
        if (disposed) return RenderOutcome.Unsupported("Rendering pipeline is disposed")
        return when (val outcome = backend.render(target, frame)) {
            is RenderOutcome.FallbackToCanvas -> failOverToCanvas(target, frame, outcome)
            else -> outcome
        }
    }

    private fun failOverToCanvas(
        target: HaloRenderTarget,
        frame: RenderFrame,
        failure: RenderOutcome.FallbackToCanvas
    ): RenderOutcome {
        if (canvasLatched) return RenderOutcome.Error(failure.cause)
        val fallback = canvasFallback ?: return RenderOutcome.Error(failure.cause)
        canvasLatched = true
        backend.dispose()
        backend = fallback(currentConfig)
        backend.updateConfig(currentConfig)
        // Same target and frame: no host callback, animation, or lifecycle restart.
        return backend.render(target, frame)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        backend.dispose()
    }
}
