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
// Keep the owned fields on screen long enough to release their energy into the
// bottom source. A short handoff makes the tail look as if it was cut off.
private const val LIGHT_CONVERGE_END = 0.92f
private const val LIGHT_TAIL_FRACTION = 0.24f
private const val LIGHT_CORE_FRACTION = 0.022f
private const val LIGHT_TERMINAL_SOURCE_RADIUS_FRACTION = 0.040f
private const val LIGHT_TAIL_SAMPLES = 40
private const val LIGHT_CORE_SAMPLES = 28
private const val LIGHT_BLOOM_SAMPLES = 40
private const val LIGHT_BRANCH_EMERGENCE_TRAVEL = 0.16f
// Tail and core own neighbouring intervals, so a saturated core need not be dimmed to share a
// global alpha budget with a tail that is never rasterized underneath it.
private const val LIGHT_TAIL_PASS_SHARE = 0.42f
private const val LIGHT_CORE_PASS_SHARE = 1f

internal fun lightImpulseTravelProgress(progress: Float): Float =
    lightImpulseSmoothStep(
        (progress - LIGHT_IGNITION_END) /
                (LIGHT_TRAVEL_END - LIGHT_IGNITION_END)
    )

/**
 * Strength allocated to each directional branch. The branches occupy disjoint physical paths,
 * so their visual strength is not divided in half merely because there are two of them.
 */
internal fun lightImpulseBranchEnergy(progress: Float): Float = when {
    progress <= LIGHT_IGNITION_END -> 0f
    progress < LIGHT_TRAVEL_END -> lightImpulseSmoothStep(
        lightImpulseTravelProgress(progress) / LIGHT_BRANCH_EMERGENCE_TRAVEL
    )
    else -> 1f
}

internal fun lightImpulseOriginGlow(progress: Float): Float = when {
    progress <= LIGHT_IGNITION_END -> lightImpulseSmoothStep(progress / LIGHT_IGNITION_END)
    progress < LIGHT_TRAVEL_END -> 1f - lightImpulseBranchEnergy(progress)
    else -> 0f
}

/** The energy retained by one owned branch while the two branches reunite at the bottom. */
internal fun lightImpulseConvergenceEnergy(progress: Float): Float = when {
    progress < LIGHT_TRAVEL_END -> 1f
    progress < LIGHT_CONVERGE_END -> 1f - lightImpulseSmoothStep(
        (progress - LIGHT_TRAVEL_END) / (LIGHT_CONVERGE_END - LIGHT_TRAVEL_END)
    )
    else -> 0f
}

/**
 * The first part of travel is a single connected source field, not a bloom plus two beams.
 * Keeping this separate from the later travelling body prevents a visible hand-off stack.
 */
internal fun lightImpulseUsesSharedBirthField(progress: Float): Boolean =
    lightImpulsePhase(progress) == LightImpulsePhase.TRAVEL &&
        lightImpulseTravelProgress(progress) < LIGHT_BRANCH_EMERGENCE_TRAVEL

internal data class LightImpulseFieldStrength(
    val alphaFraction: Float,
    val widthFactor: Float
)

/**
 * Longitudinal strength for the one-pass shared field. With a travelling front at full strength,
 * its peak exactly matches the later owned core's full-alpha, 1.90x-width emission.
 */
internal fun lightImpulseConnectedFieldStrength(
    position: Float,
    sourceStrength: Float,
    edgeStrength: Float,
    sourceAnchored: Boolean
): LightImpulseFieldStrength {
    val safePosition = position.coerceIn(0f, 1f)
    val source = (1f - safePosition) * (1f - safePosition) * sourceStrength.coerceIn(0f, 1f)
    val edge = if (sourceAnchored) {
        0.32f * safePosition * safePosition * edgeStrength.coerceIn(0f, 1f)
    } else {
        lightImpulseSmoothStep((safePosition - 0.52f) / 0.48f) * edgeStrength.coerceIn(0f, 1f)
    }
    val bridge = 0.32f * lightImpulseSmoothStep(safePosition / 0.25f) *
            lightImpulseSmoothStep((1f - safePosition) / 0.25f) *
            maxOf(sourceStrength, edgeStrength).coerceIn(0f, 1f)
    val energy = (source + edge + bridge).coerceIn(0f, 1f)
    return LightImpulseFieldStrength(
        alphaFraction = energy,
        widthFactor = 0.70f + 1.20f * energy
    )
}

/** The core's peak emission after birth; shared-field cutoff must meet this value. */
internal fun lightImpulseTravelCorePeakStrength(progress: Float): LightImpulseFieldStrength {
    val energy = lightImpulseCorePassEnergy(lightImpulseBranchEnergy(progress))
    return LightImpulseFieldStrength(energy, 0.70f + 1.20f * energy)
}

/** The bottom source remains optically charged as incoming energy changes into bloom. */
internal fun lightImpulseConvergenceSourceStrength(progress: Float): Float =
    (lightImpulseConvergenceEnergy(progress) + lightImpulseBottomBloom(progress)).coerceIn(0f, 1f)

/** The connected bottom field retracts into, rather than disappears before, the fade source. */
internal fun lightImpulseConvergenceFieldRadiusFraction(progress: Float): Float {
    val convergence = lightImpulseSmoothStep(
        (progress.coerceIn(LIGHT_TRAVEL_END, LIGHT_CONVERGE_END) - LIGHT_TRAVEL_END) /
                (LIGHT_CONVERGE_END - LIGHT_TRAVEL_END)
    )
    return LIGHT_TAIL_FRACTION * (1f - convergence) +
            LIGHT_TERMINAL_SOURCE_RADIUS_FRACTION * convergence
}

/** Fade starts at the exact convergence source radius, then contracts with the same zero-slope fade. */
internal fun lightImpulseTerminalBloomRadiusFraction(progress: Float): Float =
    0.012f + (LIGHT_TERMINAL_SOURCE_RADIUS_FRACTION - 0.012f) * lightImpulseFadeEnergy(progress)

/** Both the arriving core and the converging source put their brightest footprint at the endpoint. */
internal fun lightImpulseCorePeakOffsetFraction(): Float = 0f

/** Terminal bloom starts with the same peak profile as the connected bottom source. */
internal fun lightImpulseTerminalBloomStrength(
    normalizedDistance: Float,
    fade: Float
): LightImpulseFieldStrength {
    val energy = (1f - normalizedDistance * normalizedDistance).coerceAtLeast(0f)
    val softened = energy * energy
    return LightImpulseFieldStrength(
        alphaFraction = fade.coerceIn(0f, 1f) * softened,
        widthFactor = 0.70f + 1.20f * softened
    )
}

/**
 * The shared bottom source receives exactly the energy released by both owned branches.
 * It intentionally stays dark during travel: pre-charging a full bloom underneath two full
 * arriving fields is perceived as a stacked, thick endpoint.
 */
internal fun lightImpulseBottomBloom(progress: Float): Float = when {
    progress < LIGHT_TRAVEL_END -> 0f
    progress < LIGHT_CONVERGE_END -> lightImpulseSmoothStep(
        (progress - LIGHT_TRAVEL_END) / (LIGHT_CONVERGE_END - LIGHT_TRAVEL_END)
    )
    else -> 1f
}

internal fun lightImpulseBottomBloomRadiusFraction(progress: Float): Float = when {
    progress < LIGHT_TRAVEL_END -> 0.012f
    progress < LIGHT_CONVERGE_END -> 0.012f + 0.028f * lightImpulseSmoothStep(
        (progress - LIGHT_TRAVEL_END) / (LIGHT_CONVERGE_END - LIGHT_TRAVEL_END)
    )
    else -> LIGHT_TERMINAL_SOURCE_RADIUS_FRACTION
}

/** A long, zero-slope terminal release avoids a last-frame brightness cutoff. */
internal fun lightImpulseFadeEnergy(progress: Float): Float =
    1f - lightImpulseSmoothStep(
        (progress - LIGHT_CONVERGE_END) / (1f - LIGHT_CONVERGE_END)
    )

internal fun lightImpulseTailPassEnergy(ownedBranchEnergy: Float): Float =
    ownedBranchEnergy.coerceIn(0f, 1f) * LIGHT_TAIL_PASS_SHARE

internal fun lightImpulseCorePassEnergy(ownedBranchEnergy: Float): Float =
    ownedBranchEnergy.coerceIn(0f, 1f) * LIGHT_CORE_PASS_SHARE

/** The actual source/bloom input used by the renderer, including terminal fade. */
internal fun lightImpulseBottomBloomPassEnergy(progress: Float): Float = when {
    progress < LIGHT_CONVERGE_END -> lightImpulseBottomBloom(progress)
    else -> lightImpulseFadeEnergy(progress)
}

/**
 * Pass strengths used after the shared source field has opened into physically separate paths.
 * They are deliberately not a global alpha budget: the two paths never rasterize the same edge.
 */
internal data class LightImpulsePassEnergy(
    val originBloom: Float,
    val tailPerBranch: Float,
    val corePerBranch: Float,
    val bottomBloom: Float
) {
    fun total(): Float = originBloom + 2f * (tailPerBranch + corePerBranch) + bottomBloom
}

internal fun lightImpulsePassEnergy(progress: Float): LightImpulsePassEnergy {
    val safeProgress = progress.coerceIn(0f, 1f)
    return when (lightImpulsePhase(safeProgress)) {
        LightImpulsePhase.IGNITION -> LightImpulsePassEnergy(
            originBloom = lightImpulseOriginGlow(safeProgress),
            tailPerBranch = 0f,
            corePerBranch = 0f,
            bottomBloom = 0f
        )
        LightImpulsePhase.TRAVEL -> {
            val branch = lightImpulseBranchEnergy(safeProgress)
            LightImpulsePassEnergy(
                originBloom = lightImpulseOriginGlow(safeProgress),
                tailPerBranch = lightImpulseTailPassEnergy(branch),
                corePerBranch = lightImpulseCorePassEnergy(branch),
                bottomBloom = 0f
            )
        }
        LightImpulsePhase.CONVERGE -> {
            val branch = lightImpulseConvergenceEnergy(safeProgress)
            LightImpulsePassEnergy(
                originBloom = 0f,
                tailPerBranch = lightImpulseTailPassEnergy(branch),
                corePerBranch = lightImpulseCorePassEnergy(branch),
                bottomBloom = lightImpulseBottomBloomPassEnergy(safeProgress)
            )
        }
        LightImpulsePhase.FADE -> LightImpulsePassEnergy(
            originBloom = 0f,
            tailPerBranch = 0f,
            corePerBranch = 0f,
            bottomBloom = lightImpulseBottomBloomPassEnergy(safeProgress)
        )
    }
}

/** One-sided local coverage for the source-anchored convergence renderer. */
internal data class LightImpulseLocalCoverage(
    val bloom: Float = 0f,
    val core: Float = 0f,
    val tail: Float = 0f
) {
    fun total(): Float = bloom + core + tail
    fun activePassCount(): Int = listOf(bloom, core, tail).count { it > 0f }
}

internal fun lightImpulseCoreSpanFraction(
    totalFlowFraction: Float,
    sourceExclusionFraction: Float
): Float = min(
    LIGHT_CORE_FRACTION * 3.2f,
    (totalFlowFraction - sourceExclusionFraction).coerceAtLeast(0f) * 0.60f
)

/**
 * Mirrors the spatial reservation in the convergence draw calls. Distances are normalized to
 * outline length and measured outward from bottom on either owned side.
 */
internal fun lightImpulseConvergenceCoverage(
    progress: Float,
    distanceFromBottomFraction: Float
): LightImpulseLocalCoverage {
    if (lightImpulsePhase(progress) != LightImpulsePhase.CONVERGE) {
        return LightImpulseLocalCoverage()
    }
    val safeProgress = progress.coerceIn(0f, 1f)
    val distance = distanceFromBottomFraction.coerceAtLeast(0f)
    val passes = lightImpulsePassEnergy(safeProgress)
    // Keep this pure reservation model aligned with the renderer: the final connected field
    // retains the finite source span that the fade bloom inherits at the phase boundary.
    val totalFlowLength = lightImpulseConvergenceFieldRadiusFraction(safeProgress)
    val bloomRadius = if (passes.bottomBloom > 0f) {
        lightImpulseBottomBloomRadiusFraction(safeProgress)
    } else {
        0f
    }
    if (distance < bloomRadius) return LightImpulseLocalCoverage(bloom = passes.bottomBloom)

    val coreSpan = lightImpulseCoreSpanFraction(totalFlowLength, bloomRadius)
    if (distance < bloomRadius + coreSpan) {
        return LightImpulseLocalCoverage(core = passes.corePerBranch)
    }
    if (distance < totalFlowLength) {
        return LightImpulseLocalCoverage(tail = passes.tailPerBranch)
    }
    return LightImpulseLocalCoverage()
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

/**
 * A physical beam segment expressed as distance away from its shared source.
 *
 * Keeping these values source-relative is deliberate: a positive segment can be mapped only
 * onto its own directional half of the outline, so a core or tail can never cross the source
 * and become stacked geometry on the opposing path.
 */
internal class LightImpulseOwnedSegment(
    var startDistance: Float = 0f,
    var endDistance: Float = 0f,
    var pathCenter: Float = 0f
)

/** The bottom source uses the inverse direction of each top-origin branch. */
internal data class LightImpulseConvergenceOwnership(
    val forwardDirection: Int,
    val forwardMaxDistance: Float,
    val reverseDirection: Int,
    val reverseMaxDistance: Float
)

internal fun lightImpulseConvergenceOwnership(
    endpoints: LightImpulseEndpoints
): LightImpulseConvergenceOwnership = LightImpulseConvergenceOwnership(
    // bottom = top + forwardDistance: retract the forward (positive) branch toward top.
    forwardDirection = -1,
    forwardMaxDistance = endpoints.forwardDistance,
    // The reverse branch reaches bottom from negative top-origin coordinates.
    reverseDirection = 1,
    reverseMaxDistance = endpoints.reverseDistance
)

/** Owned cores and tails use a non-projecting cap at the shared-source boundary. */
internal fun lightImpulseOwnedBeamCap(): Paint.Cap = Paint.Cap.BUTT

/** Source bloom is contained by its reservation; round caps would extend past that footprint. */
internal fun lightImpulseBloomCap(): Paint.Cap = Paint.Cap.BUTT

internal fun lightImpulseOwnedSegment(
    origin: Float,
    direction: Int,
    frontDistance: Float,
    distanceBehindFront: Float,
    requestedLength: Float,
    maxDistance: Float,
    minSourceDistance: Float = 0f,
    out: LightImpulseOwnedSegment = LightImpulseOwnedSegment()
): LightImpulseOwnedSegment? {
    if (direction != 1 && direction != -1) return null
    if (frontDistance <= 0f || requestedLength <= 0f || maxDistance <= 0f) return null
    val end = (frontDistance - distanceBehindFront).coerceIn(0f, maxDistance)
    val start = (end - requestedLength).coerceAtLeast(minSourceDistance.coerceIn(0f, maxDistance))
    if (end <= start) return null
    out.startDistance = start
    out.endDistance = end
    out.pathCenter = origin + direction * ((start + end) / 2f)
    return out
}

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

    /** Reused by owned-beam sampling; branch geometry must not allocate per frame. */
    private val impulseOwnedSegment =
        LightImpulseOwnedSegment()

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
        // The impulse owns its 0.92–1.00 fade; the generic finite envelope must not
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
            corePaint.strokeCap = Paint.Cap.ROUND
            corePaint.shader = null
            val passEnergy = lightImpulsePassEnergy(timeline)
            when (lightImpulsePhase(timeline)) {
                LightImpulsePhase.IGNITION -> {
                    val ignition = passEnergy.originBloom
                    drawImpulseBloom(
                        canvas, geometry, origin, alpha,
                        radius = geometry.length * (0.012f + ignition * 0.026f),
                        bloom = ignition
                    )
                }

                LightImpulsePhase.TRAVEL -> {
                    val travel = lightImpulseTravelProgress(timeline)
                    if (lightImpulseUsesSharedBirthField(timeline)) {
                        // This is one connected field with a shared centre, sampled outward into
                        // two owned halves. It replaces the former bloom-to-two-beams hand-off.
                        drawConnectedImpulseField(
                            canvas = canvas,
                            geometry = geometry,
                            origin = origin,
                            forwardDistance = forwardDistance * travel,
                            reverseDistance = reverseDistance * travel,
                            forwardDirection = 1,
                            reverseDirection = -1,
                            alpha = alpha,
                            sourceStrength = lightImpulseOriginGlow(timeline),
                            frontStrength = lightImpulseBranchEnergy(timeline)
                        )
                        return
                    }
                    val originGlow = passEnergy.originBloom
                    val originBloomRadius = geometry.length * (0.018f + originGlow * 0.020f)
                    if (originGlow > 0f) {
                        drawImpulseBloom(
                            canvas, geometry, origin, alpha,
                            radius = originBloomRadius,
                            bloom = originGlow
                        )
                    }
                    val forwardFront = forwardDistance * travel
                    val reverseFront = reverseDistance * travel
                    // Each flow is source-relative and is clipped to its directional half.
                    // The one origin bloom is emitted light; no beam body exists on both halves.
                    drawImpulseFlow(
                        canvas, geometry, origin, forwardFront, forwardDistance, 1, alpha, timeline,
                        tailLimit = forwardDistance * travel,
                        sourceExclusionDistance = if (originGlow > 0f) originBloomRadius else 0f,
                        fieldEnergy = passEnergy.tailPerBranch,
                        coreEnergy = passEnergy.corePerBranch
                    )
                    drawImpulseFlow(
                        canvas, geometry, origin, reverseFront, reverseDistance, -1, alpha, timeline,
                        tailLimit = reverseDistance * travel,
                        sourceExclusionDistance = if (originGlow > 0f) originBloomRadius else 0f,
                        fieldEnergy = passEnergy.tailPerBranch,
                        coreEnergy = passEnergy.corePerBranch
                    )
                }

                LightImpulsePhase.CONVERGE -> {
                    val remainingFlow = geometry.length * lightImpulseConvergenceFieldRadiusFraction(timeline)
                    val ownership = lightImpulseConvergenceOwnership(endpoints)
                    // The field retracts into one source instead of drawing an arriving pair
                    // under a separately growing bloom. Each half owns its side of bottom.
                    drawConnectedImpulseField(
                        canvas = canvas,
                        geometry = geometry,
                        origin = bottom,
                        forwardDistance = min(remainingFlow, ownership.forwardMaxDistance),
                        reverseDistance = min(remainingFlow, ownership.reverseMaxDistance),
                        forwardDirection = ownership.forwardDirection,
                        reverseDirection = ownership.reverseDirection,
                        alpha = alpha,
                        sourceStrength = lightImpulseConvergenceSourceStrength(timeline),
                        frontStrength = lightImpulseConvergenceEnergy(timeline),
                        sourceAnchored = true
                    )
                }

                LightImpulsePhase.FADE -> {
                    val fade = passEnergy.bottomBloom
                    drawImpulseBloom(
                        canvas, geometry, bottom, alpha,
                        // Let the afterglow contract as it dissipates instead of leaving a
                        // fixed, faint endpoint that vanishes with the final animation frame.
                        radius = geometry.length * lightImpulseTerminalBloomRadiusFraction(timeline),
                        bloom = fade,
                        matchConnectedSourcePeak = true
                    )
                }
            }
        } finally {
            corePaint.strokeCap = Paint.Cap.ROUND
            canvas.restoreToCount(save)
        }
    }

    private fun lightImpulseEndpointsFor(geometry: StyleGeometry): LightImpulseEndpoints =
        geometry.lightImpulseEndpoints ?: lightImpulseEndpoints(
            measure = geometry.measure,
            length = geometry.length,
            bounds = geometry.bounds
        ).also { geometry.lightImpulseEndpoints = it }

    /**
     * Draws one optical field centred on a source and split into two butt-to-butt owned halves.
     * No bloom/core/tail pass overlaps another here: every longitudinal sample owns one interval.
     */
    private fun drawConnectedImpulseField(
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
        sourceAnchored: Boolean = false
    ) {
        val safeSource = sourceStrength.coerceIn(0f, 1f)
        val safeFront = frontStrength.coerceIn(0f, 1f)
        corePaint.strokeCap = lightImpulseOwnedBeamCap()
        drawConnectedImpulseHalf(
            canvas, geometry, origin, forwardDistance, forwardDirection, alpha, safeSource, safeFront,
            sourceAnchored
        )
        drawConnectedImpulseHalf(
            canvas, geometry, origin, reverseDistance, reverseDirection, alpha, safeSource, safeFront,
            sourceAnchored
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
        sourceAnchored: Boolean
    ) {
        if (distance <= 0f || (direction != 1 && direction != -1)) return
        val sampleLength = distance / LIGHT_BLOOM_SAMPLES
        var sample = 0
        while (sample < LIGHT_BLOOM_SAMPLES) {
            val position = (sample + 0.5f) / LIGHT_BLOOM_SAMPLES
            val strength = lightImpulseConnectedFieldStrength(
                position = position,
                sourceStrength = sourceStrength,
                edgeStrength = frontStrength,
                sourceAnchored = sourceAnchored
            )
            drawSourceAnchoredImpulseSample(
                canvas = canvas,
                geometry = geometry,
                origin = origin,
                maxDistance = distance,
                direction = direction,
                distanceFromSource = sample * sampleLength,
                length = sampleLength,
                alpha = (alpha * strength.alphaFraction).roundToInt(),
                strokeWidth = renderStrokeWidth * strength.widthFactor
            )
            sample++
        }
    }

    /** Draws one physical-edge-only tail with a core-owned head and a softly released body. */
    private fun drawImpulseFlow(
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
        corePaint.strokeCap = lightImpulseOwnedBeamCap()
        val coreLength = min(geometry.length * LIGHT_CORE_FRACTION, tailLength * 0.60f)
        val coreSpan = coreLength * 3.2f
        // The body begins after the core's occupied interval. This is a spatial partition, not
        // an alpha trick: no high-energy tail sample is rasterized underneath the core.
        val bodyLength = (tailLength - coreSpan).coerceAtLeast(0f)
        val sampleLength = bodyLength / LIGHT_TAIL_SAMPLES
        // The shared-birth field has no independent pulse. Keep the subsequent core deterministic
        // too, so its emission meets the connected front without a brightness step.
        val organicPulse = 1f
        var sample = 0
        while (sample < LIGHT_TAIL_SAMPLES && sampleLength > 0f) {
            val position = (sample + 0.5f) / LIGHT_TAIL_SAMPLES
            val retainedEnergy = 1f - position
            // Leave the head to the core. This removes the former tail-plus-core pile-up at
            // the moving source while retaining a continuously fading body behind it.
            val taperedEnergy = retainedEnergy * retainedEnergy *
                    lightImpulseSmoothStep(position / 0.28f)
            val fieldAlpha = (
                alpha * safeFieldEnergy *
                        (0.38f * taperedEnergy) *
                        organicPulse
                )
                .roundToInt()
            val fieldWidth = renderStrokeWidth *
                    (0.38f + 0.72f * taperedEnergy) * (0.58f + 0.42f * safeFieldEnergy)
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
        corePaint.strokeCap = lightImpulseOwnedBeamCap()
        val coreLength = min(geometry.length * LIGHT_CORE_FRACTION, availableOwnedLength * 0.60f)
        val coreSpan = coreLength * 3.2f
        val bodyLength = (availableOwnedLength - coreSpan).coerceAtLeast(0f)
        val sampleLength = bodyLength / LIGHT_TAIL_SAMPLES
        val organicPulse = 0.94f + 0.06f * sin(timeline * 19f + pulseDirection * 0.7f)
        var sample = 0
        while (sample < LIGHT_TAIL_SAMPLES && sampleLength > 0f) {
            val position = (sample + 0.5f) / LIGHT_TAIL_SAMPLES
            val retainedEnergy = 1f - position
            val taperedEnergy = retainedEnergy * retainedEnergy *
                    lightImpulseSmoothStep(position / 0.28f)
            drawSourceAnchoredImpulseSample(
                canvas = canvas,
                geometry = geometry,
                origin = origin,
                maxDistance = maxDistance,
                direction = direction,
                distanceFromSource = sourceExclusionDistance + coreSpan + sample * sampleLength,
                length = sampleLength * 1.12f,
                alpha = (alpha * safeFieldEnergy * 0.38f * taperedEnergy * organicPulse).roundToInt(),
                strokeWidth = renderStrokeWidth * (0.38f + 0.72f * taperedEnergy) *
                        (0.58f + 0.42f * safeFieldEnergy)
            )
            sample++
        }
    }

    private fun drawImpulseBloom(
        canvas: Canvas,
        geometry: StyleGeometry,
        center: Float,
        alpha: Int,
        radius: Float,
        bloom: Float,
        matchConnectedSourcePeak: Boolean = false
    ) {
        val safeBloom = bloom.coerceIn(0f, 1f)
        if (safeBloom <= 0f || radius <= 0f) return
        // A butt cap makes the raster footprint end exactly at the reserved source radius.
        // ROUND would add half a stroke width outside it, underneath the owned core.
        corePaint.strokeCap = lightImpulseBloomCap()
        val sampleLength = radius * 2f / LIGHT_BLOOM_SAMPLES
        var sample = 0
        while (sample < LIGHT_BLOOM_SAMPLES) {
            val normalized = ((sample + 0.5f) / LIGHT_BLOOM_SAMPLES) * 2f - 1f
            val energy = (1f - normalized * normalized).coerceAtLeast(0f)
            val softenedEnergy = energy * energy
            val terminalStrength = if (matchConnectedSourcePeak) {
                lightImpulseTerminalBloomStrength(normalized, safeBloom)
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
                strokeWidth = renderStrokeWidth * (terminalStrength?.widthFactor
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
        val coreSpan = coreLength * 3.2f
        val sampleLength = coreSpan / LIGHT_CORE_SAMPLES
        var sample = 0
        while (sample < LIGHT_CORE_SAMPLES) {
            val position = (sample + 0.5f) / LIGHT_CORE_SAMPLES
            // The high-energy point sits just behind the front, with a smooth release behind it.
            val centred = (position - lightImpulseCorePeakOffsetFraction()) / 0.62f
            val energy = (1f - centred * centred).coerceIn(0f, 1f)
            val softenedEnergy = energy * energy
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
                strokeWidth = renderStrokeWidth * (0.70f + 1.20f * softenedEnergy) *
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
        corePaint.strokeCap = lightImpulseOwnedBeamCap()
        val coreLength = min(geometry.length * LIGHT_CORE_FRACTION, availableOwnedLength * 0.60f)
        val coreSpan = coreLength * 3.2f
        val sampleLength = coreSpan / LIGHT_CORE_SAMPLES
        val organicPulse = 0.94f + 0.06f * sin(timeline * 19f + pulseDirection * 0.7f)
        var sample = 0
        while (sample < LIGHT_CORE_SAMPLES) {
            val position = (sample + 0.5f) / LIGHT_CORE_SAMPLES
            val centred = (position - 0.24f) / 0.62f
            val energy = (1f - centred * centred).coerceIn(0f, 1f)
            val softenedEnergy = energy * energy
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
                strokeWidth = renderStrokeWidth * (0.70f + 1.20f * softenedEnergy) *
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
