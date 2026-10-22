package com.yp.luminote.app.debug

import com.yp.luminote.app.rendering.gpu.GpuPulseDiagnostics
import com.yp.luminote.app.rendering.gpu.GpuPulseEvidence
import com.yp.luminote.app.rendering.gpu.GpuWitnessEvidence

/** The backend requested by the DEBUG Lab, separate from an observed rendering result. */
internal enum class VisualEffectsDemoRequestedBackend {
    CANVAS,
    GPU_WITNESS,
    GPU_PULSE,
    GPU_SNAKE,
    GPU_IMPULSE,
    GPU_SUPERNOVA
}

/** An observed result only; GPU is never inferred from a DEBUG selection. */
internal enum class VisualEffectsDemoActualBackend {
    AWAITING,
    CANVAS,
    GPU
}

/**
 * Immutable, DEBUG-only presentation state assembled from existing Gate A/B evidence.
 * This adapter is deliberately observational: it does not update a renderer, capability, or plan.
 */
internal data class VisualEffectsDemoDiagnosticsSnapshot(
    val requestedBackend: VisualEffectsDemoRequestedBackend,
    val actualBackend: VisualEffectsDemoActualBackend,
    val gpuInitialized: Boolean,
    val gpuSubmitted: Boolean,
    val selectedOperation: String,
    val admittedOperation: String?,
    val fallbackReason: String?,
    /** DEBUG-only structured evidence; full stack traces remain in HaloGpuWitness Logcat. */
    val failureStage: String?,
    val failureType: String?,
    val failureMessage: String?,
    val fallbackCount: Int,
    val capabilitySummary: String,
    val generation: Long? = null,
    val shaderInitialized: Boolean = false,
    val gpuAttempts: Int = 0,
    val gpuSubmittedFrames: Int = 0,
    val initializationAttempts: Int = 0,
    val pixelVerification: GpuValidationState = GpuValidationState.NOT_RUN
)

internal object VisualEffectsDemoDiagnosticsAdapter {
    fun snapshot(
        requestedBackend: VisualEffectsDemoRequestedBackend,
        selectedFamily: VisualEffectsDemoFamily,
        selectedMode: VisualEffectsDemoMode,
        gpuWitnessGeneration: Long?,
        gpuPulseGeneration: Long?,
        canvasRenderedFrames: Long = 0L,
        canvasPreviewBaseline: Long = 0L
    ): VisualEffectsDemoDiagnosticsSnapshot = when (requestedBackend) {
        VisualEffectsDemoRequestedBackend.CANVAS -> VisualEffectsDemoDiagnosticsSnapshot(
            requestedBackend = requestedBackend,
            actualBackend = if (canvasRenderedFrames > canvasPreviewBaseline) VisualEffectsDemoActualBackend.CANVAS else VisualEffectsDemoActualBackend.AWAITING,
            gpuInitialized = false,
            gpuSubmitted = false,
            selectedOperation = selectedFamily.label,
            admittedOperation = selectedFamily.label,
            fallbackReason = null,
            failureStage = null,
            failureType = null,
            failureMessage = null,
            fallbackCount = 0,
            capabilitySummary = "Canvas supports the selected demo plan."
        )

        VisualEffectsDemoRequestedBackend.GPU_WITNESS -> witnessSnapshot(
            selectedFamily = selectedFamily,
            selectedMode = selectedMode,
            generation = gpuWitnessGeneration
        )

        VisualEffectsDemoRequestedBackend.GPU_PULSE -> pulseSnapshot(
            selectedFamily = selectedFamily,
            selectedMode = selectedMode,
            generation = gpuPulseGeneration
        )
        VisualEffectsDemoRequestedBackend.GPU_SNAKE -> pulseSnapshot(
            selectedFamily,selectedMode,gpuPulseGeneration,snake = true
        )
        VisualEffectsDemoRequestedBackend.GPU_SUPERNOVA -> pulseSnapshot(
            selectedFamily, selectedMode, gpuPulseGeneration, supernova = true
        )
        VisualEffectsDemoRequestedBackend.GPU_IMPULSE -> pulseSnapshot(
            selectedFamily, selectedMode, gpuPulseGeneration, impulse = true
        )
    }

    private fun witnessSnapshot(
        selectedFamily: VisualEffectsDemoFamily,
        selectedMode: VisualEffectsDemoMode,
        generation: Long?
    ): VisualEffectsDemoDiagnosticsSnapshot {
        val evidence = generation?.let(GpuWitnessEvidence::snapshot)
        val submitted = (evidence?.submissionCount ?: 0) > 0
        val fallbacks = evidence?.canvasFallbackCount ?: 0
        return VisualEffectsDemoDiagnosticsSnapshot(
            requestedBackend = VisualEffectsDemoRequestedBackend.GPU_WITNESS,
            actualBackend = actualBackend(evidence?.latestActualBackend),
            gpuInitialized = (evidence?.shaderInitializationCount ?: 0) > 0,
            gpuSubmitted = submitted,
            selectedOperation = "${selectedFamily.label} / ${selectedMode.label}",
            admittedOperation = if (submitted) "GPU RuntimeShader witness" else null,
            fallbackReason = evidence?.latestFallbackReason,
            failureStage = null,
            failureType = null,
            failureMessage = null,
            fallbackCount = fallbacks,
            capabilitySummary = "DEBUG witness; one baseline command, no modifiers.",
            generation = generation,
            shaderInitialized = (evidence?.shaderInitializationCount ?: 0) > 0,
            gpuAttempts = evidence?.renderAttempts ?: 0,
            initializationAttempts = evidence?.initializationAttempts ?: 0,
            gpuSubmittedFrames = evidence?.submissionCount ?: 0
        )
    }

    private fun pulseSnapshot(
        selectedFamily: VisualEffectsDemoFamily,
        selectedMode: VisualEffectsDemoMode,
        generation: Long?,
        snake: Boolean = false,
        impulse: Boolean = false,
        supernova: Boolean = false
    ): VisualEffectsDemoDiagnosticsSnapshot {
        val evidence = generation?.let(GpuPulseEvidence::snapshot)
        val diagnostics = GpuPulseDiagnostics.snapshotForGeneration(generation)
        val submitted = (evidence?.submissions ?: 0) > 0
        val fallbacks = evidence?.fallbacks ?: 0
        return VisualEffectsDemoDiagnosticsSnapshot(
            requestedBackend = if (supernova) VisualEffectsDemoRequestedBackend.GPU_SUPERNOVA else if (impulse) VisualEffectsDemoRequestedBackend.GPU_IMPULSE else if (snake) VisualEffectsDemoRequestedBackend.GPU_SNAKE else VisualEffectsDemoRequestedBackend.GPU_PULSE,
            actualBackend = actualBackend(evidence?.latestActualBackend),
            gpuInitialized = (evidence?.shaderInitializations ?: 0) > 0,
            gpuSubmitted = submitted,
            selectedOperation = "${selectedFamily.label} / ${selectedMode.label}",
            admittedOperation = if (supernova && submitted) "Supernova" else if (impulse && submitted) "LightImpulse / NORMAL_IMPULSE" else if (snake && submitted) "TravelingLuminousSegment / FLOWING_SNAKE" else diagnostics.admittedOperation.takeIf { submitted },
            fallbackReason = evidence?.latestFallbackReason,
            failureStage = if (snake || impulse || supernova) evidence?.firstFailure?.stage.takeIf { evidence?.latestActualBackend != "GPU" } else diagnostics.failureStage,
            failureType = if (snake || impulse || supernova) evidence?.firstFailure?.type.takeIf { evidence?.latestActualBackend != "GPU" } else diagnostics.failureType,
            failureMessage = if (snake || impulse || supernova) evidence?.firstFailure?.message.takeIf { evidence?.latestActualBackend != "GPU" } else diagnostics.failureMessage,
            fallbackCount = fallbacks,
            generation = generation,
            shaderInitialized = (evidence?.shaderInitializations ?: 0) > 0,
            gpuAttempts = evidence?.preSubmitAttempts ?: 0,
            initializationAttempts = evidence?.shaderInitializationAttempts ?: 0,
            gpuSubmittedFrames = evidence?.submissions ?: 0,
            capabilitySummary = if (supernova) {
                "DEBUG GPU Supernova; Baseline or Production Supernova Stack only."
            } else if (impulse) {
                "DEBUG GPU Impulse; normal Impulse Baseline only, no optical modifiers."
            } else if (snake) {
                "DEBUG GPU Snake; flowing source, Baseline or Production Snake Stack only."
            } else if (selectedFamily == VisualEffectsDemoFamily.FULL_CONTOUR && selectedMode == VisualEffectsDemoMode.BASELINE) {
                "DEBUG GPU Pulse; Full Contour baseline only."
            } else {
                "Unavailable: GPU Pulse requires Full Contour baseline."
            }
        )
    }

    private fun actualBackend(
        submitted: Boolean,
        fallbackCount: Int,
        recordedActualBackend: String?
    ): VisualEffectsDemoActualBackend = when {
        fallbackCount > 0 && recordedActualBackend == "Canvas" -> VisualEffectsDemoActualBackend.CANVAS
        submitted -> VisualEffectsDemoActualBackend.GPU
        else -> VisualEffectsDemoActualBackend.AWAITING
    }

    private fun actualBackend(recordedActualBackend: String?): VisualEffectsDemoActualBackend = when (recordedActualBackend) {
        "Canvas" -> VisualEffectsDemoActualBackend.CANVAS
        "GPU" -> VisualEffectsDemoActualBackend.GPU
        else -> VisualEffectsDemoActualBackend.AWAITING
    }
}
