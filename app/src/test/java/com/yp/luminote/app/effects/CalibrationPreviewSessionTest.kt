package com.yp.luminote.app.effects

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationPreviewSessionTest {
    @Test fun `stale stop cannot clear newer calibration lease`() {
        val first = CalibrationPreviewSession.start()
        val second = CalibrationPreviewSession.start()
        assertFalse(CalibrationPreviewSession.stop(first))
        assertTrue(CalibrationPreviewSession.isCurrent(second))
        assertTrue(CalibrationPreviewSession.stop(second))
    }

    @Test fun `only current lease accepts update on either renderer route`() {
        val token = CalibrationPreviewSession.start()
        assertTrue(CalibrationPreviewSession.isCurrent(token))
        assertFalse(CalibrationPreviewSession.isCurrent("stale-$token"))
        CalibrationPreviewSession.stop(token)
    }
}
