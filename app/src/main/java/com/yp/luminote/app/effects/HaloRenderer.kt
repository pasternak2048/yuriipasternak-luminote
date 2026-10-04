package com.yp.luminote.app.effects

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.SweepGradient
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.data.settings.HaloFrame
import com.yp.luminote.app.data.settings.HaloMotion
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal enum class LightImpulsePhase { IGNITION, TRAVEL, CONVERGE, FADE }

/** Timeline is deliberately independent from the normal finite-animation envelope. */
internal fun lightImpulsePhase(progress: Float): LightImpulsePhase = when {
    progress < LIGHT_IGNITION_END -> LightImpulsePhase.IGNITION
    progress < LIGHT_TRAVEL_END -> LightImpulsePhase.TRAVEL
    progress < LIGHT_CONVERGE_END -> LightImpulsePhase.CONVERGE
    else -> LightImpulsePhase.FADE
}

internal const val LIGHT_IMPULSE_DURATION_SECONDS = 2.2f
private const val LIGHT_IGNITION_END = 0.09f
private const val LIGHT_TRAVEL_END = 0.80f
private const val LIGHT_CONVERGE_END = 0.88f
private const val LIGHT_TAIL_FRACTION = 0.24f
private const val LIGHT_CORE_FRACTION = 0.022f
private const val LIGHT_TAIL_SAMPLES = 24
private const val LIGHT_CORE_SAMPLES = 18
private const val LIGHT_BLOOM_SAMPLES = 24

internal fun lightImpulseTravelProgress(progress: Float): Float =
    lightImpulseSmoothStep(
        (progress - LIGHT_IGNITION_END) /
                (LIGHT_TRAVEL_END - LIGHT_IGNITION_END)
    )

/** Carries the single source through the exact ignition-to-split boundary. */
internal fun lightImpulseOriginGlow(progress: Float): Float = when {
    progress <= LIGHT_IGNITION_END -> lightImpulseSmoothStep(progress / LIGHT_IGNITION_END)
    progress < LIGHT_TRAVEL_END -> {
        val release = (1f - lightImpulseTravelProgress(progress) * 6f).coerceIn(0f, 1f)
        release * release
    }
    else -> 0f
}

/** Builds the reunion point before the cores arrive, avoiding a phase-boundary flash. */
internal fun lightImpulseBottomBloom(progress: Float): Float = when {
    progress < LIGHT_TRAVEL_END -> {
        val readiness = lightImpulseSmoothStep(
            (lightImpulseTravelProgress(progress) - 0.80f) / 0.20f
        )
        0.45f * readiness
    }
    progress < LIGHT_CONVERGE_END -> 0.45f + 0.55f * lightImpulseSmoothStep(
        (progress - LIGHT_TRAVEL_END) / (LIGHT_CONVERGE_END - LIGHT_TRAVEL_END)
    )
    else -> 0f
}

internal fun lightImpulseBottomBloomRadiusFraction(progress: Float): Float = when {
    progress < LIGHT_TRAVEL_END -> 0.012f + 0.006f * (lightImpulseBottomBloom(progress) / 0.45f)
    progress < LIGHT_CONVERGE_END -> 0.018f + 0.022f * lightImpulseSmoothStep(
        (progress - LIGHT_TRAVEL_END) / (LIGHT_CONVERGE_END - LIGHT_TRAVEL_END)
    )
    else -> 0.040f
}

internal fun lightImpulseIgnitionDurationMs(): Long =
    (LIGHT_IMPULSE_DURATION_SECONDS * LIGHT_IGNITION_END * 1_000f).roundToInt().toLong()

private fun lightImpulseSmoothStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Positions on the calibrated centreline used by the one source-to-reunion event. */
internal data class LightImpulseEndpoints(
    val topFraction: Float,
    val bottomFraction: Float,
    val forwardDistance: Float,
    val reverseDistance: Float
)

internal fun lightImpulseEndpoints(
    measure: PathMeasure,
    length: Float,
    bounds: RectF
): LightImpulseEndpoints {
    val topFraction = nearestPathFraction(measure, length, bounds.centerX(), bounds.top)
    val bottomFraction = nearestPathFraction(measure, length, bounds.centerX(), bounds.bottom)
    return lightImpulseEndpoints(topFraction, bottomFraction, length)
}

internal fun lightImpulseEndpoints(
    topFraction: Float,
    bottomFraction: Float,
    length: Float
): LightImpulseEndpoints {
    if (length <= 0f) {
        return LightImpulseEndpoints(0f, 0f, 0f, 0f)
    }
    val forwardDistance = ((bottomFraction - topFraction) * length + length) % length
    return LightImpulseEndpoints(
        topFraction = topFraction,
        bottomFraction = bottomFraction,
        forwardDistance = forwardDistance,
        reverseDistance = length - forwardDistance
    )
}

private fun nearestPathFraction(
    measure: PathMeasure,
    length: Float,
    targetX: Float,
    targetY: Float
): Float {
    val position = FloatArray(2)
    var nearestFraction = 0f
    var nearestDistance = Float.MAX_VALUE
    for (sample in 0..LIGHT_ENDPOINT_SAMPLES) {
        val fraction = sample.toFloat() / LIGHT_ENDPOINT_SAMPLES
        measure.getPosTan(length * fraction, position, null)
        val deltaX = position[0] - targetX
        val deltaY = position[1] - targetY
        val distance = deltaX * deltaX + deltaY * deltaY
        if (distance < nearestDistance) {
            nearestDistance = distance
            nearestFraction = fraction
        }
    }
    return nearestFraction
}

private const val LIGHT_ENDPOINT_SAMPLES = 960

/** Draws prepared geometry; the View supplies window size, insets and animation. */
internal class HaloRenderer(
    config: HaloConfig,
    private val outline: DisplayOutline
) {
    private var config =
        config.sanitized()

    private var colorRgb =
        this.config.color and RGB_MASK

    private var coreStrokeWidth =
        calculateCoreStrokeWidth(
            this.config.thickness
        )

    private var renderStrokeWidth =
        calculateRenderStrokeWidth(
            coreStrokeWidth
        )

    private var cachedOutlineVersion =
        -1

    private var cachedStyleKey: ConventionalPathCacheKey? = null

    private var cachedStylePath:
            Path? = null

    private var cachedStyleGeometry:
            StyleGeometry? = null

    private val segmentPath =
        Path()

    private var gradientShader:
            SweepGradient? = null

    private var gradientOutlineVersion =
        -1

    private var gradientPalette =
        intArrayOf()

    private val gradientMatrix =
        Matrix()

    private var gradientCenterX =
        0f

    private var gradientCenterY =
        0f

    private val corePaint =
        createPaint().apply {
            strokeWidth =
                renderStrokeWidth
        }

    /** Separate multi-head surface; conventional motions keep their established renderer path. */
    private val forceBlades =
        ForceBladesRenderer(outline)

    fun update(
        config: HaloConfig
    ) {
        val next =
            config.sanitized()

        val geometryChanged =
            this.config.thickness !=
                    next.thickness ||
                    this.config.frame != next.frame ||
                    this.config.edgeCalibrationDp != next.edgeCalibrationDp ||
                    this.config.cornerCalibrationDp != next.cornerCalibrationDp
                    || this.config.cornerShape != next.cornerShape

        this.config =
            next

        colorRgb =
            next.color and RGB_MASK

        if (geometryChanged) {
            updateStrokeParameters()

            clearStylePathCache()
            outline.clearRenderCaches()
        }
    }

    fun draw(
        canvas: Canvas,
        animationProgress: Float,
        effectPhase: Float,
        gradientPhase: Float
    ) {
        if (config.renderMode == HaloRenderMode.LIGHT_IMPULSE) {
            drawLightImpulse(canvas, animationProgress, effectPhase)
            return
        }
        val baseAlpha =
            (
                    255f *
                            config.intensity *
                            animationProgress.coerceIn(
                                0f,
                                1f
                            )
                    )
                .roundToInt()
                .coerceIn(
                    0,
                    255
                )

        if (baseAlpha == 0) {
            return
        }

        if (config.motion.isForceBlade) {
            forceBlades.draw(
                canvas = canvas,
                motion = config.motion,
                phase = effectPhase,
                alpha = baseAlpha,
                strokeWidth = renderStrokeWidth,
                edgeCalibrationPx = outline.dpToPx(config.edgeCalibrationDp),
                cornerCalibrationPx = outline.dpToPx(config.cornerCalibrationDp)
                , cornerShape = config.cornerShape
            )
            return
        }

        if (
            cachedOutlineVersion !=
            outline.version
        ) {
            clearStylePathCache()

            cachedOutlineVersion =
                outline.version
        }

        val saveCount =
            canvas.save()

        try {
            canvas.clipPath(
                outline.path
            )

            preparePaint(
                baseAlpha = baseAlpha,
                effectPhase = effectPhase,
                gradientPhase = gradientPhase
            )

            drawStylePath(
                canvas = canvas,
                paint = corePaint,
                phase = effectPhase
            )
        } finally {
            canvas.restoreToCount(
                saveCount
            )
        }
    }

    /** Reminder-only wavefront. It uses the same calibrated contour cache as normal effects. */
    private fun drawLightImpulse(canvas: Canvas, progress: Float, phase: Float) {
        if (outline.path.isEmpty) return
        if (cachedOutlineVersion != outline.version) {
            clearStylePathCache()
            cachedOutlineVersion = outline.version
        }
        val path = stylePathForCurrentConfig()
        val geometry = styleGeometryFor(path)
        if (geometry.length <= 0f) return
        val timeline = phase.coerceIn(0f, 1f)
        if (timeline >= 1f) return
        // The impulse owns its 0.88–1.00 fade; the generic finite envelope must not
        // prematurely dim the outline travel.
        val alpha = (255f * config.intensity).roundToInt().coerceIn(0, 255)
        if (alpha == 0) return
        val endpoints = lightImpulseEndpointsFor(geometry)
        val origin = endpoints.topFraction * geometry.length
        val bottom = endpoints.bottomFraction * geometry.length
        val forwardDistance = endpoints.forwardDistance
        val reverseDistance = endpoints.reverseDistance
        val save = canvas.save()
        try {
            canvas.clipPath(outline.path)
            corePaint.shader = null
            when (lightImpulsePhase(timeline)) {
                LightImpulsePhase.IGNITION -> {
                    val ignition = lightImpulseOriginGlow(timeline)
                    drawImpulseBloom(
                        canvas, geometry, origin, alpha,
                        radius = geometry.length * (0.012f + ignition * 0.026f),
                        bloom = ignition
                    )
                }

                LightImpulsePhase.TRAVEL -> {
                    val travel = lightImpulseTravelProgress(timeline)
                    val originGlow = lightImpulseOriginGlow(timeline)
                    if (originGlow > 0f) {
                        drawImpulseBloom(
                            canvas, geometry, origin, alpha,
                            radius = geometry.length * (0.018f + originGlow * 0.020f),
                            bloom = originGlow
                        )
                    }
                    val forwardFront = origin + forwardDistance * travel
                    val reverseFront = origin - reverseDistance * travel
                    // The two paths are born from the already-completed ignition, rather than
                    // rendering two full beams at the top centre. Their shared, tapered light
                    // structure keeps this one event readable as it separates around the edge.
                    drawImpulseFlow(
                        canvas, geometry, forwardFront, 1, alpha, timeline,
                        tailLimit = forwardDistance * travel
                    )
                    drawImpulseFlow(
                        canvas, geometry, reverseFront, -1, alpha, timeline,
                        tailLimit = reverseDistance * travel
                    )
                    val bottomBloom = lightImpulseBottomBloom(timeline)
                    if (bottomBloom > 0f) {
                        drawImpulseBloom(
                            canvas, geometry, bottom, alpha,
                            radius = geometry.length * lightImpulseBottomBloomRadiusFraction(timeline),
                            bloom = bottomBloom
                        )
                    }
                }

                LightImpulsePhase.CONVERGE -> {
                    val converge = lightImpulseSmoothStep(
                        (timeline - LIGHT_TRAVEL_END) /
                                (LIGHT_CONVERGE_END - LIGHT_TRAVEL_END)
                    )
                    val remainingFlow = geometry.length * LIGHT_TAIL_FRACTION * (1f - converge)
                    drawImpulseFlow(
                        canvas, geometry, bottom, 1, alpha, timeline, remainingFlow,
                        drawCore = false
                    )
                    drawImpulseFlow(
                        canvas, geometry, bottom, -1, alpha, timeline, remainingFlow,
                        drawCore = false
                    )
                    drawImpulseBloom(
                        canvas, geometry, bottom, alpha,
                        radius = geometry.length * lightImpulseBottomBloomRadiusFraction(timeline),
                        bloom = lightImpulseBottomBloom(timeline)
                    )
                }

                LightImpulsePhase.FADE -> {
                    val fade = 1f - lightImpulseSmoothStep(
                        (timeline - LIGHT_CONVERGE_END) /
                                (1f - LIGHT_CONVERGE_END)
                    )
                    drawImpulseBloom(
                        canvas, geometry, bottom, alpha,
                        radius = geometry.length * 0.040f,
                        bloom = fade
                    )
                }
            }
        } finally { canvas.restoreToCount(save) }
    }

    private fun lightImpulseEndpointsFor(geometry: StyleGeometry): LightImpulseEndpoints =
        geometry.lightImpulseEndpoints ?: lightImpulseEndpoints(
            measure = geometry.measure,
            length = geometry.length,
            bounds = geometry.bounds
        ).also { geometry.lightImpulseEndpoints = it }

    /**
     * Draws a physical-edge-only continuous light field. Closely overlapped samples modulate
     * opacity and width along the contour, so the eye reads one tail rather than stacked bands.
     */
    private fun drawImpulseFlow(
        canvas: Canvas,
        geometry: StyleGeometry,
        front: Float,
        direction: Int,
        alpha: Int,
        timeline: Float,
        tailLimit: Float,
        drawCore: Boolean = true
    ) {
        val tailLength = min(geometry.length * LIGHT_TAIL_FRACTION, tailLimit)
        if (tailLength <= 0f) return
        val sampleLength = tailLength / LIGHT_TAIL_SAMPLES
        val organicPulse = 0.94f + 0.06f * sin(timeline * 19f + direction * 0.7f)
        var sample = 0
        while (sample < LIGHT_TAIL_SAMPLES) {
            val position = (sample + 0.5f) / LIGHT_TAIL_SAMPLES
            val retainedEnergy = 1f - position
            val taperedEnergy = retainedEnergy * retainedEnergy
            val fieldAlpha = (
                alpha *
                        (0.018f + 0.36f * taperedEnergy) *
                        organicPulse
                )
                .roundToInt()
            val fieldWidth = renderStrokeWidth * (0.56f + 0.58f * retainedEnergy)
            drawDirectionalImpulseSample(
                canvas = canvas,
                geometry = geometry,
                front = front,
                direction = direction,
                distanceBehindFront = sample * sampleLength,
                length = sampleLength * 1.70f,
                alpha = fieldAlpha,
                strokeWidth = fieldWidth
            )
            sample++
        }

        if (!drawCore) return
        val coreLength = min(geometry.length * LIGHT_CORE_FRACTION, tailLength * 0.60f)
        drawContinuousImpulseCore(
            canvas, geometry, front, direction, alpha, organicPulse, coreLength
        )
    }

    private fun drawImpulseBloom(
        canvas: Canvas,
        geometry: StyleGeometry,
        center: Float,
        alpha: Int,
        radius: Float,
        bloom: Float
    ) {
        val safeBloom = bloom.coerceIn(0f, 1f)
        if (safeBloom <= 0f || radius <= 0f) return
        val sampleLength = radius * 2f / LIGHT_BLOOM_SAMPLES
        var sample = 0
        while (sample < LIGHT_BLOOM_SAMPLES) {
            val normalized = ((sample + 0.5f) / LIGHT_BLOOM_SAMPLES) * 2f - 1f
            val energy = (1f - normalized * normalized).coerceAtLeast(0f)
            val softenedEnergy = energy * energy
            drawImpulseSegment(
                canvas = canvas,
                geometry = geometry,
                center = center + normalized * radius,
                length = sampleLength * 1.75f,
                alpha = (alpha * safeBloom * (0.025f + 0.58f * softenedEnergy)).roundToInt(),
                strokeWidth = renderStrokeWidth * (0.72f + 1.75f * softenedEnergy)
            )
            sample++
        }
    }

    /** A one-pass longitudinal energy distribution replaces concentric core strokes. */
    private fun drawContinuousImpulseCore(
        canvas: Canvas,
        geometry: StyleGeometry,
        front: Float,
        direction: Int,
        alpha: Int,
        organicPulse: Float,
        coreLength: Float
    ) {
        val coreSpan = coreLength * 3.2f
        val sampleLength = coreSpan / LIGHT_CORE_SAMPLES
        var sample = 0
        while (sample < LIGHT_CORE_SAMPLES) {
            val position = (sample + 0.5f) / LIGHT_CORE_SAMPLES
            // The high-energy point sits just behind the front, with a smooth release behind it.
            val centred = (position - 0.24f) / 0.62f
            val energy = (1f - centred * centred).coerceIn(0f, 1f)
            val softenedEnergy = energy * energy
            drawDirectionalImpulseSample(
                canvas = canvas,
                geometry = geometry,
                front = front,
                direction = direction,
                distanceBehindFront = sample * sampleLength,
                length = sampleLength * 1.75f,
                alpha = (alpha * organicPulse * (0.14f + 0.86f * softenedEnergy)).roundToInt(),
                strokeWidth = renderStrokeWidth * (0.92f + 1.45f * softenedEnergy)
            )
            sample++
        }
    }

    private fun drawDirectionalImpulseSample(
        canvas: Canvas,
        geometry: StyleGeometry,
        front: Float,
        direction: Int,
        distanceBehindFront: Float,
        length: Float,
        alpha: Int,
        strokeWidth: Float
    ) {
        val center = if (direction > 0) {
            front - distanceBehindFront - length / 2f
        } else {
            front + distanceBehindFront + length / 2f
        }
        drawImpulseSegment(canvas, geometry, center, length, alpha, strokeWidth)
    }

    private fun drawImpulseSegment(
        canvas: Canvas,
        geometry: StyleGeometry,
        center: Float,
        length: Float,
        alpha: Int,
        strokeWidth: Float
    ) {
        if (alpha <= 0 || length <= 0f) return
        corePaint.color = colorWithAlpha(alpha, colorRgb)
        corePaint.strokeWidth = strokeWidth
        drawWrappedSegment(canvas, geometry.measure, geometry.length, center - length / 2f, length, corePaint)
    }

    /** Complete calibrated frame used by the static calibration preview. */
    fun drawStaticFrame(canvas: Canvas) {
        if (config.intensity <= 0f) return
        if (cachedOutlineVersion != outline.version) {
            clearStylePathCache()
            cachedOutlineVersion = outline.version
        }
        val saveCount = canvas.save()
        try {
            canvas.clipPath(outline.path)
            preparePaint((255f * config.intensity).roundToInt().coerceIn(0, 255), 0f, 0f)
            canvas.drawPath(staticPreviewPathForCurrentConfig(), corePaint)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    /** Calibration-only witnesses; never called by the animated renderer. */
    fun drawCalibrationDiagnostics(canvas: Canvas, rulerLegend: String, registrationLabel: String) {
        if (outline.path.isEmpty) return

        val runtimeCentrelinePaint = createPaint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = 0xFFFFD54F.toInt()
        }
        val rulerPaint = createPaint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = 0x99FFFFFF.toInt()
        }

        val edgeCalibrationPx = outline.dpToPx(config.edgeCalibrationDp)
        val rulerDepth = CalibrationDiagnostics.rulerDepthPx(edgeCalibrationPx)
        val saveCount = canvas.save()
        try {
            // Match static-frame clipping so OUT never becomes a fabricated outer frame.
            canvas.clipPath(outline.path)
            canvas.drawPath(staticPreviewPathForCurrentConfig(), runtimeCentrelinePaint)
            var offset = 0f
            while (offset <= rulerDepth) {
                rulerPaint.strokeWidth = if (offset.toInt() % 5 == 0) 2f else 1f
                canvas.drawLine(offset, 0f, offset, rulerDepth, rulerPaint)
                canvas.drawLine(canvas.width - offset, 0f, canvas.width - offset, rulerDepth, rulerPaint)
                canvas.drawLine(offset, canvas.height.toFloat(), offset, canvas.height - rulerDepth, rulerPaint)
                canvas.drawLine(canvas.width - offset, canvas.height.toFloat(), canvas.width - offset, canvas.height - rulerDepth, rulerPaint)
                offset += 1f
            }
        } finally {
            canvas.restoreToCount(saveCount)
        }

        val registration = CalibrationDiagnostics.registration(edgeCalibrationPx)
        drawRegistrationWitness(
            canvas,
            registration,
            "$rulerLegend · $registrationLabel",
            edgeCalibrationPx,
            rulerDepth,
            rulerPaint
        )
    }

    fun calibrationEdgePx(): Float = outline.dpToPx(config.edgeCalibrationDp)

    private fun drawRegistrationWitness(
        canvas: Canvas,
        registration: CalibrationDiagnostics.Registration,
        label: String,
        edgeCalibrationPx: Float,
        rulerDepth: Float,
        paint: Paint
    ) {
        val midX = canvas.width / 2f
        val edgeY = 0f
        val labelPaint = Paint(paint).apply {
            style = Paint.Style.FILL
            color = 0xFFFFFFFF.toInt()
            textSize = outline.dpToPx(12f).coerceAtLeast(16f)
            textAlign = Paint.Align.CENTER
        }
        val labelBaseline = labelPaint.textSize + 5f
        val labelWidth = labelPaint.measureText(label)
        val labelBackground = Paint(labelPaint).apply {
            color = 0xD9000000.toInt()
        }
        canvas.drawRoundRect(
            RectF(
                midX - labelWidth / 2f - 6f,
                0f,
                midX + labelWidth / 2f + 6f,
                labelBaseline + 4f
            ),
            4f,
            4f,
            labelBackground
        )
        canvas.drawText(label, midX, labelBaseline, labelPaint)
        when (registration) {
            CalibrationDiagnostics.Registration.ZERO -> {
                canvas.drawCircle(midX, edgeY, 2f, paint)
            }
            CalibrationDiagnostics.Registration.IN -> {
                val markerY = CalibrationDiagnostics.rulerMarkerPx(edgeCalibrationPx).coerceIn(0f, rulerDepth)
                canvas.drawLine(midX, edgeY, midX, markerY, paint)
                canvas.drawLine(midX, markerY, midX - 3f, markerY - 4f, paint)
                canvas.drawLine(midX, markerY, midX + 3f, markerY - 4f, paint)
            }
            // The display boundary is the outward reference; do not draw a fictional outer frame.
            CalibrationDiagnostics.Registration.OUT -> {
                canvas.drawLine(midX - 4f, edgeY + 4f, midX, edgeY, paint)
                canvas.drawLine(midX, edgeY, midX + 4f, edgeY + 4f, paint)
            }
        }
    }

    private fun preparePaint(
        baseAlpha: Int,
        effectPhase: Float,
        gradientPhase: Float
    ) {
        if (
            config.colorMode ==
            HaloColorMode.GRADIENT &&
            config.palette.size > 1
        ) {
            val shader =
                gradientShader()

            gradientMatrix.setRotate(
                (
                        gradientPhase *
                                GRADIENT_ROTATION_PER_CYCLE_DEGREES *
                                config.gradientFlowSpeed
                        ) %
                        FULL_ROTATION_DEGREES,
                gradientCenterX,
                gradientCenterY
            )

            shader.setLocalMatrix(
                gradientMatrix
            )

            corePaint.shader =
                shader

            corePaint.alpha =
                baseAlpha

            return
        }

        corePaint.shader =
            null

        val color =
            colorForPhase(
                effectPhase
            )

        corePaint.color =
            colorWithAlpha(
                alpha = baseAlpha,
                rgb = color
            )
    }

    private fun drawStylePath(
        canvas: Canvas,
        paint: Paint,
        phase: Float
    ) {
        val key = ConventionalPathCacheKey(
            outlineVersion = outline.version,
            strokeWidth = renderStrokeWidth,
            edgeCalibrationDp = config.edgeCalibrationDp,
            cornerCalibrationDp = config.cornerCalibrationDp
            , cornerShape = config.cornerShape
        )
        val path =
            cachedStylePath
                ?.takeIf {
                    cachedStyleKey == key
                }
                ?: buildStylePath()
                    .also { generatedPath ->
                        cachedStyleKey = key

                        cachedStylePath =
                            generatedPath
                    }

        when (config.motion) {
            HaloMotion.PULSE ->
                canvas.drawPath(
                    path,
                    paint
                )

            HaloMotion.SNAKE ->
                drawSnakePath(
                    canvas,
                    styleGeometryFor(path),
                    paint,
                    phase
                )

            HaloMotion.CORNER_PULSE ->
                drawCornerPulsePath(
                    canvas,
                    styleGeometryFor(path),
                    paint,
                    phase
                )

            HaloMotion.RAIN ->
                drawRain(
                    canvas,
                    styleGeometryFor(path),
                    paint,
                    phase
                )

            HaloMotion.RIPPLE_EDGE ->
                drawRippleEdge(
                    canvas,
                    styleGeometryFor(path),
                    paint,
                    phase
                )

            HaloMotion.AZURE_BLADE,
            HaloMotion.CRIMSON_BLADE,
            HaloMotion.FORCE_CLASH -> Unit
        }
    }

    internal fun stylePathForCurrentConfig(): Path {
        val key = ConventionalPathCacheKey(outline.version, renderStrokeWidth, config.edgeCalibrationDp, config.cornerCalibrationDp, config.cornerShape)
        return cachedStylePath?.takeIf { cachedStyleKey == key } ?: buildStylePath().also {
            cachedStyleKey = key
            cachedStylePath = it
        }
    }

    /** Static calibration intentionally reuses the normal runtime contour. */
    internal fun staticPreviewPathForCurrentConfig(): Path =
        stylePathForCurrentConfig()

    private fun drawSnakePath(
        canvas: Canvas,
        geometry: StyleGeometry,
        paint: Paint,
        phase: Float
    ) {
        val measure =
            geometry.measure

        val length =
            geometry.length

        if (length <= 0f) {
            return
        }

        val start =
            normalizedPhase(
                phase
            ) *
                    length

        val segmentLength =
            length *
                    SNAKE_SEGMENT_FRACTION

        drawWrappedSegment(
            canvas = canvas,
            measure = measure,
            length = length,
            start = start,
            segmentLength = segmentLength,
            paint = paint
        )
    }

    private fun drawCornerPulsePath(
        canvas: Canvas,
        geometry: StyleGeometry,
        paint: Paint,
        phase: Float
    ) {
        val measure =
            geometry.measure

        val length =
            geometry.length

        if (length <= 0f) {
            return
        }

        val normalizedPhase =
            normalizedPhase(
                phase
            )

        val cornerProgress =
            normalizedPhase *
                    CORNER_COUNT

        val cornerIndex =
            cornerProgress
                .toInt()
                .coerceIn(
                    0,
                    CORNER_COUNT - 1
                )

        val cornerAlpha =
            sin(
                (
                        cornerProgress -
                                cornerIndex
                        ) *
                        PI
            )
                .toFloat()
                .coerceIn(
                    0f,
                    1f
                )

        if (cornerAlpha <= 0f) {
            return
        }

        val center =
            cornerFractionsFor(
                geometry
            )[cornerIndex] *
                    length

        val baseAlpha =
            paint.alpha

        paint.alpha =
            (
                    baseAlpha *
                            cornerAlpha
                    )
                .roundToInt()

        drawWrappedSegment(
            canvas = canvas,
            measure = measure,
            length = length,
            start =
                center -
                        length *
                        CORNER_SEGMENT_FRACTION /
                        2f,
            segmentLength =
                length *
                        CORNER_SEGMENT_FRACTION,
            paint = paint
        )

        paint.alpha =
            baseAlpha
    }

    private fun drawRain(
        canvas: Canvas,
        geometry: StyleGeometry,
        paint: Paint,
        phase: Float
    ) {
        val bounds =
            geometry.bounds

        val height =
            bounds.height()

        if (height <= 0f) {
            return
        }

        val normalizedPhase =
            normalizedPhase(
                phase
            )

        val dropLength =
            height *
                    RAIN_DROP_LENGTH_FRACTION

        for (
        index in
        RAIN_OFFSETS.indices
        ) {
            val offset =
                RAIN_OFFSETS[index]

            val dropProgress =
                (
                        normalizedPhase +
                                offset
                        ) %
                        1f

            val bottom =
                bounds.top +
                        dropProgress *
                        (
                                height +
                                        dropLength
                                )

            val top =
                bottom -
                        dropLength

            val laneX =
                if (
                    index %
                    2 ==
                    0
                ) {
                    bounds.left
                } else {
                    bounds.right
                }

            canvas.drawLine(
                laneX,
                top,
                laneX,
                bottom,
                paint
            )
        }
    }

    private fun drawRippleEdge(
        canvas: Canvas,
        geometry: StyleGeometry,
        paint: Paint,
        phase: Float
    ) {
        val measure =
            geometry.measure

        val length =
            geometry.length

        if (length <= 0f) {
            return
        }

        val normalizedPhase =
            normalizedPhase(
                phase
            )

        val rippleAlpha =
            sin(
                normalizedPhase *
                        PI
            )
                .toFloat()
                .coerceIn(
                    0f,
                    1f
                )

        if (rippleAlpha <= 0f) {
            return
        }

        val bounds =
            geometry.bounds

        val origin =
            rippleOriginFraction(geometry) *
                    length

        val distance =
            normalizedPhase *
                    length /
                    2f

        val segmentLength =
            length *
                    RIPPLE_SEGMENT_FRACTION

        val baseAlpha =
            paint.alpha

        paint.alpha =
            (
                    baseAlpha *
                            rippleAlpha
                    )
                .roundToInt()

        drawWrappedSegment(
            canvas = canvas,
            measure = measure,
            length = length,
            start =
                origin +
                        distance -
                        segmentLength,
            segmentLength =
                segmentLength,
            paint = paint
        )

        drawWrappedSegment(
            canvas = canvas,
            measure = measure,
            length = length,
            start =
                origin -
                        distance,
            segmentLength =
                segmentLength,
            paint = paint
        )

        paint.alpha =
            baseAlpha
    }

    private fun drawWrappedSegment(
        canvas: Canvas,
        measure: PathMeasure,
        length: Float,
        start: Float,
        segmentLength: Float,
        paint: Paint
    ) {
        if (length <= 0f) {
            return
        }

        val normalizedStart =
            normalizeDistance(
                value = start,
                length = length
            )

        val end =
            normalizedStart +
                    segmentLength

        segmentPath.reset()

        if (end <= length) {
            measure.getSegment(
                normalizedStart,
                end,
                segmentPath,
                true
            )
        } else {
            measure.getSegment(
                normalizedStart,
                length,
                segmentPath,
                true
            )

            measure.getSegment(
                0f,
                end - length,
                segmentPath,
                true
            )
        }

        canvas.drawPath(
            segmentPath,
            paint
        )
    }

    private fun cornerFractionsFor(
        geometry: StyleGeometry
    ): FloatArray {
        geometry.cornerFractions
            ?.let {
                return it
            }

        val bounds =
            geometry.bounds

        val targets =
            floatArrayOf(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.top,
                bounds.right,
                bounds.bottom,
                bounds.left,
                bounds.bottom
            )

        val position =
            FloatArray(
                2
            )

        return FloatArray(
            CORNER_COUNT
        ) { cornerIndex ->

            val targetX =
                targets[
                    cornerIndex *
                            2
                ]

            val targetY =
                targets[
                    cornerIndex *
                            2 +
                            1
                ]

            var nearestFraction =
                0f

            var nearestDistance =
                Float.MAX_VALUE

            for (
            sample in
            0..CORNER_PATH_SAMPLES
            ) {
                val fraction =
                    sample.toFloat() /
                            CORNER_PATH_SAMPLES

                geometry.measure
                    .getPosTan(
                        geometry.length *
                                fraction,
                        position,
                        null
                    )

                val deltaX =
                    position[0] -
                            targetX

                val deltaY =
                    position[1] -
                            targetY

                val distance =
                    deltaX *
                            deltaX +
                            deltaY *
                            deltaY

                if (
                    distance <
                    nearestDistance
                ) {
                    nearestDistance =
                        distance

                    nearestFraction =
                        fraction
                }
            }

            nearestFraction
        }.also { fractions ->
            geometry.cornerFractions =
                fractions
        }
    }

    private fun rippleOriginFraction(geometry: StyleGeometry): Float {
        geometry.topRippleOriginFraction
            ?.let {
                return it
            }
        return nearestPathFraction(
            measure = geometry.measure,
            length = geometry.length,
            targetX = geometry.bounds.centerX(),
            targetY = geometry.bounds.top
        ).also { geometry.topRippleOriginFraction = it }
    }

    /** The DisplayOutline owns the physical centreline and its zero-default calibration. */
    private fun buildStylePath(): Path {
        val strokePath =
            outline.centerlinePath(
                renderStrokeWidth,
                outline.opticalInsetPx + outline.dpToPx(config.edgeCalibrationDp),
                outline.dpToPx(config.cornerCalibrationDp)
                , config.cornerShape
            )

        return when (config.frame) {
            HaloFrame.CLASSIC ->
                strokePath
        }
    }

    private fun updateStrokeParameters() {
        coreStrokeWidth =
            calculateCoreStrokeWidth(
                config.thickness
            )

        renderStrokeWidth =
            calculateRenderStrokeWidth(
                coreStrokeWidth
            )

        corePaint.strokeWidth =
            renderStrokeWidth
    }

    private fun calculateCoreStrokeWidth(
        thickness: Float
    ): Float =
        BASE_STROKE_WIDTH +
                thickness *
                STROKE_WIDTH_RANGE

    /**
     * Rendering width is now exactly the configured Halo width.
     *
     * Geometry containment is handled by centerline placement rather than
     * artificially widening the stroke near the display edge.
     */
    private fun calculateRenderStrokeWidth(
        strokeWidth: Float
    ): Float =
        strokeWidth

    private fun clearStylePathCache() {
        cachedStyleKey = null

        cachedStylePath =
            null

        cachedStyleGeometry =
            null
    }

    private fun styleGeometryFor(
        path: Path
    ): StyleGeometry {
        cachedStyleGeometry
            ?.takeIf {
                it.path === path
            }
            ?.let {
                return it
            }

        return StyleGeometry(
            path = path,
            measure =
                PathMeasure(
                    path,
                    false
                ),
            bounds =
                boundsOf(
                    path
                )
        ).also { geometry ->
            geometry.length =
                geometry.measure.length

            cachedStyleGeometry =
                geometry
        }
    }

    private fun gradientShader():
            SweepGradient {

        if (
            gradientShader != null &&
            gradientOutlineVersion ==
            outline.version &&
            gradientPalette.contentEquals(
                config.palette
            )
        ) {
            return gradientShader!!
        }

        val bounds =
            boundsOf(
                outline.path
            )

        gradientCenterX =
            bounds.centerX()

        gradientCenterY =
            bounds.centerY()

        val colors =
            IntArray(
                config.palette.size +
                        1
            ) { index ->
                config.palette[
                    index %
                            config.palette.size
                ] or
                        OPAQUE_ALPHA
            }

        val positions =
            FloatArray(
                colors.size
            ) { index ->
                index.toFloat() /
                        (
                                colors.size -
                                        1
                                )
                            .coerceAtLeast(
                                1
                            )
            }

        return SweepGradient(
            gradientCenterX,
            gradientCenterY,
            colors,
            positions
        ).also { shader ->

            gradientShader =
                shader

            gradientOutlineVersion =
                outline.version

            gradientPalette =
                config.palette.copyOf()
        }
    }

    private fun boundsOf(
        path: Path
    ): RectF =
        RectF().also {
            path.computeBounds(
                it,
                true
            )
        }

    private fun colorForPhase(
        phase: Float
    ): Int {
        if (
            config.colorMode ==
            HaloColorMode.GRADIENT ||
            config.palette.size <
            2
        ) {
            return colorRgb
        }

        val normalizedPhase =
            normalizedPhase(
                phase
            )

        val scaled =
            normalizedPhase *
                    config.palette.size

        val startIndex =
            scaled.toInt() %
                    config.palette.size

        val endIndex =
            (
                    startIndex +
                            1
                    ) %
                    config.palette.size

        val fraction =
            scaled -
                    scaled.toInt()

        return blend(
            config.palette[startIndex],
            config.palette[endIndex],
            fraction
        ) and
                RGB_MASK
    }

    private fun blend(
        start: Int,
        end: Int,
        fraction: Float
    ): Int {
        val safeFraction =
            fraction.coerceIn(
                0f,
                1f
            )

        val startRed =
            component(
                start,
                RED_SHIFT
            )

        val startGreen =
            component(
                start,
                GREEN_SHIFT
            )

        val startBlue =
            component(
                start,
                BLUE_SHIFT
            )

        val endRed =
            component(
                end,
                RED_SHIFT
            )

        val endGreen =
            component(
                end,
                GREEN_SHIFT
            )

        val endBlue =
            component(
                end,
                BLUE_SHIFT
            )

        val red =
            interpolateComponent(
                startRed,
                endRed,
                safeFraction
            )

        val green =
            interpolateComponent(
                startGreen,
                endGreen,
                safeFraction
            )

        val blue =
            interpolateComponent(
                startBlue,
                endBlue,
                safeFraction
            )

        return (
                red shl
                        RED_SHIFT
                ) or
                (
                        green shl
                                GREEN_SHIFT
                        ) or
                blue
    }

    private fun component(
        color: Int,
        shift: Int
    ): Int =
        (
                color shr
                        shift
                ) and
                0xFF

    private fun interpolateComponent(
        start: Int,
        end: Int,
        fraction: Float
    ): Int =
        (
                start +
                        (
                                end -
                                        start
                                ) *
                        fraction
                )
            .roundToInt()

    private fun normalizedPhase(
        phase: Float
    ): Float =
        (
                phase %
                        1f +
                        1f
                ) %
                1f

    private fun normalizeDistance(
        value: Float,
        length: Float
    ): Float =
        (
                value %
                        length +
                        length
                ) %
                length

    private fun colorWithAlpha(
        alpha: Int,
        rgb: Int
    ): Int =
        (
                alpha
                    .coerceIn(
                        0,
                        255
                    ) shl
                        ALPHA_SHIFT
                ) or
                (
                        rgb and
                                RGB_MASK
                        )

    private fun createPaint():
            Paint =
        Paint(
            Paint.ANTI_ALIAS_FLAG or
                    Paint.DITHER_FLAG
        ).apply {
            style =
                Paint.Style.STROKE

            strokeCap =
                Paint.Cap.ROUND

            strokeJoin =
                Paint.Join.ROUND
        }

    private class StyleGeometry(
        val path: Path,
        val measure: PathMeasure,
        val bounds: RectF,
        var length: Float = 0f,
        var cornerFractions: FloatArray? = null,
        var topRippleOriginFraction: Float? = null,
        var lightImpulseEndpoints: LightImpulseEndpoints? = null
    )

    private companion object {
        private const val BASE_STROKE_WIDTH =
            4f

        private const val STROKE_WIDTH_RANGE =
            10f

        private const val SNAKE_SEGMENT_FRACTION =
            0.18f

        private const val CORNER_SEGMENT_FRACTION =
            0.14f

        private const val CORNER_COUNT =
            4

        private const val CORNER_PATH_SAMPLES =
            240

        private const val RAIN_DROP_LENGTH_FRACTION =
            0.14f

        private const val RIPPLE_SEGMENT_FRACTION =
            0.12f

        private val RAIN_OFFSETS =
            floatArrayOf(
                0f,
                0.18f,
                0.43f,
                0.67f
            )

        private const val GRADIENT_ROTATION_PER_CYCLE_DEGREES =
            220f

        private const val FULL_ROTATION_DEGREES =
            360f

        private const val RGB_MASK =
            0x00FFFFFF

        private const val ALPHA_SHIFT =
            24

        private const val RED_SHIFT =
            16

        private const val GREEN_SHIFT =
            8

        private const val BLUE_SHIFT =
            0

        private const val OPAQUE_ALPHA =
            -0x1000000
    }
}

/** Cache identity for conventional (non-Force-Blades) paths. */
internal data class ConventionalPathCacheKey(
    val outlineVersion: Int,
    val strokeWidth: Float,
    val edgeCalibrationDp: Float,
    val cornerCalibrationDp: Float
    , val cornerShape: Float = 0.5f
)

private val HaloMotion.isForceBlade: Boolean
    get() = this == HaloMotion.AZURE_BLADE ||
        this == HaloMotion.CRIMSON_BLADE ||
        this == HaloMotion.FORCE_CLASH
