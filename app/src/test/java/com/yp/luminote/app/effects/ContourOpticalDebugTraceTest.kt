package com.yp.luminote.app.effects

import org.junit.Assert.assertEquals
import org.junit.Test

class ContourOpticalDebugTraceTest {
    private val key = ContourRibbonGeometryKey(EdgePathCacheKey(1, 2f, 3f, 4f, 5f, 6f), 7f)

    @Test
    fun `each diagnostic milestone is emitted once until reset`() {
        val lines = mutableListOf<String>()
        val trace = ContourOpticalDebugTrace(enabled = true, sink = lines::add)

        trace.routeEntered(); trace.routeEntered()
        trace.hardwareRejected(); trace.hardwareRejected()
        trace.geometryUnavailable(); trace.geometryUnavailable()
        trace.submissionStarted(key); trace.submissionStarted(key)
        trace.drawn(key); trace.drawn(key)
        trace.terminal(key, ContourOpticalTerminalFailure("IllegalStateException", "boom"))
        trace.terminal(key, ContourOpticalTerminalFailure("IllegalStateException", "boom"))

        assertEquals(6, lines.size)
        trace.reset()
        trace.routeEntered()
        assertEquals(7, lines.size)
    }
}
