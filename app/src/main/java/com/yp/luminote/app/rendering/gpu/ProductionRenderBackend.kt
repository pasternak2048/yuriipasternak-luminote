package com.yp.luminote.app.rendering.gpu

import android.util.Log

import com.yp.luminote.app.animation.plan.*
import com.yp.luminote.app.data.settings.RenderingEngine
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.effects.model.HaloRenderMode
import com.yp.luminote.app.rendering.api.*
import com.yp.luminote.app.rendering.canvas.CanvasRenderBackend

/** Bounded production-only evidence. Never derived from a DEBUG Lab renderer generation. */
internal object ProductionRenderEvidence {
    data class Snapshot(val generation: Long, val requested: RenderingEngine,
        val operation: String, val actual: String = "pending", val submitted: Long = 0,
        val fallbacks: Long = 0, val reason: String? = null, val disposed: Boolean = false,
        val firstFailure: String? = null)
    private var serial = 0L
    private var current: Snapshot? = null
    private var gpuFrames = 0L
    private var fallbackFrames = 0L
    val snapshot: Snapshot? @Synchronized get() = current?.copy(submitted = gpuFrames, fallbacks = fallbackFrames)
    @Synchronized fun begin(engine: RenderingEngine, operation: String): Long = (++serial).also {
        current = Snapshot(it, engine, operation); gpuFrames = 0; fallbackFrames = 0
    }
    @Synchronized fun frame(generation: Long, actual: String, fallback: String?) {
        current?.takeIf { it.generation == generation }?.let {
            if (actual == "GPU") gpuFrames++
            if (fallback != null) fallbackFrames++
            if (it.actual != actual || it.reason != fallback) current = it.copy(actual = actual, reason = fallback)
        }
    }
    @Synchronized fun failure(generation: Long, reason: String) {
        current?.takeIf { it.generation == generation }?.let { current = it.copy(actual = "error", reason = reason,
            firstFailure = it.firstFailure ?: reason.take(2048)) }
    }
    @Synchronized fun dispose(generation: Long) {
        current?.takeIf { it.generation == generation }?.let { current = it.copy(disposed = true) }
    }
    fun report(): String = snapshot?.let {
        "PRODUCTION RENDERER (includes normal previews; notification delivery requires separate evidence)\ngeneration=${it.generation} requested=${it.requested} actual=${it.actual} operation=${it.operation}\n" +
            "gpuSubmittedFrames=${it.submitted} canvasFallbackFrames=${it.fallbacks} reason=${it.reason ?: "none"} disposed=${it.disposed}\nfirstFailure=${it.firstFailure ?: "none"}"
    } ?: "PRODUCTION RENDERER: no execution observed in this process"
}

/** Production admission keeps specialized reminder and ambient routes on complete Canvas plans. */
internal object ProductionGpuAdmission {
    fun reason(config: HaloConfig, plan: VisualPlanSpec): String? {
        if (config.renderMode != HaloRenderMode.NORMAL) return "specialized-route:${config.renderMode}"
        val emitter = plan.emitters.singleOrNull() ?: return "multiple-emitters"
        if (emitter.transform != EmitterTransform()) return "unsupported-transform"
        val admitted = when (emitter.operation) {
            PrimaryVisualOperation.FullContourPulse -> GpuPulseCapability.supports(emitter.appearance, emitter.operation, emitter.modifiers)
            is PrimaryVisualOperation.TravelingLuminousSegment -> GpuSnakeCapability.supports(emitter)
            PrimaryVisualOperation.LightImpulse -> GpuImpulseCapability.supports(emitter)
            PrimaryVisualOperation.Supernova -> GpuSupernovaCapability.supports(emitter)
            else -> false
        }
        return if (admitted) null else "unsupported-full-plan"
    }
}

/** One host, one frame clock; only the pixel executor changes. Main-thread confined. */
internal class ProductionRenderBackend(
    initialConfig: HaloConfig, private val outline: DisplayOutline,
    private val requested: RenderingEngine,
    private val canvas: HaloRenderBackend = CanvasRenderBackend(initialConfig, outline),
    private val factory: ((VisualPlanSpec) -> HaloRenderBackend)? = null
) : HaloRenderBackend {
    private var config = initialConfig
    private var plan = HaloEffectVisualPlanMapper.map(initialConfig)
    private var signature = signature(plan, initialConfig.renderMode)
    private var admissionReason = ProductionGpuAdmission.reason(initialConfig, plan)
    private var gpu: HaloRenderBackend? = null
    private var failure: String? = null
    private var disposed = false
    private var generation = 0L

    private fun operation() = plan.emitters.singleOrNull()?.operation?.javaClass?.simpleName ?: "composite"
    override fun updateConfig(config: HaloConfig) {
        if (disposed) return
        val next = HaloEffectVisualPlanMapper.map(config)
        val key = signature(next, config.renderMode)
        canvas.updateConfig(config)
        this.config = config
        if (signature == key) return
        gpu?.dispose(); gpu = null; failure = null
        plan = next; signature = key
        admissionReason = ProductionGpuAdmission.reason(config, next)
        generation = 0L
    }
    override fun invalidateSurface() {
        if (disposed) return
        gpu?.invalidateSurface(); canvas.invalidateSurface(); failure = null
        generation = 0L
    }
    override fun render(target: HaloRenderTarget, frame: RenderFrame): RenderOutcome {
        if (disposed) return RenderOutcome.Unsupported("production backend disposed")
        if (generation == 0L) generation = ProductionRenderEvidence.begin(requested, operation())
        if (requested == RenderingEngine.CANVAS) return renderCanvas(target, frame, null)
        val rejected = admissionReason
        if (rejected != null) return renderCanvas(target, frame, "unsupported:$rejected")
        if (frame.mode != RenderMode.ANIMATION) return renderCanvas(target, frame, "unsupported:static-calibration")
        failure?.let { return renderCanvas(target, frame, it) }
        val backend = gpu ?: try {
            createGpu().also { gpu = it; it.updateConfig(config) }
        } catch (error: RuntimeException) {
            failure = capture("backend-initialization", error)
            return renderCanvas(target, frame, failure)
        }
        val result = try {
            backend.render(target, frame)
        } catch (error: RuntimeException) {
            // An unstructured executor throw has unknown submission state: recover next frame.
            failure = capture("unstructured-render", error)
            ProductionRenderEvidence.failure(generation, failure!!)
            return RenderOutcome.Error(error, fallbackEligible = false)
        }
        return when (val outcome = result) {
            RenderOutcome.Rendered -> outcome.also { ProductionRenderEvidence.frame(generation, "GPU", null) }
            is RenderOutcome.Unsupported -> {
                failure = "unsupported:${outcome.reason}"
                renderCanvas(target, frame, failure)
            }
            is RenderOutcome.Error -> {
                val evidence = outcome.cause as? GpuPulsePreSubmitFailure
                val classified = evidence?.classification ?: "error:${outcome.cause.javaClass.simpleName}"
                failure = classified
                ProductionRenderEvidence.failure(generation, classified + ":" +
                    (evidence?.originalMessage ?: outcome.cause.message).orEmpty().take(512))
                if (outcome.fallbackEligible) renderCanvas(target, frame, classified)
                else outcome.also { ProductionRenderEvidence.failure(generation, "post-submit:$classified") }
            }
        }
    }
    private fun capture(stage: String, error: RuntimeException): String {
        val reason = "$stage:${error.javaClass.simpleName}:${error.message.orEmpty().take(256)}"
        ProductionRenderEvidence.failure(generation, reason)
        Log.e("HaloGpuProduction", "generation=$generation $reason", error)
        return reason
    }
    private fun createGpu(): HaloRenderBackend = factory?.invoke(plan) ?: when (plan.emitters.single().operation) {
        PrimaryVisualOperation.FullContourPulse -> GpuPulseRenderBackend(outline)
        is PrimaryVisualOperation.TravelingLuminousSegment -> GpuSnakeRenderBackend(outline)
        PrimaryVisualOperation.LightImpulse -> GpuImpulseRenderBackend(outline)
        PrimaryVisualOperation.Supernova -> GpuSupernovaRenderBackend(outline)
        else -> error("Admission and GPU factory disagree")
    }
    private fun renderCanvas(target: HaloRenderTarget, frame: RenderFrame, reason: String?): RenderOutcome =
        canvas.render(target, frame).also { if (it == RenderOutcome.Rendered) ProductionRenderEvidence.frame(generation, "Canvas", reason) }
    override fun dispose() {
        if (disposed) return
        disposed = true; gpu?.dispose(); gpu = null; canvas.dispose()
        ProductionRenderEvidence.dispose(generation)
    }
    companion object {
        private fun signature(plan: VisualPlanSpec, mode: HaloRenderMode): List<Any> = listOf(mode) + plan.emitters.map {
            listOf(it.geometry, it.transform, it.appearance, it.operation, it.modifierProfile, it.modifiers, it.luminousSegmentAppearance)
        }
    }
}
