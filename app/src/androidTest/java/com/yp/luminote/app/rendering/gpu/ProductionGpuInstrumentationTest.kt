package com.yp.luminote.app.rendering.gpu

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.FrameMetrics
import android.view.View
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.yp.luminote.app.MainActivity
import com.yp.luminote.app.animation.definitions.*
import com.yp.luminote.app.data.settings.*
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.overlay.host.HaloView
import com.yp.luminote.app.rendering.api.*
import com.yp.luminote.app.rendering.canvas.CanvasRenderTarget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in matched hardware workload; measures frame timings rather than treating submission as speed. */
class ProductionGpuInstrumentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun enabled() = assumeTrue("Gate F opt-in", InstrumentationRegistry.getArguments().getString("productionGpuGateF") == "true")

    @Test fun persistedPreferenceRoutesLiveHostAndSurvivesDetachReattach() {
        enabled()
        val repository = LuminoteSettingsRepository(instrumentation.targetContext)
        val original = runBlocking { repository.settings.first() }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity(::showTestWindow)
        var view: HaloView? = null
        try {
            runBlocking { repository.saveSettings(original.copy(renderingEngine = RenderingEngine.GPU)) }
            assertEquals(RenderingEngine.GPU, runBlocking { LuminoteSettingsRepository(instrumentation.targetContext).settings.first() }.renderingEngine)
            scenario.onActivity { activity ->
                view = HaloView(activity, HaloConfig(motion = ImpulseAnimation.id)).also {
                    activity.setContentView(it)
                    it.repeatAnimation(2.5f, 0f, 0, ImpulseAnimation.id)
                }
            }
            await { ProductionRenderEvidence.snapshot?.let { it.requested == RenderingEngine.GPU && it.actual == "GPU" && it.submitted > 2 && it.fallbacks == 0L } == true }
            val first = ProductionRenderEvidence.snapshot!!.generation
            scenario.onActivity { activity -> activity.setContentView(View(activity)) }
            assertTrue(ProductionRenderEvidence.snapshot!!.disposed)
            scenario.onActivity { activity ->
                activity.setContentView(view!!)
                view!!.repeatAnimation(2.5f, 0f, 0, ImpulseAnimation.id)
            }
            await { ProductionRenderEvidence.snapshot?.let { it.generation > first && it.actual == "GPU" && it.submitted > 2 } == true }
            val second = ProductionRenderEvidence.snapshot!!.generation
            runBlocking { repository.saveSettings(original.copy(renderingEngine = RenderingEngine.CANVAS)) }
            await { ProductionRenderEvidence.snapshot?.let { it.generation > second && it.requested == RenderingEngine.CANVAS && it.actual == "Canvas" } == true }
        } finally { scenario.close(); runBlocking { repository.saveSettings(original) } }
    }

    @Test fun matchedCanvasGpuFrameMeasurements() {
        enabled()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity(::showTestWindow)
        val report = StringBuilder("Matched production executor workload; 120 frames per family/backend; one pass, no thermal/performance superiority inference. First CPU render call includes setup but is not GPU completion. FrameMetrics includes window startup; warmed distribution discards first 5 samples. PSS is process-wide, without GC control, not allocation attribution.\n")
        try {
            val families = listOf(PulseAnimation.id, SnakeAnimation.id, ImpulseAnimation.id, SupernovaAnimation.id)
            for ((index, family) in families.withIndex()) {
                val engines = if (index % 2 == 0) RenderingEngine.entries else RenderingEngine.entries.reversed()
                for (engine in engines) {
                    val total = mutableListOf<Long>()
                    var fixture: MeasuredView? = null
                    var listener: Window.OnFrameMetricsAvailableListener? = null
                    val pssBefore = Debug.getPss()
                    scenario.onActivity { activity ->
                        listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                            synchronized(total) { total.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION)) }
                        }
                        activity.window.addOnFrameMetricsAvailableListener(listener!!, Handler(Looper.getMainLooper()))
                        fixture = MeasuredView(activity, HaloConfig(motion = family), engine)
                        activity.setContentView(fixture!!)
                    }
                    val finished = fixture!!.done.await(30, TimeUnit.SECONDS)
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener!!) }
                    val view = fixture!!
                    val evidence = ProductionRenderEvidence.snapshot!!
                    val raw = synchronized(total) { total.toList() }
                    val budget = view.budgetNanos
                    val sorted = raw.sorted()
                    fun percentile(p: Double) = if (sorted.isEmpty()) -1L else sorted[((sorted.size - 1) * p).toInt()]
                    val warmed = raw.drop(5).sorted()
                    val warmedP95 = if (warmed.isEmpty()) -1L else warmed[((warmed.size - 1) * .95).toInt()]
                    report.appendLine("family=$family engine=$engine viewport=${view.width}x${view.height} frames=${view.draws} metrics=${raw.size} budgetNs=$budget firstCpuRenderCallNs=${view.coldDrawNanos} p50Ns=${percentile(.5)} p95Ns=${percentile(.95)} p99Ns=${percentile(.99)} warmedAfter5P95Ns=$warmedP95 missedBudget=${raw.count { it > budget }} processPssBeforeKb=$pssBefore processPssAfterKb=${Debug.getPss()} submitted=${evidence.submitted} fallback=${evidence.fallbacks}")
                    report.appendLine("totalFrameNs=${raw.joinToString(",")}")
                    report.appendLine("finished=$finished error=${view.error} evidence=${ProductionRenderEvidence.report()}")
                    assertTrue("frame workload finishes: family=$family engine=$engine draws=${view.draws} metrics=${raw.size} error=${view.error}; ${ProductionRenderEvidence.report()}", finished)
                    assertEquals(120, view.draws)
                    assertNull("No GPU error", view.error)
                    assertEquals(if (engine == RenderingEngine.GPU) "GPU" else "Canvas", evidence.actual)
                    assertEquals(0L, evidence.fallbacks)
                    scenario.onActivity { activity -> activity.setContentView(View(activity)) }
                }
            }
        } finally {
            val file = File(instrumentation.targetContext.getExternalFilesDir(null), "gpu-production-frame-metrics.txt")
            file.writeText(report.toString())
            Log.i("HaloGpuPerformance", "${file.absolutePath}\n$report")
            scenario.close()
        }
    }

    private class MeasuredView(activity: Activity, config: HaloConfig, engine: RenderingEngine) : View(activity) {
        private val outline = DisplayOutline(resources.displayMetrics.density)
        private val backend = ProductionRenderBackend(config, outline, engine)
        private val frame = RenderFrame().apply { animationProgress = 1f }
        val done = CountDownLatch(1)
        var draws = 0
        var coldDrawNanos = 0L
        var error: String? = null
        val budgetNanos get() = (1_000_000_000.0 / (display?.refreshRate ?: 60f)).toLong()
        init { setBackgroundColor(Color.BLACK); keepScreenOn = true }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            outline.resize(w, h); backend.invalidateSurface()
            frame.viewportWidth = w; frame.viewportHeight = h
        }
        override fun onDraw(canvas: Canvas) {
            if (draws >= 120) return
            frame.effectPhase = (draws % 60) / 60f
            frame.frameTimeNanos = draws * 16_666_667L
            val started = System.nanoTime()
            val outcome = backend.render(CanvasRenderTarget(canvas), frame)
            if (draws == 0) coldDrawNanos = System.nanoTime() - started
            if (outcome != RenderOutcome.Rendered) error = outcome.toString()
            draws++
            if (draws == 119) {
                // Register before the final traversal starts, so its callback is included
                // when ViewRoot collects the frame's commit callbacks.
                postOnAnimation {
                    viewTreeObserver.registerFrameCommitCallback { done.countDown() }
                    invalidate()
                }
            } else if (draws < 120) postInvalidateOnAnimation()
        }
        override fun onDetachedFromWindow() { backend.dispose(); super.onDetachedFromWindow() }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(20)
        }
        fail("Expected production evidence; ${ProductionRenderEvidence.report()}")
    }

    private fun showTestWindow(activity: Activity) {
        // Test fixture visibility only: does not unlock or dismiss a secure keyguard.
        activity.setShowWhenLocked(true)
        activity.setTurnScreenOn(true)
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
