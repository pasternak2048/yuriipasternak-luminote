package com.yp.luminote.app.effects

import kotlin.math.floor

/**
 * Retained, animation-agnostic input for the GPU effect passes.
 *
 * Choreography writes this frame on the UI thread; a renderer consumes it during the same draw.
 * It deliberately contains no motion names, paths, Canvas state, or Android graphics objects.
 */
internal class GpuEffectFrame {
    var color: Int = OPAQUE_WHITE
        private set
    var intensity: Float = 1f
        private set
    var palette: GpuPalette? = null
        private set
    var palettePhase: Float = 0f
        private set

    private var emitterCount = 0
    private val emitters = Array(MAX_EMITTERS) { GpuEmitter() }

    fun reset(color: Int, intensity: Float): GpuEffectFrame {
        this.color = color
        this.intensity = intensity.finiteUnit()
        palette = null
        palettePhase = 0f
        emitterCount = 0
        return this
    }

    /** Palette creation/resampling belongs to configuration changes, not animation frames. */
    fun setPalette(palette: GpuPalette?, phase: Float = 0f): GpuEffectFrame {
        this.palette = palette?.takeIf { it.size >= 2 }
        palettePhase = phase.normalizedUnit()
        return this
    }

    /**
     * A centred hot source with an optional effect-local solid colour. A null colour inherits
     * this frame's solid/gradient treatment, so user palette changes remain global by default.
     */
    fun localizedSource(
        anchor: Float,
        radius: Float,
        energy: Float,
        width: Float = 1f,
        color: Int? = null,
        featherExponent: Float = DEFAULT_SOURCE_FEATHER
    ): GpuEffectFrame = appendRaw(
        anchor, 0, radius,
        coreLength = radius, bodyLength = 0f,
        coreEnergy = energy, bodyEnergy = 0f, bloomEnergy = 0f,
        coreWidth = width, bodyWidth = 1f, bloomWidth = 1f,
        coreColor = color, bodyColor = null, bloomColor = null,
        coreHeadTaper = 0f, bodyHeadTaper = 0f, bloomHeadTaper = 0f,
        coreRelease = 0.70f, bodyRelease = 0f, bloomRelease = 0f,
        coreFeather = featherExponent, bodyFeather = DEFAULT_BODY_FEATHER, bloomFeather = DEFAULT_BODY_FEATHER
    )

    fun directedBeam(
        head: Float,
        direction: Int,
        length: Float,
        core: GpuBand,
        body: GpuBand,
        bloom: GpuBand,
        energyScale: Float = 1f
    ): GpuEffectFrame {
        require(direction == -1 || direction == 1) { "A directed GPU beam needs direction -1 or 1." }
        return append(head, direction, length, core, body, bloom, energyScale)
    }

    /** A full closed contour is represented as one field, not repeated segments. */
    fun closedEmitter(
        energy: Float,
        width: Float = 1f,
        color: Int? = null,
        featherExponent: Float = DEFAULT_BODY_FEATHER
    ): GpuEffectFrame = appendRaw(
        0f, 0, 1f,
        coreLength = 0f, bodyLength = 1f,
        coreEnergy = 0f, bodyEnergy = energy, bloomEnergy = 0f,
        coreWidth = 1f, bodyWidth = width, bloomWidth = 1f,
        coreColor = null, bodyColor = color, bloomColor = null,
        coreHeadTaper = 0f, bodyHeadTaper = 0f, bloomHeadTaper = 0f,
        coreRelease = 0f, bodyRelease = 0.999f, bloomRelease = 0f,
        coreFeather = DEFAULT_BODY_FEATHER, bodyFeather = featherExponent, bloomFeather = DEFAULT_BODY_FEATHER
    )

    internal fun emitterCount(): Int = emitterCount
    internal fun emitter(index: Int): GpuEmitter {
        check(index in 0 until emitterCount)
        return emitters[index]
    }

    private fun append(
        anchor: Float,
        direction: Int,
        length: Float,
        core: GpuBand,
        body: GpuBand,
        bloom: GpuBand,
        energyScale: Float
    ): GpuEffectFrame {
        if (emitterCount == MAX_EMITTERS || !anchor.isFinite() || !length.isFinite()) return this
        emitters[emitterCount++].set(anchor.normalizedUnit(), direction, length.nonNegativeUnit(), core, body, bloom, energyScale)
        return this
    }

    private fun appendRaw(
        anchor: Float, direction: Int, length: Float,
        coreLength: Float, bodyLength: Float,
        coreEnergy: Float, bodyEnergy: Float, bloomEnergy: Float,
        coreWidth: Float, bodyWidth: Float, bloomWidth: Float,
        coreColor: Int?, bodyColor: Int?, bloomColor: Int?,
        coreHeadTaper: Float, bodyHeadTaper: Float, bloomHeadTaper: Float,
        coreRelease: Float, bodyRelease: Float, bloomRelease: Float,
        coreFeather: Float, bodyFeather: Float, bloomFeather: Float
    ): GpuEffectFrame {
        if (emitterCount == MAX_EMITTERS || !anchor.isFinite() || !length.isFinite()) return this
        emitters[emitterCount++].setRaw(
            anchor.normalizedUnit(), direction, length.nonNegativeUnit(),
            coreLength, bodyLength, coreEnergy, bodyEnergy, bloomEnergy,
            coreWidth, bodyWidth, bloomWidth, coreColor, bodyColor, bloomColor,
            coreHeadTaper, bodyHeadTaper, bloomHeadTaper,
            coreRelease, bodyRelease, bloomRelease, coreFeather, bodyFeather, bloomFeather
        )
        return this
    }

    internal companion object {
        const val MAX_EMITTERS = 4
        private const val OPAQUE_WHITE = -0x1
        private const val DEFAULT_SOURCE_FEATHER = 2.1f
        private const val DEFAULT_BODY_FEATHER = 1.85f
    }
}

/**
 * One continuous optical component of an emitter. Values are contour-normalized and retained by
 * [GpuEffectFrame]; callers may keep profiles as constants and vary only energy each frame.
 */
internal data class GpuBand(
    val length: Float,
    val width: Float,
    val energy: Float,
    val color: Int? = null,
    val headTaperFraction: Float = 0.08f,
    val releaseFraction: Float = 0.90f,
    val featherExponent: Float = 1.85f
)

/** Mutable to keep [GpuEffectFrame] allocation-free after construction. */
internal class GpuEmitter {
    var anchor = 0f; private set
    var direction = 0; private set
    var length = 0f; private set
    var coreLength = 0f; private set
    var bodyLength = 0f; private set
    var coreEnergy = 0f; private set
    var bodyEnergy = 0f; private set
    var bloomEnergy = 0f; private set
    var coreWidth = 1f; private set
    var bodyWidth = 1f; private set
    var bloomWidth = 1f; private set
    var coreColor = 0; private set
    var bodyColor = 0; private set
    var bloomColor = 0; private set
    var coreHasColor = false; private set
    var bodyHasColor = false; private set
    var bloomHasColor = false; private set
    var coreHeadTaper = 0f; private set
    var bodyHeadTaper = 0f; private set
    var bloomHeadTaper = 0f; private set
    var coreRelease = 0f; private set
    var bodyRelease = 0f; private set
    var bloomRelease = 0f; private set
    var coreFeather = 1.85f; private set
    var bodyFeather = 1.85f; private set
    var bloomFeather = 1.85f; private set

    internal fun set(anchor: Float, direction: Int, length: Float, core: GpuBand, body: GpuBand, bloom: GpuBand, energyScale: Float = 1f) {
        setRaw(
            anchor, direction, length,
            core.length, body.length, core.energy * energyScale, body.energy * energyScale, bloom.energy * energyScale,
            core.width, body.width, bloom.width,
            core.color, body.color, bloom.color,
            core.headTaperFraction, body.headTaperFraction, bloom.headTaperFraction,
            core.releaseFraction, body.releaseFraction, bloom.releaseFraction,
            core.featherExponent, body.featherExponent, bloom.featherExponent
        )
    }

    internal fun setRaw(
        anchor: Float, direction: Int, length: Float,
        coreLength: Float, bodyLength: Float,
        coreEnergy: Float, bodyEnergy: Float, bloomEnergy: Float,
        coreWidth: Float, bodyWidth: Float, bloomWidth: Float,
        coreColor: Int?, bodyColor: Int?, bloomColor: Int?,
        coreHeadTaper: Float, bodyHeadTaper: Float, bloomHeadTaper: Float,
        coreRelease: Float, bodyRelease: Float, bloomRelease: Float,
        coreFeather: Float, bodyFeather: Float, bloomFeather: Float
    ) {
        this.anchor = anchor; this.direction = direction; this.length = length
        this.coreLength = coreLength.nonNegativeUnit(); this.bodyLength = bodyLength.nonNegativeUnit()
        this.coreEnergy = coreEnergy.finiteUnit(); this.bodyEnergy = bodyEnergy.finiteUnit(); this.bloomEnergy = bloomEnergy.finiteUnit()
        this.coreWidth = coreWidth.positiveUnit(); this.bodyWidth = bodyWidth.positiveUnit(); this.bloomWidth = bloomWidth.positiveUnit()
        this.coreColor = coreColor ?: 0; this.bodyColor = bodyColor ?: 0; this.bloomColor = bloomColor ?: 0
        coreHasColor = coreColor != null; bodyHasColor = bodyColor != null; bloomHasColor = bloomColor != null
        this.coreHeadTaper = coreHeadTaper.profileUnit(); this.bodyHeadTaper = bodyHeadTaper.profileUnit(); this.bloomHeadTaper = bloomHeadTaper.profileUnit()
        this.coreRelease = coreRelease.releaseUnit(); this.bodyRelease = bodyRelease.releaseUnit(); this.bloomRelease = bloomRelease.releaseUnit()
        this.coreFeather = coreFeather.featherUnit(); this.bodyFeather = bodyFeather.featherUnit(); this.bloomFeather = bloomFeather.featherUnit()
    }
}

/** Fixed GPU palette storage. Call [configure] only when settings change. */
internal class GpuPalette {
    private val colors = IntArray(MAX_COLORS)
    var size: Int = 0
        private set

    fun configure(source: IntArray): GpuPalette {
        size = source.size.coerceIn(0, MAX_COLORS)
        if (size == 0) return this
        for (index in 0 until size) {
            val position = index.toFloat() / size
            colors[index] = samplePeriodic(source, position)
        }
        return this
    }

    fun colorAt(index: Int): Int {
        check(index in 0 until size)
        return colors[index]
    }

    /** Copies into renderer-owned uniform storage without exposing mutable palette state. */
    internal fun copyTo(destination: IntArray): Int {
        check(destination.size >= MAX_COLORS)
        for (index in 0 until MAX_COLORS) destination[index] = if (index < size) colors[index] else 0
        return size
    }

    private fun samplePeriodic(source: IntArray, position: Float): Int {
        if (source.size == 1) return source[0]
        val scaled = position.normalizedUnit() * source.size
        val low = floor(scaled).toInt() % source.size
        val high = (low + 1) % source.size
        return interpolateArgb(source[low], source[high], scaled - floor(scaled))
    }

    companion object { const val MAX_COLORS = 8 }
}

/** Premultiplied output witness for GPU-pass tests and non-black transparent-edge invariants. */
internal data class GpuPremultipliedColor(val red: Float, val green: Float, val blue: Float, val alpha: Float) {
    companion object {
        fun fromArgb(argb: Int, opacity: Float): GpuPremultipliedColor {
            val alpha = (((argb ushr 24) and 0xFF) / 255f * opacity.finiteUnit()).finiteUnit()
            return GpuPremultipliedColor(
                ((argb ushr 16) and 0xFF) / 255f * alpha,
                ((argb ushr 8) and 0xFF) / 255f * alpha,
                (argb and 0xFF) / 255f * alpha,
                alpha
            )
        }
    }
}

/** A terminal GPU failure stops the owning render generation; it never requests a Canvas fallback. */
internal class GpuRenderSession {
    private var generation = 0L
    private var terminalFailure: Throwable? = null

    fun beginGeneration() { generation++; terminalFailure = null }
    fun failure(): Throwable? = terminalFailure
    fun canSubmit(): Boolean = terminalFailure == null
    fun fail(error: Throwable): Throwable = terminalFailure ?: error.also { terminalFailure = it }
    fun generation(): Long = generation
}

private fun Float.finiteUnit(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f
private fun Float.positiveUnit(): Float = if (isFinite()) coerceIn(0.001f, 1f) else 0.001f
private fun Float.nonNegativeUnit(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f
private fun Float.normalizedUnit(): Float = if (isFinite()) ((this % 1f) + 1f) % 1f else 0f
private fun Float.profileUnit(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f
private fun Float.releaseUnit(): Float = if (isFinite()) coerceIn(0f, 0.999f) else 0f
private fun Float.featherUnit(): Float = if (isFinite()) coerceIn(0.25f, 6f) else 1.85f

private fun interpolateArgb(start: Int, end: Int, fraction: Float): Int {
    fun channel(shift: Int): Int = (((start ushr shift) and 0xFF) +
        ((((end ushr shift) and 0xFF) - ((start ushr shift) and 0xFF)) * fraction).toInt()).coerceIn(0, 255)
    return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}
