package com.yp.luminote.app.effects

import com.yp.luminote.app.data.settings.LuminoteSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HaloEffectHostCoordinatorTest {
    @Test
    fun previewFallsBackToOverlayWhenAccessibilityIsUnavailable() {
        val port = RecordingHostPort(accessibilityAvailable = false)
        HaloEffectHostCoordinator(port).dispatch(HaloEffectCommand.Preview(LuminoteSettings()))

        assertEquals(listOf(HaloEffectHost.OVERLAY to "preview"), port.deliveries)
    }

    @Test
    fun repeatedPreviewDispatchesOnlyOncePerRequestToItsSelectedHost() {
        val port = RecordingHostPort(accessibilityAvailable = true)
        val coordinator = HaloEffectHostCoordinator(port)

        coordinator.dispatch(HaloEffectCommand.Preview(LuminoteSettings()))
        coordinator.dispatch(HaloEffectCommand.Preview(LuminoteSettings()))

        assertEquals(
            listOf(
                HaloEffectHost.ACCESSIBILITY to "preview",
                HaloEffectHost.ACCESSIBILITY to "preview"
            ),
            port.deliveries
        )
    }

    @Test
    fun ambientHandoffStopsOverlayBeforeStartingAccessibilityAndLaterStopUsesOwner() {
        val port = RecordingHostPort(accessibilityAvailable = false)
        val coordinator = HaloEffectHostCoordinator(port)
        coordinator.dispatch(HaloEffectCommand.Ambient(LuminoteSettings()))

        port.accessibilityAvailable = true
        coordinator.dispatch(HaloEffectCommand.Ambient(LuminoteSettings()))
        coordinator.dispatch(HaloEffectCommand.StopAmbient)

        assertEquals(
            listOf(
                HaloEffectHost.OVERLAY to "ambient",
                HaloEffectHost.OVERLAY to "stopAmbient",
                HaloEffectHost.ACCESSIBILITY to "ambient",
                HaloEffectHost.ACCESSIBILITY to "stopAmbient"
            ),
            port.deliveries
        )
    }

    @Test
    fun calibrationAlwaysUsesOverlayAndPreservesItsLeaseFields() {
        val port = RecordingHostPort(accessibilityAvailable = true)
        val command = HaloEffectCommand.Calibration(
            settings = LuminoteSettings(),
            token = "lease",
            generation = 7L,
            operation = CalibrationOperation.UPDATE
        )

        HaloEffectHostCoordinator(port).dispatch(command)

        assertEquals(listOf(HaloEffectHost.OVERLAY to "calibration:lease:7:UPDATE"), port.deliveries)
    }

    @Test
    fun globalOffRetainsBroaderTwoHostCleanup() {
        val port = RecordingHostPort(accessibilityAvailable = true)

        assertTrue(HaloEffectHostCoordinator(port).dispatch(HaloEffectCommand.GlobalOff))
        assertEquals(
            listOf(HaloEffectHost.ACCESSIBILITY to "globalOff", HaloEffectHost.OVERLAY to "globalOff"),
            port.deliveries
        )
    }

    @Test
    fun ambientStopWithoutControllerOwnerStillReachesBothAmbientHosts() {
        val port = RecordingHostPort(accessibilityAvailable = true)

        assertTrue(HaloEffectHostCoordinator(port).dispatch(HaloEffectCommand.StopAmbient))
        assertEquals(
            listOf(HaloEffectHost.ACCESSIBILITY to "stopAmbient", HaloEffectHost.OVERLAY to "stopAmbient"),
            port.deliveries
        )
    }

    private class RecordingHostPort(
        var accessibilityAvailable: Boolean
    ) : HaloEffectHostPort {
        val deliveries = mutableListOf<Pair<HaloEffectHost, String>>()

        override fun isAvailable(host: HaloEffectHost): Boolean =
            host == HaloEffectHost.OVERLAY || accessibilityAvailable

        override fun dispatch(host: HaloEffectHost, command: HaloEffectCommand): Boolean {
            deliveries += host to when (command) {
                is HaloEffectCommand.Preview -> "preview"
                HaloEffectCommand.StopPreview -> "stopPreview"
                is HaloEffectCommand.Calibration ->
                    "calibration:${command.token}:${command.generation}:${command.operation}"
                is HaloEffectCommand.Ambient -> "ambient"
                HaloEffectCommand.StopAmbient -> "stopAmbient"
                HaloEffectCommand.GlobalOff -> "globalOff"
            }
            return host == HaloEffectHost.OVERLAY || accessibilityAvailable
        }
    }
}
