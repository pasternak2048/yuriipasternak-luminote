package com.yp.luminote.app.animation.definitions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HaloAnimationDefinitionsTest {

    @Test
    fun `production composition root preserves persisted identities and UI order`() {
        assertEquals(
            listOf(
                "IMPULSE",
                "PULSE",
                "SNAKE",
                "AZURE_BLADE",
                "CRIMSON_BLADE",
                "FORCE_CLASH",
                "SUPERNOVA"
            ),
            LuminoteHaloAnimations.all.map { it.id.value }
        )
    }

    @Test
    fun `registry resolves independently discoverable definitions`() {
        assertSame(ImpulseAnimation, LuminoteHaloAnimations.registry.definition(HaloAnimationId("IMPULSE")))
        assertSame(PulseAnimation, LuminoteHaloAnimations.registry.definition(HaloAnimationId("PULSE")))
        assertSame(SnakeAnimation, LuminoteHaloAnimations.registry.definition(HaloAnimationId("SNAKE")))
        assertSame(AzureBladeAnimation, LuminoteHaloAnimations.registry.definition(HaloAnimationId("AZURE_BLADE")))
        assertSame(CrimsonBladeAnimation, LuminoteHaloAnimations.registry.definition(HaloAnimationId("CRIMSON_BLADE")))
        assertSame(ForceClashAnimation, LuminoteHaloAnimations.registry.definition(HaloAnimationId("FORCE_CLASH")))
        assertSame(SupernovaAnimation, LuminoteHaloAnimations.registry.definition(HaloAnimationId("SUPERNOVA")))
    }

    @Test
    fun `definitions own preserved runtime and effect behavior`() {
        assertEquals(HaloRuntimePolicy(250L, 350L, AmbientPlayback.PULSE_WITH_SILENCE), PulseAnimation.runtimePolicy)
        assertEquals(HaloEffectSpec.LuminousSegment(0.18f), SnakeAnimation.effectSpec)
        assertEquals(
            listOf(SnakeAnimation.segmentLength),
            SnakeAnimation.properties
        )
        assertEquals(0.18f, SnakeAnimation.segmentLength.defaultValue)
        assertEquals(0.05f, SnakeAnimation.segmentLength.min)
        assertEquals(0.50f, SnakeAnimation.segmentLength.max)
        assertEquals(HaloEffectSpec.Blade(BladeVariant.CLASH), ForceClashAnimation.effectSpec)
        assertEquals(HaloEffectSpec.Supernova, SupernovaAnimation.effectSpec)
        assertEquals(0.6f, SupernovaAnimation.baseDurationSeconds)
        assertTrue(PulseAnimation.ambientEligible)
        assertTrue(SnakeAnimation.ambientEligible)
        assertEquals(listOf(PulseAnimation, SnakeAnimation), LuminoteHaloAnimations.ambient)
    }
}
