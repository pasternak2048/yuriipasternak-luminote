package com.yp.luminote.app.rendering.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpuPulseEvidenceEvictionTest {
    @Test
    fun evictedLiveGenerationCanRecordAndDisposeWithoutAffectingCurrentEvidence() {
        GpuPulseEvidence.resetForTest()
        try {
            val old = GpuPulseEvidence.beginGeneration()
            repeat(32) { GpuPulseEvidence.beginGeneration() }
            val current = GpuPulseEvidence.latestGeneration()!!
            val before = GpuPulseEvidence.snapshot(current)
            GpuPulseEvidence.recordResourceInitialization(old, 7, 1)
            GpuPulseEvidence.recordShaderInitializationAttempt(old)
            GpuPulseEvidence.recordShaderInitialization(old)
            GpuPulseEvidence.recordPreSubmitAttempt(old)
            GpuPulseEvidence.recordFirstFailure(old, 7,
                GpuPulsePreSubmitFailure.at("field-preparation", IllegalStateException("injected")))
            GpuPulseEvidence.recordLatchedFailure(old)
            GpuPulseEvidence.recordSubmission(old, 255)
            GpuPulseEvidence.recordFallback(old, "injected")
            GpuPulseEvidence.recordCanvasPulse(old)
            GpuPulseEvidence.recordSurfaceInvalidation(old, 2)
            GpuPulseEvidence.recordResourceDisposal(old)
            GpuPulseEvidence.recordDisposal(old)
            assertNull(GpuPulseEvidence.snapshot(old))
            assertNull(GpuPulseEvidence.awaitSubmitted(old, 0))
            assertNull(GpuPulseEvidence.awaitSubmissionCount(old, 1, 0))
            assertEquals(before, GpuPulseEvidence.snapshot(current))
            GpuPulseEvidence.recordSubmission(current, 200)
            assertEquals(1, GpuPulseEvidence.awaitSubmitted(current, 0)!!.submissions)
        } finally {
            GpuPulseEvidence.resetForTest()
        }
    }
}
