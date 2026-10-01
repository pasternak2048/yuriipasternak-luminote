package com.yp.luminote.app.effects

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationPreviewSessionTest {
    @Test fun `stale stop cannot clear newer calibration lease`() {
        val first = "first"
        CalibrationPreviewSession.activate(first)
        val second = "second"
        CalibrationPreviewSession.activate(second)
        assertFalse(CalibrationPreviewSession.stop(first))
        assertTrue(CalibrationPreviewSession.isCurrent(second))
        assertTrue(CalibrationPreviewSession.stop(second))
    }

    @Test fun `only current lease accepts update on either renderer route`() {
        val token = "current"
        val generation = CalibrationPreviewSession.activate(token)
        assertTrue(CalibrationPreviewSession.isCurrent(token, generation))
        assertFalse(CalibrationPreviewSession.isCurrent("stale-$token", generation))
        CalibrationPreviewSession.stop(token)
    }

    @Test fun `stale pause generation cannot affect resumed calibration lease`() {
        val token = "same-screen"
        val stoppedGeneration = CalibrationPreviewSession.activate(token)
        val resumedGeneration = CalibrationPreviewSession.activate(token)

        assertFalse(CalibrationPreviewSession.isCurrent(token, stoppedGeneration))
        assertTrue(CalibrationPreviewSession.isCurrent(token, resumedGeneration))
        CalibrationPreviewSession.stop(token)
    }

    @Test fun `stale terminal stop cannot clear resumed calibration lease`() {
        val token = "same-screen-terminal"
        val firstGeneration = CalibrationPreviewSession.activate(token)
        val resumedGeneration = CalibrationPreviewSession.activate(token)

        assertFalse(CalibrationPreviewSession.stop(token, firstGeneration))
        assertTrue(CalibrationPreviewSession.isCurrent(token, resumedGeneration))
        assertTrue(CalibrationPreviewSession.stop(token, resumedGeneration))
    }
}
