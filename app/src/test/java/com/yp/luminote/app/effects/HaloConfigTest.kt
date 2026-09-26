package com.yp.luminote.app.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
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

    @Test
    fun `sanitized config clamps finite values to the established rendering bounds`() {
        val config =
            HaloConfig(
                effectSpeed = 9f,
                gradientFlowSpeed = 9f,
                intervalSeconds = -2f,
                repeatCount = 9,
                intensity = -1f,
                thickness = 2f
            )
                .sanitized()

        assertEquals(2f, config.effectSpeed)
        assertEquals(2.5f, config.gradientFlowSpeed)
        assertEquals(0f, config.intervalSeconds)
        assertEquals(1, config.repeatCount)
        assertEquals(0f, config.intensity)
        assertEquals(1f, config.thickness)
    }

    @Test
    fun `default gradient palette returns an independent copy`() {
        val firstPalette = HaloConfig.defaultGradientPalette()
        val expectedPalette = firstPalette.copyOf()

        firstPalette[0] = 0

        assertArrayEquals(
            expectedPalette,
            HaloConfig.defaultGradientPalette()
        )
    }
}
