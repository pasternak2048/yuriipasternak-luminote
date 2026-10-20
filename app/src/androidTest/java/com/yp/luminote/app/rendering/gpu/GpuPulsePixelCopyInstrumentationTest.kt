package com.yp.luminote.app.rendering.gpu

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yp.luminote.app.MainActivity
import com.yp.luminote.app.animation.plan.HaloEffectVisualPlanMapper
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.overlay.host.HaloView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** Opt-in Gate B physical proof. It freezes the shared command frame before each matched capture. */
@RunWith(AndroidJUnit4::class)
class GpuPulsePixelCopyInstrumentationTest {
    @Test fun `GPU Pulse matches frozen Canvas contour and recreates resources`() {
        assumeTrue("Gate B PixelCopy is opt-in; pass -e gpuPulseGateB true", args().getString(ARG) == "true")
        val config = HaloConfig()
        val frozen = HaloView.DebugFrozenFrame(progress = 1f, phase = 0f, gradientPhase = 0f, frameTimeNanos = 0L)
        var activity: Activity? = null; var view: HaloView? = null
        val scenario = ActivityScenario.launch<MainActivity>(Intent(InstrumentationRegistry.getInstrumentation().targetContext, MainActivity::class.java))
        fun install(gpu: Boolean) = scenario.onActivity { host ->
            HaloView(host, config).also { candidate ->
                host.setContentView(candidate)
                candidate.replaceVisualPlanForDebug(HaloEffectVisualPlanMapper.map(config))
                candidate.setFrozenRenderFrameForDebug(frozen)
                if (gpu) assertTrue(candidate.setGpuPulseEnabledForDebug(true))
                activity = host; view = candidate
            }
        }
        try {
            GpuPulseEvidence.resetForTest()
            install(gpu = false)
            val canvas = copy(activity!!, view!!)
            val before = GpuPulseEvidence.latestGeneration() ?: 0L
            scenario.onActivity { assertTrue(view!!.setGpuPulseEnabledForDebug(true)) }
            val generation = (GpuPulseEvidence.latestGeneration() ?: before).also { assertTrue("new GPU generation", it > before) }
            val evidence = GpuPulseEvidence.awaitSubmitted(generation, TIMEOUT) ?: error("GPU Pulse did not submit")
            val gpu = copy(activity!!, view!!)
            assertEquals("admitted GPU Pulse has no fallback", 0, evidence.fallbacks)
            assertEquals("admitted GPU Pulse never executes Canvas Pulse", 0, evidence.canvasPulseCalls)
            // This is the shared compiler contract for an unmodified FullContourPulse at progress=1:
            // alpha = round(255 * appearance intensity). The default config intensity is .7, not 1.
            assertEquals("frozen compiled command alpha is consumed", (255f * config.intensity).roundToInt(), evidence.lastAlpha)
            assertContourAndTransparency(canvas, gpu)
            scenario.onActivity {
                val calibrated = config.copy(edgeCalibrationDp = config.edgeCalibrationDp + 3f)
                view!!.update(calibrated)
                view!!.replaceVisualPlanForDebug(HaloEffectVisualPlanMapper.map(calibrated))
            }
            val calibratedEvidence = GpuPulseEvidence.awaitSubmissionCount(generation, evidence.submissions + 1, TIMEOUT) ?: error("calibrated GPU Pulse did not submit")
            assertTrue("calibration mutation rebuilds field", calibratedEvidence.resourceInitializations > evidence.resourceInitializations)
            assertEquals("calibrated generation has no Canvas fallback", 0, calibratedEvidence.fallbacks)
            assertEquals("calibrated GPU Pulse never executes Canvas Pulse", 0, calibratedEvidence.canvasPulseCalls)
            scenario.recreate()
            install(gpu = true)
            val recreated = GpuPulseEvidence.latestGeneration() ?: error("missing recreated GPU generation")
            assertTrue("recreate gets fresh GPU generation", recreated > generation)
            val recreatedEvidence = GpuPulseEvidence.awaitSubmitted(recreated, TIMEOUT) ?: error("recreated GPU Pulse did not submit")
            assertEquals("recreated generation has no Canvas fallback", 0, recreatedEvidence.fallbacks)
            assertEquals("recreated GPU Pulse never executes Canvas Pulse", 0, recreatedEvidence.canvasPulseCalls)
            val disposed = GpuPulseEvidence.snapshot(generation)!!
            assertTrue("old generation disposed", disposed.disposals > 0)
            assertTrue("old field disposed", disposed.fieldDisposals > 0)
        } finally { scenario.close() }
    }

    private fun assertContourAndTransparency(canvas: Bitmap, gpu: Bitmap) {
        val w = canvas.width; val h = canvas.height
        val regions = listOf(
            Rect(w / 3, 0, w * 2 / 3, h / 4), Rect(w * 3 / 4, h / 3, w, h * 2 / 3),
            Rect(w / 3, h * 3 / 4, w * 2 / 3, h), Rect(0, h / 3, w / 4, h * 2 / 3),
            Rect(0, 0, w / 3, h / 3), Rect(w * 2 / 3, 0, w, h / 3),
            Rect(w * 2 / 3, h * 2 / 3, w, h), Rect(0, h * 2 / 3, w / 3, h)
        )
        regions.forEachIndexed { index, region ->
            val canvasPoint = brightest(canvas, region)
            val gpuPoint = brightest(gpu, Rect(canvasPoint.first - POSITION_TOLERANCE, canvasPoint.second - POSITION_TOLERANCE, canvasPoint.first + POSITION_TOLERANCE + 1, canvasPoint.second + POSITION_TOLERANCE + 1))
            assertTrue("contour sample $index remains positioned", kotlin.math.abs(gpuPoint.first - canvasPoint.first) <= POSITION_TOLERANCE && kotlin.math.abs(gpuPoint.second - canvasPoint.second) <= POSITION_TOLERANCE)
            assertCompositeClose("contour sample $index", canvas.getPixel(canvasPoint.first, canvasPoint.second), gpu.getPixel(gpuPoint.first, gpuPoint.second))
        }
        val center = gpu.getPixel(gpu.width / 2, gpu.height / 2)
        assertTrue("interior remains transparent", delta(center, canvas.getPixel(canvas.width / 2, canvas.height / 2)) <= 8)
        listOf(0 to 0, gpu.width - 1 to 0, 0 to gpu.height - 1, gpu.width - 1 to gpu.height - 1).forEach { (x, y) ->
            assertTrue("exterior corner remains clipped", delta(gpu.getPixel(x, y), canvas.getPixel(x, y)) <= 8)
        }
    }
    private fun brightest(bitmap: Bitmap, requested: Rect): Pair<Int, Int> {
        val bounds = Rect(0, 0, bitmap.width, bitmap.height); requested.intersect(bounds)
        var best = requested.left to requested.top; var score = Int.MIN_VALUE
        for (y in requested.top until requested.bottom) for (x in requested.left until requested.right) {
            val pixel = bitmap.getPixel(x, y); val candidate = Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
            if (candidate > score) { score = candidate; best = x to y }
        }
        return best
    }
    private fun assertCompositeClose(label: String, canvas: Int, gpu: Int) {
        assertTrue("$label composited red", kotlin.math.abs(Color.red(canvas) - Color.red(gpu)) <= COMPOSITED_RGB_TOLERANCE)
        assertTrue("$label composited green", kotlin.math.abs(Color.green(canvas) - Color.green(gpu)) <= COMPOSITED_RGB_TOLERANCE)
        assertTrue("$label composited blue", kotlin.math.abs(Color.blue(canvas) - Color.blue(gpu)) <= COMPOSITED_RGB_TOLERANCE)
        assertTrue("$label composited alpha", kotlin.math.abs(Color.alpha(canvas) - Color.alpha(gpu)) <= COMPOSITED_ALPHA_TOLERANCE)
    }
    private fun delta(a: Int, b: Int) = maxOf(kotlin.math.abs(Color.red(a)-Color.red(b)), kotlin.math.abs(Color.green(a)-Color.green(b)), kotlin.math.abs(Color.blue(a)-Color.blue(b)))
    private fun args() = InstrumentationRegistry.getArguments()
    private fun copy(activity: Activity, view: HaloView): Bitmap {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); val latch = CountDownLatch(1); var code = PixelCopy.ERROR_UNKNOWN
        val location = IntArray(2); view.getLocationInWindow(location)
        PixelCopy.request(activity.window, Rect(location[0], location[1], location[0] + view.width, location[1] + view.height), bitmap, { code = it; latch.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue("PixelCopy timeout", latch.await(TIMEOUT, TimeUnit.MILLISECONDS)); assertEquals(PixelCopy.SUCCESS, code); return bitmap
    }
    private companion object {
        const val ARG = "gpuPulseGateB"; const val TIMEOUT = 5_000L
        const val POSITION_TOLERANCE = 3; const val COMPOSITED_RGB_TOLERANCE = 48; const val COMPOSITED_ALPHA_TOLERANCE = 8
    }
}
