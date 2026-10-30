package com.yp.luminote.app.resonance

import java.util.Collections
import kotlin.math.*

/** Stable spectral proxies, not instrument identification or source separation. */
internal enum class MusicalComponentId {
    SUB_BASS, BASS, LOW_MIDS, MIDS, VOCAL_PRESENCE, HIGH_MIDS, TREBLE, AIR
}

internal data class MusicalComponentDefinition(
    val id: MusicalComponentId,
    val lowerHz: Float,
    val upperHz: Float,
    val attackSeconds: Float,
    val releaseSeconds: Float
)

/** Frequency interpretation and dynamics belong exclusively to the audio layer. */
internal object MusicalComponentDefinitions {
    val all: List<MusicalComponentDefinition> = Collections.unmodifiableList(listOf(
        MusicalComponentDefinition(MusicalComponentId.SUB_BASS, 20f, 60f, .055f, .340f),
        MusicalComponentDefinition(MusicalComponentId.BASS, 60f, 250f, .035f, .280f),
        MusicalComponentDefinition(MusicalComponentId.LOW_MIDS, 250f, 500f, .080f, .400f),
        MusicalComponentDefinition(MusicalComponentId.MIDS, 500f, 2000f, .090f, .420f),
        MusicalComponentDefinition(MusicalComponentId.VOCAL_PRESENCE, 1000f, 4000f, .100f, .450f),
        MusicalComponentDefinition(MusicalComponentId.HIGH_MIDS, 2000f, 6000f, .050f, .250f),
        MusicalComponentDefinition(MusicalComponentId.TREBLE, 4000f, 10000f, .025f, .180f),
        MusicalComponentDefinition(MusicalComponentId.AIR, 10000f, 16000f, .035f, .220f)
    ))
}

internal data class MusicalComponentValue(
    val id: MusicalComponentId,
    val normalizedEnergy: Float,
    val smoothedEnergy: Float,
    /** Positive weighted bin flux under the same bounded calibration; diagnostic, never a second visual boost. */
    val onset: Float,
    /** Relative spectral prominence only; never used as visual gain. */
    val relativeActivity: Float
) {
    init {
        require(normalizedEnergy.isFinite() && normalizedEnergy in 0f..1f)
        require(smoothedEnergy.isFinite() && smoothedEnergy in 0f..1f)
        require(onset.isFinite() && onset in 0f..1f)
        require(relativeActivity.isFinite() && relativeActivity in 0f..1f)
    }
}

/** Defensive immutable ownership: no worker buffers or caller-owned lists survive publication. */
internal class MusicalComponents(values: List<MusicalComponentValue>) {
    val values: List<MusicalComponentValue> = Collections.unmodifiableList(ArrayList(values))
    init { require(this.values.map { it.id } == MusicalComponentId.entries) }
    operator fun get(id: MusicalComponentId) = values[id.ordinal]
    companion object {
        val EMPTY = MusicalComponents(MusicalComponentId.entries.map { MusicalComponentValue(it, 0f, 0f, 0f, 0f) })
    }
}

/** Compatibility boundary: existing twelve stationary GPU slots retain their palette/optics. */
internal object MusicalComponentVisualMapping {
    private val slots = listOf(MusicalComponentId.SUB_BASS,
        MusicalComponentId.BASS, MusicalComponentId.BASS, MusicalComponentId.LOW_MIDS,
        MusicalComponentId.MIDS, MusicalComponentId.MIDS, MusicalComponentId.VOCAL_PRESENCE,
        MusicalComponentId.HIGH_MIDS, MusicalComponentId.HIGH_MIDS,
        MusicalComponentId.TREBLE, MusicalComponentId.TREBLE, MusicalComponentId.AIR)
    fun componentForSlot(index: Int) = slots[index]
    fun project(components: MusicalComponents): List<Float> =
        Collections.unmodifiableList(slots.map { components[it].smoothedEnergy })
}

/** Fixed eight-component workload. Bin-cell weights are rebuilt only on native sample-rate changes. */
internal class MusicalComponentAnalyzer(private val captureSize: Int) {
    private val weights = Array(MusicalComponentId.entries.size) { FloatArray(captureSize / 2 + 1) }
    private val normalized = FloatArray(MusicalComponentId.entries.size)
    private val smoothed = FloatArray(MusicalComponentId.entries.size)
    private val onsets = FloatArray(MusicalComponentId.entries.size)
    private var sampleRateMilliHertz = 0
    private var lastTimeNanos = 0L

    /** Returns true only for an actual rate transition, so initial musical attacks remain visible. */
    fun prepare(samplingRateMilliHertz: Int): Boolean {
        require(samplingRateMilliHertz > 0)
        if (sampleRateMilliHertz == samplingRateMilliHertz) return false
        val changed = sampleRateMilliHertz != 0
        sampleRateMilliHertz = samplingRateMilliHertz
        normalized.fill(0f); smoothed.fill(0f); onsets.fill(0f); lastTimeNanos = 0L
        val resolution = samplingRateMilliHertz.toDouble() / (1000.0 * captureSize)
        val nyquist = samplingRateMilliHertz / 2000.0
        for ((index, definition) in MusicalComponentDefinitions.all.withIndex()) {
            weights[index].fill(0f)
            // DC is never assigned. Nyquist has only its real coefficient and a half-width cell.
            for (bin in 1..captureSize / 2) {
                val lower = (bin - .5) * resolution
                val upper = min(nyquist, (bin + .5) * resolution)
                val overlap = max(0.0, min(upper, definition.upperHz.toDouble()) - max(lower, definition.lowerHz.toDouble()))
                weights[index][bin] = (overlap / (upper - lower)).toFloat().coerceIn(0f, 1f)
            }
        }
        return changed
    }

    fun analyze(power: FloatArray, positiveFlux: FloatArray, sharedReference: Float,
        hasSignal: Boolean, timestampNanos: Long): MusicalComponents {
        require(sampleRateMilliHertz > 0 && power.size == captureSize / 2 + 1 && positiveFlux.size == power.size)
        val dt = if (lastTimeNanos == 0L) .05f else ((timestampNanos - lastTimeNanos) / 1e9f).coerceIn(0f, .1f)
        lastTimeNanos = timestampNanos
        // Same global history as compatibility features, with a fixed feature-space gain ceiling.
        val reference = max(.35f, sharedReference)
        var maximum = .10f
        for ((index, definition) in MusicalComponentDefinitions.all.withIndex()) {
            var energy = 0f; var flux = 0f
            for (bin in 1 until power.size) {
                val weight = weights[index][bin]
                energy += power[bin] * weight
                flux += positiveFlux[bin] * weight
            }
            normalized[index] = if (hasSignal) compressed(energy, reference) else 0f
            onsets[index] = if (hasSignal) compressed(flux, reference) else 0f
            val seconds = if (normalized[index] > smoothed[index]) definition.attackSeconds else definition.releaseSeconds
            smoothed[index] += (normalized[index] - smoothed[index]) * (1f - exp(-dt / seconds))
            maximum = max(maximum, normalized[index])
        }
        return MusicalComponents(MusicalComponentId.entries.map { id ->
            val index = id.ordinal
            MusicalComponentValue(id, normalized[index], smoothed[index], onsets[index], normalized[index] / maximum)
        })
    }

    fun clear() {
        weights.forEach { it.fill(0f) }; normalized.fill(0f); smoothed.fill(0f); onsets.fill(0f)
        sampleRateMilliHertz = 0; lastTimeNanos = 0L
    }

    private fun compressed(power: Float, reference: Float): Float {
        val amplitude = 1.7f * sqrt(max(0f, power)) / reference
        return componentFeatureContrast(amplitude / (1f + amplitude))
    }
}

/** Shared continuous feature-space contrast, not an audio/RMS gate or component-specific AGC.
 * Existing Beat Response/optical amplification otherwise makes tiny impulses nearly full brightness.
 * Preserve all moderate/strong values exactly; weak values remain positive and smoothly subdued. */
internal fun componentFeatureContrast(compressedEnergy: Float): Float {
    val energy = compressedEnergy.coerceIn(0f, 1f)
    val t = (energy / .25f).coerceIn(0f, 1f)
    return energy * t * t * (3f - 2f * t)
}
