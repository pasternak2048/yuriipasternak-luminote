package com.yp.luminote.app.animation.definitions

import org.junit.Assert.assertEquals
import org.junit.Test

class AnimationPropertyResolverTest {
    private val length = FloatAnimationProperty("trail_length", 0.25f, 0.10f, 0.60f)
    private val meteor = object : HaloAnimationDefinition {
        override val id = HaloAnimationId("METEOR_TEST")
        override val titleRes = 0
        override val baseDurationSeconds = 1f
        override val ambientEligible = false
        override val runtimePolicy = HaloRuntimePolicy(0, 0)
        override val effectSpec = HaloEffectSpec.LuminousSegment(length.defaultValue)
        override val properties = listOf(length)
        override fun resolveEffectSpec(properties: ResolvedAnimationProperties) = HaloEffectSpec.LuminousSegment(properties[length])
    }

    @Test fun `missing invalid and bounded values resolve from definition safely`() {
        assertEquals(0.25f, AnimationPropertyResolver.resolve(meteor, AnimationPropertyOverrides())[length])
        val invalid = AnimationPropertyOverrides().withValue(meteor.id, length.key, AnimationPropertyValue.BooleanValue(true))
        assertEquals(0.25f, AnimationPropertyResolver.resolve(meteor, invalid)[length])
        val bounded = AnimationPropertyOverrides().withValue(meteor.id, length.key, AnimationPropertyValue.FloatValue(4f))
        assertEquals(0.60f, AnimationPropertyResolver.resolve(meteor, bounded)[length])
    }
}
