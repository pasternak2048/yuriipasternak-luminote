package com.yp.luminote.app.effects
import com.yp.luminote.app.overlay.coordination.CalibrationPreviewCommandPolicy
import com.yp.luminote.app.overlay.coordination.CalibrationPreviewSession

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationPreviewCommandPolicyTest {
    @Test fun `tokenless preview stop preserves a current calibration lease`() {
        val token = CalibrationPreviewSession.start()
        assertTrue(CalibrationPreviewCommandPolicy.ignoreTokenlessPreviewStop(token))
        assertTrue(CalibrationPreviewSession.isCurrent(token))
        CalibrationPreviewSession.stop(token)
    }

    @Test fun `normal preview stop remains enabled without calibration`() {
        assertFalse(CalibrationPreviewCommandPolicy.ignoreTokenlessPreviewStop(null))
    }

    @Test fun `calibration start update and stop bypass accessibility routing`() {
        assertTrue(CalibrationPreviewCommandPolicy.bypassAccessibilityRoute("current", false))
        assertTrue(CalibrationPreviewCommandPolicy.bypassAccessibilityRoute("current", true))
        assertTrue(CalibrationPreviewCommandPolicy.bypassAccessibilityRoute(null, true))
        assertFalse(CalibrationPreviewCommandPolicy.bypassAccessibilityRoute(null, false))
    }
}
