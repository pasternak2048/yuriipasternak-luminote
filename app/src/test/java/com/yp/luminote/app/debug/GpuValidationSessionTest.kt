package com.yp.luminote.app.debug

import com.yp.luminote.app.rendering.gpu.GpuPulseEvidence
import com.yp.luminote.app.rendering.gpu.GpuPulsePreSubmitFailure
import com.yp.luminote.app.rendering.gpu.GpuWitnessEvidence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GpuValidationSessionTest {
    @Test fun finiteSessionHasExplicitCompletionAndReportsSubmittedFrameCount() {
        val generation = GpuPulseEvidence.beginGeneration()
        val session = session(generation)
        assertEquals(GpuValidationPhase.RUNNING, session.snapshot(null, null).phase)
        GpuPulseEvidence.recordShaderInitializationAttempt(generation)
        GpuPulseEvidence.recordPreSubmitAttempt(generation)
        GpuPulseEvidence.recordSubmission(generation, 255)
        session.complete()
        val snapshot = session.snapshot(GpuPulseEvidence.snapshot(generation), null)
        assertEquals(GpuValidationPhase.COMPLETED, snapshot.phase)
        assertEquals(false, snapshot.active)
        assertEquals(1, snapshot.initializationAttempts)
        assertEquals(1, snapshot.gpuAttempts)
        assertEquals(1, snapshot.gpuSubmittedFrames)
        assertEquals(true, snapshot.toDiagnosticReport().contains("gpuSubmittedFrames=1"))
    }

    @After
    fun resetEvidence() {
        GpuPulseEvidence.resetForTest()
        GpuWitnessEvidence.resetForTest()
    }

    @Test
    fun currentSessionPassesOnlyFromPostBaselineSubmissionEvidence() {
        val generation = GpuPulseEvidence.beginGeneration()
        val session = session(generation)

        GpuPulseEvidence.recordResourceInitialization(generation, fieldKey = 7, surfaceEpoch = 1)
        GpuPulseEvidence.recordShaderInitialization(generation)
        GpuPulseEvidence.recordPreSubmitAttempt(generation)
        GpuPulseEvidence.recordSubmission(generation, alpha = 255)

        val snapshot = session.snapshot(GpuPulseEvidence.snapshot(generation), null)
        assertEquals(GpuValidationState.PASS, snapshot.checks.getValue("GPU initialization").state)
        assertEquals(GpuValidationState.PASS, snapshot.checks.getValue("Shader compilation").state)
        assertEquals(GpuValidationState.PASS, snapshot.checks.getValue("Frame submission").state)
        assertEquals(GpuValidationState.PASS, snapshot.checks.getValue("Actual backend").state)
        assertEquals(GpuValidationState.NOT_TESTABLE, snapshot.checks.getValue("Pixel verification").state)
        assertEquals(VisualEffectsDemoActualBackend.GPU, snapshot.actualBackend)
        assertEquals(0, snapshot.fallbackCount)
    }

    @Test
    fun firstFailureIsPreservedWhenCanvasFallbackContinues() {
        val generation = GpuPulseEvidence.beginGeneration()
        val session = session(generation)
        val failure = GpuPulsePreSubmitFailure.at("shader-compile", IllegalStateException("bad source"))

        GpuPulseEvidence.recordPreSubmitAttempt(generation)
        GpuPulseEvidence.recordFirstFailure(generation, fieldKey = 9, failure)
        GpuPulseEvidence.recordFallback(generation, failure.classification)
        GpuPulseEvidence.recordLatchedFailure(generation)
        GpuPulseEvidence.recordFallback(generation, failure.classification)

        val snapshot = session.snapshot(GpuPulseEvidence.snapshot(generation), null)
        assertEquals(GpuValidationState.FAIL, snapshot.checks.getValue("Frame submission").state)
        assertEquals(GpuValidationState.FAIL, snapshot.checks.getValue("Canvas fallback").state)
        assertEquals(1, snapshot.gpuAttempts)
        assertEquals(1, snapshot.latchedFailureFrames)
        assertEquals(1, snapshot.canvasAfterLatchFrames)
        assertNotNull(snapshot.firstFailure)
        assertEquals("shader-compile", snapshot.firstFailure!!.stage)
        assertEquals(VisualEffectsDemoActualBackend.CANVAS, snapshot.actualBackend)
        assertEquals(2, snapshot.fallbackCount)
        assertEquals(failure.classification, snapshot.fallbackReason)
    }

    @Test
    fun cancelledSessionDoesNotCaptureNewFailure() {
        val generation = GpuPulseEvidence.beginGeneration()
        val session = session(generation)
        session.cancel()
        GpuPulseEvidence.recordFirstFailure(
            generation,
            fieldKey = 3,
            GpuPulsePreSubmitFailure.at("paint-attach", IllegalStateException("late"))
        )

        val snapshot = session.snapshot(GpuPulseEvidence.snapshot(generation), null)
        assertEquals(false, snapshot.active)
        assertEquals(null, snapshot.firstFailure)
        assertEquals(GpuValidationPhase.CANCELLED, snapshot.phase)
    }

    @Test
    fun priorGpuSubmissionIsNotExportedAsCurrentSessionEvidence() {
        val generation = GpuPulseEvidence.beginGeneration()
        GpuPulseEvidence.recordSubmission(generation, alpha = 255)
        val session = session(generation)

        val snapshot = session.snapshot(GpuPulseEvidence.snapshot(generation), null)

        assertEquals(VisualEffectsDemoActualBackend.AWAITING, snapshot.actualBackend)
        assertEquals(0, snapshot.fallbackCount)
        assertEquals(null, snapshot.fallbackReason)
        assertEquals(GpuValidationState.NOT_RUN, snapshot.checks.getValue("Actual backend").state)
        val report = snapshot.toDiagnosticReport()
        assertEquals(true, report.contains("actual=AWAITING"))
        assertEquals(true, report.contains("fallbackCount=0 fallbackReason="))
    }

    @Test
    fun priorSameGenerationFailureIsNotExportedAsCurrentSessionEvidence() {
        val generation = GpuPulseEvidence.beginGeneration()
        val failure = GpuPulsePreSubmitFailure.at("shader-compile", IllegalStateException("prior"))
        GpuPulseEvidence.recordPreSubmitAttempt(generation)
        GpuPulseEvidence.recordFirstFailure(generation, fieldKey = 7, failure)
        GpuPulseEvidence.recordFallback(generation, failure.classification)

        val session = session(generation)

        val snapshot = session.snapshot(GpuPulseEvidence.snapshot(generation), null)
        assertEquals(VisualEffectsDemoActualBackend.AWAITING, snapshot.actualBackend)
        assertEquals(null, snapshot.firstFailure)
        assertEquals(0, snapshot.fallbackCount)
        assertEquals(GpuValidationState.NOT_RUN, snapshot.checks.getValue("Frame submission").state)
        assertEquals(GpuValidationState.NOT_RUN, snapshot.checks.getValue("Canvas fallback").state)
    }

    @Test
    fun witnessSelfTestUsesOnlyCurrentWitnessGenerationEvidence() {
        val generation = GpuWitnessEvidence.beginGpuGeneration()
        val session = GpuValidationSession.start(
            id = 2,
            requestedBackend = VisualEffectsDemoRequestedBackend.GPU_WITNESS,
            selectedOperation = "Full Contour",
            selectedMode = "Baseline",
            activeModifiers = emptyList(),
            capabilityDecision = "Admitted",
            appVersion = "1.2.3",
            deviceModel = "Test Device",
            androidApi = 35,
            generation = generation
        )

        GpuWitnessEvidence.recordShaderInitialization(generation)
        GpuWitnessEvidence.recordGpuSubmission(generation)

        val snapshot = session.snapshot(null, GpuWitnessEvidence.snapshot(generation))
        assertEquals(VisualEffectsDemoActualBackend.GPU, snapshot.actualBackend)
        assertEquals(GpuValidationState.PASS, snapshot.checks.getValue("GPU initialization").state)
        assertEquals(GpuValidationState.PASS, snapshot.checks.getValue("Shader compilation").state)
        assertEquals(GpuValidationState.PASS, snapshot.checks.getValue("Frame submission").state)
        assertEquals(0, snapshot.fallbackCount)
    }

    @Test
    fun priorWitnessFallbackIsNotExportedAsCurrentSessionEvidence() {
        val generation = GpuWitnessEvidence.beginGpuGeneration()
        GpuWitnessEvidence.recordCanvasFallback(generation, "prior", canvasRendered = true)
        val session = GpuValidationSession.start(
            id = 3,
            requestedBackend = VisualEffectsDemoRequestedBackend.GPU_WITNESS,
            selectedOperation = "Full Contour",
            selectedMode = "Baseline",
            activeModifiers = emptyList(),
            capabilityDecision = "Admitted",
            appVersion = "1.2.3",
            deviceModel = "Test Device",
            androidApi = 35,
            generation = generation
        )

        val snapshot = session.snapshot(null, GpuWitnessEvidence.snapshot(generation))

        assertEquals(VisualEffectsDemoActualBackend.AWAITING, snapshot.actualBackend)
        assertEquals(0, snapshot.fallbackCount)
        assertEquals(GpuValidationState.NOT_RUN, snapshot.checks.getValue("Canvas fallback").state)
    }

    private fun session(generation: Long) = GpuValidationSession.start(
        id = 1,
        requestedBackend = VisualEffectsDemoRequestedBackend.GPU_PULSE,
        selectedOperation = "Full Contour",
        selectedMode = "Baseline",
        activeModifiers = emptyList(),
        capabilityDecision = "Admitted",
        appVersion = "1.2.3",
        deviceModel = "Test Device",
        androidApi = 35,
        generation = generation
    )
}
