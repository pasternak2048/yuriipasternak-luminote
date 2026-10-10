package com.yp.luminote.app.effects
import com.yp.luminote.app.effects.model.MotionRenderCommandKind
import com.yp.luminote.app.effects.model.MotionBladeVariant
import com.yp.luminote.app.effects.model.MotionRenderFrame
import com.yp.luminote.app.effects.model.MotionRenderCommand
import com.yp.luminote.app.effects.model.MotionRenderEmitters

import com.yp.luminote.app.data.settings.HaloMotion
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionRenderCommandTest {
    private val frame = MotionRenderFrame()
    private val command = MotionRenderCommand()

    @Test
    fun `resolved emitters preserve the existing motion primitive mapping`() {
        assertCommand(HaloMotion.IMPULSE, MotionRenderCommandKind.SPECIALIZED_FIELD)
        assertCommand(HaloMotion.PULSE, MotionRenderCommandKind.FULL_CONTOUR)
        assertCommand(HaloMotion.AZURE_BLADE, MotionRenderCommandKind.BLADE, MotionBladeVariant.AZURE)
        assertCommand(HaloMotion.CRIMSON_BLADE, MotionRenderCommandKind.BLADE, MotionBladeVariant.CRIMSON)
        assertCommand(HaloMotion.FORCE_CLASH, MotionRenderCommandKind.BLADE, MotionBladeVariant.CLASH)
    }

    @Test
    fun `snake emitter keeps its wrapped start and established segment length`() {
        frame.phase = -0.25f

        MotionRenderEmitters.resolve(HaloMotion.SNAKE).emit(frame, command)

        assertEquals(MotionRenderCommandKind.LUMINOUS_SEGMENT, command.kind)
        assertEquals(0.75f, command.segmentStartFraction, 0f)
        assertEquals(0.18f, command.segmentLengthFraction, 0f)
    }

    private fun assertCommand(
        motion: HaloMotion,
        expectedKind: MotionRenderCommandKind,
        expectedBlade: MotionBladeVariant? = null
    ) {
        MotionRenderEmitters.resolve(motion).emit(frame, command)
        assertEquals(expectedKind, command.kind)
        if (expectedBlade != null) assertEquals(expectedBlade, command.bladeVariant)
    }
}
