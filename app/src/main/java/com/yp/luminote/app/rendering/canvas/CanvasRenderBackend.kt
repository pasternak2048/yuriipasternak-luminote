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

        // RenderEffect is a hardware-only primitive. Reject before compiling or drawing so an
        // effect-bearing plan can never silently fall back to the legacy Canvas path.
        if (frame.mode == RenderMode.ANIMATION && renderer.requiresHardwareEffects()) {
            if (frame.viewportWidth <= 0 || frame.viewportHeight <= 0) {
                return RenderOutcome.Unsupported("Positive viewport dimensions are required for visual effects")
            }
            if (!canvas.isHardwareAccelerated) {
                return RenderOutcome.Unsupported("Canvas hardware acceleration is required for visual effects")
            }
        }

        return try {
            when (frame.mode) {
                RenderMode.ANIMATION -> renderer.drawPlan(
                    canvas = canvas,
                    frame = frame
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
        renderer.dispose()
    }

    internal fun replaceVisualPlanForTest(plan: com.yp.luminote.app.animation.plan.VisualPlanSpec) {
        renderer.replaceVisualPlanForTest(plan)
    }

    override fun updateDebugVisualPlan(plan: com.yp.luminote.app.animation.plan.VisualPlanSpec) {
        renderer.replaceVisualPlanForTest(plan)
    }
}
