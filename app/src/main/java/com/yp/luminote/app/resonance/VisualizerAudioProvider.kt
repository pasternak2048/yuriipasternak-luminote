package com.yp.luminote.app.resonance

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

internal data class VisualizerConfiguration(val captureSize: Int, val callbackRateMilliHertz: Int,
    val samplingRateMilliHertz: Int)

/** Public output-mix API only. No AudioRecord, microphone input, projection, or recording. */
internal class VisualizerAudioProvider(
    context: Context,
    private val generation: Long,
    private val isCurrent: (Long) -> Boolean,
    private val onReady: (VisualizerConfiguration) -> Unit,
    private val onFeature: (AudioFeatureSnapshot, Long, Boolean) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onMissingFrames: () -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val thread = HandlerThread("ResonanceVisualizer")
    private var handler: Handler? = null
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val closedFuture = CompletableFuture<Unit>()
    private var visualizer: Visualizer? = null
    private var adapter: VisualizerFeatureAdapter? = null
    private var healthBuffer: ByteArray? = null
    private var lastFeatureTime = 0L

    @Synchronized fun start() {
        check(!closed.get())
        check(started.compareAndSet(false, true))
        thread.start()
        handler = Handler(thread.looper).also { it.post(::initialize) }
    }

    private fun initialize() {
        if (closed.get() || !isCurrent(generation)) { close(); return }
        try {
            checkPermission()
            val engine = Visualizer(0)
            visualizer = engine
            checked(engine.setEnabled(false), "disable before setup")
            val range = Visualizer.getCaptureSizeRange()
            val size = chooseCaptureSize(range[0], range[1])
            val rate = minOf(20_000, Visualizer.getMaxCaptureRate())
            check(rate > 0) { "No supported callback rate" }
            checked(engine.setCaptureSize(size), "capture size")
            checked(engine.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED), "normalized scaling")
            adapter = VisualizerFeatureAdapter(generation, size)
            healthBuffer = ByteArray(size)
            checked(engine.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: Visualizer, waveform: ByteArray, samplingRate: Int) {
                    consume { adapter?.waveform(waveform, System.nanoTime()) }
                }
                override fun onFftDataCapture(visualizer: Visualizer, fft: ByteArray, samplingRate: Int) {
                    consume {
                        val start = System.nanoTime()
                        val analyzer = adapter ?: return@consume
                        val snapshot = analyzer.fft(fft, samplingRate, start)
                        lastFeatureTime = start
                        if (!closed.get() && isCurrent(generation)) onFeature(snapshot, System.nanoTime() - start, analyzer.hasSignal)
                    }
                }
            }, rate, true, true), "data listener")
            checked(engine.setEnabled(true), "enable")
            if (closed.get() || !isCurrent(generation)) { close(); return }
            onReady(VisualizerConfiguration(size, rate, engine.samplingRate))
            lastFeatureTime = System.nanoTime()
            handler?.postDelayed(healthCheck, 1000)
        } catch (error: RuntimeException) { fail("Visualizer output mix unavailable: ${error.javaClass.simpleName}") }
    }

    private inline fun consume(block: () -> Unit) {
        if (closed.get() || !isCurrent(generation)) return
        try { checkPermission(); block() }
        catch (error: RuntimeException) { fail("Visualizer callback failed: ${error.javaClass.simpleName}") }
    }

    /** Public status probe detects dead native audio objects even if callbacks cease. No visual timing. */
    private val healthCheck = object : Runnable {
        override fun run() {
            if (closed.get()) return
            if (!isCurrent(generation)) { close(); return }
            consume {
                val engine = visualizer ?: return@consume
                val buffer = healthBuffer ?: return@consume
                try { checked(engine.getFft(buffer), "native audio health") }
                finally { buffer.fill(0) }
                if (System.nanoTime() - lastFeatureTime > 500_000_000L) onMissingFrames()
            }
            if (!closed.get()) handler?.postDelayed(this, 1000)
        }
    }

    private fun checkPermission() {
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            appContext.checkSelfPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Output-mix visualization permission unavailable")
        }
    }
    private fun fail(reason: String) {
        if (closed.get()) return
        close()
        onFailure(reason)
    }

    /** Resolves only after listener removal, native release, queued callback drain, and worker exit. */
    @Synchronized fun close(): CompletableFuture<Unit> {
        if (!closed.compareAndSet(false, true)) return closedFuture
        val worker = handler
        if (worker == null) { closedFuture.complete(Unit); return closedFuture }
        val posted = worker.post {
            worker.removeCallbacks(healthCheck)
            releaseResources()
            thread.quitSafely()
        }
        // Defensive shutdown if the worker looper was already terminated unexpectedly.
        if (!posted) { releaseResources(); thread.quitSafely() }
        CoroutineScope(Dispatchers.IO).launch {
            thread.join()
            closedFuture.complete(Unit)
        }
        return closedFuture
    }

    private fun releaseResources() {
        visualizer?.let { engine ->
            runCatching { engine.setEnabled(false) }
            runCatching { engine.setDataCaptureListener(null, 0, false, false) }
            runCatching { engine.release() }
        }
        visualizer = null
        adapter?.clear(); adapter = null
        healthBuffer?.fill(0); healthBuffer = null
    }

    companion object {
        internal fun chooseCaptureSize(minimum: Int, maximum: Int): Int {
            require(minimum > 0 && maximum >= minimum)
            var size = 128
            while (size < minimum && size < 4096) size *= 2
            require(size >= minimum && size <= maximum && size <= 4096)
            while (size < 1024 && size * 2 <= maximum) size *= 2
            return size
        }
        private fun checked(status: Int, operation: String) {
            check(status == Visualizer.SUCCESS) { "Visualizer $operation status=$status" }
        }
    }
}
