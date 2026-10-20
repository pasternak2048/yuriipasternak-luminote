package com.yp.luminote.app.rendering.gpu

import com.yp.luminote.app.rendering.api.RenderIntent
import com.yp.luminote.app.rendering.api.RenderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpuWitnessCapabilityTest {
    @Test
    fun `gate A accepts only an unmodified animation command`() {
        assertNull(
            GpuWitnessCapability.unsupportedReason(
                intent = RenderIntent.DEBUG_GPU_WITNESS,
                mode = RenderMode.ANIMATION,
                commandCount = 1,
                hasModifiers = false
            )
        )
    }

    @Test
    fun `gate A explicitly rejects static calibration and modifiers`() {
        assertEquals(
            "GPU witness does not support static calibration",
            GpuWitnessCapability.unsupportedReason(RenderIntent.DEBUG_GPU_WITNESS, RenderMode.STATIC_CALIBRATION, 0, false)
        )
        assertEquals(
            "GPU witness does not support production visual modifiers",
            GpuWitnessCapability.unsupportedReason(
                intent = RenderIntent.DEBUG_GPU_WITNESS,
                mode = RenderMode.ANIMATION,
                commandCount = 1,
                hasModifiers = true
            )
        )
    }

    @Test
    fun `gate A requires exactly one witness command`() {
        assertEquals(
            "GPU witness requires exactly one compiled visual command",
            GpuWitnessCapability.unsupportedReason(
                RenderIntent.DEBUG_GPU_WITNESS,
                RenderMode.ANIMATION,
                commandCount = 0,
                hasModifiers = false
            )
        )
        assertEquals(
            "GPU witness supports exactly one debug witness command",
            GpuWitnessCapability.unsupportedReason(
                RenderIntent.DEBUG_GPU_WITNESS,
                RenderMode.ANIMATION,
                commandCount = 2,
                hasModifiers = false
            )
        )
    }

    @Test
    fun `production visual operation is explicitly unsupported without debug witness selection`() {
        assertEquals(
            "GPU witness is DEBUG-only; production visual operations are unsupported",
            GpuWitnessCapability.unsupportedReason(
                intent = RenderIntent.PRODUCTION,
                mode = RenderMode.ANIMATION,
                commandCount = 1,
                hasModifiers = false
            )
        )
    }
}
