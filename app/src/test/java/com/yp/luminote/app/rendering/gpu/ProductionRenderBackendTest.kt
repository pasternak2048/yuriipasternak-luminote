package com.yp.luminote.app.rendering.gpu

import com.yp.luminote.app.animation.definitions.*
import com.yp.luminote.app.animation.plan.*
import com.yp.luminote.app.data.settings.*
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.model.*
import com.yp.luminote.app.rendering.api.*
import org.junit.Assert.*
import org.junit.Test

class ProductionRenderBackendTest {
    private class Fake(var result: RenderOutcome = RenderOutcome.Rendered) : HaloRenderBackend {
        var draws = 0; var updates = 0; var disposals = 0
        override fun updateConfig(config: HaloConfig) { updates++ }
        override fun invalidateSurface() = Unit
        override fun render(target: HaloRenderTarget, frame: RenderFrame): RenderOutcome { draws++; return result }
        override fun dispose() { disposals++ }
    }
    private val target = object : HaloRenderTarget {}
    private val frame = RenderFrame()

    @Test fun `default and unknown persisted preferences retain Canvas`() {
        assertEquals(RenderingEngine.CANVAS, LuminoteSettings().renderingEngine)
        for (value in listOf(null, "", "future", "gpu")) assertEquals(RenderingEngine.CANVAS, RenderingEngine.fromStorage(value))
        assertEquals(RenderingEngine.GPU, RenderingEngine.fromStorage("GPU"))
    }

    @Test fun `all four complete normal production plans admitted while specialized and gradients preserved`() {
        for (id in listOf(PulseAnimation.id, SnakeAnimation.id, ImpulseAnimation.id, SupernovaAnimation.id)) {
            val config = HaloConfig(motion = id)
            assertNull("$id", ProductionGpuAdmission.reason(config, HaloEffectVisualPlanMapper.map(config)))
            for (mode in listOf(HaloRenderMode.LIGHT_IMPULSE, HaloRenderMode.AMBIENT)) {
                val specialized = config.copy(renderMode = mode)
                assertNotNull(ProductionGpuAdmission.reason(specialized, HaloEffectVisualPlanMapper.map(specialized)))
            }
            val gradient = config.copy(colorMode = HaloColorMode.GRADIENT)
            assertNotNull(ProductionGpuAdmission.reason(gradient, HaloEffectVisualPlanMapper.map(gradient)))
        }
    }

    @Test fun `persistent failure suppresses retries until changed plan or surface`() {
        val config = HaloConfig(motion = ImpulseAnimation.id)
        val gpu = Fake(RenderOutcome.Error(IllegalStateException("fail")))
        val canvas = Fake()
        val backend = ProductionRenderBackend(config, DisplayOutline(1f), RenderingEngine.GPU, canvas) { gpu }
        repeat(20) { assertSame(RenderOutcome.Rendered, backend.render(target, frame)); backend.updateConfig(config.copy(palette = intArrayOf())) }
        assertEquals(1, gpu.draws); assertEquals(20, canvas.draws)
        backend.invalidateSurface(); backend.render(target, frame)
        assertEquals(2, gpu.draws)
        backend.updateConfig(config.copy(intensity = .8f)); backend.render(target, frame)
        assertEquals(3, gpu.draws)
        backend.dispose(); backend.dispose(); assertEquals(1, canvas.disposals)
    }

    @Test fun `post submission failure never draws Canvas same frame but recovers next clean frame`() {
        val gpu = Fake(RenderOutcome.Error(IllegalStateException("partial"), fallbackEligible = false))
        val canvas = Fake()
        val backend = ProductionRenderBackend(HaloConfig(motion = ImpulseAnimation.id), DisplayOutline(1f), RenderingEngine.GPU, canvas) { gpu }
        assertTrue(backend.render(target, frame) is RenderOutcome.Error)
        assertEquals(0, canvas.draws)
        assertSame(RenderOutcome.Rendered, backend.render(target, frame))
        assertEquals(1, gpu.draws); assertEquals(1, canvas.draws)
        assertEquals("Canvas", ProductionRenderEvidence.snapshot!!.actual)
    }

    @Test fun `executor unsupported suppresses retries until changed plan or surface`() {
        val config = HaloConfig(motion = ImpulseAnimation.id)
        val gpu = Fake(RenderOutcome.Unsupported("unsupported geometry"))
        val canvas = Fake()
        val backend = ProductionRenderBackend(config, DisplayOutline(1f), RenderingEngine.GPU, canvas) { gpu }
        repeat(20) {
            assertSame(RenderOutcome.Rendered, backend.render(target, frame))
            backend.updateConfig(config.copy(palette = intArrayOf()))
        }
        assertEquals(1, gpu.draws)
        assertEquals(20, canvas.draws)
        assertEquals("unsupported:unsupported geometry", ProductionRenderEvidence.snapshot!!.reason)
        backend.invalidateSurface()
        backend.render(target, frame)
        assertEquals(2, gpu.draws)
        gpu.result = RenderOutcome.Rendered
        backend.updateConfig(config.copy(intensity = .8f))
        backend.render(target, frame)
        assertEquals(3, gpu.draws)
        assertEquals(21, canvas.draws)
        assertEquals("GPU", ProductionRenderEvidence.snapshot!!.actual)
        assertNull(ProductionRenderEvidence.snapshot!!.reason)
    }

    @Test fun `Canvas preference and unsupported full plans do not create GPU`() {
        for (engine in RenderingEngine.entries) {
            val config = HaloConfig(motion = ImpulseAnimation.id, renderMode = HaloRenderMode.LIGHT_IMPULSE)
            val canvas = Fake()
            val backend = ProductionRenderBackend(config, DisplayOutline(1f), engine, canvas) { error("must not create GPU") }
            backend.render(target, frame)
            assertEquals(1, canvas.draws)
            assertEquals("Canvas", ProductionRenderEvidence.snapshot!!.actual)
        }
    }

    @Test fun `initialization throw falls back while unstructured render throw waits for next frame`() {
        val config = HaloConfig(motion = ImpulseAnimation.id)
        val canvas = Fake()
        var attempts = 0
        val setup = ProductionRenderBackend(config, DisplayOutline(1f), RenderingEngine.GPU, canvas) {
            attempts++; throw IllegalArgumentException("initialization")
        }
        repeat(3) { assertSame(RenderOutcome.Rendered, setup.render(target, frame)) }
        assertEquals(1, attempts); assertEquals(3, canvas.draws)
        val throwing = object : HaloRenderBackend {
            override fun updateConfig(config: HaloConfig) = Unit
            override fun invalidateSurface() = Unit
            override fun dispose() = Unit
            override fun render(target: HaloRenderTarget, frame: RenderFrame): RenderOutcome = throw IllegalStateException("unknown draw state")
        }
        val nextCanvas = Fake()
        val runtime = ProductionRenderBackend(config, DisplayOutline(1f), RenderingEngine.GPU, nextCanvas) { throwing }
        assertTrue(runtime.render(target, frame) is RenderOutcome.Error)
        assertEquals(0, nextCanvas.draws)
        assertSame(RenderOutcome.Rendered, runtime.render(target, frame))
        assertEquals(1, nextCanvas.draws)
    }
}
