package com.yp.luminote.app.effects

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
internal class DisplayOutline(density: Float) {
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

    @Suppress("UNUSED_PARAMETER")
    fun updateDisplayShape(path: Path) {
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

    private fun rebuildDisplayPath() {

        if (width <= 0 || height <= 0) {
            return
        }

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

    private fun buildStrokePath(
        insetPx: Float
    ): Path {
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

    companion object {
        private const val CORNER_RADIUS_FRACTION = 0.035f
        private const val MAX_CORNER_RADIUS_FRACTION = 0.25f

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
