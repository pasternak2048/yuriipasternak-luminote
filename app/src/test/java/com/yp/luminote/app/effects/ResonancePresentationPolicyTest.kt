package com.yp.luminote.app.effects

import com.yp.luminote.app.overlay.service.canPresentResonance
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResonancePresentationPolicyTest {
    @Test fun liveResonancePreemptsEveryOrdinaryOwner() {
        for (owner in 0..3) {
            assertTrue(canPresentResonance(true, owner == 0, owner == 1, owner == 2, owner == 3))
        }
    }

    @Test fun drainedHostResumesOnlyLiveSession() {
        assertTrue(canPresentResonance(true, false, false, false, false))
        assertFalse(canPresentResonance(false, false, false, false, false))
    }
}
