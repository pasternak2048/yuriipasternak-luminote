package com.yp.luminote.app.effects

import android.view.WindowInsets
import com.yp.luminote.app.data.settings.HaloEffectCatalog
import com.yp.luminote.app.data.settings.HaloFrame
import com.yp.luminote.app.data.settings.HaloMotion
import com.yp.luminote.app.data.settings.definition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HaloSurfaceRendererRegistryTest {

    @Test
    fun `classic surface selects a renderer for every supported motion`() {
        HaloFrame.CLASSIC.definition.supportedMotions.forEach { motion ->
            val expected = FakeRenderer()
            val registry = registryFor(expected)

            val renderer = registry.create(
                HaloConfig(frame = HaloFrame.CLASSIC, motion = motion),
                density = 1f
            )

            assertSame(expected, renderer)
        }
    }

    @Test
    fun `missing renderer registration fails safely without drawing`() {
        val registry = HaloSurfaceRendererRegistry(emptyMap())

        val renderer = registry.create(
            HaloConfig(frame = HaloFrame.CLASSIC, motion = HaloMotion.PULSE),
            density = 1f
        )

        assertNull(renderer)
    }

    @Test
    fun `unsupported surface motion pair cannot enter a renderer`() {
        val registry = HaloSurfaceRendererRegistry(
            factories = mapOf(HaloFrame.CLASSIC to HaloSurfaceRendererFactory { _, _ -> FakeRenderer() }),
            supports = { _, _ -> false }
        )

        val renderer = registry.create(
            HaloConfig(frame = HaloFrame.CLASSIC, motion = HaloMotion.PULSE),
            density = 1f
        )

        assertNull(renderer)
    }

    @Test
    fun `surface renderer contract carries complete animation state`() {
        val renderer = FakeRenderer()
        val state = HaloAnimationState(
            progress = 0.6f,
            phase = 0.4f,
            gradientPhase = 0.2f,
            running = true,
            ambient = true
        )

        renderer.capture(state)

        assertEquals(state, renderer.lastState)
    }

    @Test
    fun `catalog resolves a pair atomically to a supported surface motion`() {
        val selection = HaloEffectCatalog.resolve(HaloFrame.CLASSIC, HaloMotion.RIPPLE_EDGE)

        assertEquals(HaloFrame.CLASSIC, selection.frame)
        assertEquals(HaloMotion.RIPPLE_EDGE, selection.motion)
        assertEquals(true, HaloEffectCatalog.supports(selection.frame, selection.motion))
    }

    private fun registryFor(renderer: FakeRenderer) =
        HaloSurfaceRendererRegistry(
            mapOf(HaloFrame.CLASSIC to HaloSurfaceRendererFactory { _, _ -> renderer })
        )

    private class FakeRenderer : HaloSurfaceRenderer {
        var lastState: HaloAnimationState? = null

        override fun update(config: HaloConfig) = Unit

        override fun onSizeChanged(width: Int, height: Int) = Unit

        override fun onInsetsChanged(insets: WindowInsets) = Unit

        override fun draw(canvas: android.graphics.Canvas, state: HaloAnimationState) =
            capture(state)

        fun capture(state: HaloAnimationState) {
            lastState = state
        }
    }
}
