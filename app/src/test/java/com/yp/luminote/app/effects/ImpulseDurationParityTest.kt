package com.yp.luminote.app.effects
import com.yp.luminote.app.effects.model.HaloRenderMode
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.overlay.host.finiteDurationFor
import com.yp.luminote.app.animation.LIGHT_IMPULSE_DURATION_SECONDS

import com.yp.luminote.app.data.settings.HaloMotion
import org.junit.Assert.assertEquals
import org.junit.Test

class ImpulseDurationParityTest {

    @Test
    fun `normal impulse shares the reminder base choreography duration`() {
        val impulse = HaloConfig(motion = HaloMotion.IMPULSE).sanitized()

        assertEquals(LIGHT_IMPULSE_DURATION_SECONDS, impulse.durationSeconds, 0f)
        assertEquals(
            LIGHT_IMPULSE_DURATION_SECONDS,
            finiteDurationFor(HaloRenderMode.NORMAL, impulse.durationSeconds),
            0f
        )
        assertEquals(
            LIGHT_IMPULSE_DURATION_SECONDS,
            finiteDurationFor(HaloRenderMode.LIGHT_IMPULSE, impulse.durationSeconds),
            0f
        )
    }

    @Test
    fun `impulse finite route preserves the duration resolved from each effect speed`() {
        listOf(
            0.25f to 8.8f,
            1f to LIGHT_IMPULSE_DURATION_SECONDS,
            2f to 1.1f
        ).forEach { (speed, expectedDuration) ->
            val impulse = HaloConfig(
                motion = HaloMotion.IMPULSE,
                effectSpeed = speed
            ).sanitized()

            assertEquals(expectedDuration, impulse.durationSeconds, 0f)
            assertEquals(
                expectedDuration,
                finiteDurationFor(HaloRenderMode.NORMAL, impulse.durationSeconds),
                0f
            )
        }
    }

    @Test
    fun `non impulse normal motions retain their own resolved finite durations`() {
        val pulse = HaloConfig(motion = HaloMotion.PULSE).sanitized()

        assertEquals(2.5f, pulse.durationSeconds, 0f)
        assertEquals(2.5f, finiteDurationFor(HaloRenderMode.NORMAL, pulse.durationSeconds), 0f)
    }
}
