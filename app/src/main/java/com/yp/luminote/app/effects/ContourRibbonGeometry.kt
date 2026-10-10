package com.yp.luminote.app.effects

import android.graphics.PathMeasure
import android.util.Log
import com.yp.luminote.app.BuildConfig
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Immutable, renderer-neutral contour-space mesh input.
 *
 * Each cross section has an explicit left and right vertex.  The final cross section duplicates
 * the first in position and v, but has u == 1, so renderers never need to close a texture/shader
 * coordinate with a modulo operation.
 */
internal class ContourRibbonGeometry private constructor(
    val vertexData: FloatArray,
    val vertexCount: Int,
    val bounds: ContourRibbonBounds,
    val contourLengthPx: Float,
    val crossSectionCount: Int,
    val cacheKey: ContourRibbonGeometryKey
) {
    companion object {
        const val COMPONENTS_PER_VERTEX = 4
        const val X_OFFSET = 0
        const val Y_OFFSET = 1
        const val U_OFFSET = 2
        const val V_OFFSET = 3
        const val VERTICES_PER_CROSS_SECTION = 2
        const val MAX_CROSS_SECTIONS = 2_048
        const val MAX_VERTEX_COUNT = MAX_CROSS_SECTIONS * VERTICES_PER_CROSS_SECTION
        const val MAX_VERTEX_BYTES = MAX_VERTEX_COUNT * COMPONENTS_PER_VERTEX * Float.SIZE_BYTES

        internal fun create(
            vertexData: FloatArray,
            contourLengthPx: Float,
            cacheKey: ContourRibbonGeometryKey
        ): ContourRibbonGeometry? {
            if (vertexData.size < COMPONENTS_PER_VERTEX * VERTICES_PER_CROSS_SECTION * 3 ||
                vertexData.size % (COMPONENTS_PER_VERTEX * VERTICES_PER_CROSS_SECTION) != 0 ||
                !contourLengthPx.isFinite() || contourLengthPx <= MIN_CONTOUR_LENGTH_PX
            ) return null
            if (vertexData.any { !it.isFinite() }) return null

            val crossSections = vertexData.size /
                (COMPONENTS_PER_VERTEX * VERTICES_PER_CROSS_SECTION)
            if (crossSections > MAX_CROSS_SECTIONS) return null
            val topology = validateTopology(vertexData, crossSections) ?: return null
            return ContourRibbonGeometry(
                vertexData = vertexData,
                vertexCount = crossSections * VERTICES_PER_CROSS_SECTION,
                bounds = topology,
                contourLengthPx = contourLengthPx,
                crossSectionCount = crossSections,
                cacheKey = cacheKey
            )
        }

        /** Exposed for focused JVM tests and defensive callers supplying pre-sampled contours. */
        internal fun fromCrossSections(
            crossSections: List<ContourRibbonCrossSection>,
            contourLengthPx: Float,
            cacheKey: ContourRibbonGeometryKey = ContourRibbonGeometryKey.invalid()
        ): ContourRibbonGeometry? {
            if (crossSections.size < 3) return null
            val vertices = FloatArray(crossSections.size * VERTICES_PER_CROSS_SECTION * COMPONENTS_PER_VERTEX)
            crossSections.forEachIndexed { index, section ->
                val base = index * VERTICES_PER_CROSS_SECTION * COMPONENTS_PER_VERTEX
                vertices[base + X_OFFSET] = section.leftX
                vertices[base + Y_OFFSET] = section.leftY
                vertices[base + U_OFFSET] = section.u
                vertices[base + V_OFFSET] = 0f
                vertices[base + COMPONENTS_PER_VERTEX + X_OFFSET] = section.rightX
                vertices[base + COMPONENTS_PER_VERTEX + Y_OFFSET] = section.rightY
                vertices[base + COMPONENTS_PER_VERTEX + U_OFFSET] = section.u
                vertices[base + COMPONENTS_PER_VERTEX + V_OFFSET] = 1f
            }
            return create(vertices, contourLengthPx, cacheKey)
        }

        private fun validateTopology(data: FloatArray, count: Int): ContourRibbonBounds? {
            fun vertex(section: Int, side: Int, offset: Int): Float =
                data[(section * VERTICES_PER_CROSS_SECTION + side) * COMPONENTS_PER_VERTEX + offset]
            fun distanceSquared(aX: Float, aY: Float, bX: Float, bY: Float): Float {
                val x = aX - bX
                val y = aY - bY
                return x * x + y * y
            }

            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY
            var winding = 0f
            for (section in 0 until count) {
                val u = vertex(section, 0, U_OFFSET)
                if (u !in 0f..1f || (section > 0 && u <= vertex(section - 1, 0, U_OFFSET))) return null
                if (abs(u - vertex(section, 1, U_OFFSET)) > U_EPSILON ||
                    vertex(section, 0, V_OFFSET) != 0f || vertex(section, 1, V_OFFSET) != 1f
                ) return null
                for (side in 0..1) {
                    val x = vertex(section, side, X_OFFSET)
                    val y = vertex(section, side, Y_OFFSET)
                    left = min(left, x); top = min(top, y); right = max(right, x); bottom = max(bottom, y)
                }
                if (distanceSquared(
                        vertex(section, 0, X_OFFSET), vertex(section, 0, Y_OFFSET),
                        vertex(section, 1, X_OFFSET), vertex(section, 1, Y_OFFSET)
                    ) < MIN_WIDTH_SQUARED
                ) return null
            }
            if (vertex(0, 0, U_OFFSET) != 0f || vertex(count - 1, 0, U_OFFSET) != 1f ||
                distanceSquared(vertex(0, 0, X_OFFSET), vertex(0, 0, Y_OFFSET), vertex(count - 1, 0, X_OFFSET), vertex(count - 1, 0, Y_OFFSET)) > SEAM_EPSILON_SQUARED ||
                distanceSquared(vertex(0, 1, X_OFFSET), vertex(0, 1, Y_OFFSET), vertex(count - 1, 1, X_OFFSET), vertex(count - 1, 1, Y_OFFSET)) > SEAM_EPSILON_SQUARED
            ) return null

            for (index in 0 until count - 1) {
                val ax = vertex(index, 0, X_OFFSET); val ay = vertex(index, 0, Y_OFFSET)
                val bx = vertex(index, 1, X_OFFSET); val by = vertex(index, 1, Y_OFFSET)
                val cx = vertex(index + 1, 1, X_OFFSET); val cy = vertex(index + 1, 1, Y_OFFSET)
                val dx = vertex(index + 1, 0, X_OFFSET); val dy = vertex(index + 1, 0, Y_OFFSET)
                val area = signedArea(ax, ay, bx, by, cx, cy, dx, dy)
                if (abs(area) < MIN_QUAD_AREA) return null
                if (winding == 0f) winding = area else if (winding * area <= 0f) return null
            }
            if (selfIntersects(data, count, side = 0) || selfIntersects(data, count, side = 1)) return null
            return ContourRibbonBounds(left, top, right, bottom)
        }

        private fun selfIntersects(data: FloatArray, count: Int, side: Int): Boolean {
            fun x(index: Int) = data[(index * VERTICES_PER_CROSS_SECTION + side) * COMPONENTS_PER_VERTEX + X_OFFSET]
            fun y(index: Int) = data[(index * VERTICES_PER_CROSS_SECTION + side) * COMPONENTS_PER_VERTEX + Y_OFFSET]
            val segments = count - 1
            for (a in 0 until segments) for (b in a + 1 until segments) {
                if (b == a + 1 || (a == 0 && b == segments - 1)) continue
                if (segmentsIntersect(x(a), y(a), x(a + 1), y(a + 1), x(b), y(b), x(b + 1), y(b + 1))) return true
            }
            return false
        }

        private fun signedArea(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Float =
            (ax * by - ay * bx) + (bx * cy - by * cx) + (cx * dy - cy * dx) + (dx * ay - dy * ax)

        private fun segmentsIntersect(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Boolean {
            fun cross(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float) = (qx - px) * (ry - py) - (qy - py) * (rx - px)
            val a = cross(ax, ay, bx, by, cx, cy); val b = cross(ax, ay, bx, by, dx, dy)
            val c = cross(cx, cy, dx, dy, ax, ay); val d = cross(cx, cy, dx, dy, bx, by)
            return a * b < 0f && c * d < 0f
        }

        private const val MIN_CONTOUR_LENGTH_PX = 1f
        private const val MIN_WIDTH_SQUARED = 0.25f
        private const val MIN_QUAD_AREA = 0.1f
        private const val SEAM_EPSILON_SQUARED = 0.01f
        private const val U_EPSILON = 0.0001f
    }
}

internal data class ContourRibbonBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)
internal data class ContourRibbonCrossSection(val leftX: Float, val leftY: Float, val rightX: Float, val rightY: Float, val u: Float)
internal data class ContourRibbonGeometryKey(val pathKey: EdgePathCacheKey, val maxEnvelopePx: Float) {
    companion object { fun invalid() = ContourRibbonGeometryKey(EdgePathCacheKey(-1, 0f, 0f, 0f, 0f, 0f), 0f) }
}

/**
 * The centerline already reserves half of the configured stroke width.  A GPU field can extend
 * farther than that baseline on either side, so reserve only the remaining distance in its
 * physical path.  This keeps the whole ribbon inside the calibrated display surface without
 * using a clip to hide its outside half.
 */
internal fun contourRibbonExtraEnvelopePx(renderStrokeWidth: Float, maxEnvelopePx: Float): Float {
    if (!renderStrokeWidth.isFinite() || !maxEnvelopePx.isFinite()) return 0f
    return (maxEnvelopePx - renderStrokeWidth.coerceAtLeast(0f) / 2f).coerceAtLeast(0f)
}

/**
 * Curvature-aware refinement policy shared by the PathMeasure sampler and JVM tests.  The
 * envelope term limits the positional error of the displaced ribbon edge, not only its centre.
 */
internal object ContourRibbonTessellation {
    private const val MAX_CHORD_DEVIATION_PX = 0.35f
    private const val MAX_ENVELOPE_EDGE_DEVIATION_PX = 0.5f
    private const val MAX_REFINEMENT_DEPTH = 8

    fun shouldRefine(
        chordDeviationPx: Float,
        normalTurnRadians: Float,
        envelopePx: Float,
        depth: Int
    ): Boolean {
        if (!chordDeviationPx.isFinite() || !normalTurnRadians.isFinite() ||
            !envelopePx.isFinite() || depth >= MAX_REFINEMENT_DEPTH
        ) return false
        val edgeDeviation = envelopePx * kotlin.math.sin(abs(normalTurnRadians) / 2f)
        return chordDeviationPx > MAX_CHORD_DEVIATION_PX ||
            edgeDeviation > MAX_ENVELOPE_EDGE_DEVIATION_PX
    }
}

/** Cached builder. The envelope must include the maximum opaque body and zero-alpha feather. */
internal class ContourRibbonGeometryCache(private val outline: DisplayOutline) {
    private val paths = EdgePathCache(outline)
    private var hasCachedGeometry = false
    private var outlineVersion = Int.MIN_VALUE
    private var strokeWidth = Float.NaN
    private var edgeCalibrationPx = Float.NaN
    private var cornerCalibrationPx = Float.NaN
    private var cornerShape = Float.NaN
    private var extraEnvelopePx = Float.NaN
    private var maxEnvelopePx = Float.NaN
    private var cached: ContourRibbonGeometry? = null

    /** One rebuild-local rejection witness for device diagnostics; null means geometry is usable. */
    var lastRejectionReason: String? = null
        private set

    fun geometryFor(strokeWidth: Float, edgeCalibrationPx: Float, cornerCalibrationPx: Float, cornerShape: Float, extraEnvelopePx: Float, maxEnvelopePx: Float): ContourRibbonGeometry? {
        if (matches(strokeWidth, edgeCalibrationPx, cornerCalibrationPx, cornerShape, extraEnvelopePx, maxEnvelopePx)) {
            ContourOpticalRenderMetrics.recordGeometryCacheHit()
        } else {
            ContourOpticalRenderMetrics.recordGeometryCacheMiss()
            lastRejectionReason = null
            val requested = ContourRibbonGeometryKey(
                EdgePathCacheKey(outline.version, strokeWidth, edgeCalibrationPx, cornerCalibrationPx, cornerShape, extraEnvelopePx),
                maxEnvelopePx
            )
            val pathGeometry = paths.geometryFor(strokeWidth, edgeCalibrationPx, cornerCalibrationPx, cornerShape, extraEnvelopePx)
            cached = pathGeometry?.let { sampleRibbon(it.measure, it.length, maxEnvelopePx, requested) }
            if (cached == null && lastRejectionReason == null) {
                lastRejectionReason = if (pathGeometry == null) "centerline unavailable" else "ribbon topology rejected"
            }
            remember(strokeWidth, edgeCalibrationPx, cornerCalibrationPx, cornerShape, extraEnvelopePx, maxEnvelopePx)
            ContourOpticalRenderMetrics.recordGeometryRebuild(cached?.vertexCount ?: 0)
            if (BuildConfig.DEBUG && Log.isLoggable(CONTOUR_OPTICAL_LOG_TAG, Log.VERBOSE)) {
                cached?.let { geometry ->
                    Log.d(
                        CONTOUR_OPTICAL_LOG_TAG,
                        "ribbon geometry sections=${geometry.crossSectionCount} vertices=${geometry.vertexCount} " +
                            "length=${geometry.contourLengthPx} envelope=$maxEnvelopePx " +
                            "extraEnvelope=$extraEnvelopePx bounds=${geometry.bounds} seam=explicit-u-0-to-1"
                    )
                }
            }
        }
        return cached
    }

    private fun matches(strokeWidth: Float, edgeCalibrationPx: Float, cornerCalibrationPx: Float, cornerShape: Float, extraEnvelopePx: Float, maxEnvelopePx: Float): Boolean =
        hasCachedGeometry && outlineVersion == outline.version && this.strokeWidth == strokeWidth &&
            this.edgeCalibrationPx == edgeCalibrationPx && this.cornerCalibrationPx == cornerCalibrationPx &&
            this.cornerShape == cornerShape && this.extraEnvelopePx == extraEnvelopePx && this.maxEnvelopePx == maxEnvelopePx

    private fun remember(strokeWidth: Float, edgeCalibrationPx: Float, cornerCalibrationPx: Float, cornerShape: Float, extraEnvelopePx: Float, maxEnvelopePx: Float) {
        hasCachedGeometry = true
        outlineVersion = outline.version
        this.strokeWidth = strokeWidth
        this.edgeCalibrationPx = edgeCalibrationPx
        this.cornerCalibrationPx = cornerCalibrationPx
        this.cornerShape = cornerShape
        this.extraEnvelopePx = extraEnvelopePx
        this.maxEnvelopePx = maxEnvelopePx
    }

    private fun sampleRibbon(measure: PathMeasure, length: Float, envelope: Float, key: ContourRibbonGeometryKey): ContourRibbonGeometry? {
        if (!length.isFinite() || length <= 1f || !envelope.isFinite() || envelope <= 0f) {
            lastRejectionReason = "invalid contour length/envelope"
            return null
        }
        val positions = FloatArray(2)
        val tangents = FloatArray(2)
        fun sample(distance: Float): Sample? {
            if (!measure.getPosTan(distance, positions, tangents)) {
                lastRejectionReason = "PathMeasure position/tangent unavailable"
                return null
            }
            val magnitude = sqrt(tangents[0] * tangents[0] + tangents[1] * tangents[1])
            if (!magnitude.isFinite() || magnitude <= TANGENT_EPSILON) {
                lastRejectionReason = "invalid contour tangent"
                return null
            }
            return Sample(distance, positions[0], positions[1], -tangents[1] / magnitude, tangents[0] / magnitude)
        }
        val intervals = ceil(length / MAX_SEED_ARC_STEP_PX).toInt().coerceIn(MIN_INTERVALS, MAX_SEED_INTERVALS)
        val samples = ArrayList<Sample>(intervals + 1)
        val first = sample(0f) ?: return null
        samples += first
        // Each split consumes one of these slots.  Reserving the unrefined interval endpoints
        // first makes the final section count strict even with deep adaptive recursion.
        var refinementBudget = ContourRibbonGeometry.MAX_CROSS_SECTIONS - 1 - intervals
        fun appendRefined(start: Sample, end: Sample, depth: Int): Boolean {
            val midpoint = sample((start.distance + end.distance) / 2f) ?: return false
            val chordMidX = (start.x + end.x) / 2f
            val chordMidY = (start.y + end.y) / 2f
            val chordDeviation = sqrt((midpoint.x - chordMidX) * (midpoint.x - chordMidX) + (midpoint.y - chordMidY) * (midpoint.y - chordMidY))
            val normalDot = (start.normalX * end.normalX + start.normalY * end.normalY).coerceIn(-1f, 1f)
            val normalTurn = acos(normalDot)
            if (refinementBudget > 0 && ContourRibbonTessellation.shouldRefine(chordDeviation, normalTurn, envelope, depth)) {
                refinementBudget--
                return appendRefined(start, midpoint, depth + 1) && appendRefined(midpoint, end, depth + 1)
            }
            samples += end
            return true
        }
        var start = first
        for (index in 1..intervals) {
            val end = sample(if (index == intervals) length else length * index / intervals) ?: return null
            if (!appendRefined(start, end, 0)) {
                if (lastRejectionReason == null) lastRejectionReason = "adaptive tessellation failed"
                return null
            }
            start = end
        }
        if (samples.size > ContourRibbonGeometry.MAX_CROSS_SECTIONS) {
            lastRejectionReason = "cross-section cap exceeded"
            return null
        }
        val sections = samples.map { point ->
            ContourRibbonCrossSection(
                point.x + point.normalX * envelope, point.y + point.normalY * envelope,
                point.x - point.normalX * envelope, point.y - point.normalY * envelope,
                point.distance / length
            )
        }.toMutableList()
        // PathMeasure can represent a closed contour's final sample with tiny native drift.
        // Copy the first position explicitly while preserving u=1 for an unambiguous seam.
        val firstSection = sections.first()
        sections[sections.lastIndex] = sections.last().copy(leftX = firstSection.leftX, leftY = firstSection.leftY, rightX = firstSection.rightX, rightY = firstSection.rightY)
        return ContourRibbonGeometry.fromCrossSections(sections, length, key).also {
            if (it == null) lastRejectionReason = "ribbon topology rejected"
        }
    }

    private data class Sample(val distance: Float, val x: Float, val y: Float, val normalX: Float, val normalY: Float)

    private companion object {
        const val MAX_SEED_ARC_STEP_PX = 24f
        const val MIN_INTERVALS = 8
        const val MAX_SEED_INTERVALS = 512
        const val TANGENT_EPSILON = 0.001f
    }
}

/** Debug-only counters for physical-device profiling. Disabled release builds have no work here. */
internal object ContourOpticalRenderMetrics {
    private var enabled = false
    private var geometryCacheHits = 0L
    private var geometryCacheMisses = 0L
    private var geometryRebuilds = 0L
    private var rebuiltVertexBytes = 0L
    private var meshCreations = 0L
    private var meshVertexBytes = 0L
    private var drawPasses = 0L
    private var submissionFailures = 0L

    internal fun setEnabledForDebug(enabled: Boolean) {
        if (BuildConfig.DEBUG) this.enabled = enabled
    }

    internal fun resetForDebug() {
        if (!BuildConfig.DEBUG) return
        geometryCacheHits = 0L
        geometryCacheMisses = 0L
        geometryRebuilds = 0L
        rebuiltVertexBytes = 0L
        meshCreations = 0L
        meshVertexBytes = 0L
        drawPasses = 0L
        submissionFailures = 0L
    }

    internal fun snapshotForDebug(): ContourOpticalRenderMetricsSnapshot =
        ContourOpticalRenderMetricsSnapshot(
            geometryCacheHits, geometryCacheMisses, geometryRebuilds, rebuiltVertexBytes,
            meshCreations, meshVertexBytes, drawPasses, submissionFailures
        )

    internal fun recordGeometryCacheHit() { if (BuildConfig.DEBUG && enabled) geometryCacheHits++ }
    internal fun recordGeometryCacheMiss() { if (BuildConfig.DEBUG && enabled) geometryCacheMisses++ }
    internal fun recordGeometryRebuild(vertexCount: Int) {
        if (BuildConfig.DEBUG && enabled) {
            geometryRebuilds++
            rebuiltVertexBytes += vertexCount.toLong() * ContourRibbonGeometry.COMPONENTS_PER_VERTEX * Float.SIZE_BYTES
        }
    }
    internal fun recordMeshCreation(vertexCount: Int) {
        if (BuildConfig.DEBUG && enabled) {
            meshCreations++
            meshVertexBytes += vertexCount.toLong() * ContourRibbonGeometry.COMPONENTS_PER_VERTEX * Float.SIZE_BYTES
        }
    }
    internal fun recordDrawPass() { if (BuildConfig.DEBUG && enabled) drawPasses++ }
    internal fun recordSubmissionFailure() { if (BuildConfig.DEBUG && enabled) submissionFailures++ }
}

internal data class ContourOpticalRenderMetricsSnapshot(
    val geometryCacheHits: Long,
    val geometryCacheMisses: Long,
    val geometryRebuilds: Long,
    val rebuiltVertexBytes: Long,
    val meshCreations: Long,
    val meshVertexBytes: Long,
    val drawPasses: Long,
    val submissionFailures: Long
)
