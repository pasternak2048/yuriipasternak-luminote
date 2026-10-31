package com.yp.luminote.app.rendering.canvas

import android.graphics.Canvas
import com.yp.luminote.app.animation.definitions.BladeVariant
import com.yp.luminote.app.animation.definitions.HaloEffectSpec
import com.yp.luminote.app.animation.definitions.HaloAnimationId
import com.yp.luminote.app.animation.definitions.LuminoteHaloAnimations
import com.yp.luminote.app.animation.NORMAL_COMPOSITED_LIGHT_CORE_SAMPLES
import com.yp.luminote.app.animation.plan.PrimaryVisualOperation
import com.yp.luminote.app.animation.plan.VisualModifier
import com.yp.luminote.app.animation.plan.LuminousSegmentAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class CanvasEffectDispatcherTest {
    @Test(expected = IllegalStateException::class)
    fun `Resonance has no Canvas dispatch target`() {
        CanvasEffectDispatcher(RecordingSurface()).resolve(PrimaryVisualOperation.ResonanceFluidContour)
    }
    @Test
    fun `registered definitions dispatch to their Canvas primitives`() {
        val surface = RecordingSurface()
        val dispatcher = CanvasEffectDispatcher(surface)
        val frame = CanvasEffectFrame().apply {
            phase = 0.4f
            alpha = 153
            gradientPhase = 0.7f
            frameTimeNanos = 42L
        }

        LuminoteHaloAnimations.all.forEach { definition ->
            dispatcher.resolve(definition.effectSpec).draw(Canvas(), frame)
        }

        assertEquals(
            listOf(
                Primitive.SPECIALIZED_FIELD,
                Primitive.SUPERNOVA,
                Primitive.FULL_CONTOUR,
                Primitive.LUMINOUS_SEGMENT,
                Primitive.AZURE_BLADE,
                Primitive.CRIMSON_BLADE,
                Primitive.CLASH_BLADE
            ),
            surface.calls.map(Call::primitive)
        )
        assertEquals(0.18f, surface.calls.single { it.primitive == Primitive.LUMINOUS_SEGMENT }.lengthFraction)
        surface.calls.forEach { call -> assertSame(frame, call.frame) }
    }

    @Test
    fun `typed plan operations retain Canvas routing phase alpha gradient and blade variants`() {
        val surface = RecordingSurface()
        val dispatcher = CanvasEffectDispatcher(surface)
        val frame = CanvasEffectFrame().apply {
            phase = 0.4f
            alpha = 153
            gradientPhase = 0.7f
            frameTimeNanos = 42L
        }
        val operations = listOf(
            PrimaryVisualOperation.FullContourPulse,
            PrimaryVisualOperation.TravelingLuminousSegment(0.18f),
            PrimaryVisualOperation.LightImpulse,
            PrimaryVisualOperation.Supernova,
            PrimaryVisualOperation.ForceBlade(BladeVariant.AZURE),
            PrimaryVisualOperation.ForceBlade(BladeVariant.CRIMSON),
            PrimaryVisualOperation.ForceBlade(BladeVariant.CLASH)
        )

        operations.forEach { dispatcher.resolve(it).draw(Canvas(), frame) }

        assertEquals(
            listOf(
                Primitive.FULL_CONTOUR, Primitive.LUMINOUS_SEGMENT, Primitive.SPECIALIZED_FIELD,
                Primitive.SUPERNOVA, Primitive.AZURE_BLADE, Primitive.CRIMSON_BLADE, Primitive.CLASH_BLADE
            ),
            surface.calls.map(Call::primitive)
        )
        surface.calls.forEach { call ->
            assertSame(frame, call.frame)
            assertEquals(0.4f, call.frame.phase)
            assertEquals(153, call.frame.alpha)
            assertEquals(0.7f, call.frame.gradientPhase)
            assertEquals(42L, call.frame.frameTimeNanos)
        }
        assertEquals(0.18f, surface.calls[1].lengthFraction)
    }

    @Test
    fun `Supernova's production modifier stack retains the dedicated Supernova Canvas target`() {
        val surface = RecordingSurface()
        val frame = CanvasEffectFrame()
        val productionStack = listOf(
            VisualModifier.IntensityPulse(amplitude = 0.18f, frequencyHz = 1.65f),
            VisualModifier.Blur(radiusPx = 4.5f),
            VisualModifier.Glow(radiusPx = 11f, alphaMultiplier = 0.50f)
        )

        CanvasEffectDispatcher(surface)
            .resolve(PrimaryVisualOperation.Supernova)
            .draw(Canvas(), frame, productionStack)

        assertEquals(listOf(Primitive.SUPERNOVA), surface.calls.map(Call::primitive))
    }

    @Test
    fun `cached luminous target normalizes negative phase at the rendering-surface boundary`() {
        val surface = RecordingSurface()
        val dispatcher = CanvasEffectDispatcher(surface)
        val snake = dispatcher.resolve(HaloEffectSpec.LuminousSegment(0.18f))
        val frame = CanvasEffectFrame().apply { phase = -0.25f }

        assertSame(snake, dispatcher.resolve(HaloEffectSpec.LuminousSegment(0.18f)))

        snake.draw(Canvas(), frame)

        assertEquals(0.18f, surface.calls.single().lengthFraction)
        assertEquals(0.75f, surface.calls.single().startFraction)
        assertEquals(-0.25f, surface.calls.single().frame.phase)
    }

    @Test
    fun `snake definition retains declared length while its path phase normalizes`() {
        val snake = LuminoteHaloAnimations.registry.definition(HaloAnimationId("SNAKE"))
        val effect = snake.effectSpec as HaloEffectSpec.LuminousSegment

        val plan = luminousSnakeDrawPlan(contourLength = 100f, phase = -0.25f)

        assertEquals(0.18f, effect.lengthFraction)
        assertEquals(75f, plan?.start)
        assertEquals(18f, plan?.length)
    }

    @Test
    fun `blade targets are distinct and prebound to their primitive`() {
        val surface = RecordingSurface()
        val dispatcher = CanvasEffectDispatcher(surface)
        val frame = CanvasEffectFrame()
        val azure = dispatcher.resolve(HaloEffectSpec.Blade(BladeVariant.AZURE))
        val crimson = dispatcher.resolve(HaloEffectSpec.Blade(BladeVariant.CRIMSON))
        val clash = dispatcher.resolve(HaloEffectSpec.Blade(BladeVariant.CLASH))

        assertSame(azure, dispatcher.resolve(HaloEffectSpec.Blade(BladeVariant.AZURE)))
        assertNotSame(azure, crimson)
        assertNotSame(crimson, clash)

        azure.draw(Canvas(), frame)
        crimson.draw(Canvas(), frame)
        clash.draw(Canvas(), frame)

        assertEquals(
            listOf(Primitive.AZURE_BLADE, Primitive.CRIMSON_BLADE, Primitive.CLASH_BLADE),
            surface.calls.map(Call::primitive)
        )
    }

    @Test
    fun `ordinary light impulse receives the compiled pulse multiplier at its Canvas source`() {
        val surface = RecordingSurface()
        val frame = CanvasEffectFrame().apply { intensityPulseMultiplier = 0.5f }
        CanvasEffectDispatcher(surface)
            .resolve(PrimaryVisualOperation.LightImpulse)
            .draw(Canvas(), frame, listOf(VisualModifier.IntensityPulse(1f)))

        assertEquals(Primitive.SPECIALIZED_FIELD, surface.calls.single().primitive)
        assertEquals(0.5f, surface.calls.single().pulseMultiplier)
        assertEquals(listOf(VisualModifier.IntensityPulse(1f)), surface.calls.single().modifiers)
    }

    @Test
    fun `ordinary light impulse receives the refined core resolution at its Canvas source`() {
        val surface = RecordingSurface()
        val frame = CanvasEffectFrame().apply {
            lightImpulseCoreSamples = NORMAL_COMPOSITED_LIGHT_CORE_SAMPLES
        }

        CanvasEffectDispatcher(surface)
            .resolve(PrimaryVisualOperation.LightImpulse)
            .draw(Canvas(), frame, listOf(VisualModifier.Blur(3.5f)))

        assertEquals(
            NORMAL_COMPOSITED_LIGHT_CORE_SAMPLES,
            surface.calls.single().frame.lightImpulseCoreSamples
        )
    }

    @Test
    fun `Snake appearance remains on the reused frame through luminous segment dispatch`() {
        val surface = RecordingSurface()
        val frame = CanvasEffectFrame().apply {
            luminousSegmentAppearance = LuminousSegmentAppearance.FLOWING_SNAKE
        }

        CanvasEffectDispatcher(surface)
            .resolve(PrimaryVisualOperation.TravelingLuminousSegment(0.18f))
            .draw(Canvas(), frame)

        assertEquals(LuminousSegmentAppearance.FLOWING_SNAKE, surface.calls.single().frame.luminousSegmentAppearance)
    }

    private enum class Primitive {
        SPECIALIZED_FIELD,
        FULL_CONTOUR,
        LUMINOUS_SEGMENT,
        AZURE_BLADE,
        CRIMSON_BLADE,
        CLASH_BLADE,
        SUPERNOVA
    }

    private data class Call(
        val primitive: Primitive,
        val frame: CanvasEffectFrame,
        val startFraction: Float? = null,
        val lengthFraction: Float? = null,
        val modifiers: List<VisualModifier> = emptyList(),
        val pulseMultiplier: Float = frame.intensityPulseMultiplier
    )

    private class RecordingSurface : CanvasEffectRenderSurface {
        val calls = mutableListOf<Call>()

        override fun drawSpecializedField(canvas: Canvas, frame: CanvasEffectFrame, modifiers: List<VisualModifier>) {
            calls += Call(Primitive.SPECIALIZED_FIELD, frame, modifiers = modifiers)
        }

        override fun drawSupernova(canvas: Canvas, frame: CanvasEffectFrame) {
            calls += Call(Primitive.SUPERNOVA, frame)
        }

        override fun drawFullContour(canvas: Canvas, frame: CanvasEffectFrame, modifiers: List<VisualModifier>) {
            calls += Call(Primitive.FULL_CONTOUR, frame)
        }

        override fun drawLuminousSegment(
            canvas: Canvas,
            frame: CanvasEffectFrame,
            startFraction: Float,
            lengthFraction: Float
        ) {
            calls += Call(Primitive.LUMINOUS_SEGMENT, frame, startFraction, lengthFraction)
        }

        override fun drawAzureBlade(canvas: Canvas, frame: CanvasEffectFrame) {
            calls += Call(Primitive.AZURE_BLADE, frame)
        }

        override fun drawCrimsonBlade(canvas: Canvas, frame: CanvasEffectFrame) {
            calls += Call(Primitive.CRIMSON_BLADE, frame)
        }

        override fun drawClashBlade(canvas: Canvas, frame: CanvasEffectFrame) {
            calls += Call(Primitive.CLASH_BLADE, frame)
        }
    }
}
