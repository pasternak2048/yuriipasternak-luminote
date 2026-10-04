package com.yp.luminote.app.effects

import com.yp.luminote.app.data.settings.HaloMotion
import org.junit.Assert.assertEquals
import org.junit.Test

class ImpulseAnimationStrategyTest {

    @Test
    fun `impulse delegates fades to its renderer timeline`() {
        val envelope = HaloAnimationStrategies.forMotion(HaloMotion.IMPULSE).envelope(2_800L)

        assertEquals(0L, envelope.fadeInMs)
        assertEquals(0L, envelope.fadeOutMs)
    }
}
