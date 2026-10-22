package com.yp.luminote.app.rendering.gpu

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
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

/**
 * Physical-device Gate A evidence harness. It is deliberately opt-in: ordinary connected-test
 * runs skip it unless passed `-e gpuWitnessGateA true`. The required proof is PixelCopy output,
 * not successful compilation or logs.
 */
@RunWith(AndroidJUnit4::class)
class GpuWitnessPixelCopyInstrumentationTest {
    @Test
    fun `GPU witness has magenta contribution transparent cells clipped exterior and fresh recreated resources`() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "Gate A PixelCopy is opt-in; rerun with instrumentation argument gpuWitnessGateA=true",
            InstrumentationRegistry.getArguments().getString(ARG_GATE_A) == "true"
        )
        val config = HaloConfig()
        var activity: Activity? = null
        var view: HaloView? = null
        lateinit var scenario: ActivityScenario<MainActivity>
        fun installWitnessHost(enableGpu: Boolean) {
            scenario.onActivity { host ->
                val haloView = HaloView(host, config)
                host.setContentView(haloView)
                haloView.replaceVisualPlanForDebug(HaloEffectVisualPlanMapper.map(config))
                haloView.startAmbientEffect(config.effectSpeed, config.motion)
                if (enableGpu) assertTrue(haloView.setGpuWitnessEnabledForDebug(true))
                activity = host
                view = haloView
            }
        }
        scenario = ActivityScenario.launch<MainActivity>(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            GpuWitnessEvidence.resetForTest()
            installWitnessHost(enableGpu = false)
            val canvasControl = pixelCopy(instrumentation, activity!!, view!!)

            val generationBeforeSelection = GpuWitnessEvidence.latestGeneration() ?: 0L
            scenario.onActivity { assertTrue(view!!.setGpuWitnessEnabledForDebug(true)) }
            val firstGeneration = awaitGenerationAfter(generationBeforeSelection)
            val firstEvidence = awaitInitializedAndSubmitted(firstGeneration)
            val gpuWitness = pixelCopy(instrumentation, activity!!, view!!)
            assertTrue("GPU must be explicitly requested", firstEvidence.requestedGpu)
            assertTrue("shader must submit a GPU frame", firstEvidence.actualGpu && firstEvidence.submissionCount > 0)
            assertEquals("witness success must not Canvas-fallback", 0, firstEvidence.canvasFallbackCount)

            assertMagentaContributionAndTransparentCells(canvasControl, gpuWitness)
            assertClippedExterior(canvasControl, gpuWitness)

            scenario.recreate()
            val disposedFirstEvidence = awaitDisposal(firstGeneration)
            assertTrue("recreating the activity must dispose the first GPU generation", disposedFirstEvidence.gpuDisposalCount > 0)
            installWitnessHost(enableGpu = true)
            val recreatedGeneration = awaitGenerationAfter(firstGeneration)
            val recreatedEvidence = awaitInitializedAndSubmitted(recreatedGeneration)
            val recreated = pixelCopy(instrumentation, activity!!, view!!)
            assertMagentaContributionAndTransparentCells(canvasControl, recreated)
            assertTrue("recreated host must use a new immutable GPU generation", recreatedEvidence.generation > firstEvidence.generation)
            assertTrue("recreated activity must initialize a fresh shader", recreatedEvidence.shaderInitializationCount > 0)
            assertTrue("recreated activity must submit a fresh GPU frame", recreatedEvidence.submissionCount > 0)
            assertEquals("recreated witness must not Canvas-fallback", 0, recreatedEvidence.canvasFallbackCount)
        } finally {
            scenario.close()
        }
    }

    private fun assertMagentaContributionAndTransparentCells(canvas: Bitmap, gpu: Bitmap) {
        var magentaContributionPixels = 0
        var transparentControlPixels = 0
        for (y in gpu.height / 4 until gpu.height * 3 / 4 step 4) {
            for (x in gpu.width / 4 until gpu.width * 3 / 4 step 4) {
                val pixel = gpu.getPixel(x, y)
                val control = canvas.getPixel(x, y)
                // The shader's only visible branch is straight RGB (.95, .08, .72) at alpha .82.
                // PixelCopy observes source-over composition, so calculate its expected output
                // over the paired Canvas control instead of classifying a broad color family.
                if (matchesExpectedMagentaComposite(pixel, control) && differsMeaningfully(pixel, control)) {
                    magentaContributionPixels++
                }
                // The cyan branch has zero alpha. In the empty center of this control frame it
                // must preserve the Canvas baseline rather than contribute cyan/white pixels.
                if (preservesAlphaAndRgb(pixel, control)) {
                    transparentControlPixels++
                }
            }
        }
        assertTrue("expected composited magenta witness contribution", magentaContributionPixels >= MIN_SAMPLE_COUNT)
        assertTrue("expected transparent shader cells to preserve the Canvas baseline", transparentControlPixels >= MIN_SAMPLE_COUNT)
    }

    private fun assertClippedExterior(canvas: Bitmap, gpu: Bitmap) {
        val corners = listOf(
            0 to 0,
            gpu.width - 1 to 0,
            0 to gpu.height - 1,
            gpu.width - 1 to gpu.height - 1
        )
        corners.forEach { (x, y) ->
            assertTrue(
                "clipped exterior corner ($x,$y) must preserve the Canvas baseline",
                preservesAlphaAndRgb(gpu.getPixel(x, y), canvas.getPixel(x, y))
            )
        }
    }

    private fun matchesExpectedMagentaComposite(pixel: Int, control: Int): Boolean {
        val expectedRed = composite(SOURCE_RED, Color.red(control))
        val expectedGreen = composite(SOURCE_GREEN, Color.green(control))
        val expectedBlue = composite(SOURCE_BLUE, Color.blue(control))
        val expectedAlpha = compositeAlpha(Color.alpha(control))
        return kotlin.math.abs(Color.red(pixel) - expectedRed) <= COMPOSITED_RGB_CHANNEL_ERROR &&
            kotlin.math.abs(Color.green(pixel) - expectedGreen) <= COMPOSITED_RGB_CHANNEL_ERROR &&
            kotlin.math.abs(Color.blue(pixel) - expectedBlue) <= COMPOSITED_RGB_CHANNEL_ERROR &&
            kotlin.math.abs(Color.alpha(pixel) - expectedAlpha) <= COMPOSITED_ALPHA_CHANNEL_ERROR
    }

    private fun composite(source: Float, destination: Int): Int =
        (source * SOURCE_ALPHA + destination * (1f - SOURCE_ALPHA)).toInt()

    private fun compositeAlpha(destinationAlpha: Int): Int =
        (255f * SOURCE_ALPHA + destinationAlpha * (1f - SOURCE_ALPHA)).toInt()

    private fun differsMeaningfully(first: Int, second: Int): Boolean =
        maxOf(
            kotlin.math.abs(Color.red(first) - Color.red(second)),
            kotlin.math.abs(Color.green(first) - Color.green(second)),
            kotlin.math.abs(Color.blue(first) - Color.blue(second))
        ) >= MIN_CHANNEL_DELTA

    private fun preservesAlphaAndRgb(first: Int, second: Int): Boolean =
        kotlin.math.abs(Color.red(first) - Color.red(second)) <= TRANSPARENT_CHANNEL_ERROR &&
            kotlin.math.abs(Color.green(first) - Color.green(second)) <= TRANSPARENT_CHANNEL_ERROR &&
            kotlin.math.abs(Color.blue(first) - Color.blue(second)) <= TRANSPARENT_CHANNEL_ERROR &&
            kotlin.math.abs(Color.alpha(first) - Color.alpha(second)) <= TRANSPARENT_ALPHA_CHANNEL_ERROR

    private fun awaitGenerationAfter(previousGeneration: Long): Long =
        GpuWitnessEvidence.awaitGenerationAfter(previousGeneration, SUBMISSION_WAIT_TIMEOUT_MS)
            ?.generation
            ?: throw AssertionError("timed out waiting for a newly selected GPU witness generation")

    private fun awaitInitializedAndSubmitted(generation: Long): GpuWitnessEvidence.Snapshot =
        GpuWitnessEvidence.awaitInitializedAndSubmitted(generation, SUBMISSION_WAIT_TIMEOUT_MS)
            ?: throw AssertionError("timed out waiting for generation $generation initialization and GPU submission")

    private fun awaitDisposal(generation: Long): GpuWitnessEvidence.Snapshot =
        GpuWitnessEvidence.awaitDisposal(generation, SUBMISSION_WAIT_TIMEOUT_MS)
            ?: throw AssertionError("timed out waiting for generation $generation GPU disposal")

    private fun pixelCopy(
        instrumentation: android.app.Instrumentation,
        activity: Activity,
        view: HaloView
    ): Bitmap {
        instrumentation.waitForIdleSync()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val sourceRect = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        PixelCopy.request(activity.window, sourceRect, bitmap, { code -> result = code; latch.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue("PixelCopy timed out", latch.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        return bitmap
    }

    private companion object {
        const val ARG_GATE_A = "gpuWitnessGateA"
        const val MIN_SAMPLE_COUNT = 16
        const val MIN_CHANNEL_DELTA = 24
        // Device-candidate tolerance for PixelCopy/color-space conversion; exact color correctness
        // remains a Galaxy S25 framebuffer-validation result, not a host-build claim.
        const val COMPOSITED_RGB_CHANNEL_ERROR = 24
        const val COMPOSITED_ALPHA_CHANNEL_ERROR = 8
        const val TRANSPARENT_CHANNEL_ERROR = 12
        const val TRANSPARENT_ALPHA_CHANNEL_ERROR = 2
        const val SUBMISSION_WAIT_TIMEOUT_MS = 5_000L
        const val SOURCE_RED = 0.95f * 255f
        const val SOURCE_GREEN = 0.08f * 255f
        const val SOURCE_BLUE = 0.72f * 255f
        const val SOURCE_ALPHA = 0.82f
    }
}
