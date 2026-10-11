package com.yp.luminote.app.effects
import com.yp.luminote.app.effects.geometry.EdgeSegmentRenderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EdgeSegmentRendererTest {
    @Test fun `normalizes negative starts across the seam`() {
        val range = EdgeSegmentRenderer.range(-3f, 10f, 2f, allowFullContour = false)

        assertEquals(7f, range!!.start)
        assertEquals(2f, range.length)
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
