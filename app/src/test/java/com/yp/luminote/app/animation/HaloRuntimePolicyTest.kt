package com.yp.luminote.app.animation

import com.yp.luminote.app.animation.definitions.HaloAnimationDefinition
import com.yp.luminote.app.animation.definitions.HaloAnimationId
import com.yp.luminote.app.animation.definitions.LuminoteHaloAnimations
import com.yp.luminote.app.data.settings.HaloAnimationStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HaloRuntimePolicyTest {
    @Test
    fun `catalog scalar policies resolve once with preserved timings`() {
        val pulse = LuminoteHaloAnimations.registry.definition(HaloAnimationId("PULSE"))
        val impulse = LuminoteHaloAnimations.registry.definition(HaloAnimationId("IMPULSE"))

        assertEquals(250L, pulse.runtimeEnvelope().fadeInMs)
        assertEquals(350L, pulse.runtimeEnvelope().fadeOutMs)
        assertEquals(0f, pulse.runtimeAmbientPolicy().alphaAt(0.0), 0f)
        assertEquals(1f, impulse.runtimeAmbientPolicy().alphaAt(0.0), 0f)
    }

    @Test
    fun `resolved definition has no canvas or lifecycle field dependency`() {
        HaloAnimationDefinition::class.java.declaredMethods.forEach { method ->
            assertFalse(method.returnType.name.startsWith("android.graphics"))
            assertFalse(method.returnType.name.startsWith("android.view"))
            assertFalse(method.returnType.name.contains(".overlay."))
        }
    }

    @Test
    fun `fallback motions retain the expected resolved runtime policy`() {
        val normal = HaloAnimationStorage.resolveNormal("unknown")
        val ambient = HaloAnimationStorage.resolveAmbient("FORCE_CLASH")

        assertEquals(HaloAnimationId("IMPULSE"), normal)
        assertEquals(HaloAnimationId("PULSE"), ambient)
        assertEquals(0L, LuminoteHaloAnimations.registry.definition(normal).runtimeEnvelope().fadeInMs)
        assertEquals(0f, LuminoteHaloAnimations.registry.definition(ambient).runtimeAmbientPolicy().alphaAt(0.0), 0f)
    }
}
