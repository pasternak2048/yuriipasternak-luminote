package com.yp.luminote.app.effects

/**
 * Atomic lease state for the application-overlay Ambient fallback.
 *
 * The Intent is intentionally kept by the host; this state only answers
 * whether that Intent still owns a fallback session and may be cleaned up.
 */
internal class ApplicationAmbientOwnership {
    private var nextLease = 0L
    private var pendingLease: Long? = null
    private var activeOverlayLease: Long? = null

    @Synchronized
    fun beginFallback(): Long = (++nextLease).also { pendingLease = it }

    @Synchronized
    fun pendingLease(): Long? = pendingLease

    @Synchronized
    fun isPending(lease: Long): Boolean = pendingLease == lease

    @Synchronized
    fun markOverlayActive(lease: Long) {
        if (pendingLease == lease) activeOverlayLease = lease
    }

    @Synchronized
    fun consumePending(lease: Long? = null) {
        if (lease == null || pendingLease == lease) pendingLease = null
    }

    @Synchronized
    fun supersedeOverlay() {
        pendingLease = null
        activeOverlayLease = null
    }

    @Synchronized
    fun canStopOverlay(lease: Long): Boolean = activeOverlayLease == lease

    @Synchronized
    fun consumeActive(lease: Long) {
        if (activeOverlayLease == lease) activeOverlayLease = null
    }
}
