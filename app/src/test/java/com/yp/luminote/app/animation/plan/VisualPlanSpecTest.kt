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

    @Test
    fun `new optical modifiers admit only full contour in canonical unique order`() {
        val all = listOf(
            VisualModifier.Opacity(0.8f),
            VisualModifier.IntensityPulse(0.4f, 2f),
            VisualModifier.Blur(3f),
            VisualModifier.Glow(6f, 0.5f)
        )
        VisualEmitterSpec(geometry(), appearance = appearance(), operation = PrimaryVisualOperation.FullContourPulse, modifiers = all)

        listOf<PrimaryVisualOperation>(
            PrimaryVisualOperation.TravelingLuminousSegment(0.2f),
            PrimaryVisualOperation.LightImpulse,
            PrimaryVisualOperation.Supernova,
            PrimaryVisualOperation.ForceBlade(BladeVariant.AZURE)
        ).forEach { operation ->
            listOf(VisualModifier.IntensityPulse(0.2f), VisualModifier.Blur(2f), VisualModifier.Glow(2f)).forEach { modifier ->
                expectRejected { VisualEmitterSpec(geometry(), appearance = appearance(), operation = operation, modifiers = listOf(modifier)) }
            }
        }
        expectRejected { VisualEmitterSpec(geometry(), appearance = appearance(), operation = PrimaryVisualOperation.FullContourPulse, modifiers = all.reversed()) }
        expectRejected { VisualEmitterSpec(geometry(), appearance = appearance(), operation = PrimaryVisualOperation.FullContourPulse, modifiers = listOf(VisualModifier.Blur(1f), VisualModifier.Blur(2f))) }
    }

    @Test
    fun `admission is JVM immutable and pulse is deterministic at large timestamps`() {
        val mutableModifiers = mutableListOf<VisualModifier>(VisualModifier.IntensityPulse(1f, 1f))
        val emitter = VisualEmitterSpec(geometry(), appearance = appearance(), operation = PrimaryVisualOperation.FullContourPulse, modifiers = mutableModifiers)
        val plan = VisualPlanSpec(mutableListOf(emitter))
        mutableModifiers.clear()
        assertEquals(1, plan.emitters.single().modifiers.size)
        expectImmutable { (plan.emitters as MutableList<VisualEmitterSpec>).clear() }
        expectImmutable { (emitter.modifiers as MutableList<VisualModifier>).clear() }
        assertEquals(1, plan.emitters.size)
        assertEquals(1, plan.emitters.single().modifiers.size)

        val frame = RenderFrame().apply {
            animationProgress = 1f
            frameTimeNanos = 9_000_000_000_250_000_000L
        }
        val first = FrameCommandCompiler.compile(plan, frame, FrameCommandBuffer())[0].alpha
        val second = FrameCommandCompiler.compile(plan, frame, FrameCommandBuffer())[0].alpha
        assertEquals(first, second)
        // 0.25 seconds at 1 Hz is peak, proving precision was not lost before division.
        assertEquals(255, first)
    }

    @Test
    fun `pulse frequency has a bounded frame-resolvable range`() {
        expectRejected { VisualModifier.IntensityPulse(0.5f, 120.1f) }
        VisualModifier.IntensityPulse(0.5f, 120f)
    }

    private fun expectRejected(block: () -> Unit) {
        try {
            block()
            fail("Expected modifier admission to reject the combination")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun expectImmutable(block: () -> Unit) {
        try {
            block()
            fail("Expected admitted collection to reject mutation")
        } catch (_: UnsupportedOperationException) {
            // Expected: JVM-facing collection is unmodifiable.
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
