package com.yp.luminote.app.effects
import com.yp.luminote.app.effects.model.HaloRenderMode
import com.yp.luminote.app.overlay.host.finiteDurationFor
import com.yp.luminote.app.animation.LIGHT_IMPULSE_DURATION_SECONDS
import com.yp.luminote.app.animation.definitions.HaloEffectSpec

import org.junit.Assert.assertEquals
import org.junit.Test

class FiniteEffectDurationTest {

    @Test
    fun `light impulse uses its fixed duration even at the fastest configured speed`() {
        assertEquals(
            LIGHT_IMPULSE_DURATION_SECONDS,
            finiteDurationFor(HaloRenderMode.LIGHT_IMPULSE, 0.25f),
            0f
        )
    }

    @Test
    fun `normal mode retains the requested finite duration`() {
        assertEquals(
            0.25f,
            finiteDurationFor(HaloRenderMode.NORMAL, 0.25f),
            0f
        )
    }

    @Test
    fun `supernova effect plan uses its definition duration for reminder lifecycle`() {
        assertEquals(
            0.6f,
            finiteDurationFor(
                HaloRenderMode.LIGHT_IMPULSE,
                HaloEffectSpec.Supernova,
                0.6f
            ),
            0f
        )
    }
}
