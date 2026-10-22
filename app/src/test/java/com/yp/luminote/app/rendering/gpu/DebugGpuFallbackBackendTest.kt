package com.yp.luminote.app.rendering.gpu

import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.animation.plan.HaloEffectVisualPlanMapper
import com.yp.luminote.app.animation.definitions.HaloEffectSpec
import com.yp.luminote.app.animation.definitions.SupernovaAnimation
import com.yp.luminote.app.rendering.api.HaloRenderBackend
import com.yp.luminote.app.rendering.api.HaloRenderTarget
import com.yp.luminote.app.rendering.api.RenderFrame
import com.yp.luminote.app.rendering.api.RenderOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugGpuFallbackBackendTest {
    @Test
    fun `selection configures both backends before the first render`() {
        val gpu = RecordingBackend(RenderOutcome.Rendered)
        val canvas = RecordingBackend(RenderOutcome.Rendered)
        val config = HaloConfig()

        DebugGpuFallbackBackend(gpu, canvas, config)

        assertEquals(listOf("config"), gpu.events)
        assertEquals(listOf("config"), canvas.events)
    }

    @Test
    fun `unsupported GPU execution uses Canvas outside GPU success`() {
        val gpu = RecordingBackend(RenderOutcome.Unsupported("unsupported operation"))
        val canvas = RecordingBackend(RenderOutcome.Rendered)
        val backend = DebugGpuFallbackBackend(gpu, canvas, HaloConfig())

        assertEquals(RenderOutcome.Rendered, backend.render(Target, RenderFrame()))
        assertEquals(listOf("config", "render"), gpu.events)
        assertEquals(listOf("config", "render"), canvas.events)
    }

    @Test
    fun `selected debug plan is forwarded to both executors before fallback`() {
        val gpu = RecordingBackend(RenderOutcome.Unsupported("selected modifier unsupported"))
        val canvas = RecordingBackend(RenderOutcome.Rendered)
        val backend = DebugGpuFallbackBackend(gpu, canvas, HaloConfig())
        val modifierPlan = HaloEffectVisualPlanMapper.map(
            HaloConfig(
                motion = SupernovaAnimation.id,
                effectPlan = HaloEffectSpec.Supernova
            )
        )

        backend.updateDebugVisualPlan(modifierPlan)
        backend.render(Target, RenderFrame())

        assertEquals(listOf("config", "plan", "render"), gpu.events)
        assertEquals(listOf("config", "plan", "render"), canvas.events)
    }

    @Test
    fun `GPU success does not invoke Canvas and disposal reaches both backends`() {
        val gpu = RecordingBackend(RenderOutcome.Rendered)
        val canvas = RecordingBackend(RenderOutcome.Rendered)
        val backend = DebugGpuFallbackBackend(gpu, canvas, HaloConfig())

        backend.render(Target, RenderFrame())
        backend.invalidateSurface()
        backend.dispose()

        assertEquals(listOf("config", "render", "surface", "dispose"), gpu.events)
        assertEquals(listOf("config", "surface", "dispose"), canvas.events)
    }

    @Test
    fun `post submission GPU error is not composited with Canvas`() {
        val gpu = RecordingBackend(RenderOutcome.Error(IllegalStateException("partially submitted"), fallbackEligible = false))
        val canvas = RecordingBackend(RenderOutcome.Rendered)
        val backend = DebugGpuFallbackBackend(gpu, canvas, HaloConfig())

        val result = backend.render(Target, RenderFrame())

        assertEquals(RenderOutcome.Error(IllegalStateException("partially submitted"), fallbackEligible = false).fallbackEligible,
            (result as RenderOutcome.Error).fallbackEligible)
        assertEquals(listOf("config", "render"), gpu.events)
        assertEquals(listOf("config"), canvas.events)
    }

    @Test
    fun `draw submission boundary error is never overdrawn by Canvas`() {
        // The GPU executor marks the boundary immediately before Canvas.drawRect. A drawRect
        // exception is therefore conservatively post-submission, even when the framework cannot
        // tell whether it wrote a partial shader result first.
        val gpu = RecordingBackend(RenderOutcome.Error(IllegalStateException("drawRect failed"), fallbackEligible = false))
        val canvas = RecordingBackend(RenderOutcome.Rendered)
        val backend = DebugGpuFallbackBackend(gpu, canvas, HaloConfig())

        val result = backend.render(Target, RenderFrame())

        assertEquals(false, (result as RenderOutcome.Error).fallbackEligible)
        assertEquals(listOf("config"), canvas.events)
    }

    @Test
    fun `pre submission GPU error falls back to Canvas`() {
        val gpu = RecordingBackend(RenderOutcome.Error(IllegalStateException("uniform setup"), fallbackEligible = true))
        val canvas = RecordingBackend(RenderOutcome.Rendered)
        val backend = DebugGpuFallbackBackend(gpu, canvas, HaloConfig())

        assertEquals(RenderOutcome.Rendered, backend.render(Target, RenderFrame()))
        assertEquals(listOf("config", "render"), gpu.events)
        assertEquals(listOf("config", "render"), canvas.events)
    }

    @Test
    fun `Pulse pre submission failure retains its stage in fallback diagnostics`() {
        val gpu = RecordingBackend(
            RenderOutcome.Error(
                GpuPulsePreSubmitFailure.at("uniform-color", IllegalStateException("uniform missing"))
            )
        )
        val canvas = RecordingBackend(RenderOutcome.Rendered)
        var reason: String? = null
        val backend = DebugGpuFallbackBackend(
            gpu = gpu,
            canvas = canvas,
            initialConfig = HaloConfig(),
            onCanvasFallback = { fallback, _ -> reason = fallback }
        )

        backend.render(Target, RenderFrame())

        assertEquals("pre-submit-error:uniform-color:IllegalStateException", reason)
        assertTrue(canvas.events.contains("render"))
    }

    private object Target : HaloRenderTarget

    private class RecordingBackend(private val outcome: RenderOutcome) : HaloRenderBackend {
        val events = mutableListOf<String>()
        override fun updateConfig(config: HaloConfig) { events += "config" }
        override fun updateDebugVisualPlan(plan: com.yp.luminote.app.animation.plan.VisualPlanSpec) { events += "plan" }
        override fun invalidateSurface() { events += "surface" }
        override fun render(target: HaloRenderTarget, frame: RenderFrame): RenderOutcome {
            events += "render"
            return outcome
        }
        override fun dispose() { events += "dispose" }
    }
}
