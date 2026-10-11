package com.yp.luminote.app.rendering.canvas
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.geometry.EdgePathGeometryProvider
import com.yp.luminote.app.effects.geometry.EdgePathGeometry
import com.yp.luminote.app.animation.BladeLifecycle
import com.yp.luminote.app.animation.BladeRetraction
import com.yp.luminote.app.animation.BladeState
import com.yp.luminote.app.animation.DuelBoundaries
import com.yp.luminote.app.animation.DuelLifecycle
import com.yp.luminote.app.animation.DuelRetraction
import com.yp.luminote.app.animation.DuelState
import com.yp.luminote.app.animation.MutableBladePhase
import com.yp.luminote.app.animation.MutableDuelBoundaries
import com.yp.luminote.app.animation.MutableDuelPhase
import com.yp.luminote.app.animation.MutableRetractionSegment

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Edge-only energy-blade surface. Geometry is rebuilt only when [DisplayOutline]
 * changes, allowing any number of independently driven heads to share one contour.
 */
internal class ForceBladesRenderer(
    private val outline: DisplayOutline
) {
    private val pathCache = EdgePathGeometryProvider(outline)
    private val azureHead = EnergyBladeHead(BladePalette.AZURE)
    private val crimsonHead = EnergyBladeHead(BladePalette.CRIMSON)
    // Duel owns four fronts. They share cached surface resources, but their progress is independent.
    private val azureReverseHead = EnergyBladeHead(BladePalette.AZURE)
    private val crimsonReverseHead = EnergyBladeHead(BladePalette.CRIMSON)
    private val clash = BladeClash()
    private val azureLifecycle = MutableBladePhase()
    private val crimsonLifecycle = MutableBladePhase()
    private val duelLifecycle = MutableDuelPhase()
    private val duelBoundaries = MutableDuelBoundaries()

    fun drawAzure(
        canvas: Canvas,
        phase: Float,
        alpha: Int,
        strokeWidth: Float,
        edgeCalibrationPx: Float,
        cornerCalibrationPx: Float,
        cornerShape: Float,
        frameTimeNanos: Long
    ) {
        val geometry = geometryFor(
            strokeWidth,
            outline.opticalInsetPx + edgeCalibrationPx,
            cornerCalibrationPx,
            cornerShape
        ) ?: return
        val save = canvas.save()
        try {
            // Bloom is allowed inward, never beyond the physical display shape.
            canvas.clipPath(outline.path)
            drawAzure(canvas, geometry, phase, alpha, frameTimeNanos)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    fun drawCrimson(canvas: Canvas, phase: Float, alpha: Int, strokeWidth: Float, edgeCalibrationPx: Float, cornerCalibrationPx: Float, cornerShape: Float, frameTimeNanos: Long) {
        val geometry = geometryFor(strokeWidth, outline.opticalInsetPx + edgeCalibrationPx, cornerCalibrationPx, cornerShape) ?: return
        val save = canvas.save()
        try { canvas.clipPath(outline.path); drawCrimson(canvas, geometry, phase, alpha, frameTimeNanos) } finally { canvas.restoreToCount(save) }
    }

    fun drawClash(canvas: Canvas, phase: Float, alpha: Int, strokeWidth: Float, edgeCalibrationPx: Float, cornerCalibrationPx: Float, cornerShape: Float, frameTimeNanos: Long) {
        val geometry = geometryFor(strokeWidth, outline.opticalInsetPx + edgeCalibrationPx, cornerCalibrationPx, cornerShape) ?: return
        val save = canvas.save()
        try { canvas.clipPath(outline.path); drawClash(canvas, geometry, phase, alpha, frameTimeNanos) } finally { canvas.restoreToCount(save) }
    }

    private fun geometryFor(strokeWidth: Float, edgeCalibrationPx: Float, cornerCalibrationPx: Float, cornerShape: Float): EdgePathGeometry? {
        return pathCache.geometryFor(
            strokeWidth = strokeWidth,
            edgeCalibrationPx = edgeCalibrationPx,
            cornerCalibrationPx = cornerCalibrationPx,
            cornerShape = cornerShape,
            extraEnvelopePx = MAX_BLOOM_INSET_PX
        )
    }

    private fun drawAzure(canvas: Canvas, surface: EdgePathGeometry, phase: Float, alpha: Int, frameTimeNanos: Long) {
        val t = phase.coerceIn(0f, 1f)
        val lifecycle = BladeLifecycle.azure(t, azureLifecycle)
        BladeIgnition.draw(canvas, surface, AZURE_ORIGIN, t / DUEL_EXTEND_END, alpha, BladePalette.AZURE)

        when (lifecycle.state) {
            BladeState.EXTENDING -> azureHead.draw(canvas, surface, AZURE_ORIGIN, 1, lifecycle.extent, alpha, 1f, frameTimeNanos)
            BladeState.SEALED -> azureHead.draw(canvas, surface, AZURE_ORIGIN, 1, 1f, alpha, 1f, frameTimeNanos)
            BladeState.HOLDING -> azureHead.draw(canvas, surface, AZURE_ORIGIN, 1, 1f, alpha, 1f, frameTimeNanos)
            BladeState.RETRACTING -> azureHead.drawRetraction(canvas, surface, AZURE_ORIGIN, 1, lifecycle.localPhase, alpha, 1f, frameTimeNanos)
            BladeState.FINISHED -> BladeFinalDischarge.draw(canvas, surface, AZURE_ORIGIN, alpha, BladePalette.AZURE)
        }
    }

    private fun drawCrimson(canvas: Canvas, surface: EdgePathGeometry, phase: Float, alpha: Int, frameTimeNanos: Long) {
        val t = phase.coerceIn(0f, 1f)
        val lifecycle = BladeLifecycle.crimson(t, crimsonLifecycle)
        val flicker = if (t < 0.13f) 0.55f + 0.45f * abs(sin(t * PI.toFloat() * 45f)) else 1f

        BladeIgnition.draw(canvas, surface, CRIMSON_ORIGIN, t / DUEL_EXTEND_END, alpha, BladePalette.CRIMSON)

        when (lifecycle.state) {
            BladeState.EXTENDING -> crimsonHead.draw(canvas, surface, CRIMSON_ORIGIN, 1, lifecycle.extent, alpha, flicker, frameTimeNanos)
            BladeState.SEALED -> crimsonHead.draw(canvas, surface, CRIMSON_ORIGIN, 1, 1f, alpha, 1f, frameTimeNanos)
            BladeState.HOLDING -> crimsonHead.draw(canvas, surface, CRIMSON_ORIGIN, 1, 1f, alpha, 1f, frameTimeNanos)
            BladeState.RETRACTING -> crimsonHead.drawRetraction(canvas, surface, CRIMSON_ORIGIN, 1, lifecycle.localPhase, alpha, 1f, frameTimeNanos)
            BladeState.FINISHED -> BladeFinalDischarge.draw(canvas, surface, CRIMSON_ORIGIN, alpha, BladePalette.CRIMSON)
        }
    }

    private fun drawClash(canvas: Canvas, surface: EdgePathGeometry, phase: Float, alpha: Int, frameTimeNanos: Long) {
        val t = phase.coerceIn(0f, 1f)
        if (t < DUEL_EXTEND_END) {
            val approach = easeOut(t / DUEL_EXTEND_END) * CLASH_APPROACH
            azureHead.draw(canvas, surface, AZURE_ORIGIN, 1, approach, alpha, 1f, frameTimeNanos)
            azureReverseHead.draw(canvas, surface, AZURE_ORIGIN, -1, approach, alpha, 1f, frameTimeNanos)
            crimsonHead.draw(canvas, surface, CRIMSON_ORIGIN, 1, approach, alpha, 1f, frameTimeNanos)
            crimsonReverseHead.draw(canvas, surface, CRIMSON_ORIGIN, -1, approach, alpha, 1f, frameTimeNanos)
            BladeIgnition.draw(canvas, surface, AZURE_ORIGIN, t / 0.12f, alpha, BladePalette.AZURE)
            BladeIgnition.draw(canvas, surface, CRIMSON_ORIGIN, t / 0.12f, alpha, BladePalette.CRIMSON)
            return
        }

        val duel = DuelLifecycle.at(t, duelLifecycle)
        if (duel.state == DuelState.RETRACTING) {
            drawDuelRetraction(canvas, surface, duel.localPhase, alpha, frameTimeNanos)
            return
        }

        DuelBoundaries.fromAzureShare(0.5f, duelBoundaries)
        val boundaries = duelBoundaries

        azureHead.drawRegion(canvas, surface, boundaries.left, boundaries.right, 1, alpha, 1f, frameTimeNanos)
        crimsonHead.drawRegion(canvas, surface, boundaries.right, boundaries.left, 1, alpha, 1f, frameTimeNanos)

        if (duel.state == DuelState.CLASHING) {
            clash.drawImpact(canvas, surface, boundaries.left, duel.localPhase, alpha)
            clash.drawImpact(canvas, surface, boundaries.right, duel.localPhase, alpha)
        }
    }

    private fun drawDuelRetraction(canvas: Canvas, surface: EdgePathGeometry, progress: Float, alpha: Int, frameTimeNanos: Long) {
        val halfTerritory = 0.25f
        if (progress >= 0.98f) {
            BladeFinalDischarge.draw(canvas, surface, AZURE_ORIGIN, alpha, BladePalette.AZURE)
            BladeFinalDischarge.draw(canvas, surface, CRIMSON_ORIGIN, alpha, BladePalette.CRIMSON)
            return
        }
        // Each head begins at its collision endpoint and consumes its own territory toward origin.
        azureHead.drawRetractionToOrigin(canvas, surface, AZURE_ORIGIN, AZURE_ORIGIN + halfTerritory, -1, progress, alpha, frameTimeNanos)
        azureReverseHead.drawRetractionToOrigin(canvas, surface, AZURE_ORIGIN, AZURE_ORIGIN - halfTerritory, 1, progress, alpha, frameTimeNanos)
        crimsonHead.drawRetractionToOrigin(canvas, surface, CRIMSON_ORIGIN, CRIMSON_ORIGIN + halfTerritory, -1, progress, alpha, frameTimeNanos)
        crimsonReverseHead.drawRetractionToOrigin(canvas, surface, CRIMSON_ORIGIN, CRIMSON_ORIGIN - halfTerritory, 1, progress, alpha, frameTimeNanos)
    }

    private fun easeOut(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return 1f - (1f - x) * (1f - x)
    }

    private fun easeIn(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return x * x
    }

    private companion object {
        /** Preserved original Force-Blades bloom containment margin. */
        const val MAX_BLOOM_INSET_PX = 18f
        const val AZURE_ORIGIN = 0.08f
        const val CRIMSON_ORIGIN = 0.58f
        const val CLASH_APPROACH = 0.25f
        const val DUEL_EXTEND_END = 0.30f
    }
}

internal enum class BladePalette(val body: Int, val bloom: Int) {
    AZURE(0xFF0874FF.toInt(), 0xFF004CFF.toInt()),
    CRIMSON(0xFFFF0808.toInt(), 0xFFFF0000.toInt())
}

/** Per-head state and cached drawing resources. No paint/path allocation happens in draw. */
internal class EnergyBladeHead(private val palette: BladePalette) {
    private val trail = EnergyBladeTrail(palette)
    private val front = EnergyBladeFront(palette)
    private val retractionSegment = MutableRetractionSegment()

    fun draw(canvas: Canvas, surface: EdgePathGeometry, origin: Float, direction: Int, extent: Float, alpha: Int, energy: Float, frameTimeNanos: Long) {
        trail.draw(canvas, surface, origin, direction, extent, alpha, energy, frameTimeNanos)
    }

    fun drawRegion(canvas: Canvas, surface: EdgePathGeometry, start: Float, end: Float, direction: Int, alpha: Int, energy: Float, frameTimeNanos: Long) {
        val extent = if (direction > 0) surface.normalized(end - start) else surface.normalized(start - end)
        trail.draw(canvas, surface, start, direction, extent, alpha, energy, frameTimeNanos)
    }

    fun drawFront(canvas: Canvas, surface: EdgePathGeometry, origin: Float, direction: Int, extent: Float, alpha: Int, energy: Float, frameTimeNanos: Long) {
        front.draw(canvas, surface, origin + direction * extent, direction, alpha, energy, frameTimeNanos)
    }

    /** Consuming head continues in the original direction; no trail remains anchored at ignition. */
    fun drawRetraction(
        canvas: Canvas,
        surface: EdgePathGeometry,
        origin: Float,
        direction: Int,
        progress: Float,
        alpha: Int,
        energy: Float,
        frameTimeNanos: Long,
        maximumExtent: Float = 1f
    ) {
        val segment = BladeRetraction.remainingSegment(origin, direction, progress, maximumExtent, retractionSegment)
        if (direction > 0) {
            trail.drawSegment(canvas, surface, segment.start, segment.length, alpha, energy, frameTimeNanos)
        } else {
            trail.draw(canvas, surface, segment.start, -1, segment.length, alpha, energy, frameTimeNanos)
        }
        front.draw(canvas, surface, segment.head, direction, alpha, energy, frameTimeNanos)
    }

    fun drawRetractionToOrigin(
        canvas: Canvas,
        surface: EdgePathGeometry,
        origin: Float,
        collisionBoundary: Float,
        directionTowardOrigin: Int,
        progress: Float,
        alpha: Int,
        frameTimeNanos: Long
    ) {
        val segment = DuelRetraction.remainingInterval(origin, collisionBoundary, progress, directionTowardOrigin, retractionSegment)
        trail.drawSegment(canvas, surface, segment.start, segment.length, alpha, 1f, frameTimeNanos)
        front.draw(canvas, surface, segment.head, directionTowardOrigin, alpha, 1f, frameTimeNanos)
    }
}

internal class EnergyBladeTrail(private val palette: BladePalette) {
    private val glow = BladeGlow(palette)
    private val bodyPaint = bladePaint()
    private val corePaint = bladePaint()
    private val segmentPath = Path()

    fun draw(canvas: Canvas, surface: EdgePathGeometry, origin: Float, direction: Int, extent: Float, alpha: Int, energy: Float, frameTimeNanos: Long) {
        if (extent <= 0f) return
        val start = if (direction > 0) origin else origin - extent
        drawSegment(canvas, surface, start, extent, alpha, energy, frameTimeNanos)
    }

    fun drawSegment(canvas: Canvas, surface: EdgePathGeometry, start: Float, extent: Float, alpha: Int, energy: Float, frameTimeNanos: Long) {
        if (extent <= 0f) return
        val path = surface.wrappedSegment(start, extent, segmentPath)
        glow.draw(canvas, path, alpha, energy, frameTimeNanos)
        bodyPaint.strokeWidth = BODY_WIDTH
        bodyPaint.color = palette.body
        bodyPaint.alpha = scaledAlpha(alpha, energy)
        canvas.drawPath(path, bodyPaint)
        corePaint.strokeWidth = CORE_WIDTH
        corePaint.color = HOT_CORE
        corePaint.alpha = scaledAlpha(alpha, energy)
        canvas.drawPath(path, corePaint)
    }
}

internal class EnergyBladeFront(private val palette: BladePalette) {
    private val glow = BladeGlow(palette)
    private val bodyPaint = bladePaint()
    private val corePaint = bladePaint()
    private val segmentPath = Path()

    fun draw(
        canvas: Canvas,
        surface: EdgePathGeometry,
        front: Float,
        direction: Int,
        alpha: Int,
        energy: Float,
        frameTimeNanos: Long
    ) {
        val start = if (direction > 0) front - FRONT_LENGTH else front
        val path = surface.wrappedSegment(start, FRONT_LENGTH, segmentPath)

        glow.draw(canvas, path, alpha, energy, frameTimeNanos)

        bodyPaint.strokeWidth = BODY_WIDTH
        bodyPaint.color = palette.body
        bodyPaint.alpha = scaledAlpha(alpha, energy)
        canvas.drawPath(path, bodyPaint)

        corePaint.strokeWidth = CORE_WIDTH
        corePaint.color = HOT_CORE
        corePaint.alpha = scaledAlpha(alpha, energy)
        canvas.drawPath(path, corePaint)
    }
}

internal class BladeGlow(private val palette: BladePalette) {
    private val outerPaint = bladePaint()
    private val middlePaint = bladePaint()
    private val innerPaint = bladePaint()

    fun draw(canvas: Canvas, path: Path, alpha: Int, energy: Float, frameTimeNanos: Long) {
        val time = frameTimeNanos / 1_000_000_000f
        val flicker =
            0.76f +
                    0.22f * sin(time * PI.toFloat() * 13f) +
                    0.14f * sin(time * PI.toFloat() * 29f)

        outerPaint.strokeWidth = OUTER_WIDTH * (0.89f + flicker * 0.15f)
        outerPaint.color = palette.bloom
        outerPaint.alpha = scaledAlpha(alpha, energy * 0.16f * flicker)
        canvas.drawPath(path, outerPaint)

        middlePaint.strokeWidth = 22f * (0.90f + flicker * 0.14f)
        middlePaint.color = palette.bloom
        middlePaint.alpha = scaledAlpha(alpha, energy * 0.24f * flicker)
        canvas.drawPath(path, middlePaint)

        innerPaint.strokeWidth = INNER_WIDTH * (0.91f + flicker * 0.12f)
        innerPaint.color = palette.body
        innerPaint.alpha = scaledAlpha(alpha, energy * 0.40f * flicker)
        canvas.drawPath(path, innerPaint)
    }
}

internal object BladeIgnition {
    private val bloomPaint = bladePaint().apply { style = Paint.Style.FILL }
    private val corePaint = bladePaint().apply { style = Paint.Style.FILL }
    private val position = FloatArray(2)
    private val tangent = FloatArray(2)

    fun draw(canvas: Canvas, surface: EdgePathGeometry, origin: Float, phase: Float, alpha: Int, palette: BladePalette) {
        val p = phase.coerceIn(0f, 1f)
        val charge = (p / 0.32f).coerceIn(0f, 1f)
        val release = ((p - 0.32f) / 0.68f).coerceIn(0f, 1f)

        val compression = 1f - release
        val bloomStrength = if (p < 0.32f) charge else compression
        val coreStrength = if (p < 0.32f) charge else compression * compression

        if (bloomStrength <= 0f) return

        val point = surface.pointAt(origin, position, tangent)

        bloomPaint.color = palette.bloom
        bloomPaint.alpha = scaledAlpha(alpha, bloomStrength * 0.38f)
        canvas.drawCircle(point[0], point[1], IGNITION_BLOOM_RADIUS * (0.55f + 0.70f * bloomStrength), bloomPaint)

        corePaint.color = HOT_CORE
        corePaint.alpha = scaledAlpha(alpha, coreStrength)
        canvas.drawCircle(point[0], point[1], IGNITION_CORE_RADIUS * (0.65f + 0.35f * coreStrength), corePaint)
    }
}


internal object BladeSeal {
    private val paint = bladePaint().apply { style = Paint.Style.FILL }
    private val position = FloatArray(2)
    private val tangent = FloatArray(2)

    fun draw(canvas: Canvas, surface: EdgePathGeometry, origin: Float, phase: Float, alpha: Int, palette: BladePalette) {
        val strength = 1f - phase.coerceIn(0f, 1f)
        val point = surface.pointAt(origin, position, tangent)
        paint.color = palette.body
        paint.alpha = scaledAlpha(alpha, strength)
        canvas.drawCircle(point[0], point[1], 8f + 16f * strength, paint)
    }
}

internal object BladeFinalDischarge {
    private val paint = bladePaint().apply { style = Paint.Style.FILL }
    private val position = FloatArray(2)
    private val tangent = FloatArray(2)

    fun draw(canvas: Canvas, surface: EdgePathGeometry, origin: Float, alpha: Int, palette: BladePalette) {
        val point = surface.pointAt(origin, position, tangent)
        paint.color = palette.body
        paint.alpha = scaledAlpha(alpha, 0.5f)
        canvas.drawCircle(point[0], point[1], 4f, paint)
    }
}

internal object BladeEmitter {
    private val paint = bladePaint().apply { style = Paint.Style.FILL }
    private val position = FloatArray(2)
    private val tangent = FloatArray(2)

    fun draw(canvas: Canvas, surface: EdgePathGeometry, origin: Float, alpha: Int, palette: BladePalette) {
        val point = surface.pointAt(origin, position, tangent)
        paint.color = palette.body
        paint.alpha = alpha
        canvas.drawCircle(point[0], point[1], 8f, paint)
    }
}


/** Local edge-bound collision pulse plus a fixed mutable spark pool. */
internal class BladeClash {
    private val corePaint = bladePaint()
    private val azurePulsePaint = bladePaint()
    private val crimsonPulsePaint = bladePaint()
    private val azureSparkPaint = bladePaint().apply { style = Paint.Style.FILL; color = BladePalette.AZURE.body }
    private val crimsonSparkPaint = bladePaint().apply { style = Paint.Style.FILL; color = BladePalette.CRIMSON.body }
    private val sparks = Array(SPARK_COUNT) { EnergySpark(it) }
    private val seamPath = Path()
    private val azurePath = Path()
    private val crimsonPath = Path()
    private val position = FloatArray(2)
    private val tangent = FloatArray(2)

    fun draw(canvas: Canvas, surface: EdgePathGeometry, boundary: Float, phase: Float, alpha: Int, activity: Float = 1f) {
        val p = phase.coerceIn(0f, 1f)
        val strength = (1f - p) * activity
        drawPulse(canvas, surface, boundary, p, alpha, strength, PULSE_TRAVEL)
        drawSparks(canvas, surface, boundary, p, alpha, activity)
    }

    fun drawImpact(canvas: Canvas, surface: EdgePathGeometry, boundary: Float, phase: Float, alpha: Int) {
        val p = phase.coerceIn(0f, 1f)
        val impact = if (p < 0.28f) { p / 0.28f } else { 1f - (p - 0.28f) / 0.72f }

        val strength = impact.coerceIn(0f, 1f)
        if (strength <= 0f) return

        val point = surface.pointAt(boundary, position, tangent)

        corePaint.style = Paint.Style.FILL
        corePaint.color = HOT_CORE
        corePaint.alpha = scaledAlpha(alpha, strength)
        canvas.drawCircle(point[0], point[1], 4f + 10f * strength, corePaint)
        corePaint.style = Paint.Style.STROKE

        drawSparks(canvas, surface, boundary, p, alpha, strength)
    }

    private fun drawPulse(canvas: Canvas, surface: EdgePathGeometry, boundary: Float, phase: Float, alpha: Int, strength: Float, travel: Float) {
        val progress = 1f - (1f - phase) * (1f - phase)
        val offset = progress * travel
        val pulseStrength = (strength * 1.8f).coerceAtMost(1f)
        val seam = surface.wrappedSegment(boundary - SEAM_LENGTH / 2f, SEAM_LENGTH, seamPath)

        corePaint.strokeWidth = INNER_WIDTH
        corePaint.color = HOT_CORE
        corePaint.alpha = scaledAlpha(alpha, pulseStrength)
        canvas.drawPath(seam, corePaint)

        val azure = surface.wrappedSegment(boundary - offset - PULSE_LENGTH, PULSE_LENGTH, azurePath)
        azurePulsePaint.strokeWidth = INNER_WIDTH
        azurePulsePaint.color = BladePalette.AZURE.body
        azurePulsePaint.alpha = scaledAlpha(alpha, pulseStrength)
        canvas.drawPath(azure, azurePulsePaint)

        val crimson = surface.wrappedSegment(boundary + offset, PULSE_LENGTH, crimsonPath)
        crimsonPulsePaint.strokeWidth = INNER_WIDTH
        crimsonPulsePaint.color = BladePalette.CRIMSON.body
        crimsonPulsePaint.alpha = scaledAlpha(alpha, pulseStrength)
        canvas.drawPath(crimson, crimsonPulsePaint)
    }

    private fun drawSparks(canvas: Canvas, surface: EdgePathGeometry, boundary: Float, phase: Float, alpha: Int, activity: Float) {
        for (spark in sparks) {
            spark.update(boundary, phase, activity)
            val point = surface.pointAt(spark.fraction, position, tangent)
            val paint = if (spark.azure) azureSparkPaint else crimsonSparkPaint
            paint.alpha = scaledAlpha(alpha, spark.alpha)
            canvas.drawCircle(point[0], point[1], spark.radius, paint)
        }
    }

    companion object {
        private const val SEAM_LENGTH = 0.010f
        private const val PULSE_LENGTH = 0.035f
        private const val PULSE_TRAVEL = 0.10f
        private const val FINAL_PULSE_TRAVEL = 0.18f

        /** No RNG: stable, local motion with a different phase for each collision boundary. */
        fun jitter(time: Float, boundaryIndex: Int, strength: Float): Float =
            sin(time * PI.toFloat() * (29f + boundaryIndex * 4f) + boundaryIndex * 1.7f) * 0.006f * strength
    }
}

/** Mutable fixed-pool particle; instances are created once with the renderer. */
internal class EnergySpark(index: Int) {
    private val direction = if (index and 1 == 0) 1f else -1f
    private val offset = (index + 1) / 18f
    val azure = index and 1 == 0
    var fraction = 0f
        private set
    var alpha = 0f
        private set
    var radius = 1f
        private set

    fun update(origin: Float, phase: Float, activity: Float = 1f) {
        val life = (1f - phase.coerceIn(0f, 1f)) * activity
        fraction = origin + direction * offset * (1f - life * 0.35f)
        alpha = life * (0.35f + offset)
        radius = 1.5f + offset * 4f
    }
}

private fun bladePaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}

private fun scaledAlpha(alpha: Int, scale: Float): Int = (alpha * scale).roundToInt().coerceIn(0, 255)

private const val HOT_CORE = 0xFFF8FCFF.toInt()
private const val BODY_WIDTH = 7.5f
private const val CORE_WIDTH = 2.5f
private const val OUTER_WIDTH = 29f
private const val INNER_WIDTH = 16f
private const val FRONT_LENGTH = 0.035f
private const val IGNITION_BLOOM_RADIUS = 24f
private const val IGNITION_CORE_RADIUS = 5f
private const val SPARK_COUNT = 12
private const val AZURE_DUEL_ORIGIN = 0.08f
