package com.yp.luminote.app.effects

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationAmbientOwnershipTest {
    @Test
    fun terminalStopConsumesFallbackBeforeAccessibilityCanTakeItOver() {
        val ownership = ApplicationAmbientOwnership()
        val lease = ownership.beginFallback()

        ownership.supersedeOverlay() // Terminal Ambient stop.

        assertNull(ownership.pendingLease())
        assertFalse(ownership.isPending(lease))
    }

    @Test
    fun staleTakeoverCleanupCannotStopNewerCalibrationOverlay() {
        val ownership = ApplicationAmbientOwnership()
        val fallbackLease = ownership.beginFallback()
        ownership.markOverlayActive(fallbackLease)

        ownership.supersedeOverlay() // Calibration supersedes the fallback host.

        assertFalse(ownership.canStopOverlay(fallbackLease))
    }
}
