package com.yp.luminote.app.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ContourRibbonGeometryTest {
    @Test fun `closed ribbon has monotonic contour coordinates and an explicit duplicate seam`() {
        val geometry = ContourRibbonGeometry.fromCrossSections(roundRibbon(), 100f)!!

        assertEquals(5, geometry.crossSectionCount)
        assertEquals(10, geometry.vertexCount)
        assertEquals(0f, geometry.vertexData[ContourRibbonGeometry.U_OFFSET], 0f)
        val last = (geometry.vertexCount - 2) * ContourRibbonGeometry.COMPONENTS_PER_VERTEX
        assertEquals(1f, geometry.vertexData[last + ContourRibbonGeometry.U_OFFSET], 0f)
        assertEquals(geometry.vertexData[0], geometry.vertexData[last], 0f)
        assertEquals(geometry.vertexData[1], geometry.vertexData[last + 1], 0f)
        (1 until geometry.crossSectionCount).forEach { section ->
            val offset = section * ContourRibbonGeometry.VERTICES_PER_CROSS_SECTION * ContourRibbonGeometry.COMPONENTS_PER_VERTEX
            val previous = offset - ContourRibbonGeometry.VERTICES_PER_CROSS_SECTION * ContourRibbonGeometry.COMPONENTS_PER_VERTEX
            assertTrue(geometry.vertexData[offset + ContourRibbonGeometry.U_OFFSET] > geometry.vertexData[previous + ContourRibbonGeometry.U_OFFSET])
        }
    }

    @Test fun `invalid small folded and non finite contours are suppressed`() {
        assertNull(ContourRibbonGeometry.fromCrossSections(roundRibbon().take(2), 100f))
        assertNull(ContourRibbonGeometry.fromCrossSections(roundRibbon(), 0f))
        assertNull(ContourRibbonGeometry.fromCrossSections(listOf(
            ContourRibbonCrossSection(0f, -1f, 0f, 1f, 0f),
            ContourRibbonCrossSection(10f, 1f, 10f, -1f, .5f),
            ContourRibbonCrossSection(0f, -1f, 0f, 1f, 1f)
        ), 20f))
        assertNull(ContourRibbonGeometry.fromCrossSections(listOf(
            ContourRibbonCrossSection(Float.NaN, 0f, 0f, 1f, 0f),
            ContourRibbonCrossSection(1f, 0f, 1f, 1f, .5f),
            ContourRibbonCrossSection(Float.NaN, 0f, 0f, 1f, 1f)
        ), 2f))
    }

    @Test fun `geometry cache identity includes every path input and max envelope`() {
        val key = EdgePathCacheKey(7, 8f, 2f, 3f, .6f, 4f)
        val ribbonKey = ContourRibbonGeometryKey(key, 20f)

        assertNotEquals(ribbonKey, ribbonKey.copy(pathKey = key.copy(cornerShape = .7f)))
        assertNotEquals(ribbonKey, ribbonKey.copy(maxEnvelopePx = 21f))
        assertNotNull(ContourRibbonGeometry.fromCrossSections(roundRibbon(), 100f, ribbonKey))
    }

    @Test fun `GPU centerline reserves only bloom beyond the configured half stroke`() {
        assertEquals(0f, contourRibbonExtraEnvelopePx(renderStrokeWidth = 14f, maxEnvelopePx = 7f), 0f)
        assertEquals(14f, contourRibbonExtraEnvelopePx(renderStrokeWidth = 14f, maxEnvelopePx = 21f), 0f)
        assertEquals(0f, contourRibbonExtraEnvelopePx(Float.NaN, 21f), 0f)
        assertEquals(0f, contourRibbonExtraEnvelopePx(14f, Float.POSITIVE_INFINITY), 0f)
    }

    @Test fun `closed strip seam uses duplicate positions but never interpolates U backward`() {
        val geometry = ContourRibbonGeometry.fromCrossSections(roundRibbon(), 100f)!!
        val stride = ContourRibbonGeometry.VERTICES_PER_CROSS_SECTION * ContourRibbonGeometry.COMPONENTS_PER_VERTEX
        val penultimate = (geometry.crossSectionCount - 2) * stride
        val seam = (geometry.crossSectionCount - 1) * stride

        // The final TRIANGLE_STRIP quad is the sole seam connection. Its U advances into the
        // duplicate U=1 vertices; shader periodicity, not a 1→0 interpolation, closes the field.
        assertTrue(geometry.vertexData[seam + ContourRibbonGeometry.U_OFFSET] >
            geometry.vertexData[penultimate + ContourRibbonGeometry.U_OFFSET])
        assertEquals(1f, geometry.vertexData[seam + ContourRibbonGeometry.U_OFFSET], 0f)
        assertEquals(geometry.vertexData[0], geometry.vertexData[seam], 0f)
        assertEquals(geometry.vertexData[1], geometry.vertexData[seam + 1], 0f)
    }

    @Test fun `geometry rejects a ribbon above the strict vertex byte budget`() {
        val overBudget = (0..ContourRibbonGeometry.MAX_CROSS_SECTIONS).map { index ->
            val angle = index * 2.0 * Math.PI / ContourRibbonGeometry.MAX_CROSS_SECTIONS
            ContourRibbonCrossSection(
                leftX = 20f + 10f * cos(angle).toFloat(),
                leftY = 20f + 10f * sin(angle).toFloat(),
                rightX = 20f + 8f * cos(angle).toFloat(),
                rightY = 20f + 8f * sin(angle).toFloat(),
                u = index.toFloat() / ContourRibbonGeometry.MAX_CROSS_SECTIONS
            )
        }

        assertNull(ContourRibbonGeometry.fromCrossSections(overBudget, 100f))
        assertEquals(
            ContourRibbonGeometry.MAX_VERTEX_COUNT * ContourRibbonGeometry.COMPONENTS_PER_VERTEX * Float.SIZE_BYTES,
            ContourRibbonGeometry.MAX_VERTEX_BYTES
        )
    }

    @Test fun `debug metrics report bounded geometry rebuild evidence`() {
        ContourOpticalRenderMetrics.setEnabledForDebug(true)
        ContourOpticalRenderMetrics.resetForDebug()

        ContourOpticalRenderMetrics.recordGeometryCacheMiss()
        ContourOpticalRenderMetrics.recordGeometryRebuild(ContourRibbonGeometry.MAX_VERTEX_COUNT)
        ContourOpticalRenderMetrics.recordGeometryCacheHit()
        ContourOpticalRenderMetrics.recordSubmissionFailure()

        val snapshot = ContourOpticalRenderMetrics.snapshotForDebug()
        assertEquals(1L, snapshot.geometryCacheMisses)
        assertEquals(1L, snapshot.geometryCacheHits)
        assertEquals(1L, snapshot.geometryRebuilds)
        assertEquals(ContourRibbonGeometry.MAX_VERTEX_BYTES.toLong(), snapshot.rebuiltVertexBytes)
        assertEquals(1L, snapshot.submissionFailures)
        ContourOpticalRenderMetrics.setEnabledForDebug(false)
    }

    @Test fun `tessellation refines tight corners and wide feather envelopes`() {
        // A deformed corner visibly departs from its chord, so it must be subdivided even for a
        // narrow ribbon.  This is the centreline part of the adaptive safety rule.
        assertTrue(ContourRibbonTessellation.shouldRefine(
            chordDeviationPx = .6f,
            normalTurnRadians = .01f,
            envelopePx = 2f,
            depth = 0
        ))
        // A small centreline bend also needs subdivision for a broad zero-alpha feather: its
        // displaced edge otherwise moves visibly farther than the centreline itself.
        assertTrue(ContourRibbonTessellation.shouldRefine(
            chordDeviationPx = .05f,
            normalTurnRadians = .2f,
            envelopePx = 20f,
            depth = 0
        ))
        assertTrue(!ContourRibbonTessellation.shouldRefine(
            chordDeviationPx = .05f,
            normalTurnRadians = .02f,
            envelopePx = 2f,
            depth = 0
        ))
    }

    private fun roundRibbon(): List<ContourRibbonCrossSection> = (0..4).map { index ->
        val angle = index * Math.PI / 2.0
        val outer = 10f
        val inner = 8f
        ContourRibbonCrossSection(
            leftX = 20f + outer * cos(angle).toFloat(),
            leftY = 20f + outer * sin(angle).toFloat(),
            rightX = 20f + inner * cos(angle).toFloat(),
            rightY = 20f + inner * sin(angle).toFloat(),
            u = index / 4f
        )
    }
}
