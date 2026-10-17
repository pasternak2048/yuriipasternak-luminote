package com.yp.luminote.app.effects
import com.yp.luminote.app.effects.geometry.EdgeSegmentRenderer
import com.yp.luminote.app.rendering.canvas.FLOWING_SNAKE_SAMPLES
import com.yp.luminote.app.rendering.canvas.flowingSnakeEmission
import com.yp.luminote.app.rendering.canvas.flowingSnakeWidthMultiplier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeSegmentRendererTest {
    @Test
    fun `flowing Snake profile has fixed contiguous samples and a smooth tail to head ramp`() {
        assertEquals(32, FLOWING_SNAKE_SAMPLES)
        var previousEnd = 0f
        var previousEmission = -1f
        for (sample in 0 until FLOWING_SNAKE_SAMPLES) {
            val start = sample.toFloat() / FLOWING_SNAKE_SAMPLES
            val end = (sample + 1f) / FLOWING_SNAKE_SAMPLES
            assertEquals(previousEnd, start, 0f)
            val center = (start + end) / 2f
            val emission = flowingSnakeEmission(center)
            assertTrue(emission >= previousEmission)
            assertTrue(flowingSnakeWidthMultiplier(center) > 0f)
            previousEnd = end
            previousEmission = emission
        }
        assertEquals(1f, previousEnd, 0f)
        assertTrue(flowingSnakeEmission(0.01f) < flowingSnakeEmission(0.99f))
    }
    @Test fun `normalizes negative starts across the seam`() {
        val range = EdgeSegmentRenderer.range(-3f, 10f, 2f, allowFullContour = false)

        assertEquals(7f, range!!.start)
        assertEquals(2f, range.length)
    }

    @Test fun `wrapped ranges preserve their length while crossing the top-left seam`() {
        val range = EdgeSegmentRenderer.range(9f, 10f, 2f, allowFullContour = true)

        assertEquals(9f, range!!.start)
        assertEquals(2f, range.length)
        assertEquals(true, range.start + range.length > 10f)
    }

    @Test fun `conventional policy rejects invalid subpixel and near full segments`() {
        assertNull(EdgeSegmentRenderer.range(0f, 10f, 0.49f, allowFullContour = false))
        assertNull(EdgeSegmentRenderer.range(0f, 10f, 9.5f, allowFullContour = false))
        assertNull(EdgeSegmentRenderer.range(Float.NaN, 10f, 1f, allowFullContour = false))
    }

    @Test fun `blade policy permits a complete loop`() {
        val range = EdgeSegmentRenderer.range(2f, 10f, 10f, allowFullContour = true)

        assertEquals(2f, range!!.start)
        assertEquals(10f, range.length)
    }
}
