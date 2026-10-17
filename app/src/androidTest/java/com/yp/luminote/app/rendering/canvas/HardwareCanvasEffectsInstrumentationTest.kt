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
import com.yp.luminote.app.animation.definitions.BladeVariant
import com.yp.luminote.app.animation.definitions.HaloEffectSpec
import com.yp.luminote.app.animation.definitions.ImpulseAnimation
import com.yp.luminote.app.animation.definitions.SupernovaAnimation
import com.yp.luminote.app.animation.plan.HaloEffectVisualPlanMapper
import com.yp.luminote.app.animation.plan.VisualModifier
import com.yp.luminote.app.animation.plan.VisualPlanSpec
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.effects.model.HaloRenderMode
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
    fun `production normal impulse mapper stack changes framebuffer and preserves corner clipping`() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val harness = HardwareHarnessView(activity)
        instrumentation.runOnMainSync { activity.setContentView(harness) }
        instrumentation.waitForIdleSync()

        val productionConfig = HaloConfig(
            motion = ImpulseAnimation.id,
            renderMode = HaloRenderMode.NORMAL,
            effectPlan = HaloEffectSpec.SpecializedField
        )
        // This is deliberately the real mapper output, not a test-created diagnostic stack.
        assertEquals(
            listOf(
                VisualModifier.IntensityPulse(amplitude = 0.22f, frequencyHz = 1.15f),
                VisualModifier.Blur(radiusPx = 3.5f),
                VisualModifier.Glow(radiusPx = 7f, alphaMultiplier = 0.42f)
            ),
            HaloEffectVisualPlanMapper.map(productionConfig).emitters.single().modifiers
        )

        val baseline = captureProductionMapperStack(
            instrumentation, activity, harness, productionConfig, useMapperStack = false
        )
        val modified = captureProductionMapperStack(
            instrumentation, activity, harness, productionConfig, useMapperStack = true
        )

        assertMeaningfulDifference("Production normal Impulse mapper stack", baseline, modified)
        assertSafeCorners("Production normal Impulse mapper stack", modified)
        assertTrue("window Canvas must be hardware accelerated", harness.observedHardwareCanvas)
        assertEquals(RenderOutcome.Rendered, harness.lastOutcome)
    }

    @Test
    fun `production normal Supernova mapper stack changes framebuffer and preserves corner clipping`() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val harness = HardwareHarnessView(activity)
        instrumentation.runOnMainSync { activity.setContentView(harness) }
        instrumentation.waitForIdleSync()

        val productionConfig = HaloConfig(
            motion = SupernovaAnimation.id,
            renderMode = HaloRenderMode.NORMAL,
            effectPlan = HaloEffectSpec.Supernova
        )
        // This must remain the production mapper contract, not a debug diagnostic profile.
        assertEquals(
            listOf(
                VisualModifier.IntensityPulse(amplitude = 0.18f, frequencyHz = 1.65f),
                VisualModifier.Blur(radiusPx = 4.5f),
                VisualModifier.Glow(radiusPx = 11f, alphaMultiplier = 0.50f)
            ),
            HaloEffectVisualPlanMapper.map(productionConfig).emitters.single().modifiers
        )

        val baseline = captureProductionMapperStack(
            instrumentation, activity, harness, productionConfig, useMapperStack = false
        )
        val modified = captureProductionMapperStack(
            instrumentation, activity, harness, productionConfig, useMapperStack = true
        )

        assertMeaningfulDifference("Production normal Supernova mapper stack", baseline, modified)
        assertSafeCorners("Production normal Supernova mapper stack", modified)
        assertTrue("window Canvas must be hardware accelerated", harness.observedHardwareCanvas)
        assertEquals(RenderOutcome.Rendered, harness.lastOutcome)
    }

    @Test
    fun `hardware canvas produces paired framebuffer differences for every admitted modifier lane`() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val harness = HardwareHarnessView(activity)
        instrumentation.runOnMainSync { activity.setContentView(harness) }
        instrumentation.waitForIdleSync()

        admittedLanes.forEach { lane ->
            val baseline = capture(instrumentation, activity, harness, lane.config, emptyList(), PULSE_LOW_NANOS)
            val pulseLow = capture(
                instrumentation, activity, harness, lane.config,
                listOf(VisualModifier.IntensityPulse(1f, 1f)), PULSE_LOW_NANOS
            )
            val pulseHigh = capture(
                instrumentation, activity, harness, lane.config,
                listOf(VisualModifier.IntensityPulse(1f, 1f)), PULSE_HIGH_NANOS
            )

            assertMeaningfulDifference("${lane.label} IntensityPulse", baseline, pulseLow)
            assertMeaningfulDifference("${lane.label} IntensityPulse high/low", pulseHigh, pulseLow)
            assertTrue("${lane.label} pulse high point must retain the source", luminance(pulseHigh) > 0L)
            assertSafeCorners("${lane.label} IntensityPulse", pulseLow)

            if (lane.supportsOpticalModifiers) {
                val blur = capture(
                    instrumentation, activity, harness, lane.config,
                    listOf(VisualModifier.Blur(64f)), PULSE_LOW_NANOS
                )
                val glow = capture(
                    instrumentation, activity, harness, lane.config,
                    listOf(VisualModifier.Glow(64f, 1f)), PULSE_LOW_NANOS
                )
                assertMeaningfulDifference("${lane.label} Blur", baseline, blur)
                assertMeaningfulDifference("${lane.label} Glow", baseline, glow)
                assertSafeCorners("${lane.label} Blur", blur)
                assertSafeCorners("${lane.label} Glow", glow)
            }
        }

        assertTrue("window Canvas must be hardware accelerated", harness.observedHardwareCanvas)
        assertEquals(RenderOutcome.Rendered, harness.lastOutcome)
    }

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
        instrumentation.runOnMainSync {
            harness.show(HaloConfig(effectPlan = HaloEffectSpec.FullContour), modifiers, frameTimeNanos)
        }
        return pixelCopy(instrumentation, activity, harness)
    }

    private fun capture(
        instrumentation: android.app.Instrumentation,
        activity: Activity,
        harness: HardwareHarnessView,
        config: HaloConfig,
        modifiers: List<VisualModifier>,
        frameTimeNanos: Long
    ): Bitmap {
        instrumentation.runOnMainSync { harness.show(config, modifiers, frameTimeNanos) }
        return pixelCopy(instrumentation, activity, harness)
    }

    private fun captureProductionMapperStack(
        instrumentation: android.app.Instrumentation,
        activity: Activity,
        harness: HardwareHarnessView,
        config: HaloConfig,
        useMapperStack: Boolean
    ): Bitmap {
        instrumentation.runOnMainSync {
            // Both frames share this exact phase/time; only the plan stack differs.
            harness.showProductionMapperStack(config, useMapperStack, PRODUCTION_CAPTURE_NANOS)
        }
        return pixelCopy(instrumentation, activity, harness)
    }

    private fun pixelCopy(
        instrumentation: android.app.Instrumentation,
        activity: Activity,
        harness: HardwareHarnessView
    ): Bitmap {
        instrumentation.waitForIdleSync()
        val bitmap = Bitmap.createBitmap(harness.width, harness.height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(activity.window, bitmap, { copyResult -> result = copyResult; latch.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue("PixelCopy timed out", latch.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        return bitmap
    }

    private fun assertMeaningfulDifference(label: String, baseline: Bitmap, modified: Bitmap) {
        var changedPixels = 0
        var channelDelta = 0L
        var activePixels = 0
        // This is an operation ROI: only pixels touched by either paired output participate.
        // It avoids letting unrelated empty framebuffer area dilute a real renderer difference.
        for (y in 0 until baseline.height step 2) for (x in 0 until baseline.width step 2) {
            val before = baseline.getPixel(x, y)
            val after = modified.getPixel(x, y)
            val delta = kotlin.math.abs(Color.red(before) - Color.red(after)) +
                kotlin.math.abs(Color.green(before) - Color.green(after)) +
                kotlin.math.abs(Color.blue(before) - Color.blue(after))
            if (luminance(before) > ACTIVE_PIXEL_LUMINANCE || luminance(after) > ACTIVE_PIXEL_LUMINANCE) {
                activePixels++
                if (delta >= MEANINGFUL_CHANNEL_DELTA) changedPixels++
                channelDelta += delta
            }
        }
        assertTrue("$label source ROI must be visible", activePixels >= MIN_ACTIVE_PIXELS)
        val requiredChangedPixels = maxOf(MIN_CHANGED_PIXELS, activePixels / MIN_CHANGED_ROI_DIVISOR)
        val requiredChannelDelta = maxOf(MIN_CHANNEL_DELTA, activePixels.toLong() * MIN_DELTA_PER_ACTIVE_PIXEL)
        assertTrue("$label must change a material portion of its ROI; changed=$changedPixels required=$requiredChangedPixels", changedPixels >= requiredChangedPixels)
        assertTrue("$label must produce a material ROI RGB delta; delta=$channelDelta required=$requiredChannelDelta", channelDelta >= requiredChannelDelta)
    }

    private fun assertSafeCorners(label: String, bitmap: Bitmap) {
        assertEquals("$label must keep top-left exterior corner clear", Color.BLACK, bitmap.getPixel(0, 0))
        assertEquals("$label must keep top-right exterior corner clear", Color.BLACK, bitmap.getPixel(bitmap.width - 1, 0))
        assertEquals("$label must keep bottom-left exterior corner clear", Color.BLACK, bitmap.getPixel(0, bitmap.height - 1))
        assertEquals("$label must keep bottom-right exterior corner clear", Color.BLACK, bitmap.getPixel(bitmap.width - 1, bitmap.height - 1))
    }

    private fun luminance(bitmap: Bitmap): Long {
        var result = 0L
        for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
            val pixel = bitmap.getPixel(x, y)
            result += Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
        }
        return result
    }

    private fun luminance(color: Int): Int = Color.red(color) + Color.green(color) + Color.blue(color)

    private class HardwareHarnessView(activity: Activity) : View(activity) {
        private val outline = DisplayOutline(resources.displayMetrics.density)
        private val backend = CanvasRenderBackend(HaloConfig(effectPlan = HaloEffectSpec.FullContour), outline)
        private val target = CanvasRenderTarget()
        private val frame = RenderFrame().apply { animationProgress = 1f; effectPhase = 0.4f; gradientPhase = 0.7f }
        var observedHardwareCanvas = false
        var lastOutcome: RenderOutcome? = null

        fun show(config: HaloConfig, modifiers: List<VisualModifier>, frameTimeNanos: Long) {
            backend.updateConfig(config)
            val emitter = HaloEffectVisualPlanMapper.map(config).emitters.single()
            backend.replaceVisualPlanForTest(VisualPlanSpec(listOf(emitter.copy(modifiers = modifiers))))
            frame.frameTimeNanos = frameTimeNanos
            invalidate()
        }

        fun showProductionMapperStack(config: HaloConfig, useMapperStack: Boolean, frameTimeNanos: Long) {
            backend.updateConfig(config)
            val mappedEmitter = HaloEffectVisualPlanMapper.map(config).emitters.single()
            val emitter = if (useMapperStack) mappedEmitter else mappedEmitter.copy(modifiers = emptyList())
            backend.replaceVisualPlanForTest(VisualPlanSpec(listOf(emitter)))
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

    private companion object {
        // For 1 Hz and amplitude 1 this is the zero-output trough. Baseline uses the identical
        // geometry, color, animation progress and effect/gradient phase at the same capture time.
        const val PULSE_LOW_NANOS = 750_000_000L
        const val PULSE_HIGH_NANOS = 250_000_000L
        // At 1.15 Hz this keeps the mapped 22% pulse near its high point, so
        // baseline and modified captures share an otherwise stable visible beam.
        const val PRODUCTION_CAPTURE_NANOS = 250_000_000L
        const val ACTIVE_PIXEL_LUMINANCE = 12
        const val MEANINGFUL_CHANNEL_DELTA = 8
        const val MIN_ACTIVE_PIXELS = 96
        const val MIN_CHANGED_PIXELS = 96
        const val MIN_CHANNEL_DELTA = 10_000L
        const val MIN_CHANGED_ROI_DIVISOR = 100
        const val MIN_DELTA_PER_ACTIVE_PIXEL = 2L

        val admittedLanes = listOf(
            EffectLane("Full Contour", HaloConfig(effectPlan = HaloEffectSpec.FullContour), supportsOpticalModifiers = true),
            EffectLane("Traveling Luminous Segment", HaloConfig(effectPlan = HaloEffectSpec.LuminousSegment(0.18f)), supportsOpticalModifiers = true),
            EffectLane("Supernova", HaloConfig(effectPlan = HaloEffectSpec.Supernova), supportsOpticalModifiers = true),
            EffectLane("ForceBlade Azure", HaloConfig(effectPlan = HaloEffectSpec.Blade(BladeVariant.AZURE)), supportsOpticalModifiers = false),
            EffectLane("ForceBlade Crimson", HaloConfig(effectPlan = HaloEffectSpec.Blade(BladeVariant.CRIMSON)), supportsOpticalModifiers = false),
            EffectLane("ForceBlade Clash", HaloConfig(effectPlan = HaloEffectSpec.Blade(BladeVariant.CLASH)), supportsOpticalModifiers = false)
        )
    }

    private data class EffectLane(
        val label: String,
        val config: HaloConfig,
        val supportsOpticalModifiers: Boolean
    )
}
