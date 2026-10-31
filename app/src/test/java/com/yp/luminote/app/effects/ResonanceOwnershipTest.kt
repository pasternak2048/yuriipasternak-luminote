package com.yp.luminote.app.effects

import com.yp.luminote.app.data.settings.LuminoteSettings
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.effects.model.HaloRenderMode
import com.yp.luminote.app.overlay.coordination.*
import org.junit.Assert.*
import org.junit.Test

class ResonanceOwnershipTest {
    private fun request(name: String, time: Long = 0L) = HaloEffectRequest(name, name, HaloConfig(), null, enqueuedAt = time)

    @Test fun suspensionReplaysCurrentReminderSegmentAndMakesTeardownInert() {
        var now = 0L
        val queue = HaloEffectCoordinator { now }
        val attempts = mutableListOf<HaloEffectAttempt>()
        queue.attachAttemptRenderer(Any()) { attempts += it }
        queue.enqueue(request("reminder").copy(config = HaloConfig(renderMode = HaloRenderMode.LIGHT_IMPULSE), reminderColors = intArrayOf(1, 2, 3)))
        queue.onAttemptOutcome(attempts[0], HaloEffectAttemptOutcome.Started)
        queue.onAttemptOutcome(attempts[0], HaloEffectAttemptOutcome.Completed)
        val interrupted = attempts.last()
        queue.onAttemptOutcome(interrupted, HaloEffectAttemptOutcome.Started)
        assertEquals(1, interrupted.segment)
        queue.suspendPresentation(42)
        queue.enqueue(request("notification"))
        now = 60_000
        queue.onAttemptOutcome(interrupted, HaloEffectAttemptOutcome.Completed)
        queue.onAttemptOutcome(interrupted, HaloEffectAttemptOutcome.AbandonedAfterStart)
        assertEquals(2, attempts.size)
        assertFalse(queue.resumePresentation(41))
        assertTrue(queue.resumePresentation(42))
        val replay = attempts.last()
        assertSame(interrupted.delivery, replay.delivery)
        assertEquals(1, replay.segment)
        assertNotEquals(interrupted.lease, replay.lease)
        queue.onAttemptOutcome(replay, HaloEffectAttemptOutcome.Started)
        queue.onAttemptOutcome(replay, HaloEffectAttemptOutcome.Completed)
        val last = attempts.last()
        queue.onAttemptOutcome(last, HaloEffectAttemptOutcome.Started)
        queue.onAttemptOutcome(last, HaloEffectAttemptOutcome.Completed)
        assertEquals("notification", attempts.last().delivery.request.packageName)
    }

    @Test fun suspendedArrivalsAreBoundedCoalescedAndFifoWithoutExpiringDuringSession() {
        var now = 0L
        val queue = HaloEffectCoordinator { now }
        val attempts = mutableListOf<HaloEffectAttempt>()
        queue.attachAttemptRenderer(Any()) { attempts += it }
        queue.enqueue(request("active"))
        val old = attempts.single()
        queue.suspendPresentation(1)
        repeat(10) { index -> now += 20_000; queue.enqueue(request("app$index", now)); queue.enqueue(request("app$index", now)) }
        assertEquals(1, attempts.size)
        queue.resumePresentation(1)
        assertSame(old.delivery, attempts.last().delivery)
        repeat(9) {
            val current = attempts.last()
            queue.onAttemptOutcome(current, HaloEffectAttemptOutcome.Started)
            queue.onAttemptOutcome(current, HaloEffectAttemptOutcome.Completed)
        }
        assertEquals(listOf("active", "active") + (0..7).map { "app$it" }, attempts.map { it.delivery.request.packageName })
        assertFalse(queue.isBusy())
    }

    @Test fun queueClockPreservesAgeBeforeSuspensionAndGivesDuringArrivalsOnlyTheirActiveAge() {
        var now = 0L
        val queue = HaloEffectCoordinator { now }
        val attempts = mutableListOf<HaloEffectAttempt>()
        queue.attachAttemptRenderer(Any()) { attempts += it }
        queue.enqueue(request("active"))
        queue.enqueue(request("before"))
        now = 10_000
        queue.suspendPresentation(1)
        now = 100_000
        queue.enqueue(request("during", now))
        now = 200_000
        queue.resumePresentation(1)
        now += 6_000
        val active = attempts.last()
        queue.onAttemptOutcome(active, HaloEffectAttemptOutcome.Started)
        queue.onAttemptOutcome(active, HaloEffectAttemptOutcome.Completed)
        assertEquals("during", attempts.last().delivery.request.packageName)
    }

    @Test fun rendererRecreationAndOldGenerationReleaseCannotLoseParkedDelivery() {
        val queue = HaloEffectCoordinator { 0L }
        val first = Any()
        val attempts = mutableListOf<HaloEffectAttempt>()
        queue.attachAttemptRenderer(first) { attempts += it }
        queue.enqueue(request("active"))
        queue.suspendPresentation(1)
        queue.detachRenderer(first, terminalActiveAttempt = true)
        queue.attachAttemptRenderer(Any()) { attempts += it }
        assertEquals(1, attempts.size)
        queue.resumePresentation(1)
        queue.suspendPresentation(2)
        assertFalse(queue.resumePresentation(1))
        assertEquals(2, attempts.size)
        queue.resumePresentation(2)
        assertEquals(3, attempts.size)
        queue.clear()
        assertFalse(queue.resumePresentation(2))
        assertFalse(queue.isBusy())
    }

    private class Port : HaloEffectHostPort {
        val commands = mutableListOf<HaloEffectCommand>()
        override fun isAvailable(host: HaloEffectHost) = true
        override fun dispatch(host: HaloEffectHost, command: HaloEffectCommand): Boolean { commands += command; return true }
    }

    @Test fun resonanceSuppressesAmbientAndToolsButAlwaysAdmitsCleanup() {
        val port = Port()
        val coordinator = HaloEffectHostCoordinator(port)
        coordinator.dispatch(HaloEffectCommand.Ambient(LuminoteSettings()))
        assertTrue(coordinator.acquireResonance(1))
        val before = port.commands.size
        assertFalse(coordinator.dispatch(HaloEffectCommand.Ambient(LuminoteSettings())))
        assertFalse(coordinator.dispatch(HaloEffectCommand.Preview(LuminoteSettings())))
        assertFalse(coordinator.dispatch(HaloEffectCommand.Calibration(LuminoteSettings(), "token", 1, CalibrationOperation.START)))
        assertEquals(before, port.commands.size)
        coordinator.dispatch(HaloEffectCommand.StopAmbient)
        assertTrue(port.commands.last() is HaloEffectCommand.StopAmbient)
        assertTrue(coordinator.releaseResonance(1))
        assertTrue(coordinator.dispatch(HaloEffectCommand.Ambient(LuminoteSettings())))
    }

    @Test fun windowReplacementWithinGenerationInvalidatesOldFailureAndDetach() {
        val coordinator = HaloEffectHostCoordinator(Port())
        repeat(10) { cycle ->
            val generation = cycle + 1L
            assertTrue(coordinator.acquireResonance(generation))
            val app = coordinator.newResonancePresentation(generation, HaloEffectHost.OVERLAY)!!
            val accessibility = coordinator.newResonancePresentation(generation, HaloEffectHost.ACCESSIBILITY)!!
            assertFalse(coordinator.ownsResonancePresentation(app))
            coordinator.invalidateResonancePresentation(app)
            assertTrue(coordinator.ownsResonancePresentation(accessibility))
            val recreated = coordinator.newResonancePresentation(generation, HaloEffectHost.ACCESSIBILITY)!!
            assertFalse(coordinator.ownsResonancePresentation(accessibility))
            assertFalse(coordinator.releaseResonance(generation - 1))
            assertTrue(coordinator.ownsResonancePresentation(recreated))
            assertTrue(coordinator.releaseResonance(generation))
            assertFalse(coordinator.ownsResonancePresentation(recreated))
            assertFalse(coordinator.hasResonance())
        }
    }
}
