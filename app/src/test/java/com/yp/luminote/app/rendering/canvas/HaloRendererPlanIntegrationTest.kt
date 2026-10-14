package com.yp.luminote.app.rendering.canvas

import android.graphics.Canvas
import com.yp.luminote.app.animation.definitions.BladeVariant
import com.yp.luminote.app.animation.definitions.HaloEffectSpec
import com.yp.luminote.app.animation.plan.HaloEffectVisualPlanMapper
import com.yp.luminote.app.animation.plan.PrimaryVisualOperation
import com.yp.luminote.app.animation.plan.VisualPlanSpec
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.effects.model.HaloRenderMode
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.rendering.api.RenderFrame
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises the production FrameCommandCompiler -> HaloRenderer.drawPlan route for every family. */
class HaloRendererPlanIntegrationTest {
    @Test
    fun `plan route preserves legacy family execution across Canvas operations`() {
        val customized = HaloConfig(
            color = 0xFF12ABCD.toInt(),
            intensity = 0.63f,
            thickness = 0.82f,
            colorMode = HaloColorMode.GRADIENT,
            palette = intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt(), 0xFF778899.toInt()),
            edgeCalibrationDp = 3.5f,
            cornerCalibrationDp = -1.75f,
            cornerShape = 0.83f
        )
        val cases = listOf(
            customized.copy(effectPlan = HaloEffectSpec.FullContour),
            customized.copy(effectPlan = HaloEffectSpec.LuminousSegment(0.18f)),
            customized.copy(effectPlan = HaloEffectSpec.SpecializedField),
            customized.copy(renderMode = HaloRenderMode.LIGHT_IMPULSE, effectPlan = HaloEffectSpec.SpecializedField),
            customized.copy(effectPlan = HaloEffectSpec.Supernova),
            customized.copy(effectPlan = HaloEffectSpec.Blade(BladeVariant.AZURE)),
            customized.copy(effectPlan = HaloEffectSpec.Blade(BladeVariant.CRIMSON)),
            customized.copy(effectPlan = HaloEffectSpec.Blade(BladeVariant.CLASH))
        )
        val frame = RenderFrame().apply {
            animationProgress = 0.6f
            effectPhase = 0.4f
            gradientPhase = 0.7f
            frameTimeNanos = 42L
        }

        cases.forEach { config ->
            val outline = DisplayOutline(density = 1f).also { it.resize(320, 640) }
            val renderer = HaloRenderer(config, outline)
            val traces = mutableListOf<EdgeRenderPipeline.RenderTrace>()
            renderer.setRenderTraceForTest(traces)
            renderer.drawPlan(Canvas(), frame)
            renderer.draw(Canvas(), frame.animationProgress, frame.effectPhase, frame.gradientPhase, frame.frameTimeNanos)

            assertEquals("${config.effectPlan} plan/legacy trace count", 2, traces.size)
            assertEquals("${config.effectPlan} plan route must reach the same family inputs", traces[1], traces[0])
            assertEquals(config.edgeCalibrationDp, traces[0].geometry.edgeCalibrationDp)
            assertEquals(config.cornerCalibrationDp, traces[0].geometry.cornerCalibrationDp)
            assertEquals(config.cornerShape, traces[0].geometry.cornerShape)
            assertEquals(config.color, traces[0].appearance.color)
            assertEquals(config.intensity, traces[0].appearance.intensity)
            assertEquals(config.thickness, traces[0].appearance.thickness)
            assertEquals(config.colorMode, traces[0].appearance.colorMode)
            assertEquals(config.palette.toList(), traces[0].appearance.palette)
        }
    }

    @Test
    fun `plan route executes emitters in declared compositor order`() {
        val config = HaloConfig(effectPlan = HaloEffectSpec.FullContour)
        val renderer = HaloRenderer(config, DisplayOutline(density = 1f).also { it.resize(320, 640) })
        val emitter = HaloEffectVisualPlanMapper.map(config).emitters.single()
        renderer.replaceVisualPlanForTest(
            VisualPlanSpec(
                listOf(
                    emitter.copy(operation = PrimaryVisualOperation.FullContourPulse),
                    emitter.copy(operation = PrimaryVisualOperation.Supernova),
                    emitter.copy(operation = PrimaryVisualOperation.ForceBlade(BladeVariant.CLASH))
                )
            )
        )
        val traces = mutableListOf<EdgeRenderPipeline.RenderTrace>()
        renderer.setRenderTraceForTest(traces)
        renderer.drawPlan(Canvas(), RenderFrame().apply {
            animationProgress = 0.6f
            effectPhase = 0.4f
            gradientPhase = 0.7f
            frameTimeNanos = 42L
        })

        assertEquals(
            listOf(
                PrimaryVisualOperation.FullContourPulse,
                PrimaryVisualOperation.Supernova,
                PrimaryVisualOperation.ForceBlade(BladeVariant.CLASH)
            ),
            traces.map { it.operation }
        )
        assertEquals(listOf(107, 107, 107), traces.map { it.alpha })
    }
}
