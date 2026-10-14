package com.yp.luminote.app.rendering.canvas

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yp.luminote.app.MainActivity
import com.yp.luminote.app.animation.definitions.HaloEffectSpec
import com.yp.luminote.app.animation.plan.HaloEffectVisualPlanMapper
import com.yp.luminote.app.animation.plan.VisualModifier
import com.yp.luminote.app.animation.plan.VisualPlanSpec
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.rendering.api.RenderFrame
import com.yp.luminote.app.rendering.api.RenderOutcome
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device witness for the production CanvasRenderBackend path. It must run on a real hardware
 * window: JVM Canvas tests deliberately exercise the Unsupported preflight instead.
 */
@RunWith(AndroidJUnit4::class)
class HardwareCanvasEffectsInstrumentationTest {
    @Test
    fun `hardware canvas distinguishes source blur glow composition pulse and corner clipping`() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val harness = HardwareHarnessView(activity)
        instrumentation.runOnMainSync { activity.setContentView(harness) }
        instrumentation.waitForIdleSync()

        val blurOnly = capture(instrumentation, activity, harness, listOf(VisualModifier.Blur(6f)), 250_000_000L)
        val glowOnly = capture(instrumentation, activity, harness, listOf(VisualModifier.Glow(12f, 0.8f)), 250_000_000L)
        val combinedHigh = capture(
            instrumentation,
            activity,
            harness,
            listOf(VisualModifier.IntensityPulse(0.5f), VisualModifier.Blur(6f), VisualModifier.Glow(12f, 0.8f)),
            250_000_000L
        )
        val combinedLow = capture(
            instrumentation,
            activity,
            harness,
            listOf(VisualModifier.IntensityPulse(0.5f), VisualModifier.Blur(6f), VisualModifier.Glow(12f, 0.8f)),
            750_000_000L
        )

        assertTrue("window Canvas must be hardware accelerated", harness.observedHardwareCanvas)
        assertEquals(RenderOutcome.Rendered, harness.lastOutcome)
        assertTrue("source blur must spread visible source luminance", luminance(blurOnly) > 0L)
        assertTrue("glow envelope must contribute outer luminance", luminance(glowOnly) > 0L)
        assertTrue("glow then source composition must be brighter than either isolated pass", luminance(combinedHigh) > luminance(blurOnly))
        assertTrue("pulse high frame must exceed pulse low frame", luminance(combinedHigh) > luminance(combinedLow))
        assertEquals("parent outline clipping must keep the top-left corner clear", Color.BLACK, combinedHigh.getPixel(0, 0))
        assertEquals("parent outline clipping must keep the top-right corner clear", Color.BLACK, combinedHigh.getPixel(combinedHigh.width - 1, 0))
    }

    private fun capture(
        instrumentation: android.app.Instrumentation,
        activity: Activity,
        harness: HardwareHarnessView,
        modifiers: List<VisualModifier>,
        frameTimeNanos: Long
    ): Bitmap {
        instrumentation.runOnMainSync { harness.show(modifiers, frameTimeNanos) }
        instrumentation.waitForIdleSync()
        val bitmap = Bitmap.createBitmap(harness.width, harness.height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(activity.window, bitmap, { copyResult -> result = copyResult; latch.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue("PixelCopy timed out", latch.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        return bitmap
    }

    private fun luminance(bitmap: Bitmap): Long {
        var result = 0L
        for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
            val pixel = bitmap.getPixel(x, y)
            result += Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
        }
        return result
    }

    private class HardwareHarnessView(activity: Activity) : View(activity) {
        private val outline = DisplayOutline(resources.displayMetrics.density)
        private val backend = CanvasRenderBackend(HaloConfig(effectPlan = HaloEffectSpec.FullContour), outline)
        private val target = CanvasRenderTarget()
        private val frame = RenderFrame().apply { animationProgress = 1f; effectPhase = 0.4f; gradientPhase = 0.7f }
        var observedHardwareCanvas = false
        var lastOutcome: RenderOutcome? = null

        fun show(modifiers: List<VisualModifier>, frameTimeNanos: Long) {
            val emitter = HaloEffectVisualPlanMapper.map(HaloConfig(effectPlan = HaloEffectSpec.FullContour)).emitters.single()
            backend.replaceVisualPlanForTest(VisualPlanSpec(listOf(emitter.copy(modifiers = modifiers))))
            frame.frameTimeNanos = frameTimeNanos
            invalidate()
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            outline.resize(width, height)
            backend.invalidateSurface()
            frame.viewportWidth = width
            frame.viewportHeight = height
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            observedHardwareCanvas = canvas.isHardwareAccelerated
            lastOutcome = target.withCanvas(canvas) { backend.render(target, frame) }
        }
    }
}
