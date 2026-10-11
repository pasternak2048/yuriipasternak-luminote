package com.yp.luminote.app.notification

import android.os.Handler
import android.os.Looper
import com.yp.luminote.app.data.settings.LuminoteSettings

internal interface ReminderTaskScheduler {
    fun post(task: Runnable)
    fun postDelayed(task: Runnable, delayMs: Long)
    fun removeCallbacks(task: Runnable)
}

internal class AndroidReminderTaskScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper())
) : ReminderTaskScheduler {
    override fun post(task: Runnable) {
        handler.post(task)
    }

    override fun postDelayed(task: Runnable, delayMs: Long) {
        handler.postDelayed(task, delayMs)
    }
    override fun removeCallbacks(task: Runnable) = handler.removeCallbacks(task)
}

/**
 * Main-thread reminder scheduler. A countdown is armed only after a terminal
 * playback callback; normal notification admission always cancels it.
 */
internal class HaloReminderCoordinator(
    private val scheduler: ReminderTaskScheduler = AndroidReminderTaskScheduler(),
    /** True only when the renderer route accepted the reminder for delivery. */
    private val dispatchCycle: (List<String>) -> Boolean,
    /** Process-wide queue state, read on the same main thread as reminder scheduling. */
    private val playbackBusy: () -> Boolean = { false }
) {
    private var settings: LuminoteSettings? = null
    private var snapshot: (() -> List<String>)? = null
    private var cycleActive = false
    private val deadlineEpoch = ReminderDeadlineEpoch()
    private var armedDue: Runnable? = null

    fun update(settings: LuminoteSettings, snapshot: () -> List<String>) {
        scheduler.post {
            this.settings = settings
            this.snapshot = snapshot
            if (!canRemind()) cancelInternal() else if (
                canArmReminderAfterSettingsEmission(
                    remindersEnabled = canRemind(),
                    cycleActive = cycleActive,
                    playbackBusy = playbackBusy()
                )
            ) armAfterTerminalPlayback()
        }
    }

    fun onNormalEnqueued() {
        scheduler.post(::cancelInternal)
    }

    fun onPlaybackCompleted(reminder: Boolean, coordinatorDrained: Boolean) {
        scheduler.post {
            if (reminder) cycleActive = false
            if (coordinatorDrained && !playbackBusy()) armAfterTerminalPlayback()
        }
    }

    /**
     * A cancelled delivery may recover only by scheduling a fresh full interval.
     * Global Off and other terminal cancellation paths pass false.
     */
    fun onReminderPlaybackCancelled(recoverWithFreshInterval: Boolean) {
        val recoveryEpoch = deadlineEpoch.current()
        scheduler.post {
            if (!recoverWithFreshInterval) {
                cycleActive = reminderCycleActiveAfterDeliveryCancelled()
                cancelInternal(invalidateDeadline = true)
                return@post
            }
            if (!deadlineEpoch.isCurrent(recoveryEpoch)) return@post
            cycleActive = reminderCycleActiveAfterDeliveryCancelled()
            if (
                shouldRearmAfterReminderCancellation(
                    recoverWithFreshInterval = recoverWithFreshInterval,
                    remindersEligible = canRemind(),
                    playbackBusy = playbackBusy()
                )
            ) {
                armAfterTerminalPlayback()
            }
        }
    }

    fun onNoRelevantNotifications() {
        scheduler.post(::cancelInternal)
    }

    fun cancel() {
        scheduler.post(::cancelInternal)
    }

    private fun cancelInternal(invalidateDeadline: Boolean = false) {
        armedDue?.let(scheduler::removeCallbacks)
        armedDue = null
        if (invalidateDeadline) deadlineEpoch.invalidate()
    }

    private fun armAfterTerminalPlayback() {
        cancelInternal()
        val current = settings ?: return
        if (!canRemind() || playbackBusy() || snapshot?.invoke().isNullOrEmpty()) return
        val epoch = deadlineEpoch.current()
        lateinit var due: Runnable
        due = Runnable {
            if (armedDue !== due || !deadlineEpoch.isCurrent(epoch)) return@Runnable
            armedDue = null
            val freshSettings = settings ?: return@Runnable
            if (playbackBusy()) return@Runnable
            val apps = selectReminderApps(snapshot?.invoke().orEmpty())
            if (!freshSettings.remindersEnabled || apps.isEmpty()) return@Runnable
            cycleActive = reminderCycleActiveAfterDispatchAccepted(dispatchCycle(apps))
            if (!cycleActive && deadlineEpoch.isCurrent(epoch)) {
                armAfterTerminalPlayback()
            }
        }
        armedDue = due
        scheduler.postDelayed(due, current.reminderIntervalSeconds * MILLIS_PER_SECOND)
    }

    private fun canRemind(): Boolean = settings?.remindersEnabled == true &&
        settings?.haloEnabled == true && settings?.ambientEnabled == false

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L
    }
}

/** A reminder cycle represents every currently relevant unread application once. */
internal fun selectReminderApps(packages: List<String>): List<String> =
    packages.distinct()

/** Settings emissions may only arm reminders when the transient FIFO is terminal. */
internal fun canArmReminderAfterSettingsEmission(
    remindersEnabled: Boolean,
    cycleActive: Boolean,
    playbackBusy: Boolean
): Boolean = remindersEnabled && !cycleActive && !playbackBusy

/** A delivery rejection is terminal for the current deadline and does not create a busy retry loop. */
internal fun reminderCycleActiveAfterDispatchAccepted(accepted: Boolean): Boolean = accepted

/** Cancellation and dropped delivery use the same idle transition as a rejected dispatch. */
internal fun reminderCycleActiveAfterDeliveryCancelled(): Boolean = false

/** Recovery is delayed by the configured interval, preventing calibration-drop busy loops. */
internal fun shouldRearmAfterReminderCancellation(
    recoverWithFreshInterval: Boolean,
    remindersEligible: Boolean,
    playbackBusy: Boolean
): Boolean = recoverWithFreshInterval && remindersEligible && !playbackBusy

/** Monotonic token used to reject scheduled deadlines and recovery callbacks after Global Off. */
internal class ReminderDeadlineEpoch {
    private var value = 0L
    fun current(): Long = value
    fun invalidate() { value++ }
    fun isCurrent(epoch: Long): Boolean = epoch == value
}

/** Process-local bridge from the renderer completion handshake to the listener scheduler. */
internal object HaloReminderRuntime {
    @Volatile private var coordinator: HaloReminderCoordinator? = null
    fun attach(value: HaloReminderCoordinator) { coordinator = value }
    fun detach(value: HaloReminderCoordinator) { if (coordinator === value) coordinator = null }
    fun onNormalEnqueued() = coordinator?.onNormalEnqueued()
    fun onPlaybackCompleted(reminder: Boolean, coordinatorDrained: Boolean) =
        coordinator?.onPlaybackCompleted(reminder, coordinatorDrained)
    fun onReminderPlaybackCancelled(recoverWithFreshInterval: Boolean) =
        coordinator?.onReminderPlaybackCancelled(recoverWithFreshInterval)
}
