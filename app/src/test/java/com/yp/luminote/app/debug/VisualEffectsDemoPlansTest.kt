package com.yp.luminote.app.debug

import com.yp.luminote.app.animation.definitions.HaloEffectSpec
import com.yp.luminote.app.animation.definitions.PulseAnimation
import com.yp.luminote.app.animation.plan.PrimaryVisualOperation
import com.yp.luminote.app.animation.plan.VisualModifier
import com.yp.luminote.app.effects.model.HaloConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class VisualEffectsDemoPlansTest {
    @Test
    fun `all modes keep one FullContourPulse base and change only modifiers`() {
        val plans = visualEffectsDemoPlans(
            HaloConfig(motion = PulseAnimation.id, effectPlan = HaloEffectSpec.FullContour).sanitized()
        )

        assertEquals(VisualEffectsDemoMode.entries.toSet(), plans.keys)
        val baseline = plans.getValue(VisualEffectsDemoMode.BASELINE).emitters.single()
        assertSame(PrimaryVisualOperation.FullContourPulse, baseline.operation)
        assertEquals(emptyList<VisualModifier>(), baseline.modifiers)

        plans.values.forEach { plan ->
            val emitter = plan.emitters.single()
            assertSame(PrimaryVisualOperation.FullContourPulse, emitter.operation)
            assertEquals(baseline.geometry, emitter.geometry)
            assertEquals(baseline.transform, emitter.transform)
            assertEquals(baseline.appearance, emitter.appearance)
        }
        assertEquals(listOf(VisualModifier.Glow(18f, 0.75f)), plans.getValue(VisualEffectsDemoMode.GLOW).emitters.single().modifiers)
        assertEquals(listOf(VisualModifier.Blur(10f)), plans.getValue(VisualEffectsDemoMode.BLUR).emitters.single().modifiers)
        assertEquals(
            listOf(VisualModifier.IntensityPulse(0.55f, 1.2f)),
            plans.getValue(VisualEffectsDemoMode.INTENSITY_PULSE).emitters.single().modifiers
        )
        assertEquals(
            listOf(VisualModifier.IntensityPulse(0.55f, 1.2f), VisualModifier.Blur(10f), VisualModifier.Glow(18f, 0.75f)),
            plans.getValue(VisualEffectsDemoMode.COMBINED).emitters.single().modifiers
        )
    }
}
