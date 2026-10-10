package com.yp.luminote.app.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContourOpticalSubmissionStateTest {

    @Test
    fun `only terminal gpu outcomes stop the owning render session`() {
        assertFalse(isTerminalContourOpticalOutcome(ContourOpticalDrawResult.DRAWN))
        assertFalse(isTerminalContourOpticalOutcome(ContourOpticalDrawResult.EMPTY_FIELD))
        assertTrue(isTerminalContourOpticalOutcome(ContourOpticalDrawResult.UNSUPPORTED_HARDWARE))
        assertTrue(isTerminalContourOpticalOutcome(ContourOpticalDrawResult.GPU_UNAVAILABLE))
    }

    private val key = ContourRibbonGeometryKey(EdgePathCacheKey(1, 2f, 3f, 4f, 5f, 6f), 7f)

    @Test
    fun `fatal submission is sticky for the same geometry key`() {
        val state = ContourOpticalSubmissionState()
        val first = state.markFatal(key, IllegalStateException("first"))
        val repeated = state.markFatal(key, IllegalArgumentException("second"))

        assertSame(first, repeated)
        assertEquals(IllegalStateException::class.java.name, repeated.throwableClass)
        assertEquals("first", repeated.message)
    }

    @Test
    fun `clear permits a renderer generation to retry after geometry lifecycle reset`() {
        val state = ContourOpticalSubmissionState()
        state.markFatal(key, IllegalStateException("first"))
        state.clear()

        assertNull(state.failureFor(key))
        assertEquals("retry", state.markFatal(key, IllegalStateException("retry")).message)
    }
}
