package com.yp.luminote.app.effects

import org.junit.Assert.assertEquals
import org.junit.Test

class LightImpulseTimelineTest {

    @Test
    fun `timeline keeps one ignition before split travel and terminal convergence`() {
        assertEquals(LightImpulsePhase.IGNITION, lightImpulsePhase(0f))
        assertEquals(LightImpulsePhase.IGNITION, lightImpulsePhase(0.119f))
        assertEquals(LightImpulsePhase.TRAVEL, lightImpulsePhase(0.12f))
        assertEquals(LightImpulsePhase.TRAVEL, lightImpulsePhase(0.779f))
        assertEquals(LightImpulsePhase.CONVERGE, lightImpulsePhase(0.78f))
        assertEquals(LightImpulsePhase.FADE, lightImpulsePhase(0.90f))
    }
}
