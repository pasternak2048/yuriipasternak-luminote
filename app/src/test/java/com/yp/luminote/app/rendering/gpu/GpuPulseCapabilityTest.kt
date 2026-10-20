package com.yp.luminote.app.rendering.gpu

import com.yp.luminote.app.animation.plan.AppearancePayload
import com.yp.luminote.app.animation.plan.PrimaryVisualOperation
import com.yp.luminote.app.animation.plan.VisualModifier
import com.yp.luminote.app.data.settings.HaloColorMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuPulseCapabilityTest {
    private fun appearance(mode: HaloColorMode = HaloColorMode.SOLID) = AppearancePayload(
        color = 0xFF00A0FF.toInt(), intensity = 1f, thickness = .5f, colorMode = mode,
        palette = emptyList()
    )

    @Test fun `admits only solid unmodified full contour pulse`() {
        assertTrue(GpuPulseCapability.supports(appearance(), PrimaryVisualOperation.FullContourPulse, emptyList()))
    }

    @Test fun `rejects unsupported operation modifiers and unproven gradient`() {
        assertFalse(GpuPulseCapability.supports(appearance(), PrimaryVisualOperation.LightImpulse, emptyList()))
        assertFalse(GpuPulseCapability.supports(appearance(), PrimaryVisualOperation.FullContourPulse, listOf(VisualModifier.Glow(1f))))
        assertFalse(GpuPulseCapability.supports(appearance(HaloColorMode.GRADIENT), PrimaryVisualOperation.FullContourPulse, emptyList()))
        assertFalse(GpuPulseCapability.supports(AppearancePayload(0xFF00A0FF.toInt(), 1f, .5f, HaloColorMode.SOLID, listOf(0xFF00A0FF.toInt(), 0xFFFF0088.toInt())), PrimaryVisualOperation.FullContourPulse, emptyList()))
    }
}
