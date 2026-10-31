package com.yp.luminote.app.notification

import org.junit.Assert.*
import org.junit.Test

class NotificationListenerSnapshotAccessTest {
    @Test fun settingsEmissionBeforeConnectionDoesNotCallFrameworkAndConnectionRehydrates() {
        val access = NotificationListenerSnapshotAccess()
        var reads = 0
        val retained = mutableListOf("retained-event")
        assertNull(access.read { reads++; error("Android rejects unknown listener") })
        assertEquals(0, reads)
        assertEquals(listOf("retained-event"), retained)
        val connection = access.onConnected()
        val snapshot = access.read(connection) { reads++; listOf("restored-system-notification") }!!
        assertTrue(access.applyIfCurrent(snapshot) { retained.clear(); retained.addAll(it) })
        assertEquals(1, reads)
        assertEquals(listOf("restored-system-notification"), retained)
    }

    @Test fun tokenRevokedBeforeDisconnectCallbackRetainsStateAndRetriesOnlyAfterReconnect() {
        var rejections = 0
        val access = NotificationListenerSnapshotAccess { rejections++ }
        access.onConnected()
        val retained = mutableListOf("existing-notification")
        assertNull(access.read<List<String>> { throw SecurityException("unknown listener") })
        assertEquals(1, rejections)
        assertNull(access.read { error("Must wait for a real connection callback") })
        assertEquals(listOf("existing-notification"), retained)
        access.onDisconnected()
        val connection = access.onConnected()
        val snapshot = access.read(connection) { listOf("current-notification") }!!
        access.applyIfCurrent(snapshot) { retained.clear(); retained.addAll(it) }
        assertEquals(listOf("current-notification"), retained)
    }

    @Test fun staleReadAndOldWaitingConnectionCannotRestoreAfterDisconnectOrRecreation() {
        val access = NotificationListenerSnapshotAccess()
        val firstConnection = access.onConnected()
        val oldSnapshot = access.read { "old" }!!
        access.onDisconnected()
        val secondConnection = access.onConnected()
        assertFalse(access.applyIfCurrent(oldSnapshot) { error("Stale restore") })
        assertNull(access.read(firstConnection) { error("Old connection task must not read") })
        val freshSnapshot = access.read(secondConnection) { "fresh" }!!
        var restored = ""
        assertTrue(access.applyIfCurrent(freshSnapshot) { restored = it })
        assertEquals("fresh", restored)
    }

    @Test fun rejectedOldBinderReadCannotInvalidateNewConnection() {
        val access = NotificationListenerSnapshotAccess()
        access.onConnected()
        assertNull(access.read<String> {
            access.onDisconnected()
            access.onConnected()
            throw SecurityException("old Binder token")
        })
        val fresh = access.read { "fresh" }!!
        assertTrue(access.applyIfCurrent(fresh) { assertEquals("fresh", it) })
    }

    @Test(expected = IllegalStateException::class)
    fun unrelatedFrameworkFailureIsNotSwallowed() {
        val access = NotificationListenerSnapshotAccess()
        access.onConnected()
        access.read<String> { throw IllegalStateException("Unrelated programming error") }
    }

    @Test(expected = SecurityException::class)
    fun errorsInStateApplicationAreNotMistakenForBinderAccessRevocation() {
        val access = NotificationListenerSnapshotAccess()
        access.onConnected()
        val snapshot = access.read { "snapshot" }!!
        access.applyIfCurrent(snapshot) { throw SecurityException("Application error") }
    }
}
