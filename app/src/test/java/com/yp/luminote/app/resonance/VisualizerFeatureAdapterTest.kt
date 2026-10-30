package com.yp.luminote.app.resonance

import org.junit.Assert.*
import org.junit.Test

class VisualizerFeatureAdapterTest {
    private val size = 1024
    private fun waveform() = ByteArray(size) { if (it % 2 == 0) 168.toByte() else 88.toByte() }
    private fun frame(bin: Int, real: Int = 80, imaginary: Int = -30) = ByteArray(size).also {
        it[2 * bin] = real.toByte(); it[2 * bin + 1] = imaginary.toByte()
    }
    @Test fun signedComplexPackingDcAndNyquistHaveDocumentedIndices() {
        val bytes = byteArrayOf(-128, 64, -3, -4, 5, -12, 0, 0)
        assertEquals(1f, visualizerBinPower(bytes, 0), 0f)
        assertEquals(.25f, visualizerBinPower(bytes, 4), 0f)
        assertEquals(25f / 16384f, visualizerBinPower(bytes, 1), 0f)
        assertEquals(169f / 16384f, visualizerBinPower(bytes, 2), 0f)
    }
    @Test fun millihertzSamplingRateMapsKnownBinsToBands() {
        // 48,000,000mHz / 1024 => 46.875Hz/bin; bins 3,32,171 are bass,mid,high.
        for ((bin, expected) in listOf(3 to 0, 32 to 1, 171 to 2)) {
            val adapter = VisualizerFeatureAdapter(8, size)
            val wave = waveform(); val fft = frame(bin)
            var result = AudioFeatureSnapshot()
            repeat(20) {
                val time = 1_000_000_000L + it * 50_000_000L
                adapter.waveform(wave, time); result = adapter.fft(fft, 48_000_000, time)
            }
            val bands = listOf(result.low, result.mid, result.high)
            assertTrue(bands[expected] > .9f)
            assertTrue(bands.filterIndexed { i, _ -> i != expected }.all { it < .001f })
            assertEquals(8L, result.generation)
        }
    }
    @Test fun unsignedWaveformSilenceAndStalePairCannotCreateOnsets() {
        val adapter = VisualizerFeatureAdapter(1, size)
        val silence = ByteArray(size) { 128.toByte() }
        adapter.waveform(silence, 1_000_000_000L)
        repeat(30) {
            val feature = adapter.fft(frame(3), 48_000_000, 1_000_000_000L + it)
            assertEquals(0f, feature.loudness, 0f); assertEquals(0f, feature.onset, 0f)
            assertFalse(adapter.hasSignal)
        }
        adapter.waveform(waveform(), 2_000_000_000L)
        val stale = adapter.fft(frame(3), 48_000_000, 2_200_000_000L)
        assertEquals(0f, stale.loudness, 0f); assertFalse(adapter.hasSignal)
    }
    @Test fun ordinarySignalHasFastAttackAndSilenceRecovery() {
        val adapter = VisualizerFeatureAdapter(2, size)
        adapter.waveform(waveform(), 1_000_000_000)
        val attack = adapter.fft(frame(3), 48_000_000, 1_000_000_000)
        assertTrue(attack.low > .5f && attack.onset > .5f && adapter.hasSignal)
        var silence = attack
        repeat(120) {
            val time = 2_000_000_000L + it * 50_000_000L
            adapter.waveform(ByteArray(size) { 128.toByte() }, time)
            silence = adapter.fft(ByteArray(size), 48_000_000, time)
        }
        assertTrue(silence.low < .0001f && silence.loudness < .0001f)
        assertEquals(0f, silence.onset, 0f)
    }
    @Test fun captureSizeHonorsPlatformRangeAndBound() {
        assertEquals(1024, VisualizerAudioProvider.chooseCaptureSize(128, 1024))
        assertEquals(512, VisualizerAudioProvider.chooseCaptureSize(128, 512))
        assertEquals(2048, VisualizerAudioProvider.chooseCaptureSize(2048, 8192))
    }
    @Test(expected = IllegalArgumentException::class)
    fun unsupportedCaptureDimensionsFailClosed() { VisualizerAudioProvider.chooseCaptureSize(8192, 16384) }

    @Test fun twelveCompatibilitySlotsHaveDeterministicComponentOwnership() {
        // Intentional range overlap: 1500Hz belongs to both Mids and Vocal Presence.
        for ((bin, expected) in listOf(3 to setOf(1, 2), 32 to setOf(4, 5, 6), 171 to setOf(9, 10))) {
            val adapter = VisualizerFeatureAdapter(9, size)
            var snapshot = AudioFeatureSnapshot()
            repeat(20) { iteration ->
                val time = 1_000_000_000L + iteration * 50_000_000L
                adapter.waveform(waveform(), time)
                snapshot = adapter.fft(frame(bin), 48_000_000, time)
            }
            assertEquals(12, snapshot.spectrum.size)
            assertTrue(expected.all { snapshot.spectrum[it] > .3f })
            assertTrue(snapshot.spectrum.withIndex().all { (i, value) ->
                i in expected || value < .001f
            })
        }
    }
    @Test fun spectralSnapshotIsIndependentOfWorkerBufferReuse() {
        val adapter = VisualizerFeatureAdapter(10, size)
        val time = 1_000_000_000L
        adapter.waveform(waveform(), time)
        val first = adapter.fft(frame(3), 48_000_000, time)
        repeat(20) {
            val now = time + (it + 1) * 50_000_000L
            adapter.waveform(waveform(), now)
            adapter.fft(frame(171), 48_000_000, now)
        }
        assertEquals(12, first.spectrum.size)
        assertEquals(0f, first.spectrum[11], 0f)
        assertTrue(first.spectrum[2] > 0f)
    }
    @Test fun benchmarkNativeFftAdaptationWithoutAnotherTransform() {
        val adapter = VisualizerFeatureAdapter(1, size)
        val wave = waveform(); val spectrum = frame(3)
        repeat(100) {
            val time = System.nanoTime(); adapter.waveform(wave, time); adapter.fft(spectrum, 48_000_000, time)
        }
        val timings = LongArray(500)
        repeat(timings.size) {
            spectrum[7] = (it % 60 - 30).toByte()
            val start = System.nanoTime()
            adapter.waveform(wave, start); adapter.fft(spectrum, 48_000_000, start)
            timings[it] = System.nanoTime() - start
        }
        timings.sort()
        println("Visualizer adapter desktop JVM mean_us=${timings.average()/1000} p95_us=${timings[475]/1000} max_us=${timings.last()/1000}; synthetic adaptation performance only")
    }
}
