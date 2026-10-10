package com.yp.luminote.app.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuEffectContractTest {
    @Test fun `frame composes source beam and closed contour without animation identities`() {
        val frame = GpuEffectFrame().reset(0xFF3366CC.toInt(), .8f)
            .localizedSource(.1f, .08f, 1f)
            .directedBeam(.5f, 1, .3f, GpuBand(.04f, .5f, 1f), GpuBand(.2f, .8f, .7f), GpuBand(.3f, 1f, .25f))
            .closedEmitter(.2f, .9f)
        assertEquals(3, frame.emitterCount())
        assertEquals(1, frame.emitter(1).direction)
        assertEquals(0, frame.emitter(2).direction)
    }

    @Test fun `palette is bounded at configuration time`() {
        val palette = GpuPalette().configure(IntArray(12) { 0xFF000000.toInt() or (it shl 16) })
        assertEquals(GpuPalette.MAX_COLORS, palette.size)
        assertEquals(0xFF000000.toInt(), palette.colorAt(0))
    }

    @Test fun `premultiplied color never has rgb above alpha`() {
        val color = GpuPremultipliedColor.fromArgb(0x803060FF.toInt(), .5f)
        assertTrue(color.red <= color.alpha && color.green <= color.alpha && color.blue <= color.alpha)
    }

    @Test fun `beam profile retains independent colours and applies energy without profile copies`() {
        val blueCore = GpuBand(.03f, .35f, 1f, color = 0xFF3EA6FF.toInt(), headTaperFraction = .15f)
        val blueBody = GpuBand(.10f, .72f, .7f, color = 0xFF3EA6FF.toInt(), featherExponent = 1.5f)
        val blueBloom = GpuBand(.22f, 1f, .35f, color = 0xFF3EA6FF.toInt(), featherExponent = 1.1f)
        val frame = GpuEffectFrame().reset(0xFFFF0000.toInt(), 1f)
            .directedBeam(.3f, 1, .22f, blueCore, blueBody, blueBloom, energyScale = .5f)

        val emitter = frame.emitter(0)
        assertTrue(emitter.coreHasColor)
        assertEquals(0xFF3EA6FF.toInt(), emitter.coreColor)
        assertEquals(.5f, emitter.coreEnergy, 0f)
        assertEquals(.35f, emitter.bodyEnergy, 0f)
        assertEquals(.15f, emitter.coreHeadTaper, 0f)
        assertEquals(1.1f, emitter.bloomFeather, 0f)
    }

    @Test fun `local source keeps the frame palette unless a local colour is supplied`() {
        val inherited = GpuEffectFrame().reset(0xFF112233.toInt(), 1f).localizedSource(.5f, .1f, 1f).emitter(0)
        val local = GpuEffectFrame().reset(0xFF112233.toInt(), 1f).localizedSource(.5f, .1f, 1f, color = 0xFFFF0033.toInt()).emitter(0)

        assertFalse(inherited.coreHasColor)
        assertTrue(local.coreHasColor)
        assertEquals(0xFFFF0033.toInt(), local.coreColor)
    }

    @Test fun `terminal session failure is sticky until next generation`() {
        val session = GpuRenderSession()
        val first = IllegalStateException("first")
        assertSame(first, session.fail(first))
        assertSame(first, session.fail(IllegalArgumentException("later")))
        assertFalse(session.canSubmit())
        session.beginGeneration()
        assertTrue(session.canSubmit())
    }
}
