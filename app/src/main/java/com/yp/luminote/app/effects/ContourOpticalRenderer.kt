package com.yp.luminote.app.effects

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Mesh
import android.graphics.MeshSpecification
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.yp.luminote.app.BuildConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** CPU witness of the mesh fragment field; tests protect its seam and direction semantics. */
internal object ContourOpticalFieldMath {
    fun lobeEnergy(
        contourU: Float,
        anchorU: Float,
        spanFraction: Float,
        energy: Float,
        direction: Int
    ): Float {
        if (!contourU.isFinite() || !anchorU.isFinite() || !spanFraction.isFinite() ||
            !energy.isFinite() || spanFraction <= 0f || energy <= 0f || direction !in -1..1
        ) return 0f
        val delta = circularDelta(contourU, anchorU)
        val longitudinal = if (direction == 0) {
            kotlin.math.abs(delta)
        } else {
            val behindHead = -delta * direction
            if (behindHead < 0f) return 0f
            behindHead
        }
        return ((1f - smoothStep(0f, spanFraction, longitudinal)) * energy).coerceIn(0f, 1f)
    }

    /** A directional interval begins after [startOffsetFraction] behind its head. */
    fun intervalLobeEnergy(
        contourU: Float,
        anchorU: Float,
        startOffsetFraction: Float,
        spanFraction: Float,
        energy: Float,
        direction: Int
    ): Float {
        if (direction == 0 || !startOffsetFraction.isFinite() || startOffsetFraction < 0f) return 0f
        if (!contourU.isFinite() || !anchorU.isFinite() || !spanFraction.isFinite() ||
            !energy.isFinite() || spanFraction <= 0f || energy <= 0f || direction !in -1..1
        ) return 0f
        val behindHead = -circularDelta(contourU, anchorU) * direction
        if (behindHead < startOffsetFraction || behindHead > startOffsetFraction + spanFraction) return 0f
        return ((1f - smoothStep(startOffsetFraction, startOffsetFraction + spanFraction, behindHead)) * energy)
            .coerceIn(0f, 1f)
    }

    fun profiledLobeEnergy(
        contourU: Float,
        anchorU: Float,
        startOffsetFraction: Float,
        spanFraction: Float,
        energy: Float,
        direction: Int,
        headTaperFraction: Float,
        releaseFraction: Float
    ): Float {
        if (!headTaperFraction.isFinite() || !releaseFraction.isFinite()) return 0f
        val distance = if (direction == 0) kotlin.math.abs(circularDelta(contourU, anchorU)) else {
            val behindHead = -circularDelta(contourU, anchorU) * direction
            if (behindHead < 0f) return 0f
            behindHead
        }
        if (distance < startOffsetFraction || distance > startOffsetFraction + spanFraction ||
            spanFraction <= 0f || energy <= 0f
        ) return 0f
        val normalized = ((distance - startOffsetFraction) / spanFraction).coerceIn(0f, 1f)
        // A localized source is centred on its anchor: its centre must stay a hot core. Only
        // directed fields have a leading edge that grows in behind the travelling head.
        val taper = if (direction == 0 || headTaperFraction <= 0f) 1f else {
            smoothStep(0f, headTaperFraction.coerceIn(MIN_PROFILE_FRACTION, 1f), normalized)
        }
        val releaseStart = releaseFraction.coerceIn(0f, MAX_RELEASE_FRACTION)
        val release = 1f - smoothStep(releaseStart, 1f, normalized)
        return (energy * taper * release).coerceIn(0f, 1f)
    }

    fun transverseEnergy(contourV: Float): Float {
        if (!contourV.isFinite()) return 0f
        val base = (1f - kotlin.math.abs(contourV - 0.5f) * 2f).coerceIn(0f, 1f)
        return Math.pow(base.toDouble(), 1.85).toFloat()
    }

    fun alpha(longitudinal: Float, contourV: Float, intensity: Float): Float {
        if (!longitudinal.isFinite() || !intensity.isFinite()) return 0f
        return displayAlpha(longitudinal * transverseEnergy(contourV), intensity)
    }

    /** Bounded pre-intensity lift: finite slope at zero preserves smooth tail endpoints. */
    fun displayAlpha(field: Float, intensity: Float = 1f): Float {
        val x = field.coerceIn(0f, 1f)
        return (intensity.coerceIn(0f, 1f) * x * (2f - x)).coerceIn(0f, 1f)
    }

    private fun circularDelta(value: Float, anchor: Float): Float =
        (((value - anchor + 0.5f) % 1f + 1f) % 1f) - 0.5f

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private const val MIN_PROFILE_FRACTION = 0.001f
    private const val MAX_RELEASE_FRACTION = 0.999f
}

/**
 * Four emitters, each with core/body/bloom, fit in one bounded Mesh field. Keep this in lockstep
 * with the explicitly unrolled AGSL evaluation below: AGSL arrays are not used here so uniforms
 * retain stable names and no driver-dependent array indexing is required.
 */
internal const val CONTOUR_MAX_LOBES = 12
internal const val CONTOUR_MAX_PALETTE_COLORS = 8
internal const val CONTOUR_OPTICAL_LOG_TAG = "ContourOptical"

/**
 * Mesh output is modulated with Paint. An opaque neutral-white Paint preserves the fragment
 * shader's premultiplied RGB/alpha; default opaque black erases it, while transparent Paint
 * suppresses it on the Pixel renderer.
 */
internal val CONTOUR_OPTICAL_MESH_BLEND_MODE: BlendMode = BlendMode.MODULATE

internal fun contourOpticalMeshPaint(): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        color = Color.WHITE
    }

internal enum class ContourOpticalVisibilityStage { OPAQUE_MESH, CONSTANT_AGSL, OPTICAL_FIELD }

/** Debug-only, three-frame GPU visibility isolator. Enable with `adb shell setprop log.tag.ContourOptical VERBOSE`, then restart the app. */
internal class ContourOpticalVisibilityProbe(
    private val enabled: Boolean = BuildConfig.DEBUG && Log.isLoggable(CONTOUR_OPTICAL_LOG_TAG, Log.VERBOSE),
    private val sink: (String) -> Unit = { Log.d(CONTOUR_OPTICAL_LOG_TAG, it) }
) {
    private var key: ContourRibbonGeometryKey? = null
    private var next = 0
    private var framesInStage = 0
    fun nextStage(geometryKey: ContourRibbonGeometryKey): ContourOpticalVisibilityStage? {
        if (!enabled) return null
        if (key != geometryKey) { key = geometryKey; next = 0; framesInStage = 0 }
        val stage = ContourOpticalVisibilityStage.entries.getOrNull(next) ?: return null
        framesInStage++
        if (framesInStage == 1) sink("visibility probe enter stage=${stage.name} geometry=$geometryKey frames=$FRAMES_PER_STAGE")
        if (framesInStage == FRAMES_PER_STAGE) {
            sink("visibility probe exit stage=${stage.name} geometry=$geometryKey frames=$framesInStage")
            next++
            framesInStage = 0
        }
        return stage
    }

    private companion object { const val FRAMES_PER_STAGE = 120 }
}

/** Explicit GPU submission outcome; callers can terminate an effect without double-rendering. */
internal enum class ContourOpticalDrawResult {
    DRAWN,
    EMPTY_FIELD,
    UNSUPPORTED_HARDWARE,
    GPU_UNAVAILABLE
}

internal data class ContourOpticalTerminalFailure(val throwableClass: String, val message: String?)

internal data class ContourOpticalSubmissionResult(
    val outcome: ContourOpticalDrawResult,
    val terminalFailure: ContourOpticalTerminalFailure? = null
)

/** Debug-visible lifecycle milestones, deliberately independent from opt-in performance metrics. */
internal class ContourOpticalDebugTrace(
    private val enabled: Boolean = BuildConfig.DEBUG,
    private val sink: (String) -> Unit = { message -> Log.d(CONTOUR_OPTICAL_LOG_TAG, message) }
) {
    private var routeLogged = false
    private var hardwareLogged = false
    private var geometryLogged = false
    private var submissionKey: ContourRibbonGeometryKey? = null
    private var drawnKey: ContourRibbonGeometryKey? = null
    private var terminalKey: ContourRibbonGeometryKey? = null

    fun routeEntered() = once(predicate = { !routeLogged }) { routeLogged = true; "route entered" }
    fun hardwareRejected() = once(predicate = { !hardwareLogged }) { hardwareLogged = true; "hardware canvas rejected" }
    fun geometryUnavailable() = once(predicate = { !geometryLogged }) { geometryLogged = true; "geometry unavailable" }
    fun submissionStarted(key: ContourRibbonGeometryKey) = once(predicate = { submissionKey != key }) { submissionKey = key; "Mesh submission started for geometry=$key" }
    fun drawn(key: ContourRibbonGeometryKey) = once(predicate = { drawnKey != key }) { drawnKey = key; "drawn for geometry=$key" }
    fun terminal(key: ContourRibbonGeometryKey, failure: ContourOpticalTerminalFailure) = once(predicate = { terminalKey != key }) {
        terminalKey = key
        "terminal failure for geometry=$key; ${failure.throwableClass}: ${failure.message ?: "(no message)"}"
    }
    fun reset() {
        routeLogged = false; hardwareLogged = false; geometryLogged = false
        submissionKey = null; drawnKey = null; terminalKey = null
    }
    private inline fun once(predicate: () -> Boolean, message: () -> String) {
        if (enabled && predicate()) sink(message())
    }
}

/** Renderer-local sticky fatal state, independently testable without a native Canvas. */
internal class ContourOpticalSubmissionState {
    private var failedKey: ContourRibbonGeometryKey? = null
    private var failure: ContourOpticalTerminalFailure? = null

    fun failureFor(key: ContourRibbonGeometryKey): ContourOpticalTerminalFailure? =
        failure?.takeIf { failedKey == key }

    fun markFatal(key: ContourRibbonGeometryKey, throwable: Throwable): ContourOpticalTerminalFailure {
        failureFor(key)?.let { return it }
        return ContourOpticalTerminalFailure(throwable.javaClass.name, throwable.message).also {
            failedKey = key
            failure = it
        }
    }

    fun clear() {
        failedKey = null
        failure = null
    }
}

/**
 * Animation-agnostic input to the contour optical pipeline.
 *
 * A lobe is deliberately expressed in contour coordinates rather than animation names. Twelve
 * lobes cover four independently coloured core/body/bloom emitters while Snake normally needs
 * three. The frame is mutable and renderer-owned so callers can update it without allocating on
 * every display frame.
 */
internal class OpticalFieldFrame {
    var intensity: Float = 1f
        private set
    var color: Int = 0xFFFFFFFF.toInt()
        private set
    private var count = 0
    private var rejectedLobeCount = 0
    private var paletteCount = 0
    private var palettePhase = 0f
    private val anchors = FloatArray(CONTOUR_MAX_LOBES)
    private val starts = FloatArray(CONTOUR_MAX_LOBES)
    private val spans = FloatArray(CONTOUR_MAX_LOBES)
    private val energies = FloatArray(CONTOUR_MAX_LOBES)
    private val directions = FloatArray(CONTOUR_MAX_LOBES)
    private val headTapers = FloatArray(CONTOUR_MAX_LOBES)
    private val releases = FloatArray(CONTOUR_MAX_LOBES)
    private val widths = FloatArray(CONTOUR_MAX_LOBES)
    private val featherExponents = FloatArray(CONTOUR_MAX_LOBES)
    private val colors = IntArray(CONTOUR_MAX_LOBES)
    private val explicitColors = FloatArray(CONTOUR_MAX_LOBES)
    private val palette = IntArray(CONTOUR_MAX_PALETTE_COLORS)

    fun reset(color: Int, intensity: Float): OpticalFieldFrame {
        this.color = color or OPAQUE_ALPHA
        this.intensity = intensity.coerceIn(0f, 1f)
        count = 0
        rejectedLobeCount = 0
        paletteCount = 0
        palettePhase = 0f
        return this
    }

    /**
     * [direction] is +1 or -1 for a travelling tail and 0 for a symmetric localized source.
     * [spanFraction] is expressed in normalized contour length and includes the zero-alpha tail.
     */
    fun addLobe(
        anchorFraction: Float,
        spanFraction: Float,
        energy: Float,
        direction: Int
    ): OpticalFieldFrame = addLobe(anchorFraction, 0f, spanFraction, energy, direction)

    /**
     * A nonzero [startOffsetFraction] is meaningful only for directional lobes: it reserves the
     * adjacent core interval, then starts a tapering tail without overlapping that core.
     */
    fun addLobe(
        anchorFraction: Float,
        startOffsetFraction: Float,
        spanFraction: Float,
        energy: Float,
        direction: Int
    ): OpticalFieldFrame = addLobe(
        anchorFraction, startOffsetFraction, spanFraction, energy, direction,
        headTaperFraction = 0f, releaseFraction = 0f, widthFraction = 1f, featherExponent = 1.85f
    )

    /**
     * Generic contour-field profile. A lobe rises smoothly over [headTaperFraction], can hold a
     * bright body until [releaseFraction], then fades to zero at its interval end. Width and
     * feather are expressed in normalized ribbon space, avoiding additional geometry or passes.
     */
    fun addLobe(
        anchorFraction: Float,
        startOffsetFraction: Float,
        spanFraction: Float,
        energy: Float,
        direction: Int,
        headTaperFraction: Float,
        releaseFraction: Float,
        widthFraction: Float,
        featherExponent: Float,
        color: Int? = null
    ): OpticalFieldFrame {
        if (count == CONTOUR_MAX_LOBES || !anchorFraction.isFinite() || !startOffsetFraction.isFinite() ||
            startOffsetFraction < 0f || !spanFraction.isFinite() ||
            !energy.isFinite() || !headTaperFraction.isFinite() || !releaseFraction.isFinite() ||
            !widthFraction.isFinite() || !featherExponent.isFinite() || spanFraction <= 0f || energy <= 0f || direction !in -1..1
        ) {
            if (count == CONTOUR_MAX_LOBES) rejectedLobeCount++
            return this
        }
        anchors[count] = normalized(anchorFraction)
        starts[count] = startOffsetFraction.coerceIn(0f, MAX_SPAN_FRACTION)
        spans[count] = spanFraction.coerceIn(MIN_SPAN_FRACTION, MAX_SPAN_FRACTION)
        energies[count] = energy.coerceIn(0f, 1f)
        directions[count] = direction.toFloat()
        headTapers[count] = headTaperFraction.coerceIn(0f, 1f)
        releases[count] = releaseFraction.coerceIn(0f, MAX_RELEASE_FRACTION)
        widths[count] = widthFraction.coerceIn(MIN_WIDTH_FRACTION, 1f)
        featherExponents[count] = featherExponent.coerceIn(MIN_FEATHER_EXPONENT, MAX_FEATHER_EXPONENT)
        // Optical alpha is evaluated exclusively by the field. A local effect colour with a
        // transparent source alpha would otherwise modulate a nonzero lobe into a dark/white
        // fringe under the retained white Paint + MODULATE composition. Keep local colours in
        // the same opaque-colour contract as the frame and palette uniforms.
        colors[count] = (color ?: this.color) or OPAQUE_ALPHA
        explicitColors[count] = if (color == null) 0f else 1f
        count++
        return this
    }

    internal fun lobeCount(): Int = count
    /** Bounded admission witness for diagnostics/tests; capacity loss must never be silent. */
    internal fun rejectedLobeCount(): Int = rejectedLobeCount

    internal fun lobe(index: Int, out: FloatArray) {
        check(index in 0 until CONTOUR_MAX_LOBES && out.size >= LOBE_COMPONENTS)
        if (index >= count) {
            out[0] = 0f; out[1] = 0f; out[2] = 1f; out[3] = 0f
        } else {
            out[0] = anchors[index]; out[1] = starts[index]
            out[2] = spans[index]; out[3] = energies[index]
        }
    }

    internal fun lobeDirection(index: Int): Float = if (index in 0 until count) directions[index] else 0f
    internal fun lobeProfile(index: Int, out: FloatArray) {
        check(index in 0 until CONTOUR_MAX_LOBES && out.size >= PROFILE_COMPONENTS)
        if (index >= count) {
            out[0] = 0f; out[1] = 0f; out[2] = 1f; out[3] = DEFAULT_FEATHER_EXPONENT
        } else {
            out[0] = headTapers[index]; out[1] = releases[index]
            out[2] = widths[index]; out[3] = featherExponents[index]
        }
    }

    /** A lobe may override the frame palette with a solid effect-local colour. */
    internal fun lobeColor(index: Int): Int = if (index in 0 until count) colors[index] else color
    internal fun lobeHasExplicitColor(index: Int): Float = if (index in 0 until count) explicitColors[index] else 0f

    /**
     * Resamples every nonempty source palette into a bounded periodic GPU representation. The
     * source array is read once into retained frame state; no draw-time allocation occurs.
     */
    fun setPalette(colors: IntArray, phase: Float): OpticalFieldFrame {
        if (colors.size < 2 || !phase.isFinite()) return this
        paletteCount = minOf(colors.size, CONTOUR_MAX_PALETTE_COLORS)
        palettePhase = normalized(phase)
        for (index in 0 until paletteCount) {
            palette[index] = sampledPaletteColor(colors, index.toFloat() / paletteCount) or OPAQUE_ALPHA
        }
        return this
    }

    /** Receives fixed, pre-resampled GPU palette storage; used on every frame without sampling. */
    fun setPaletteUniforms(colors: IntArray, count: Int, phase: Float): OpticalFieldFrame {
        if (count < 2 || colors.size < CONTOUR_MAX_PALETTE_COLORS || !phase.isFinite()) return this
        paletteCount = count.coerceIn(2, CONTOUR_MAX_PALETTE_COLORS)
        palettePhase = normalized(phase)
        for (index in 0 until paletteCount) palette[index] = colors[index] or OPAQUE_ALPHA
        return this
    }

    internal fun paletteCount(): Int = paletteCount
    internal fun palettePhase(): Float = palettePhase
    internal fun paletteColor(index: Int): Int {
        check(index in 0 until CONTOUR_MAX_PALETTE_COLORS)
        return if (index < paletteCount) palette[index] else color
    }

    private fun sampledPaletteColor(colors: IntArray, position: Float): Int {
        val scaled = normalized(position) * colors.size
        val low = scaled.toInt() % colors.size
        val high = (low + 1) % colors.size
        val fraction = scaled - scaled.toInt()
        return blendArgb(colors[low], colors[high], fraction)
    }

    private fun blendArgb(start: Int, end: Int, fraction: Float): Int {
        fun channel(shift: Int): Int = (((start ushr shift) and 0xFF) +
            ((((end ushr shift) and 0xFF) - ((start ushr shift) and 0xFF)) * fraction).toInt()).coerceIn(0, 255)
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun normalized(value: Float): Float = ((value % 1f) + 1f) % 1f

    private companion object {
        const val LOBE_COMPONENTS = 4
        const val PROFILE_COMPONENTS = 4
        const val MIN_SPAN_FRACTION = 0.001f
        const val MAX_SPAN_FRACTION = 0.5f
        const val MAX_RELEASE_FRACTION = 0.999f
        const val MIN_WIDTH_FRACTION = 0.01f
        const val MIN_FEATHER_EXPONENT = 0.25f
        const val MAX_FEATHER_EXPONENT = 6f
        const val DEFAULT_FEATHER_EXPONENT = 1.85f
        const val OPAQUE_ALPHA = -0x1000000
    }
}

/**
 * GPU implementation of one continuous premultiplied contour-space optical field.
 *
 * Geometry and its direct buffer are rebuilt only for a new [ContourRibbonGeometry.cacheKey].
 * Per-frame work changes the mesh's fixed uniform set then issues one draw; it never samples a
 * path, constructs a shader, or creates a mesh.
 */
internal class ContourOpticalRenderer {
    // MeshSpecification reaches native graphics code. Keep it lazy so construction is safe for
    // JVM tests, static calibration previews, and any legacy-only render session.
    private var specification: MeshSpecification? = null
    private val paint = contourOpticalMeshPaint()
    private val opaqueProbePaint = Paint().apply { color = Color.MAGENTA }
    private val visibilityProbe = ContourOpticalVisibilityProbe()
    private val lobeUniform = FloatArray(4)
    private val profileUniform = FloatArray(4)
    private val effectFrameAdapter = OpticalFieldFrame()
    private val paletteUniforms = IntArray(CONTOUR_MAX_PALETTE_COLORS)
    private var cachedKey: ContourRibbonGeometryKey? = null
    private var mesh: Mesh? = null
    private var vertexBuffer: FloatBuffer? = null
    private var fieldDiagnosticKey: ContourRibbonGeometryKey? = null
    private val submissionState = ContourOpticalSubmissionState()
    private val debugTrace = ContourOpticalDebugTrace()

    /** Compatibility helper; GPU-first callers should inspect [drawResult] directly. */
    fun draw(canvas: Canvas, geometry: ContourRibbonGeometry, frame: OpticalFieldFrame): Boolean =
        drawResult(canvas, geometry, frame).outcome == ContourOpticalDrawResult.DRAWN

    /**
     * Contract bridge for the unified engine. It performs only retained-array writes; no shader,
     * Mesh, geometry, or palette allocations occur per frame.
     */
    fun drawResult(canvas: Canvas, geometry: ContourRibbonGeometry, frame: GpuEffectFrame): ContourOpticalSubmissionResult {
        val optical = effectFrameAdapter.reset(frame.color, frame.intensity)
        frame.palette?.let { palette ->
            val count = palette.copyTo(paletteUniforms)
            optical.setPaletteUniforms(paletteUniforms, count, frame.palettePhase)
        }
        for (index in 0 until frame.emitterCount()) appendEmitter(optical, frame.emitter(index))
        if (optical.rejectedLobeCount() != 0) {
            return fatalResult(
                geometry.cacheKey,
                IllegalStateException(
                    "GPU effect exceeded $CONTOUR_MAX_LOBES optical lobes; " +
                        "${optical.rejectedLobeCount()} lobe(s) were rejected"
                )
            )
        }
        return drawResult(canvas, geometry, optical)
    }

    /** A non-drawn result is explicit so production routing can terminate without a legacy redraw. */
    fun drawResult(canvas: Canvas, geometry: ContourRibbonGeometry, frame: OpticalFieldFrame): ContourOpticalSubmissionResult {
        submissionState.failureFor(geometry.cacheKey)?.let {
            return ContourOpticalSubmissionResult(ContourOpticalDrawResult.GPU_UNAVAILABLE, it)
        }
        if (frame.lobeCount() == 0 || frame.intensity <= 0f) return ContourOpticalSubmissionResult(ContourOpticalDrawResult.EMPTY_FIELD)
        if (!canvas.isHardwareAccelerated) {
            debugTrace.hardwareRejected()
            return ContourOpticalSubmissionResult(ContourOpticalDrawResult.UNSUPPORTED_HARDWARE)
        }
        val drawableMesh = try { meshFor(geometry) } catch (error: RuntimeException) {
            return fatalResult(geometry.cacheKey, error)
        }
            ?: return fatalResult(geometry.cacheKey, IllegalStateException("Mesh preparation returned null"))
        return try {
        when (visibilityProbe.nextStage(geometry.cacheKey)) {
            ContourOpticalVisibilityStage.OPAQUE_MESH -> {
                canvas.drawMesh(drawableMesh, BlendMode.SRC, opaqueProbePaint)
                return ContourOpticalSubmissionResult(ContourOpticalDrawResult.DRAWN)
            }
            ContourOpticalVisibilityStage.CONSTANT_AGSL -> {
                drawableMesh.setColorUniform(UNIFORM_COLOR, Color.MAGENTA)
                drawableMesh.setFloatUniform(UNIFORM_INTENSITY, 1f)
                for (index in 0 until CONTOUR_MAX_LOBES) {
                    drawableMesh.setFloatUniform(UNIFORM_LOBES[index], floatArrayOf(0f, 0f, 0.5f, 1f))
                    drawableMesh.setFloatUniform(UNIFORM_DIRECTIONS[index], 1f)
                    drawableMesh.setFloatUniform(UNIFORM_PROFILES[index], floatArrayOf(0f, 0.999f, 1f, 1f))
                    drawableMesh.setColorUniform(UNIFORM_LOBE_COLORS[index], Color.MAGENTA)
                    drawableMesh.setFloatUniform(UNIFORM_LOBE_HAS_COLORS[index], 1f)
                }
                canvas.drawMesh(drawableMesh, CONTOUR_OPTICAL_MESH_BLEND_MODE, paint)
                return ContourOpticalSubmissionResult(ContourOpticalDrawResult.DRAWN)
            }
            ContourOpticalVisibilityStage.OPTICAL_FIELD, null -> Unit
        }
        debugTrace.submissionStarted(geometry.cacheKey)
        logSubmittedFieldOnce(geometry.cacheKey, frame)
        drawableMesh.setColorUniform(UNIFORM_COLOR, frame.color)
        drawableMesh.setFloatUniform(UNIFORM_INTENSITY, frame.intensity)
        drawableMesh.setFloatUniform(UNIFORM_PALETTE_COUNT, frame.paletteCount().toFloat())
        drawableMesh.setFloatUniform(UNIFORM_PALETTE_PHASE, frame.palettePhase())
        for (index in 0 until CONTOUR_MAX_PALETTE_COLORS) {
            drawableMesh.setColorUniform(UNIFORM_PALETTE[index], frame.paletteColor(index))
        }
        for (index in 0 until CONTOUR_MAX_LOBES) {
            frame.lobe(index, lobeUniform)
            drawableMesh.setFloatUniform(UNIFORM_LOBES[index], lobeUniform)
            drawableMesh.setFloatUniform(UNIFORM_DIRECTIONS[index], frame.lobeDirection(index))
            frame.lobeProfile(index, profileUniform)
            drawableMesh.setFloatUniform(UNIFORM_PROFILES[index], profileUniform)
            drawableMesh.setColorUniform(UNIFORM_LOBE_COLORS[index], frame.lobeColor(index))
            drawableMesh.setFloatUniform(UNIFORM_LOBE_HAS_COLORS[index], frame.lobeHasExplicitColor(index))
        }
        canvas.drawMesh(drawableMesh, CONTOUR_OPTICAL_MESH_BLEND_MODE, paint)
        ContourOpticalRenderMetrics.recordDrawPass()
        debugTrace.drawn(geometry.cacheKey)
        ContourOpticalSubmissionResult(ContourOpticalDrawResult.DRAWN)
        } catch (error: RuntimeException) {
            fatalResult(geometry.cacheKey, error)
        }
    }

    private fun fatalResult(key: ContourRibbonGeometryKey, error: Throwable): ContourOpticalSubmissionResult {
        val existing = submissionState.failureFor(key)
        val failure = submissionState.markFatal(key, error)
        if (existing == null) {
            ContourOpticalRenderMetrics.recordSubmissionFailure()
            debugTrace.terminal(key, failure)
            Log.e(CONTOUR_OPTICAL_LOG_TAG, "GPU submission disabled for geometry=$key; ${failure.throwableClass}: ${failure.message ?: "(no message)"}", error)
        }
        return ContourOpticalSubmissionResult(ContourOpticalDrawResult.GPU_UNAVAILABLE, failure)
    }

    private fun logSubmittedFieldOnce(key: ContourRibbonGeometryKey, frame: OpticalFieldFrame) {
        if (!BuildConfig.DEBUG || fieldDiagnosticKey == key) return
        fieldDiagnosticKey = key
        var maxEnergy = 0f
        var minWidth = Float.POSITIVE_INFINITY
        var maxWidth = 0f
        var minPaletteAlpha = 255
        var maxPaletteAlpha = 0
        for (index in 0 until frame.lobeCount()) {
            frame.lobe(index, lobeUniform)
            frame.lobeProfile(index, profileUniform)
            maxEnergy = maxOf(maxEnergy, lobeUniform[3])
            minWidth = minOf(minWidth, profileUniform[2])
            maxWidth = maxOf(maxWidth, profileUniform[2])
        }
        for (index in 0 until frame.paletteCount()) {
            val alpha = frame.paletteColor(index) ushr 24 and 0xFF
            minPaletteAlpha = minOf(minPaletteAlpha, alpha); maxPaletteAlpha = maxOf(maxPaletteAlpha, alpha)
        }
        val paletteAlpha = if (frame.paletteCount() == 0) "solid" else "$minPaletteAlpha..$maxPaletteAlpha"
        Log.d(CONTOUR_OPTICAL_LOG_TAG, "submitted field geometry=$key lobes=${frame.lobeCount()} intensity=${frame.intensity} maxEnergy=$maxEnergy widths=${if (minWidth.isFinite()) "$minWidth..$maxWidth" else "none"} peakAlpha=${frame.intensity * maxEnergy} paletteCount=${frame.paletteCount()} paletteAlpha=$paletteAlpha")
    }

    /** Drops native-backed mesh references when the owning render pipeline is reconfigured. */
    fun clear() {
        cachedKey = null
        mesh = null
        vertexBuffer = null
        fieldDiagnosticKey = null
        submissionState.clear()
        debugTrace.reset()
    }

    /** Converts one retained effect emitter to its bounded contour-field bands without allocation. */
    internal fun appendEmitter(target: OpticalFieldFrame, emitter: GpuEmitter) {
        // A closed contour is represented as two half-contour fields. Their zero-alpha ends meet
        // the other field's hot head, avoiding a primitive seam or repeated Canvas stroke.
        if (emitter.direction == 0 && emitter.length >= 1f && emitter.bodyLength >= 1f) {
            val energy = maxOf(emitter.bodyEnergy, emitter.coreEnergy, emitter.bloomEnergy)
            target.addLobe(0f, 0f, 0.5f, energy, 1, 0f, 0.999f, emitter.bodyWidth, emitter.bodyFeather, emitter.bodyColor.takeIf { emitter.bodyHasColor })
            target.addLobe(0.5f, 0f, 0.5f, energy, 1, 0f, 0.999f, emitter.bodyWidth, emitter.bodyFeather, emitter.bodyColor.takeIf { emitter.bodyHasColor })
            return
        }
        if (emitter.direction == 0) {
            target.addLobe(emitter.anchor, 0f, emitter.length, emitter.coreEnergy, 0, emitter.coreHeadTaper, emitter.coreRelease, emitter.coreWidth, emitter.coreFeather, emitter.coreColor.takeIf { emitter.coreHasColor })
            return
        }
        val fullLength = emitter.length.coerceAtLeast(0.001f)
        val coreLength = emitter.coreLength.coerceIn(0.001f, fullLength)
        val bodyLength = emitter.bodyLength.coerceIn(0f, (fullLength - coreLength).coerceAtLeast(0f))
        if (emitter.bloomEnergy > 0f) target.addLobe(emitter.anchor, 0f, fullLength, emitter.bloomEnergy, emitter.direction, emitter.bloomHeadTaper, emitter.bloomRelease, emitter.bloomWidth, emitter.bloomFeather, emitter.bloomColor.takeIf { emitter.bloomHasColor })
        if (emitter.bodyEnergy > 0f && bodyLength > 0f) target.addLobe(emitter.anchor, coreLength, bodyLength, emitter.bodyEnergy, emitter.direction, emitter.bodyHeadTaper, emitter.bodyRelease, emitter.bodyWidth, emitter.bodyFeather, emitter.bodyColor.takeIf { emitter.bodyHasColor })
        if (emitter.coreEnergy > 0f) target.addLobe(emitter.anchor, 0f, coreLength, emitter.coreEnergy, emitter.direction, emitter.coreHeadTaper, emitter.coreRelease, emitter.coreWidth, emitter.coreFeather, emitter.coreColor.takeIf { emitter.coreHasColor })
    }

    private fun meshFor(geometry: ContourRibbonGeometry): Mesh? {
        if (cachedKey == geometry.cacheKey) return mesh
        cachedKey = geometry.cacheKey
        mesh = null
        vertexBuffer = null
        if (geometry.vertexCount < 3 || geometry.vertexData.size != geometry.vertexCount * VERTEX_COMPONENTS) return null
        val buffer = ByteBuffer.allocateDirect(geometry.vertexData.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(geometry.vertexData)
        buffer.rewind()
        val bounds = RectF(geometry.bounds.left, geometry.bounds.top, geometry.bounds.right, geometry.bounds.bottom)
        val meshSpecification = specification ?: createSpecification().also { specification = it }
        return Mesh(meshSpecification, Mesh.TRIANGLE_STRIP, buffer, geometry.vertexCount, bounds).also {
            vertexBuffer = buffer
            mesh = it
            ContourOpticalRenderMetrics.recordMeshCreation(geometry.vertexCount)
        }
    }

    private fun createSpecification(): MeshSpecification = MeshSpecification.make(
        arrayOf(
            MeshSpecification.Attribute(MeshSpecification.TYPE_FLOAT2, 0, ATTRIBUTE_POSITION),
            MeshSpecification.Attribute(MeshSpecification.TYPE_FLOAT2, 8, ATTRIBUTE_CONTOUR_UV)
        ),
        VERTEX_STRIDE_BYTES,
        arrayOf(MeshSpecification.Varying(MeshSpecification.TYPE_FLOAT2, VARYING_CONTOUR_UV)),
        VERTEX_SHADER,
        FRAGMENT_SHADER,
        ColorSpace.get(ColorSpace.Named.SRGB),
        MeshSpecification.ALPHA_TYPE_PREMULTIPLIED
    )

    private companion object {
        const val VERTEX_COMPONENTS = 4
        const val VERTEX_STRIDE_BYTES = VERTEX_COMPONENTS * Float.SIZE_BYTES
        const val ATTRIBUTE_POSITION = "position"
        const val ATTRIBUTE_CONTOUR_UV = "contourUv"
        const val VARYING_CONTOUR_UV = "contourUv"
        const val UNIFORM_COLOR = "uColor"
        const val UNIFORM_INTENSITY = "uIntensity"
        const val UNIFORM_PALETTE_COUNT = "uPaletteCount"
        const val UNIFORM_PALETTE_PHASE = "uPalettePhase"
        val UNIFORM_LOBES = Array(CONTOUR_MAX_LOBES) { "uLobe$it" }
        val UNIFORM_DIRECTIONS = Array(CONTOUR_MAX_LOBES) { "uLobeDirection$it" }
        val UNIFORM_PROFILES = Array(CONTOUR_MAX_LOBES) { "uLobeProfile$it" }
        val UNIFORM_LOBE_COLORS = Array(CONTOUR_MAX_LOBES) { "uLobeColor$it" }
        val UNIFORM_LOBE_HAS_COLORS = Array(CONTOUR_MAX_LOBES) { "uLobeHasColor$it" }
        val UNIFORM_PALETTE = Array(CONTOUR_MAX_PALETTE_COLORS) { "uPalette$it" }

        // The mesh program owns the u/v hand-off. RuntimeShader is intentionally not involved:
        // it cannot receive MeshSpecification varyings.
        val VERTEX_SHADER = """
            Varyings main(const Attributes attributes) {
                Varyings varyings;
                varyings.position = attributes.position;
                varyings.contourUv = attributes.contourUv;
                return varyings;
            }
        """.trimIndent()

        // This returns premultiplied color. v reaches exactly 0/1 at the ribbon boundaries, so
        // alpha reaches zero before drawMesh's non-antialiased primitive edge is visible.
        val FRAGMENT_SHADER = """
            layout(color) uniform float4 uColor;
            uniform float uIntensity;
            uniform float uPaletteCount;
            uniform float uPalettePhase;
            layout(color) uniform float4 uPalette0;
            layout(color) uniform float4 uPalette1;
            layout(color) uniform float4 uPalette2;
            layout(color) uniform float4 uPalette3;
            layout(color) uniform float4 uPalette4;
            layout(color) uniform float4 uPalette5;
            layout(color) uniform float4 uPalette6;
            layout(color) uniform float4 uPalette7;
            uniform float4 uLobe0; uniform float uLobeDirection0;
            uniform float4 uLobeProfile0;
            layout(color) uniform float4 uLobeColor0; uniform float uLobeHasColor0;
            uniform float4 uLobe1; uniform float uLobeDirection1;
            uniform float4 uLobeProfile1;
            layout(color) uniform float4 uLobeColor1; uniform float uLobeHasColor1;
            uniform float4 uLobe2; uniform float uLobeDirection2;
            uniform float4 uLobeProfile2;
            layout(color) uniform float4 uLobeColor2; uniform float uLobeHasColor2;
            uniform float4 uLobe3; uniform float uLobeDirection3;
            uniform float4 uLobeProfile3;
            layout(color) uniform float4 uLobeColor3; uniform float uLobeHasColor3;
            uniform float4 uLobe4; uniform float uLobeDirection4;
            uniform float4 uLobeProfile4;
            layout(color) uniform float4 uLobeColor4; uniform float uLobeHasColor4;
            uniform float4 uLobe5; uniform float uLobeDirection5;
            uniform float4 uLobeProfile5;
            layout(color) uniform float4 uLobeColor5; uniform float uLobeHasColor5;
            uniform float4 uLobe6; uniform float uLobeDirection6;
            uniform float4 uLobeProfile6;
            layout(color) uniform float4 uLobeColor6; uniform float uLobeHasColor6;
            uniform float4 uLobe7; uniform float uLobeDirection7;
            uniform float4 uLobeProfile7;
            layout(color) uniform float4 uLobeColor7; uniform float uLobeHasColor7;
            uniform float4 uLobe8; uniform float uLobeDirection8;
            uniform float4 uLobeProfile8;
            layout(color) uniform float4 uLobeColor8; uniform float uLobeHasColor8;
            uniform float4 uLobe9; uniform float uLobeDirection9;
            uniform float4 uLobeProfile9;
            layout(color) uniform float4 uLobeColor9; uniform float uLobeHasColor9;
            uniform float4 uLobe10; uniform float uLobeDirection10;
            uniform float4 uLobeProfile10;
            layout(color) uniform float4 uLobeColor10; uniform float uLobeHasColor10;
            uniform float4 uLobe11; uniform float uLobeDirection11;
            uniform float4 uLobeProfile11;
            layout(color) uniform float4 uLobeColor11; uniform float uLobeHasColor11;

            float circularDelta(float value, float anchor) {
                return fract(value - anchor + 0.5) - 0.5;
            }

            float lobeEnergy(float u, float v, float4 lobe, float direction, float4 profile) {
                if (lobe.z <= 0.0) return 0.0;
                float delta = circularDelta(u, lobe.x);
                float longitudinal;
                if (direction == 0.0) {
                    longitudinal = abs(delta);
                } else {
                    longitudinal = -delta * direction;
                    // A directional lobe has no field ahead of its head. Clamping that negative
                    // value to zero would incorrectly turn the complete opposite side into a
                    // full-strength core.
                    if (longitudinal < 0.0) return 0.0;
                }
                // A smoothstep-to-zero tail avoids both a hard head and a visible alpha layer.
                if (longitudinal < lobe.y) return 0.0;
                float progress = (longitudinal - lobe.y) / lobe.z;
                if (progress > 1.0) return 0.0;
                // Symmetric/localized lobes peak at their anchor. Head taper is a directed
                // field property only; applying it here would punch out the source core.
                float head = (direction == 0.0 || profile.x <= 0.0) ? 1.0 : smoothstep(0.0, profile.x, progress);
                float release = 1.0 - smoothstep(profile.y, 1.0, progress);
                float radialBase = max(0.0, 1.0 - abs(v - 0.5) * 2.0 / profile.z);
                float transverse = pow(radialBase, profile.w);
                return head * release * transverse * lobe.w;
            }

            float4 paletteAt(float index) {
                if (index < 0.5) return uPalette0;
                if (index < 1.5) return uPalette1;
                if (index < 2.5) return uPalette2;
                if (index < 3.5) return uPalette3;
                if (index < 4.5) return uPalette4;
                if (index < 5.5) return uPalette5;
                if (index < 6.5) return uPalette6;
                return uPalette7;
            }

            float4 fieldColor(float u) {
                if (uPaletteCount < 2.0) return uColor;
                float scaled = fract(u + uPalettePhase) * uPaletteCount;
                float low = floor(scaled);
                return mix(paletteAt(low), paletteAt(mod(low + 1.0, uPaletteCount)), fract(scaled));
            }

            float4 lobeColor(float u, float4 color, float hasColor) {
                return hasColor > 0.5 ? color : fieldColor(u);
            }

            float2 main(const Varyings varyings, out float4 color) {
                float e0 = lobeEnergy(varyings.contourUv.x, varyings.contourUv.y, uLobe0, uLobeDirection0, uLobeProfile0);
                float e1 = lobeEnergy(varyings.contourUv.x, varyings.contourUv.y, uLobe1, uLobeDirection1, uLobeProfile1);
                float e2 = lobeEnergy(varyings.contourUv.x, varyings.contourUv.y, uLobe2, uLobeDirection2, uLobeProfile2);
                float e3 = lobeEnergy(varyings.contourUv.x, varyings.contourUv.y, uLobe3, uLobeDirection3, uLobeProfile3);
                float e4 = lobeEnergy(varyings.contourUv.x, varyings.contourUv.y, uLobe4, uLobeDirection4, uLobeProfile4);
                float e5 = lobeEnergy(varyings.contourUv.x, varyings.contourUv.y, uLobe5, uLobeDirection5, uLobeProfile5);
                float e6 = lobeEnergy(varyings.contourUv.x, varyings.contourUv.y, uLobe6, uLobeDirection6, uLobeProfile6);
                float e7 = lobeEnergy(varyings.contourUv.x, varyings.contourUv.y, uLobe7, uLobeDirection7, uLobeProfile7);
                float e8 = lobeEnergy(varyings.contourUv.x, varyings.contourUv.y, uLobe8, uLobeDirection8, uLobeProfile8);
                float e9 = lobeEnergy(varyings.contourUv.x, varyings.contourUv.y, uLobe9, uLobeDirection9, uLobeProfile9);
                float e10 = lobeEnergy(varyings.contourUv.x, varyings.contourUv.y, uLobe10, uLobeDirection10, uLobeProfile10);
                float e11 = lobeEnergy(varyings.contourUv.x, varyings.contourUv.y, uLobe11, uLobeDirection11, uLobeProfile11);
                float field = e0;
                float4 baseColor = lobeColor(varyings.contourUv.x, uLobeColor0, uLobeHasColor0);
                if (e1 > field) { field = e1; baseColor = lobeColor(varyings.contourUv.x, uLobeColor1, uLobeHasColor1); }
                if (e2 > field) { field = e2; baseColor = lobeColor(varyings.contourUv.x, uLobeColor2, uLobeHasColor2); }
                if (e3 > field) { field = e3; baseColor = lobeColor(varyings.contourUv.x, uLobeColor3, uLobeHasColor3); }
                if (e4 > field) { field = e4; baseColor = lobeColor(varyings.contourUv.x, uLobeColor4, uLobeHasColor4); }
                if (e5 > field) { field = e5; baseColor = lobeColor(varyings.contourUv.x, uLobeColor5, uLobeHasColor5); }
                if (e6 > field) { field = e6; baseColor = lobeColor(varyings.contourUv.x, uLobeColor6, uLobeHasColor6); }
                if (e7 > field) { field = e7; baseColor = lobeColor(varyings.contourUv.x, uLobeColor7, uLobeHasColor7); }
                if (e8 > field) { field = e8; baseColor = lobeColor(varyings.contourUv.x, uLobeColor8, uLobeHasColor8); }
                if (e9 > field) { field = e9; baseColor = lobeColor(varyings.contourUv.x, uLobeColor9, uLobeHasColor9); }
                if (e10 > field) { field = e10; baseColor = lobeColor(varyings.contourUv.x, uLobeColor10, uLobeHasColor10); }
                if (e11 > field) { field = e11; baseColor = lobeColor(varyings.contourUv.x, uLobeColor11, uLobeHasColor11); }
                // Lift the field before user intensity: energized cores still peak exactly at
                // uIntensity, and the finite slope at zero preserves smooth tail endpoints.
                float liftedField = clamp(field, 0.0, 1.0);
                liftedField = liftedField * (2.0 - liftedField);
                float alpha = clamp(uIntensity * liftedField, 0.0, 1.0);
                color = float4(baseColor.rgb * alpha, alpha);
                return varyings.position;
            }
        """.trimIndent()
    }
}
