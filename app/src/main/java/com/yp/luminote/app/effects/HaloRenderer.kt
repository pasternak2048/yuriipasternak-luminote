package com.yp.luminote.app.effects

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.SweepGradient
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.data.settings.HaloFrame
import com.yp.luminote.app.data.settings.HaloMotion
import kotlin.math.PI
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
    private var cachedStyleGeometry: StyleGeometry? = null
    private val segmentPath = Path()
    private var gradientShader: SweepGradient? = null
    private var gradientOutlineVersion = -1
    private var gradientPalette = intArrayOf()
    private val gradientMatrix = Matrix()
    private var gradientCenterX = 0f
    private var gradientCenterY = 0f
    private val corePaint = createPaint()

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
                val shader = gradientShader().also { gradient ->
                    gradientMatrix.setRotate(
                        (gradientPhase * GRADIENT_ROTATION_PER_CYCLE_DEGREES * config.gradientFlowSpeed) % FULL_ROTATION_DEGREES,
                        gradientCenterX,
                        gradientCenterY
                    )
                    gradient.setLocalMatrix(gradientMatrix)
                }
                corePaint.shader = shader
                corePaint.alpha = alpha
            } else {
                corePaint.shader = null
                val color = colorForPhase(effectPhase)
                corePaint.color = colorWithAlpha(alpha, color)
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
            HaloMotion.SNAKE -> drawSnakePath(canvas, styleGeometryFor(path), paint, phase)
            HaloMotion.CORNER_PULSE -> drawCornerPulsePath(canvas, styleGeometryFor(path), paint, phase)
            HaloMotion.RAIN -> drawRain(canvas, styleGeometryFor(path), paint, phase)
            HaloMotion.RIPPLE_EDGE -> drawRippleEdge(canvas, styleGeometryFor(path), paint, phase)
        }
    }

    private fun drawSnakePath(canvas: Canvas, geometry: StyleGeometry, paint: Paint, phase: Float) {
        val measure = geometry.measure
        val length = geometry.length
        if (length <= 0f) return
        val start = ((phase % 1f + 1f) % 1f) * length
        val segmentLength = length * SNAKE_SEGMENT_FRACTION
        drawWrappedSegment(canvas, measure, length, start, segmentLength, paint)
    }

    private fun drawCornerPulsePath(canvas: Canvas, geometry: StyleGeometry, paint: Paint, phase: Float) {
        val measure = geometry.measure
        val length = geometry.length
        if (length <= 0f) return
        val normalizedPhase = (phase % 1f + 1f) % 1f
        val cornerProgress = normalizedPhase * CORNER_COUNT
        val cornerIndex = cornerProgress.toInt().coerceIn(0, CORNER_COUNT - 1)
        val cornerAlpha = sin((cornerProgress - cornerIndex) * PI).toFloat().coerceIn(0f, 1f)
        if (cornerAlpha <= 0f) return
        val center = cornerFractionsFor(geometry)[cornerIndex] * length
        val baseAlpha = paint.alpha
        paint.alpha = (baseAlpha * cornerAlpha).roundToInt()
        drawWrappedSegment(
            canvas = canvas,
            measure = measure,
            length = length,
            start = center - length * CORNER_SEGMENT_FRACTION / 2f,
            segmentLength = length * CORNER_SEGMENT_FRACTION,
            paint = paint
        )
        paint.alpha = baseAlpha
    }

    private fun drawRain(canvas: Canvas, geometry: StyleGeometry, paint: Paint, phase: Float) {
        val bounds = geometry.bounds
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

    private fun drawRippleEdge(canvas: Canvas, geometry: StyleGeometry, paint: Paint, phase: Float) {
        val measure = geometry.measure
        val length = geometry.length
        if (length <= 0f) return
        val normalizedPhase = (phase % 1f + 1f) % 1f
        val rippleAlpha = sin(normalizedPhase * PI).toFloat().coerceIn(0f, 1f)
        if (rippleAlpha <= 0f) return
        val bounds = geometry.bounds
        val origin = rippleOriginFraction(geometry, bounds.centerX(), bounds.top) * length
        val distance = normalizedPhase * length / 2f
        val segmentLength = length * RIPPLE_SEGMENT_FRACTION
        val baseAlpha = paint.alpha
        paint.alpha = (baseAlpha * rippleAlpha).roundToInt()
        drawWrappedSegment(canvas, measure, length, origin + distance - segmentLength, segmentLength, paint)
        drawWrappedSegment(canvas, measure, length, origin - distance, segmentLength, paint)
        paint.alpha = baseAlpha
    }

    private fun drawWrappedSegment(
        canvas: Canvas,
        measure: PathMeasure,
        length: Float,
        start: Float,
        segmentLength: Float,
        paint: Paint
    ) {
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

    private fun cornerFractionsFor(geometry: StyleGeometry): FloatArray {
        geometry.cornerFractions?.let { return it }
        val bounds = geometry.bounds
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
                geometry.measure.getPosTan(geometry.length * fraction, position, null)
                val distance = (position[0] - targetX) * (position[0] - targetX) +
                    (position[1] - targetY) * (position[1] - targetY)
                if (distance < nearestDistance) {
                    nearestDistance = distance
                    nearestFraction = fraction
                }
            }
            nearestFraction
        }.also { fractions -> geometry.cornerFractions = fractions }
    }

    private fun rippleOriginFraction(
        geometry: StyleGeometry,
        targetX: Float,
        targetY: Float
    ): Float {
        geometry.rippleOriginFraction?.let { return it }
        val position = FloatArray(2)
        var nearestFraction = 0f
        var nearestDistance = Float.MAX_VALUE
        for (sample in 0..CORNER_PATH_SAMPLES) {
            val fraction = sample.toFloat() / CORNER_PATH_SAMPLES
            geometry.measure.getPosTan(geometry.length * fraction, position, null)
            val distance = (position[0] - targetX) * (position[0] - targetX) +
                (position[1] - targetY) * (position[1] - targetY)
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestFraction = fraction
            }
        }
        return nearestFraction.also { fraction -> geometry.rippleOriginFraction = fraction }
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
        cachedStyleGeometry = null
    }

    private fun styleGeometryFor(path: Path): StyleGeometry {
        cachedStyleGeometry?.takeIf { it.path === path }?.let { return it }
        return StyleGeometry(
            path = path,
            measure = PathMeasure(path, false),
            bounds = boundsOf(path)
        ).also { geometry ->
            geometry.length = geometry.measure.length
            cachedStyleGeometry = geometry
        }
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

    private class StyleGeometry(
        val path: Path,
        val measure: PathMeasure,
        val bounds: RectF,
        var length: Float = 0f,
        var cornerFractions: FloatArray? = null,
        var rippleOriginFraction: Float? = null
    )

    private companion object {
        private const val SNAKE_SEGMENT_FRACTION = 0.18f
        private const val CORNER_SEGMENT_FRACTION = 0.14f
        private const val CORNER_COUNT = 4
        private const val CORNER_PATH_SAMPLES = 240
        private const val RAIN_DROP_LENGTH_FRACTION = 0.14f
        private const val RIPPLE_SEGMENT_FRACTION = 0.12f
        private val RAIN_OFFSETS = floatArrayOf(0f, 0.18f, 0.43f, 0.67f)
        private const val GRADIENT_ROTATION_PER_CYCLE_DEGREES = 220f
        private const val FULL_ROTATION_DEGREES = 360f
        private const val RGB_MASK = 0x00FFFFFF
        private const val ALPHA_SHIFT = 24
        private const val OPAQUE_ALPHA = -0x1000000
    }
}
