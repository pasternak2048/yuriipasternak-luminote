package com.yp.luminote.app.effects.geometry

import android.graphics.Path
import android.graphics.PathMeasure
import android.view.WindowInsets
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Android-native contracts that JVM tests cannot observe because PathMeasure is framework code. */
@RunWith(AndroidJUnit4::class)
class EdgePathGeometryInstrumentedTest {
    @Test
    fun `calibrated contour starts at the top-left edge and traverses clockwise`() {
        val outline = DisplayOutline(density = 1f).apply { resize(100, 200) }
        val contour = outline.centerlinePath(
            strokeWidth = 0f,
            edgeCalibrationPx = outline.opticalInsetPx,
            cornerCalibrationPx = 1f,
            cornerShape = 0.5f
        )
        val measure = PathMeasure(contour, false)
        val position = FloatArray(2)
        val tangent = FloatArray(2)

        assertTrue(measure.getPosTan(0f, position, tangent))
        assertEquals(outline.opticalInsetPx, position[1], 0.001f)
        assertTrue("contour must begin on the left half of the top edge", position[0] < 50f)
        assertTrue("clockwise traversal leaves the top-left edge toward the right", tangent[0] > 0f)
        assertEquals(0f, tangent[1], 0.001f)
    }

    @Test
    fun `append splits a wrapped segment at the seam without losing either side`() {
        val contour = Path().apply {
            moveTo(0f, 0f)
            lineTo(10f, 0f)
            lineTo(10f, 10f)
            lineTo(0f, 10f)
            close()
        }
        val out = Path()

        assertTrue(
            EdgeSegmentRenderer.append(
                measure = PathMeasure(contour, false),
                contourLength = 40f,
                start = 35f,
                segmentLength = 10f,
                out = out,
                allowFullContour = true
            )
        )

        val first = PathMeasure(out, false)
        val position = FloatArray(2)
        assertTrue(first.getPosTan(0f, position, null))
        assertEquals(0f, position[0], 0.001f)
        assertEquals(5f, position[1], 0.001f)
        assertTrue(first.nextContour())
        assertTrue(first.getPosTan(0f, position, null))
        assertEquals(0f, position[0], 0.001f)
        assertEquals(0f, position[1], 0.001f)
        assertEquals(5f, first.length, 0.001f)
    }

    @Test
    fun `updateInsets invalidates the provider cache through an outline rebuild`() {
        val outline = DisplayOutline(density = 1f).apply { resize(100, 200) }
        val provider = EdgePathGeometryProvider(outline)
        val beforeInsets = provider.pathFor(8f, outline.opticalInsetPx, 0f, 0.5f, 0f)

        outline.updateInsets(WindowInsets.Builder().build())

        assertNotSame(beforeInsets, provider.pathFor(8f, outline.opticalInsetPx, 0f, 0.5f, 0f))
    }
}
