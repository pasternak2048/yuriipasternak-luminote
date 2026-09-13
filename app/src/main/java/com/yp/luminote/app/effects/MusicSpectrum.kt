package com.yp.luminote.app.effects

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/**
 * Small, process-local FFT snapshot shared by the audio service and halo views.
 * The published arrays are never mutated after publication, so rendering can
 * safely read the newest spectrum from either overlay host.
 */
internal object MusicSpectrum {
    const val BAND_COUNT = 12

    private val silence = FloatArray(BAND_COUNT)
    private val currentFrame = AtomicReference(Frame(silence, 0L))

    fun bands(): FloatArray {
        val frame = currentFrame.get()
        if (SystemClock.elapsedRealtime() - frame.updatedAtMs <= STALE_SPECTRUM_TIMEOUT_MS) {
            return frame.bands
        }
        currentFrame.compareAndSet(frame, Frame(silence, 0L))
        return silence
    }

    fun publishFft(fft: ByteArray) {
        if (fft.size < 4) return

        val previous = currentFrame.get().bands
        val next = FloatArray(BAND_COUNT)
        val availableBins = (fft.size / 2 - 1).coerceAtLeast(1)

        for (band in 0 until BAND_COUNT) {
            val start = band * availableBins / BAND_COUNT + 1
            val end = ((band + 1) * availableBins / BAND_COUNT + 1).coerceAtMost(availableBins)
            var peak = 0f
            for (bin in start..end) {
                val real = fft[bin * 2].toFloat()
                val imaginary = fft[bin * 2 + 1].toFloat()
                peak = maxOf(peak, sqrt(real * real + imaginary * imaginary) / FFT_COMPONENT_MAX)
            }
            val target = (peak * RESPONSE_GAIN).coerceIn(0f, 1f)
            val smoothing = if (target > previous[band]) ATTACK_SMOOTHING else RELEASE_SMOOTHING
            next[band] = previous[band] * smoothing + target * (1f - smoothing)
        }

        currentFrame.set(Frame(next, SystemClock.elapsedRealtime()))
    }

    fun clear() {
        currentFrame.set(Frame(silence, 0L))
    }

    private const val FFT_COMPONENT_MAX = 181f
        private const val RESPONSE_GAIN = 2.05f
        private const val ATTACK_SMOOTHING = 0.14f
        private const val RELEASE_SMOOTHING = 0.64f
    private const val STALE_SPECTRUM_TIMEOUT_MS = 300L

    private data class Frame(
        val bands: FloatArray,
        val updatedAtMs: Long
    )
}
