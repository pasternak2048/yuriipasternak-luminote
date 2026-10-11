package com.yp.luminote.app.rendering.canvas

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.yp.luminote.app.effects.geometry.EdgeSegmentRenderer
import kotlin.math.roundToInt

/**
 * Standalone Supernova implementation.
 *
 * Its ignition field intentionally duplicates Light Impulse's original top bloom: the same
 * physical top-centre lookup, smoothstep birth, 40 sample profile, energy, width, and butt cap.
 * It never creates a travelling field; after that parity ramp, its own finite fade clears it.
 */
internal interface SupernovaRenderSurface {
    val outlinePath: Path
    val corePaint: Paint
    val renderStrokeWidth: Float
    fun geometry(): StyleGeometry?
    fun preparePaint(alpha: Int, effectPhase: Float, gradientPhase: Float)
}

internal class SupernovaRenderer(private val surface: SupernovaRenderSurface) {
    private val segmentPath = Path()
    private val position = FloatArray(2)
    private var endpointGeometry: StyleGeometry? = null
    private var topFraction = 0f

    fun draw(canvas: Canvas, phase: Float, alpha: Int, gradientPhase: Float) {
        if (surface.outlinePath.isEmpty || alpha <= 0) return
        val timeline = supernovaCycleProgress(phase) ?: return
        val geometry = surface.geometry() ?: return
        val intensity = supernovaIntensity(timeline)
        if (intensity <= 0f) return
        val origin = topFractionFor(geometry) * geometry.length
        val radius = geometry.length *
            (SUPERNOVA_BASE_RADIUS_FRACTION + intensity * SUPERNOVA_GROWN_RADIUS_FRACTION) *
            SUPERNOVA_HORIZONTAL_EXTENT_FACTOR
        if (radius <= 0f) return

        val savedCap = surface.corePaint.strokeCap
        val savedWidth = surface.corePaint.strokeWidth
        val save = canvas.save()
        try {
            canvas.clipPath(surface.outlinePath)
            surface.preparePaint(alpha, phase, gradientPhase)
            // Exact containment behavior of the copied ignition bloom.
            surface.corePaint.strokeCap = Paint.Cap.BUTT
            val sampleLength = radius * 2f / SUPERNOVA_SAMPLES
            var sample = 0
            while (sample < SUPERNOVA_SAMPLES) {
                val normalized = ((sample + 0.5f) / SUPERNOVA_SAMPLES) * 2f - 1f
                val energy = (1f - normalized * normalized).coerceAtLeast(0f)
                val softenedEnergy = energy * energy
                drawSample(
                    canvas = canvas,
                    geometry = geometry,
                    center = origin + normalized * radius,
                    length = sampleLength,
                    alpha = (alpha * intensity * softenedEnergy).roundToInt(),
                    strokeWidth = surface.renderStrokeWidth *
                        (0.70f + 1.20f * softenedEnergy)
                )
                sample++
            }
        } finally {
            surface.corePaint.strokeCap = savedCap
            surface.corePaint.strokeWidth = savedWidth
            canvas.restoreToCount(save)
        }
    }

    private fun drawSample(
        canvas: Canvas,
        geometry: StyleGeometry,
        center: Float,
        length: Float,
        alpha: Int,
        strokeWidth: Float
    ) {
        if (alpha <= 0 || length < EdgeSegmentRenderer.MIN_RENDERABLE_SEGMENT_PX) return
        surface.corePaint.alpha = alpha.coerceIn(0, 255)
        surface.corePaint.strokeWidth = strokeWidth
        if (EdgeSegmentRenderer.append(
                geometry.measure,
                geometry.length,
                center - length / 2f,
                length,
                segmentPath,
                allowFullContour = false
            )
        ) {
            canvas.drawPath(segmentPath, surface.corePaint)
        }
    }

    private fun topFractionFor(geometry: StyleGeometry): Float {
        if (endpointGeometry === geometry) return topFraction
        val bounds = geometry.bounds
        var nearestFraction = 0f
        var nearestDistance = Float.MAX_VALUE
        for (sample in 0..SUPERNOVA_ENDPOINT_SAMPLES) {
            val fraction = sample.toFloat() / SUPERNOVA_ENDPOINT_SAMPLES
            geometry.measure.getPosTan(geometry.length * fraction, position, null)
            val deltaX = position[0] - bounds.centerX()
            val deltaY = position[1] - bounds.top
            val distance = deltaX * deltaX + deltaY * deltaY
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestFraction = fraction
            }
        }
        endpointGeometry = geometry
        return nearestFraction.also { topFraction = it }
    }
}

/** Same smoothstep ignition ramp, followed by Supernova's independently owned terminal fade. */
internal fun supernovaIntensity(progress: Float): Float = when {
    !progress.isFinite() -> 0f
    progress < SUPERNOVA_IGNITION_END -> supernovaSmoothStep(progress / SUPERNOVA_IGNITION_END)
    else -> 1f - supernovaSmoothStep(
        (progress - SUPERNOVA_IGNITION_END) / (1f - SUPERNOVA_IGNITION_END)
    )
}

/** Finite playback accumulates phase per repeat; Supernova owns one pulse per unit interval. */
internal fun supernovaCycleProgress(phase: Float): Float? {
    if (!phase.isFinite()) return null
    return (phase % 1f + 1f) % 1f
}

private fun supernovaSmoothStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private const val SUPERNOVA_IGNITION_END = 0.09f
private const val SUPERNOVA_BASE_RADIUS_FRACTION = 0.012f
private const val SUPERNOVA_GROWN_RADIUS_FRACTION = 0.026f
private const val SUPERNOVA_HORIZONTAL_EXTENT_FACTOR = 1.35f
private const val SUPERNOVA_SAMPLES = 40
private const val SUPERNOVA_ENDPOINT_SAMPLES = 960
