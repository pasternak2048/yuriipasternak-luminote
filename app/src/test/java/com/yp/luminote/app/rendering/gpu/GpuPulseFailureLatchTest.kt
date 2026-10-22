package com.yp.luminote.app.rendering.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GpuPulseFailureLatchTest {
    @Test
    fun `same resource identity returns original failure without a new setup attempt`() {
        val latch = GpuPulseFailureLatch<String>()
        val root = IllegalStateException("uniform not found")
        val failure = GpuPulsePreSubmitFailure.at("uniform-color", root)

        latch.record("surface-3-field-a", failure)

        assertSame(failure, latch.failureFor("surface-3-field-a"))
        assertEquals("pre-submit-error:uniform-color:IllegalStateException", failure.classification)
        assertSame(root, failure.cause)
    }

    @Test
    fun `changed resource identity and explicit lifecycle clear permit recovery`() {
        val latch = GpuPulseFailureLatch<String>()
        latch.record("old-field", GpuPulsePreSubmitFailure.at("shader-compile", IllegalStateException("bad agsl")))

        assertNull(latch.failureFor("new-field"))
        latch.clear()
        assertNull(latch.failureFor("old-field"))
    }
}
