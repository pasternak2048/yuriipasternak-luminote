package com.yp.luminote.app.effects

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.data.settings.HaloFrame
import com.yp.luminote.app.data.settings.HaloMotion
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** Draws prepared geometry; the View supplies window size, insets and animation. */
internal class HaloRenderer(
    config: HaloConfig,
    private val outline: DisplayOutline
) {
    private var config = config.sanitized()
    private var colorRgb = this.config.color and RGB_MASK
    private var cachedOutlineVersion = -1
    private var cachedStyleStrokeWidth = Float.NaN
    private var cachedStylePath: Path? = null
    private var cachedCornerFractions: FloatArray? = null
    private var cachedRippleOriginFraction: Float? = null
    private val segmentPath = Path()
    private var gradientShader: SweepGradient? = null
    private var gradientOutlineVersion = -1
    private var gradientPalette = intArrayOf()
    private val gradientMatrix = Matrix()
    private var gradientCenterX = 0f
    private var gradientCenterY = 0f
    private val corePaint = createPaint()
    private val equalizerBloomPaint = createPaint()
    private val equalizerBloomNode = RenderNode("LuminoteEqualizerBloom")
    private val equalizerBandPath = Path()
    private var displayedEqualizerEnergy = 0f
    private var lastEqualizerEnergyUpdateNanos = 0L

    fun update(config: HaloConfig) {
        val next = config.sanitized()
        if (this.config.thickness != next.thickness || this.config.frame != next.frame) {
            clearStylePathCache()
            outline.clearRenderCaches()
        }
        this.config = next
        colorRgb = next.color and RGB_MASK
    }

    fun draw(
        canvas: Canvas,
        animationProgress: Float,
        effectPhase: Float,
        gradientPhase: Float
    ) {
        val baseAlpha = (255f * config.intensity * animationProgress.coerceIn(0f, 1f))
            .roundToInt().coerceIn(0, 255)
        if (baseAlpha == 0) {
            return
        }

        if (cachedOutlineVersion != outline.version) {
            clearStylePathCache()
            cachedOutlineVersion = outline.version
        }

        val saveCount = canvas.save()
        try {
            canvas.clipPath(outline.path)

            val alpha = baseAlpha
            if (config.colorMode == HaloColorMode.GRADIENT && config.palette.size > 1) {
                corePaint.shader = gradientShader().also { shader ->
                    gradientMatrix.setRotate(
                        (gradientPhase * GRADIENT_ROTATION_PER_CYCLE_DEGREES * config.gradientFlowSpeed) % FULL_ROTATION_DEGREES,
                        gradientCenterX,
                        gradientCenterY
                    )
                    shader.setLocalMatrix(gradientMatrix)
                }
                corePaint.alpha = alpha
            } else {
                corePaint.shader = null
                corePaint.color = colorWithAlpha(alpha, colorForPhase(effectPhase))
            }
            val coreStrokeWidth = 4f + config.thickness * 10f
            corePaint.strokeWidth = coreStrokeWidth + outerGapWidth(coreStrokeWidth)

            drawStylePath(canvas, corePaint, effectPhase)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    private fun drawStylePath(canvas: Canvas, paint: Paint, phase: Float) {
        val path = cachedStylePath?.takeIf {
            cachedStyleStrokeWidth == paint.strokeWidth
        } ?: buildStylePath().also { generatedPath ->
            cachedStyleStrokeWidth = paint.strokeWidth
            cachedStylePath = generatedPath
        }
        when (config.motion) {
            HaloMotion.PULSE -> canvas.drawPath(path, paint)
            HaloMotion.SNAKE -> drawSnakePath(canvas, path, paint, phase)
            HaloMotion.CORNER_PULSE -> drawCornerPulsePath(canvas, path, paint, phase)
            HaloMotion.RAIN -> drawRain(canvas, path, paint, phase)
            HaloMotion.RIPPLE_EDGE -> drawRippleEdge(canvas, path, paint, phase)
            HaloMotion.EQUALIZER -> drawEqualizer(canvas, path, paint, phase)
        }
    }

    /**
     * A real audio-reactive Pulse frame. Silence draws nothing; music opens a
     * full-screen frame whose glow and width follow the current FFT energy.
     * The shadow layer gives the outer bloom a soft blur while the core stays
     * crisp enough to respect the display contour.
     */
    private fun drawEqualizer(canvas: Canvas, path: Path, paint: Paint, phase: Float) {
        val bands = MusicSpectrum.bands()
        var total = 0f
        var peak = 0f
        for (band in bands) {
            val level = band.coerceIn(0f, 1f)
            total += level
            peak = maxOf(peak, level)
        }
        val average = total / bands.size
        // A peak can amplify a loud moment, but cannot keep the frame bright
        // by itself while the track is already fading out.
        val energy = (average * EQUALIZER_AVERAGE_WEIGHT +
            peak * EQUALIZER_PEAK_WEIGHT *
                (EQUALIZER_PEAK_GATE_BASE + average * EQUALIZER_PEAK_GATE_FROM_AVERAGE))
            .coerceIn(0f, 1f)
        val displayedEnergy = smoothEqualizerEnergy(energy)
        if (displayedEnergy < EQUALIZER_VISIBILITY_THRESHOLD) return

        val baseAlpha = paint.alpha
        val pulse = ((displayedEnergy - EQUALIZER_VISIBILITY_THRESHOLD) /
            (1f - EQUALIZER_VISIBILITY_THRESHOLD)).coerceIn(0f, 1f)
        val coreWidth = paint.strokeWidth *
            (EQUALIZER_CORE_WIDTH_BASE + pulse * EQUALIZER_CORE_WIDTH_BOOST)
        // Convert the audio response to the Android alpha domain explicitly:
        // silence is 0 and a full-scale music peak is 255 before the user's
        // Intensity ceiling is applied.
        val audioAlpha = (pulse * MAX_ALPHA).roundToInt()
        val peakAlpha = (audioAlpha * (baseAlpha / MAX_ALPHA) * EQUALIZER_AUDIO_BRIGHTNESS_BOOST)
            .roundToInt()
            .coerceIn(0, MAX_ALPHA.toInt())
        equalizerBloomPaint.set(paint)
        // peakAlpha is already mapped from the 0..255 audio amplitude. Do not
        // multiply by pulse again here, or medium-strength music becomes
        // quadratically dim instead of retaining saturated color.
        equalizerBloomPaint.alpha = (peakAlpha * EQUALIZER_BLOOM_ALPHA).roundToInt()
        drawEqualizerBloom(canvas, path, coreWidth, bands, peakAlpha)

        paint.alpha = baseAlpha
    }

    /** Renders the previous FFT-shaped bloom treatment along the bottom edge only. */
    private fun drawEqualizerBloom(
        canvas: Canvas,
        path: Path,
        coreWidth: Float,
        bands: FloatArray,
        peakAlpha: Int
    ) {
        equalizerBloomNode.setPosition(0, 0, canvas.width, canvas.height)
        equalizerBloomNode.setRenderEffect(
            RenderEffect.createBlurEffect(
                coreWidth * EQUALIZER_BLUR_RADIUS_MULTIPLIER,
                coreWidth * EQUALIZER_BLUR_RADIUS_MULTIPLIER,
                Shader.TileMode.DECAL
            )
        )
        val bloomCanvas = equalizerBloomNode.beginRecording()
        val measure = PathMeasure(path, false)
        val length = measure.length
        if (length > 0f) {
            val corners = cornerFractionsFor(path, measure, length)
            drawFrequencyRange(
                bloomCanvas, measure, edgeSpan(corners[3] * length, corners[2] * length, length),
                bands, HIGH_FREQUENCIES, peakAlpha, coreWidth
            )
            drawFrequencyRange(
                bloomCanvas, measure, edgeSpan(corners[0] * length, corners[3] * length, length),
                bands, LOW_FREQUENCIES, peakAlpha, coreWidth
            )
            drawFrequencyRange(
                bloomCanvas, measure, edgeSpan(corners[1] * length, corners[2] * length, length),
                bands, LOW_FREQUENCIES, peakAlpha, coreWidth
            )
            drawFrequencyRange(
                bloomCanvas, measure, edgeSpan(corners[0] * length, corners[1] * length, length),
                bands, MID_FREQUENCIES, peakAlpha, coreWidth
            )
        }
        equalizerBloomNode.endRecording()
        // Keep the number of passes fixed. Changing it per frame produces
        // visible brightness steps; the alpha above already varies smoothly
        // across the complete 0..255 audio range.
        repeat(EQUALIZER_BLOOM_PASSES) {
            canvas.drawRenderNode(equalizerBloomNode)
        }
    }

    private fun appendWrappedSegment(
        measure: PathMeasure,
        start: Float,
        segmentLength: Float,
        destination: Path
    ) {
        val length = measure.length
        destination.reset()
        if (length <= 0f || segmentLength <= 0f) return
        val normalizedStart = (start % length + length) % length
        val end = normalizedStart + segmentLength
        if (end <= length) {
            measure.getSegment(normalizedStart, end, destination, true)
        } else {
            measure.getSegment(normalizedStart, length, destination, true)
            measure.getSegment(0f, end - length, destination, true)
        }
    }

    private fun drawFrequencyRange(
        canvas: Canvas,
        measure: PathMeasure,
        edge: EdgeSpan,
        bands: FloatArray,
        range: IntRange,
        peakAlpha: Int,
        coreWidth: Float
    ) {
        var total = 0f
        range.forEach { bandIndex -> total += bands[bandIndex].coerceIn(0f, 1f) }
        val level = (total / range.count()).coerceIn(0f, 1f)
        equalizerBloomPaint.alpha = (peakAlpha *
            (EQUALIZER_BAND_MIN_ALPHA + level * EQUALIZER_BAND_ALPHA_BOOST)).roundToInt()
        equalizerBloomPaint.strokeWidth = coreWidth * EQUALIZER_BLOOM_SOURCE_WIDTH_MULTIPLIER *
            (EQUALIZER_BAND_MIN_WIDTH + level * EQUALIZER_BAND_WIDTH_BOOST)
        appendWrappedSegment(measure, edge.start, edge.length, equalizerBandPath)
        canvas.drawPath(equalizerBandPath, equalizerBloomPaint)
    }

    private fun edgeSpan(first: Float, second: Float, length: Float): EdgeSpan {
        val forwardLength = (second - first + length) % length
        return if (forwardLength <= length - forwardLength) {
            EdgeSpan(first, forwardLength)
        } else {
            EdgeSpan(second, length - forwardLength)
        }
    }

    /** Fast attack with a short, frame-rate-independent fade for music stops. */
    private fun smoothEqualizerEnergy(target: Float): Float {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val elapsedMs = if (lastEqualizerEnergyUpdateNanos == 0L) {
            0f
        } else {
            (nowNanos - lastEqualizerEnergyUpdateNanos) / NANOS_PER_MILLISECOND.toFloat()
        }
        lastEqualizerEnergyUpdateNanos = nowNanos
        val timeConstantMs = if (target > displayedEqualizerEnergy) {
            EQUALIZER_ATTACK_TIME_MS
        } else {
            EQUALIZER_RELEASE_TIME_MS
        }
        val interpolation = if (elapsedMs <= 0f) 1f else {
            (1f - exp((-elapsedMs / timeConstantMs).toDouble()).toFloat()).coerceIn(0f, 1f)
        }
        displayedEqualizerEnergy += (target - displayedEqualizerEnergy) * interpolation
        return displayedEqualizerEnergy
    }

    private fun drawSnakePath(canvas: Canvas, path: Path, paint: Paint, phase: Float) {
        val measure = PathMeasure(path, false)
        val length = measure.length
        if (length <= 0f) return
        val start = ((phase % 1f + 1f) % 1f) * length
        val segmentLength = length * SNAKE_SEGMENT_FRACTION
        drawWrappedSegment(canvas, measure, start, segmentLength, paint)
    }

    private fun drawCornerPulsePath(canvas: Canvas, path: Path, paint: Paint, phase: Float) {
        val measure = PathMeasure(path, false)
        val length = measure.length
        if (length <= 0f) return
        val normalizedPhase = (phase % 1f + 1f) % 1f
        val cornerProgress = normalizedPhase * CORNER_COUNT
        val cornerIndex = cornerProgress.toInt().coerceIn(0, CORNER_COUNT - 1)
        val cornerAlpha = sin((cornerProgress - cornerIndex) * PI).toFloat().coerceIn(0f, 1f)
        if (cornerAlpha <= 0f) return
        val center = cornerFractionsFor(path, measure, length)[cornerIndex] * length
        val baseAlpha = paint.alpha
        paint.alpha = (baseAlpha * cornerAlpha).roundToInt()
        drawWrappedSegment(
            canvas = canvas,
            measure = measure,
            start = center - length * CORNER_SEGMENT_FRACTION / 2f,
            segmentLength = length * CORNER_SEGMENT_FRACTION,
            paint = paint
        )
        paint.alpha = baseAlpha
    }

    private fun drawRain(canvas: Canvas, path: Path, paint: Paint, phase: Float) {
        val bounds = boundsOf(path)
        val height = bounds.height()
        if (height <= 0f) return
        val normalizedPhase = (phase % 1f + 1f) % 1f
        val dropLength = height * RAIN_DROP_LENGTH_FRACTION
        RAIN_OFFSETS.forEachIndexed { index, offset ->
            val dropProgress = (normalizedPhase + offset) % 1f
            val bottom = bounds.top + dropProgress * (height + dropLength)
            val top = bottom - dropLength
            val laneX = if (index % 2 == 0) bounds.left else bounds.right
            canvas.drawLine(laneX, top, laneX, bottom, paint)
        }
    }

    private fun drawRippleEdge(canvas: Canvas, path: Path, paint: Paint, phase: Float) {
        val measure = PathMeasure(path, false)
        val length = measure.length
        if (length <= 0f) return
        val normalizedPhase = (phase % 1f + 1f) % 1f
        val rippleAlpha = sin(normalizedPhase * PI).toFloat().coerceIn(0f, 1f)
        if (rippleAlpha <= 0f) return
        val bounds = boundsOf(path)
        val origin = rippleOriginFraction(path, measure, length, bounds.centerX(), bounds.top) * length
        val distance = normalizedPhase * length / 2f
        val segmentLength = length * RIPPLE_SEGMENT_FRACTION
        val baseAlpha = paint.alpha
        paint.alpha = (baseAlpha * rippleAlpha).roundToInt()
        drawWrappedSegment(canvas, measure, origin + distance - segmentLength, segmentLength, paint)
        drawWrappedSegment(canvas, measure, origin - distance, segmentLength, paint)
        paint.alpha = baseAlpha
    }

    private fun drawWrappedSegment(
        canvas: Canvas,
        measure: PathMeasure,
        start: Float,
        segmentLength: Float,
        paint: Paint
    ) {
        val length = measure.length
        if (length <= 0f) return
        val normalizedStart = (start % length + length) % length
        val end = normalizedStart + segmentLength
        segmentPath.reset()
        if (end <= length) {
            measure.getSegment(normalizedStart, end, segmentPath, true)
        } else {
            measure.getSegment(normalizedStart, length, segmentPath, true)
            measure.getSegment(0f, end - length, segmentPath, true)
        }
        canvas.drawPath(segmentPath, paint)
    }

    private fun cornerFractionsFor(path: Path, measure: PathMeasure, length: Float): FloatArray {
        if (path === cachedStylePath) {
            cachedCornerFractions?.let { return it }
        }
        val bounds = boundsOf(path)
        val targets = floatArrayOf(
            bounds.left, bounds.top,
            bounds.right, bounds.top,
            bounds.right, bounds.bottom,
            bounds.left, bounds.bottom
        )
        val position = FloatArray(2)
        return FloatArray(CORNER_COUNT) { cornerIndex ->
            val targetX = targets[cornerIndex * 2]
            val targetY = targets[cornerIndex * 2 + 1]
            var nearestFraction = 0f
            var nearestDistance = Float.MAX_VALUE
            for (sample in 0..CORNER_PATH_SAMPLES) {
                val fraction = sample.toFloat() / CORNER_PATH_SAMPLES
                measure.getPosTan(length * fraction, position, null)
                val distance = (position[0] - targetX) * (position[0] - targetX) +
                    (position[1] - targetY) * (position[1] - targetY)
                if (distance < nearestDistance) {
                    nearestDistance = distance
                    nearestFraction = fraction
                }
            }
            nearestFraction
        }.also { fractions ->
            if (path === cachedStylePath) cachedCornerFractions = fractions
        }
    }

    private fun rippleOriginFraction(
        path: Path,
        measure: PathMeasure,
        length: Float,
        targetX: Float,
        targetY: Float
    ): Float {
        if (path === cachedStylePath) {
            cachedRippleOriginFraction?.let { return it }
        }
        val position = FloatArray(2)
        var nearestFraction = 0f
        var nearestDistance = Float.MAX_VALUE
        for (sample in 0..CORNER_PATH_SAMPLES) {
            val fraction = sample.toFloat() / CORNER_PATH_SAMPLES
            measure.getPosTan(length * fraction, position, null)
            val distance = (position[0] - targetX) * (position[0] - targetX) +
                (position[1] - targetY) * (position[1] - targetY)
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestFraction = fraction
            }
        }
        return nearestFraction.also { fraction ->
            if (path === cachedStylePath) cachedRippleOriginFraction = fraction
        }
    }

    private fun buildStylePath(): Path {
        val coreStrokeWidth = 4f + config.thickness * 10f
        val coreInset = frameInsetFor(coreStrokeWidth)
        /* Keep the inner edge fixed while extending only the outer edge. */
        val strokePath = outline.strokePath(coreInset - outerGapWidth(coreStrokeWidth) / 2f)
        return when (config.frame) {
            HaloFrame.CLASSIC -> strokePath
        }
    }

    private fun frameInsetFor(strokeWidth: Float): Float =
        maxOf(strokeWidth / 2f + 1f, outline.opticalInsetPx)

    private fun outerGapWidth(strokeWidth: Float): Float =
        (frameInsetFor(strokeWidth) - strokeWidth / 2f).coerceAtLeast(0f)

    private fun clearStylePathCache() {
        cachedStyleStrokeWidth = Float.NaN
        cachedStylePath = null
        cachedCornerFractions = null
        cachedRippleOriginFraction = null
    }

    private fun gradientShader(): SweepGradient {
        if (
            gradientShader != null &&
            gradientOutlineVersion == outline.version &&
            gradientPalette.contentEquals(config.palette)
        ) {
            return gradientShader!!
        }

        val bounds = boundsOf(outline.path)
        gradientCenterX = bounds.centerX()
        gradientCenterY = bounds.centerY()
        val colors = IntArray(config.palette.size + 1) { index ->
            config.palette[index % config.palette.size] or OPAQUE_ALPHA
        }
        val positions = FloatArray(colors.size) { index ->
            index.toFloat() / (colors.size - 1).coerceAtLeast(1)
        }
        return SweepGradient(
            gradientCenterX,
            gradientCenterY,
            colors,
            positions
        ).also { shader ->
            gradientShader = shader
            gradientOutlineVersion = outline.version
            gradientPalette = config.palette.copyOf()
        }
    }

    private fun boundsOf(path: Path): RectF =
        RectF().also { path.computeBounds(it, true) }

    private fun colorForPhase(phase: Float): Int {
        if (config.colorMode == HaloColorMode.GRADIENT || config.palette.size < 2) {
            return colorRgb
        }
        val normalizedPhase = (phase % 1f + 1f) % 1f
        val scaled = normalizedPhase * config.palette.size
        val startIndex = scaled.toInt() % config.palette.size
        val endIndex = (startIndex + 1) % config.palette.size
        val fraction = scaled - scaled.toInt()
        return blend(config.palette[startIndex], config.palette[endIndex], fraction) and RGB_MASK
    }

    private fun blend(start: Int, end: Int, fraction: Float): Int {
        val safeFraction = fraction.coerceIn(0f, 1f)
        fun component(color: Int, shift: Int): Int = (color shr shift) and 0xFF
        fun interpolate(shift: Int): Int =
            (component(start, shift) + (component(end, shift) - component(start, shift)) * safeFraction)
                .roundToInt()
        return (interpolate(16) shl 16) or (interpolate(8) shl 8) or interpolate(0)
    }

    private fun colorWithAlpha(alpha: Int, rgb: Int): Int =
        (alpha.coerceIn(0, 255) shl ALPHA_SHIFT) or (rgb and RGB_MASK)

    private fun createPaint(): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private data class EdgeSpan(val start: Float, val length: Float)

    private companion object {
        private const val SNAKE_SEGMENT_FRACTION = 0.18f
        private const val CORNER_SEGMENT_FRACTION = 0.14f
        private const val CORNER_COUNT = 4
        private const val CORNER_PATH_SAMPLES = 240
        private const val RAIN_DROP_LENGTH_FRACTION = 0.14f
        private const val RIPPLE_SEGMENT_FRACTION = 0.12f
        private const val EQUALIZER_VISIBILITY_THRESHOLD = 0.06f
        private const val EQUALIZER_CORE_WIDTH_BASE = 1.18f
        private const val EQUALIZER_CORE_WIDTH_BOOST = 1.92f
        private const val EQUALIZER_AUDIO_BRIGHTNESS_BOOST = 1.65f
        private const val EQUALIZER_BLOOM_ALPHA = 1f
        private const val EQUALIZER_BLOOM_SOURCE_WIDTH_MULTIPLIER = 1.10f
        private const val EQUALIZER_BLUR_RADIUS_MULTIPLIER = 4.5f
        private const val EQUALIZER_BLOOM_PASSES = 8
        private const val EQUALIZER_BAND_MIN_ALPHA = 0.02f
        private const val EQUALIZER_BAND_ALPHA_BOOST = 0.90f
        private const val EQUALIZER_BAND_MIN_WIDTH = 0.60f
        private const val EQUALIZER_BAND_WIDTH_BOOST = 0.90f
        private const val EQUALIZER_AVERAGE_WEIGHT = 0.55f
        private const val EQUALIZER_PEAK_WEIGHT = 0.45f
        private const val EQUALIZER_PEAK_GATE_BASE = 0.20f
        private const val EQUALIZER_PEAK_GATE_FROM_AVERAGE = 0.80f
        private const val EQUALIZER_ATTACK_TIME_MS = 60f
        private const val EQUALIZER_RELEASE_TIME_MS = 140f
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private val LOW_FREQUENCIES = 0..3
        private val MID_FREQUENCIES = 4..7
        private val HIGH_FREQUENCIES = 8..11
        private val RAIN_OFFSETS = floatArrayOf(0f, 0.18f, 0.43f, 0.67f)
        private const val GRADIENT_ROTATION_PER_CYCLE_DEGREES = 220f
        private const val FULL_ROTATION_DEGREES = 360f
        private const val RGB_MASK = 0x00FFFFFF
        private const val ALPHA_SHIFT = 24
        private const val MAX_ALPHA = 255f
        private const val OPAQUE_ALPHA = -0x1000000
    }
}
