package com.yp.luminote.app.rendering.gpu

import android.graphics.BlendMode
import com.yp.luminote.app.animation.definitions.BladeVariant
import com.yp.luminote.app.animation.definitions.HaloEffectSpec
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.effects.model.HaloRenderMode
import com.yp.luminote.app.animation.LightImpulseDrawMode
import com.yp.luminote.app.animation.LightImpulseOwnedSegment
import com.yp.luminote.app.animation.lightImpulseOwnedSegment
import com.yp.luminote.app.animation.lightImpulseProductionDrawPlan
import com.yp.luminote.app.rendering.api.RenderMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuRenderAdmissionTest {
    @Test
    fun `only solid normal full contour animation is admitted to mesh backend`() {
        assertTrue(gpuCanFaithfullyRender(HaloConfig(effectPlan = HaloEffectSpec.FullContour), RenderMode.ANIMATION))
        assertFalse(gpuCanFaithfullyRender(HaloConfig(effectPlan = HaloEffectSpec.FullContour), RenderMode.STATIC_CALIBRATION))
        assertFalse(gpuCanFaithfullyRender(HaloConfig(effectPlan = HaloEffectSpec.FullContour, colorMode = HaloColorMode.GRADIENT), RenderMode.ANIMATION))
        assertFalse(gpuCanFaithfullyRender(HaloConfig(effectPlan = HaloEffectSpec.FullContour, palette = intArrayOf(1, 2)), RenderMode.ANIMATION))
        assertTrue(gpuCanFaithfullyRender(HaloConfig(effectPlan = HaloEffectSpec.FullContour, renderMode = HaloRenderMode.LIGHT_IMPULSE), RenderMode.ANIMATION))
    }

    @Test
    fun `complex effect families are deterministically routed to Canvas`() {
        val specs = listOf(
            HaloEffectSpec.SpecializedField,
            HaloEffectSpec.Supernova,
            HaloEffectSpec.LuminousSegment(.18f),
            HaloEffectSpec.Blade(BladeVariant.AZURE),
            HaloEffectSpec.Blade(BladeVariant.CRIMSON),
            HaloEffectSpec.Blade(BladeVariant.CLASH)
        )
        specs.forEach { spec ->
            assertFalse("$spec", gpuCanFaithfullyRender(HaloConfig(effectPlan = spec), RenderMode.ANIMATION))
        }
    }

    @Test
    fun `GPU admission reason exposes mode palette and effect rejection`() {
        assertEquals("mode=STATIC_CALIBRATION", gpuAdmissionReason(HaloConfig(), RenderMode.STATIC_CALIBRATION))
        assertEquals("paletteSize=2", gpuAdmissionReason(HaloConfig(palette = intArrayOf(1, 2)), RenderMode.ANIMATION))
        assertTrue(gpuAdmissionReason(HaloConfig(), RenderMode.ANIMATION) == null)
    }

    @Test
    fun `GPU debug witness requires both debug build and loggable tag`() {
        assertFalse(gpuDebugProofEnabled(false, true))
        assertFalse(gpuDebugProofEnabled(true, false))
        assertTrue(gpuDebugProofEnabled(true, true))
    }

    @Test
    fun `opaque composite witness requires an already enabled witness session`() {
        assertFalse(gpuDebugCompositeEnabled(false, true))
        assertFalse(gpuDebugCompositeEnabled(true, false))
        assertTrue(gpuDebugCompositeEnabled(true, true))
    }

    @Test
    fun `debug AGSL shaders use portable vec4 color uniform declarations`() {
        assertFalse(GpuMeshShaders.FULL.contains("layout(color)"))
        assertFalse(GpuMeshShaders.LIGHT.contains("layout(color)"))
        assertTrue(GpuMeshShaders.FULL.contains("uniform vec4 resolvedColor"))
        assertTrue(GpuMeshShaders.LIGHT.contains("uniform vec4 resolvedColor"))
    }

    @Test
    fun `plain vec4 resolved color binding uses normalized opaque RGBA`() {
        val color = 0x7F4080C0
        assertEquals(64f / 255f, gpuResolvedColorComponent(color, 16), 0f)
        assertEquals(128f / 255f, gpuResolvedColorComponent(color, 8), 0f)
        assertEquals(192f / 255f, gpuResolvedColorComponent(color, 0), 0f)
    }

    @Test
    fun `adaptive tessellation subdivides curved or sharply turning spans within a bound`() {
        val straight = adaptiveContourFractions({ f -> GpuContourSample(f, 0f, 1f, 0f) })
        val curved = adaptiveContourFractions({ f ->
            val angle = f * Math.PI.toFloat()
            GpuContourSample(kotlin.math.cos(angle), kotlin.math.sin(angle), -kotlin.math.sin(angle), kotlin.math.cos(angle))
        })
        assertEquals(2, straight!!.size)
        assertTrue(curved!!.size > straight.size)
        assertTrue(curved.size <= 1_025)
    }

    @Test
    fun `feather coverage is opaque in core and reaches zero one pixel outside`() {
        assertEquals(1f, featherCoverage(4f, 4f), 0f)
        assertEquals(0.5f, featherCoverage(4.5f, 4f), 0f)
        assertEquals(0f, featherCoverage(5f, 4f), 0f)
    }

    @Test
    fun `full contour topology includes opaque core plus both feather bands`() {
        val coverage = fullContourCoveragePattern()
        assertEquals(fullContourVerticesPerSegment(), coverage.size)
        assertEquals(6, coverage.count { it == 0f })
        assertEquals(12, coverage.count { it == 1f })
        assertTrue(coverage.sliceArray(6..11).all { it == 1f })
    }

    @Test
    fun `mesh bounds include the full outer feather envelope`() {
        val bounds = expandedMeshBounds(10f, 20f, 30f, 40f, 8f)
        assertEquals(5f, bounds.left, 0f)
        assertEquals(15f, bounds.top, 0f)
        assertEquals(35f, bounds.right, 0f)
        assertEquals(45f, bounds.bottom, 0f)
    }

    @Test
    fun `adaptive cap fails rather than accepting an out of tolerance contour`() {
        assertNull(adaptiveContourFractions({ f ->
            val angle = f * Math.PI.toFloat()
            GpuContourSample(kotlin.math.cos(angle), kotlin.math.sin(angle), -kotlin.math.sin(angle), kotlin.math.cos(angle))
        }, maxSegments = 1))
    }

    @Test
    fun `Light Impulse GPU admission keeps production plan phase mapping`() {
        val config = HaloConfig(renderMode = HaloRenderMode.LIGHT_IMPULSE)
        assertTrue(gpuCanFaithfullyRender(config, RenderMode.ANIMATION))
        assertEquals(LightImpulseDrawMode.TOP_BLOOM, lightImpulseProductionDrawPlan(.02f).mode)
        assertEquals(LightImpulseDrawMode.OWNED_TRAVEL_FIELDS, lightImpulseProductionDrawPlan(.5f).mode)
        assertEquals(LightImpulseDrawMode.CONVERGING_FIELDS, lightImpulseProductionDrawPlan(.85f).mode)
        assertEquals(LightImpulseDrawMode.BOTTOM_BLOOM, lightImpulseProductionDrawPlan(.96f).mode)
    }

    @Test
    fun `Light Impulse owned segment stays on its source branch without wrapping`() {
        val out = LightImpulseOwnedSegment()
        val segment = lightImpulseOwnedSegment(origin = 100f, direction = -1, frontDistance = 30f,
            distanceBehindFront = 6f, requestedLength = 10f, maxDistance = 40f, minSourceDistance = 4f, out = out)
        assertTrue(segment != null)
        assertTrue(out.startDistance >= 4f)
        assertTrue(out.endDistance <= 40f)
        assertEquals(100f - ((out.startDistance + out.endDistance) / 2f), out.pathCenter, 0f)
    }

    @Test
    fun `three layer Light Impulse mesh cache remains below one MiB at adaptive cap`() {
        assertTrue(lightImpulseMeshCacheBytes(1_024) < 1_024 * 1_024)
        assertEquals(196_608, lightImpulseMeshCacheBytes(1_024))
    }

    @Test
    fun `Light Impulse source and reservation uniforms follow every phase family`() {
        assertFalse(lightImpulseGpuSourceIsBottom(.02f))
        assertFalse(lightImpulseGpuSourceIsBottom(.50f))
        assertTrue(lightImpulseGpuSourceIsBottom(.85f))
        assertTrue(lightImpulseGpuSourceIsBottom(.96f))
        assertEquals(0f, lightImpulseGpuFieldMask(.02f, .03f, false), 0f)
        assertEquals(1f, lightImpulseGpuFieldMask(.03f, .03f, false), 0f)
        assertEquals(0f, lightImpulseGpuFieldMask(.8f, .03f, true), 0f)
    }

    @Test
    fun `owned travel reserves its top source even when production plan has no bloom radius`() {
        val plan = lightImpulseProductionDrawPlan(.50f, contourLength = 1_000f)
        assertEquals(LightImpulseDrawMode.OWNED_TRAVEL_FIELDS, plan.mode)
        assertEquals(0f, plan.sourceBloomRadius, 0f)
        assertTrue(lightImpulseGpuSourceReservation(.50f, plan.mode, 1_000f, plan.sourceBloomRadius) > 0f)
    }

    @Test
    fun `stable primitive geometry key avoids a warmed frame geometry lookup`() {
        assertTrue(lightImpulseGpuCacheMatches(7, 9f, 1f, 2f, .5f, 7, 9f, 1f, 2f, .5f))
        assertFalse(lightImpulseGpuCacheMatches(7, 9f, 1f, 2f, .5f, 8, 9f, 1f, 2f, .5f))
        assertFalse(lightImpulseGpuCacheMatches(7, 9f, 1f, 2f, .5f, 7, 10f, 1f, 2f, .5f))
    }

    @Test
    fun `Light Impulse transverse and longitudinal profiles are continuous rather than stacked rails`() {
        assertEquals(0f, lightImpulseTransverseProfile(0f), 0f)
        assertEquals(1f, lightImpulseTransverseProfile(1f), 0f)
        assertTrue(lightImpulseTransverseProfile(.5f) in 0f..1f)
        val before = lightImpulseLongitudinalProfile(.499f, 1f, .03f, .46f, 1f)
        val after = lightImpulseLongitudinalProfile(.501f, 1f, .03f, .46f, 1f)
        assertTrue(kotlin.math.abs(after - before) < .02f)
        assertTrue(lightImpulseLongitudinalProfile(.70f, 1f, .03f, .46f, 1f) <= 1f)
    }

    @Test
    fun `Light Impulse soft union is bounded symmetric and does not accumulate brightness`() {
        val left = lightImpulseSmoothUnion(.46f, .82f)
        val right = lightImpulseSmoothUnion(.82f, .46f)
        assertEquals(left, right, .000001f)
        assertTrue(left in .46f.. .82f)
        assertEquals(.7f, lightImpulseSmoothUnion(.7f, .7f), .00001f)
        assertTrue(lightImpulseSmoothUnion(1f, 1f) <= 1f)
        assertTrue(lightImpulseSmoothUnion(Float.NaN, .5f).isFinite())
    }

    @Test
    fun `Light Impulse Mesh composition preserves shader destination`() {
        // BaseCanvas drawMesh uses the Mesh output as destination and Paint as source.
        assertEquals(BlendMode.DST, lightImpulseMeshBlendMode())
    }

    @Test
    fun `zero length ignition or fade field is finite and bloom only`() {
        assertEquals(0f, lightImpulseLongitudinalProfile(0f, 0f, .03f, .46f, 1f), 0f)
        assertEquals(0f, lightImpulseLongitudinalProfile(.1f, Float.NaN, .03f, .46f, 1f), 0f)
    }

    @Test
    fun `Light Impulse field uniforms reject nonfinite or negative values`() {
        assertEquals(0f, finiteNonNegativeGpuUniform(Float.NaN), 0f)
        assertEquals(0f, finiteNonNegativeGpuUniform(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, finiteNonNegativeGpuUniform(-.1f), 0f)
        assertEquals(.2f, finiteNonNegativeGpuUniform(.2f), 0f)
    }
}
