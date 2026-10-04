package com.yp.luminote.app.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LightImpulseTimelineTest {

    @Test
    fun `timeline keeps one ignition before split travel and terminal convergence`() {
        assertEquals(LightImpulsePhase.IGNITION, lightImpulsePhase(0f))
        assertEquals(LightImpulsePhase.IGNITION, lightImpulsePhase(0.089f))
        assertEquals(LightImpulsePhase.TRAVEL, lightImpulsePhase(0.09f))
        assertEquals(LightImpulsePhase.TRAVEL, lightImpulsePhase(0.799f))
        assertEquals(LightImpulsePhase.CONVERGE, lightImpulsePhase(0.80f))
        assertEquals(LightImpulsePhase.FADE, lightImpulsePhase(0.88f))
    }

    @Test
    fun `travel starts with the fully charged single source still visible`() {
        assertEquals(LightImpulsePhase.TRAVEL, lightImpulsePhase(0.09f))
        assertEquals(0f, lightImpulseTravelProgress(0.09f), 0.0001f)
        assertEquals(1f, lightImpulseOriginGlow(0.09f), 0.0001f)
        assertTrue(lightImpulseTravelProgress(0.20f) > 0f)
        assertTrue(lightImpulseTravelProgress(0.20f) < lightImpulseTravelProgress(0.40f))
    }

    @Test
    fun `calibrated display outline supplies the renderer source path`() {
        val outline = DisplayOutline(density = 2f).apply { resize(1_080, 2_400) }
        val path = outline.centerlinePath(
            strokeWidth = 8f,
            edgeCalibrationPx = outline.opticalInsetPx + outline.dpToPx(3f),
            cornerCalibrationPx = outline.dpToPx(4f)
        )
        assertTrue(!path.isEmpty)
    }

    @Test
    fun `endpoint distance semantics keep distinct fractions on symmetric routes`() {
        // Android's native PathMeasure is unavailable to this JVM suite. This covers only the
        // pure distance contract; on-device/instrumented validation covers sampled geometry.
        val endpoints = lightImpulseEndpoints(
            topFraction = 0.25f,
            bottomFraction = 0.75f,
            length = 10_000f
        )

        assertTrue(endpoints.topFraction != endpoints.bottomFraction)
        assertEquals(endpoints.forwardDistance, endpoints.reverseDistance, 4f)
        assertEquals(0.75f, endpoints.bottomFraction, 0f)
    }

    @Test
    fun `bottom bloom is continuous from late travel into convergence`() {
        assertEquals(0.45f, lightImpulseBottomBloom(0.80f), 0.0001f)
        assertEquals(0.018f, lightImpulseBottomBloomRadiusFraction(0.80f), 0.0001f)
        assertTrue(lightImpulseBottomBloom(0.799f) > 0.44f)
        assertTrue(lightImpulseBottomBloomRadiusFraction(0.799f) > 0.017f)
    }

    @Test
    fun `reminder impulse reserves a two point two second wall clock cycle`() {
        assertEquals(LIGHT_IMPULSE_DURATION_SECONDS, finiteDurationFor(HaloRenderMode.LIGHT_IMPULSE, 1f), 0f)
        assertEquals(0.6f, finiteDurationFor(HaloRenderMode.NORMAL, 0.6f), 0f)
        assertEquals(198L, lightImpulseIgnitionDurationMs())
    }
}
