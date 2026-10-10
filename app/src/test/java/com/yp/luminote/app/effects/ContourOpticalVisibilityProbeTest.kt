package com.yp.luminote.app.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContourOpticalVisibilityProbeTest {
    @Test fun `debug probe holds each bounded visibility stage before advancing`() {
        val probe = ContourOpticalVisibilityProbe(enabled = true, sink = {})
        val key = ContourRibbonGeometryKey.invalid()
        repeat(120) { assertEquals(ContourOpticalVisibilityStage.OPAQUE_MESH, probe.nextStage(key)) }
        repeat(120) { assertEquals(ContourOpticalVisibilityStage.CONSTANT_AGSL, probe.nextStage(key)) }
        repeat(120) { assertEquals(ContourOpticalVisibilityStage.OPTICAL_FIELD, probe.nextStage(key)) }
        assertNull(probe.nextStage(key))
    }
}
