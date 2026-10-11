package com.yp.luminote.app.effects
import com.yp.luminote.app.rendering.canvas.CalibrationDiagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationDiagnosticsTest {
    @Test fun `diagnostics are static-only and reject empty bounds`() {
        assertTrue(CalibrationDiagnostics.shouldRender(staticFrameMode = true, width = 100, height = 200))
        assertFalse(CalibrationDiagnostics.shouldRender(staticFrameMode = false, width = 100, height = 200))
        assertFalse(CalibrationDiagnostics.shouldRender(staticFrameMode = true, width = 0, height = 200))
    }

    @Test fun `registration reports zero inward and outward edge offsets`() {
        assertEquals(CalibrationDiagnostics.Registration.ZERO, CalibrationDiagnostics.registration(0f))
        assertEquals(CalibrationDiagnostics.Registration.IN, CalibrationDiagnostics.registration(1f))
        assertEquals(CalibrationDiagnostics.Registration.OUT, CalibrationDiagnostics.registration(-1f))
    }

    @Test fun `registration exposes a locale-neutral magnitude for resource formatting`() {
        assertEquals(1f, CalibrationDiagnostics.registrationMagnitudePx(-1f), 0f)
        assertEquals(1.5f, CalibrationDiagnostics.registrationMagnitudePx(-1.5f), 0f)
    }

    @Test fun `physical ruler includes next tick after fractional displacement`() {
        assertEquals(10f, CalibrationDiagnostics.rulerDepthPx(5.2f), 0f)
        assertEquals(10f, CalibrationDiagnostics.rulerDepthPx(-5.2f), 0f)
        assertEquals(4f, CalibrationDiagnostics.rulerDepthPx(0f), 0f)
        assertEquals(5.2f, CalibrationDiagnostics.rulerMarkerPx(5.2f), 0f)
    }
}
