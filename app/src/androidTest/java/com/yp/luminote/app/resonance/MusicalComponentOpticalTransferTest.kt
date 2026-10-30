package com.yp.luminote.app.resonance

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
import com.yp.luminote.app.rendering.gpu.GpuResonanceRenderBackend
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** Synthetic component/host inputs and arclength field isolate the UNCHANGED production shader.
 * This measures optical transfer, not display geometry, real playback, or human visual approval. */
@RunWith(AndroidJUnit4::class)
class MusicalComponentOpticalTransferTest {
    private val bins = listOf(1, 3, 8, 16, 32, 64, 150, 256)
    private data class UniformFrame(val bands: FloatArray, val low: Float, val mid: Float,
        val high: Float, val loudness: Float, val phase: Float, val impulse: Float, val buildup: Float)

    @Test fun eachComponentLightsItsFixedRegionsAtEveryBeatBalance() {
        assumeTrue("LMN028 optical transfer opt-in", InstrumentationRegistry.getArguments().getString("musicalComponentOptics") == "true")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var fixture: Fixture? = null
        try {
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true); activity.setTurnScreenOn(true)
                fixture = Fixture(activity).also { activity.setContentView(it) }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { fixture!!.select(peakFrame(MusicalComponentId.SUB_BASS, false, 0f, 0)) }
            val silenceImage = copy(fixture!!)
            try {
            val violations = ArrayList<String>()
            for (mixed in listOf(false, true)) for (id in MusicalComponentId.entries) for (balance in listOf(0f, .5f, 1f)) {
                val active = peakFrame(id, mixed, balance, 80)
                scenario.onActivity { fixture!!.select(active) }
                val image = copy(fixture!!)
                val quiet = peakFrame(id, mixed, balance, 3)
                scenario.onActivity { fixture!!.select(quiet) }
                val quietImage = copy(fixture!!)
                try {
                    val assigned = (0 until 12).filter { MusicalComponentVisualMapping.componentForSlot(it) == id }
                    for (slot in assigned) {
                        val peak = brightness(image, slot)
                        val subdued = brightness(quietImage, slot)
                        val baseline = brightness(silenceImage, slot)
                        Log.i("MusicalComponentOptics", "id=$id mixed=$mixed Beat=$balance slot=$slot shaderInput=${active.bands[slot]} rgbPeak=$peak quietPeak=$subdued silence=$baseline")
                        assertEquals("Pure silence is black in every assigned slot", 0, baseline)
                        if (peak - baseline <= 16) violations += "Dim actual GPU pixels: $id mixed=$mixed Beat=$balance slot=$slot peak=$peak"
                        if (subdued >= peak * .6f) violations += "Quiet peak insufficiently subordinate: $id mixed=$mixed Beat=$balance slot=$slot $subdued/$peak"
                    }
                } finally { image.recycle(); quietImage.recycle() }
            }
            assertTrue(violations.joinToString("\n"), violations.isEmpty())
            } finally { silenceImage.recycle() }
        } finally { scenario.close() }
    }

    @Test fun unchangedIntensityTransferIsMonotonicAndOffIsBlack() {
        assumeTrue("LMN028 optical transfer opt-in", InstrumentationRegistry.getArguments().getString("musicalComponentOptics") == "true")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var fixture: Fixture? = null
        try {
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true); activity.setTurnScreenOn(true)
                fixture = Fixture(activity).also { activity.setContentView(it) }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val frame = peakFrame(MusicalComponentId.MIDS, false, .5f, 80)
            val values = listOf(0f, .5f, 1f).map { intensity ->
                scenario.onActivity { fixture!!.select(frame, intensity) }
                val bitmap = copy(fixture!!)
                try { brightness(bitmap, 4) } finally { bitmap.recycle() }
            }
            Log.i("MusicalComponentOptics", "intensity0/.5/1 actualRgbPeaks=$values")
            assertEquals(0, values[0])
            assertTrue(values[1] > 16 && values[2] >= values[1])
        } finally { scenario.close() }
    }

    @Test fun mixedHighMidsContributesAtBothSlotsBeyondNeighborIllumination() {
        assumeTrue("LMN028 optical transfer opt-in", InstrumentationRegistry.getArguments().getString("musicalComponentOptics") == "true")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var fixture: Fixture? = null
        try {
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true); activity.setTurnScreenOn(true)
                fixture = Fixture(activity).also { activity.setContentView(it) }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val frame = peakFrame(MusicalComponentId.HIGH_MIDS, true, 0f, 80)
            scenario.onActivity { fixture!!.select(frame) }
            val full = copy(fixture!!)
            val removed = frame.copy(bands = frame.bands.copyOf().apply { this[7] = 0f; this[8] = 0f })
            scenario.onActivity { fixture!!.select(removed) }
            val without = copy(fixture!!)
            try {
                for (slot in listOf(7, 8)) {
                    val center = ((slot + .5f) / 12f * (WIDTH - 1)).roundToInt()
                    var delta = 0
                    for (x in center - 2..center + 2) for (y in 0..3) {
                        val a = full.getPixel(x, y); val b = without.getPixel(x, y)
                        delta = max(delta, abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b)))
                    }
                    Log.i("MusicalComponentOptics", "HIGH_MIDS mixedBeat0 counterfactual slot=$slot fullPeak=${brightness(full, slot)} withoutHighMidsPeak=${brightness(without, slot)} rgbDelta=$delta")
                    assertTrue("High Mids itself affects slot $slot beyond its overlapping neighbors", delta > 8)
                }
            } finally { full.recycle(); without.recycle() }
        } finally { scenario.close() }
    }

    private fun peakFrame(id: MusicalComponentId, mixed: Boolean, balance: Float, amplitude: Int): UniformFrame {
        val adapter = VisualizerFeatureAdapter(73, 1024)
        val behavior = ResonanceBehavior()
        val bytes = ByteArray(1024)
        val selected = if (mixed) bins else listOf(bins[id.ordinal])
        selected.forEach { bytes[it * 2] = amplitude.toByte() }
        val waveform = ByteArray(1024) { if (it % 2 == 0) 168.toByte() else 88.toByte() }
        val slot = (0 until 12).first { MusicalComponentVisualMapping.componentForSlot(it) == id }
        var best = -1f
        var frame: UniformFrame? = null
        repeat(12) { iteration ->
            val now = 1_000_000_000L + iteration * 50_000_000L
            adapter.waveform(waveform, now)
            val snapshot = adapter.fft(bytes, 48_000_000, now)
            repeat(3) { subFrame ->
                behavior.update(snapshot, 73, now + subFrame * 16_666_667L)
                val value = behavior.band(slot, balance)
                if (value > best) {
                    best = value
                    frame = UniformFrame(FloatArray(12) { behavior.band(it, balance) }, behavior.low,
                        behavior.mid, behavior.high, behavior.loudness, behavior.phase, behavior.impulse, behavior.buildup)
                }
            }
        }
        return frame!!
    }

    private class Fixture(val activity: Activity) : View(activity) {
        private val field = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        private val shader = RuntimeShader(GpuResonanceRenderBackend.FLUID_SHADER)
        private val paint = Paint()
        var committed = CountDownLatch(1); private set
        init {
            val pixels = IntArray(WIDTH * HEIGHT)
            for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
                val distance = (y * 255f / (HEIGHT - 1)).roundToInt()
                val progress = (x * 65535f / (WIDTH - 1)).roundToInt()
                pixels[y * WIDTH + x] = 0xff000000.toInt() or (distance shl 16) or progress
            }
            field.setPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            val input = BitmapShader(field, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            input.setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
            shader.setInputBuffer("field", input)
            shader.setFloatUniform("audio", 0f, 0f, 0f, 0f)
            shader.setFloatUniform("motion", 0f, 0f, 0f, 1f)
            shader.setFloatUniform("spectrum0", 0f, 0f, 0f, 0f)
            shader.setFloatUniform("spectrum1", 0f, 0f, 0f, 0f)
            shader.setFloatUniform("spectrum2", 0f, 0f, 0f, 0f)
            shader.setFloatUniform("strokeWidth", 9f)
            paint.shader = shader
            setBackgroundColor(Color.BLACK)
        }
        fun select(frame: UniformFrame, intensity: Float = 1f) {
            committed = CountDownLatch(1)
            shader.setFloatUniform("audio", frame.low, frame.mid, frame.high, frame.loudness)
            shader.setFloatUniform("motion", frame.phase, frame.impulse, frame.buildup, intensity)
            shader.setFloatUniform("spectrum0", frame.bands[0], frame.bands[1], frame.bands[2], frame.bands[3])
            shader.setFloatUniform("spectrum1", frame.bands[4], frame.bands[5], frame.bands[6], frame.bands[7])
            shader.setFloatUniform("spectrum2", frame.bands[8], frame.bands[9], frame.bands[10], frame.bands[11])
            viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            check(canvas.isHardwareAccelerated)
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        }
        override fun onDetachedFromWindow() { paint.shader = null; field.recycle(); super.onDetachedFromWindow() }
    }

    private fun copy(fixture: Fixture): Bitmap {
        assertTrue("Shader fixture frame committed", fixture.committed.await(10, TimeUnit.SECONDS))
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val point = IntArray(2); fixture.getLocationInWindow(point)
        val copied = CountDownLatch(1); var result = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(fixture.activity.window, Rect(point[0], point[1], point[0] + WIDTH, point[1] + HEIGHT), bitmap,
            { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue("PixelCopy callback", copied.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        return bitmap
    }
    private fun brightness(bitmap: Bitmap, slot: Int): Int {
        val center = ((slot + .5f) / 12f * (WIDTH - 1)).roundToInt()
        var peak = 0
        for (x in center - 2..center + 2) for (y in 0..3) {
            val color = bitmap.getPixel(x, y)
            peak = max(peak, max(Color.red(color), max(Color.green(color), Color.blue(color))))
        }
        return peak
    }
    companion object { private const val WIDTH = 384; private const val HEIGHT = 80 }
}
