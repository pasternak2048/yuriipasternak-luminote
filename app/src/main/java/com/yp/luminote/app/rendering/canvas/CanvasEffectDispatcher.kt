package com.yp.luminote.app.rendering.canvas

import android.graphics.Canvas
import com.yp.luminote.app.animation.definitions.BladeVariant
import com.yp.luminote.app.animation.definitions.HaloEffectSpec

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
    private val fullContour = CanvasFullContourRenderer(surface)
    private val luminousSegments = mutableMapOf<Float, CanvasEffectTarget>()
    private val azureBlade = CanvasAzureBladeRenderer(surface)
    private val crimsonBlade = CanvasCrimsonBladeRenderer(surface)
    private val clashBlade = CanvasClashBladeRenderer(surface)

    fun resolve(effectSpec: HaloEffectSpec): CanvasEffectTarget = when (effectSpec) {
        HaloEffectSpec.SpecializedField -> specializedField
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
}

/** Existing Canvas primitives are exposed without leaking them into definitions. */
internal interface CanvasEffectRenderSurface {
    fun drawSpecializedField(canvas: Canvas, frame: CanvasEffectFrame)
    fun drawFullContour(canvas: Canvas, frame: CanvasEffectFrame)
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
}

internal fun interface CanvasEffectTarget {
    fun draw(canvas: Canvas, frame: CanvasEffectFrame)
}

private class CanvasSpecializedFieldRenderer(
    private val surface: CanvasEffectRenderSurface
) : CanvasEffectTarget {
    override fun draw(canvas: Canvas, frame: CanvasEffectFrame) =
        surface.drawSpecializedField(canvas, frame)
}

private class CanvasFullContourRenderer(
    private val surface: CanvasEffectRenderSurface
) : CanvasEffectTarget {
    override fun draw(canvas: Canvas, frame: CanvasEffectFrame) =
        surface.drawFullContour(canvas, frame)
}

private class CanvasLuminousSegmentRenderer(
    private val surface: CanvasEffectRenderSurface,
    private val lengthFraction: Float
) : CanvasEffectTarget {
    override fun draw(canvas: Canvas, frame: CanvasEffectFrame) =
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
    override fun draw(canvas: Canvas, frame: CanvasEffectFrame) = surface.drawAzureBlade(canvas, frame)
}

private class CanvasCrimsonBladeRenderer(
    private val surface: CanvasEffectRenderSurface
) : CanvasEffectTarget {
    override fun draw(canvas: Canvas, frame: CanvasEffectFrame) = surface.drawCrimsonBlade(canvas, frame)
}

private class CanvasClashBladeRenderer(
    private val surface: CanvasEffectRenderSurface
) : CanvasEffectTarget {
    override fun draw(canvas: Canvas, frame: CanvasEffectFrame) = surface.drawClashBlade(canvas, frame)
}
