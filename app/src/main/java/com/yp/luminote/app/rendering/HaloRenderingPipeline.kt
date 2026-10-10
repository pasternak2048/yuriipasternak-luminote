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
    private val backend: HaloRenderBackend
) {
    private var disposed = false

    fun updateConfig(config: HaloConfig) {
        if (!disposed) backend.updateConfig(config)
    }

    fun invalidateSurface() {
        if (!disposed) backend.invalidateSurface()
    }

    fun render(target: HaloRenderTarget, frame: RenderFrame): RenderOutcome =
        if (disposed) RenderOutcome.Unsupported("Rendering pipeline is disposed")
        else backend.render(target, frame)

    fun dispose() {
        if (disposed) return
        disposed = true
        backend.dispose()
    }
}
