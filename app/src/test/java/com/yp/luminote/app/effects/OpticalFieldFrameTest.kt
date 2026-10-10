package com.yp.luminote.app.effects

import com.yp.luminote.app.data.settings.HaloBladeVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpticalFieldFrameTest {
    @Test
    fun `frame keeps the full bounded generic lobe set and clears unused uniforms`() {
        val frame = OpticalFieldFrame().reset(color = 0x00112233, intensity = 1.5f)
            .addLobe(anchorFraction = -0.25f, spanFraction = 0.2f, energy = 1f, direction = 1)
            .addLobe(anchorFraction = 0.5f, spanFraction = 0.1f, energy = 0.75f, direction = -1)
            .addLobe(anchorFraction = 0f, spanFraction = 0.05f, energy = 0.5f, direction = 0)
            .addLobe(anchorFraction = 0.8f, spanFraction = 0.05f, energy = 0.25f, direction = 0)
            .addLobe(anchorFraction = 0.9f, spanFraction = 0.05f, energy = 1f, direction = 1)
            .addLobe(anchorFraction = 0.1f, spanFraction = 0.05f, energy = 1f, direction = -1)
            .addLobe(anchorFraction = 0.2f, spanFraction = 0.05f, energy = 1f, direction = 0)
            .addLobe(anchorFraction = 0.3f, spanFraction = 0.05f, energy = 1f, direction = 1)
            .addLobe(anchorFraction = 0.4f, spanFraction = 0.05f, energy = 1f, direction = 1)
            .addLobe(anchorFraction = 0.5f, spanFraction = 0.05f, energy = 1f, direction = 1)
            .addLobe(anchorFraction = 0.6f, spanFraction = 0.05f, energy = 1f, direction = 1)
            .addLobe(anchorFraction = 0.7f, spanFraction = 0.05f, energy = 1f, direction = 1)
            .addLobe(anchorFraction = 0.8f, spanFraction = 0.05f, energy = 1f, direction = 1)

        val first = FloatArray(4)
        val unused = FloatArray(4)
        frame.lobe(0, first)
        frame.lobe(CONTOUR_MAX_LOBES - 1, unused)

        assertEquals(CONTOUR_MAX_LOBES, frame.lobeCount())
        assertEquals(0.75f, first[0], 0.0001f)
        assertEquals(1f, frame.intensity, 0f)
        assertEquals(0xFF112233.toInt(), frame.color)
        assertEquals(0.7f, unused[0], 0.0001f)
        assertEquals(1, frame.rejectedLobeCount())
    }

    @Test
    fun `four directed duel emitters retain all twelve coloured field bands`() {
        val effects = GpuEffectFrame().reset(0xFFFFFFFF.toInt(), 1f)
        GpuEffectPrograms.laser(effects, phase = 0.2f, variant = HaloBladeVariant.CLASH, energy = 1f)
        val field = OpticalFieldFrame().reset(effects.color, effects.intensity)
        val adapter = ContourOpticalRenderer()

        repeat(effects.emitterCount()) { adapter.appendEmitter(field, effects.emitter(it)) }

        assertEquals(4, effects.emitterCount())
        assertEquals(CONTOUR_MAX_LOBES, field.lobeCount())
        assertEquals(0, field.rejectedLobeCount())
        // Each emitter contributes bloom/body/core in order. The latter two emitters are crimson.
        assertEquals(0xFF0874FF.toInt(), field.lobeColor(1))
        assertEquals(0xFFFF0808.toInt(), field.lobeColor(7))
        assertEquals(0xFFFF0808.toInt(), field.lobeColor(10))
    }

    @Test
    fun `directional intervals reserve their adjacent core and palette is fixed capacity`() {
        val frame = OpticalFieldFrame().reset(color = 0xFF010203.toInt(), intensity = 1f)
            .addLobe(
                anchorFraction = 0.25f,
                startOffsetFraction = 0.04f,
                spanFraction = 0.20f,
                energy = 1f,
                direction = 1
            )
            .setPalette(IntArray(CONTOUR_MAX_PALETTE_COLORS) { 0x00010203 + it }, phase = -0.25f)
        val lobe = FloatArray(4)
        frame.lobe(0, lobe)

        assertEquals(0.04f, lobe[1], 0f)
        assertEquals(0.20f, lobe[2], 0f)
        assertEquals(1f, frame.lobeDirection(0), 0f)
        assertEquals(CONTOUR_MAX_PALETTE_COLORS, frame.paletteCount())
        assertEquals(0.75f, frame.palettePhase(), 0f)
        assertEquals(0xFF010203.toInt(), frame.paletteColor(0))
        assertEquals(0xFF01020A.toInt(), frame.paletteColor(7))

        assertEquals(0f, ContourOpticalFieldMath.intervalLobeEnergy(0.24f, 0.25f, 0.04f, 0.20f, 1f, 1), 0f)
        assertTrue(ContourOpticalFieldMath.intervalLobeEnergy(0.19f, 0.25f, 0.04f, 0.20f, 1f, 1) > 0f)
    }

    @Test
    fun `arbitrary palettes are resampled into the fixed GPU representation`() {
        val frame = OpticalFieldFrame().reset(color = 0xFF010203.toInt(), intensity = 1f)
            .setPalette(IntArray(CONTOUR_MAX_PALETTE_COLORS + 3) { 0xFF000000.toInt() or (it * 0x00010101) }, phase = 0f)

        assertEquals(CONTOUR_MAX_PALETTE_COLORS, frame.paletteCount())
        assertEquals(0xFF000000.toInt(), frame.paletteColor(0))
        assertEquals(0xFF090909.toInt(), frame.paletteColor(7))
    }

    @Test
    fun `uniform palettes are made opaque because field owns optical alpha`() {
        val frame = OpticalFieldFrame().reset(color = 0xFF010203.toInt(), intensity = 1f)
            .setPaletteUniforms(IntArray(CONTOUR_MAX_PALETTE_COLORS) { 0x00010203 }, 2, 0f)

        assertEquals(0xFF010203.toInt(), frame.paletteColor(0))
    }

    @Test
    fun `explicit lobe colour is retained separately from the frame palette`() {
        val frame = OpticalFieldFrame().reset(color = 0xFF112233.toInt(), intensity = 1f)
            .setPalette(intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()), 0f)
            .addLobe(.2f, 0f, .2f, 1f, 1, .1f, .8f, .8f, 1.5f, 0xFF408CFF.toInt())
            .addLobe(.6f, 0f, .2f, 1f, -1, .1f, .8f, .8f, 1.5f)

        assertEquals(0xFF408CFF.toInt(), frame.lobeColor(0))
        assertEquals(1f, frame.lobeHasExplicitColor(0), 0f)
        assertEquals(0xFF112233.toInt(), frame.lobeColor(1))
        assertEquals(0f, frame.lobeHasExplicitColor(1), 0f)
    }

    @Test
    fun `local lobe colours are opaque because the optical field exclusively owns alpha`() {
        val frame = OpticalFieldFrame().reset(color = 0x00112233, intensity = 1f)
            .addLobe(.2f, 0f, .2f, 1f, 1, .1f, .8f, .8f, 1.5f, 0x00010203)

        // A caller's transparent swatch must not turn a live lobe into a compositing fringe.
        assertEquals(0xFF010203.toInt(), frame.lobeColor(0))
        assertEquals(1f, frame.lobeHasExplicitColor(0), 0f)
    }

    @Test
    fun `reused field clears omitted lobe and palette state before the next submission`() {
        val frame = OpticalFieldFrame().reset(color = 0xFF112233.toInt(), intensity = 1f)
            .setPalette(intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()), 0.4f)
            .addLobe(.97f, 0f, .12f, 1f, 1, .1f, .8f, .8f, 1.5f, 0xFF010203.toInt())

        frame.reset(color = 0x00445566, intensity = 1f)
        val lobe = FloatArray(4)
        val profile = FloatArray(4)
        frame.lobe(0, lobe)
        frame.lobeProfile(0, profile)

        assertEquals(0, frame.lobeCount())
        assertEquals(0, frame.paletteCount())
        assertEquals(0f, lobe[3], 0f)
        assertEquals(0f, frame.lobeDirection(0), 0f)
        assertEquals(0f, frame.lobeHasExplicitColor(0), 0f)
        assertEquals(0xFF445566.toInt(), frame.lobeColor(0))
        assertEquals(1f, profile[2], 0f)
    }

    @Test
    fun `profiled directional tail has equal energy on either representation of the closed seam`() {
        val atZero = ContourOpticalFieldMath.profiledLobeEnergy(
            contourU = 0f, anchorU = .02f, startOffsetFraction = 0f, spanFraction = .18f,
            energy = .8f, direction = 1, headTaperFraction = .1f, releaseFraction = .6f
        )
        val atOne = ContourOpticalFieldMath.profiledLobeEnergy(
            contourU = 1f, anchorU = .02f, startOffsetFraction = 0f, spanFraction = .18f,
            energy = .8f, direction = 1, headTaperFraction = .1f, releaseFraction = .6f
        )

        assertEquals(atZero, atOne, 0.0001f)
        assertTrue(atZero > 0f)
    }

    @Test
    fun `profiled lobe supports a tapered head bright body and smooth release`() {
        val head = ContourOpticalFieldMath.profiledLobeEnergy(
            contourU = 0.50f, anchorU = 0.50f, startOffsetFraction = 0f, spanFraction = 0.20f,
            energy = 1f, direction = 1, headTaperFraction = 0.25f, releaseFraction = 0.70f
        )
        val body = ContourOpticalFieldMath.profiledLobeEnergy(
            contourU = 0.44f, anchorU = 0.50f, startOffsetFraction = 0f, spanFraction = 0.20f,
            energy = 1f, direction = 1, headTaperFraction = 0.25f, releaseFraction = 0.70f
        )
        val tail = ContourOpticalFieldMath.profiledLobeEnergy(
            contourU = 0.32f, anchorU = 0.50f, startOffsetFraction = 0f, spanFraction = 0.20f,
            energy = 1f, direction = 1, headTaperFraction = 0.25f, releaseFraction = 0.70f
        )

        assertEquals(0f, head, 0f)
        assertTrue(body > 0.9f)
        assertTrue(tail in 0f..<body)
    }

    @Test
    fun `localized profiled lobe retains its maximal hot centre`() {
        val centre = ContourOpticalFieldMath.profiledLobeEnergy(
            contourU = 0.50f, anchorU = 0.50f, startOffsetFraction = 0f, spanFraction = 0.20f,
            energy = 0.8f, direction = 0, headTaperFraction = 0.25f, releaseFraction = 0.70f
        )
        val edge = ContourOpticalFieldMath.profiledLobeEnergy(
            contourU = 0.32f, anchorU = 0.50f, startOffsetFraction = 0f, spanFraction = 0.20f,
            energy = 0.8f, direction = 0, headTaperFraction = 0.25f, releaseFraction = 0.70f
        )

        assertEquals(0.8f, centre, 0.0001f)
        assertTrue(edge in 0f..<centre)
    }

    @Test
    fun `invalid lobes cannot consume fixed frame capacity`() {
        val frame = OpticalFieldFrame().reset(color = 0, intensity = -1f)
            .addLobe(anchorFraction = Float.NaN, spanFraction = 0.1f, energy = 1f, direction = 1)
            .addLobe(anchorFraction = 0f, spanFraction = 0f, energy = 1f, direction = 1)
            .addLobe(anchorFraction = 0f, spanFraction = 0.1f, energy = 1f, direction = 2)
        val out = FloatArray(4)
        frame.lobe(0, out)

        assertEquals(0, frame.lobeCount())
        assertEquals(0f, frame.intensity, 0f)
        assertEquals(0f, out[3], 0f)
    }

    @Test
    fun `directional field lights only the tail side including across the seam`() {
        val forwardAcrossSeam = ContourOpticalFieldMath.lobeEnergy(
            contourU = 0.99f, anchorU = 0.01f, spanFraction = 0.1f, energy = 1f, direction = 1
        )
        val forwardAhead = ContourOpticalFieldMath.lobeEnergy(
            contourU = 0.03f, anchorU = 0.01f, spanFraction = 0.1f, energy = 1f, direction = 1
        )
        val reverseAcrossSeam = ContourOpticalFieldMath.lobeEnergy(
            contourU = 0.01f, anchorU = 0.99f, spanFraction = 0.1f, energy = 1f, direction = -1
        )
        val reverseAhead = ContourOpticalFieldMath.lobeEnergy(
            contourU = 0.97f, anchorU = 0.99f, spanFraction = 0.1f, energy = 1f, direction = -1
        )

        assertTrue(forwardAcrossSeam > 0f)
        assertEquals(0f, forwardAhead, 0f)
        assertTrue(reverseAcrossSeam > 0f)
        assertEquals(0f, reverseAhead, 0f)
    }

    @Test
    fun `transverse feather reaches zero at both ribbon boundaries and output is bounded`() {
        assertEquals(0f, ContourOpticalFieldMath.transverseEnergy(0f), 0f)
        assertEquals(0f, ContourOpticalFieldMath.transverseEnergy(1f), 0f)
        assertEquals(1f, ContourOpticalFieldMath.transverseEnergy(0.5f), 0.0001f)

        listOf(-1f, 0f, 0.25f, 0.5f, 0.75f, 1f, 2f, Float.NaN).forEach { v ->
            val alpha = ContourOpticalFieldMath.alpha(1.5f, v, 1.5f)
            assertTrue(alpha.isFinite())
            assertTrue(alpha in 0f..1f)
        }
    }

    @Test
    fun `pre intensity optical lift preserves endpoints intensity and smooth low field response`() {
        assertEquals(0f, ContourOpticalFieldMath.displayAlpha(0f), 0f)
        assertEquals(1f, ContourOpticalFieldMath.displayAlpha(1f), 0f)
        assertEquals(0.25f, ContourOpticalFieldMath.displayAlpha(1f, 0.25f), 0f)
        assertTrue(ContourOpticalFieldMath.displayAlpha(0.1029f) > 0.1029f)
        assertTrue(ContourOpticalFieldMath.displayAlpha(0.1f) < ContourOpticalFieldMath.displayAlpha(0.2f))
    }
}
