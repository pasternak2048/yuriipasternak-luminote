package com.yp.luminote.app.effects

import com.yp.luminote.app.data.settings.*
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.overlay.coordination.*
import com.yp.luminote.app.viewmodel.SettingsWriteAcknowledgment
import org.junit.Assert.*
import org.junit.Test

class NativeResonanceModeTest {
    private fun request(name: String, now: Long = 0) = HaloEffectRequest(name, name, HaloConfig(), null, enqueuedAt = now)

    @Test fun permissionBlockedSelectionHoldsBoundedQueueWithoutAnyCaptureGeneration() {
        var now = 0L
        val queue = HaloEffectCoordinator { now }
        val attempts = mutableListOf<HaloEffectAttempt>()
        queue.attachAttemptRenderer(Any()) { attempts += it }
        queue.selectMode(HaloMode.RESONANCE)
        repeat(12) { now += 30_000; queue.enqueue(request("app$it", now)); queue.enqueue(request("app$it", now)) }
        assertTrue(attempts.isEmpty())
        queue.selectMode(HaloMode.NOTIFICATIONS)
        repeat(8) {
            val current = attempts.last()
            queue.onAttemptOutcome(current, HaloEffectAttemptOutcome.Started)
            queue.onAttemptOutcome(current, HaloEffectAttemptOutcome.Completed)
        }
        assertEquals((0..7).map { "app$it" }, attempts.map { it.delivery.request.packageName })
    }

    @Test fun switchingResonanceToAmbientDoesNotReplayNotificationsEvenAfterCaptureEnds() {
        val queue = HaloEffectCoordinator { 0L }
        val attempts = mutableListOf<HaloEffectAttempt>()
        queue.attachAttemptRenderer(Any()) { attempts += it }
        queue.enqueue(request("before"))
        val old = attempts.single()
        queue.selectMode(HaloMode.RESONANCE)
        queue.suspendPresentation(3)
        queue.enqueue(request("during"))
        queue.selectMode(HaloMode.AMBIENT)
        queue.onAttemptOutcome(old, HaloEffectAttemptOutcome.Completed)
        queue.resumePresentation(3)
        assertEquals(1, attempts.size)
        queue.selectMode(HaloMode.NOTIFICATIONS)
        assertEquals(2, attempts.size)
        assertSame(old.delivery, attempts.last().delivery)
    }

    @Test fun cleanupKeepsDesiredNotificationAdmissionClosedUntilPhysicalCleanupCompletes() {
        val queue = HaloEffectCoordinator { 0L }
        val attempts = mutableListOf<HaloEffectAttempt>()
        queue.attachAttemptRenderer(Any()) { attempts += it }
        queue.selectMode(HaloMode.RESONANCE)
        queue.enqueue(request("during"))
        queue.suspendPresentation(4)
        queue.setCleanupPending(true)
        queue.selectMode(HaloMode.NOTIFICATIONS)
        queue.resumePresentation(4)
        assertTrue(attempts.isEmpty())
        queue.setCleanupPending(false)
        assertEquals(1, attempts.size)
    }

    @Test fun ambientAndOffDoNotFreezeOrdinaryQueueAge() {
        var now = 0L
        val queue = HaloEffectCoordinator { now }
        val attempts = mutableListOf<HaloEffectAttempt>()
        queue.attachAttemptRenderer(Any()) { attempts += it }
        queue.selectMode(HaloMode.AMBIENT)
        queue.enqueue(request("old"))
        now = 20_000
        queue.selectMode(HaloMode.NOTIFICATIONS)
        assertTrue(attempts.isEmpty())
    }

    @Test fun globalOffClearsHeldQueueWithoutReopeningNotificationAdmission() {
        val queue = HaloEffectCoordinator { 0L }
        val attempts = mutableListOf<HaloEffectAttempt>()
        queue.attachAttemptRenderer(Any()) { attempts += it }
        queue.selectMode(HaloMode.RESONANCE)
        queue.enqueue(request("held"))
        queue.selectMode(HaloMode.OFF)
        queue.clear()
        assertFalse(queue.isBusy())
        queue.enqueue(request("off"))
        assertTrue(attempts.isEmpty())
        queue.clear()
        queue.selectMode(HaloMode.NOTIFICATIONS)
        assertTrue(attempts.isEmpty())
    }

    @Test fun framesAndTerminalStatusCannotRetrySameActivationButNewForegroundOrGrantCan() {
        val policy = ResonanceModeActivation()
        assertTrue(policy.takeAttempt(1, 1, 1, true, true, true))
        repeat(20) { assertFalse(policy.takeAttempt(1, 1, 1, true, true, true)) }
        assertTrue(policy.takeAttempt(1, 2, 1, true, true, true))
        assertTrue(policy.takeAttempt(1, 2, 2, true, true, true))
    }

    @Test fun cleanupAfterPauseCannotStartAndDoesNotConsumeNextLegalActivation() {
        val policy = ResonanceModeActivation()
        assertFalse(policy.takeAttempt(1, 1, 1, true, true, false))
        assertFalse(policy.takeAttempt(2, 0, 1, false, true, true))
        assertTrue(policy.takeAttempt(2, 2, 1, true, true, true))
        assertFalse(policy.takeAttempt(3, 2, 2, true, false, true))
        assertTrue(policy.takeAttempt(3, 2, 3, true, true, true))
    }

    @Test fun processRecreationCanRestoreDesiredModeAtFirstLegalForeground() {
        val recreated = ResonanceModeActivation()
        assertFalse(recreated.takeAttempt(1, 0, 1, false, true, true))
        assertTrue(recreated.takeAttempt(1, 1, 1, true, true, true))
        val settings = LuminoteSettings(haloMode = HaloMode.valueOf(HaloMode.RESONANCE.name))
        assertTrue(settings.notificationProcessingEnabled)
        assertFalse(settings.haloEnabled)
        assertFalse(settings.ambientEnabled)
    }

    @Test fun oldAmbientProducerRejectedEvenWhenResonanceHasNoCaptureGeneration() {
        val commands = mutableListOf<HaloEffectCommand>()
        val host = HaloEffectHostCoordinator(object : HaloEffectHostPort {
            override fun isAvailable(host: HaloEffectHost) = true
            override fun dispatch(host: HaloEffectHost, command: HaloEffectCommand): Boolean { commands += command; return true }
        })
        host.selectMode(HaloMode.RESONANCE)
        assertFalse(host.dispatch(HaloEffectCommand.Ambient(LuminoteSettings(haloMode = HaloMode.AMBIENT))))
        assertFalse(host.dispatch(HaloEffectCommand.Preview(LuminoteSettings())))
        host.selectMode(HaloMode.NOTIFICATIONS)
        assertFalse(host.dispatch(HaloEffectCommand.Ambient(LuminoteSettings(haloMode = HaloMode.AMBIENT))))
        assertTrue(commands.isEmpty())
    }

    @Test fun exactSuccessfulWriteAcknowledgmentRejectsFailureAndOlderCancelledCompletion() {
        val writes = SettingsWriteAcknowledgment()
        val oldA = writes.nextRevision()
        val middleB = writes.nextRevision()
        val finalA = writes.nextRevision()
        assertFalse(writes.complete(finalA, false))
        assertFalse(writes.complete(oldA, true))
        assertFalse(writes.complete(middleB, true))
        assertNotEquals(writes.revision, writes.committedRevision)
        assertTrue(writes.complete(finalA, true))
        assertFalse(writes.complete(oldA, true))
        assertEquals(finalA, writes.committedRevision)
    }

    @Test fun committedHandoffRestartsSourceAndRejectsQueuedOldPersistedB() {
        val source = SelectedModeSourceAuthority()
        val oldFeed = source.newPersistedFeed()
        source.claimPublisher(1)
        assertFalse(source.acceptPersisted(oldFeed))
        assertFalse(source.releasePublisher(1, false))
        assertTrue(source.releasePublisher(1, true))
        val newFeed = source.newPersistedFeed()
        assertFalse(source.acceptPersisted(oldFeed))
        assertTrue(source.acceptPersisted(newFeed))
    }

    @Test fun repeatedBindingsInvalidateAllOldPublisherAndPersistedLeases() {
        val source = SelectedModeSourceAuthority()
        val oldFeed = source.newPersistedFeed()
        repeat(3) { index ->
            val lease = index + 1L
            source.claimPublisher(lease)
            assertFalse(source.releasePublisher(lease, false))
            assertTrue(source.acceptPublisher(lease))
            if (lease > 1L) assertFalse(source.acceptPublisher(lease - 1))
        }
        assertFalse(source.acceptPersisted(oldFeed))
        assertFalse(source.releasePublisher(1, true))
        assertTrue(source.releasePublisher(3, true))
    }

}
