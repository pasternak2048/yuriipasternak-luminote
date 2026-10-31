package com.yp.luminote.app.rendering.gpu

import android.app.Activity
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yp.luminote.app.MainActivity
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.geometry.EdgePathGeometryProvider
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.animation.plan.GeometryRequest
import com.yp.luminote.app.rendering.api.*
import com.yp.luminote.app.rendering.canvas.CanvasRenderTarget
import com.yp.luminote.app.resonance.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** Synthetic frozen host-input fixtures prove GPU pixels only, never audio capture or visual approval. */
@RunWith(AndroidJUnit4::class)
class GpuResonanceSpectralPixelTest {
    @Test fun spectralPalettesSpatialDistributionAndOpticalLayers() = withFixture { scenario, generation ->
        val bass = capture(scenario, generation, AudioFeatureSnapshot(low = 1f, loudness = .85f))
        val mid = capture(scenario, generation, AudioFeatureSnapshot(mid = 1f, loudness = .85f))
        val high = capture(scenario, generation, AudioFeatureSnapshot(high = 1f, loudness = .85f))
        val mixed = capture(scenario, generation, AudioFeatureSnapshot(low = .8f, mid = .8f, high = .8f, loudness = .85f))
        try {
            val b = bass.colors(); val m = mid.colors(); val h = high.colors()
            assertTrue("Bass has blue/teal mass: $b", b.green > b.red * 2 && b.blue > b.red * 2)
            assertTrue("Midrange has violet/magenta flow: $m", m.red > m.green * 1.4 && m.blue > m.green * 1.4)
            assertTrue("Highs have pink/amber highlights: $h", h.red > h.blue * 1.1 && h.red > h.green * 1.1)
            assertTrue("Bass creates thicker mass than delicate highs", bass.radialMass() > high.radialMass() * 1.25)
            assertTrue("Mixed frequencies retain spatial color variation", mixed.hueSpread() > 25f)
            assertTrue("Midrange color also varies along contour", mid.hueSpread() > 15f)
            for (image in listOf(bass, mid, high, mixed)) {
                val core = image.maxBrightness(0f)
                val near = image.maxBrightness(7f)
                val outer = image.maxBrightness(9f)
                assertTrue("Luminous core and falling near/outer optical layers: $core/$near/$outer", core > near && near > outer && outer > 0)
                assertEquals("Optical support fades fully before field saturation", 0, image.maxBrightness(22f))
                assertEquals("Interior remains black", Color.BLACK, image.bitmap.getPixel(image.bitmap.width / 2, image.bitmap.height / 2))
                assertEquals("Physical exterior clipped", Color.BLACK, image.bitmap.getPixel(0, 0))
            }
            Log.i("ResonanceSpectralPixels", "bass=$b mid=$m high=$h mixedHueSpread=${mixed.hueSpread()} midHueSpread=${mid.hueSpread()} bassMass=${bass.radialMass()} highMass=${high.radialMass()}; synthetic GPU pixels only")
        } finally { bass.close(); mid.close(); high.close(); mixed.close() }
    }

    @Test fun wrappedHostPhaseHasNoSpectralColorSeam() = withFixture { scenario, generation ->
        val feature = AudioFeatureSnapshot(low = .8f, mid = .8f, high = .8f, loudness = .85f)
        val before = capture(scenario, generation, feature, phaseOverride = .99999f)
        val after = capture(scenario, generation, feature, phaseOverride = .00001f)
        try {
            val delta = before.averageColorDelta(after)
            assertTrue("Phase wrap preserves spectral colors, mean channel delta=$delta", delta < 3f)
            Log.i("ResonanceSpectralPixels", "hostPhaseWrapMeanChannelDelta=$delta; frozen shader uniforms, synthetic GPU pixels only")
        } finally { before.close(); after.close() }
    }

    @Test fun quietSignalSilenceDecayAndAdjacentHostFramesRemainContinuous() = withFixture { scenario, generation ->
        val quiet = capture(scenario, generation, AudioFeatureSnapshot(low = .08f, mid = .05f, high = .02f, loudness = .08f))
        val silence = capture(scenario, generation, AudioFeatureSnapshot())
        val mixed = capture(scenario, generation, AudioFeatureSnapshot(low = .8f, mid = .7f, high = .6f, loudness = .8f))
        try {
            assertTrue("Quiet music remains visible", quiet.maxBrightness(0f) > 0)
            assertEquals("True silence has no luminous baseline", 0, silence.maxBrightness(0f))
            var next: Image? = null
            var decayed: Image? = null
            try {
                scenario.onActivity { mixed.fixture.advance(1, stale = false) }
                next = copy(mixed.fixture)
                assertTrue("Adjacent host frame has no global color jump", mixed.averageColorDelta(next) < 24f)
                scenario.onActivity { mixed.fixture.advance(180, stale = true) }
                decayed = copy(mixed.fixture)
                assertTrue("Missing audio naturally fades using existing host-time decay", decayed.maxBrightness(0f) <= 1)
                Log.i("ResonanceSpectralPixels", "quietPeak=${quiet.maxBrightness(0f)} silencePeak=${silence.maxBrightness(0f)} adjacentDelta=${mixed.averageColorDelta(next)} missingFrameDecayPeak=${decayed.maxBrightness(0f)}; synthetic GPU pixels only")
            } finally { next?.close(); decayed?.close() }
        } finally { quiet.close(); silence.close(); mixed.close() }
    }

    private fun withFixture(block: (ActivityScenario<MainActivity>, Long) -> Unit) {
        assumeTrue("Spectral GPU PixelCopy opt-in", InstrumentationRegistry.getArguments().getString("resonanceSpectralPixels") == "true")
        assumeTrue("Stop live Resonance before synthetic pixel fixtures", ResonanceRuntime.canStart())
        val intensity = ResonanceController.state.value.intensity
        ResonanceController.setIntensity(1f)
        val generation = ResonanceRuntime.begin()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try { block(scenario, generation) }
        finally {
            scenario.close(); ResonanceRuntime.end(generation); ResonanceRuntime.cleanupComplete(generation)
            ResonanceController.setIntensity(intensity)
        }
    }

    private fun capture(scenario: ActivityScenario<MainActivity>, generation: Long, features: AudioFeatureSnapshot,
        phaseOverride: Float? = null): Image {
        var fixture: Fixture? = null
        scenario.onActivity { activity ->
            activity.setShowWhenLocked(true); activity.setTurnScreenOn(true)
            fixture = Fixture(activity, generation, features, phaseOverride).also { activity.setContentView(it) }
        }
        return copy(fixture!!)
    }

    private class Fixture(val activity: Activity, private val generation: Long, private val features: AudioFeatureSnapshot,
        private val phaseOverride: Float?) : View(activity) {
        private val outline = DisplayOutline(resources.displayMetrics.density)
        private val provider = EdgePathGeometryProvider(outline)
        private val config = HaloConfig(thickness = .5f)
        private val backend = GpuResonanceRenderBackend(outline, generation)
        private val frame = RenderFrame().apply { animationProgress = 1f }
        var committed = CountDownLatch(1); private set
        private var step = 0
        private var targetStep = 40
        private var stale = false
        private var lastFreshTime = 0L
        var drawnFrames = 0; private set
        var committedFrames = 0; private set
        private var uniformField: GpuSnakeContourField? = null
        private var uniformShader: RuntimeShader? = null
        private val uniformPaint = Paint()
        init { setBackgroundColor(Color.BLACK); backend.updateConfig(config) }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            outline.resize(w, h)
            outline.updateDisplayShape(Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 80f, 80f, Path.Direction.CW) })
            backend.invalidateSurface()
            frame.viewportWidth = w; frame.viewportHeight = h
            uniformField?.dispose(); uniformField = null; uniformShader = null
        }
        fun advance(frames: Int, stale: Boolean) {
            committed = CountDownLatch(1)
            targetStep = step + frames
            this.stale = stale
            invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            drawnFrames++
            val time = 1_000_000_000L + step * 16_666_667L
            if (!stale) lastFreshTime = time
            frame.frameTimeNanos = time
            frame.resonanceFeatures = features.copy(generation = generation, timestampNanos = lastFreshTime)
            if (phaseOverride == null) check(backend.render(CanvasRenderTarget(canvas), frame) == RenderOutcome.Rendered)
            else {
                // Direct shader-uniform seam fixture; production behavior/clock remain unmodified.
                if (uniformShader == null) {
                    val geometry = GeometryRequest.DisplayOutlineContour(config.thickness, config.frame, 0f, 0f, config.cornerShape)
                    uniformField = GpuSnakeContourField.build(provider, GpuSnakeContourField.key(outline, 0, width, height, geometry))!!
                    uniformShader = RuntimeShader(GpuResonanceRenderBackend.FLUID_SHADER).also { it.setInputBuffer("field", uniformField!!.input) }
                }
                uniformShader!!.setFloatUniform("audio", features.low, features.mid, features.high, features.loudness)
                uniformShader!!.setFloatUniform("motion", phaseOverride, .6f, .5f, 1f)
                uniformShader!!.setFloatUniform("strokeWidth", 9f)
                uniformPaint.shader = uniformShader
                val saved = canvas.save()
                canvas.clipPath(outline.path)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), uniformPaint)
                canvas.restoreToCount(saved)
            }
            if (step < targetStep) {
                step++
                if (step == targetStep) {
                    // Register before the final traversal; ViewRoot gathers commit callbacks
                    // before invoking onDraw, so registration inside that draw is too late.
                    postOnAnimation {
                        viewTreeObserver.registerFrameCommitCallback { committedFrames++; committed.countDown() }
                        invalidate()
                    }
                } else postInvalidateOnAnimation()
            }
        }
        fun point(q: Float, distance: Float): Pair<Int, Int> {
            val geometry = provider.geometryFor(9f, outline.opticalInsetPx, 0f, config.cornerShape, 0f)!!
            val xy = FloatArray(2); val tangent = FloatArray(2)
            check(geometry.measure.getPosTan(q * geometry.measure.length, xy, tangent))
            var nx = -tangent[1]; var ny = tangent[0]
            if (nx * (width * .5f - xy[0]) + ny * (height * .5f - xy[1]) < 0f) { nx = -nx; ny = -ny }
            return (xy[0] + nx * distance).roundToInt().coerceIn(0, width - 1) to
                (xy[1] + ny * distance).roundToInt().coerceIn(0, height - 1)
        }
        override fun onDetachedFromWindow() {
            backend.dispose(); uniformPaint.shader = null; uniformShader = null
            uniformField?.dispose(); uniformField = null
            super.onDetachedFromWindow()
        }
    }

    private data class Colors(val red: Double, val green: Double, val blue: Double)
    private class Image(val fixture: Fixture, val bitmap: Bitmap) {
        private fun samples(distance: Float) = (0 until 64).map {
            val point = fixture.point((it + .5f) / 64f, distance)
            bitmap.getPixel(point.first, point.second)
        }
        fun colors(): Colors {
            val samples = samples(0f)
            return Colors(samples.sumOf { Color.red(it).toDouble() }, samples.sumOf { Color.green(it).toDouble() }, samples.sumOf { Color.blue(it).toDouble() })
        }
        fun maxBrightness(distance: Float) = samples(distance).maxOf { max(Color.red(it), max(Color.green(it), Color.blue(it))) }
        fun radialMass() = (0..16).sumOf { distance -> samples(distance.toFloat()).sumOf { max(Color.red(it), max(Color.green(it), Color.blue(it))) } }
        fun hueSpread(): Float {
            val hues = samples(0f).map {
                FloatArray(3).also { hsv -> Color.colorToHSV(it, hsv) }
            }.filter { it[1] > .15f && it[2] > .02f }.map { it[0] }
            return hues.maxOfOrNull { a -> hues.maxOf { b -> min(abs(a - b), 360f - abs(a - b)) } } ?: 0f
        }
        fun averageColorDelta(other: Image): Float {
            val first = samples(0f); val second = other.samples(0f)
            return first.indices.sumOf { i ->
                abs(Color.red(first[i]) - Color.red(second[i])) + abs(Color.green(first[i]) - Color.green(second[i])) + abs(Color.blue(first[i]) - Color.blue(second[i]))
            } / (first.size * 3f)
        }
        fun close() = bitmap.recycle()
    }

    private fun copy(fixture: Fixture): Image {
        val committed = fixture.committed.await(15, TimeUnit.SECONDS)
        assertTrue("Fixture GPU frame commit draws=${fixture.drawnFrames} commits=${fixture.committedFrames} attached=${fixture.isAttachedToWindow} hardware=${fixture.isHardwareAccelerated}", committed)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val bitmap = Bitmap.createBitmap(fixture.width, fixture.height, Bitmap.Config.ARGB_8888)
        val location = IntArray(2); fixture.getLocationInWindow(location)
        val done = CountDownLatch(1); var result = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(fixture.activity.window, Rect(location[0], location[1], location[0] + fixture.width, location[1] + fixture.height), bitmap,
            { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue("PixelCopy result deadline", done.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
        return Image(fixture, bitmap)
    }
}
