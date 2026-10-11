package com.yp.luminote.app.overlay.coordination

/**
 * Pure ownership contract shared by queued renderer completion paths: a
 * watchdog is armed only after the renderer acknowledges start, and all
 * terminal paths consume the lease before invoking the coordinator.
 */
internal class QueuedCompletionLease {
    private var nextLease = 0L
    private var activeLease: Long? = null
    private var startedLease: Long? = null

    fun begin(): Long = (++nextLease).also { activeLease = it; startedLease = null }

    fun onStarted(lease: Long): Boolean {
        if (activeLease != lease) return false
        startedLease = lease
        return true
    }

    fun isActive(lease: Long): Boolean = activeLease == lease

    fun completeNaturally(lease: Long): Boolean = consume(lease)

    fun completeFromWatchdog(lease: Long): Boolean =
        if (startedLease == lease) consume(lease) else false

    /** Teardown is terminal even if the renderer never acknowledged start. */
    fun terminateForTeardown(lease: Long): Boolean = consume(lease)

    fun invalidate(lease: Long) {
        if (activeLease == lease) {
            activeLease = null
            startedLease = null
        }
    }

    private fun consume(lease: Long): Boolean {
        if (activeLease != lease) return false
        activeLease = null
        startedLease = null
        return true
    }
}
