package com.yp.luminote.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafeFrameworkSnapshotTest {
    @Test
    fun `returns platform snapshot when available`() {
        assertEquals("ranking", safeFrameworkSnapshot { "ranking" })
    }

    @Test
    fun `contains platform snapshot failure during reconnect`() {
        assertNull(safeFrameworkSnapshot<String> { throw IllegalStateException("listener reconnecting") })
    }
}
