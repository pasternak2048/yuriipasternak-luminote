package com.yp.luminote.app.rendering.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupernovaRendererTest {
    @Test
    fun `intensity follows copied ignition ramp then independently fades to clear`() {
        assertEquals(0f, supernovaIntensity(0f), 0.0001f)
        assertEquals(1f, supernovaIntensity(0.09f), 0.0001f)
        assertTrue(supernovaIntensity(0.40f) in 0f..1f)
        assertEquals(0f, supernovaIntensity(1f), 0.0001f)
    }

    @Test
    fun `terminal and non finite progress are fully clear`() {
        assertEquals(0f, supernovaIntensity(Float.NaN), 0.0001f)
        assertEquals(0f, supernovaIntensity(Float.POSITIVE_INFINITY), 0.0001f)
    }

    @Test
    fun `each finite playback cycle repeats the same supernova progression`() {
        assertEquals(0f, supernovaCycleProgress(1f)!!, 0.0001f)
        assertEquals(0.09f, supernovaCycleProgress(1.09f)!!, 0.0001f)
        assertEquals(0.40f, supernovaCycleProgress(1.40f)!!, 0.0001f)
        assertEquals(
            supernovaIntensity(0.40f),
            supernovaIntensity(supernovaCycleProgress(1.40f)!!),
            0.0001f
        )
    }
}
