package com.yp.luminote.app.rendering.gpu

import android.graphics.BlendMode
import android.graphics.Mesh
import android.graphics.MeshSpecification
import android.graphics.Paint
import android.graphics.RectF
import com.yp.luminote.app.animation.definitions.BladeVariant
import com.yp.luminote.app.animation.definitions.HaloEffectSpec
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.geometry.EdgePathGeometry
import com.yp.luminote.app.effects.geometry.EdgePathGeometryProvider
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.effects.model.HaloRenderMode
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.rendering.api.HaloRenderBackend
import com.yp.luminote.app.rendering.api.HaloRenderTarget
import com.yp.luminote.app.rendering.api.RenderFrame
import com.yp.luminote.app.rendering.api.RenderMode
import com.yp.luminote.app.rendering.api.RenderOutcome
import com.yp.luminote.app.rendering.canvas.CanvasRenderTarget
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Hardware-Canvas renderer. The mesh specification contains AGSL vertex and fragment programs;
 * it never delegates animation work to the Canvas renderer and retains no host target.
 */
internal class GpuRenderBackend(
    config: HaloConfig,
    private val outline: DisplayOutline
) : HaloRenderBackend {
    private var config = config.sanitized()
    private val geometryProvider = EdgePathGeometryProvider(outline)
    private val composer = GpuRibbonComposer(outline, geometryProvider)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var disposed = false

    override fun updateConfig(config: HaloConfig) {
        if (!disposed) this.config = config.sanitized()
    }

    override fun invalidateSurface() {
        if (!disposed) composer.invalidate()
    }

    override fun render(target: HaloRenderTarget, frame: RenderFrame): RenderOutcome {
        if (disposed) return RenderOutcome.Unsupported("GPU backend is disposed")
        if (!gpuCanFaithfullyRender(config, frame.mode)) {
            return RenderOutcome.FallbackToCanvas(
                UnsupportedOperationException("GPU mesh path does not implement this effect's Canvas semantics")
            )
        }
        val canvas = (target as? CanvasRenderTarget)?.canvas
            ?: return RenderOutcome.Unsupported("GPU backend requires a CanvasRenderTarget")
        if (!canvas.isHardwareAccelerated) {
            return RenderOutcome.FallbackToCanvas(IllegalStateException("GPU backend requires a hardware Canvas"))
        }
        return try {
            val alpha = if (frame.mode == RenderMode.STATIC_CALIBRATION) {
                (config.intensity * 255f).roundToInt().coerceIn(0, 255)
            } else {
                (config.intensity * frame.animationProgress.coerceIn(0f, 1f) * 255f)
                    .roundToInt().coerceIn(0, 255)
            }
            if (alpha == 0) return RenderOutcome.Rendered
            paint.color = (alpha shl 24) or (colorFor(frame.effectPhase) and 0x00FFFFFF)
            composer.draw(canvas, config, frame, paint)
            RenderOutcome.Rendered
        } catch (failure: RuntimeException) {
            RenderOutcome.FallbackToCanvas(failure)
        }
    }

    private fun colorFor(phase: Float): Int {
        val palette = config.palette
        if (palette.size < 2) return config.color
        val normalized = ((phase % 1f) + 1f) % 1f
        val scaled = normalized * palette.size
        val from = palette[scaled.toInt() % palette.size]
        val to = palette[(scaled.toInt() + 1) % palette.size]
        val t = scaled - scaled.toInt()
        fun component(color: Int, shift: Int) = (color shr shift) and 0xFF
        return (component(from, 16) + ((component(to, 16) - component(from, 16)) * t).roundToInt() shl 16) or
            (component(from, 8) + ((component(to, 8) - component(from, 8)) * t).roundToInt() shl 8) or
            (component(from, 0) + ((component(to, 0) - component(from, 0)) * t).roundToInt())
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        composer.dispose()
    }
}

/**
 * The mesh implementation intentionally has a narrow admission policy. Moving-segment optics,
 * Light Impulse, Supernova, blades, gradients and calibration diagnostics have richer established
 * Canvas semantics, so they fail over before a GPU draw instead of approximating them.
 */
internal fun gpuCanFaithfullyRender(config: HaloConfig, mode: RenderMode): Boolean =
    mode == RenderMode.ANIMATION &&
        config.renderMode != HaloRenderMode.LIGHT_IMPULSE &&
        config.effectPlan == HaloEffectSpec.FullContour &&
        config.colorMode == HaloColorMode.SOLID &&
        config.palette.size < 2

/** Derived, bounded mesh cache. DisplayOutline remains the authoritative geometry owner. */
private class GpuRibbonComposer(
    private val outline: DisplayOutline,
    private val geometryProvider: EdgePathGeometryProvider
) {
    private var lastKey: RibbonKey? = null
    private var lastMesh: Mesh? = null

    fun invalidate() {
        lastKey = null
        lastMesh = null
    }

    fun dispose() = invalidate()

    fun draw(canvas: android.graphics.Canvas, config: HaloConfig, frame: RenderFrame, paint: Paint) {
        val stroke = 4f + config.thickness * 10f
        val envelope = if ((config.effectPlan as? HaloEffectSpec.Blade) != null) 18f else 0f
        val geometry = geometryProvider.geometryFor(
            strokeWidth = stroke,
            edgeCalibrationPx = outline.opticalInsetPx + outline.dpToPx(config.edgeCalibrationDp),
            cornerCalibrationPx = outline.dpToPx(config.cornerCalibrationDp),
            cornerShape = config.cornerShape,
            extraEnvelopePx = envelope
        ) ?: return
        for (pass in passesFor(config.effectPlan, frame)) {
            val key = RibbonKey(outline.version, stroke, config.edgeCalibrationDp, config.cornerCalibrationDp,
                config.cornerShape, envelope, pass.start, pass.length, pass.widthScale)
            val mesh = if (key == lastKey) lastMesh else createMesh(geometry, stroke * pass.widthScale, pass.start, pass.length).also {
                lastKey = key
                lastMesh = it
            }
            if (mesh != null) canvas.drawMesh(mesh, BlendMode.SRC_OVER, paint)
        }
    }

    private fun passesFor(spec: HaloEffectSpec, frame: RenderFrame): List<RibbonPass> = when (spec) {
        HaloEffectSpec.FullContour, HaloEffectSpec.Supernova, HaloEffectSpec.SpecializedField -> listOf(RibbonPass(0f, 1f, 1f))
        is HaloEffectSpec.LuminousSegment -> listOf(RibbonPass(frame.effectPhase, spec.lengthFraction.coerceIn(0.01f, 1f), 1f))
        is HaloEffectSpec.Blade -> when (spec.variant) {
            BladeVariant.AZURE -> listOf(RibbonPass(frame.effectPhase, .18f, 1.25f))
            BladeVariant.CRIMSON -> listOf(RibbonPass(-frame.effectPhase, .18f, 1.25f))
            BladeVariant.CLASH -> listOf(RibbonPass(frame.effectPhase, .16f, 1.25f), RibbonPass(-frame.effectPhase, .16f, 1.25f))
        }
    }

    private fun createMesh(geometry: EdgePathGeometry, width: Float, start: Float, length: Float): Mesh? {
        val count = ((geometry.length * length.coerceIn(0f, 1f)) / 12f).roundToInt().coerceIn(8, 192)
        val vertices = ByteBuffer.allocateDirect((count + 1) * 2 * 3 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        val position = FloatArray(2)
        val tangent = FloatArray(2)
        for (index in 0..count) {
            val fraction = start + length * index.toFloat() / count
            geometry.pointAt(fraction, position, tangent)
            val magnitude = kotlin.math.sqrt(tangent[0] * tangent[0] + tangent[1] * tangent[1]).coerceAtLeast(.001f)
            val nx = -tangent[1] / magnitude * width / 2f
            val ny = tangent[0] / magnitude * width / 2f
            // position (float2), coverage (float). The AGSL fragment shader premultiplies coverage.
            vertices.putFloat(position[0] + nx).putFloat(position[1] + ny).putFloat(1f)
            vertices.putFloat(position[0] - nx).putFloat(position[1] - ny).putFloat(1f)
        }
        vertices.rewind()
        return Mesh(meshSpecification, Mesh.TRIANGLE_STRIP, vertices, (count + 1) * 2, geometry.bounds)
    }

    private data class RibbonPass(val start: Float, val length: Float, val widthScale: Float)
    private data class RibbonKey(
        val outlineVersion: Int, val stroke: Float, val edge: Float, val corner: Float, val cornerShape: Float,
        val envelope: Float, val start: Float, val length: Float, val widthScale: Float
    )

    private companion object {
        private val meshSpecification: MeshSpecification by lazy {
            MeshSpecification.make(
                arrayOf(
                    MeshSpecification.Attribute(MeshSpecification.TYPE_FLOAT2, 0, "position"),
                    MeshSpecification.Attribute(MeshSpecification.TYPE_FLOAT, 8, "coverage")
                ),
                12,
                arrayOf(MeshSpecification.Varying(MeshSpecification.TYPE_FLOAT, "coverage")),
                "Varyings main(const Attributes attributes) { Varyings varyings; varyings.position = attributes.position; varyings.coverage = attributes.coverage; return varyings; }",
                "float2 main(const Varyings varyings, out float4 color) { float a = clamp(varyings.coverage, 0.0, 1.0); color = float4(a, a, a, a); return varyings.position; }"
            )
        }
    }
}
