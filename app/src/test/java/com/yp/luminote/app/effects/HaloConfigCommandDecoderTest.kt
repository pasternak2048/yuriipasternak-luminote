package com.yp.luminote.app.effects
import com.yp.luminote.app.effects.model.HaloRenderMode
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.overlay.coordination.HaloConfigCommandDecoder
import com.yp.luminote.app.overlay.coordination.RawHaloConfigCommand

import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.data.settings.HaloFrame
import com.yp.luminote.app.animation.definitions.HaloAnimationId
import org.junit.Assert.assertEquals
import org.junit.Test

class HaloConfigCommandDecoderTest {

    @Test
    fun `absent command values preserve sanitized HaloConfig defaults`() {
        val config =
            HaloConfigCommandDecoder.decode(
                RawHaloConfigCommand()
            )

        assertEquals(0xFF3E91FF.toInt(), config.color)
        assertEquals(2.5f, config.durationSeconds)
        assertEquals(1f, config.intervalSeconds)
        assertEquals(0.7f, config.intensity)
        assertEquals(0.5f, config.thickness)
        assertEquals(HaloFrame.CLASSIC, config.frame)
        assertEquals(HaloAnimationId("PULSE"), config.motion)
        assertEquals(1f, config.effectSpeed)
        assertEquals(1f, config.gradientFlowSpeed)
        assertEquals(HaloColorMode.SOLID, config.colorMode)
        assertEquals(HaloRenderMode.NORMAL, config.renderMode)
    }

    @Test
    fun `invalid enum names fall back while numeric values retain sanitization`() {
        val config =
            HaloConfigCommandDecoder.decode(
                RawHaloConfigCommand(
                    intervalSeconds = Float.NEGATIVE_INFINITY,
                    intensity = Float.NaN,
                    thickness = 2f,
                    frameName = "missing-frame",
                    motionName = "missing-motion",
                    effectSpeed = 4f,
                    gradientFlowSpeed = -1f,
                    colorModeName = "missing-color-mode",
                    legacyNotificationPlaybackName = "missing-playback"
                )
            )

        assertEquals(1f, config.intervalSeconds)
        assertEquals(0.7f, config.intensity)
        assertEquals(1f, config.thickness)
        assertEquals(HaloFrame.CLASSIC, config.frame)
        assertEquals(HaloAnimationId("IMPULSE"), config.motion)
        assertEquals(2f, config.effectSpeed)
        assertEquals(0.5f, config.gradientFlowSpeed)
        assertEquals(HaloColorMode.SOLID, config.colorMode)
        assertEquals(HaloRenderMode.NORMAL, config.renderMode)
    }

    @Test
    fun `legacy repetition payload is migrated to one finite playback`() {
        val config =
            HaloConfigCommandDecoder.decode(
                RawHaloConfigCommand(
                    color = 0xFF112233.toInt(),
                    intervalSeconds = 1.5f,
                    intensity = 0.4f,
                    thickness = 0.6f,
                    frameName = HaloFrame.CLASSIC.name,
                    motionName = "SNAKE",
                    effectSpeed = 1.25f,
                    gradientFlowSpeed = 1.75f,
                    colorModeName = HaloColorMode.GRADIENT.name,
                    legacyNotificationPlaybackName = "KEEP_VISIBLE"
                )
            )

        assertEquals(0xFF112233.toInt(), config.color)
        assertEquals(1.5f, config.intervalSeconds)
        assertEquals(0.4f, config.intensity)
        assertEquals(0.6f, config.thickness)
        assertEquals(HaloFrame.CLASSIC, config.frame)
        assertEquals(HaloAnimationId("SNAKE"), config.motion)
        assertEquals(1.25f, config.effectSpeed)
        assertEquals(1.75f, config.gradientFlowSpeed)
        assertEquals(HaloColorMode.GRADIENT, config.colorMode)
        assertEquals(HaloRenderMode.NORMAL, config.renderMode)
    }

    @Test
    fun `legacy ripple edge command selects impulse`() {
        val config =
            HaloConfigCommandDecoder.decode(
                RawHaloConfigCommand(motionName = "RIPPLE_EDGE")
            )

        assertEquals(HaloAnimationId("IMPULSE"), config.motion)
    }
}
