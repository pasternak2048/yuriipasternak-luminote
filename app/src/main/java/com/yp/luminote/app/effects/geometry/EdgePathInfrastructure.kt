package com.yp.luminote.app.effects.geometry

import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF

/**
 * The cache boundary for every physical edge surface.  It deliberately sits above
 * [DisplayOutline]: that class remains the owner of display-shape semantics.
 */
internal class EdgePathGeometryProvider(private val outline: DisplayOutline) {
    private var key: EdgePathCacheKey? = null
    private var geometry: EdgePathGeometry? = null

    fun geometryFor(
        strokeWidth: Float,
        edgeCalibrationPx: Float,
        cornerCalibrationPx: Float,
        cornerShape: Float,
        extraEnvelopePx: Float
    ): EdgePathGeometry? {
        val requestedKey = EdgePathCacheKey(
            outlineVersion = outline.version,
            strokeWidth = strokeWidth,
            edgeCalibrationPx = edgeCalibrationPx,
            cornerCalibrationPx = cornerCalibrationPx,
            cornerShape = cornerShape,
            extraEnvelopePx = extraEnvelopePx
        )
        if (key != requestedKey) {
            geometry = EdgePathGeometry(outline.centerlinePath(
                strokeWidth = strokeWidth,
                edgeCalibrationPx = edgeCalibrationPx,
                cornerCalibrationPx = cornerCalibrationPx,
                cornerShape = cornerShape,
                extraEnvelopePx = extraEnvelopePx
            ))
            key = requestedKey
        }
        return geometry?.takeIf { it.length > 0f }
    }

    /** Returns the keyed snapshot even when its contour is not drawable yet. */
    fun geometrySnapshotFor(
        strokeWidth: Float,
        edgeCalibrationPx: Float,
        cornerCalibrationPx: Float,
        cornerShape: Float,
        extraEnvelopePx: Float
    ): EdgePathGeometry {
        geometryFor(strokeWidth, edgeCalibrationPx, cornerCalibrationPx, cornerShape, extraEnvelopePx)
        return requireNotNull(geometry)
    }

    /** Preview callers need cache identity even on JVMs whose PathMeasure has no contour witness. */
    fun pathFor(
        strokeWidth: Float,
        edgeCalibrationPx: Float,
        cornerCalibrationPx: Float,
        cornerShape: Float,
        extraEnvelopePx: Float
    ): Path {
        val requestedKey = EdgePathCacheKey(
            outline.version, strokeWidth, edgeCalibrationPx, cornerCalibrationPx, cornerShape, extraEnvelopePx
        )
        if (key != requestedKey) {
            geometry = EdgePathGeometry(outline.centerlinePath(
                strokeWidth, edgeCalibrationPx, cornerCalibrationPx, cornerShape, extraEnvelopePx
            ))
            key = requestedKey
        }
        return geometry!!.path
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

/** Validated physical-contour interval, independent of a drawing backend. */
internal data class WrappedSegmentRange(val start: Float, val length: Float)

/**
 * Cached physical contour. It contains no renderer scratch state: every drawing
 * consumer supplies its own [Path] and position/tangent buffers.
 */
internal class EdgePathGeometry(path: Path) {
    val path = path
    val measure = PathMeasure(path, false)
    val length = measure.length
    /** Immutable contour bounds, computed once with the authoritative physical path. */
    val bounds = RectF().also { path.computeBounds(it, true) }

    fun wrappedSegment(startFraction: Float, fraction: Float, out: Path): Path {
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

    fun pointAt(fraction: Float, position: FloatArray, tangent: FloatArray): FloatArray {
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
