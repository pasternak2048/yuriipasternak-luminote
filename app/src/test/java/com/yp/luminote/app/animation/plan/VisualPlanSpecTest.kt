package com.yp.luminote.app.animation.plan

import com.yp.luminote.app.animation.definitions.BladeVariant
import com.yp.luminote.app.animation.definitions.HaloEffectSpec
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.rendering.api.FrameCommandBuffer
import com.yp.luminote.app.rendering.api.FrameCommandCompiler
import com.yp.luminote.app.rendering.api.RenderFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class VisualPlanSpecTest {
    @Test
    fun `legacy effect specs map one to one to typed operations`() {
        assertEquals(
            PrimaryVisualOperation.FullContourPulse,
            HaloEffectVisualPlanMapper.map(HaloConfig(effectPlan = HaloEffectSpec.FullContour)).emitters.single().operation
        )
        assertEquals(
            PrimaryVisualOperation.TravelingLuminousSegment(0.18f),
            HaloEffectVisualPlanMapper.map(HaloConfig(effectPlan = HaloEffectSpec.LuminousSegment(0.18f))).emitters.single().operation
        )
        assertEquals(
            PrimaryVisualOperation.LightImpulse,
            HaloEffectVisualPlanMapper.map(HaloConfig(effectPlan = HaloEffectSpec.SpecializedField)).emitters.single().operation
        )
        assertEquals(
            PrimaryVisualOperation.Supernova,
            HaloEffectVisualPlanMapper.map(HaloConfig(effectPlan = HaloEffectSpec.Supernova)).emitters.single().operation
        )
        assertEquals(
            PrimaryVisualOperation.ForceBlade(BladeVariant.CLASH),
            HaloEffectVisualPlanMapper.map(HaloConfig(effectPlan = HaloEffectSpec.Blade(BladeVariant.CLASH))).emitters.single().operation
        )
    }

    @Test
    fun `compiler preserves emitter order phase and alpha`() {
        val plan = VisualPlanSpec(
            listOf(
                emitter(PrimaryVisualOperation.FullContourPulse, EmitterTransform(phaseOffset = 0.1f)),
                emitter(PrimaryVisualOperation.TravelingLuminousSegment(0.2f), EmitterTransform(rate = 2f, reverse = true), listOf(VisualModifier.Opacity(0.5f)))
            )
        )
        val frame = RenderFrame().apply {
            animationProgress = 0.8f
            effectPhase = 0.25f
            gradientPhase = 0.4f
            frameTimeNanos = 42L
        }

        val buffer = FrameCommandCompiler.compile(plan, frame, FrameCommandBuffer())

        assertEquals(2, buffer.size)
        assertEquals(0.35f, buffer[0].phase, 0.0001f)
        assertEquals(PrimaryVisualOperation.FullContourPulse, buffer[0].operation)
        assertEquals(geometry(), buffer[0].geometry)
        assertEquals(appearance(), buffer[0].appearance)
        assertEquals(0.5f, buffer[1].phase, 0.0001f)
        assertEquals(102, buffer[1].alpha)
        assertEquals(42L, buffer[1].frameTimeNanos)
    }

    @Test
    fun `light impulse rejects opacity modifier until its dedicated envelope supports it`() {
        try {
            VisualEmitterSpec(
                geometry = geometry(),
                appearance = appearance(),
                operation = PrimaryVisualOperation.LightImpulse,
                modifiers = listOf(VisualModifier.Opacity(0.5f))
            )
            fail("Expected unsupported modifier combination to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: dedicated Light Impulse alpha has no compatible modifier yet.
        }
    }

    private fun emitter(
        operation: PrimaryVisualOperation,
        transform: EmitterTransform = EmitterTransform(),
        modifiers: List<VisualModifier> = emptyList()
    ) = VisualEmitterSpec(
        geometry = geometry(),
        transform = transform,
        appearance = appearance(),
        operation = operation,
        modifiers = modifiers
    )

    private fun appearance() = AppearancePayload(
        color = 0xFF3E91FF.toInt(),
        intensity = 1f,
        thickness = 0.5f,
        colorMode = com.yp.luminote.app.data.settings.HaloColorMode.SOLID,
        palette = emptyList()
    )

    private fun geometry() = GeometryRequest.DisplayOutlineContour(
        thickness = 0.5f,
        frame = com.yp.luminote.app.data.settings.HaloFrame.CLASSIC,
        edgeCalibrationDp = 0f,
        cornerCalibrationDp = 0f,
        cornerShape = 0.5f
    )
}
