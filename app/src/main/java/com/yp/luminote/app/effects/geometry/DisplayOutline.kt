package com.yp.luminote.app.effects.geometry

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import android.view.RoundedCorner
import android.view.WindowInsets
import kotlin.math.abs

/**
 * Owns the validated display outline and derived paths in overlay coordinates.
 * Paths are read-only to callers. All updates happen on the UI thread.
 */
internal class DisplayOutline(private val density: Float) {
    private val displayPath = Path()
    private var windowDisplayShapePath: Path? = null
    private var cornerRadii: FloatArray? = null
    private var width = 0
    private var height = 0
    private var geometryVersion = 0
    private val strokePathCache = mutableMapOf<Float, Path>()

    val path: Path get() = displayPath
    val version: Int get() = geometryVersion
    val opticalInsetPx = density * 2f

    @Suppress("UNUSED_PARAMETER")
    constructor(displayShapePath: Path, density: Float) : this(density)

    fun resize(width: Int, height: Int) {
        if (this.width == width && this.height == height) return
        this.width = width
        this.height = height
        windowDisplayShapePath = null
        cornerRadii = null
        rebuildDisplayPath()
    }

    fun updateDisplayShape(path: Path) {
        windowDisplayShapePath = Path(path)
        rebuildDisplayPath()
    }

    fun updateInsets(insets: WindowInsets) {
        windowDisplayShapePath = insets.displayShape?.path?.let(::Path)
        cornerRadii = extractCornerRadii(insets)
        rebuildDisplayPath()
    }

    fun clearRenderCaches() {
        strokePathCache.clear()
    }

    fun strokePath(insetPx: Float): Path =
        strokePathCache.getOrPut(insetPx) { buildStrokePath(insetPx) }

    /**
     * Keeps the calibration command contract while using the established rounded-outline
     * transform. Corner-shape and per-corner deformation are intentionally not applied: their
     * replacement geometry distorted physical corners on devices with real RoundedCorner data.
     */
    fun centerlinePath(
        strokeWidth: Float,
        edgeCalibrationPx: Float = 0f,
        cornerCalibrationPx: Float = 0f,
        cornerShape: Float = 0.5f,
        extraEnvelopePx: Float = 0f
    ): Path {
        if (width <= 0 || height <= 0) {
            return Path()
        }

        val halfStroke =
            strokeWidth
                .takeIf { it.isFinite() }
                ?.coerceAtLeast(0f)
                ?.div(2f)
                ?: 0f

        val edgeOffset =
            edgeCalibrationPx
                .takeIf { it.isFinite() }
                ?: 0f

        val cornerOffset =
            cornerCalibrationPx
                .takeIf { it.isFinite() }
                ?: 0f

        val shape =
            cornerShape
                .takeIf { it.isFinite() }
                ?.coerceIn(0f, 1f)
                ?: DEFAULT_CORNER_SHAPE

        val envelope =
            extraEnvelopePx
                .takeIf { it.isFinite() }
                ?.coerceAtLeast(0f)
                ?: 0f

        /*
         * edgeCalibrationPx already contains the automatic optical inset supplied
         * by the renderer. Remove it here so we can distinguish the automatic
         * baseline from the user's calibration delta.
         */
        val userEdgeCalibrationPx = edgeOffset - opticalInsetPx

        val hasCornerCalibration =
            abs(cornerOffset) > CALIBRATION_EPSILON_PX

        val hasCornerShapeCalibration =
            abs(shape - DEFAULT_CORNER_SHAPE) > CALIBRATION_EPSILON

        /*
         * IMPORTANT:
         *
         * With neutral corner calibration we preserve the exact established
         * physical-display rendering path.
         *
         * Edge calibration remains a simple additional inset/outset on top of
         * that automatically detected display geometry.
         */
        if (!hasCornerCalibration && !hasCornerShapeCalibration) {
            return strokePath(
                halfStroke +
                        opticalInsetPx +
                        userEdgeCalibrationPx +
                        envelope
            )
        }

        return buildCalibratedCenterline(
            insetPx =
                halfStroke +
                        opticalInsetPx +
                        userEdgeCalibrationPx +
                        envelope,
            cornerCalibrationPx = cornerOffset,
            cornerShape = shape
        )
    }

    fun dpToPx(dp: Float): Float = dp * density

    private fun buildCalibratedCenterline(
        insetPx: Float,
        cornerCalibrationPx: Float,
        cornerShape: Float
    ): Path {
        val left = insetPx
        val top = insetPx
        val right = width.toFloat() - insetPx
        val bottom = height.toFloat() - insetPx

        if (right <= left || bottom <= top) {
            return strokePath(insetPx)
        }

        /*
         * The detected physical corner geometry remains the baseline.
         *
         * Only when Android cannot provide valid RoundedCorner data do we use
         * the existing fallback radius.
         */
        val physicalRadii =
            cornerRadii ?: FloatArray(4) {
                minOf(width, height) * CORNER_RADIUS_FRACTION
            }

        val maxCornerExtent =
            minOf(
                (right - left) / 2f,
                (bottom - top) / 2f
            ).coerceAtLeast(0f)

        fun resolveCornerExtent(physicalRadius: Float): Float {
            val automaticRadius =
                physicalRadius - insetPx

            return (
                    automaticRadius +
                            cornerCalibrationPx
                    ).coerceIn(
                    0f,
                    maxCornerExtent
                )
        }

        val topLeft = resolveCornerExtent(physicalRadii[0])
        val topRight = resolveCornerExtent(physicalRadii[1])
        val bottomRight = resolveCornerExtent(physicalRadii[2])
        val bottomLeft = resolveCornerExtent(physicalRadii[3])

        val bezierFactor =
            resolveCornerBezierFactor(cornerShape)

        return Path().apply {
            /*
             * Top-left -> top-right
             */
            moveTo(
                left + topLeft,
                top
            )

            lineTo(
                right - topRight,
                top
            )

            appendTopRightCorner(
                right = right,
                top = top,
                radius = topRight,
                factor = bezierFactor
            )

            /*
             * Top-right -> bottom-right
             */
            lineTo(
                right,
                bottom - bottomRight
            )

            appendBottomRightCorner(
                right = right,
                bottom = bottom,
                radius = bottomRight,
                factor = bezierFactor
            )

            /*
             * Bottom-right -> bottom-left
             */
            lineTo(
                left + bottomLeft,
                bottom
            )

            appendBottomLeftCorner(
                left = left,
                bottom = bottom,
                radius = bottomLeft,
                factor = bezierFactor
            )

            /*
             * Bottom-left -> top-left
             */
            lineTo(
                left,
                top + topLeft
            )

            appendTopLeftCorner(
                left = left,
                top = top,
                radius = topLeft,
                factor = bezierFactor
            )

            close()
        }
    }

    private fun Path.appendTopRightCorner(
        right: Float,
        top: Float,
        radius: Float,
        factor: Float
    ) {
        if (radius <= 0f) {
            lineTo(right, top)
            return
        }

        val handle = radius * factor

        cubicTo(
            right - radius + handle,
            top,
            right,
            top + radius - handle,
            right,
            top + radius
        )
    }

    private fun Path.appendBottomRightCorner(
        right: Float,
        bottom: Float,
        radius: Float,
        factor: Float
    ) {
        if (radius <= 0f) {
            lineTo(right, bottom)
            return
        }

        val handle = radius * factor

        cubicTo(
            right,
            bottom - radius + handle,
            right - radius + handle,
            bottom,
            right - radius,
            bottom
        )
    }

    private fun Path.appendBottomLeftCorner(
        left: Float,
        bottom: Float,
        radius: Float,
        factor: Float
    ) {
        if (radius <= 0f) {
            lineTo(left, bottom)
            return
        }

        val handle = radius * factor

        cubicTo(
            left + radius - handle,
            bottom,
            left,
            bottom - radius + handle,
            left,
            bottom - radius
        )
    }

    private fun Path.appendTopLeftCorner(
        left: Float,
        top: Float,
        radius: Float,
        factor: Float
    ) {
        if (radius <= 0f) {
            lineTo(left, top)
            return
        }

        val handle = radius * factor

        cubicTo(
            left,
            top + radius - handle,
            left + radius - handle,
            top,
            left + radius,
            top
        )
    }

    private fun resolveCornerBezierFactor(
        cornerShape: Float
    ): Float {
        val normalized =
            cornerShape
                .takeIf { it.isFinite() }
                ?.coerceIn(0f, 1f)
                ?: DEFAULT_CORNER_SHAPE

        return lerp(
            CORNER_SHAPE_MIN_FACTOR,
            CORNER_SHAPE_MAX_FACTOR,
            normalized
        )
    }

    private fun rebuildDisplayPath() {
        if (width <= 0 || height <= 0) return

        geometryVersion++
        clearRenderCaches()

        displayPath.reset()

        val cornerPath = cornerRadii?.let { radii ->
            roundedRectPath(insetPx = 0f, cornerRadii = radii)
        }
        val shapePath = windowDisplayShapePath?.takeIf {
            isInViewCoordinates(it, width, height)
        }

        if (cornerPath != null && shapePath != null) {
            val safeContour = Path()
            if (safeContour.op(shapePath, cornerPath, Path.Op.INTERSECT) && !safeContour.isEmpty) {
                displayPath.addPath(safeContour)
                return
            }
        }

        cornerPath?.let { path ->
            displayPath.addPath(path)
            return
        }

        shapePath?.let { path ->
            displayPath.addPath(path)
            return
        }

        displayPath.addPath(roundedRectPath(insetPx = 0f, cornerRadii = null))
    }

    private fun buildStrokePath(insetPx: Float): Path {
        val bounds = RectF()
        displayPath.computeBounds(bounds, true)
        if (bounds.width() <= insetPx * 2f || bounds.height() <= insetPx * 2f) {
            return Path(displayPath)
        }

        val insetBounds = RectF(bounds).apply { inset(insetPx, insetPx) }
        val transform = Matrix().apply {
            setRectToRect(bounds, insetBounds, Matrix.ScaleToFit.FILL)
        }
        return Path().apply { addPath(displayPath, transform) }
    }

    private fun roundedRectPath(insetPx: Float, cornerRadii: FloatArray?): Path {
        val left = insetPx.coerceAtLeast(0f)
        val top = left
        val right = (width.toFloat() - insetPx).coerceAtLeast(left)
        val bottom = (height.toFloat() - insetPx).coerceAtLeast(top)
        val maxRadius = minOf(right - left, bottom - top) / 2f
        val radii = cornerRadii ?: FloatArray(4) {
            minOf(width, height) * CORNER_RADIUS_FRACTION
        }
        val adjustedRadii = FloatArray(8)
        radii.forEachIndexed { index, radius ->
            val adjusted = (radius - insetPx).coerceIn(0f, maxRadius)
            adjustedRadii[index * 2] = adjusted
            adjustedRadii[index * 2 + 1] = adjusted
        }

        return Path().apply {
            addRoundRect(
                RectF(left, top, right, bottom),
                adjustedRadii,
                Path.Direction.CW
            )
        }
    }

    private fun extractCornerRadii(insets: WindowInsets): FloatArray? {
        if (width <= 0 || height <= 0) return null

        val corners = arrayOf(
            insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT),
            insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT),
            insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT),
            insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
        )
        if (corners.any { it == null }) return null

        val maxRadius = minOf(width, height) * MAX_CORNER_RADIUS_FRACTION
        fun isCurrentCorner(corner: RoundedCorner, left: Boolean, top: Boolean): Boolean {
            val center = corner.center
            return corner.radius > 0 && corner.radius <= maxRadius &&
                center.x in 0..width && center.y in 0..height &&
                (center.x < width / 2) == left && (center.y < height / 2) == top
        }

        val topLeft = corners[0]!!
        val topRight = corners[1]!!
        val bottomRight = corners[2]!!
        val bottomLeft = corners[3]!!
        if (!isCurrentCorner(topLeft, left = true, top = true) ||
            !isCurrentCorner(topRight, left = false, top = true) ||
            !isCurrentCorner(bottomRight, left = false, top = false) ||
            !isCurrentCorner(bottomLeft, left = true, top = false)
        ) return null

        return floatArrayOf(
            topLeft.radius.toFloat(),
            topRight.radius.toFloat(),
            bottomRight.radius.toFloat(),
            bottomLeft.radius.toFloat()
        )
    }

    private fun lerp(
        start: Float,
        end: Float,
        fraction: Float
    ): Float =
        start + (end - start) * fraction

    companion object {
        private const val CORNER_RADIUS_FRACTION = 0.035f
        private const val MAX_CORNER_RADIUS_FRACTION = 0.25f

        private const val DEFAULT_CORNER_SHAPE = 0.5f

        private const val QUARTER_CIRCLE_BEZIER_FACTOR = 0.55228475f
        private const val CORNER_SHAPE_RANGE = 0.18f

        private const val CORNER_SHAPE_MIN_FACTOR =
            QUARTER_CIRCLE_BEZIER_FACTOR - CORNER_SHAPE_RANGE

        private const val CORNER_SHAPE_MAX_FACTOR =
            QUARTER_CIRCLE_BEZIER_FACTOR + CORNER_SHAPE_RANGE

        private const val CALIBRATION_EPSILON = 0.0001f
        private const val CALIBRATION_EPSILON_PX = 0.01f

        fun isInViewCoordinates(
            path: Path,
            width: Int,
            height: Int,
            tolerancePx: Float = 2f
        ): Boolean {
            val bounds = RectF()
            path.computeBounds(bounds, true)

            return abs(bounds.left) <= tolerancePx &&
                    abs(bounds.top) <= tolerancePx &&
                    abs(bounds.right - width) <= tolerancePx &&
                    abs(bounds.bottom - height) <= tolerancePx
        }
    }
}
