package com.yp.luminote.app.effects

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedCompletionLeaseTest {
    @Test fun `watchdog requires actual start and completes exactly once`() {
        val lease = QueuedCompletionLease()
        val token = lease.begin()
        assertFalse(lease.completeFromWatchdog(token))
        assertTrue(lease.onStarted(token))
        assertTrue(lease.completeFromWatchdog(token))
        assertFalse(lease.completeNaturally(token))
    }

    @Test fun `stale watchdog and detached lease cannot affect replacement`() {
        val lease = QueuedCompletionLease()
        val stale = lease.begin()
        assertTrue(lease.onStarted(stale))
        lease.invalidate(stale)
        val replacement = lease.begin()
        assertTrue(lease.onStarted(replacement))
        assertFalse(lease.completeFromWatchdog(stale))
        assertTrue(lease.completeNaturally(replacement))
    }

    @Test fun `teardown terminal claim is exactly once and stale safe`() {
        val lease = QueuedCompletionLease()
        val active = lease.begin()
        assertTrue(lease.terminateForTeardown(active))
        assertFalse(lease.terminateForTeardown(active))
        val replacement = lease.begin()
        assertFalse(lease.completeNaturally(active))
        assertTrue(lease.terminateForTeardown(replacement))
    }
}
