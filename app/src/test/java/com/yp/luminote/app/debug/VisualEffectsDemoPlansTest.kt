package com.yp.luminote.app.debug

import com.yp.luminote.app.animation.definitions.BladeVariant
import com.yp.luminote.app.animation.plan.PrimaryVisualOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualEffectsDemoPlansTest {
    @Test
    fun `every family maps its baseline to its real production operation`() {
        val operations = VisualEffectsDemoFamily.entries.associateWith { family ->
            visualEffectsDemoPlan(family, VisualEffectsDemoMode.BASELINE)!!.emitters.single().operation
        }

        assertEquals(PrimaryVisualOperation.FullContourPulse, operations.getValue(VisualEffectsDemoFamily.FULL_CONTOUR))
        assertEquals(PrimaryVisualOperation.TravelingLuminousSegment(0.18f), operations.getValue(VisualEffectsDemoFamily.LUMINOUS_SEGMENT))
        assertEquals(PrimaryVisualOperation.LightImpulse, operations.getValue(VisualEffectsDemoFamily.LIGHT_IMPULSE))
        assertEquals(PrimaryVisualOperation.Supernova, operations.getValue(VisualEffectsDemoFamily.SUPERNOVA))
        assertEquals(PrimaryVisualOperation.ForceBlade(BladeVariant.AZURE), operations.getValue(VisualEffectsDemoFamily.FORCE_BLADE_AZURE))
        assertEquals(PrimaryVisualOperation.ForceBlade(BladeVariant.CRIMSON), operations.getValue(VisualEffectsDemoFamily.FORCE_BLADE_CRIMSON))
        assertEquals(PrimaryVisualOperation.ForceBlade(BladeVariant.CLASH), operations.getValue(VisualEffectsDemoFamily.FORCE_BLADE_CLASH))
    }

    @Test
    fun `D1 exposes full stacks only for families with admitted semantics`() {
        listOf(
            VisualEffectsDemoFamily.FULL_CONTOUR,
            VisualEffectsDemoFamily.LUMINOUS_SEGMENT,
            VisualEffectsDemoFamily.SUPERNOVA
        ).forEach { family ->
            VisualEffectsDemoMode.entries.filter { it != VisualEffectsDemoMode.PRODUCTION && it != VisualEffectsDemoMode.PRODUCTION_SUPERNOVA }.forEach { mode ->
                assertTrue(supportsDemoMode(family, mode))
                assertTrue(visualEffectsDemoPlan(family, mode) != null)
            }
        }

        listOf(
            VisualEffectsDemoFamily.LIGHT_IMPULSE,
            VisualEffectsDemoFamily.FORCE_BLADE_AZURE,
            VisualEffectsDemoFamily.FORCE_BLADE_CRIMSON,
            VisualEffectsDemoFamily.FORCE_BLADE_CLASH
        ).forEach { family ->
            assertTrue(supportsDemoMode(family, VisualEffectsDemoMode.BASELINE))
            assertTrue(supportsDemoMode(family, VisualEffectsDemoMode.INTENSITY_PULSE))
            assertTrue(visualEffectsDemoPlan(family, VisualEffectsDemoMode.INTENSITY_PULSE) != null)
            assertFalse(supportsDemoMode(family, VisualEffectsDemoMode.BLUR))
            assertFalse(supportsDemoMode(family, VisualEffectsDemoMode.GLOW))
            assertFalse(supportsDemoMode(family, VisualEffectsDemoMode.COMBINED))
            assertNull(visualEffectsDemoPlan(family, VisualEffectsDemoMode.GLOW))
        }
    }

    @Test fun `production Snake stack is independently reachable without changing full stack`() {
        val family = VisualEffectsDemoFamily.LUMINOUS_SEGMENT
        assertTrue(demoCapability(VisualEffectsDemoEngine.GPU,family,VisualEffectsDemoMode.BASELINE).admitted)
        assertTrue(demoCapability(VisualEffectsDemoEngine.GPU,family,VisualEffectsDemoMode.PRODUCTION).admitted)
        assertFalse(demoCapability(VisualEffectsDemoEngine.GPU,family,VisualEffectsDemoMode.COMBINED).admitted)
        assertEquals(com.yp.luminote.app.rendering.gpu.GpuSnakeCapability.productionModifiers,
            visualEffectsDemoPlan(family,VisualEffectsDemoMode.PRODUCTION)!!.emitters.single().modifiers)
        assertNull(visualEffectsDemoPlan(VisualEffectsDemoFamily.FULL_CONTOUR,VisualEffectsDemoMode.PRODUCTION))
        assertEquals(10f,visualEffectsDemoPlan(family,VisualEffectsDemoMode.COMBINED)!!.emitters.single().modifiers.filterIsInstance<com.yp.luminote.app.animation.plan.VisualModifier.Blur>().single().radiusPx)
    }
}
