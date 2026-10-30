package com.yp.luminote.app.resonance

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class MusicalComponentsTest {
    private val toneBins = listOf(1, 3, 8, 16, 32, 64, 150, 256)
    private fun wave(size: Int) = ByteArray(size) { if (it % 2 == 0) 168.toByte() else 88.toByte() }
    private fun spectrum(size: Int, bins: List<Int>, amplitude: Int = 80) = ByteArray(size).also { bytes ->
        for (bin in bins) when (bin) {
            0 -> bytes[0] = amplitude.toByte()
            size / 2 -> bytes[1] = amplitude.toByte()
            else -> bytes[bin * 2] = amplitude.toByte()
        }
    }
    private class Signal(val size: Int = 1024, val adapter: VisualizerFeatureAdapter = VisualizerFeatureAdapter(27, size)) {
        var now = 1_000_000_000L
        fun frame(bytes: ByteArray, sampleRate: Int = 48_000_000): AudioFeatureSnapshot {
            adapter.waveform(ByteArray(size) { if (it % 2 == 0) 168.toByte() else 88.toByte() }, now)
            val result = adapter.fft(bytes, sampleRate, now)
            now += 50_000_000L
            return result
        }
    }

    @Test fun allEightStableComponentsRespondAndOnlyDocumentedOverlapLeaks() {
        val expected = listOf(setOf(MusicalComponentId.SUB_BASS, MusicalComponentId.BASS),
            setOf(MusicalComponentId.BASS), setOf(MusicalComponentId.LOW_MIDS), setOf(MusicalComponentId.MIDS),
            setOf(MusicalComponentId.MIDS, MusicalComponentId.VOCAL_PRESENCE),
            setOf(MusicalComponentId.VOCAL_PRESENCE, MusicalComponentId.HIGH_MIDS),
            setOf(MusicalComponentId.TREBLE), setOf(MusicalComponentId.AIR))
        for ((index, bin) in toneBins.withIndex()) {
            val signal = Signal()
            var snapshot = AudioFeatureSnapshot()
            repeat(40) { snapshot = signal.frame(spectrum(1024, listOf(bin))) }
            assertEquals(MusicalComponentId.entries, snapshot.components.values.map { it.id })
            snapshot.components.values.forEach {
                if (it.id in expected[index]) assertTrue("$bin -> ${it.id}", it.smoothedEnergy > .05f)
                else assertEquals("No unrelated leakage from bin $bin to ${it.id}", 0f, it.smoothedEnergy, .00001f)
            }
        }
    }

    @Test fun quietSustainedComponentsStayBelowLoudWithoutIndependentAutoGain() {
        for ((index, bin) in toneBins.withIndex()) {
            val quietSignal = Signal(); val loudSignal = Signal()
            var quiet = AudioFeatureSnapshot(); var loud = AudioFeatureSnapshot()
            repeat(400) {
                quiet = quietSignal.frame(spectrum(1024, listOf(bin), 3))
                loud = loudSignal.frame(spectrum(1024, listOf(bin), 80))
            }
            val id = MusicalComponentId.entries[index]
            val q = quiet.components[id]; val l = loud.components[id]
            assertTrue("Quiet $id remains subdued: $q", q.normalizedEnergy < .12f && q.smoothedEnergy < .12f)
            assertTrue("Feature-space amplitude distinguishes quiet/loud $id", l.smoothedEnergy > q.smoothedEnergy * 4)
            assertTrue("Sustain cannot normalize $id to one", l.normalizedEnergy < .64f)
            assertEquals("Sustained bins cease generating onsets", 0f, l.onset, 0f)
        }
    }

    @Test fun quietToLoudAndRhythmicImpulsesProduceBoundedNoticeablePeaks() {
        val signal = Signal()
        val quiet = spectrum(1024, listOf(3), 3)
        val loud = spectrum(1024, listOf(3), 80)
        var baseline = AudioFeatureSnapshot()
        repeat(80) { baseline = signal.frame(quiet) }
        val impulse = signal.frame(loud)
        val value = impulse.components[MusicalComponentId.BASS]
        assertTrue(value.normalizedEnergy > baseline.components[MusicalComponentId.BASS].normalizedEnergy * 4)
        assertTrue(value.smoothedEnergy > baseline.components[MusicalComponentId.BASS].smoothedEnergy * 3)
        assertTrue("Bass attack responds on first callback", value.onset > .5f)
        repeat(80) { signal.frame(ByteArray(1024)) }
        assertTrue(signal.frame(loud).components[MusicalComponentId.BASS].onset > .5f)
    }

    @Test fun samePowerToneSwapInsideComponentStillHasPositiveBinFlux() {
        val signal = Signal()
        repeat(20) { signal.frame(spectrum(1024, listOf(16))) }
        val swapped = signal.frame(spectrum(1024, listOf(18)))
        assertTrue(swapped.components[MusicalComponentId.MIDS].onset > .5f)
    }

    @Test fun fractionalResolutionAndNyquistAreHonestWithoutFabricatingDc() {
        for (size in listOf(128, 256, 512, 1024, 2048, 4096)) {
            for (sampleRate in listOf(44_100_000, 48_000_000)) {
                val signal = Signal(size)
                val result = signal.frame(spectrum(size, listOf(1)), sampleRate)
                val resolution = sampleRate.toDouble() / (1000 * size)
                val subCoverage = max(0.0, min(1.5 * resolution, 60.0) - max(.5 * resolution, 20.0)) / resolution
                val bassCoverage = max(0.0, min(1.5 * resolution, 250.0) - max(.5 * resolution, 60.0)) / resolution
                if (subCoverage == 0.0) assertEquals(0f, result.components[MusicalComponentId.SUB_BASS].normalizedEnergy, 0f)
                else assertTrue(result.components[MusicalComponentId.SUB_BASS].normalizedEnergy > 0f)
                if (subCoverage > 0 && bassCoverage > 0) {
                    val sub = result.components[MusicalComponentId.SUB_BASS].normalizedEnergy
                    val bass = result.components[MusicalComponentId.BASS].normalizedEnergy
                    assertEquals("Fractional overlap preserves partial amplitude evidence", subCoverage > bassCoverage, sub > bass)
                }
                val dc = Signal(size).frame(spectrum(size, listOf(0)), sampleRate)
                assertTrue(dc.components.values.all { it.normalizedEnergy == 0f && it.onset == 0f })
            }
        }
        val nyquist = Signal().frame(spectrum(1024, listOf(512)), 32_000_000)
        assertTrue("16kHz Nyquist real-only halfcell belongs to Air", nyquist.components[MusicalComponentId.AIR].normalizedEnergy > .5f)
        val outOfRange = Signal().frame(spectrum(1024, listOf(512)), 48_000_000)
        assertTrue("24kHz is not fabricated into any component", outOfRange.components.values.all { it.normalizedEnergy == 0f })
    }

    @Test fun sampleRateChangeRebuildsWeightsWithoutStaleOnsets() {
        val signal = Signal()
        val bytes = spectrum(1024, listOf(32))
        repeat(40) { signal.frame(bytes, 48_000_000) }
        val changed = signal.frame(bytes, 96_000_000)
        assertEquals("1500 -> 3000Hz no longer Mids", 0f, changed.components[MusicalComponentId.MIDS].normalizedEnergy, 0f)
        assertTrue(changed.components[MusicalComponentId.HIGH_MIDS].normalizedEnergy > 0f)
        assertEquals(0f, changed.onset, 0f)
        assertTrue(changed.components.values.all { it.onset == 0f })
    }

    @Test fun silenceAndOutOfRangeOnlyNeverGenerateComponents() {
        val signal = Signal()
        repeat(100) {
            val result = signal.frame(ByteArray(1024))
            assertTrue(result.components.values.all { it.normalizedEnergy == 0f && it.smoothedEnergy == 0f && it.onset == 0f && it.relativeActivity == 0f })
        }
        val ultrasound = signal.frame(spectrum(1024, listOf(450)))
        assertTrue(ultrasound.components.values.all { it.normalizedEnergy == 0f && it.smoothedEnergy == 0f })
    }

    @Test fun smearedBoundaryBinsExposeExpectedOverlapWithoutUnrelatedActivity() {
        val cases = listOf(
            1000 to setOf(MusicalComponentId.MIDS, MusicalComponentId.VOCAL_PRESENCE),
            2000 to setOf(MusicalComponentId.MIDS, MusicalComponentId.VOCAL_PRESENCE, MusicalComponentId.HIGH_MIDS),
            4000 to setOf(MusicalComponentId.VOCAL_PRESENCE, MusicalComponentId.HIGH_MIDS, MusicalComponentId.TREBLE),
            6000 to setOf(MusicalComponentId.HIGH_MIDS, MusicalComponentId.TREBLE),
            10000 to setOf(MusicalComponentId.TREBLE, MusicalComponentId.AIR))
        for ((boundary, expected) in cases) {
            val center = (boundary / 46.875).roundToInt()
            val bytes = ByteArray(1024)
            bytes[(center - 1) * 2] = 30; bytes[center * 2] = 80; bytes[(center + 1) * 2] = 30
            val value = Signal().frame(bytes)
            for (component in value.components.values) {
                if (component.id in expected) assertTrue("$boundary Hz smear -> ${component.id}", component.normalizedEnergy > 0f)
                else assertEquals("$boundary Hz smear excludes ${component.id}", 0f, component.normalizedEnergy, 0f)
            }
        }
    }

    @Test fun resolvedMidAndAirTonesRemainClassifiedAcrossCaptureDimensions() {
        for (size in listOf(128, 256, 512, 1024, 2048, 4096)) for (rate in listOf(44_100_000, 48_000_000)) {
            for ((frequency, expected) in listOf(750 to MusicalComponentId.MIDS, 12000 to MusicalComponentId.AIR)) {
                val bin = (frequency * size * 1000.0 / rate).roundToInt()
                val result = Signal(size).frame(spectrum(size, listOf(bin)), rate)
                assertTrue("$size/$rate $frequency Hz resolves $expected", result.components[expected].normalizedEnergy > .4f)
                assertTrue(result.components.values.all { it.id == expected || it.normalizedEnergy == 0f })
            }
        }
    }

    @Test fun trebleHasFasterAttackAndReleaseThanMidsAtMatchedNormalizedEnergy() {
        fun response(bin: Int, id: MusicalComponentId): Pair<Float, Float> {
            val analyzer = MusicalComponentAnalyzer(1024)
            analyzer.prepare(48_000_000)
            val power = FloatArray(513).also { it[bin] = .390625f }
            val flux = power.copyOf()
            val attack = analyzer.analyze(power, flux, .625f, true, 1_000_000_000)[id]
            assertEquals(1.7f / 2.7f, attack.normalizedEnergy, .00001f)
            repeat(80) { analyzer.analyze(power, FloatArray(513), .625f, true, 1_050_000_000L + it * 50_000_000L) }
            val release = analyzer.analyze(FloatArray(513), FloatArray(513), .625f, false, 5_050_000_000)[id]
            return attack.smoothedEnergy to release.smoothedEnergy
        }
        val mids = response(16, MusicalComponentId.MIDS)
        val treble = response(150, MusicalComponentId.TREBLE)
        assertTrue("Treble captures sharp attacks before sustained mids", treble.first > mids.first * 1.5f)
        assertTrue("Treble recovers faster than sustained mids", treble.second < mids.second)
    }

    @Test fun snapshotsAndProjectedSlotsDoNotBorrowMutableWorkerCollections() {
        val mutable = MusicalComponents.EMPTY.values.toMutableList()
        val owned = MusicalComponents(mutable)
        mutable.clear()
        assertEquals(8, owned.values.size)
        assertTrue(runCatching { (owned.values as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
        val signal = Signal()
        val old = signal.frame(spectrum(1024, listOf(3)))
        val oldValues = old.components.values.toList(); val oldSlots = old.spectrum.toList()
        repeat(50) { signal.frame(spectrum(1024, listOf(256))) }
        assertEquals(oldValues, old.components.values); assertEquals(oldSlots, old.spectrum)
        assertTrue(runCatching { (old.spectrum as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
        for (slot in 0 until 12) assertEquals(old.components[MusicalComponentVisualMapping.componentForSlot(slot)].smoothedEnergy, old.spectrum[slot], 0f)
    }

    @Test fun oneHotComponentsOwnOnlyTheirDocumentedFixedSlotsWithoutAmplitudeDivision() {
        val ownership = listOf(setOf(0), setOf(1, 2), setOf(3), setOf(4, 5), setOf(6), setOf(7, 8), setOf(9, 10), setOf(11))
        for (id in MusicalComponentId.entries) {
            val components = MusicalComponents(MusicalComponentId.entries.map {
                if (it == id) MusicalComponentValue(it, .4f, .35f, .1f, .5f)
                else MusicalComponentValue(it, 0f, 0f, 0f, 0f)
            })
            val projected = MusicalComponentVisualMapping.project(components)
            assertEquals(ownership[id.ordinal], projected.indices.filter { projected[it] > 0f }.toSet())
            ownership[id.ordinal].forEach { assertEquals(.35f, projected[it], 0f) }
        }
    }

    @Test fun sharedFeatureContrastIsContinuousMonotonicAndPreservesModerateSignals() {
        var previous = 0f
        for (i in 0..1000) {
            val input = i / 1000f
            val output = componentFeatureContrast(input)
            assertTrue(output >= previous && output <= input)
            if (input >= .25f) assertEquals(input, output, 0f)
            previous = output
        }
        assertEquals(0f, componentFeatureContrast(0f), 0f)
        assertTrue(componentFeatureContrast(.001f) > 0f)
        assertTrue(componentFeatureContrast(.102f) in .03f.. .04f)
        val delta = .0001f
        val leftSlope = (.25f - componentFeatureContrast(.25f - delta)) / delta
        val rightSlope = (componentFeatureContrast(.25f + delta) - .25f) / delta
        assertEquals("No abrupt knee slope", leftSlope, rightSlope, .01f)
    }

    @Test fun actualBeatResponseCanDriveEveryAssignedRegionDuringStrongMusicalChanges() {
        val allBins = toneBins
        for (mixed in listOf(false, true)) for ((index, bin) in toneBins.withIndex()) {
            val id = MusicalComponentId.entries[index]
            val signal = Signal(); val behavior = ResonanceBehavior()
            val bins = if (mixed) allBins else listOf(bin)
            val peak = FloatArray(3)
            repeat(12) {
                val snapshot = signal.frame(spectrum(1024, bins))
                repeat(3) { subFrame ->
                    behavior.update(snapshot, 27, snapshot.timestampNanos + subFrame * 16_666_667L)
                    for (balanceIndex in 0..2) for (slot in 0 until 12) {
                        if (MusicalComponentVisualMapping.componentForSlot(slot) == id) {
                            peak[balanceIndex] = max(peak[balanceIndex], behavior.band(slot, balanceIndex * .5f))
                        }
                    }
                }
            }
            val assigned = (0 until 12).filter { MusicalComponentVisualMapping.componentForSlot(it) == id }
            // All colors share the approved optical transfer again.
            // Teal's sustained floor is attenuated but transients still pass.
            val minimum = .20f
            assertTrue("$id mixed=$mixed Beat0/.5/1 peaks=${peak.toList()} need>$minimum", peak.all { it > minimum })
            println("Musical component route $id mixed=$mixed Beat0/.5/1 peaks=${peak.toList()} fixedSlots=$assigned")
        }
    }

    @Test fun tealSustainIsSubduedWithoutSuppressingBassAttacks() {
        val behavior = ResonanceBehavior()
        val stable = List(12) { .75f }
        var time = 1_000_000_000L
        repeat(240) {
            behavior.update(AudioFeatureSnapshot(generation = 27, timestampNanos = time,
                spectrum = stable), 27, time)
            time += 16_666_667L
        }
        val tealSteady = behavior.band(1, 0f)
        val midSteady = behavior.band(5, 0f)
        assertTrue("Teal sustained floor must be visibly lower", tealSteady < midSteady * .6f)
        assertTrue("Do not remove teal completely", tealSteady > .15f)
        val tealRhythmic = behavior.band(1, 1f)
        val midRhythmic = behavior.band(5, 1f)
        assertEquals("Beat-only mode still preserves bass transients",
            midRhythmic, tealRhythmic, .0001f)

        // Repeated percussion-style level jumps must still create bass peaks.
        repeat(40) {
            val off = List(12) { if (it == 1) 0f else .75f }
            behavior.update(AudioFeatureSnapshot(generation = 27, timestampNanos = time,
                spectrum = off), 27, time)
            time += 16_666_667L
        }
        val before = behavior.band(1, 0f)
        repeat(6) {
            behavior.update(AudioFeatureSnapshot(generation = 27, timestampNanos = time,
                spectrum = stable), 27, time)
            time += 16_666_667L
        }
        assertTrue("Bass attack must exceed quiet baseline", behavior.band(1, 0f) > before + .10f)
    }

    @Test fun establishedBeatBlendDimsSustainAndPreservesItsFullRange() {
        val signal = Signal(); val behavior = ResonanceBehavior()
        repeat(160) {
            val snapshot = signal.frame(spectrum(1024, listOf(16)))
            repeat(3) { subFrame -> behavior.update(snapshot, 27, snapshot.timestampNanos + subFrame * 16_666_667L) }
        }
        val steady = behavior.band(4, 0f); val transient = behavior.band(4, 1f)
        assertTrue(steady > .5f)
        assertTrue("Beat-only sustained energy remains subdued", transient < .10f)
        assertEquals((steady + transient) * .5f, behavior.band(4, .5f), .000001f)
    }
}
