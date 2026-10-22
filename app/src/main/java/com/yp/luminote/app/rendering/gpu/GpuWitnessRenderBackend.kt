package com.yp.luminote.app.rendering.gpu

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.util.Log
import com.yp.luminote.app.animation.plan.HaloEffectVisualPlanMapper
import com.yp.luminote.app.animation.plan.VisualPlanSpec
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.rendering.api.FrameCommandBuffer
import com.yp.luminote.app.rendering.api.FrameCommandCompiler
import com.yp.luminote.app.rendering.api.HaloRenderBackend
import com.yp.luminote.app.rendering.api.HaloRenderTarget
import com.yp.luminote.app.rendering.api.RenderFrame
import com.yp.luminote.app.rendering.api.RenderOutcome
import com.yp.luminote.app.rendering.canvas.CanvasRenderTarget

/**
 * The permanent second executor boundary for Gate A.  It owns its RuntimeShader and Paint and
 * draws directly to the host's hardware Canvas.  It intentionally has no Canvas effect renderer
 * dependency; an unsupported plan is returned to the host-owned fallback policy.
 */
internal class GpuWitnessRenderBackend(
    private val outline: DisplayOutline,
    private val diagnostics: GpuWitnessDiagnostics = GpuWitnessDiagnostics
) : HaloRenderBackend {
    /** Immutable backend identity; surface epochs can change without changing this selection. */
    private val generation = GpuWitnessEvidence.beginGpuGeneration()
    private val commands = FrameCommandBuffer()
    private var config: HaloConfig? = null
    private var plan: VisualPlanSpec? = null
    private var shader: RuntimeShader? = null
    private var paint: Paint? = null
    private var failure: String? = null
    private var disposed = false
    private var epoch = 0

    internal fun generationForDebugEvidence(): Long = generation

    override fun updateConfig(config: HaloConfig) {
        if (disposed) return
        this.config = config
        plan = HaloEffectVisualPlanMapper.map(config)
    }

    override fun updateDebugVisualPlan(plan: VisualPlanSpec) {
        if (!disposed) this.plan = plan
    }

    override fun invalidateSurface() {
        if (disposed) return
        epoch++
        shader = null
        paint = null
        failure = null
        diagnostics.record("surface-invalidated", "epoch=$epoch")
    }

    override fun render(target: HaloRenderTarget, frame: RenderFrame): RenderOutcome {
        diagnostics.record("render-invoked", "epoch=$epoch intent=${frame.intent}")
        if (disposed) return RenderOutcome.Unsupported("GPU witness backend is disposed")
        val canvas = (target as? CanvasRenderTarget)?.canvas
            ?: return RenderOutcome.Unsupported("GPU witness requires a CanvasRenderTarget")
        if (!canvas.isHardwareAccelerated) {
            return RenderOutcome.Unsupported("GPU witness requires a hardware accelerated Canvas")
        }
        if (frame.viewportWidth <= 0 || frame.viewportHeight <= 0) {
            return RenderOutcome.Unsupported("GPU witness requires positive viewport dimensions")
        }
        val resolvedPlan = plan ?: config?.let(HaloEffectVisualPlanMapper::map)?.also { plan = it }
            ?: return RenderOutcome.Unsupported("GPU witness has no visual plan")
        FrameCommandCompiler.compile(resolvedPlan, frame, commands)
        var hasModifiers = false
        var index = 0
        while (index < commands.size) {
            if (commands[index].modifiers.isNotEmpty()) hasModifiers = true
            index++
        }
        GpuWitnessCapability.unsupportedReason(frame.intent, frame.mode, commands.size, hasModifiers)?.let {
            diagnostics.record("unsupported", it)
            return RenderOutcome.Unsupported(it)
        }

        // The command is observed for evidence only. Gate A does not claim to execute this
        // operation; the explicit DEBUG_GPU_WITNESS intent admits the shader witness itself.
        diagnostics.recordRequestedOperation(commands[0].operation)

        GpuWitnessEvidence.recordRenderAttempt(generation)
        val witnessPaint = initialize() ?: return RenderOutcome.Error(
            IllegalStateException(failure ?: "GPU witness shader initialization failed")
        )
        var saveCount = -1
        try {
            // This pre-submission setup does not write pixels and can safely Canvas-fallback.
            saveCount = canvas.save()
            canvas.clipPath(outline.path)
            shader!!.setFloatUniform("resolution", frame.viewportWidth.toFloat(), frame.viewportHeight.toFloat())
            shader!!.setFloatUniform("timeSeconds", frame.frameTimeNanos / 1_000_000_000f)
        } catch (error: RuntimeException) {
            if (saveCount >= 0) runCatching { canvas.restoreToCount(saveCount) }
            diagnostics.record("setup-failed", error.javaClass.simpleName)
            return RenderOutcome.Error(error, fallbackEligible = true)
        }
        val outcome = try {
            // This is the first GPU submission. Any failure afterwards is intentionally not
            // composited with Canvas because the target may contain partial shader pixels.
            canvas.drawRect(0f, 0f, frame.viewportWidth.toFloat(), frame.viewportHeight.toFloat(), witnessPaint)
            diagnostics.record("submitted", "epoch=$epoch commands=${commands.size}")
            diagnostics.record("actual-backend", "GPU")
            GpuWitnessEvidence.recordGpuSubmission(generation)
            RenderOutcome.Rendered
        } catch (error: RuntimeException) {
            diagnostics.record("submission-failed", error.javaClass.simpleName)
            RenderOutcome.Error(error, fallbackEligible = false)
        }
        return try {
            canvas.restoreToCount(saveCount)
            outcome
        } catch (error: RuntimeException) {
            diagnostics.record("restore-failed", error.javaClass.simpleName)
            RenderOutcome.Error(error, fallbackEligible = false)
        }
    }

    private fun initialize(): Paint? {
        failure?.let {
            diagnostics.record("initialization-latched", it)
            return null
        }
        paint?.let { return it }
        return try {
            diagnostics.record("shader-initializing", "epoch=$epoch")
            GpuWitnessEvidence.recordInitializationAttempt(generation)
            val compiledShader = RuntimeShader(WITNESS_SHADER)
            // RuntimeShader construction is Android's compile boundary; no separate compile API exists.
            diagnostics.record("shader-compiled", "epoch=$epoch")
            val compiledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = compiledShader }
            shader = compiledShader
            paint = compiledPaint
            GpuWitnessEvidence.recordShaderInitialization(generation)
            diagnostics.record("shader-attached", "epoch=$epoch")
            compiledPaint
        } catch (error: RuntimeException) {
            failure = "shader-initialization-or-compilation:${error.javaClass.simpleName}"
            diagnostics.record("shader-init-or-compile-failed", failure!!)
            null
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        GpuWitnessEvidence.recordGpuDisposal(generation)
        shader = null
        paint = null
        plan = null
        diagnostics.record("disposed", "epoch=$epoch")
    }

    private companion object {
        // AGSL returns premultiplied alpha. Odd bands contribute straight RGB (.95, .08, .72)
        // at alpha .82; even bands have alpha zero, so their nominal cyan RGB is transparent
        // and cannot appear in the framebuffer. The checker is intentionally unlike every
        // production effect while exercising source-alpha composition.
        const val WITNESS_SHADER = """
            uniform float2 resolution;
            uniform float timeSeconds;
            half4 main(float2 p) {
                float2 uv = p / resolution;
                float band = floor((uv.x + uv.y + timeSeconds * 0.20) * 12.0);
                float on = mod(band, 2.0);
                float alpha = mix(0.0, 0.82, on);
                float3 rgb = mix(float3(0.05, 0.95, 0.95), float3(0.95, 0.08, 0.72), on);
                return half4(half3(rgb * alpha), half(alpha));
            }
        """
    }
}

/** Debug-only call sites create this backend; events are transition-deduplicated to avoid frame spam. */
internal object GpuWitnessDiagnostics {
    private val lastByEvent = mutableMapOf<String, String>()
    private var lastOperation: Any? = null

    /** Read-only DEBUG presentation bridge; it does not participate in render decisions. */
    data class Snapshot(
        val requestedOperation: String?,
        val admittedOperation: String?,
        val fallbackReason: String?,
        val actualBackend: String?
    )

    @Synchronized
    fun record(event: String, detail: String) {
        if (lastByEvent[event] == detail) return
        lastByEvent[event] = detail
        Log.d("HaloGpuWitness", "GPU witness $event: $detail")
    }

    @Synchronized
    fun recordRequestedOperation(operation: Any) {
        if (operation == lastOperation) return
        lastOperation = operation
        Log.d("HaloGpuWitness", "GPU witness requested-operation: $operation")
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        requestedOperation = lastOperation?.toString(),
        admittedOperation = lastByEvent["admitted-operation"],
        fallbackReason = lastByEvent["canvas-fallback"],
        actualBackend = lastByEvent["actual-backend"]
    )
}

/** Resettable debug/device-test evidence; logs remain the operator-facing diagnostics. */
internal object GpuWitnessEvidence {
    private val lock = Object()
    private var nextGeneration = 0L
    private val states = mutableMapOf<Long, MutableState>()

    private data class MutableState(
        var requestedGpu: Boolean = true,
        var actualGpu: Boolean = false,
        var canvasFallbackCount: Int = 0,
        var shaderInitializationCount: Int = 0,
        var initializationAttempts: Int = 0,
        var renderAttempts: Int = 0,
        var submissionCount: Int = 0,
        var gpuDisposalCount: Int = 0,
        var latestActualBackend: String? = null,
        var latestFallbackReason: String? = null,
        var mostRecentFallbackReason: String? = null
    )

    data class Snapshot(
        val generation: Long,
        val requestedGpu: Boolean,
        val actualGpu: Boolean,
        val canvasFallbackCount: Int,
        val shaderInitializationCount: Int,
        val submissionCount: Int,
        val gpuDisposalCount: Int,
        /** Latest outcome for this generation; counters above remain historical evidence. */
        val latestActualBackend: String?,
        val latestFallbackReason: String?,
        val mostRecentFallbackReason: String?,
        val initializationAttempts: Int = 0,
        val renderAttempts: Int = 0
    )

    fun resetForTest() = synchronized(lock) {
        states.clear()
    }

    fun beginGpuGeneration(): Long = synchronized(lock) {
        val generation = ++nextGeneration
        states[generation] = MutableState()
        if (states.size > 32) states.remove(states.keys.minOrNull())
        lock.notifyAll()
        generation
    }

    fun latestGeneration(): Long? = synchronized(lock) { states.keys.maxOrNull() }
    fun recordShaderInitialization(generation: Long) = synchronized(lock) { stateFor(generation).shaderInitializationCount++ }
    fun recordInitializationAttempt(generation: Long) = synchronized(lock) { stateFor(generation).initializationAttempts++ }
    fun recordRenderAttempt(generation: Long) = synchronized(lock) { stateFor(generation).renderAttempts++ }
    fun recordGpuSubmission(generation: Long) = synchronized(lock) {
        val state = stateFor(generation)
        state.actualGpu = true
        state.submissionCount++
        state.latestActualBackend = "GPU"
        state.latestFallbackReason = null
        lock.notifyAll()
    }
    fun recordCanvasFallback(generation: Long, reason: String, canvasRendered: Boolean) = synchronized(lock) {
        stateFor(generation).apply {
            canvasFallbackCount++
            latestActualBackend = if (canvasRendered) "Canvas" else null
            latestFallbackReason = reason
            mostRecentFallbackReason = reason
        }
        Unit
    }
    fun recordGpuDisposal(generation: Long) = synchronized(lock) {
        stateFor(generation).gpuDisposalCount++
        lock.notifyAll()
    }
    fun snapshot(generation: Long): Snapshot? = synchronized(lock) {
        states[generation]?.let { snapshotLocked(generation, it) }
    }

    /** Waits for exact backend identity, never a counter from an older host. */
    fun awaitGenerationAfter(previousGeneration: Long, timeoutMillis: Long): Snapshot? = synchronized(lock) {
        val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L
        while ((states.keys.maxOrNull() ?: previousGeneration) <= previousGeneration) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) return@synchronized null
            lock.wait(remainingNanos / 1_000_000L, (remainingNanos % 1_000_000L).toInt())
        }
        val generation = states.keys.maxOrNull()!!
        snapshotLocked(generation, states.getValue(generation))
    }

    /** Waits for the specified generation's shader initialization and its submitted GPU frame. */
    fun awaitInitializedAndSubmitted(generation: Long, timeoutMillis: Long): Snapshot? = synchronized(lock) {
        val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            val state = states[generation] ?: return@synchronized null
            if (state.shaderInitializationCount > 0 && state.submissionCount > 0) {
                return@synchronized snapshotLocked(generation, state)
            }
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) return@synchronized null
            lock.wait(remainingNanos / 1_000_000L, (remainingNanos % 1_000_000L).toInt())
        }
        error("unreachable")
    }

    fun awaitDisposal(generation: Long, timeoutMillis: Long): Snapshot? = synchronized(lock) {
        val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            val state = states[generation] ?: return@synchronized null
            if (state.gpuDisposalCount > 0) return@synchronized snapshotLocked(generation, state)
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) return@synchronized null
            lock.wait(remainingNanos / 1_000_000L, (remainingNanos % 1_000_000L).toInt())
        }
        error("unreachable")
    }

    private fun stateFor(generation: Long): MutableState = states.getValue(generation)
    private fun snapshotLocked(generation: Long, state: MutableState): Snapshot = Snapshot(
        generation,
        state.requestedGpu,
        state.actualGpu,
        state.canvasFallbackCount,
        state.shaderInitializationCount,
        state.submissionCount,
        state.gpuDisposalCount,
        state.latestActualBackend,
        state.latestFallbackReason,
        state.mostRecentFallbackReason,
        state.initializationAttempts,
        state.renderAttempts
    )
}
