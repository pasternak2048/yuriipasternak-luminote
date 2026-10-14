package com.yp.luminote.app.rendering.canvas

import android.graphics.Canvas
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.animation.definitions.HaloEffectSpec
import com.yp.luminote.app.animation.plan.HaloEffectVisualPlanMapper
import com.yp.luminote.app.animation.plan.VisualPlanSpec
import com.yp.luminote.app.animation.plan.VisualModifier
import com.yp.luminote.app.rendering.api.HaloRenderTarget
import com.yp.luminote.app.rendering.api.RenderFrame
import com.yp.luminote.app.rendering.api.RenderOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasRenderBackendTest {
    @Test
    fun `canvas target clears the per-draw Canvas after normal and throwing render paths`() {
        val target = CanvasRenderTarget()

        target.withCanvas(Canvas()) {
            assertNotNullCanvas(target)
        }
        assertNull(target.canvas)

        runCatching {
            target.withCanvas(Canvas()) {
                error("expected")
            }
        }
        assertNull(target.canvas)
    }

    @Test
    fun `canvas backend reports invalid targets and is a no-op after disposal`() {
        val backend = CanvasRenderBackend(HaloConfig(), DisplayOutline(density = 1f))
        val frame = RenderFrame()

        assertEquals(
            RenderOutcome.Unsupported("Canvas backend requires a CanvasRenderTarget"),
            backend.render(FakeTarget(), frame)
        )

        backend.dispose()
        backend.updateConfig(HaloConfig(intensity = 0.5f))
        backend.invalidateSurface()

        assertEquals(
            RenderOutcome.Unsupported("Canvas backend is disposed"),
            backend.render(CanvasRenderTarget(Canvas()), frame)
        )
    }

    @Test
    fun `effect plan preflight rejects zero viewport and software Canvas before drawing`() {
        val config = HaloConfig(effectPlan = HaloEffectSpec.FullContour)
        val backend = CanvasRenderBackend(config, DisplayOutline(density = 1f))
        val emitter = HaloEffectVisualPlanMapper.map(config).emitters.single()
        backend.replaceVisualPlanForTest(
            VisualPlanSpec(listOf(emitter.copy(modifiers = listOf(VisualModifier.Blur(2f), VisualModifier.Glow(4f)))))
        )
        val frame = RenderFrame()

        assertEquals(
            RenderOutcome.Unsupported("Positive viewport dimensions are required for visual effects"),
            backend.render(CanvasRenderTarget(Canvas()), frame)
        )

        frame.viewportWidth = 320
        frame.viewportHeight = 640
        assertEquals(
            RenderOutcome.Unsupported("Canvas hardware acceleration is required for visual effects"),
            backend.render(CanvasRenderTarget(Canvas()), frame)
        )
    }

    private fun assertNotNullCanvas(target: CanvasRenderTarget) {
        check(target.canvas != null)
    }

    private class FakeTarget : HaloRenderTarget
}
