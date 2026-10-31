package com.yp.luminote.app.resonance

import org.junit.Assert.*
import org.junit.Test

class ResonanceBehaviorTest {
    @Test fun missingFramesFadeAndStaleGenerationCannotExciteBehavior() {
        val behavior = ResonanceBehavior()
        val feature = AudioFeatureSnapshot(5, 1_000_000_000, 1f, .5f, .5f, 1f, 1f, .5f)
        repeat(10) { behavior.update(feature, 5, 1_000_000_000 + it * 16_666_667L) }
        assertTrue(behavior.low > .5f)
        val phase = behavior.phase
        repeat(300) { behavior.update(feature, 5, 2_000_000_000 + it * 16_666_667L) }
        assertTrue(behavior.low < .001f && behavior.loudness < .001f)
        assertTrue(behavior.phase != phase)
        val stale = ResonanceBehavior()
        repeat(30) { stale.update(feature, 6, 1_000_000_000 + it * 16_666_667L) }
        assertEquals(0f, stale.low, 0f)
        assertEquals(0f, stale.impulse, 0f)
    }
}
