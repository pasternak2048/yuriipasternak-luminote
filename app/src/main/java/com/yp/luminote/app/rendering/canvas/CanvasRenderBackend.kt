package com.yp.luminote.app.rendering.canvas

import android.graphics.Canvas
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.rendering.api.HaloRenderBackend
import com.yp.luminote.app.rendering.api.HaloRenderTarget
import com.yp.luminote.app.rendering.api.RenderFrame
import com.yp.luminote.app.rendering.api.RenderMode
import com.yp.luminote.app.rendering.api.RenderOutcome

/** Per-draw Canvas adapter. HaloView owns and reuses it; the backend never retains it. */
internal class CanvasRenderTarget(var canvas: Canvas? = null) : HaloRenderTarget {
    inline fun <T> withCanvas(canvas: Canvas, block: () -> T): T {
        this.canvas = canvas
        return try {
            block()
        } finally {
            this.canvas = null
        }
    }
}

/** Production adapter which keeps the existing Canvas renderer, dispatcher and caches intact. */
internal class CanvasRenderBackend(
    config: HaloConfig,
    outline: DisplayOutline
) : HaloRenderBackend {
    private val renderer = HaloRenderer(config, outline)
    private var disposed = false

    override fun updateConfig(config: HaloConfig) {
        if (!disposed) renderer.update(config)
    }

    override fun invalidateSurface() {
        if (!disposed) renderer.invalidateSurface()
    }

    override fun render(target: HaloRenderTarget, frame: RenderFrame): RenderOutcome {
        if (disposed) return RenderOutcome.Unsupported("Canvas backend is disposed")
        val canvas = (target as? CanvasRenderTarget)?.canvas
            ?: return RenderOutcome.Unsupported("Canvas backend requires a CanvasRenderTarget")

        return try {
            when (frame.mode) {
                RenderMode.ANIMATION -> renderer.draw(
                    canvas = canvas,
                    animationProgress = frame.animationProgress,
                    effectPhase = frame.effectPhase,
                    gradientPhase = frame.gradientPhase,
                    frameTimeNanos = frame.frameTimeNanos
                )

                RenderMode.STATIC_CALIBRATION -> {
                    renderer.drawStaticFrame(canvas)
                    if (CalibrationDiagnostics.shouldRender(frame.mode == RenderMode.STATIC_CALIBRATION, frame.viewportWidth, frame.viewportHeight)) {
                        renderer.drawCalibrationDiagnostics(
                            canvas,
                            frame.calibrationRulerLegend,
                            frame.calibrationRegistrationLabel
                        )
                    }
                }
            }
            RenderOutcome.Rendered
        } catch (error: RuntimeException) {
            RenderOutcome.Error(error)
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
    }
}
