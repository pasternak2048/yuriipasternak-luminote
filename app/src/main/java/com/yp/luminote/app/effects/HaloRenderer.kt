package com.yp.luminote.app.effects

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.SweepGradient
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.data.settings.HaloMotionDefinition
import com.yp.luminote.app.data.settings.HaloRenderFrame
import com.yp.luminote.app.data.settings.HaloRenderSurface
import com.yp.luminote.app.data.settings.HaloBladeVariant
import com.yp.luminote.app.data.settings.HaloFrame
import com.yp.luminote.app.data.settings.HaloMotion
import com.yp.luminote.app.data.settings.definition
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal enum class LightImpulsePhase { IGNITION, TRAVEL, CONVERGE, FADE }

/** Reminder owns the dedicated Light Impulse route; normal Impulse is registry-dispatched. */
internal fun usesLightImpulseRenderer(config: HaloConfig): Boolean =
    config.renderMode == HaloRenderMode.LIGHT_IMPULSE

/** Normal-app Impulse retains the conventional color preparation; dedicated impulse keeps its source color. */
internal fun impulseUsesNormalColorTreatment(config: HaloConfig): Boolean =
    config.renderMode != HaloRenderMode.LIGHT_IMPULSE && config.motion == HaloMotion.IMPULSE

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
// This is an aggregate contour budget, not a tail on each side.  Each branch owns half.
private const val LIGHT_AGGREGATE_TAIL_FRACTION = 0.33f
internal const val LIGHT_TAIL_FRACTION = LIGHT_AGGREGATE_TAIL_FRACTION / 2f
internal const val LIGHT_CORE_FRACTION = 0.022f
// The planned field still reserves 16.5% per branch, but its visible energy is intentionally
// concentrated close to the moving head.  A shorter physical tail prevents it reading as a
// delayed second beam while the enlarged body retains the useful 33% aggregate choreography.
internal const val LIGHT_CORE_SPAN_MULTIPLIER = 4.0f
internal const val LIGHT_ATTACHED_TAIL_BODY_SHARE = 0.55f
private const val LIGHT_TERMINAL_SOURCE_RADIUS_FRACTION = 0.040f
internal const val LIGHT_TAIL_SAMPLES = 40
internal const val LIGHT_CORE_SAMPLES = 28
internal const val LIGHT_BLOOM_SAMPLES = 40
private const val LIGHT_BRANCH_EMERGENCE_TRAVEL = 0.08f
// Tail and core own neighbouring intervals, so a saturated core need not be dimmed to share a
// global alpha budget with a tail that is never rasterized underneath it.
private const val LIGHT_TAIL_PASS_SHARE = 0.46f
private const val LIGHT_CORE_PASS_SHARE = 1f

internal fun lightImpulseTravelProgress(progress: Float): Float {
    val linear = ((progress - LIGHT_IGNITION_END) /
            (LIGHT_TRAVEL_END - LIGHT_IGNITION_END)).coerceIn(0f, 1f)
    // Preserve a compact birth, but do not give the released field a zero-velocity plateau.
    // The small linear component lets energy leave the impulse immediately; the smooth component
    // keeps the rest of the journey elegant rather than mechanical.
    return linear * (0.16f + 0.84f * lightImpulseSmoothStep(linear))
}

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

/** Peak profile shared by the top source and the first connected birth-field sample. */
internal fun lightImpulseSharedSourceBloomStrength(
    normalizedDistance: Float,
    sourceStrength: Float
): LightImpulseFieldStrength {
    val softened = (1f - normalizedDistance * normalizedDistance).coerceAtLeast(0f).let { it * it }
    val energy = sourceStrength.coerceIn(0f, 1f) * softened
    return LightImpulseFieldStrength(energy, 0.70f + 1.20f * energy)
}

/**
 * One selected birth field, not a bloom drawn underneath a beam.  Its inner part is the same
 * radial source profile; only the distance grown beyond that profile may become a travelling
 * front.  This keeps the whole cross-section continuous at the plan handoff.
 */
internal fun lightImpulseBirthFieldStrength(
    position: Float,
    sourceRadiusFraction: Float,
    sourceStrength: Float,
    frontStrength: Float
): LightImpulseFieldStrength {
    val safePosition = position.coerceIn(0f, 1f)
    val radius = sourceRadiusFraction.coerceIn(0f, 1f)
    val source = if (radius > 0f && safePosition <= radius) {
        lightImpulseSharedSourceBloomStrength(safePosition / radius, sourceStrength).alphaFraction
    } else {
        0f
    }
    val frontDistance = if (radius < 1f) {
        ((safePosition - radius) / (1f - radius)).coerceIn(0f, 1f)
    } else {
        0f
    }
    // At the instant the front reaches the source radius this is zero.  It gains energy only as
    // physical length is added outside that radius, so no bright head appears at the handoff.
    val frontActivation = if (radius > 0f) {
        lightImpulseSmoothStep(((1f - radius) / radius).coerceIn(0f, 1f))
    } else {
        1f
    }
    // `frontDistance == 1` is the physical leading edge.  Give that edge zero energy and
    // place the peak a small distance behind it, so the newborn field leaves the source as a
    // soft pointed wavefront rather than a butt-ended strip.
    val front = lightImpulseTaperedFrontStrength(1f - frontDistance) *
            frontStrength.coerceIn(0f, 1f) * frontActivation
    val energy = (source + front).coerceIn(0f, 1f)
    return LightImpulseFieldStrength(energy, 0.70f + 1.20f * energy)
}

internal fun lightImpulseProductionBirthFieldStrength(
    progress: Float,
    plan: LightImpulseProductionDrawPlan,
    distanceFromSource: Float
): LightImpulseFieldStrength {
    val distance = distanceFromSource.coerceAtLeast(0f)
    return when (plan.mode) {
        LightImpulseDrawMode.TOP_BLOOM -> {
            if (plan.sourceBloomRadius <= 0f || distance > plan.sourceBloomRadius) {
                LightImpulseFieldStrength(0f, 0.70f)
            } else {
                lightImpulseSharedSourceBloomStrength(
                    distance / plan.sourceBloomRadius,
                    lightImpulseOriginGlow(progress)
                )
            }
        }
        LightImpulseDrawMode.SHARED_BIRTH_FIELD -> {
            if (plan.forwardFieldLength <= 0f || distance > plan.forwardFieldLength) {
                LightImpulseFieldStrength(0f, 0.70f)
            } else {
                lightImpulseBirthFieldStrength(
                    position = distance / plan.forwardFieldLength,
                    sourceRadiusFraction = plan.sourceBloomRadius / plan.forwardFieldLength,
                    sourceStrength = lightImpulseOriginGlow(progress),
                    frontStrength = lightImpulseBranchEnergy(progress)
                )
            }
        }
        else -> LightImpulseFieldStrength(0f, 0.70f)
    }
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

/**
 * Pure coverage contract for the final travelling field. This explicitly excludes the local
 * terminal impulse, which is allowed to breathe independently after travelling geometry is gone.
 */
internal fun lightImpulseTravellingCoverageFraction(progress: Float): Float = when {
    !progress.isFinite() -> 0f
    progress < LIGHT_TRAVEL_END -> 0f
    progress < LIGHT_CONVERGE_END -> lightImpulseConvergenceFieldRadiusFraction(progress)
    else -> 0f
}

/**
 * The production draw contract.  Keeping this next to the timeline makes the
 * physical footprint testable instead of testing a parallel, unused model.
 * Coverage is normalized to the whole contour and excludes the local source
 * bloom, which is allowed to remain visible while a sub-pixel branch is born.
 */
internal enum class LightImpulseDrawMode {
    TOP_BLOOM,
    SHARED_BIRTH_FIELD,
    OWNED_TRAVEL_FIELDS,
    CONVERGING_FIELDS,
    BOTTOM_BLOOM
}

internal class LightImpulseProductionDrawPlan(
    var mode: LightImpulseDrawMode = LightImpulseDrawMode.TOP_BLOOM,
    var sourceBloomVisible: Boolean = false,
    var branchCount: Int = 0,
    var aggregateTravellingCoverageFraction: Float = 0f,
    var aggregatePhysicalCoverageFraction: Float = 0f,
    var forwardFieldLength: Float = 0f,
    var reverseFieldLength: Float = 0f,
    var sourceBloomRadius: Float = 0f
)

internal fun lightImpulseProductionDrawPlan(
    progress: Float,
    contourLength: Float = 10_000f,
    forwardMaxDistance: Float = contourLength / 2f,
    reverseMaxDistance: Float = contourLength / 2f,
    out: LightImpulseProductionDrawPlan = LightImpulseProductionDrawPlan()
): LightImpulseProductionDrawPlan {
    val safeProgress = progress.coerceIn(0f, 1f)
    val safeLength = contourLength.coerceAtLeast(0f)
    fun plan(
        mode: LightImpulseDrawMode,
        sourceBloomVisible: Boolean,
        forwardLength: Float = 0f,
        reverseLength: Float = 0f,
        sourceBloomRadius: Float = 0f,
        sourceBloomOwnsFootprint: Boolean = false
    ): LightImpulseProductionDrawPlan {
        val forward = forwardLength.coerceIn(0f, forwardMaxDistance.coerceAtLeast(0f))
        val reverse = reverseLength.coerceIn(0f, reverseMaxDistance.coerceAtLeast(0f))
        out.mode = mode
        out.sourceBloomVisible = sourceBloomVisible
        out.branchCount = if (mode == LightImpulseDrawMode.SHARED_BIRTH_FIELD ||
                mode == LightImpulseDrawMode.OWNED_TRAVEL_FIELDS ||
                mode == LightImpulseDrawMode.CONVERGING_FIELDS
            ) 2 else 0
        out.aggregateTravellingCoverageFraction = if (safeLength > 0f) {
                (forward + reverse) / safeLength
            } else {
                0f
            }
        out.aggregatePhysicalCoverageFraction = if (safeLength > 0f) {
            if (sourceBloomOwnsFootprint) {
                2f * sourceBloomRadius.coerceAtLeast(0f) / safeLength
            } else {
                (forward + reverse) / safeLength
            }
        } else {
            0f
        }
        out.forwardFieldLength = forward
        out.reverseFieldLength = reverse
        out.sourceBloomRadius = sourceBloomRadius.coerceAtLeast(0f)
        return out
    }
    return when (lightImpulsePhase(safeProgress)) {
        LightImpulsePhase.IGNITION -> plan(
            LightImpulseDrawMode.TOP_BLOOM,
            sourceBloomVisible = true,
            sourceBloomRadius = safeLength * (0.012f + lightImpulseOriginGlow(safeProgress) * 0.026f),
            sourceBloomOwnsFootprint = true
        )
        LightImpulsePhase.TRAVEL -> {
            val travel = lightImpulseTravelProgress(safeProgress)
            val forwardFront = forwardMaxDistance.coerceAtLeast(0f) * travel
            val reverseFront = reverseMaxDistance.coerceAtLeast(0f) * travel
            if (lightImpulseUsesSharedBirthField(safeProgress)) {
                // A single localized source owns every sub-pixel frame.  Connected halves only
                // begin once both have a drawable sample, never underneath that bloom.
                val bloomRadius = safeLength * (0.012f + lightImpulseOriginGlow(safeProgress) * 0.026f)
                val sampleThreshold = maxOf(MIN_RENDERABLE_SEGMENT_PX * LIGHT_BLOOM_SAMPLES, bloomRadius)
                if (forwardFront < sampleThreshold || reverseFront < sampleThreshold) {
                    plan(
                        LightImpulseDrawMode.TOP_BLOOM,
                        sourceBloomVisible = true,
                        sourceBloomRadius = bloomRadius,
                        sourceBloomOwnsFootprint = true
                    )
                } else {
                    plan(
                        LightImpulseDrawMode.SHARED_BIRTH_FIELD,
                        sourceBloomVisible = false,
                        forwardLength = forwardFront,
                        reverseLength = reverseFront,
                        sourceBloomRadius = bloomRadius
                    )
                }
            } else {
                plan(
                    LightImpulseDrawMode.OWNED_TRAVEL_FIELDS,
                    sourceBloomVisible = lightImpulseOriginGlow(safeProgress) > 0f,
                    forwardLength = min(safeLength * LIGHT_TAIL_FRACTION, forwardFront),
                    reverseLength = min(safeLength * LIGHT_TAIL_FRACTION, reverseFront)
                )
            }
        }
        LightImpulsePhase.CONVERGE -> {
            val flowLength = safeLength * lightImpulseConvergenceFieldRadiusFraction(safeProgress)
            val bloomVisible = lightImpulseBottomBloom(safeProgress) > 0f
            plan(
                LightImpulseDrawMode.CONVERGING_FIELDS,
                sourceBloomVisible = bloomVisible,
                forwardLength = min(flowLength, forwardMaxDistance.coerceAtLeast(0f)),
                reverseLength = min(flowLength, reverseMaxDistance.coerceAtLeast(0f)),
                sourceBloomRadius = if (bloomVisible) {
                    safeLength * lightImpulseBottomBloomRadiusFraction(safeProgress)
                } else 0f
            )
        }
        LightImpulsePhase.FADE -> plan(
            LightImpulseDrawMode.BOTTOM_BLOOM,
            sourceBloomVisible = true,
            sourceBloomRadius = safeLength * lightImpulseTerminalBloomRadiusFraction(safeProgress),
            sourceBloomOwnsFootprint = true
        )
    }
}

private const val MIN_RENDERABLE_SEGMENT_PX = 0.5f

/**
 * Shared path extraction contract. Native PathMeasure has no useful JVM rendering witness, so
 * range validation happens before its modulo/wrap operation. A near-full request is rejected:
 * no caller in this renderer needs it and it is the unsafe failure shape for a stale tail.
 */
internal data class WrappedSegmentRange(val start: Float, val length: Float)

internal fun renderableWrappedSegmentRange(
    start: Float,
    contourLength: Float,
    segmentLength: Float
): WrappedSegmentRange? {
    return EdgeSegmentRenderer.range(
        start = start,
        contourLength = contourLength,
        segmentLength = segmentLength,
        allowFullContour = false
    )
}

/** Light Impulse owns a compact field only; invalid input can never request a broad contour. */
internal fun lightImpulseTravellingSegmentIsSafe(segmentLength: Float, contourLength: Float): Boolean =
    renderableWrappedSegmentRange(0f, contourLength, segmentLength) != null &&
        segmentLength <= contourLength * LIGHT_TAIL_FRACTION

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

/**
 * Keeps the tail optically attached to its moving core: its useful coverage still reaches the
 * planned field boundary, but energy is released much earlier instead of leaving a bright ghost.
 */
internal fun lightImpulseTailSampleStrength(normalizedDistanceBehindCore: Float): Float {
    val position = normalizedDistanceBehindCore.coerceIn(0f, 1f)
    val retainedEnergy = 1f - position
    return (1.10f * retainedEnergy * retainedEnergy * retainedEnergy *
            lightImpulseSmoothStep(position / 0.24f)).coerceIn(0f, 1f)
}

/**
 * Shared optical profile for every travelling front. The coordinate is measured backward from
 * the physical front: it begins and ends at zero, with a rounded peak just behind the tip.
 * Drawing adjacent samples with this profile makes a pointed, softly blurred front without
 * extending a cap over another branch's owned contour.
 */
internal fun lightImpulseTaperedFrontStrength(normalizedDistanceBehindFront: Float): Float {
    val position = normalizedDistanceBehindFront.coerceIn(0f, 1f)
    val birth = lightImpulseSmoothStep(position / 0.22f)
    val release = 1f - lightImpulseSmoothStep((position - 0.20f) / 0.80f)
    return (birth * release).coerceIn(0f, 1f)
}

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
    LIGHT_CORE_FRACTION * LIGHT_CORE_SPAN_MULTIPLIER,
    (totalFlowFraction - sourceExclusionFraction).coerceAtLeast(0f) * 0.60f
)

/** Actual tail geometry is shorter than the reserved field, keeping it attached to the core. */
internal fun lightImpulseAttachedTailSpanFraction(
    totalFlowFraction: Float,
    sourceExclusionFraction: Float
): Float {
    val available = (totalFlowFraction - sourceExclusionFraction).coerceAtLeast(0f)
    val core = lightImpulseCoreSpanFraction(totalFlowFraction, sourceExclusionFraction)
    return core + (available - core).coerceAtLeast(0f) * LIGHT_ATTACHED_TAIL_BODY_SHARE
}

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
    val attachedTailEnd = bloomRadius + lightImpulseAttachedTailSpanFraction(
        totalFlowLength,
        bloomRadius
    )
    if (distance < attachedTailEnd) {
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
    if (!origin.isFinite() || !frontDistance.isFinite() || !distanceBehindFront.isFinite() ||
        !requestedLength.isFinite() || !maxDistance.isFinite() || !minSourceDistance.isFinite()
    ) return null
    if (frontDistance <= 0f || requestedLength < MIN_RENDERABLE_SEGMENT_PX ||
        maxDistance <= MIN_RENDERABLE_SEGMENT_PX
    ) return null
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
/** Rendering implementation separated from HaloRenderer's public lifecycle facade. */
internal class EdgeRenderPipeline(
    config: HaloConfig,
    private val outline: DisplayOutline
) {
    internal val outlinePath: Path
        get() = outline.path

    internal fun lightImpulseGeometry(): StyleGeometry? {
        if (cachedOutlineVersion != outline.version) {
            clearStylePathCache()
            cachedOutlineVersion = outline.version
        }
        return styleGeometryFor(stylePathForCurrentConfig()).takeIf { it.length > 0f }
    }

    internal fun lightImpulseAlpha(): Int = (255f * config.intensity).roundToInt().coerceIn(0, 255)
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

    private var cachedStyleGeometry:
            StyleGeometry? = null

    /** Conventional and impulse use the shared 0px-envelope physical surface. */
    private val conventionalPathCache = EdgePathCache(outline)
    private val calibrationRenderer = CalibrationEdgeRenderer(outline)

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

    internal val corePaint =
        createPaint().apply {
            strokeWidth =
                renderStrokeWidth
        }

    private val conventionalRenderer = ConventionalEdgeRenderer(object : ConventionalRenderSurface {
        override val outlinePath: Path
            get() = this@EdgeRenderPipeline.outlinePath
        override val corePaint: Paint
            get() = this@EdgeRenderPipeline.corePaint
        override fun preparePaint(baseAlpha: Int, effectPhase: Float, gradientPhase: Float) {
            prepareConventionalPaint(baseAlpha, effectPhase, gradientPhase)
        }

        override fun stylePath(): Path = stylePathForCurrentConfig()

        override fun styleGeometry(path: Path): StyleGeometry = styleGeometryFor(path)
    })

    private val lightImpulseRenderer = LightImpulseRenderer(object : LightImpulseRenderSurface {
        override val outlinePath: Path
            get() = this@EdgeRenderPipeline.outlinePath
        override val corePaint: Paint
            get() = this@EdgeRenderPipeline.corePaint
        override val renderStrokeWidth: Float
            get() = this@EdgeRenderPipeline.renderStrokeWidth
        override val colorRgb: Int
            get() = this@EdgeRenderPipeline.colorRgb

        override fun geometry(): StyleGeometry? = lightImpulseGeometry()

        override fun alpha(): Int = lightImpulseAlpha()

        override fun preparePaint(alpha: Int, effectPhase: Float, gradientPhase: Float) {
            prepareLightImpulsePaint(alpha, effectPhase, gradientPhase)
        }
    })

    /** Separate multi-head surface; conventional motions keep their established renderer path. */
    private val forceBlades =
        ForceBladesRenderer(outline)

    private var activeDefinition: HaloMotionDefinition = this.config.motion.definition
    private var delegateCanvas: Canvas? = null
    private val renderFrame = HaloRenderFrame()
    private val renderSurface = object : HaloRenderSurface {
        override fun drawFullContour(frame: HaloRenderFrame) {
            val canvas = delegateCanvas ?: return
            conventionalRenderer.drawPulse(canvas, frame.alpha, frame.phase, frame.gradientPhase)
        }

        override fun drawSpecializedField(frame: HaloRenderFrame) {
            val canvas = delegateCanvas ?: return
            lightImpulseRenderer.draw(canvas, 1f, frame.phase, frame.gradientPhase)
        }

        override fun drawLuminousSegment(frame: HaloRenderFrame, startFraction: Float, lengthFraction: Float) {
            val canvas = delegateCanvas ?: return
            conventionalRenderer.drawLuminousSegment(canvas, frame.alpha, frame.phase, frame.gradientPhase, startFraction, lengthFraction)
        }

        override fun drawBlade(frame: HaloRenderFrame, variant: HaloBladeVariant) =
            this@EdgeRenderPipeline.drawBlade(frame, variant)
    }

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
        activeDefinition = next.motion.definition

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
        if (usesLightImpulseRenderer(config)) {
            lightImpulseRenderer.draw(canvas, animationProgress, effectPhase, gradientPhase)
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

        if (
            cachedOutlineVersion !=
            outline.version
        ) {
            clearStylePathCache()

            cachedOutlineVersion =
                outline.version
        }

        delegateCanvas = canvas
        try {
            renderFrame.phase = effectPhase
            renderFrame.alpha = baseAlpha
            renderFrame.gradientPhase = gradientPhase
            activeDefinition.renderDelegate.draw(renderSurface, renderFrame)
        } finally {
            delegateCanvas = null
        }
    }

    private fun drawBlade(frame: HaloRenderFrame, variant: HaloBladeVariant) {
        val canvas = delegateCanvas ?: return
        when (variant) {
            HaloBladeVariant.AZURE -> forceBlades.drawAzure(canvas, frame.phase, frame.alpha, renderStrokeWidth, outline.dpToPx(config.edgeCalibrationDp), outline.dpToPx(config.cornerCalibrationDp), config.cornerShape)
            HaloBladeVariant.CRIMSON -> forceBlades.drawCrimson(canvas, frame.phase, frame.alpha, renderStrokeWidth, outline.dpToPx(config.edgeCalibrationDp), outline.dpToPx(config.cornerCalibrationDp), config.cornerShape)
            HaloBladeVariant.CLASH -> forceBlades.drawClash(canvas, frame.phase, frame.alpha, renderStrokeWidth, outline.dpToPx(config.edgeCalibrationDp), outline.dpToPx(config.cornerCalibrationDp), config.cornerShape)
        }
    }

    /** Keeps normal Impulse on the app's existing solid/palette/gradient paint path. */
    internal fun prepareLightImpulsePaint(alpha: Int, effectPhase: Float, gradientPhase: Float) {
        if (impulseUsesNormalColorTreatment(config)) {
            prepareConventionalPaint(alpha, effectPhase, gradientPhase)
        } else {
            corePaint.shader = null
            corePaint.color = colorWithAlpha(alpha, colorRgb)
        }
    }

    /** Complete calibrated frame used by the static calibration preview. */
    fun drawStaticFrame(canvas: Canvas) {
        if (config.intensity <= 0f) return
        if (cachedOutlineVersion != outline.version) {
            clearStylePathCache()
            cachedOutlineVersion = outline.version
        }
        prepareConventionalPaint((255f * config.intensity).roundToInt().coerceIn(0, 255), 0f, 0f)
        calibrationRenderer.drawStaticFrame(canvas, staticPreviewPathForCurrentConfig(), corePaint)
    }

    /** Calibration-only witnesses; never called by the animated renderer. */
    fun drawCalibrationDiagnostics(canvas: Canvas, rulerLegend: String, registrationLabel: String) {
        val edgeCalibrationPx = outline.dpToPx(config.edgeCalibrationDp)
        calibrationRenderer.drawDiagnostics(canvas, staticPreviewPathForCurrentConfig(), edgeCalibrationPx, rulerLegend, registrationLabel)
    }

    fun calibrationEdgePx(): Float = outline.dpToPx(config.edgeCalibrationDp)

    /** Shared paint primitive consumed by conventional and dedicated-impulse renderers. */
    internal fun prepareConventionalPaint(
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

    internal fun stylePathForCurrentConfig(): Path {
        val edgeCalibrationPx = outline.opticalInsetPx + outline.dpToPx(config.edgeCalibrationDp)
        return conventionalPathCache.pathFor(
            strokeWidth = renderStrokeWidth,
            edgeCalibrationPx = edgeCalibrationPx,
            cornerCalibrationPx = outline.dpToPx(config.cornerCalibrationDp),
            cornerShape = config.cornerShape,
            extraEnvelopePx = 0f
        )
    }

    /** Static calibration intentionally reuses the normal runtime contour. */
    internal fun staticPreviewPathForCurrentConfig(): Path =
        stylePathForCurrentConfig()

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

    private companion object {
        private const val BASE_STROKE_WIDTH =
            4f

        private const val STROKE_WIDTH_RANGE =
            10f

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

/**
 * Public renderer lifecycle. Rendering families and their mutable paint/path state live in
 * [EdgeRenderPipeline], leaving this type as the explicit config/lifecycle entry point used by
 * HaloView and preview callers.
 */
internal class HaloRenderer(config: HaloConfig, outline: DisplayOutline) {
    private val pipeline = EdgeRenderPipeline(config, outline)

    fun update(config: HaloConfig) = pipeline.update(config)

    fun draw(canvas: Canvas, animationProgress: Float, effectPhase: Float, gradientPhase: Float) =
        pipeline.draw(canvas, animationProgress, effectPhase, gradientPhase)

    fun drawStaticFrame(canvas: Canvas) = pipeline.drawStaticFrame(canvas)

    fun drawCalibrationDiagnostics(canvas: Canvas, rulerLegend: String, registrationLabel: String) =
        pipeline.drawCalibrationDiagnostics(canvas, rulerLegend, registrationLabel)

    fun calibrationEdgePx(): Float = pipeline.calibrationEdgePx()

    internal fun stylePathForCurrentConfig(): Path = pipeline.stylePathForCurrentConfig()

    internal fun staticPreviewPathForCurrentConfig(): Path = pipeline.staticPreviewPathForCurrentConfig()
}
