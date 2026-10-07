package com.yp.luminote.app.effects

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import com.yp.luminote.app.data.settings.HaloMotion
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal class StyleGeometry(
    val path: Path,
    val measure: PathMeasure,
    val bounds: RectF,
    var length: Float = 0f
)

private const val SNAKE_SEGMENT_FRACTION = 0.18f
private const val CORNER_SEGMENT_FRACTION = 0.14f
private const val CORNER_COUNT = 4
private const val CORNER_PATH_SAMPLES = 240
private const val RAIN_DROP_LENGTH_FRACTION = 0.14f
private val RAIN_OFFSETS = floatArrayOf(0f, 0.18f, 0.43f, 0.67f)

private fun normalizedPhase(phase: Float): Float = (phase % 1f + 1f) % 1f

private fun colorWithAlpha(alpha: Int, rgb: Int): Int =
    (alpha.coerceIn(0, 255) shl 24) or (rgb and 0x00FFFFFF)

internal interface ConventionalRenderSurface {
    val outlinePath: Path
    val corePaint: Paint
    val motion: HaloMotion
    fun preparePaint(baseAlpha: Int, effectPhase: Float, gradientPhase: Float)
    fun stylePath(): Path
    fun styleGeometry(path: Path): StyleGeometry
}

internal interface LightImpulseRenderSurface {
    val outlinePath: Path
    val corePaint: Paint
    val renderStrokeWidth: Float
    val colorRgb: Int
    fun geometry(): StyleGeometry?
    fun alpha(): Int
    fun preparePaint(alpha: Int, effectPhase: Float, gradientPhase: Float)
}

internal class ConventionalEdgeRenderer(private val surface: ConventionalRenderSurface) {
    private val segmentPath = Path()
    private var cachedCornerGeometry: StyleGeometry? = null
    private var cachedCornerFractions: FloatArray? = null

    fun draw(canvas: Canvas, baseAlpha: Int, effectPhase: Float, gradientPhase: Float) {
        val saveCount = canvas.save()
        try {
            canvas.clipPath(surface.outlinePath)
            surface.preparePaint(baseAlpha, effectPhase, gradientPhase)
            drawConventionalStyle(canvas, surface.corePaint, effectPhase)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }
/** Shared calibrated geometry primitive; conventional motion owns its dispatch. */
internal fun drawConventionalStyle(
    canvas: Canvas,
    paint: Paint,
    phase: Float
) {
    val path = surface.stylePath()

    when (surface.motion) {
        HaloMotion.PULSE ->
            canvas.drawPath(
                path,
                paint
            )

        HaloMotion.SNAKE ->
            drawSnakePath(
                canvas,
                surface.styleGeometry(path),
                paint,
                phase
            )

        HaloMotion.CORNER_PULSE ->
            drawCornerPulsePath(
                canvas,
                surface.styleGeometry(path),
                paint,
                phase
            )

        HaloMotion.RAIN ->
            drawRain(
                canvas,
                surface.styleGeometry(path),
                paint,
                phase
            )

        HaloMotion.AZURE_BLADE,
        HaloMotion.CRIMSON_BLADE,
        HaloMotion.FORCE_CLASH,
        HaloMotion.IMPULSE -> Unit
    }
}

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

private fun drawWrappedSegment(
    canvas: Canvas,
    measure: PathMeasure,
    length: Float,
    start: Float,
    segmentLength: Float,
    paint: Paint
) {
    if (EdgeSegmentRenderer.append(
            measure = measure,
            contourLength = length,
            start = start,
            segmentLength = segmentLength,
            out = segmentPath,
            allowFullContour = false
        )) {
        canvas.drawPath(segmentPath, paint)
    }
}

private fun cornerFractionsFor(
    geometry: StyleGeometry
): FloatArray {
    cachedCornerFractions
        ?.takeIf { cachedCornerGeometry === geometry }
        ?.let { return it }

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
        cachedCornerGeometry = geometry
        cachedCornerFractions = fractions
    }
}

}

internal class LightImpulseRenderer(private val surface: LightImpulseRenderSurface) {
    private val drawPlan = LightImpulseProductionDrawPlan()
    private val segmentPath = Path()
    private val impulseOwnedSegment = LightImpulseOwnedSegment()
    private var endpointGeometry: StyleGeometry? = null
    private var cachedEndpoints: LightImpulseEndpoints? = null

    fun draw(canvas: Canvas, progress: Float, phase: Float, gradientPhase: Float) {
        if (surface.outlinePath.isEmpty) return
        val geometry = surface.geometry() ?: return
        val timeline = phase.coerceIn(0f, 1f)
        if (timeline >= 1f) return
        val alpha = surface.alpha()
        if (alpha == 0) return
        val endpoints = endpointsFor(geometry)
        val origin = endpoints.topFraction * geometry.length
        val bottom = endpoints.bottomFraction * geometry.length
        val save = canvas.save()
        try {
            canvas.clipPath(surface.outlinePath)
            surface.corePaint.strokeCap = Paint.Cap.ROUND
            surface.preparePaint(alpha, phase, gradientPhase)
            val passEnergy = lightImpulsePassEnergy(timeline)
            when (lightImpulsePhase(timeline)) {
                LightImpulsePhase.IGNITION -> drawLightImpulseBloom(canvas, geometry, origin, alpha, geometry.length * (0.012f + passEnergy.originBloom * 0.026f), passEnergy.originBloom, matchSharedSourcePeak = true)
                LightImpulsePhase.TRAVEL -> {
                    val plan = lightImpulseProductionDrawPlan(timeline, geometry.length, endpoints.forwardDistance, endpoints.reverseDistance, drawPlan)
                    when (plan.mode) {
                        LightImpulseDrawMode.TOP_BLOOM -> drawLightImpulseBloom(canvas, geometry, origin, alpha, plan.sourceBloomRadius, lightImpulseOriginGlow(timeline), matchSharedSourcePeak = true)
                        LightImpulseDrawMode.SHARED_BIRTH_FIELD -> drawConnectedLightImpulseField(canvas, geometry, origin, plan.forwardFieldLength, plan.reverseFieldLength, 1, -1, alpha, lightImpulseOriginGlow(timeline), lightImpulseBranchEnergy(timeline), birthSourceRadius = plan.sourceBloomRadius)
                        LightImpulseDrawMode.OWNED_TRAVEL_FIELDS -> {
                            val originGlow = passEnergy.originBloom
                            val radius = geometry.length * (0.018f + originGlow * 0.020f)
                            if (originGlow > 0f) drawLightImpulseBloom(canvas, geometry, origin, alpha, radius, originGlow)
                            drawTravellingLightImpulseFlow(canvas, geometry, origin, endpoints.forwardDistance * lightImpulseTravelProgress(timeline), endpoints.forwardDistance, 1, alpha, timeline, plan.forwardFieldLength, if (originGlow > 0f) radius else 0f, passEnergy.tailPerBranch, passEnergy.corePerBranch)
                            drawTravellingLightImpulseFlow(canvas, geometry, origin, endpoints.reverseDistance * lightImpulseTravelProgress(timeline), endpoints.reverseDistance, -1, alpha, timeline, plan.reverseFieldLength, if (originGlow > 0f) radius else 0f, passEnergy.tailPerBranch, passEnergy.corePerBranch)
                        }
                        else -> Unit
                    }
                }
                LightImpulsePhase.CONVERGE -> drawConvergingLightImpulse(canvas, geometry, bottom, endpoints, alpha, timeline, passEnergy, drawPlan)
                LightImpulsePhase.FADE -> drawLightImpulseBloom(canvas, geometry, bottom, alpha, geometry.length * lightImpulseTerminalBloomRadiusFraction(timeline), passEnergy.bottomBloom, matchConnectedSourcePeak = true)
            }
        } finally {
            surface.corePaint.strokeCap = Paint.Cap.ROUND
            canvas.restoreToCount(save)
        }
    }
/**
 * Draws one optical field centred on a source and split into two butt-to-butt owned halves.
 * No bloom/core/tail pass overlaps another here: every longitudinal sample owns one interval.
 */
internal fun drawConnectedLightImpulseField(
    canvas: Canvas,
    geometry: StyleGeometry,
    origin: Float,
    forwardDistance: Float,
    reverseDistance: Float,
    forwardDirection: Int,
    reverseDirection: Int,
    alpha: Int,
    sourceStrength: Float,
    frontStrength: Float,
    sourceAnchored: Boolean = false,
    birthSourceRadius: Float = 0f
) {
    val safeSource = sourceStrength.coerceIn(0f, 1f)
    val safeFront = frontStrength.coerceIn(0f, 1f)
    surface.corePaint.strokeCap = lightImpulseOwnedBeamCap()
    drawConnectedImpulseHalf(
        canvas, geometry, origin, forwardDistance, forwardDirection, alpha, safeSource, safeFront,
        sourceAnchored, birthSourceRadius
    )
    drawConnectedImpulseHalf(
        canvas, geometry, origin, reverseDistance, reverseDirection, alpha, safeSource, safeFront,
        sourceAnchored, birthSourceRadius
    )
}

private fun drawConnectedImpulseHalf(
    canvas: Canvas,
    geometry: StyleGeometry,
    origin: Float,
    distance: Float,
    direction: Int,
    alpha: Int,
    sourceStrength: Float,
    frontStrength: Float,
    sourceAnchored: Boolean,
    birthSourceRadius: Float
) {
    if (distance <= 0f || (direction != 1 && direction != -1)) return
    val sampleLength = distance / LIGHT_BLOOM_SAMPLES
    var sample = 0
    while (sample < LIGHT_BLOOM_SAMPLES) {
        val position = (sample + 0.5f) / LIGHT_BLOOM_SAMPLES
        val strength = if (birthSourceRadius > 0f) {
            lightImpulseBirthFieldStrength(
                position = position,
                sourceRadiusFraction = birthSourceRadius / distance,
                sourceStrength = sourceStrength,
                frontStrength = frontStrength
            )
        } else {
            lightImpulseConnectedFieldStrength(
                position = position,
                sourceStrength = sourceStrength,
                edgeStrength = frontStrength,
                sourceAnchored = sourceAnchored
            )
        }
        drawSourceAnchoredImpulseSample(
            canvas = canvas,
            geometry = geometry,
            origin = origin,
            maxDistance = distance,
            direction = direction,
            distanceFromSource = sample * sampleLength,
            length = sampleLength,
            alpha = (alpha * strength.alphaFraction).roundToInt(),
            strokeWidth = surface.renderStrokeWidth * strength.widthFactor
        )
        sample++
    }
}

/** Draws one physical-edge-only tail with a core-owned head and a softly released body. */
internal fun drawTravellingLightImpulseFlow(
    canvas: Canvas,
    geometry: StyleGeometry,
    origin: Float,
    frontDistance: Float,
    maxDistance: Float,
    direction: Int,
    alpha: Int,
    timeline: Float,
    tailLimit: Float,
    sourceExclusionDistance: Float = 0f,
    fieldEnergy: Float = 1f,
    coreEnergy: Float = 1f
) {
    val tailLength = min(geometry.length * LIGHT_TAIL_FRACTION, tailLimit)
    val safeFieldEnergy = fieldEnergy.coerceIn(0f, 1f)
    val safeCoreEnergy = coreEnergy.coerceIn(0f, 1f)
    if (tailLength <= 0f || (safeFieldEnergy <= 0f && safeCoreEnergy <= 0f)) return
    // A round cap extends beyond its path end; owned beams must end at the source boundary.
    surface.corePaint.strokeCap = lightImpulseOwnedBeamCap()
    val coreLength = min(geometry.length * LIGHT_CORE_FRACTION, tailLength * 0.60f)
    val coreSpan = coreLength * LIGHT_CORE_SPAN_MULTIPLIER
    // The body begins after the core's occupied interval. This is a spatial partition, not
    // an alpha trick: no high-energy tail sample is rasterized underneath the core.
    val bodyLength = (tailLength - coreSpan).coerceAtLeast(0f) *
            LIGHT_ATTACHED_TAIL_BODY_SHARE
    // Each interior sample overlaps its successor by 12%, but the final footprint must stay
    // inside the field length selected by the production plan.
    val sampleLength = bodyLength / (LIGHT_TAIL_SAMPLES - 1 + 1.12f)
    // The shared-birth field has no independent pulse. Keep the subsequent core deterministic
    // too, so its emission meets the connected front without a brightness step.
    val organicPulse = 1f
    var sample = 0
    while (sample < LIGHT_TAIL_SAMPLES && sampleLength > 0f) {
        val position = (sample + 0.5f) / LIGHT_TAIL_SAMPLES
        // Leave the head to the core. This removes the former tail-plus-core pile-up at
        // the moving source while letting the released body fade close behind that core.
        val taperedEnergy = lightImpulseTailSampleStrength(position)
        val fieldAlpha = (
            alpha * safeFieldEnergy *
                    (0.46f * taperedEnergy) *
                    organicPulse
            )
            .roundToInt()
        val fieldWidth = surface.renderStrokeWidth *
                (0.46f + 0.76f * taperedEnergy) * (0.60f + 0.40f * safeFieldEnergy)
        drawDirectionalImpulseSample(
            canvas = canvas,
            geometry = geometry,
            origin = origin,
            frontDistance = frontDistance,
            maxDistance = maxDistance,
            direction = direction,
            distanceBehindFront = coreSpan + sample * sampleLength,
            // A small overlap prevents raster seams without repeatedly summing the same
            // energy into visibly striped or thick bands.
            length = sampleLength * 1.12f,
            minSourceDistance = sourceExclusionDistance,
            alpha = fieldAlpha,
            strokeWidth = fieldWidth
        )
        sample++
    }

    if (safeCoreEnergy <= 0f) return
    drawContinuousImpulseCore(
        canvas, geometry, origin, frontDistance, maxDistance, direction, alpha, organicPulse,
        coreLength, safeCoreEnergy, sourceExclusionDistance
    )
}

/** Dedicated impulse owns the converge choreography; this supplies its calibrated primitives. */
internal fun drawConvergingLightImpulse(
    canvas: Canvas,
    geometry: StyleGeometry,
    bottom: Float,
    endpoints: LightImpulseEndpoints,
    alpha: Int,
    timeline: Float,
    passEnergy: LightImpulsePassEnergy,
    plan: LightImpulseProductionDrawPlan
) {
    val ownership = lightImpulseConvergenceOwnership(endpoints)
    val drawPlan = lightImpulseProductionDrawPlan(
        timeline, geometry.length, ownership.forwardMaxDistance, ownership.reverseMaxDistance, plan
    )
    val bloomRadius = drawPlan.sourceBloomRadius
    if (drawPlan.sourceBloomVisible) {
        drawLightImpulseBloom(canvas, geometry, bottom, alpha, bloomRadius, passEnergy.bottomBloom)
    }
    drawSourceAnchoredImpulseFlow(canvas, geometry, bottom, ownership.forwardMaxDistance,
        ownership.forwardDirection, ownership.forwardDirection, alpha, timeline,
        drawPlan.forwardFieldLength, bloomRadius, passEnergy.tailPerBranch)
    drawSourceAnchoredImpulseCore(canvas, geometry, bottom, ownership.forwardMaxDistance,
        ownership.forwardDirection, ownership.forwardDirection, alpha, timeline,
        drawPlan.forwardFieldLength, bloomRadius, passEnergy.corePerBranch)
    drawSourceAnchoredImpulseFlow(canvas, geometry, bottom, ownership.reverseMaxDistance,
        ownership.reverseDirection, ownership.reverseDirection, alpha, timeline,
        drawPlan.reverseFieldLength, bloomRadius, passEnergy.tailPerBranch)
    drawSourceAnchoredImpulseCore(canvas, geometry, bottom, ownership.reverseMaxDistance,
        ownership.reverseDirection, ownership.reverseDirection, alpha, timeline,
        drawPlan.reverseFieldLength, bloomRadius, passEnergy.corePerBranch)
}

/**
 * Mirrors travel's field around the bottom source. At the handoff, each sample has the same
 * path interval and pulse as its arriving counterpart; it can then shorten into that source.
 */
private fun drawSourceAnchoredImpulseFlow(
    canvas: Canvas,
    geometry: StyleGeometry,
    origin: Float,
    maxDistance: Float,
    direction: Int,
    pulseDirection: Int,
    alpha: Int,
    timeline: Float,
    tailLimit: Float,
    sourceExclusionDistance: Float,
    fieldEnergy: Float
) {
    val resolvedTailLength = min(geometry.length * LIGHT_TAIL_FRACTION, tailLimit)
    val safeFieldEnergy = fieldEnergy.coerceIn(0f, 1f)
    val availableOwnedLength = (resolvedTailLength - sourceExclusionDistance).coerceAtLeast(0f)
    if (availableOwnedLength <= 0f || safeFieldEnergy <= 0f) return
    surface.corePaint.strokeCap = lightImpulseOwnedBeamCap()
    val coreLength = min(geometry.length * LIGHT_CORE_FRACTION, availableOwnedLength * 0.60f)
    val coreSpan = coreLength * LIGHT_CORE_SPAN_MULTIPLIER
    val bodyLength = (availableOwnedLength - coreSpan).coerceAtLeast(0f) *
            LIGHT_ATTACHED_TAIL_BODY_SHARE
    val sampleLength = bodyLength / (LIGHT_TAIL_SAMPLES - 1 + 1.12f)
    val organicPulse = 0.94f + 0.06f * sin(timeline * 19f + pulseDirection * 0.7f)
    var sample = 0
    while (sample < LIGHT_TAIL_SAMPLES && sampleLength > 0f) {
        val position = (sample + 0.5f) / LIGHT_TAIL_SAMPLES
        val taperedEnergy = lightImpulseTailSampleStrength(position)
        drawSourceAnchoredImpulseSample(
            canvas = canvas,
            geometry = geometry,
            origin = origin,
            maxDistance = maxDistance,
            direction = direction,
            distanceFromSource = sourceExclusionDistance + coreSpan + sample * sampleLength,
            length = sampleLength * 1.12f,
            alpha = (alpha * safeFieldEnergy * 0.46f * taperedEnergy * organicPulse).roundToInt(),
            strokeWidth = surface.renderStrokeWidth * (0.46f + 0.76f * taperedEnergy) *
                    (0.60f + 0.40f * safeFieldEnergy)
        )
        sample++
    }
}

internal fun drawLightImpulseBloom(
    canvas: Canvas,
    geometry: StyleGeometry,
    center: Float,
    alpha: Int,
    radius: Float,
    bloom: Float,
    matchConnectedSourcePeak: Boolean = false,
    matchSharedSourcePeak: Boolean = false
) {
    val safeBloom = bloom.coerceIn(0f, 1f)
    if (safeBloom <= 0f || radius <= 0f) return
    // A butt cap makes the raster footprint end exactly at the reserved source radius.
    // ROUND would add half a stroke width outside it, underneath the owned core.
    surface.corePaint.strokeCap = lightImpulseBloomCap()
    val sampleLength = radius * 2f / LIGHT_BLOOM_SAMPLES
    var sample = 0
    while (sample < LIGHT_BLOOM_SAMPLES) {
        val normalized = ((sample + 0.5f) / LIGHT_BLOOM_SAMPLES) * 2f - 1f
        val energy = (1f - normalized * normalized).coerceAtLeast(0f)
        val softenedEnergy = energy * energy
        val terminalStrength = if (matchConnectedSourcePeak) {
            lightImpulseTerminalBloomStrength(normalized, safeBloom)
        } else if (matchSharedSourcePeak) {
            lightImpulseSharedSourceBloomStrength(normalized, safeBloom)
        } else {
            null
        }
        drawImpulseSegment(
            canvas = canvas,
            geometry = geometry,
            center = center + normalized * radius,
            // Keep bloom inside its reserved source interval. Adjacent source samples meet
            // at their bounds instead of bleeding underneath an owned core outside radius.
            length = sampleLength,
            alpha = if (terminalStrength != null) {
                (alpha * terminalStrength.alphaFraction).roundToInt()
            } else {
                (alpha * safeBloom * (0.025f + 0.58f * softenedEnergy)).roundToInt()
            },
            strokeWidth = surface.renderStrokeWidth * (terminalStrength?.widthFactor
                ?: (0.72f + 1.75f * softenedEnergy))
        )
        sample++
    }
}

/** A one-pass longitudinal energy distribution replaces concentric core strokes. */
private fun drawContinuousImpulseCore(
    canvas: Canvas,
    geometry: StyleGeometry,
    origin: Float,
    frontDistance: Float,
    maxDistance: Float,
    direction: Int,
    alpha: Int,
    organicPulse: Float,
    coreLength: Float,
    coreEnergy: Float,
    minSourceDistance: Float
) {
    val coreSpan = coreLength * LIGHT_CORE_SPAN_MULTIPLIER
    val sampleLength = coreSpan / LIGHT_CORE_SAMPLES
    var sample = 0
    while (sample < LIGHT_CORE_SAMPLES) {
        val position = (sample + 0.5f) / LIGHT_CORE_SAMPLES
        // Start at zero at the physical leading edge, then bloom into a compact peak. This
        // keeps the front tapered from the first frame rather than only at convergence.
        val softenedEnergy = lightImpulseTaperedFrontStrength(position)
        drawDirectionalImpulseSample(
            canvas = canvas,
            geometry = geometry,
            origin = origin,
            frontDistance = frontDistance,
            maxDistance = maxDistance,
            direction = direction,
            distanceBehindFront = sample * sampleLength,
            // Core and tail own adjacent, not overlapping, intervals. Internal tail samples
            // retain their own small overlap; it never crosses this ownership seam.
            length = sampleLength,
            minSourceDistance = minSourceDistance,
            alpha = (alpha * coreEnergy * organicPulse * softenedEnergy).roundToInt(),
            strokeWidth = surface.renderStrokeWidth * (0.70f + 1.20f * softenedEnergy) *
                    (0.58f + 0.42f * coreEnergy)
        )
        sample++
    }
}

/**
 * The final cores shrink toward a shared bottom source. Its sample coordinates are measured
 * from that source, preserving the arrival position from the preceding travel frame.
 */
private fun drawSourceAnchoredImpulseCore(
    canvas: Canvas,
    geometry: StyleGeometry,
    origin: Float,
    maxDistance: Float,
    direction: Int,
    pulseDirection: Int,
    alpha: Int,
    timeline: Float,
    tailLength: Float,
    sourceExclusionDistance: Float,
    coreEnergy: Float
) {
    val availableOwnedLength = (tailLength - sourceExclusionDistance).coerceAtLeast(0f)
    if (availableOwnedLength <= 0f || coreEnergy <= 0f) return
    surface.corePaint.strokeCap = lightImpulseOwnedBeamCap()
    val coreLength = min(geometry.length * LIGHT_CORE_FRACTION, availableOwnedLength * 0.60f)
    val coreSpan = coreLength * LIGHT_CORE_SPAN_MULTIPLIER
    val sampleLength = coreSpan / LIGHT_CORE_SAMPLES
    val organicPulse = 0.94f + 0.06f * sin(timeline * 19f + pulseDirection * 0.7f)
    var sample = 0
    while (sample < LIGHT_CORE_SAMPLES) {
        val position = (sample + 0.5f) / LIGHT_CORE_SAMPLES
        // The same pointed profile is measured outward from the bottom source. The two
        // low-energy tips meet the reserved bloom, which turns their reunion into one local
        // glint instead of two blunt caps stacking at the endpoint.
        val softenedEnergy = lightImpulseTaperedFrontStrength(position)
        drawSourceAnchoredImpulseSample(
            canvas = canvas,
            geometry = geometry,
            origin = origin,
            maxDistance = maxDistance,
            direction = direction,
            distanceFromSource = sourceExclusionDistance + sample * sampleLength,
            // Match travel's butt-to-butt core/tail ownership boundary.
            length = sampleLength,
            alpha = (alpha * coreEnergy * organicPulse * softenedEnergy).roundToInt(),
            strokeWidth = surface.renderStrokeWidth * (0.70f + 1.20f * softenedEnergy) *
                    (0.58f + 0.42f * coreEnergy)
        )
        sample++
    }
}

private fun drawDirectionalImpulseSample(
    canvas: Canvas,
    geometry: StyleGeometry,
    origin: Float,
    frontDistance: Float,
    maxDistance: Float,
    direction: Int,
    distanceBehindFront: Float,
    length: Float,
    minSourceDistance: Float,
    alpha: Int,
    strokeWidth: Float
) {
    val segment = lightImpulseOwnedSegment(
        origin = origin,
        direction = direction,
        frontDistance = frontDistance,
        distanceBehindFront = distanceBehindFront,
        requestedLength = length,
        maxDistance = maxDistance,
        minSourceDistance = minSourceDistance,
        out = impulseOwnedSegment
    ) ?: return
    drawImpulseSegment(
        canvas = canvas,
        geometry = geometry,
        center = segment.pathCenter,
        length = segment.endDistance - segment.startDistance,
        alpha = alpha,
        strokeWidth = strokeWidth
    )
}

private fun drawSourceAnchoredImpulseSample(
    canvas: Canvas,
    geometry: StyleGeometry,
    origin: Float,
    maxDistance: Float,
    direction: Int,
    distanceFromSource: Float,
    length: Float,
    alpha: Int,
    strokeWidth: Float
) {
    val segment = lightImpulseOwnedSegment(
        origin = origin,
        direction = direction,
        frontDistance = distanceFromSource + length,
        distanceBehindFront = 0f,
        requestedLength = length,
        maxDistance = maxDistance,
        out = impulseOwnedSegment
    ) ?: return
    drawImpulseSegment(
        canvas = canvas,
        geometry = geometry,
        center = segment.pathCenter,
        length = segment.endDistance - segment.startDistance,
        alpha = alpha,
        strokeWidth = strokeWidth
    )
}

private fun drawImpulseSegment(
    canvas: Canvas,
    geometry: StyleGeometry,
    center: Float,
    length: Float,
    alpha: Int,
    strokeWidth: Float
) {
    if (alpha <= 0 || !lightImpulseTravellingSegmentIsSafe(length, geometry.length)) return
    if (surface.corePaint.shader == null) {
        surface.corePaint.color = colorWithAlpha(alpha, surface.colorRgb)
    } else {
        surface.corePaint.alpha = alpha
    }
    surface.corePaint.strokeWidth = strokeWidth
    drawWrappedSegment(canvas, geometry.measure, geometry.length, center - length / 2f, length, surface.corePaint)
}

private fun drawWrappedSegment(
    canvas: Canvas,
    measure: PathMeasure,
    length: Float,
    start: Float,
    segmentLength: Float,
    paint: Paint
) {
    if (EdgeSegmentRenderer.append(
            measure = measure,
            contourLength = length,
            start = start,
            segmentLength = segmentLength,
            out = segmentPath,
            allowFullContour = false
        )) {
        canvas.drawPath(segmentPath, paint)
    }
}

private fun endpointsFor(geometry: StyleGeometry): LightImpulseEndpoints {
    cachedEndpoints
        ?.takeIf { endpointGeometry === geometry }
        ?.let { return it }
    return lightImpulseEndpoints(
        measure = geometry.measure,
        length = geometry.length,
        bounds = geometry.bounds
    ).also {
        endpointGeometry = geometry
        cachedEndpoints = it
    }
}


}

