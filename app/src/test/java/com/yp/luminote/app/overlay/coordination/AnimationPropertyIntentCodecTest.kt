package com.yp.luminote.app.overlay.coordination

import com.yp.luminote.app.animation.definitions.AnimationPropertyOverrides
import com.yp.luminote.app.animation.definitions.AnimationPropertyValue
import com.yp.luminote.app.animation.definitions.FloatAnimationProperty
import com.yp.luminote.app.animation.definitions.HaloAnimationId
import com.yp.luminote.app.effects.model.HaloConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class AnimationPropertyIntentCodecTest {
    private val snake = HaloAnimationId("SNAKE")
    private val segmentLength = FloatAnimationProperty("segment_length", 0.18f, 0.05f, 0.50f)

    @Test fun `wrong or malformed transport values are absent and use Snake definition default`() {
        listOf<Any>(true, "0.3", Float.NaN, Float.POSITIVE_INFINITY, 0.9f).forEach { malformed ->
            assertEquals(false, AnimationPropertyIntentCodec.acceptsFloat(segmentLength, malformed))
        }
        assertEquals(true, AnimationPropertyIntentCodec.acceptsFloat(segmentLength, 0.3f))
    }

    @Test fun `wrong type override resolves to Snake definition default`() {
        val overrides = AnimationPropertyOverrides().withValue(snake, "segment_length", AnimationPropertyValue.FloatValue(0.3f))
        assertEquals(0.3f, (HaloConfig.resolveEffectPlan(snake, overrides) as com.yp.luminote.app.animation.definitions.HaloEffectSpec.LuminousSegment).lengthFraction)
        val malformed = AnimationPropertyOverrides().withValue(snake, "segment_length", AnimationPropertyValue.BooleanValue(true))
        assertEquals(0.18f, (HaloConfig.resolveEffectPlan(snake, malformed) as com.yp.luminote.app.animation.definitions.HaloEffectSpec.LuminousSegment).lengthFraction)
    }
}
