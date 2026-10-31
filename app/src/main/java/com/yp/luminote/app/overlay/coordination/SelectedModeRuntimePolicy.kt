package com.yp.luminote.app.overlay.coordination

/** Attempt identity is derived from real activation events, never from frame/status emissions. */
internal class ResonanceModeActivation {
    private var attempted: Triple<Long, Long, Long>? = null
    fun takeAttempt(modeEpoch: Long, foreground: Long, accessEpoch: Long,
        foregroundReady: Boolean, accessReady: Boolean, canStart: Boolean): Boolean {
        if (foreground == 0L || !foregroundReady || !accessReady || !canStart) return false
        val key = Triple(modeEpoch, foreground, accessEpoch)
        if (attempted == key) return false
        attempted = key
        return true
    }
}

/** Transient source leases; this owns no mode value and writes no settings. */
internal class SelectedModeSourceAuthority {
    var publisher: Long = 0L; private set
    private var persistedEpoch = 0L
    fun claimPublisher(lease: Long) { publisher = lease; persistedEpoch++ }
    fun acceptPublisher(lease: Long): Boolean = publisher == lease
    fun releasePublisher(lease: Long, latestSuccessfullyCommitted: Boolean): Boolean {
        if (publisher != lease || !latestSuccessfullyCommitted) return false
        publisher = 0
        return true
    }
    fun newPersistedFeed(): Long = ++persistedEpoch
    fun acceptPersisted(epoch: Long): Boolean = publisher == 0L && epoch == persistedEpoch
}
