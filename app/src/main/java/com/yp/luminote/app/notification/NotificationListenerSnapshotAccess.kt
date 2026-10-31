package com.yp.luminote.app.notification

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes settings publication, snapshot filtering and reminder enqueue ordering. */
internal class NotificationListenerRefreshOrder {
    private val mutex = Mutex()
    suspend fun <T> run(action: () -> T): T = mutex.withLock { action() }
}

/** Framework snapshot access is valid only between listener connection callbacks. */
internal class NotificationListenerSnapshotAccess(private val onAccessRejected: (SecurityException) -> Unit = {}) {
    internal data class Snapshot<T>(val generation: Long, val value: T)
    private var generation = 0L
    private var connected = false

    @Synchronized fun onConnected(): Long { connected = true; return ++generation }
    @Synchronized fun onDisconnected() { connected = false; generation++ }

    fun <T> read(expectedGeneration: Long? = null, readFrameworkSnapshot: () -> T): Snapshot<T>? {
        val owner = synchronized(this) {
            if (!connected || expectedGeneration != null && expectedGeneration != generation) return null
            generation
        }
        val value = try {
            readFrameworkSnapshot()
        } catch (error: SecurityException) {
            // Android can revoke its Binder token before onListenerDisconnected arrives.
            synchronized(this) { if (generation == owner) connected = false }
            onAccessRejected(error)
            return null
        }
        return Snapshot(owner, value)
    }

    @Synchronized fun <T> applyIfCurrent(snapshot: Snapshot<T>, apply: (T) -> Unit): Boolean {
        if (!connected || snapshot.generation != generation) return false
        apply(snapshot.value)
        return true
    }
}
