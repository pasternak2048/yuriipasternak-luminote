package com.yp.luminote.app.animation.definitions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HaloAnimationRegistryTest {

    @Test
    fun `a test local Meteor definition is usable without an engine or renderer change`() {
        val meteor = fixture(id = HaloAnimationId("METEOR"))

        val registry = HaloAnimationRegistry(listOf(meteor))

        assertSame(meteor, registry.definition(HaloAnimationId("METEOR")))
        assertEquals(listOf(meteor), registry.definitions)
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
        ambientEligible: Boolean = false
    ): HaloAnimationDefinition = object : HaloAnimationDefinition {
        override val id = id
        override val titleRes = 0
        override val baseDurationSeconds = 1f
        override val ambientEligible = ambientEligible
        override val runtimePolicy = HaloRuntimePolicy(0L, 0L)
        override val effectSpec = HaloEffectSpec.FullContour
    }
}
