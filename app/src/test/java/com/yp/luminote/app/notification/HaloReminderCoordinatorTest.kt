package com.yp.luminote.app.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class HaloReminderCoordinatorTest {
    @Test
    fun `fresh snapshot includes every distinct unread app in stable order`() {
        val snapshot = listOf("a", "b", "a", "c", "d", "e")
        assertEquals(listOf("a", "b", "c", "d", "e"), selectReminderApps(snapshot))
    }

    @Test
    fun `current filtered snapshot is returned unchanged apart from duplicates`() {
        assertEquals(
            listOf("b", "c"),
            selectReminderApps(listOf("b", "c", "b"))
        )
    }

    @Test
    fun `settings emission cannot arm a reminder during long normal fifo playback`() {
        assertEquals(false, canArmReminderAfterSettingsEmission(
            remindersEnabled = true,
            cycleActive = false,
            playbackBusy = true
        ))
        assertEquals(true, canArmReminderAfterSettingsEmission(
            remindersEnabled = true,
            cycleActive = false,
            playbackBusy = false
        ))
    }

    @Test
    fun `failed reminder dispatch leaves the scheduler idle without immediate retry`() {
        assertEquals(false, reminderCycleActiveAfterDispatchAccepted(false))
        assertEquals(true, reminderCycleActiveAfterDispatchAccepted(true))
    }

    @Test
    fun `cancelled reminder delivery becomes idle so a later enable can arm a new deadline`() {
        assertEquals(false, reminderCycleActiveAfterDeliveryCancelled())
        assertEquals(true, canArmReminderAfterSettingsEmission(
            remindersEnabled = true,
            cycleActive = reminderCycleActiveAfterDeliveryCancelled(),
            playbackBusy = false
        ))
    }

    @Test
    fun `static calibration drop rearms one full interval when still eligible`() {
        assertEquals(true, shouldRearmAfterReminderCancellation(
            recoverWithFreshInterval = true,
            remindersEligible = true,
            playbackBusy = false
        ))
    }

    @Test
    fun `global off cancellation stays idle`() {
        assertEquals(false, shouldRearmAfterReminderCancellation(
            recoverWithFreshInterval = false,
            remindersEligible = true,
            playbackBusy = false
        ))
    }

    @Test
    fun `global off invalidates an armed deadline`() {
        val epoch = ReminderDeadlineEpoch()
        val armedDeadline = epoch.current()

        epoch.invalidate()

        assertEquals(false, epoch.isCurrent(armedDeadline))
    }

    @Test
    fun `stale recovery cannot revive after global off`() {
        val epoch = ReminderDeadlineEpoch()
        val staleRecovery = epoch.current()

        epoch.invalidate()

        assertEquals(false, epoch.isCurrent(staleRecovery))
    }

    @Test
    fun `direct rejected dispatch rearms exactly one configured full interval`() {
        val scheduler = FakeReminderTaskScheduler()
        var dispatches = 0
        val coordinator = coordinator(scheduler) {
            dispatches++
            false
        }

        coordinator.update(settings(), ::unreadApps)
        scheduler.runNext()
        assertEquals(listOf(10_000L), scheduler.delays())

        scheduler.runNext()
        assertEquals(1, dispatches)
        assertEquals(listOf(10_000L), scheduler.delays())
    }

    @Test
    fun `armed deadline cannot dispatch after global off`() {
        val scheduler = FakeReminderTaskScheduler()
        var dispatches = 0
        val coordinator = coordinator(scheduler) { dispatches++; true }
        coordinator.update(settings(), ::unreadApps)
        scheduler.runNext()

        coordinator.onReminderPlaybackCancelled(recoverWithFreshInterval = false)
        scheduler.runAll()

        assertEquals(0, dispatches)
    }

    @Test
    fun `posted recovery cannot dispatch after global off`() {
        val scheduler = FakeReminderTaskScheduler()
        var dispatches = 0
        val coordinator = coordinator(scheduler) { dispatches++; true }
        coordinator.update(settings(), ::unreadApps)
        scheduler.runNext()
        coordinator.onReminderPlaybackCancelled(recoverWithFreshInterval = true)
        coordinator.onReminderPlaybackCancelled(recoverWithFreshInterval = false)

        scheduler.runAll()

        assertEquals(0, dispatches)
    }

    private fun coordinator(
        scheduler: FakeReminderTaskScheduler,
        dispatch: (List<String>) -> Boolean
    ) = HaloReminderCoordinator(
        scheduler = scheduler,
        dispatchCycle = dispatch,
        playbackBusy = { false }
    )

    private fun settings() = com.yp.luminote.app.data.settings.LuminoteSettings(
        remindersEnabled = true,
        reminderIntervalSeconds = 10
    )

    private fun unreadApps() = listOf("com.example.chat")

    private class FakeReminderTaskScheduler : ReminderTaskScheduler {
        private data class Task(val runnable: Runnable, val delayMs: Long)
        private val tasks = mutableListOf<Task>()

        override fun post(task: Runnable) { tasks += Task(task, 0L) }
        override fun postDelayed(task: Runnable, delayMs: Long) { tasks += Task(task, delayMs) }
        override fun removeCallbacks(task: Runnable) { tasks.removeAll { it.runnable === task } }

        fun delays(): List<Long> = tasks.filter { it.delayMs > 0L }.map(Task::delayMs)
        fun runNext() { tasks.removeFirst().runnable.run() }
        /** Advances only immediate Handler work; delayed deadlines require an explicit runNext(). */
        fun runAll() {
            while (true) {
                val index = tasks.indexOfFirst { it.delayMs == 0L }
                if (index < 0) return
                tasks.removeAt(index).runnable.run()
            }
        }
    }
}
