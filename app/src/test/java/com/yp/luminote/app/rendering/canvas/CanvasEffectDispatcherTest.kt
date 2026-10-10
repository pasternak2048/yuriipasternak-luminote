package com.yp.luminote.app.rendering.canvas

import android.graphics.Canvas
import com.yp.luminote.app.animation.definitions.BladeVariant
import com.yp.luminote.app.animation.definitions.HaloEffectSpec
import com.yp.luminote.app.animation.definitions.HaloAnimationId
import com.yp.luminote.app.animation.definitions.LuminoteHaloAnimations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class CanvasEffectDispatcherTest {
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

    private enum class Primitive {
        SPECIALIZED_FIELD,
        FULL_CONTOUR,
        LUMINOUS_SEGMENT,
        AZURE_BLADE,
        CRIMSON_BLADE,
        CLASH_BLADE
    }

    private data class Call(
        val primitive: Primitive,
        val frame: CanvasEffectFrame,
        val startFraction: Float? = null,
        val lengthFraction: Float? = null
    )

    private class RecordingSurface : CanvasEffectRenderSurface {
        val calls = mutableListOf<Call>()

        override fun drawSpecializedField(canvas: Canvas, frame: CanvasEffectFrame) {
            calls += Call(Primitive.SPECIALIZED_FIELD, frame)
        }

        override fun drawFullContour(canvas: Canvas, frame: CanvasEffectFrame) {
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
