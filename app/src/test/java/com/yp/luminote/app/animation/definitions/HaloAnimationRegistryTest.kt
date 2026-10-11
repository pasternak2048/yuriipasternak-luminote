package com.yp.luminote.app.animation.definitions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HaloAnimationRegistryTest {

    @Test
    fun `a test local Meteor definition is usable without an engine or renderer change`() {
        val trailLength = FloatAnimationProperty(
            key = "trail_length",
            defaultValue = 0.25f,
            min = 0.10f,
            max = 0.60f
        )
        val meteor = fixture(
            id = HaloAnimationId("METEOR"),
            properties = listOf(trailLength)
        )

        val registry = HaloAnimationRegistry(listOf(meteor))

        assertSame(meteor, registry.definition(HaloAnimationId("METEOR")))
        assertEquals(listOf(meteor), registry.definitions)
        assertEquals(listOf(trailLength), registry.definition(HaloAnimationId("METEOR")).properties)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate identities are rejected at composition time`() {
        HaloAnimationRegistry(listOf(fixture(), fixture()))
    }

    @Test
    fun `ambient list is derived from definition metadata without id rules`() {
        val ambient = fixture(id = HaloAnimationId("METEOR"), ambientEligible = true)

        val registry = HaloAnimationRegistry(listOf(fixture(), ambient))

        assertEquals(listOf(ambient), registry.ambientDefinitions)
        assertTrue(registry.find(HaloAnimationId("UNKNOWN")) == null)
    }

    private fun fixture(
        id: HaloAnimationId = HaloAnimationId("FIXTURE"),
        ambientEligible: Boolean = false,
        properties: List<AnimationPropertySpec<*>> = emptyList()
    ): HaloAnimationDefinition = object : HaloAnimationDefinition {
        override val id = id
        override val titleRes = 0
        override val baseDurationSeconds = 1f
        override val ambientEligible = ambientEligible
        override val runtimePolicy = HaloRuntimePolicy(0L, 0L)
        override val effectSpec = HaloEffectSpec.FullContour
        override val properties = properties
    }
}
