package com.yp.luminote.app.effects

import android.graphics.Path
import android.graphics.PathMeasure

/**
 * The cache boundary for every physical edge surface.  It deliberately sits above
 * [DisplayOutline]: that class remains the owner of display-shape semantics.
 */
internal class EdgePathCache(private val outline: DisplayOutline) {
    private var hasGeometry = false
    private var outlineVersion = Int.MIN_VALUE
    private var strokeWidth = Float.NaN
    private var edgeCalibrationPx = Float.NaN
    private var cornerCalibrationPx = Float.NaN
    private var cornerShape = Float.NaN
    private var extraEnvelopePx = Float.NaN
    private var geometry: EdgePathGeometry? = null

    fun geometryFor(
        strokeWidth: Float,
        edgeCalibrationPx: Float,
        cornerCalibrationPx: Float,
        cornerShape: Float,
        extraEnvelopePx: Float
    ): EdgePathGeometry? {
        if (!matches(strokeWidth, edgeCalibrationPx, cornerCalibrationPx, cornerShape, extraEnvelopePx)) {
            geometry = EdgePathGeometry(outline.centerlinePath(
                strokeWidth = strokeWidth,
                edgeCalibrationPx = edgeCalibrationPx,
                cornerCalibrationPx = cornerCalibrationPx,
                cornerShape = cornerShape,
                extraEnvelopePx = extraEnvelopePx
            ))
            remember(strokeWidth, edgeCalibrationPx, cornerCalibrationPx, cornerShape, extraEnvelopePx)
        }
        return geometry?.takeIf { it.length > 0f }
    }

    /** Preview callers need cache identity even on JVMs whose PathMeasure has no contour witness. */
    fun pathFor(
        strokeWidth: Float,
        edgeCalibrationPx: Float,
        cornerCalibrationPx: Float,
        cornerShape: Float,
        extraEnvelopePx: Float
    ): Path {
        if (!matches(strokeWidth, edgeCalibrationPx, cornerCalibrationPx, cornerShape, extraEnvelopePx)) {
            geometry = EdgePathGeometry(outline.centerlinePath(
                strokeWidth, edgeCalibrationPx, cornerCalibrationPx, cornerShape, extraEnvelopePx
            ))
            remember(strokeWidth, edgeCalibrationPx, cornerCalibrationPx, cornerShape, extraEnvelopePx)
        }
        return geometry!!.path
    }

    private fun matches(
        strokeWidth: Float,
        edgeCalibrationPx: Float,
        cornerCalibrationPx: Float,
        cornerShape: Float,
        extraEnvelopePx: Float
    ): Boolean = hasGeometry &&
        outlineVersion == outline.version &&
        this.strokeWidth == strokeWidth &&
        this.edgeCalibrationPx == edgeCalibrationPx &&
        this.cornerCalibrationPx == cornerCalibrationPx &&
        this.cornerShape == cornerShape &&
        this.extraEnvelopePx == extraEnvelopePx

    private fun remember(
        strokeWidth: Float,
        edgeCalibrationPx: Float,
        cornerCalibrationPx: Float,
        cornerShape: Float,
        extraEnvelopePx: Float
    ) {
        hasGeometry = true
        outlineVersion = outline.version
        this.strokeWidth = strokeWidth
        this.edgeCalibrationPx = edgeCalibrationPx
        this.cornerCalibrationPx = cornerCalibrationPx
        this.cornerShape = cornerShape
        this.extraEnvelopePx = extraEnvelopePx
    }
}

/** Every geometry-affecting parameter is explicit, including family-specific bloom containment. */
internal data class EdgePathCacheKey(
    val outlineVersion: Int,
    val strokeWidth: Float,
    val edgeCalibrationPx: Float,
    val cornerCalibrationPx: Float,
    val cornerShape: Float,
    val extraEnvelopePx: Float
)

/**
 * Caller-owned geometry and mutable scratch paths.  A segment path is never shared
 * globally or returned from the cache.
 */
internal class EdgePathGeometry(path: Path) {
    val path = path
    val measure = PathMeasure(path, false)
    val length = measure.length
    val segmentPath = Path()
    val scratchPath = Path()
    val position = FloatArray(2)
    val tangent = FloatArray(2)

    fun wrappedSegment(startFraction: Float, fraction: Float, out: Path = segmentPath): Path {
        EdgeSegmentRenderer.append(
            measure = measure,
            contourLength = length,
            start = startFraction * length,
            segmentLength = fraction * length,
            out = out,
            allowFullContour = true
        )
        return out
    }

    fun pointAt(fraction: Float): FloatArray {
        measure.getPosTan(normalized(fraction) * length, position, tangent)
        return position
    }

    fun normalized(value: Float): Float = EdgeSegmentRenderer.normalizedDistance(value, 1f)
}

/** One wrapped contour implementation; policy is chosen by the renderer family. */
internal object EdgeSegmentRenderer {
    const val MIN_RENDERABLE_SEGMENT_PX = 0.5f

    fun normalizedDistance(value: Float, contourLength: Float): Float {
        if (!value.isFinite() || !contourLength.isFinite() || contourLength <= 0f) return 0f
        return ((value % contourLength) + contourLength) % contourLength
    }

    fun range(
        start: Float,
        contourLength: Float,
        segmentLength: Float,
        allowFullContour: Boolean
    ): WrappedSegmentRange? {
        if (!start.isFinite() || !contourLength.isFinite() || !segmentLength.isFinite()) return null
        if (contourLength <= MIN_RENDERABLE_SEGMENT_PX || segmentLength < MIN_RENDERABLE_SEGMENT_PX) return null
        if (!allowFullContour && segmentLength >= contourLength - MIN_RENDERABLE_SEGMENT_PX) return null
        return WrappedSegmentRange(normalizedDistance(start, contourLength), segmentLength.coerceAtMost(contourLength))
    }

    fun append(
        measure: PathMeasure,
        contourLength: Float,
        start: Float,
        segmentLength: Float,
        out: Path,
        allowFullContour: Boolean
    ): Boolean {
        // Do the range validation inline.  `range()` remains the immutable test/query helper,
        // but constructing its data object here would allocate once for every rendered segment.
        if (!start.isFinite() || !contourLength.isFinite() || !segmentLength.isFinite() ||
            contourLength <= MIN_RENDERABLE_SEGMENT_PX ||
            segmentLength < MIN_RENDERABLE_SEGMENT_PX ||
            (!allowFullContour && segmentLength >= contourLength - MIN_RENDERABLE_SEGMENT_PX)
        ) {
            out.reset()
            return false
        }
        val normalizedStart = normalizedDistance(start, contourLength)
        val normalizedLength = segmentLength.coerceAtMost(contourLength)
        out.reset()
        val end = normalizedStart + normalizedLength
        if (end <= contourLength) {
            measure.getSegment(normalizedStart, end, out, true)
        } else {
            measure.getSegment(normalizedStart, contourLength, out, true)
            measure.getSegment(0f, end - contourLength, out, true)
        }
        return true
    }
}
