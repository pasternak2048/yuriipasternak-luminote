package com.yp.luminote.app.effects

import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.data.settings.HaloFrame
import com.yp.luminote.app.data.settings.HaloMotion
import com.yp.luminote.app.data.settings.NotificationPlayback
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
        assertEquals(1, config.repeatCount)
        assertEquals(0.7f, config.intensity)
        assertEquals(0.5f, config.thickness)
        assertEquals(HaloFrame.CLASSIC, config.frame)
        assertEquals(HaloMotion.PULSE, config.motion)
        assertEquals(1f, config.effectSpeed)
        assertEquals(1f, config.gradientFlowSpeed)
        assertEquals(HaloColorMode.SOLID, config.colorMode)
        assertEquals(NotificationPlayback.ONCE, config.notificationPlayback)
    }

    @Test
    fun `invalid enum names fall back while numeric values retain sanitization`() {
        val config =
            HaloConfigCommandDecoder.decode(
                RawHaloConfigCommand(
                    intervalSeconds = Float.NEGATIVE_INFINITY,
                    repeatCount = 7,
                    intensity = Float.NaN,
                    thickness = 2f,
                    frameName = "missing-frame",
                    motionName = "missing-motion",
                    effectSpeed = 4f,
                    gradientFlowSpeed = -1f,
                    colorModeName = "missing-color-mode",
                    notificationPlaybackName = "missing-playback"
                )
            )

        assertEquals(1f, config.intervalSeconds)
        assertEquals(1, config.repeatCount)
        assertEquals(0.7f, config.intensity)
        assertEquals(1f, config.thickness)
        assertEquals(HaloFrame.CLASSIC, config.frame)
        assertEquals(HaloMotion.PULSE, config.motion)
        assertEquals(2f, config.effectSpeed)
        assertEquals(0.5f, config.gradientFlowSpeed)
        assertEquals(HaloColorMode.SOLID, config.colorMode)
        assertEquals(NotificationPlayback.ONCE, config.notificationPlayback)
    }

    @Test
    fun `valid command names and continuous repetition are preserved`() {
        val config =
            HaloConfigCommandDecoder.decode(
                RawHaloConfigCommand(
                    color = 0xFF112233.toInt(),
                    intervalSeconds = 1.5f,
                    repeatCount = -1,
                    intensity = 0.4f,
                    thickness = 0.6f,
                    frameName = HaloFrame.CLASSIC.name,
                    motionName = HaloMotion.SNAKE.name,
                    effectSpeed = 1.25f,
                    gradientFlowSpeed = 1.75f,
                    colorModeName = HaloColorMode.GRADIENT.name,
                    notificationPlaybackName = NotificationPlayback.KEEP_VISIBLE.name
                )
            )

        assertEquals(0xFF112233.toInt(), config.color)
        assertEquals(1.5f, config.intervalSeconds)
        assertEquals(-1, config.repeatCount)
        assertEquals(0.4f, config.intensity)
        assertEquals(0.6f, config.thickness)
        assertEquals(HaloFrame.CLASSIC, config.frame)
        assertEquals(HaloMotion.SNAKE, config.motion)
        assertEquals(1.25f, config.effectSpeed)
        assertEquals(1.75f, config.gradientFlowSpeed)
        assertEquals(HaloColorMode.GRADIENT, config.colorMode)
        assertEquals(NotificationPlayback.KEEP_VISIBLE, config.notificationPlayback)
    }
}
