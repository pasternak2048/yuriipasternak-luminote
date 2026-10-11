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
}
