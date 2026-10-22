package com.yp.luminote.app.debug

import com.yp.luminote.app.rendering.gpu.GpuPulseEvidence
import com.yp.luminote.app.rendering.gpu.GpuPulseDiagnostics
import com.yp.luminote.app.rendering.gpu.GpuPulsePreSubmitFailure
import com.yp.luminote.app.rendering.gpu.GpuWitnessEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class VisualEffectsDemoDiagnosticsAdapterTest {
    @Test fun canvasRequiresSuccessfulFrameAfterPreviewBaseline() {
        fun snapshot(frames: Long, baseline: Long) = VisualEffectsDemoDiagnosticsAdapter.snapshot(
            VisualEffectsDemoRequestedBackend.CANVAS, VisualEffectsDemoFamily.FULL_CONTOUR,
            VisualEffectsDemoMode.BASELINE, null, null, frames, baseline
        )
        assertEquals(VisualEffectsDemoActualBackend.AWAITING, snapshot(0, 0).actualBackend)
        assertEquals(VisualEffectsDemoActualBackend.CANVAS, snapshot(1, 0).actualBackend)
        assertEquals(VisualEffectsDemoActualBackend.AWAITING, snapshot(1, 1).actualBackend)
        assertEquals(VisualEffectsDemoActualBackend.CANVAS, snapshot(2, 1).actualBackend)
    }
    @Test
    fun `GPU witness remains awaiting until its tracked generation submits`() {
        val snapshot = VisualEffectsDemoDiagnosticsAdapter.snapshot(
            requestedBackend = VisualEffectsDemoRequestedBackend.GPU_WITNESS,
            selectedFamily = VisualEffectsDemoFamily.FULL_CONTOUR,
            selectedMode = VisualEffectsDemoMode.BASELINE,
            gpuWitnessGeneration = Long.MIN_VALUE,
            gpuPulseGeneration = null
        )

        assertEquals(VisualEffectsDemoActualBackend.AWAITING, snapshot.actualBackend)
        assertFalse(snapshot.gpuSubmitted)
        assertNull(snapshot.admittedOperation)
    }

    @Test
    fun `Canvas presentation remains explicitly separate from GPU evidence`() {
        val snapshot = VisualEffectsDemoDiagnosticsAdapter.snapshot(
            requestedBackend = VisualEffectsDemoRequestedBackend.CANVAS,
            selectedFamily = VisualEffectsDemoFamily.SUPERNOVA,
            selectedMode = VisualEffectsDemoMode.GLOW,
            gpuWitnessGeneration = null,
            gpuPulseGeneration = null,
            canvasRenderedFrames = 1
        )

        assertEquals(VisualEffectsDemoActualBackend.CANVAS, snapshot.actualBackend)
        assertEquals("Supernova", snapshot.selectedOperation)
        assertEquals(0, snapshot.fallbackCount)
    }

    @Test
    fun `Pulse latest outcome becomes GPU after a fallback in the same generation`() {
        GpuPulseEvidence.resetForTest()
        try {
            val generation = GpuPulseEvidence.beginGeneration()
            GpuPulseEvidence.recordFallback(generation, "unsupported:test configuration")

            val fallbackSnapshot = pulseSnapshot(generation)
            assertEquals(VisualEffectsDemoActualBackend.CANVAS, fallbackSnapshot.actualBackend)
            assertEquals("unsupported:test configuration", fallbackSnapshot.fallbackReason)
            assertEquals(1, fallbackSnapshot.fallbackCount)

            GpuPulseEvidence.recordSubmission(generation, alpha = 179)

            val gpuSnapshot = pulseSnapshot(generation)
            assertEquals(VisualEffectsDemoActualBackend.GPU, gpuSnapshot.actualBackend)
            assertNull(gpuSnapshot.fallbackReason)
            assertEquals(1, gpuSnapshot.fallbackCount)
        } finally {
            GpuPulseEvidence.resetForTest()
        }
    }

    @Test
    fun `Witness latest outcome becomes GPU after a fallback in the same generation`() {
        GpuWitnessEvidence.resetForTest()
        try {
            val generation = GpuWitnessEvidence.beginGpuGeneration()
            GpuWitnessEvidence.recordCanvasFallback(
                generation,
                reason = "unsupported:test configuration",
                canvasRendered = true
            )

            val fallbackSnapshot = witnessSnapshot(generation)
            assertEquals(VisualEffectsDemoActualBackend.CANVAS, fallbackSnapshot.actualBackend)
            assertEquals("unsupported:test configuration", fallbackSnapshot.fallbackReason)
            assertEquals(1, fallbackSnapshot.fallbackCount)

            GpuWitnessEvidence.recordGpuSubmission(generation)

            val gpuSnapshot = witnessSnapshot(generation)
            assertEquals(VisualEffectsDemoActualBackend.GPU, gpuSnapshot.actualBackend)
            assertNull(gpuSnapshot.fallbackReason)
            assertEquals(1, gpuSnapshot.fallbackCount)
        } finally {
            GpuWitnessEvidence.resetForTest()
        }
    }

    @Test
    fun `Pulse exposes structured pre-submit failure evidence without inferring GPU success`() {
        GpuPulseEvidence.resetForTest()
        GpuPulseDiagnostics.resetForTest()
        try {
            val generation = GpuPulseEvidence.beginGeneration()
            GpuPulseDiagnostics.recordFailure(
                generation = generation,
                fieldKey = 42,
                failure = GpuPulsePreSubmitFailure.at(
                    "input-shader-bind",
                    IllegalStateException("field input rejected")
                )
            )
            GpuPulseEvidence.recordFallback(
                generation,
                "pre-submit-error:input-shader-bind:IllegalStateException"
            )

            val snapshot = pulseSnapshot(generation)

            assertEquals(VisualEffectsDemoActualBackend.CANVAS, snapshot.actualBackend)
            assertEquals("input-shader-bind", snapshot.failureStage)
            assertEquals("IllegalStateException", snapshot.failureType)
            assertEquals("field input rejected", snapshot.failureMessage)
            assertEquals("pre-submit-error:input-shader-bind:IllegalStateException", snapshot.fallbackReason)
        } finally {
            GpuPulseEvidence.resetForTest()
            GpuPulseDiagnostics.resetForTest()
        }
    }

    @Test
    fun `Pulse success clears active failure evidence for its generation`() {
        GpuPulseDiagnostics.resetForTest()
        try {
            val generation = 71L
            GpuPulseDiagnostics.recordFailure(
                generation, 1,
                GpuPulsePreSubmitFailure.at("uniform-color", IllegalStateException("bad uniform"))
            )

            // This is the same transition performed after a successful GPU draw submission.
            GpuPulseDiagnostics.clearActiveFailure(generation)

            val diagnostics = GpuPulseDiagnostics.snapshotForGeneration(generation)
            assertNull(diagnostics.failureStage)
            assertNull(diagnostics.failureType)
            assertNull(diagnostics.failureMessage)
        } finally {
            GpuPulseDiagnostics.resetForTest()
        }
    }

    @Test
    fun `old Pulse generation failure is not shown for a new generation`() {
        GpuPulseDiagnostics.resetForTest()
        try {
            GpuPulseDiagnostics.recordFailure(
                generation = 11L,
                fieldKey = 99,
                failure = GpuPulsePreSubmitFailure.at("shader-compile", IllegalStateException("old failure"))
            )

            val diagnostics = GpuPulseDiagnostics.snapshotForGeneration(12L)
            assertNull(diagnostics.failureStage)
            assertNull(diagnostics.failureType)
            assertNull(diagnostics.failureMessage)
        } finally {
            GpuPulseDiagnostics.resetForTest()
        }
    }

    private fun pulseSnapshot(generation: Long) = VisualEffectsDemoDiagnosticsAdapter.snapshot(
        requestedBackend = VisualEffectsDemoRequestedBackend.GPU_PULSE,
        selectedFamily = VisualEffectsDemoFamily.FULL_CONTOUR,
        selectedMode = VisualEffectsDemoMode.BASELINE,
        gpuWitnessGeneration = null,
        gpuPulseGeneration = generation
    )

    private fun witnessSnapshot(generation: Long) = VisualEffectsDemoDiagnosticsAdapter.snapshot(
        requestedBackend = VisualEffectsDemoRequestedBackend.GPU_WITNESS,
        selectedFamily = VisualEffectsDemoFamily.FULL_CONTOUR,
        selectedMode = VisualEffectsDemoMode.BASELINE,
        gpuWitnessGeneration = generation,
        gpuPulseGeneration = null
    )
}
