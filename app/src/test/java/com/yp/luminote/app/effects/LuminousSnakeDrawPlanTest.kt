package com.yp.luminote.app.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LuminousSnakeDrawPlanTest {
    @Test
    fun `snake keeps one physical segment at the zero one seam`() {
        val before = luminousSnakeDrawPlan(contourLength = 1_000f, phase = 0.999f)!!
        val after = luminousSnakeDrawPlan(contourLength = 1_000f, phase = 1f)!!

        assertEquals(180f, before.length, 0.0001f)
        assertEquals(999f, before.start, 0.0001f)
        assertEquals(0f, after.start, 0.0001f)
        assertEquals(before.length, after.length, 0.0001f)
    }

    @Test
    fun `snake plan rejects non renderable contours and non finite phases`() {
        assertNull(luminousSnakeDrawPlan(0f, 0f))
        assertNull(luminousSnakeDrawPlan(2f, Float.NaN))
        assertNull(luminousSnakeDrawPlan(2f, 0f))
    }

    @Test
    fun `snake phase wraps without changing length or producing a full contour`() {
        val plan = luminousSnakeDrawPlan(contourLength = 2_000f, phase = -0.25f)!!

        assertEquals(1_500f, plan.start, 0.0001f)
        assertEquals(360f, plan.length, 0.0001f)
        assertTrue(plan.length < 2_000f)
    }
}
