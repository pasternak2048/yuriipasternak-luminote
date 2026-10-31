package com.yp.luminote.app.rendering.gpu

import android.graphics.RuntimeShader
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ActivityScenario
import com.yp.luminote.app.MainActivity
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.overlay.host.HaloView
import com.yp.luminote.app.rendering.api.*
import com.yp.luminote.app.rendering.canvas.CanvasRenderTarget
import com.yp.luminote.app.resonance.*
import com.yp.luminote.app.data.settings.LuminoteSettingsRepository
import com.yp.luminote.app.data.settings.RenderingEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GpuResonanceShaderCompilationTest {
    @Test fun fluidContourCompilesOnDevice() { RuntimeShader(GpuResonanceRenderBackend.FLUID_SHADER) }
    @Test fun softwareTargetFailsClosed() {
        val generation = ResonanceRuntime.begin()
        val backend = GpuResonanceRenderBackend(DisplayOutline(1f), generation)
        try {
            backend.updateConfig(HaloConfig())
            assertTrue(backend.render(CanvasRenderTarget(Canvas()), RenderFrame()) is RenderOutcome.Unsupported)
            assertEquals(0L, ResonanceRuntime.gpuFrames)
        } finally { backend.dispose(); ResonanceRuntime.end(generation); ResonanceRuntime.cleanupComplete(generation) }
    }
    @Test fun hostSubmitsGpuFramesAndRecreatesResourcesWithoutCanvasExecution() {
        // Silence fixture validates GPU submission/resource lifecycle, never playback compatibility or pixels.
        val generation = ResonanceRuntime.begin()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var view: HaloView? = null
        var failure: String? = null
        try {
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true); activity.setTurnScreenOn(true)
                view = HaloView(activity, HaloConfig()).also { host ->
                    host.startResonance(generation) { failure = it }
                    activity.setContentView(host)
                }
            }
            awaitFrames(10)
            scenario.onActivity { activity -> activity.setContentView(View(activity)) }
            val before = ResonanceRuntime.gpuFrames
            scenario.onActivity { activity -> activity.setContentView(view!!) }
            awaitFrames(before + 10)
            assertNull(failure)
            assertEquals(0L, view!!.canvasRenderedFramesForDebug())
        } finally { scenario.close(); ResonanceRuntime.end(generation); ResonanceRuntime.cleanupComplete(generation) }
    }
    @Test fun exitingResonanceRestoresLiveRenderingPreferenceObserverOnReusedView() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LuminoteSettingsRepository(context)
        val original = runBlocking { repository.settings.first() }
        val generation = ResonanceRuntime.begin()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var view: HaloView? = null
        try {
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true); activity.setTurnScreenOn(true)
                view = HaloView(activity, HaloConfig()).also { host ->
                    host.startResonance(generation) { fail(it) }; activity.setContentView(host)
                }
            }
            awaitFrames(3)
            runBlocking { repository.saveSettings(original.copy(renderingEngine = RenderingEngine.GPU)) }
            scenario.onActivity { view!!.stopResonance(); view!!.showStaticFrame() }
            awaitPreference(RenderingEngine.GPU)
            runBlocking { repository.saveSettings(original.copy(renderingEngine = RenderingEngine.CANVAS)) }
            awaitPreference(RenderingEngine.CANVAS)
        } finally {
            scenario.close(); ResonanceRuntime.end(generation); ResonanceRuntime.cleanupComplete(generation)
            runBlocking { repository.saveSettings(original) }
        }
    }
    private fun awaitPreference(engine: RenderingEngine) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline && ProductionRenderEvidence.snapshot?.requested != engine) Thread.sleep(20)
        assertEquals(engine, ProductionRenderEvidence.snapshot?.requested)
    }
    private fun awaitFrames(count: Long) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline && ResonanceRuntime.gpuFrames < count) Thread.sleep(20)
        assertTrue("GPU host frames >= $count, actual=${ResonanceRuntime.gpuFrames}", ResonanceRuntime.gpuFrames >= count)
    }
}
