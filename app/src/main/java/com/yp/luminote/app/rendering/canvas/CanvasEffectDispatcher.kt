package com.yp.luminote.app.rendering.canvas

import android.graphics.Canvas
import com.yp.luminote.app.animation.LIGHT_CORE_SAMPLES
import com.yp.luminote.app.animation.definitions.BladeVariant
import com.yp.luminote.app.animation.definitions.HaloEffectSpec
import com.yp.luminote.app.animation.plan.PrimaryVisualOperation
import com.yp.luminote.app.animation.plan.VisualModifier

/**
 * Canvas translation of the backend-neutral effect contract.
 *
 * A target is selected when an animation session/config changes. Drawing then
 * invokes that already-resolved target directly, so VSYNC performs neither a
 * catalog lookup nor effect-family selection.
 */
internal class CanvasEffectDispatcher(
    private val surface: CanvasEffectRenderSurface
) {
    private val specializedField = CanvasSpecializedFieldRenderer(surface)
    private val supernova = CanvasSupernovaRenderer(surface)
    private val fullContour = CanvasFullContourRenderer(surface)
    private val luminousSegments = mutableMapOf<Float, CanvasEffectTarget>()
    private val azureBlade = CanvasAzureBladeRenderer(surface)
    private val crimsonBlade = CanvasCrimsonBladeRenderer(surface)
    private val clashBlade = CanvasClashBladeRenderer(surface)

    fun resolve(effectSpec: HaloEffectSpec): CanvasEffectTarget = when (effectSpec) {
        HaloEffectSpec.SpecializedField -> specializedField
        HaloEffectSpec.Supernova -> supernova
        HaloEffectSpec.FullContour -> fullContour
        is HaloEffectSpec.LuminousSegment -> luminousSegments.getOrPut(effectSpec.lengthFraction) {
            CanvasLuminousSegmentRenderer(surface, effectSpec.lengthFraction)
        }
        is HaloEffectSpec.Blade -> when (effectSpec.variant) {
            BladeVariant.AZURE -> azureBlade
            BladeVariant.CRIMSON -> crimsonBlade
            BladeVariant.CLASH -> clashBlade
        }
    }

    /** Plan operation adapter; it deliberately binds to the established render targets. */
    fun resolve(operation: PrimaryVisualOperation): CanvasEffectTarget = when (operation) {
        PrimaryVisualOperation.LightImpulse -> specializedField
        PrimaryVisualOperation.Supernova -> supernova
        PrimaryVisualOperation.FullContourPulse -> fullContour
        is PrimaryVisualOperation.TravelingLuminousSegment -> luminousSegments.getOrPut(operation.lengthFraction) {
            CanvasLuminousSegmentRenderer(surface, operation.lengthFraction)
        }
        is PrimaryVisualOperation.ForceBlade -> when (operation.variant) {
            BladeVariant.AZURE -> azureBlade
            BladeVariant.CRIMSON -> crimsonBlade
            BladeVariant.CLASH -> clashBlade
        }
    }
}

/** Existing Canvas primitives are exposed without leaking them into definitions. */
internal interface CanvasEffectRenderSurface {
    fun drawSpecializedField(canvas: Canvas, frame: CanvasEffectFrame, modifiers: List<VisualModifier>)
    fun drawSupernova(canvas: Canvas, frame: CanvasEffectFrame)
    fun drawFullContour(canvas: Canvas, frame: CanvasEffectFrame, modifiers: List<VisualModifier>)
    fun drawLuminousSegment(
        canvas: Canvas,
        frame: CanvasEffectFrame,
        startFraction: Float,
        lengthFraction: Float
    )
    fun drawAzureBlade(canvas: Canvas, frame: CanvasEffectFrame)
    fun drawCrimsonBlade(canvas: Canvas, frame: CanvasEffectFrame)
    fun drawClashBlade(canvas: Canvas, frame: CanvasEffectFrame)
}

/** Reused mutable frame input populated by [EdgeRenderPipeline] for every VSYNC. */
internal class CanvasEffectFrame {
    var phase: Float = 0f
    var alpha: Int = 0
    var gradientPhase: Float = 0f
    var frameTimeNanos: Long = 0L
    var intensityPulseMultiplier: Float = 1f
    /**
     * Canvas-private scale for a composited operation pass. It is intentionally
     * separate from [alpha]: normal Impulse owns its historical source envelope
     * and must not inherit the generic animation-progress alpha.
     */
    var compositedSourceAlphaScale: Float = 1f
    /**
     * Longitudinal core resolution selected by the config-bound renderer route. This is mutable
     * frame state so a normal Impulse's RenderNode recording can retain its source-only polish
     * without changing the dedicated reminder renderer or shared geometry constants.
     */
    var lightImpulseCoreSamples: Int = LIGHT_CORE_SAMPLES
}

internal fun interface CanvasEffectTarget {
    fun draw(canvas: Canvas, frame: CanvasEffectFrame, modifiers: List<VisualModifier>)
}

internal fun CanvasEffectTarget.draw(canvas: Canvas, frame: CanvasEffectFrame) = draw(canvas, frame, emptyList())

private class CanvasSpecializedFieldRenderer(
    private val surface: CanvasEffectRenderSurface
) : CanvasEffectTarget {
    override fun draw(canvas: Canvas, frame: CanvasEffectFrame, modifiers: List<VisualModifier>) =
        surface.drawSpecializedField(canvas, frame, modifiers)
}

private class CanvasSupernovaRenderer(
    private val surface: CanvasEffectRenderSurface
) : CanvasEffectTarget {
    override fun draw(canvas: Canvas, frame: CanvasEffectFrame, modifiers: List<VisualModifier>) = surface.drawSupernova(canvas, frame)
}

private class CanvasFullContourRenderer(
    private val surface: CanvasEffectRenderSurface
) : CanvasEffectTarget {
    override fun draw(canvas: Canvas, frame: CanvasEffectFrame, modifiers: List<VisualModifier>) =
        surface.drawFullContour(canvas, frame, modifiers)
}

private class CanvasLuminousSegmentRenderer(
    private val surface: CanvasEffectRenderSurface,
    private val lengthFraction: Float
) : CanvasEffectTarget {
    override fun draw(canvas: Canvas, frame: CanvasEffectFrame, modifiers: List<VisualModifier>) =
        surface.drawLuminousSegment(
            canvas,
            frame,
            normalizedFraction(frame.phase),
            lengthFraction
        )
}

/** Converts playback phase at the cached target-to-Canvas boundary. */
private fun normalizedFraction(value: Float): Float = (value % 1f + 1f) % 1f

/** Each blade target has its primitive bound while resolving the definition. */
private class CanvasAzureBladeRenderer(
    private val surface: CanvasEffectRenderSurface
) : CanvasEffectTarget {
    override fun draw(canvas: Canvas, frame: CanvasEffectFrame, modifiers: List<VisualModifier>) = surface.drawAzureBlade(canvas, frame)
}

private class CanvasCrimsonBladeRenderer(
    private val surface: CanvasEffectRenderSurface
) : CanvasEffectTarget {
    override fun draw(canvas: Canvas, frame: CanvasEffectFrame, modifiers: List<VisualModifier>) = surface.drawCrimsonBlade(canvas, frame)
}

private class CanvasClashBladeRenderer(
    private val surface: CanvasEffectRenderSurface
) : CanvasEffectTarget {
    override fun draw(canvas: Canvas, frame: CanvasEffectFrame, modifiers: List<VisualModifier>) = surface.drawClashBlade(canvas, frame)
}
