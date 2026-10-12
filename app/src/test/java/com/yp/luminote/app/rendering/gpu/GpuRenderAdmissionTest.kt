package com.yp.luminote.app.rendering.gpu

import com.yp.luminote.app.animation.definitions.BladeVariant
import com.yp.luminote.app.animation.definitions.HaloEffectSpec
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.effects.model.HaloRenderMode
import com.yp.luminote.app.rendering.api.RenderMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuRenderAdmissionTest {
    @Test
    fun `only solid normal full contour animation is admitted to mesh backend`() {
        assertTrue(gpuCanFaithfullyRender(HaloConfig(effectPlan = HaloEffectSpec.FullContour), RenderMode.ANIMATION))
        assertFalse(gpuCanFaithfullyRender(HaloConfig(effectPlan = HaloEffectSpec.FullContour), RenderMode.STATIC_CALIBRATION))
        assertFalse(gpuCanFaithfullyRender(HaloConfig(effectPlan = HaloEffectSpec.FullContour, colorMode = HaloColorMode.GRADIENT), RenderMode.ANIMATION))
        assertFalse(gpuCanFaithfullyRender(HaloConfig(effectPlan = HaloEffectSpec.FullContour, palette = intArrayOf(1, 2)), RenderMode.ANIMATION))
        assertFalse(gpuCanFaithfullyRender(HaloConfig(effectPlan = HaloEffectSpec.FullContour, renderMode = HaloRenderMode.LIGHT_IMPULSE), RenderMode.ANIMATION))
    }

    @Test
    fun `complex effect families are deterministically routed to Canvas`() {
        val specs = listOf(
            HaloEffectSpec.SpecializedField,
            HaloEffectSpec.Supernova,
            HaloEffectSpec.LuminousSegment(.18f),
            HaloEffectSpec.Blade(BladeVariant.AZURE),
            HaloEffectSpec.Blade(BladeVariant.CRIMSON),
            HaloEffectSpec.Blade(BladeVariant.CLASH)
        )
        specs.forEach { spec ->
            assertFalse("$spec", gpuCanFaithfullyRender(HaloConfig(effectPlan = spec), RenderMode.ANIMATION))
        }
    }
}
