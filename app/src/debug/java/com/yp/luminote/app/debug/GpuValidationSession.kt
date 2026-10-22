package com.yp.luminote.app.debug

import com.yp.luminote.app.rendering.gpu.GpuPulseEvidence
import com.yp.luminote.app.rendering.gpu.GpuWitnessEvidence

/** Explicit outcome; a selection or a compiled shader never implies [PASS]. */
internal enum class GpuValidationState { PASS, FAIL, NOT_TESTABLE, NOT_RUN }
internal enum class GpuValidationPhase { NOT_RUN, RUNNING, COMPLETED, CANCELLED }

internal data class GpuValidationCheck(
    val state: GpuValidationState,
    val detail: String
)

/**
 * Immutable DEBUG-only data which presentation may render or copy, but may never mutate.
 * Pixel checks are deliberately NOT_TESTABLE: the Lab controls overlay the host and no
 * renderer-owned unobscured capture region exists in this architecture.
 */
internal data class GpuValidationSessionSnapshot(
    val sessionId: Long,
    val active: Boolean,
    val phase: GpuValidationPhase,
    val requestedBackend: VisualEffectsDemoRequestedBackend,
    val actualBackend: VisualEffectsDemoActualBackend,
    val selectedOperation: String,
    val selectedMode: String,
    val activeModifiers: List<String>,
    val capabilityDecision: String,
    val appVersion: String,
    val deviceModel: String,
    val androidApi: Int,
    val generation: Long?,
    val fieldKey: Int?,
    val surfaceEpoch: Int?,
    val gpuAttempts: Int,
    val gpuSubmittedFrames: Int,
    val initializationAttempts: Int,
    val latchedFailureFrames: Int,
    val canvasAfterLatchFrames: Int,
    val fallbackCount: Int,
    val fallbackReason: String?,
    val firstFailure: GpuPulseEvidence.FailureDetails?,
    val checks: Map<String, GpuValidationCheck>
)

/**
 * Renderer-evidence session. It has no renderer resources and no async callbacks: callers start
 * it immediately before the real preview restart, then poll an immutable snapshot on UI refresh.
 * A replacement/cancel makes subsequent observations inert, preventing stale UI updates.
 */
internal class GpuValidationSession private constructor(
    private val id: Long,
    private val requestedBackend: VisualEffectsDemoRequestedBackend,
    private val selectedOperation: String,
    private val selectedMode: String,
    private val activeModifiers: List<String>,
    private val capabilityDecision: String,
    private val appVersion: String,
    private val deviceModel: String,
    private val androidApi: Int,
    private val generation: Long?,
    private val pulseBaseline: GpuPulseEvidence.Snapshot?,
    private val witnessBaseline: GpuWitnessEvidence.Snapshot?
) {
    private var active = true
    private var phase = if (id == 0L) GpuValidationPhase.NOT_RUN else GpuValidationPhase.RUNNING
    // Evidence is valid only when it arrives after this session's baseline. `firstFailure`
    // is generation-lifetime evidence, so seeding this from the baseline would falsely make a
    // prior attempt appear as this self-test's failure.
    private var capturedFailure: GpuPulseEvidence.FailureDetails? = null

    fun cancel() {
        active = false
        if (phase == GpuValidationPhase.RUNNING) phase = GpuValidationPhase.CANCELLED
    }
    fun complete() { active = false; phase = GpuValidationPhase.COMPLETED }

    fun snapshot(
        currentPulse: GpuPulseEvidence.Snapshot?,
        currentWitness: GpuWitnessEvidence.Snapshot?
    ): GpuValidationSessionSnapshot {
        val observation = observe(currentPulse, currentWitness)
        if (active && observation.failure != null && capturedFailure == null) {
            capturedFailure = observation.failure
        }
        val failure = capturedFailure
        val submitted = observation.submissions > 0
        val fallback = observation.fallbacks > 0
        val actualBackend = if (!submitted && !fallback) {
            VisualEffectsDemoActualBackend.AWAITING
        } else when (observation.latestActualBackend) {
            "GPU" -> VisualEffectsDemoActualBackend.GPU
            "Canvas" -> VisualEffectsDemoActualBackend.CANVAS
            else -> VisualEffectsDemoActualBackend.AWAITING
        }
        val gpuActual = submitted && actualBackend == VisualEffectsDemoActualBackend.GPU
        val checks = linkedMapOf(
            "GPU initialization" to initializationCheck(observation, failure),
            "Shader compilation" to shaderCheck(observation, failure),
            "Frame submission" to submissionCheck(submitted, failure),
            "Actual backend" to actualBackendCheck(gpuActual, fallback, failure),
            "Pixel verification" to unavailableCaptureCheck(),
            "Alpha/transparency" to unavailableCaptureCheck(),
            "Clipping" to unavailableCaptureCheck(),
            "Resource recreation" to recreationCheck(submitted),
            "Canvas fallback" to fallbackCheck(fallback, submitted, failure)
        )
        return GpuValidationSessionSnapshot(
            sessionId = id,
            active = active,
            phase = phase,
            requestedBackend = requestedBackend,
            actualBackend = actualBackend,
            selectedOperation = selectedOperation,
            selectedMode = selectedMode,
            activeModifiers = activeModifiers,
            capabilityDecision = capabilityDecision,
            appVersion = appVersion,
            deviceModel = deviceModel,
            androidApi = androidApi,
            generation = generation,
            fieldKey = observation.fieldKey,
            surfaceEpoch = observation.surfaceEpoch,
            gpuAttempts = observation.preSubmitAttempts,
            gpuSubmittedFrames = observation.submissions,
            initializationAttempts = when (requestedBackend) {
                VisualEffectsDemoRequestedBackend.GPU_PULSE, VisualEffectsDemoRequestedBackend.GPU_SNAKE, VisualEffectsDemoRequestedBackend.GPU_IMPULSE, VisualEffectsDemoRequestedBackend.GPU_SUPERNOVA -> (currentPulse?.takeIf { it.generation == generation }?.shaderInitializationAttempts ?: 0) - (pulseBaseline?.shaderInitializationAttempts ?: 0)
                VisualEffectsDemoRequestedBackend.GPU_WITNESS -> (currentWitness?.initializationAttempts ?: 0) - (witnessBaseline?.initializationAttempts ?: 0)
                else -> 0
            },
            latchedFailureFrames = observation.latchedFailures,
            canvasAfterLatchFrames = observation.canvasAfterLatchFallbacks,
            fallbackCount = observation.fallbacks,
            fallbackReason = if (fallback) observation.mostRecentFallbackReason else null,
            firstFailure = failure,
            checks = checks
        )
    }

    private fun initializationCheck(observation: Observation, failure: GpuPulseEvidence.FailureDetails?) = when {
        observation.initializations > 0 -> GpuValidationCheck(GpuValidationState.PASS, "GPU resource initialized after self-test started.")
        failure != null -> GpuValidationCheck(GpuValidationState.FAIL, "Failed before a new field initialization: ${failure.stage}.")
        else -> GpuValidationCheck(GpuValidationState.NOT_RUN, "No new GPU resource initialization observed in this session.")
    }

    private fun shaderCheck(observation: Observation, failure: GpuPulseEvidence.FailureDetails?) = when {
        observation.shaderInitializations > 0 -> GpuValidationCheck(GpuValidationState.PASS, "RuntimeShader initialized after self-test started.")
        failure?.stage == "shader-compile" -> GpuValidationCheck(GpuValidationState.FAIL, "${failure.type}: ${failure.message.orEmpty()}")
        failure != null -> GpuValidationCheck(GpuValidationState.NOT_RUN, "No shader compilation attempt was observed before ${failure.stage}.")
        else -> GpuValidationCheck(GpuValidationState.NOT_RUN, "No new RuntimeShader initialization observed in this session.")
    }

    private fun submissionCheck(submitted: Boolean, failure: GpuPulseEvidence.FailureDetails?) = when {
        submitted -> GpuValidationCheck(GpuValidationState.PASS, "A GPU frame submission was observed in this session.")
        failure != null -> GpuValidationCheck(GpuValidationState.FAIL, "${failure.stage}: ${failure.type}")
        else -> GpuValidationCheck(GpuValidationState.NOT_RUN, "Waiting for a renderer-owned submission event.")
    }

    private fun actualBackendCheck(gpuActual: Boolean, fallback: Boolean, failure: GpuPulseEvidence.FailureDetails?) = when {
        gpuActual -> GpuValidationCheck(GpuValidationState.PASS, "Observed backend is GPU after a current-session submission.")
        fallback -> GpuValidationCheck(GpuValidationState.FAIL, "Canvas fallback observed${failure?.let { ": ${it.classification}" }.orEmpty()}.")
        else -> GpuValidationCheck(GpuValidationState.NOT_RUN, "No current-session backend result observed.")
    }

    private fun unavailableCaptureCheck() = GpuValidationCheck(
        GpuValidationState.NOT_TESTABLE,
        "No renderer-owned unobscured framebuffer capture region is available in the DEBUG Lab."
    )

    private fun recreationCheck(submitted: Boolean) = GpuValidationCheck(
        GpuValidationState.NOT_TESTABLE,
        "Activity/window recreation requires a new renderer generation and is not driven by this in-app session."
    )

    private fun fallbackCheck(fallback: Boolean, submitted: Boolean, failure: GpuPulseEvidence.FailureDetails?) = when {
        !fallback && (failure != null) -> GpuValidationCheck(GpuValidationState.NOT_RUN, "Failure captured before a Canvas fallback result.")
        !fallback && submitted -> GpuValidationCheck(GpuValidationState.PASS, "No Canvas fallback observed after a submitted GPU frame.")
        !fallback -> GpuValidationCheck(GpuValidationState.NOT_RUN, "No renderer result observed in this session.")
        else -> GpuValidationCheck(GpuValidationState.FAIL, "Canvas rendered after GPU failure${failure?.let { ": ${it.classification}" }.orEmpty()}.")
    }

    private data class Observation(
        val initializations: Int,
        val shaderInitializations: Int,
        val preSubmitAttempts: Int,
        val latchedFailures: Int,
        val canvasAfterLatchFallbacks: Int,
        val submissions: Int,
        val fallbacks: Int,
        val fieldDisposals: Int,
        val latestActualBackend: String?,
        val mostRecentFallbackReason: String?,
        val fieldKey: Int?,
        val surfaceEpoch: Int?,
        val failure: GpuPulseEvidence.FailureDetails?
    )

    private fun observe(pulse: GpuPulseEvidence.Snapshot?, witness: GpuWitnessEvidence.Snapshot?): Observation = when (requestedBackend) {
        VisualEffectsDemoRequestedBackend.GPU_PULSE, VisualEffectsDemoRequestedBackend.GPU_SNAKE, VisualEffectsDemoRequestedBackend.GPU_IMPULSE, VisualEffectsDemoRequestedBackend.GPU_SUPERNOVA -> pulse?.takeIf { it.generation == generation }
            ?.minus(pulseBaseline) ?: emptyObservation()
        VisualEffectsDemoRequestedBackend.GPU_WITNESS -> witness?.takeIf { it.generation == generation }
            ?.minus(witnessBaseline) ?: emptyObservation()
        VisualEffectsDemoRequestedBackend.CANVAS -> emptyObservation()
    }

    private fun GpuPulseEvidence.Snapshot.minus(before: GpuPulseEvidence.Snapshot?): Observation = Observation(
        resourceInitializations - (before?.resourceInitializations ?: 0),
        shaderInitializations - (before?.shaderInitializations ?: 0),
        preSubmitAttempts - (before?.preSubmitAttempts ?: 0),
        latchedFailures - (before?.latchedFailures ?: 0),
        canvasAfterLatchFallbacks - (before?.canvasAfterLatchFallbacks ?: 0),
        submissions - (before?.submissions ?: 0),
        fallbacks - (before?.fallbacks ?: 0),
        fieldDisposals - (before?.fieldDisposals ?: 0),
        latestActualBackend,
        mostRecentFallbackReason,
        fieldKey,
        surfaceEpoch,
        firstFailure.takeIf { before?.firstFailure == null }
    )

    private fun GpuWitnessEvidence.Snapshot.minus(before: GpuWitnessEvidence.Snapshot?): Observation = Observation(
        shaderInitializationCount - (before?.shaderInitializationCount ?: 0),
        shaderInitializationCount - (before?.shaderInitializationCount ?: 0),
        renderAttempts - (before?.renderAttempts ?: 0),
        0,
        0,
        submissionCount - (before?.submissionCount ?: 0),
        canvasFallbackCount - (before?.canvasFallbackCount ?: 0),
        0,
        latestActualBackend,
        mostRecentFallbackReason,
        null,
        null,
        null
    )

    private fun emptyObservation() = Observation(0, 0, 0, 0, 0, 0, 0, 0, null, null, null, null, null)

    companion object {
        fun start(
            id: Long,
            requestedBackend: VisualEffectsDemoRequestedBackend,
            selectedOperation: String,
            selectedMode: String,
            activeModifiers: List<String>,
            capabilityDecision: String,
            appVersion: String,
            deviceModel: String,
            androidApi: Int,
            generation: Long?
        ) = GpuValidationSession(
            id, requestedBackend, selectedOperation, selectedMode, activeModifiers, capabilityDecision,
            appVersion, deviceModel, androidApi, generation,
            generation?.takeIf { requestedBackend == VisualEffectsDemoRequestedBackend.GPU_PULSE || requestedBackend == VisualEffectsDemoRequestedBackend.GPU_SNAKE || requestedBackend == VisualEffectsDemoRequestedBackend.GPU_IMPULSE || requestedBackend == VisualEffectsDemoRequestedBackend.GPU_SUPERNOVA }
                ?.let(GpuPulseEvidence::snapshot),
            generation?.takeIf { requestedBackend == VisualEffectsDemoRequestedBackend.GPU_WITNESS }
                ?.let(GpuWitnessEvidence::snapshot)
        )
    }
}

/** Kept free of device/user data; UI owns the actual clipboard interaction. */
internal fun GpuValidationSessionSnapshot.toDiagnosticReport(): String = buildString {
    appendLine("Luminote DEBUG GPU validation")
    appendLine("session=$sessionId active=$active phase=$phase")
    appendLine("appVersion=$appVersion deviceModel=$deviceModel androidApi=$androidApi")
    appendLine("requested=$requestedBackend actual=$actualBackend operation=$selectedOperation mode=$selectedMode")
    appendLine("activeModifiers=${activeModifiers.joinToString().ifEmpty { "none" }}")
    appendLine("capability=$capabilityDecision")
    appendLine("generation=${generation ?: "none"} field=${fieldKey ?: "unknown"} surfaceEpoch=${surfaceEpoch ?: "unknown"}")
    appendLine("gpuRenderAttempts=$gpuAttempts initializationAttempts=$initializationAttempts gpuSubmittedFrames=$gpuSubmittedFrames latchedFailureFrames=$latchedFailureFrames canvasAfterLatchFrames=$canvasAfterLatchFrames")
    appendLine("fallbackCount=$fallbackCount fallbackReason=${fallbackReason.orEmpty()}")
    firstFailure?.let {
        appendLine("firstFailure=${it.classification} stage=${it.stage} type=${it.type} message=${it.message.orEmpty()}")
        it.stack.forEach { frame -> appendLine("  at $frame") }
    }
    checks.forEach { (name, check) -> appendLine("$name=${check.state}: ${check.detail}") }
}
