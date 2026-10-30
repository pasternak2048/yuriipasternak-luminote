package com.yp.luminote.app.resonance

import kotlin.math.*

/** Visualizer supplies a packed signed-complex FFT; no second transform is performed. */
internal fun visualizerBinPower(fft: ByteArray, bin: Int): Float {
    require(fft.size >= 2 && bin in 0..fft.size / 2)
    val real: Int
    val imaginary: Int
    when (bin) {
        0 -> { real = fft[0].toInt(); imaginary = 0 }
        fft.size / 2 -> { real = fft[1].toInt(); imaginary = 0 }
        else -> { real = fft[2 * bin].toInt(); imaginary = fft[2 * bin + 1].toInt() }
    }
    return (real * real + imaginary * imaginary) / 16384f
}

/** Worker-confined; callback arrays are borrowed for this call only and never retained. */
internal class VisualizerFeatureAdapter(private val generation: Long, private val captureSize: Int) {
    init { require(captureSize in 128..4096 && captureSize and (captureSize - 1) == 0) }
    private val previousSpectrum = FloatArray(captureSize / 2 + 1)
    private val bands = FloatArray(3)
    private val spectralBands = FloatArray(12)
    private val spectralPower = FloatArray(12)
    // Log-spaced bins in Hz; low bands are limited by the Visualizer FFT resolution.
    private val spectralEdges = doubleArrayOf(0.0, 90.0, 140.0, 210.0, 320.0,
        480.0, 720.0, 1100.0, 1700.0, 2600.0, 4000.0, 6500.0, 16000.0)
    private val history = FloatArray(48)
    private var historyIndex = 0
    private var historyCount = 0
    private var waveformRms = 0f
    private var waveformTime = 0L
    private var spectralReference = .02f
    private var waveformReference = .03f
    private var envelope = 0f
    var hasSignal: Boolean = false; private set

    fun waveform(bytes: ByteArray, timestampNanos: Long) {
        require(bytes.size == captureSize)
        var squares = 0.0
        for (sample in bytes) {
            // Waveform alone is unsigned 8-bit centered on 128. FFT components remain signed.
            val centered = ((sample.toInt() and 255) - 128) / 128.0
            squares += centered * centered
        }
        waveformRms = sqrt(squares / captureSize).toFloat()
        waveformTime = timestampNanos
    }

    fun fft(bytes: ByteArray, samplingRateMilliHertz: Int, timestampNanos: Long): AudioFeatureSnapshot {
        require(bytes.size == captureSize && samplingRateMilliHertz > 0)
        val waveformFresh = waveformTime != 0L && timestampNanos >= waveformTime && timestampNanos - waveformTime <= 150_000_000L
        var low = 0f; var mid = 0f; var high = 0f; var total = 0f; var flux = 0f
        spectralPower.fill(0f)
        // DC is decoded by visualizerBinPower but excluded from musical energy. Nyquist is real-only.
        for (bin in 1..captureSize / 2) {
            val power = visualizerBinPower(bytes, bin)
            val hz = bin.toDouble() * samplingRateMilliHertz / (1000.0 * captureSize)
            when {
                hz < 250.0 -> low += power
                hz < 4000.0 -> mid += power
                hz <= 16000.0 -> high += power
            }
            if (hz < spectralEdges.last()) {
                val bandIndex = (0 until spectralBands.size).firstOrNull {
                    hz >= spectralEdges[it] && hz < spectralEdges[it + 1]
                }
                if (bandIndex != null) spectralPower[bandIndex] += power
            }
            total += power
            flux += max(0f, power - previousSpectrum[bin])
            previousSpectrum[bin] = power
        }
        hasSignal = waveformFresh && waveformRms > 1.5f / 128f && total > .0001f
        spectralReference = max(.02f, max(sqrt(total), spectralReference * .995f))
        waveformReference = max(.03f, max(waveformRms, waveformReference * .995f))
        val targetEnvelope = if (hasSignal) (waveformRms / waveformReference).coerceIn(0f, 1f) else 0f
        envelope = smooth(envelope, targetEnvelope, .65f, .12f)
        bands[0] = smooth(bands[0], normalized(low), .82f, .14f)
        bands[1] = smooth(bands[1], normalized(mid), .55f, .14f)
        bands[2] = smooth(bands[2], normalized(high), .55f, .14f)
        for (i in spectralBands.indices) {
            // Preserve relative spectral contrast: no independent auto-gain per bin.
            val target = if (hasSignal) (sqrt(spectralPower[i]) * 2.1f / spectralReference).coerceIn(0f, 1f) else 0f
            spectralBands[i] = smooth(spectralBands[i], target, .88f, .35f)
        }
        var recent = 0f
        for (i in 0 until historyCount) recent += history[i]
        recent /= max(1, historyCount)
        val trend = (envelope - recent).coerceIn(-1f, 1f)
        history[historyIndex] = envelope
        historyIndex = (historyIndex + 1) % history.size
        historyCount = min(historyCount + 1, history.size)
        val onset = if (hasSignal) (flux / max(total, .0001f)).coerceIn(0f, 1f) else 0f
        return AudioFeatureSnapshot(generation, timestampNanos, bands[0], bands[1], bands[2], envelope, onset, trend,
            spectralBands.toList())
    }

    private fun normalized(power: Float) = if (hasSignal) (sqrt(power) / spectralReference).coerceIn(0f, 1f) else 0f
    fun clear() {
        previousSpectrum.fill(0f); bands.fill(0f); spectralBands.fill(0f); spectralPower.fill(0f); history.fill(0f)
        waveformRms = 0f; waveformTime = 0L; envelope = 0f; hasSignal = false
    }
    private fun smooth(previous: Float, target: Float, attack: Float, release: Float) =
        previous + (target - previous) * if (target > previous) attack else release
}
