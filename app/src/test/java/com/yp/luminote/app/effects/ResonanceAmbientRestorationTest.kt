package com.yp.luminote.app.effects

import com.yp.luminote.app.overlay.service.shouldRestoreAmbientAfterResonance
import org.junit.Assert.*
import org.junit.Test

class ResonanceAmbientRestorationTest {
    @Test fun currentRepositoryPreferenceRestoresWithoutPriorUiCommand() {
        assertTrue(shouldRestoreAmbientAfterResonance(true, false, 0, 0, 3, 0))
        assertFalse(shouldRestoreAmbientAfterResonance(false, false, 0, 0, 3, 0))
    }
    @Test fun globalOffNewSessionAndNewSettingsInvalidateDelayedRestoration() {
        assertFalse(shouldRestoreAmbientAfterResonance(true, false, 2, 2, 3, 3))
        assertFalse(shouldRestoreAmbientAfterResonance(true, true, 2, 2, 3, 0))
        assertFalse(shouldRestoreAmbientAfterResonance(true, false, 2, 3, 3, 0))
    }
}
