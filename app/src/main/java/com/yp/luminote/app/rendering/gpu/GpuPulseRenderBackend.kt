package com.yp.luminote.app.rendering.gpu

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.Log
import com.yp.luminote.app.animation.plan.GeometryRequest
import com.yp.luminote.app.animation.plan.HaloEffectVisualPlanMapper
import com.yp.luminote.app.animation.plan.VisualPlanSpec
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.geometry.EdgePathGeometryProvider
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.rendering.api.FrameCommandBuffer
import com.yp.luminote.app.rendering.api.FrameCommandCompiler
import com.yp.luminote.app.rendering.api.FrameVisualCommand
import com.yp.luminote.app.rendering.api.HaloRenderBackend
import com.yp.luminote.app.rendering.api.HaloRenderTarget
import com.yp.luminote.app.rendering.api.RenderFrame
import com.yp.luminote.app.rendering.api.RenderOutcome
import com.yp.luminote.app.rendering.canvas.CanvasRenderTarget
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Gate B executor. A bounded, unsigned centreline distance field is generated from the exact
 * [EdgePathGeometryProvider] snapshot and sampled by RuntimeShader for a single full-viewport
 * draw. It never calls the Canvas Pulse renderer. The field is 1:1 overlay pixels (texel centres
 * map to `x+.5,y+.5`), uses CLAMP filtering, encodes unsigned distance in red/maxDistance, and
 * clamps farther samples to maxDistance. The field is built from a 0.125px-error path polyline;
 * its bounded device-pixel error is <= 0.125 (polyline) + .707 (texel centre) + .095 (8-bit
 * quantization) = 0.927px, below one quarter of the minimum 4px Pulse stroke. All parameters
 * that affect those pixels are in [FieldKey].
 */
internal class GpuPulseRenderBackend(
    private val outline: DisplayOutline,
    private val diagnostics: GpuPulseDiagnostics = GpuPulseDiagnostics
) : HaloRenderBackend {
    private val generation = GpuPulseEvidence.beginGeneration()
    private val commands = FrameCommandBuffer()
    private val geometryProvider = EdgePathGeometryProvider(outline)
    private var config: HaloConfig? = null
    private var plan: VisualPlanSpec? = null
    private var shader: RuntimeShader? = null
    private var paint: Paint? = null
    private var resource: FieldResource? = null
    private val preSubmitFailures = GpuPulseFailureLatch<FieldKey>()
    private var disposed = false
    private var surfaceEpoch = 0

    internal fun generationForDebugEvidence(): Long = generation
    override fun updateConfig(config: HaloConfig) { if (!disposed) { this.config = config; plan = HaloEffectVisualPlanMapper.map(config); preSubmitFailures.clear(); diagnostics.clearActiveFailure(generation) } }
    override fun updateDebugVisualPlan(plan: VisualPlanSpec) { if (!disposed) { this.plan = plan; preSubmitFailures.clear(); diagnostics.clearActiveFailure(generation) } }
    override fun invalidateSurface() { if (!disposed) { surfaceEpoch++; disposeField(); shader = null; paint = null; preSubmitFailures.clear(); GpuPulseEvidence.recordSurfaceInvalidation(generation, surfaceEpoch); diagnostics.clearActiveFailure(generation); diagnostics.record("surface-invalidated", "generation=$generation epoch=$surfaceEpoch") } }

    override fun render(target: HaloRenderTarget, frame: RenderFrame): RenderOutcome {
        if (disposed) return RenderOutcome.Unsupported("GPU Pulse backend is disposed")
        val canvas = (target as? CanvasRenderTarget)?.canvas ?: return RenderOutcome.Unsupported("GPU Pulse requires CanvasRenderTarget")
        if (!canvas.isHardwareAccelerated) return RenderOutcome.Unsupported("GPU Pulse requires a hardware accelerated Canvas")
        if (frame.viewportWidth <= 0 || frame.viewportHeight <= 0) return RenderOutcome.Unsupported("GPU Pulse requires a positive viewport")
        val resolvedPlan = plan ?: config?.let(HaloEffectVisualPlanMapper::map)?.also { plan = it }
            ?: return RenderOutcome.Unsupported("GPU Pulse has no visual plan")
        FrameCommandCompiler.compile(resolvedPlan, frame, commands)
        val command = if (commands.size == 1) commands[0] else null
        GpuPulseCapability.unsupportedReason(frame.intent, frame.mode, commands.size, command)?.let {
            diagnostics.record("fallback", it); return RenderOutcome.Unsupported(it)
        }
        command!!
        diagnostics.record("selected-operation", "generation=$generation operation=${command.operation}")
        val requestedFieldKey = fieldKey(command, frame) ?: return RenderOutcome.Unsupported("GPU Pulse could not build complete contour field")
        preSubmitFailures.failureFor(requestedFieldKey)?.let {
            GpuPulseEvidence.recordLatchedFailure(generation)
            return RenderOutcome.Error(it)
        }
        val field = try {
            resolveField(requestedFieldKey, frame)
        } catch (error: RuntimeException) {
            recordPreSubmitFailure(requestedFieldKey, "field-preparation", error)
            return RenderOutcome.Error(preSubmitFailures.failureFor(requestedFieldKey)!!)
        } ?: return RenderOutcome.Unsupported("GPU Pulse could not build complete contour field")
        diagnostics.record("admitted-operation", "generation=$generation operation=${command.operation} key=${field.key.hashCode()}")
        preSubmitFailures.failureFor(field.key)?.let {
            GpuPulseEvidence.recordLatchedFailure(generation)
            return RenderOutcome.Error(it)
        }
        GpuPulseEvidence.recordPreSubmitAttempt(generation)
        val pulsePaint = initialize(field) ?: return RenderOutcome.Error(
            preSubmitFailures.failureFor(field.key)
                ?: IllegalStateException("GPU Pulse shader initialization failed")
        )
        // Generation/field key must still match immediately before the only pixel submission.
        if (disposed || resource !== field) return RenderOutcome.Unsupported("stale GPU Pulse resource rejected")
        var saveCount = -1
        var submissionStarted = false
        return try {
            // Physical-display clipping belongs to the host outline. Each setup operation remains
            // pre-submission, so it has a named failure stage and may safely Canvas-fallback.
            if (!prepare(field, "canvas-save") { saveCount = canvas.save() }) return failureOutcome(field)
            if (!prepare(field, "outline-clip") { canvas.clipPath(outline.path) }) return failureOutcome(field)
            // PULSE_SHADER deliberately has no `resolution` uniform. Setting an undeclared
            // RuntimeShader uniform throws IllegalStateException on device before submission.
            if (!prepare(field, "uniform-stroke-width") { shader!!.setFloatUniform("strokeWidth", field.key.strokeWidth) }) return failureOutcome(field)
            if (!prepare(field, "uniform-max-distance") { shader!!.setFloatUniform("maxDistance", field.maxDistance) }) return failureOutcome(field)
            if (!prepare(field, "uniform-color") { shader!!.setFloatUniform("color", red(command.appearance.color), green(command.appearance.color), blue(command.appearance.color), command.alpha / 255f) }) return failureOutcome(field)
            // Submission starts before invoking Canvas. drawRect may partially write GPU pixels
            // before throwing, so its failure must never be composited with a Canvas fallback.
            submissionStarted = true
            canvas.drawRect(0f, 0f, frame.viewportWidth.toFloat(), frame.viewportHeight.toFloat(), pulsePaint)
            GpuPulseEvidence.recordSubmission(generation, command.alpha)
            diagnostics.clearActiveFailure(generation)
            diagnostics.record("submitted", "generation=$generation key=${field.key.hashCode()}")
            diagnostics.record("actual-backend", "GPU")
            RenderOutcome.Rendered
        } catch (error: RuntimeException) {
            diagnostics.record("submission-failed", error.javaClass.simpleName)
            RenderOutcome.Error(error, fallbackEligible = !submissionStarted)
        } finally {
            if (saveCount >= 0) runCatching { canvas.restoreToCount(saveCount) }
        }
    }

    private fun fieldKey(command: FrameVisualCommand, frame: RenderFrame): FieldKey? {
        val contour = command.geometry as? GeometryRequest.DisplayOutlineContour ?: return null
        val strokeWidth = RESOLVED_BASE_STROKE_WIDTH + contour.thickness * RESOLVED_STROKE_WIDTH_RANGE
        if (!strokeWidth.isFinite() || strokeWidth <= 0f) return null
        return FieldKey(outline.version, surfaceEpoch, frame.viewportWidth, frame.viewportHeight, strokeWidth,
            outline.opticalInsetPx + outline.dpToPx(contour.edgeCalibrationDp), outline.dpToPx(contour.cornerCalibrationDp), contour.cornerShape,
            FIELD_TEXEL_SCALE, FIELD_MAX_DISTANCE_PX, FIELD_FORMAT_VERSION)
    }

    private fun resolveField(key: FieldKey, frame: RenderFrame): FieldResource? {
        resource?.takeIf { it.key == key }?.let { return it }
        disposeField()
        // Provider owns all physical geometry and calibration resolution; this executor retains no Path.
        val snapshot = geometryProvider.geometryFor(key.strokeWidth, key.edgeCalibrationPx, key.cornerCalibrationPx, key.cornerShape, 0f) ?: return null
        val measure = snapshot.measure
        if (measure.length <= 0f || measure.nextContour()) { // never silently omit a second authoritative contour
            diagnostics.record("fallback", "unsupported incomplete contour topology")
            return null
        }
        val points = snapshot.path.approximate(PATH_APPROXIMATION_ERROR_PX)
        if (points.size < 6) return null
        if (frame.viewportWidth > MAX_TEXTURE_DIMENSION || frame.viewportHeight > MAX_TEXTURE_DIMENSION) {
            diagnostics.record("fallback", "viewport exceeds bounded field texture limit")
            return null
        }
        val fieldWidth = frame.viewportWidth
        val fieldHeight = frame.viewportHeight
        val bitmap = Bitmap.createBitmap(fieldWidth, fieldHeight, Bitmap.Config.ARGB_8888)
        var transferred = false
        try {
        val pixels = IntArray(fieldWidth * fieldHeight)
        java.util.Arrays.fill(pixels, 0xFFFF0000.toInt())
        var i = 0
        while (i + 5 < points.size) {
            val ax = points[i + 1]; val ay = points[i + 2]; val bx = points[i + 4]; val by = points[i + 5]
            val left = kotlin.math.floor(min(ax, bx) - FIELD_MAX_DISTANCE_PX).toInt().coerceAtLeast(0)
            val right = kotlin.math.ceil(kotlin.math.max(ax, bx) + FIELD_MAX_DISTANCE_PX).toInt().coerceAtMost(fieldWidth - 1)
            val top = kotlin.math.floor(min(ay, by) - FIELD_MAX_DISTANCE_PX).toInt().coerceAtLeast(0)
            val bottom = kotlin.math.ceil(kotlin.math.max(ay, by) + FIELD_MAX_DISTANCE_PX).toInt().coerceAtMost(fieldHeight - 1)
            for (y in top..bottom) for (x in left..right) {
                val px = x + .5f; val py = y + .5f
                val dx = bx - ax; val dy = by - ay; val span = dx * dx + dy * dy
                val t = if (span <= 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / span).coerceIn(0f, 1f)
                val nearest = hypot(px - (ax + dx * t), py - (ay + dy * t)).coerceAtMost(FIELD_MAX_DISTANCE_PX)
                val encoded = (nearest / FIELD_MAX_DISTANCE_PX * 255f).roundToInt()
                val index = y * fieldWidth + x
                if (encoded < ((pixels[index] shr 16) and 255)) pixels[index] = 0xFF000000.toInt() or (encoded shl 16)
            }
            i += 3
        }
        bitmap.setPixels(pixels, 0, fieldWidth, 0, 0, fieldWidth, fieldHeight)
        val input = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        return FieldResource(key, bitmap, input, FIELD_MAX_DISTANCE_PX).also {
            resource = it
            transferred = true
            GpuPulseEvidence.recordResourceInitialization(generation, key.hashCode(), surfaceEpoch)
            diagnostics.record("field-initialized", "generation=$generation key=${key.hashCode()} ${fieldWidth}x$fieldHeight")
        }
        } finally {
            if (!transferred) bitmap.recycle()
        }
    }

    private fun initialize(field: FieldResource): Paint? {
        paint?.let {
            // A calibration/version key can replace the field while the compiled program stays valid.
            return if (prepare(field, "input-shader-bind") { shader!!.setInputShader("field", field.shader) }) it else null
        }
        GpuPulseEvidence.recordShaderInitializationAttempt(generation)
        val runtimeShader = create(field, "shader-compile") { RuntimeShader(PULSE_SHADER) } ?: return null
        if (!prepare(field, "input-shader-bind") { runtimeShader.setInputShader("field", field.shader) }) return null
        val runtimePaint = create(field, "paint-attach") {
            Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = runtimeShader }
        } ?: return null
        shader = runtimeShader
        paint = runtimePaint
        GpuPulseEvidence.recordShaderInitialization(generation)
        diagnostics.record("shader-initialized", "generation=$generation key=${field.key.hashCode()}")
        return runtimePaint
    }

    private fun failureOutcome(field: FieldResource): RenderOutcome.Error = RenderOutcome.Error(
        preSubmitFailures.failureFor(field.key)
            ?: IllegalStateException("GPU Pulse pre-submit setup failed without a captured cause")
    )

    private fun prepare(field: FieldResource, stage: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (error: RuntimeException) {
        recordPreSubmitFailure(field, stage, error)
        false
    }

    private fun <T> create(field: FieldResource, stage: String, block: () -> T): T? = try {
        block()
    } catch (error: RuntimeException) {
        recordPreSubmitFailure(field, stage, error)
        null
    }

    private fun recordPreSubmitFailure(field: FieldResource, stage: String, error: RuntimeException) {
        recordPreSubmitFailure(field.key, stage, error)
    }

    private fun recordPreSubmitFailure(key: FieldKey, stage: String, error: RuntimeException) {
        val failure = GpuPulsePreSubmitFailure.at(stage, error)
        preSubmitFailures.record(key, failure)
        GpuPulseEvidence.recordFirstFailure(generation, key.hashCode(), failure)
        diagnostics.recordFailure(generation, key.hashCode(), failure)
    }
    private fun disposeField() { resource?.let { it.bitmap.recycle(); resource = null; GpuPulseEvidence.recordResourceDisposal(generation); diagnostics.record("resource-disposed", "generation=$generation") } }
    override fun dispose() { if (!disposed) { disposed = true; disposeField(); shader = null; paint = null; plan = null; GpuPulseEvidence.recordDisposal(generation); diagnostics.record("disposed", "generation=$generation") } }

    private data class FieldKey(val outlineVersion: Int, val surfaceEpoch: Int, val viewportWidth: Int, val viewportHeight: Int, val strokeWidth: Float, val edgeCalibrationPx: Float, val cornerCalibrationPx: Float, val cornerShape: Float, val texelScale: Int, val maxDistancePx: Float, val formatVersion: Int)
    private class FieldResource(val key: FieldKey, val bitmap: Bitmap, val shader: BitmapShader, val maxDistance: Float)
    internal companion object {
        const val RESOLVED_BASE_STROKE_WIDTH = 4f
        const val RESOLVED_STROKE_WIDTH_RANGE = 10f
        const val FIELD_TEXEL_SCALE = 1
        const val MAX_TEXTURE_DIMENSION = 4096
        const val FIELD_MAX_DISTANCE_PX = 24f
        const val FIELD_FORMAT_VERSION = 1
        const val PATH_APPROXIMATION_ERROR_PX = 0.125f
        fun red(c: Int) = ((c shr 16) and 255) / 255f; fun green(c: Int) = ((c shr 8) and 255) / 255f; fun blue(c: Int) = (c and 255) / 255f
        const val PULSE_SHADER = """
            uniform shader field;
            uniform float strokeWidth;
            uniform float maxDistance;
            uniform float4 color;
            half4 main(float2 p) {
                float distance = float(field.eval(p).r) * maxDistance;
                // AGSL targets SkSL ES1: derivatives such as fwidth are unavailable.
                // The native-resolution field supplies the same device-pixel distance gradient.
                float dx = float(field.eval(p + float2(1.0, 0.0)).r) * maxDistance - distance;
                float dy = float(field.eval(p + float2(0.0, 1.0)).r) * maxDistance - distance;
                float aa = max(1.0, abs(dx) + abs(dy));
                float coverage = smoothstep(strokeWidth * .5 + aa, strokeWidth * .5 - aa, distance);
                float alpha = color.a * coverage;
                return half4(half3(color.rgb * alpha), half(alpha));
            }
        """
    }
}

internal object GpuPulseDiagnostics {
    private val last = mutableMapOf<String, String>()
    private var activeFailure: ActiveFailure? = null

    private data class ActiveFailure(
        val generation: Long,
        val fieldKey: Int,
        val stage: String,
        val type: String,
        val message: String?
    )

    /** Read-only DEBUG presentation bridge; it does not participate in render decisions. */
    data class Snapshot(
        val selectedOperation: String?,
        val admittedOperation: String?,
        val fallbackReason: String?,
        val actualBackend: String?,
        val failureStage: String?,
        val failureType: String?,
        val failureMessage: String?
    )

    @Synchronized
    fun record(event: String, detail: String) {
        if (last.put(event, detail) != detail) Log.d("HaloGpuWitness", "GPU Pulse $event: $detail")
    }

    @Synchronized
    fun resetForTest() {
        last.clear()
        activeFailure = null
    }

    /** Emits the complete stack only when this stable failure fingerprint first occurs. */
    @Synchronized
    fun recordFailure(generation: Long, fieldKey: Int, failure: GpuPulsePreSubmitFailure) {
        val fingerprint = "generation=$generation key=$fieldKey stage=${failure.stage} type=${failure.originalType} message=${failure.originalMessage}"
        activeFailure = ActiveFailure(
            generation = generation,
            fieldKey = fieldKey,
            stage = failure.stage,
            type = failure.originalType,
            message = failure.originalMessage
        )
        if (last.put("pre-submit-failure", fingerprint) == fingerprint) return
        Log.e("HaloGpuWitness", "GPU Pulse pre-submit failure: $fingerprint", failure)
    }

    @Synchronized
    fun clearActiveFailure(generation: Long) {
        if (activeFailure?.generation == generation) activeFailure = null
    }

    @Synchronized
    fun snapshotForGeneration(generation: Long?): Snapshot {
        val failure = activeFailure?.takeIf { it.generation == generation }
        return Snapshot(
        selectedOperation = last["selected-operation"],
        admittedOperation = last["admitted-operation"],
        fallbackReason = last["fallback"],
        actualBackend = last["actual-backend"],
        failureStage = failure?.stage,
        failureType = failure?.type,
        failureMessage = failure?.message
        )
    }
}

internal object GpuPulseEvidence {
    private val lock = Object()
    private var next = 0L
    private val states = mutableMapOf<Long, State>()
    private data class State(
        var initialized: Int = 0,
        var shaderInitializations: Int = 0,
        var shaderInitializationAttempts: Int = 0,
        var preSubmitAttempts: Int = 0,
        var latchedFailures: Int = 0,
        var pendingLatchedFallbacks: Int = 0,
        var canvasAfterLatchFallbacks: Int = 0,
        var submissions: Int = 0,
        var fallbacks: Int = 0,
        var canvasPulseCalls: Int = 0,
        var fieldDisposals: Int = 0,
        var disposals: Int = 0,
        var lastAlpha: Int = -1,
        var latestActualBackend: String? = null,
        var latestFallbackReason: String? = null,
        var mostRecentFallbackReason: String? = null,
        var fieldKey: Int? = null,
        var surfaceEpoch: Int? = null,
        var firstFailure: FailureDetails? = null
    )
    /** Bounded immutable failure context for DEBUG-only, session-scoped reporting. */
    data class FailureDetails(
        val fieldKey: Int,
        val stage: String,
        val type: String,
        val message: String?,
        val classification: String,
        val stack: List<String>
    )
    data class Snapshot(
        val generation: Long,
        val resourceInitializations: Int,
        val shaderInitializations: Int,
        val preSubmitAttempts: Int,
        val latchedFailures: Int,
        val canvasAfterLatchFallbacks: Int,
        val submissions: Int,
        val fallbacks: Int,
        val canvasPulseCalls: Int,
        val fieldDisposals: Int,
        val disposals: Int,
        val lastAlpha: Int,
        /** Latest outcome for this generation; counts above remain historical evidence. */
        val latestActualBackend: String?,
        val latestFallbackReason: String?,
        val mostRecentFallbackReason: String?,
        val fieldKey: Int?,
        val surfaceEpoch: Int?,
        val firstFailure: FailureDetails?,
        val shaderInitializationAttempts: Int = 0
    )
    fun beginGeneration(): Long = synchronized(lock) {
        val generation = ++next
        states[generation] = State()
        if (states.size > 32) states.remove(states.keys.minOrNull())
        generation
    }
    fun resetForTest() = synchronized(lock) { states.clear() }
    fun latestGeneration(): Long? = synchronized(lock) { states.keys.maxOrNull() }
    fun recordResourceInitialization(g: Long, fieldKey: Int, surfaceEpoch: Int) = synchronized(lock) {
        (states[g] ?: return@synchronized).apply {
            initialized++
            this.fieldKey = fieldKey
            this.surfaceEpoch = surfaceEpoch
        }
        Unit
    }
    fun recordShaderInitialization(g: Long) = synchronized(lock) { (states[g] ?: return@synchronized).shaderInitializations++ }
    fun recordShaderInitializationAttempt(g: Long) = synchronized(lock) { (states[g] ?: return@synchronized).shaderInitializationAttempts++ }
    fun recordPreSubmitAttempt(g: Long) = synchronized(lock) { (states[g] ?: return@synchronized).preSubmitAttempts++ }
    fun recordLatchedFailure(g: Long) = synchronized(lock) {
        (states[g] ?: return@synchronized).apply {
            latchedFailures++
            pendingLatchedFallbacks++
        }
        Unit
    }
    fun recordSurfaceInvalidation(g: Long, surfaceEpoch: Int) = synchronized(lock) {
        (states[g] ?: return@synchronized).surfaceEpoch = surfaceEpoch
    }
    fun recordFirstFailure(g: Long, fieldKey: Int, failure: GpuPulsePreSubmitFailure) = synchronized(lock) {
        val state = states[g] ?: return@synchronized
        if (state.firstFailure == null) {
            state.firstFailure = FailureDetails(
                fieldKey = fieldKey,
                stage = failure.stage,
                type = failure.originalType,
                message = failure.originalMessage,
                classification = failure.classification,
                stack = failure.stackTraceToString().take(16000).lineSequence().take(100).toList()
            )
        }
    }
    fun recordSubmission(g: Long, alpha: Int) = synchronized(lock) {
        (states[g] ?: return@synchronized).apply {
            submissions++
            lastAlpha = alpha
            latestActualBackend = "GPU"
            latestFallbackReason = null
        }
        lock.notifyAll()
    }
    fun recordFallback(g: Long, reason: String, canvasRendered: Boolean = true) = synchronized(lock) {
        (states[g] ?: return@synchronized).apply {
            fallbacks++
            if (pendingLatchedFallbacks > 0) {
                pendingLatchedFallbacks--
                canvasAfterLatchFallbacks++
            }
            latestActualBackend = if (canvasRendered) "Canvas" else null
            latestFallbackReason = reason
            mostRecentFallbackReason = reason
        }
        Unit
    }
    fun recordCanvasPulse(g: Long) = synchronized(lock) { (states[g] ?: return@synchronized).canvasPulseCalls++ }
    fun recordResourceDisposal(g: Long) = synchronized(lock) { (states[g] ?: return@synchronized).fieldDisposals++ }
    fun recordDisposal(g: Long) = synchronized(lock) { (states[g] ?: return@synchronized).disposals++ }
    fun snapshot(g: Long) = synchronized(lock) {
        states[g]?.let {
            Snapshot(
                g,
                it.initialized,
                it.shaderInitializations,
                it.preSubmitAttempts,
                it.latchedFailures,
                it.canvasAfterLatchFallbacks,
                it.submissions,
                it.fallbacks,
                it.canvasPulseCalls,
                it.fieldDisposals,
                it.disposals,
                it.lastAlpha,
                it.latestActualBackend,
                it.latestFallbackReason,
                it.mostRecentFallbackReason,
                it.fieldKey,
                it.surfaceEpoch,
                it.firstFailure,
                it.shaderInitializationAttempts
            )
        }
    }
    fun awaitSubmitted(g: Long, timeoutMillis: Long): Snapshot? = synchronized(lock) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while ((states[g]?.submissions ?: 0) == 0) {
            val remaining = deadline - System.nanoTime(); if (remaining <= 0) return@synchronized null
            lock.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
        }
        snapshot(g)
    }
    fun awaitSubmissionCount(g: Long, atLeast: Int, timeoutMillis: Long): Snapshot? = synchronized(lock) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while ((states[g]?.submissions ?: 0) < atLeast) {
            val remaining = deadline - System.nanoTime(); if (remaining <= 0) return@synchronized null
            lock.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
        }
        snapshot(g)
    }
}
