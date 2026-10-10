package com.yp.luminote.app.animation

import com.yp.luminote.app.data.settings.HaloAnimationRegistry
import com.yp.luminote.app.data.settings.HaloMotion
import com.yp.luminote.app.data.settings.HaloMotionDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HaloRuntimePolicyTest {
    @Test
    fun `catalog scalar policies resolve once with preserved timings`() {
        val pulse = HaloAnimationRegistry.definition(HaloMotion.PULSE)
        val impulse = HaloAnimationRegistry.definition(HaloMotion.IMPULSE)

        assertEquals(250L, pulse.runtimeEnvelope().fadeInMs)
        assertEquals(350L, pulse.runtimeEnvelope().fadeOutMs)
        assertEquals(0f, pulse.runtimeAmbientPolicy().alphaAt(0.0), 0f)
        assertEquals(1f, impulse.runtimeAmbientPolicy().alphaAt(0.0), 0f)
    }

    @Test
    fun `settings definition has no animation runtime field dependency`() {
        HaloMotionDefinition::class.java.declaredFields.forEach { field ->
            assertFalse(field.type.name.startsWith("com.yp.luminote.app.animation"))
        }
    }

    @Test
    fun `fallback motions retain the expected resolved runtime policy`() {
        val normal = HaloAnimationRegistry.resolveNormal("unknown")
        val ambient = HaloAnimationRegistry.resolveAmbient("FORCE_CLASH")

        assertEquals(HaloMotion.IMPULSE, normal)
        assertEquals(HaloMotion.PULSE, ambient)
        assertEquals(0L, HaloAnimationRegistry.definition(normal).runtimeEnvelope().fadeInMs)
        assertEquals(0f, HaloAnimationRegistry.definition(ambient).runtimeAmbientPolicy().alphaAt(0.0), 0f)
    }
}
