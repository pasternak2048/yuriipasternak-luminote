package com.yp.luminote.app.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HaloConfigTest {

    @Test
    fun `sanitized config replaces invalid rendering values with safe defaults`() {
        val config =
            HaloConfig(
                effectSpeed = Float.NaN,
                gradientFlowSpeed = Float.POSITIVE_INFINITY,
                intervalSeconds = Float.NEGATIVE_INFINITY,
                intensity = Float.NaN,
                thickness = Float.POSITIVE_INFINITY
            )
                .sanitized()

        assertEquals(1f, config.effectSpeed)
        assertEquals(1f, config.gradientFlowSpeed)
        assertEquals(1f, config.intervalSeconds)
        assertEquals(0.7f, config.intensity)
        assertEquals(0.5f, config.thickness)
    }

    @Test
    fun `sanitized config derives a finite duration from an invalid effect speed`() {
        floatArrayOf(
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY
        ).forEach { invalidEffectSpeed ->
            val config =
                HaloConfig(
                    effectSpeed = invalidEffectSpeed
                ).sanitized()

            assertEquals(1f, config.effectSpeed)
            assertEquals(2.5f, config.durationSeconds)
            assertTrue(config.durationSeconds.isFinite())
        }
    }
}
