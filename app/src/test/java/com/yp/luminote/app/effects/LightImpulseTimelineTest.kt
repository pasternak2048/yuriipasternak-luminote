package com.yp.luminote.app.effects

import android.graphics.Paint
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.data.settings.HaloMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LightImpulseTimelineTest {

    @Test
    fun `timeline keeps one ignition before split travel and terminal convergence`() {
        assertEquals(LightImpulsePhase.IGNITION, lightImpulsePhase(0f))
        assertEquals(LightImpulsePhase.IGNITION, lightImpulsePhase(0.089f))
        assertEquals(LightImpulsePhase.TRAVEL, lightImpulsePhase(0.09f))
        assertEquals(LightImpulsePhase.TRAVEL, lightImpulsePhase(0.799f))
        assertEquals(LightImpulsePhase.CONVERGE, lightImpulsePhase(0.80f))
        assertEquals(LightImpulsePhase.FADE, lightImpulsePhase(0.92f))
    }

    @Test
    fun `travel starts with the fully charged single source still visible`() {
        assertEquals(LightImpulsePhase.TRAVEL, lightImpulsePhase(0.09f))
        assertEquals(0f, lightImpulseTravelProgress(0.09f), 0.0001f)
        assertEquals(1f, lightImpulseOriginGlow(0.09f), 0.0001f)
        assertTrue(lightImpulseTravelProgress(0.20f) > 0f)
        assertTrue(lightImpulseTravelProgress(0.20f) < lightImpulseTravelProgress(0.40f))
    }

    @Test
    fun `production plan retains one top source through subpixel branch birth`() {
        val atBoundary = lightImpulseProductionDrawPlan(0.09f)
        assertTrue(atBoundary.sourceBloomVisible)
        assertEquals(0, atBoundary.branchCount)
        assertEquals(0f, atBoundary.aggregateTravellingCoverageFraction, 0f)

        // This is deliberately below a practical PathMeasure segment threshold on a phone.  The
        // local source is still rendered, so suppression of a tiny branch cannot create a blank.
        val subPixelBirth = lightImpulseProductionDrawPlan(0.091f)
        assertTrue(subPixelBirth.sourceBloomVisible)
        assertEquals(LightImpulseDrawMode.TOP_BLOOM, subPixelBirth.mode)
        assertEquals(0, subPixelBirth.branchCount)
        assertEquals(0f, subPixelBirth.aggregateTravellingCoverageFraction, 0f)

        val firstBranches = lightImpulseProductionDrawPlan(0.25f)
        assertEquals(LightImpulseDrawMode.SHARED_BIRTH_FIELD, firstBranches.mode)
        assertEquals(2, firstBranches.branchCount)
        assertTrue(firstBranches.aggregateTravellingCoverageFraction > 0f)
    }

    @Test
    fun `source energy transfers continuously into newborn branches`() {
        assertEquals(0f, lightImpulseBranchEnergy(0.09f), 0.0001f)
        assertEquals(1f, lightImpulseOriginGlow(0.09f), 0.0001f)

        val earlyTravel = 0.12f
        assertTrue(lightImpulseBranchEnergy(earlyTravel) > 0f)
        assertTrue(lightImpulseBranchEnergy(earlyTravel) < 1f)
        assertEquals(
            1f,
            lightImpulseOriginGlow(earlyTravel) + lightImpulseBranchEnergy(earlyTravel),
            0.0001f
        )
        assertEquals(1f, lightImpulseBranchEnergy(0.80f), 0.0001f)
    }

    @Test
    fun `tail energy is continuously redistributed from two branches into one bottom source`() {
        assertEquals(1f, lightImpulseConvergenceEnergy(0.80f), 0.0001f)
        assertTrue(lightImpulseConvergenceEnergy(0.84f) in 0f..1f)
        assertTrue(lightImpulseConvergenceEnergy(0.84f) < 1f)
        assertEquals(
            1f,
            lightImpulseConvergenceEnergy(0.84f) + lightImpulseBottomBloom(0.84f),
            0.0001f
        )
        assertEquals(0f, lightImpulseConvergenceEnergy(0.92f), 0.0001f)
    }

    @Test
    fun `separate owned paths retain saturated app color without overlapping their geometry`() {
        listOf(0.09f, 0.80f, 0.84f, 0.92f).forEach { progress ->
            val passes = lightImpulsePassEnergy(progress)
            assertTrue("negative pass allocation at $progress", passes.originBloom >= 0f)
            assertTrue("negative pass allocation at $progress", passes.tailPerBranch >= 0f)
            assertTrue("negative pass allocation at $progress", passes.corePerBranch >= 0f)
            assertTrue("negative pass allocation at $progress", passes.bottomBloom >= 0f)
        }

        val split = lightImpulsePassEnergy(0.12f)
        assertEquals(lightImpulseBranchEnergy(0.12f), split.corePerBranch, 0.0001f)
        assertTrue(split.tailPerBranch > 0f)
        val reunion = lightImpulsePassEnergy(0.84f)
        assertTrue(reunion.total() > 1f)
        val afterglow = lightImpulsePassEnergy(0.92f)
        assertEquals(1f, afterglow.bottomBloom, 0.0001f)
        assertEquals(0f, afterglow.tailPerBranch, 0.0001f)
        assertEquals(0f, afterglow.corePerBranch, 0.0001f)
    }

    @Test
    fun `early travel is one symmetric shared source field before separate beams exist`() {
        assertTrue(lightImpulseUsesSharedBirthField(0.091f))
        assertTrue(lightImpulseUsesSharedBirthField(0.12f))
        assertTrue(!lightImpulseUsesSharedBirthField(0.30f))
        assertTrue(!lightImpulseUsesSharedBirthField(0.80f))
        assertEquals(
            1f,
            lightImpulseOriginGlow(0.12f) + lightImpulseBranchEnergy(0.12f),
            0.0001f
        )
    }

    @Test
    fun `shared birth front hands off to the same tapered owned core profile`() {
        // travelProgress crosses the shared-field cutoff between these two timeline samples.
        val before = 0.276f
        val after = 0.278f
        assertTrue(lightImpulseUsesSharedBirthField(before))
        assertTrue(!lightImpulseUsesSharedBirthField(after))

        val beforePlan = lightImpulseProductionDrawPlan(before)
        assertEquals(LightImpulseDrawMode.SHARED_BIRTH_FIELD, beforePlan.mode)
        val sharedFront = lightImpulseProductionBirthFieldStrength(
            before, beforePlan, beforePlan.forwardFieldLength
        )
        // The physical tip is intentionally unlit. Both the shared field and the later owned
        // core place their peak a short distance behind it, avoiding a blunt bright cap.
        assertEquals(0f, sharedFront.alphaFraction, 0f)
        assertEquals(0.70f, sharedFront.widthFactor, 0f)
        assertTrue(lightImpulseTaperedFrontStrength(0.24f) >
            lightImpulseTaperedFrontStrength(0.02f))
    }

    @Test
    fun `convergence keeps its strongest core at the bottom source across the boundary`() {
        listOf(0.80f, 0.8001f).forEach { progress ->
            val source = lightImpulseConnectedFieldStrength(
                position = 0f,
                sourceStrength = lightImpulseConvergenceSourceStrength(progress),
                edgeStrength = lightImpulseConvergenceEnergy(progress),
                sourceAnchored = true
            )
            val outerTail = lightImpulseConnectedFieldStrength(
                position = 1f,
                sourceStrength = lightImpulseConvergenceSourceStrength(progress),
                edgeStrength = lightImpulseConvergenceEnergy(progress),
                sourceAnchored = true
            )
            assertTrue(source.alphaFraction > outerTail.alphaFraction)
            assertEquals(1f, source.alphaFraction, 0.0001f)
        }
    }

    @Test
    fun `arrival and convergence share the same endpoint peak at point eight`() {
        // The travel core starts its brightest owned sample at the arriving endpoint; the
        // convergence field starts its brightest source sample at the same physical endpoint.
        assertEquals(0f, lightImpulseCorePeakOffsetFraction(), 0f)
        val atConvergence = lightImpulseConnectedFieldStrength(
            position = lightImpulseCorePeakOffsetFraction(),
            sourceStrength = lightImpulseConvergenceSourceStrength(0.80f),
            edgeStrength = lightImpulseConvergenceEnergy(0.80f),
            sourceAnchored = true
        )
        assertEquals(1f, atConvergence.alphaFraction, 0.0001f)
        assertEquals(1.90f, atConvergence.widthFactor, 0.0001f)
    }

    @Test
    fun `terminal bloom preserves the converging source peak across point nine two`() {
        val justBefore = lightImpulseConnectedFieldStrength(
            position = 0f,
            sourceStrength = lightImpulseConvergenceSourceStrength(0.9199f),
            edgeStrength = lightImpulseConvergenceEnergy(0.9199f),
            sourceAnchored = true
        )
        val justAfter = lightImpulseTerminalBloomStrength(0f, lightImpulseFadeEnergy(0.9201f))
        assertEquals(justBefore.alphaFraction, justAfter.alphaFraction, 0.001f)
        assertEquals(justBefore.widthFactor, justAfter.widthFactor, 0.0001f)
    }

    @Test
    fun `convergence outer raster endpoint meets the first fade bloom footprint at point nine two`() {
        // The connected field's final sample ends at this radius on each owned side. The fade
        // bloom samples the same two-sided radius, so there is no broad contour pop at handoff.
        val justBefore = lightImpulseConvergenceFieldRadiusFraction(0.9199f)
        val justAfter = lightImpulseTerminalBloomRadiusFraction(0.9201f)
        assertEquals(justBefore, justAfter, 0.00001f)
        assertEquals(0.040f, lightImpulseConvergenceFieldRadiusFraction(0.92f), 0.0001f)
        assertEquals(0.040f, lightImpulseTerminalBloomRadiusFraction(0.92f), 0.0001f)
    }

    @Test
    fun `pure convergence coverage retains the renderer final source reservation`() {
        // Just before fade, the production field still owns the same finite source span.
        val coverage = lightImpulseConvergenceCoverage(0.9199f, 0.0399f)
        assertTrue(coverage.bloom > 0f)
        assertEquals(0f, coverage.core, 0f)
        assertEquals(0f, coverage.tail, 0f)
    }

    @Test
    fun `convergence spatially reserves bottom bloom core and tail intervals`() {
        listOf(0.80f, 0.84f).forEach { progress ->
            // Sample beyond the tail extent as well as through bloom, core and tail coverage.
            (0..80).forEach { sample ->
                val coverage = lightImpulseConvergenceCoverage(progress, sample / 400f)
                assertTrue(
                    "stacked local passes at progress $progress sample $sample",
                    coverage.activePassCount() <= 1
                )
                assertTrue(
                    "over-budget local energy at progress $progress sample $sample",
                    coverage.total() <= 1.0001f
                )
            }
        }

        // At reunion, bloom owns the source centre while the two owned fields begin outside it.
        val source = lightImpulseConvergenceCoverage(0.84f, 0f)
        assertTrue(source.bloom > 0f)
        assertEquals(0f, source.core, 0f)
        assertEquals(0f, source.tail, 0f)
    }

    @Test
    fun `raster footprint contains bloom and core tail owners meet without crossing`() {
        assertEquals(Paint.Cap.BUTT, lightImpulseBloomCap())

        val bloomRadius = lightImpulseBottomBloomRadiusFraction(0.84f)
        assertTrue(lightImpulseConvergenceCoverage(0.84f, bloomRadius - 0.00001f).bloom > 0f)
        val afterBloom = lightImpulseConvergenceCoverage(0.84f, bloomRadius)
        assertEquals(0f, afterBloom.bloom, 0f)
        assertTrue(afterBloom.core > 0f)

        val travelFlowFraction = lightImpulseConvergenceFieldRadiusFraction(0.80f)
        val coreBoundary = lightImpulseCoreSpanFraction(travelFlowFraction, 0f)
        assertTrue(lightImpulseConvergenceCoverage(0.80f, coreBoundary - 0.00001f).core > 0f)
        val tailAtBoundary = lightImpulseConvergenceCoverage(0.80f, coreBoundary)
        assertEquals(0f, tailAtBoundary.core, 0f)
        assertTrue(tailAtBoundary.tail > 0f)
    }

    @Test
    fun `beam samples are born at the source and never claim opposite path length`() {
        // At the split boundary there is no travelling segment at all: only the shared bloom.
        assertEquals(null, lightImpulseOwnedSegment(100f, 1, 0f, 0f, 8f, 500f))

        // A newborn tail that would otherwise extend through the source is clipped to zero.
        val newborn = lightImpulseOwnedSegment(
            origin = 100f,
            direction = 1,
            frontDistance = 12f,
            distanceBehindFront = 10f,
            requestedLength = 8f,
            maxDistance = 500f
        )!!
        assertEquals(0f, newborn.startDistance, 0f)
        assertEquals(2f, newborn.endDistance, 0f)
        assertEquals(101f, newborn.pathCenter, 0f)

        // The same source-relative bounds apply independently to both directional halves.
        val travelling = lightImpulseOwnedSegment(
            origin = 100f,
            direction = -1,
            frontDistance = 320f,
            distanceBehindFront = 300f,
            requestedLength = 60f,
            maxDistance = 500f
        )!!
        assertTrue(travelling.startDistance >= 0f)
        assertTrue(travelling.endDistance <= 500f)
        assertTrue(travelling.endDistance > travelling.startDistance)
        assertEquals(90f, travelling.pathCenter, 0f)
    }

    @Test
    fun `bottom convergence branches remain on their own sides while shortening`() {
        val nearBottom = lightImpulseOwnedSegment(
            origin = 8_000f,
            direction = -1,
            frontDistance = 80f,
            distanceBehindFront = 72f,
            requestedLength = 16f,
            maxDistance = 4_000f
        )!!
        assertEquals(0f, nearBottom.startDistance, 0f)
        assertEquals(8f, nearBottom.endDistance, 0f)
    }

    @Test
    fun `bottom convergence reverses each asymmetric top origin branch`() {
        val ownership = lightImpulseConvergenceOwnership(
            LightImpulseEndpoints(
                topFraction = 0.1f,
                bottomFraction = 0.7f,
                forwardDistance = 6_000f,
                reverseDistance = 4_000f
            )
        )

        // At bottom, the positive top-origin branch must move backward and vice versa.
        assertEquals(-1, ownership.forwardDirection)
        assertEquals(6_000f, ownership.forwardMaxDistance, 0f)
        assertEquals(1, ownership.reverseDirection)
        assertEquals(4_000f, ownership.reverseMaxDistance, 0f)
    }

    @Test
    fun `owned beam raster endpoints do not project beyond their source bounds`() {
        assertEquals(Paint.Cap.BUTT, lightImpulseOwnedBeamCap())
    }

    @Test
    fun `convergence core remains at the travel arrival position`() {
        // Just before convergence, this core sample is 20–30 px behind the bottom arrival.
        val travel = lightImpulseOwnedSegment(
            origin = 0f,
            direction = 1,
            frontDistance = 1_000f,
            distanceBehindFront = 20f,
            requestedLength = 10f,
            maxDistance = 1_000f
        )!!
        // At convergence, the same interval is measured outward from the bottom source.
        val converge = lightImpulseOwnedSegment(
            origin = 1_000f,
            direction = -1,
            frontDistance = 30f,
            distanceBehindFront = 0f,
            requestedLength = 10f,
            maxDistance = 1_000f
        )!!

        assertEquals(travel.pathCenter, converge.pathCenter, 0f)
        assertEquals(975f, converge.pathCenter, 0f)
    }

    @Test
    fun `calibrated display outline supplies the renderer source path`() {
        val outline = DisplayOutline(density = 2f).apply { resize(1_080, 2_400) }
        val path = outline.centerlinePath(
            strokeWidth = 8f,
            edgeCalibrationPx = outline.opticalInsetPx + outline.dpToPx(3f),
            cornerCalibrationPx = outline.dpToPx(4f)
        )
        assertTrue(!path.isEmpty)
    }

    @Test
    fun `endpoint distance semantics keep distinct fractions on symmetric routes`() {
        // Android's native PathMeasure is unavailable to this JVM suite. This covers only the
        // pure distance contract; on-device/instrumented validation covers sampled geometry.
        val endpoints = lightImpulseEndpoints(
            topFraction = 0.25f,
            bottomFraction = 0.75f,
            length = 10_000f
        )

        assertTrue(endpoints.topFraction != endpoints.bottomFraction)
        assertEquals(endpoints.forwardDistance, endpoints.reverseDistance, 4f)
        assertEquals(0.75f, endpoints.bottomFraction, 0f)
    }

    @Test
    fun `bottom bloom begins only as owned flows release their energy`() {
        assertEquals(0f, lightImpulseBottomBloom(0.80f), 0.0001f)
        assertEquals(0.012f, lightImpulseBottomBloomRadiusFraction(0.80f), 0.0001f)
        assertEquals(0f, lightImpulseBottomBloom(0.799f), 0.0001f)
        assertTrue(lightImpulseBottomBloom(0.84f) > 0f)
    }

    @Test
    fun `terminal afterglow is continuous and contracts before the final frame`() {
        assertEquals(1f, lightImpulseFadeEnergy(0.92f), 0.0001f)
        assertTrue(lightImpulseFadeEnergy(0.96f) in 0f..1f)
        assertTrue(lightImpulseFadeEnergy(0.96f) < 1f)
        assertTrue(lightImpulseFadeEnergy(0.99f) > 0f)
        assertEquals(0f, lightImpulseFadeEnergy(1f), 0.0001f)
    }

    @Test
    fun `final travelling coverage only contracts and is gone before the local fade impulse`() {
        val samples = listOf(0.80f, 0.82f, 0.84f, 0.88f, 0.91f, 0.919f, 0.92f, 0.96f, 1f)
        val coverage = samples.map(::lightImpulseTravellingCoverageFraction)

        coverage.zipWithNext().forEachIndexed { index, (current, next) ->
            assertTrue("travelling coverage resurrected after ${samples[index]}", next <= current)
        }
        assertTrue(coverage.first() > 0f)
        assertEquals(0f, lightImpulseTravellingCoverageFraction(0.92f), 0f)
        assertEquals(0f, lightImpulseTravellingCoverageFraction(Float.NaN), 0f)
    }

    @Test
    fun `production convergence plan has two owned branches and never grows coverage`() {
        val arrival = lightImpulseProductionDrawPlan(0.80f)
        val midConvergence = lightImpulseProductionDrawPlan(0.84f)
        val fade = lightImpulseProductionDrawPlan(0.92f)

        // The explicit aggregate budget is split equally between the two owned branches.
        assertEquals(2, arrival.branchCount)
        assertEquals(0.33f, arrival.aggregateTravellingCoverageFraction, 0.0001f)
        assertEquals(2, midConvergence.branchCount)
        assertTrue(midConvergence.sourceBloomVisible)
        assertTrue(midConvergence.aggregateTravellingCoverageFraction <
            arrival.aggregateTravellingCoverageFraction)
        assertEquals(0, fade.branchCount)
        assertEquals(0f, fade.aggregateTravellingCoverageFraction, 0f)

        listOf(0.80f, 0.84f, 0.88f, 0.919f, 0.92f)
            .map(::lightImpulseProductionDrawPlan)
            .zipWithNext()
            .forEach { (current, next) ->
                assertTrue(next.aggregateTravellingCoverageFraction <=
                    current.aggregateTravellingCoverageFraction)
            }
    }

    @Test
    fun `production plan supplies the exact bounded field lengths used by renderer`() {
        val contour = 10_000f
        val arrival = lightImpulseProductionDrawPlan(
            progress = 0.80f,
            contourLength = contour,
            forwardMaxDistance = 5_000f,
            reverseMaxDistance = 5_000f
        )
        assertEquals(LightImpulseDrawMode.CONVERGING_FIELDS, arrival.mode)
        assertEquals(1_650f, arrival.forwardFieldLength, 0.001f)
        assertEquals(1_650f, arrival.reverseFieldLength, 0.001f)
        assertEquals(
            (arrival.forwardFieldLength + arrival.reverseFieldLength) / contour,
            arrival.aggregateTravellingCoverageFraction,
            0f
        )

        val initial = lightImpulseProductionDrawPlan(
            progress = 0.091f,
            contourLength = contour,
            forwardMaxDistance = 5_000f,
            reverseMaxDistance = 5_000f
        )
        assertEquals(LightImpulseDrawMode.TOP_BLOOM, initial.mode)
        assertEquals(0f, initial.forwardFieldLength, 0f)
        assertEquals(0f, initial.reverseFieldLength, 0f)
    }

    @Test
    fun `complete impulse field is fifty percent longer while each side stays bounded`() {
        val contour = 10_000f
        val plan = lightImpulseProductionDrawPlan(
            progress = 0.80f,
            contourLength = contour,
            forwardMaxDistance = 5_000f,
            reverseMaxDistance = 5_000f
        )

        assertEquals(0.33f, plan.aggregateTravellingCoverageFraction, 0.0001f)
        assertEquals(0.165f, plan.forwardFieldLength / contour, 0.0001f)
        assertEquals(0.165f, plan.reverseFieldLength / contour, 0.0001f)
        assertEquals(1.5f, plan.aggregateTravellingCoverageFraction / 0.22f, 0.0001f)
    }

    @Test
    fun `tail releases smoothly near its core without changing owned field coverage`() {
        val attached = lightImpulseTailSampleStrength(0.20f)
        val middle = lightImpulseTailSampleStrength(0.50f)
        val released = lightImpulseTailSampleStrength(0.80f)

        assertEquals(0f, lightImpulseTailSampleStrength(0f), 0f)
        assertTrue(attached > middle * 3f)
        assertTrue(middle > released * 10f)
        assertTrue(released > 0f)
        assertEquals(0.33f, lightImpulseProductionDrawPlan(0.80f)
            .aggregateTravellingCoverageFraction, 0.0001f)
    }

    @Test
    fun `every impulse front is tapered from its physical tip before it reaches the bottom glint`() {
        val tip = lightImpulseTaperedFrontStrength(0f)
        val nearTip = lightImpulseTaperedFrontStrength(0.08f)
        val peak = lightImpulseTaperedFrontStrength(0.24f)
        val release = lightImpulseTaperedFrontStrength(0.72f)

        // The same source-relative profile is used by travel and bottom convergence: no blunt
        // cap can be brighter than the compact peak behind it, and it fades back into the tail.
        assertEquals(0f, tip, 0f)
        assertTrue(nearTip > 0f && nearTip < peak)
        assertTrue(release > 0f && release < peak)
        assertEquals(0f, lightImpulseTaperedFrontStrength(1f), 0f)
    }

    @Test
    fun `attached tail uses a shorter physical span while the planned field remains 33 percent`() {
        val plannedPerBranch = 0.165f
        val attachedSpan = lightImpulseAttachedTailSpanFraction(plannedPerBranch, 0f)

        assertTrue("tail should stay materially closer to its core", attachedSpan < plannedPerBranch * 0.82f)
        assertTrue(lightImpulseConvergenceCoverage(0.80f, attachedSpan - 0.00001f).tail > 0f)
        assertEquals(0f, lightImpulseConvergenceCoverage(0.80f, attachedSpan).tail, 0f)
        assertEquals(0.33f, lightImpulseProductionDrawPlan(0.80f)
            .aggregateTravellingCoverageFraction, 0.0001f)
    }

    @Test
    fun `only dedicated reminder mode uses the light impulse override`() {
        assertTrue(!usesLightImpulseRenderer(HaloConfig(motion = HaloMotion.IMPULSE)))
        assertTrue(usesLightImpulseRenderer(HaloConfig(renderMode = HaloRenderMode.LIGHT_IMPULSE)))
        assertTrue(!usesLightImpulseRenderer(HaloConfig(motion = HaloMotion.SNAKE)))
    }

    @Test
    fun `normal impulse preserves app color treatment while dedicated impulse keeps source color`() {
        val normalGradient = HaloConfig(
            motion = HaloMotion.IMPULSE,
            colorMode = HaloColorMode.GRADIENT,
            palette = intArrayOf(0xFF0077FF.toInt(), 0xFFFF33AA.toInt())
        )
        val dedicatedImpulse = normalGradient.copy(renderMode = HaloRenderMode.LIGHT_IMPULSE)

        assertTrue(impulseUsesNormalColorTreatment(normalGradient))
        assertTrue(!impulseUsesNormalColorTreatment(dedicatedImpulse))
    }

    @Test
    fun `birth mode switch preserves one field footprint and source peak`() {
        val before = lightImpulseProductionDrawPlan(0.22f)
        val after = lightImpulseProductionDrawPlan(0.23f)
        assertEquals(LightImpulseDrawMode.TOP_BLOOM, before.mode)
        assertEquals(LightImpulseDrawMode.SHARED_BIRTH_FIELD, after.mode)

        // The source-only footprint is two radii; the two connected halves take over only after
        // each has grown to that radius, avoiding the old abrupt contraction.
        assertTrue(after.aggregatePhysicalCoverageFraction >=
            before.aggregatePhysicalCoverageFraction - 0.01f)
        assertTrue(after.aggregatePhysicalCoverageFraction <=
            before.aggregatePhysicalCoverageFraction + 0.03f)

        val sourceStrength = lightImpulseOriginGlow(0.23f)
        val bloomPeak = lightImpulseSharedSourceBloomStrength(0f, sourceStrength)
        val fieldPeak = lightImpulseConnectedFieldStrength(
            position = 0f,
            sourceStrength = sourceStrength,
            edgeStrength = lightImpulseBranchEnergy(0.23f),
            sourceAnchored = false
        )
        assertEquals(bloomPeak.alphaFraction, fieldPeak.alphaFraction, 0f)
        assertEquals(bloomPeak.widthFactor, fieldPeak.widthFactor, 0f)
    }

    @Test
    fun `birth handoff preserves the full source to front cross section`() {
        val beforeProgress = 0.22f
        val afterProgress = 0.23f
        val before = lightImpulseProductionDrawPlan(beforeProgress)
        val after = lightImpulseProductionDrawPlan(afterProgress)
        val sharedRadius = minOf(before.sourceBloomRadius, after.sourceBloomRadius)

        listOf(0f, 0.5f, 0.9f).forEach { normalized ->
            val distance = sharedRadius * normalized
            val outgoing = lightImpulseProductionBirthFieldStrength(beforeProgress, before, distance)
            val incoming = lightImpulseProductionBirthFieldStrength(afterProgress, after, distance)
            assertTrue("source alpha popped at $normalized", kotlin.math.abs(outgoing.alphaFraction - incoming.alphaFraction) < 0.12f)
            assertTrue("source width popped at $normalized", kotlin.math.abs(outgoing.widthFactor - incoming.widthFactor) < 0.15f)
        }

        // Near the new field's front the born head is still below the source peak rather than a
        // full-strength strip suddenly replacing the bloom edge.
        val nearFront = lightImpulseProductionBirthFieldStrength(
            afterProgress, after, after.forwardFieldLength * 0.99f
        )
        val center = lightImpulseProductionBirthFieldStrength(afterProgress, after, 0f)
        assertTrue(nearFront.alphaFraction <= center.alphaFraction)
        assertTrue(after.aggregatePhysicalCoverageFraction >= before.aggregatePhysicalCoverageFraction - 0.01f)
    }

    @Test
    fun `wrapped segment extraction rejects tiny non finite and near full requests`() {
        assertEquals(null, renderableWrappedSegmentRange(0f, 1_000f, 0f))
        assertEquals(null, renderableWrappedSegmentRange(0f, 1_000f, 0.49f))
        assertEquals(null, renderableWrappedSegmentRange(Float.NaN, 1_000f, 10f))
        assertEquals(null, renderableWrappedSegmentRange(0f, Float.POSITIVE_INFINITY, 10f))
        assertEquals(null, renderableWrappedSegmentRange(0f, 1_000f, 999.5f))

        val wrapped = renderableWrappedSegmentRange(-20f, 1_000f, 10f)!!
        assertEquals(980f, wrapped.start, 0f)
        assertEquals(10f, wrapped.length, 0f)
        assertTrue(lightImpulseTravellingSegmentIsSafe(165f, 1_000f))
        assertTrue(!lightImpulseTravellingSegmentIsSafe(166f, 1_000f))
    }

    @Test
    fun `reminder impulse reserves a two point two second wall clock cycle`() {
        assertEquals(LIGHT_IMPULSE_DURATION_SECONDS, finiteDurationFor(HaloRenderMode.LIGHT_IMPULSE, 1f), 0f)
        assertEquals(0.6f, finiteDurationFor(HaloRenderMode.NORMAL, 0.6f), 0f)
        assertEquals(198L, lightImpulseIgnitionDurationMs())
    }
}
