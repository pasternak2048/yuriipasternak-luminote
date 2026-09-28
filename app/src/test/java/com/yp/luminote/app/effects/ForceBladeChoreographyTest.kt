package com.yp.luminote.app.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForceBladeChoreographyTest {

    @Test
    fun `single blade explicitly seals and holds before forward retraction`() {
        assertEquals(BladeState.EXTENDING, BladeLifecycle.azure(0.40f).state)
        assertEquals(BladeState.SEALED, BladeLifecycle.azure(0.58f).state)
        assertEquals(BladeState.HOLDING, BladeLifecycle.azure(0.70f).state)
        assertEquals(BladeState.RETRACTING, BladeLifecycle.azure(0.90f).state)
    }

    @Test
    fun `retraction consuming head advances from origin in extension direction`() {
        val forward = BladeRetraction.remainingSegment(origin = 0.08f, direction = 1, progress = 0.25f)
        assertEquals(0.33f, forward.head, 0.0001f)
        assertEquals(0.33f, forward.start, 0.0001f)
        assertEquals(0.75f, forward.length, 0.0001f)

        val reverse = BladeRetraction.remainingSegment(origin = 0.58f, direction = -1, progress = 0.25f)
        assertEquals(0.33f, reverse.head, 0.0001f)
        assertEquals(0.58f, reverse.start, 0.0001f)
        assertEquals(0.75f, reverse.length, 0.0001f)
    }

    @Test
    fun `duel boundaries are derived from a shared ownership ratio`() {
        val initial = DuelBoundaries.fromAzureShare(0.50f)
        val pushed = DuelBoundaries.fromAzureShare(0.60f)
        assertEquals(0.60f, pushed.right - pushed.left, 0.0001f)
        assertEquals(0.60f, DuelLifecycle.azureShare(0.25f), 0.0001f)
        assertEquals(0.45f, DuelLifecycle.azureShare(0.50f), 0.0001f)
        assertEquals(0.55f, DuelLifecycle.azureShare(0.75f), 0.0001f)
        assertEquals(initial.left + initial.right, pushed.left + pushed.right, 0.0001f)
    }

    @Test
    fun `duel lifecycle reaches overload before composed retraction`() {
        assertEquals(DuelState.CLASHING, DuelLifecycle.at(0.34f).state)
        assertEquals(DuelState.STRUGGLING, DuelLifecycle.at(0.55f).state)
        assertEquals(DuelState.OVERLOADING, DuelLifecycle.at(0.80f).state)
        assertEquals(DuelState.RETRACTING, DuelLifecycle.at(0.93f).state)
        assertTrue(DuelLifecycle.at(0.80f).energy > 1f)
    }

    @Test
    fun `duel retraction keeps each live interval between its consuming head and origin`() {
        val out = MutableRetractionSegment()
        DuelRetraction.remainingInterval(0.08f, 0.33f, 0.50f, -1, out)
        assertEquals(0.08f, out.start, 0.0001f)
        assertEquals(0.205f, out.head, 0.0001f)
        assertEquals(0.125f, out.length, 0.0001f)

        DuelRetraction.remainingInterval(0.08f, -0.17f, 0.50f, 1, out)
        assertEquals(-0.045f, out.start, 0.0001f)
        assertEquals(-0.045f, out.head, 0.0001f)
        assertEquals(0.125f, out.length, 0.0001f)

        DuelRetraction.remainingInterval(0.08f, 0.33f, 1f, -1, out)
        assertEquals(0.08f, out.head, 0.0001f)
    }

    @Test
    fun `clash jitter is deterministic and independent per boundary`() {
        assertEquals(BladeClash.jitter(0.42f, 0, 1f), BladeClash.jitter(0.42f, 0, 1f), 0f)
        assertTrue(BladeClash.jitter(0.42f, 0, 1f) != BladeClash.jitter(0.42f, 1, 1f))
    }
}
