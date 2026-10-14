package com.yp.luminote.app.effects.geometry

import android.graphics.Path
import android.graphics.PathMeasure
import android.content.Intent
import android.view.WindowInsets
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yp.luminote.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            instrumentation.waitForIdleSync()
            val rootView = activity.window.decorView
            val insets = requireNotNull(rootView.rootWindowInsets)
            val displayShapePath = requireNotNull(insets.displayShape).path
            assertFalse("real root WindowInsets must provide a display-shape path", displayShapePath.isEmpty)

            val outline = DisplayOutline(density = rootView.resources.displayMetrics.density).apply {
                resize(rootView.width, rootView.height)
            }
            val provider = EdgePathGeometryProvider(outline)
            val beforeInsets = provider.pathFor(8f, outline.opticalInsetPx, 0f, 0.5f, 0f)

            outline.updateInsets(insets)

            assertNotSame(beforeInsets, provider.pathFor(8f, outline.opticalInsetPx, 0f, 0.5f, 0f))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
