package com.yp.luminote.app.notification

import com.yp.luminote.app.data.settings.HaloMode
import com.yp.luminote.app.data.settings.LuminoteSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NotificationListenerRefreshOrderTest {
    @Test fun delayedConnectionReadCannotOverwriteNewerOffSettingsOrRearmReminders() =
        assertDisabledSettingsWin(LuminoteSettings(haloMode = HaloMode.OFF, remindersEnabled = true))

    @Test fun delayedConnectionReadCannotReenableExplicitlyDisabledReminders() =
        assertDisabledSettingsWin(LuminoteSettings(remindersEnabled = false))

    private fun assertDisabledSettingsWin(disabled: LuminoteSettings) = runBlocking {
        val order = NotificationListenerRefreshOrder()
        val access = NotificationListenerSnapshotAccess()
        val connection = access.onConnected()
        val scheduler = Scheduler()
        val reminder = HaloReminderCoordinator(scheduler, dispatchCycle = { error("Disabled settings dispatched a reminder") })
        var settings = LuminoteSettings(remindersEnabled = true)
        val applied = mutableListOf<LuminoteSettings>()
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val connectedRefresh = launch(Dispatchers.Default) {
            order.run {
                val captured = settings
                val snapshot = access.read(connection) {
                    readStarted.countDown()
                    check(releaseRead.await(5, TimeUnit.SECONDS))
                    listOf("unread.app")
                }!!
                access.applyIfCurrent(snapshot) {
                    applied += captured // Same protected boundary as production filtering.
                }
                reminder.update(captured) { listOf("unread.app") }
            }
        }
        assertTrue(readStarted.await(5, TimeUnit.SECONDS))
        try {
            // Starts immediately and suspends on the production ordering guard.
            val changedSettings = launch(start = CoroutineStart.UNDISPATCHED) {
                order.run {
                    settings = disabled
                    applied += disabled
                    reminder.update(disabled) { listOf("unread.app") }
                }
            }
            assertTrue(changedSettings.isActive)
            assertTrue(applied.isEmpty())
            releaseRead.countDown()
            connectedRefresh.join()
            changedSettings.join()
            assertEquals(listOf(LuminoteSettings(remindersEnabled = true), disabled), applied)
            // Drain actual reminder coordinator posts after both producer jobs completed.
            scheduler.drainPosted()
            assertTrue(scheduler.delayed.isEmpty())
            reminder.onPlaybackCompleted(reminder = false, coordinatorDrained = true)
            scheduler.drainPosted()
            assertTrue("The final disabled settings must also block later rearming", scheduler.delayed.isEmpty())
        } finally { releaseRead.countDown() }
    }

    private class Scheduler : ReminderTaskScheduler {
        private val posted = java.util.ArrayDeque<Runnable>()
        val delayed = mutableListOf<Runnable>()
        override fun post(task: Runnable) { posted.addLast(task) }
        override fun postDelayed(task: Runnable, delayMs: Long) { delayed += task }
        override fun removeCallbacks(task: Runnable) { delayed.removeAll { it === task } }
        fun drainPosted() { while (posted.isNotEmpty()) posted.removeFirst().run() }
    }
}
