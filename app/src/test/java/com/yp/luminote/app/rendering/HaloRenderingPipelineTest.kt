package com.yp.luminote.app.rendering

import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.rendering.api.HaloRenderBackend
import com.yp.luminote.app.rendering.api.HaloRenderTarget
import com.yp.luminote.app.rendering.api.RenderFrame
import com.yp.luminote.app.rendering.api.RenderOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HaloRenderingPipelineTest {
    @Test
    fun `pipeline delegates deterministic lifecycle to an injected backend`() {
        val backend = RecordingBackend()
        val pipeline = HaloRenderingPipeline(backend)
        val config = HaloConfig()
        val target = FakeTarget()
        val frame = RenderFrame()

        pipeline.updateConfig(config)
        pipeline.invalidateSurface()

        assertSame(RenderOutcome.Rendered, pipeline.render(target, frame))

        pipeline.dispose()
        pipeline.dispose()

        assertEquals(listOf("config", "surface", "render", "dispose"), backend.events)
        assertSame(config, backend.config)
        assertSame(target, backend.target)
        assertSame(frame, backend.frame)
        assertEquals(RenderOutcome.Unsupported("Rendering pipeline is disposed"), pipeline.render(target, frame))
    }

    @Test
    fun `GPU backend failure falls back to Canvas once and redraws the current frame`() {
        val gpu = FailingGpuBackend()
        val canvas = RecordingBackend()
        val pipeline = HaloRenderingPipeline(gpu) { canvas }
        val target = FakeTarget()
        val frame = RenderFrame()
        val config = HaloConfig(intensity = .4f)

        pipeline.updateConfig(config)

        assertSame(RenderOutcome.Rendered, pipeline.render(target, frame))
        assertEquals(1, gpu.renderCalls)
        assertEquals(1, gpu.disposeCalls)
        assertSame(target, canvas.target)
        assertSame(frame, canvas.frame)
        assertSame(config, canvas.config)

        assertSame(RenderOutcome.Rendered, pipeline.render(target, frame))
        assertEquals(1, gpu.renderCalls)
        assertEquals(2, canvas.events.count { it == "render" })
    }

    @Test
    fun `first GPU fallback keeps the construction session config`() {
        val gpu = FailingGpuBackend()
        val canvas = RecordingBackend()
        val sessionConfig = HaloConfig(intensity = .23f)
        val pipeline = HaloRenderingPipeline(gpu, sessionConfig) { canvas }

        assertSame(RenderOutcome.Rendered, pipeline.render(FakeTarget(), RenderFrame()))
        assertSame(sessionConfig, canvas.config)
    }

    private class FakeTarget : HaloRenderTarget

    private class RecordingBackend : HaloRenderBackend {
        val events = mutableListOf<String>()
        var config: HaloConfig? = null
        var target: HaloRenderTarget? = null
        var frame: RenderFrame? = null

        override fun updateConfig(config: HaloConfig) {
            events += "config"
            this.config = config
        }

        override fun invalidateSurface() {
            events += "surface"
        }

        override fun render(target: HaloRenderTarget, frame: RenderFrame): RenderOutcome {
            events += "render"
            this.target = target
            this.frame = frame
            return RenderOutcome.Rendered
        }

        override fun dispose() {
            events += "dispose"
        }
    }

    private class FailingGpuBackend : HaloRenderBackend {
        var renderCalls = 0
        var disposeCalls = 0
        override fun updateConfig(config: HaloConfig) = Unit
        override fun invalidateSurface() = Unit
        override fun render(target: HaloRenderTarget, frame: RenderFrame): RenderOutcome {
            renderCalls++
            return RenderOutcome.FallbackToCanvas(IllegalStateException("forced GPU failure"))
        }
        override fun dispose() { disposeCalls++ }
    }
}
