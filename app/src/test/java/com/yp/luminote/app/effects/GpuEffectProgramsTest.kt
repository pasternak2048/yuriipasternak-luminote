package com.yp.luminote.app.effects

import com.yp.luminote.app.data.settings.HaloBladeVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuEffectProgramsTest {
    @Test
    fun `pulse emits one closed contour field`() {
        val frame = GpuEffectFrame().reset(0xFF3366FF.toInt(), 1f)
        GpuEffectPrograms.closedPulse(frame, 0.8f)

        assertEquals(1, frame.emitterCount())
        assertEquals(0, frame.emitter(0).direction)
    }

    @Test
    fun `snake emits one directed beam`() {
        val frame = GpuEffectFrame().reset(0xFF3366FF.toInt(), 1f)
        GpuEffectPrograms.snake(frame, head = 0.98f, energy = 1f)

        assertEquals(1, frame.emitterCount())
        assertEquals(1, frame.emitter(0).direction)
        assertTrue(frame.emitter(0).length > 0f)
        assertEquals(1f, frame.emitter(0).coreEnergy)
    }

    @Test
    fun `impulse terminal fade intentionally reduces its sole source energy`() {
        val frame = GpuEffectFrame().reset(0xFF3366FF.toInt(), 1f)

        // phase .984 is in the last Impulse phase: (0.984 - .92) / .08 = .8,
        // so smoothstep leaves approximately 10.4% energy for the final release.
        GpuEffectPrograms.impulse(frame, phase = 0.984f, origin = 0.1f, convergence = 0.6f, energy = 1f)

        assertEquals(1, frame.emitterCount())
        assertEquals(0, frame.emitter(0).direction)
        assertEquals(0.104f, frame.emitter(0).coreEnergy, 0.0001f)
    }

    @Test
    fun `impulse starts localized and travels as two beams`() {
        val ignition = GpuEffectFrame().reset(0xFF3366FF.toInt(), 1f)
        GpuEffectPrograms.impulse(ignition, phase = 0.01f, origin = 0.1f, convergence = 0.6f, energy = 1f)
        assertEquals(1, ignition.emitterCount())
        assertEquals(0, ignition.emitter(0).direction)

        val travel = GpuEffectFrame().reset(0xFF3366FF.toInt(), 1f)
        GpuEffectPrograms.impulse(travel, phase = 0.5f, origin = 0.1f, convergence = 0.6f, energy = 1f)
        assertEquals(3, travel.emitterCount())
        assertEquals(1, travel.emitter(1).direction)
        assertEquals(-1, travel.emitter(2).direction)
    }

    @Test
    fun `clash opens with four independently coloured beams`() {
        val frame = GpuEffectFrame().reset(0xFF3366FF.toInt(), 1f)
        GpuEffectPrograms.laser(frame, phase = 0.2f, variant = HaloBladeVariant.CLASH, energy = 1f)

        assertEquals(4, frame.emitterCount())
        assertEquals(1, frame.emitter(0).direction)
        assertEquals(-1, frame.emitter(1).direction)
        assertTrue(frame.emitter(0).bodyHasColor)
        assertTrue(frame.emitter(2).bodyHasColor)
        assertTrue(frame.emitter(0).bodyColor != frame.emitter(2).bodyColor)
    }

    @Test
    fun `single blades retain their distinct explicit colours`() {
        val azure = GpuEffectFrame().reset(0xFF00FF00.toInt(), 1f)
        val crimson = GpuEffectFrame().reset(0xFF00FF00.toInt(), 1f)

        GpuEffectPrograms.laser(azure, phase = 0.5f, variant = HaloBladeVariant.AZURE, energy = 1f)
        GpuEffectPrograms.laser(crimson, phase = 0.5f, variant = HaloBladeVariant.CRIMSON, energy = 1f)

        assertEquals(1, azure.emitterCount())
        assertEquals(1, crimson.emitterCount())
        assertTrue(azure.emitter(0).bodyHasColor)
        assertTrue(crimson.emitter(0).bodyHasColor)
        assertTrue(azure.emitter(0).bodyColor != crimson.emitter(0).bodyColor)
    }
}
