package com.yp.luminote.app.ui.effects

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayCalibrationFineTuneTest {
    @Test fun `one physical pixel is converted to density-scaled draft dp`() {
        assertEquals(10.5f, adjustCalibrationByPhysicalPx(10f, 1, 2f), 0.0001f)
        assertEquals(9.5f, adjustCalibrationByPhysicalPx(10f, -1, 2f), 0.0001f)
    }
}
