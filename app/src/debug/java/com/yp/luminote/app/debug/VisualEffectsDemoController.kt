package com.yp.luminote.app.debug

import android.content.Context
import android.os.Build
import android.view.ViewGroup
import com.yp.luminote.app.animation.definitions.AzureBladeAnimation
import com.yp.luminote.app.animation.definitions.BladeVariant
import com.yp.luminote.app.animation.definitions.CrimsonBladeAnimation
import com.yp.luminote.app.animation.definitions.ForceClashAnimation
import com.yp.luminote.app.animation.definitions.HaloAnimationId
import com.yp.luminote.app.animation.definitions.HaloEffectSpec
import com.yp.luminote.app.animation.definitions.ImpulseAnimation
import com.yp.luminote.app.animation.definitions.PulseAnimation
import com.yp.luminote.app.animation.definitions.SnakeAnimation
import com.yp.luminote.app.animation.definitions.SupernovaAnimation
import com.yp.luminote.app.animation.plan.HaloEffectVisualPlanMapper
import com.yp.luminote.app.animation.plan.PrimaryVisualOperation
import com.yp.luminote.app.animation.plan.VisualModifier
import com.yp.luminote.app.animation.plan.VisualPlanSpec
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.overlay.host.HaloView
import com.yp.luminote.app.rendering.gpu.GpuPulseEvidence
import com.yp.luminote.app.rendering.gpu.GpuWitnessEvidence
import com.yp.luminote.app.animation.definitions.LuminoteHaloAnimations
import com.yp.luminote.app.rendering.gpu.GpuPulseCapability
import com.yp.luminote.app.rendering.gpu.GpuSnakeCapability
import com.yp.luminote.app.rendering.gpu.GpuSupernovaCapability
import com.yp.luminote.app.rendering.gpu.GpuImpulseCapability

internal enum class VisualEffectsDemoEngine { CANVAS, GPU }
internal data class DemoCapability(val admitted: Boolean, val reason: String)
internal val visualEffectsDemoFamilies: List<VisualEffectsDemoFamily>
    get() = LuminoteHaloAnimations.all.mapNotNull { definition ->
        VisualEffectsDemoFamily.entries.find { it.config().motion == definition.id }
    }
internal fun demoCapability(engine: VisualEffectsDemoEngine, family: VisualEffectsDemoFamily, mode: VisualEffectsDemoMode): DemoCapability {
    val plan = visualEffectsDemoPlan(family, mode)
        ?: return DemoCapability(false, "Effect is not supported for this animation.")
    if (engine == VisualEffectsDemoEngine.CANVAS) return DemoCapability(true, "Canvas operation available.")
    val emitter = plan.emitters.single()
    val admitted = GpuPulseCapability.supports(emitter.appearance, emitter.operation, emitter.modifiers) || GpuSnakeCapability.supports(emitter) || GpuImpulseCapability.supports(emitter) || GpuSupernovaCapability.supports(emitter)
    return DemoCapability(admitted, if (admitted) "GPU operation admitted for validation." else "GPU implementation pending for this selection.")
}

/** The modifier stack variants exposed by the debug-only visual-effects demo. */
internal enum class VisualEffectsDemoMode {
    BASELINE,
    GLOW,
    BLUR,
    INTENSITY_PULSE,
    COMBINED,
    /** Exact production normal Snake parameters; demo Full Stack stays unchanged. */
    PRODUCTION,
    PRODUCTION_SUPERNOVA,
    /** Deliberately conspicuous DEBUG-only witness for framebuffer comparison. */
    DIAGNOSTIC_GLOW,
    /** Deliberately conspicuous DEBUG-only witness for framebuffer comparison. */
    DIAGNOSTIC_BLUR,
    /** The low point of the real frame-time pulse; it changes alpha without a new clock. */
    DIAGNOSTIC_INTENSITY_PULSE
}

/** Each family is backed by its real production operation, renderer, and motion id. */
internal enum class VisualEffectsDemoFamily(
    val label: String,
    private val motionId: HaloAnimationId,
    private val effectSpec: HaloEffectSpec
) {
    FULL_CONTOUR("Full Contour", PulseAnimation.id, HaloEffectSpec.FullContour),
    LUMINOUS_SEGMENT("Traveling Segment", SnakeAnimation.id, HaloEffectSpec.LuminousSegment(0.18f)),
    LIGHT_IMPULSE("Light Impulse", ImpulseAnimation.id, HaloEffectSpec.SpecializedField),
    SUPERNOVA("Supernova", SupernovaAnimation.id, HaloEffectSpec.Supernova),
    FORCE_BLADE_AZURE("ForceBlade Azure", AzureBladeAnimation.id, HaloEffectSpec.Blade(BladeVariant.AZURE)),
    FORCE_BLADE_CRIMSON("ForceBlade Crimson", CrimsonBladeAnimation.id, HaloEffectSpec.Blade(BladeVariant.CRIMSON)),
    FORCE_BLADE_CLASH("ForceBlade Clash", ForceClashAnimation.id, HaloEffectSpec.Blade(BladeVariant.CLASH));

    fun config(): HaloConfig = HaloConfig(motion = motionId, effectPlan = effectSpec).sanitized()
}

internal sealed interface VisualEffectsDemoSelection {
    data object Applied : VisualEffectsDemoSelection
    data class Unsupported(val reason: String) : VisualEffectsDemoSelection
}

/**
 * Debug-only bridge between a simple UI and the production [HaloView] Canvas host.
 *
 * The family configuration always rebuilds the real plan and restarts its native motion.
 * The view retains production display-outline geometry, Canvas backend, and lifecycle.
 */
internal class VisualEffectsDemoController(
    private val context: Context,
    private val container: ViewGroup
) {
    private var config = VisualEffectsDemoFamily.FULL_CONTOUR.config()
    private val haloView = HaloView(context, config)
    private var gpuWitnessEnabled = false
    private var gpuPulseEnabled = false
    private var gpuSnakeEnabled = false
    private var gpuSupernovaEnabled = false
    private var gpuImpulseEnabled = false
    private var gpuWitnessGeneration: Long? = null
    private var gpuPulseGeneration: Long? = null
    private var validationSession: GpuValidationSession? = null
    private var nextValidationSessionId = 0L
    private var validationDeadline = 0L
    private var completedValidation: GpuValidationSessionSnapshot? = null
    private var restoreAfterValidation: (() -> Unit)? = null
    private var canvasPreviewBaseline = 0L

    fun previewAnimation(engine: VisualEffectsDemoEngine, family: VisualEffectsDemoFamily, mode: VisualEffectsDemoMode): VisualEffectsDemoSelection {
        val capability = demoCapability(engine, family, mode)
        if (!capability.admitted) return VisualEffectsDemoSelection.Unsupported(capability.reason)
        cancelSelfTest()
        canvasPreviewBaseline = haloView.canvasRenderedFramesForDebug()
        selectCanvasBackend()
        selectFamily(family)
        select(mode)
        if (engine == VisualEffectsDemoEngine.GPU && !when (family) {
            VisualEffectsDemoFamily.LUMINOUS_SEGMENT -> setGpuSnakeEnabled(true)
            VisualEffectsDemoFamily.LIGHT_IMPULSE -> setGpuImpulseEnabled(true)
            VisualEffectsDemoFamily.SUPERNOVA -> setGpuSupernovaEnabled(true)
            else -> setGpuPulseEnabled(true)
        }) {
            return VisualEffectsDemoSelection.Unsupported("GPU backend is unavailable on this device/build.")
        }
        restart()
        return VisualEffectsDemoSelection.Applied
    }

    fun runShaderTest(): Boolean {
        cancelSelfTest()
        selectCanvasBackend()
        selectedMode = VisualEffectsDemoMode.BASELINE
        haloView.replaceVisualPlanForDebug(visualEffectsDemoPlan(selectedFamily, selectedMode)!!)
        val enabled = setGpuWitnessEnabled(true)
        restart()
        return enabled
    }

    private fun selectCanvasBackend() {
        if (gpuWitnessEnabled) setGpuWitnessEnabled(false)
        if (gpuPulseEnabled) setGpuPulseEnabled(false)
        if (gpuSnakeEnabled) setGpuSnakeEnabled(false)
        if (gpuImpulseEnabled) setGpuImpulseEnabled(false)
        if (gpuSupernovaEnabled) setGpuSupernovaEnabled(false)
    }

    private fun cancelSelfTest() {
        validationSession?.cancel()
        restoreAfterValidation = null
    }

    /** Backgrounding ends the bounded diagnostic run even when UI polling is suspended. */
    fun onHostStopped() {
        val session = validationSession ?: return
        if (!session.snapshot(gpuPulseGeneration?.let(GpuPulseEvidence::snapshot), gpuWitnessGeneration?.let(GpuWitnessEvidence::snapshot)).active) return
        session.cancel()
        completedValidation = session.snapshot(gpuPulseGeneration?.let(GpuPulseEvidence::snapshot), gpuWitnessGeneration?.let(GpuWitnessEvidence::snapshot))
        val restore = restoreAfterValidation
        restoreAfterValidation = null
        // Existing selectors dispose diagnostic resources and restore the prior selected route.
        restore?.invoke()
        haloView.cancelAnimation()
    }

    var selectedFamily: VisualEffectsDemoFamily = VisualEffectsDemoFamily.FULL_CONTOUR
        private set
    var selectedMode: VisualEffectsDemoMode = VisualEffectsDemoMode.BASELINE
        private set

    init {
        container.addView(
            haloView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        applyPlan(VisualEffectsDemoMode.BASELINE)
    }

    fun selectFamily(family: VisualEffectsDemoFamily) {
        validationSession?.cancel()
        selectedFamily = family
        selectedMode = VisualEffectsDemoMode.BASELINE
        config = family.config()
        haloView.update(config)
        applyPlan(VisualEffectsDemoMode.BASELINE)
    }

    fun select(mode: VisualEffectsDemoMode): VisualEffectsDemoSelection {
        if ((gpuWitnessEnabled || gpuPulseEnabled || gpuImpulseEnabled) && mode != VisualEffectsDemoMode.BASELINE) {
            return VisualEffectsDemoSelection.Unsupported(
                "GPU RuntimeShader witness accepts Baseline only. Disable it before selecting modifiers."
            )
        }
        val plan = visualEffectsDemoPlan(selectedFamily, mode)
            ?: return VisualEffectsDemoSelection.Unsupported(unsupportedDescription(selectedFamily, mode))
        if (gpuSnakeEnabled && !GpuSnakeCapability.supports(plan.emitters.single())) {
            return VisualEffectsDemoSelection.Unsupported("GPU Snake requires Baseline or Production Snake Stack.")
        }
        if (gpuSupernovaEnabled && !GpuSupernovaCapability.supports(plan.emitters.single())) return VisualEffectsDemoSelection.Unsupported("GPU Supernova requires Baseline or Production Supernova Stack.")
        selectedMode = mode
        validationSession?.cancel()
        haloView.replaceVisualPlanForDebug(plan)
        restart()
        return VisualEffectsDemoSelection.Applied
    }

    /** Gate A developer switch; normal configuration is retained and no production setting changes. */
    fun setGpuWitnessEnabled(enabled: Boolean): Boolean {
        if (!haloView.setGpuWitnessEnabledForDebug(enabled)) return false
        gpuWitnessEnabled = enabled
        validationSession?.cancel()
        gpuWitnessGeneration = if (enabled) GpuWitnessEvidence.latestGeneration() else null
        if (enabled) {
            gpuPulseEnabled = false
            gpuSnakeEnabled = false
            gpuImpulseEnabled = false
            gpuSupernovaEnabled = false
            gpuPulseGeneration = null
        }
        if (enabled) {
            selectedMode = VisualEffectsDemoMode.BASELINE
            // Selection recreates the pipeline; inject the precise selected plan afterwards.
            haloView.replaceVisualPlanForDebug(visualEffectsDemoPlan(selectedFamily, selectedMode)!!)
        }
        return true
    }

    /** Gate B debug route; only the existing normal Full Contour baseline is admitted. */
    fun setGpuPulseEnabled(enabled: Boolean): Boolean {
        if (enabled && selectedFamily != VisualEffectsDemoFamily.FULL_CONTOUR) return false
        if (!haloView.setGpuPulseEnabledForDebug(enabled)) return false
        gpuPulseEnabled = enabled
        validationSession?.cancel()
        gpuPulseGeneration = if (enabled) GpuPulseEvidence.latestGeneration() else null
        if (enabled) {
            gpuWitnessEnabled = false
            gpuSnakeEnabled = false
            gpuImpulseEnabled = false
            gpuSupernovaEnabled = false
            gpuWitnessGeneration = null
            selectedMode = VisualEffectsDemoMode.BASELINE
            haloView.replaceVisualPlanForDebug(visualEffectsDemoPlan(selectedFamily, selectedMode)!!)
        }
        return true
    }

    fun setGpuSnakeEnabled(enabled: Boolean): Boolean {
        if (enabled && (selectedFamily != VisualEffectsDemoFamily.LUMINOUS_SEGMENT ||
                !GpuSnakeCapability.supports(visualEffectsDemoPlan(selectedFamily,selectedMode)!!.emitters.single()))) return false
        if (!haloView.setGpuSnakeEnabledForDebug(enabled)) return false
        gpuSnakeEnabled = enabled
        validationSession?.cancel()
        // Shared evidence uses globally unique generations, captured only after this selector.
        gpuPulseGeneration = if (enabled) GpuPulseEvidence.latestGeneration() else null
        if (enabled) {
            gpuPulseEnabled = false; gpuWitnessEnabled = false; gpuImpulseEnabled = false; gpuSupernovaEnabled = false; gpuWitnessGeneration = null
            haloView.replaceVisualPlanForDebug(visualEffectsDemoPlan(selectedFamily,selectedMode)!!)
        }
        return true
    }

    fun setGpuImpulseEnabled(enabled: Boolean): Boolean {
        if (enabled && (selectedFamily != VisualEffectsDemoFamily.LIGHT_IMPULSE ||
                !GpuImpulseCapability.supports(visualEffectsDemoPlan(selectedFamily, selectedMode)!!.emitters.single()))) return false
        if (!haloView.setGpuImpulseEnabledForDebug(enabled)) return false
        gpuImpulseEnabled = enabled
        validationSession?.cancel()
        gpuPulseGeneration = if (enabled) GpuPulseEvidence.latestGeneration() else null
        if (enabled) {
            gpuPulseEnabled = false; gpuSnakeEnabled = false; gpuSupernovaEnabled = false; gpuWitnessEnabled = false; gpuWitnessGeneration = null
            haloView.replaceVisualPlanForDebug(visualEffectsDemoPlan(selectedFamily, selectedMode)!!)
        }
        return true
    }

    fun setGpuSupernovaEnabled(enabled: Boolean): Boolean {
        if (enabled && (selectedFamily != VisualEffectsDemoFamily.SUPERNOVA ||
                !GpuSupernovaCapability.supports(visualEffectsDemoPlan(selectedFamily, selectedMode)!!.emitters.single()))) return false
        if (!haloView.setGpuSupernovaEnabledForDebug(enabled)) return false
        gpuSupernovaEnabled = enabled
        validationSession?.cancel()
        gpuPulseGeneration = if (enabled) GpuPulseEvidence.latestGeneration() else null
        if (enabled) {
            gpuPulseEnabled = false; gpuSnakeEnabled = false; gpuImpulseEnabled = false; gpuWitnessEnabled = false; gpuWitnessGeneration = null
            haloView.replaceVisualPlanForDebug(visualEffectsDemoPlan(selectedFamily, selectedMode)!!)
        }
        return true
    }

    /** Fresh immutable evidence for DEBUG UI; selection alone never reports actual GPU execution. */
    fun diagnosticsSnapshot(): VisualEffectsDemoDiagnosticsSnapshot {
        val requestedBackend = when {
            gpuWitnessEnabled -> VisualEffectsDemoRequestedBackend.GPU_WITNESS
            gpuSnakeEnabled -> VisualEffectsDemoRequestedBackend.GPU_SNAKE
            gpuSupernovaEnabled -> VisualEffectsDemoRequestedBackend.GPU_SUPERNOVA
            gpuImpulseEnabled -> VisualEffectsDemoRequestedBackend.GPU_IMPULSE
            gpuPulseEnabled -> VisualEffectsDemoRequestedBackend.GPU_PULSE
            else -> VisualEffectsDemoRequestedBackend.CANVAS
        }
        val snapshot = VisualEffectsDemoDiagnosticsAdapter.snapshot(
            requestedBackend = requestedBackend,
            selectedFamily = selectedFamily,
            selectedMode = selectedMode,
            gpuWitnessGeneration = gpuWitnessGeneration,
            gpuPulseGeneration = gpuPulseGeneration,
            canvasRenderedFrames = haloView.canvasRenderedFramesForDebug(),
            canvasPreviewBaseline = canvasPreviewBaseline
        )
        return snapshot
    }

    /**
     * Starts an evidence session then restarts the real current preview path. It never creates a
     * second renderer, mock target, overlay, or temporary selection. UI may only render/copy the
     * returned immutable snapshot and poll [gpuValidationSnapshot] after normal refreshes.
     */
    fun runGpuSelfTest(): GpuValidationSessionSnapshot {
        cancelSelfTest()
        completedValidation = null
        val previousWitness = gpuWitnessEnabled
        val previousPulse = gpuPulseEnabled
        val previousSnake = gpuSnakeEnabled
        val previousImpulse = gpuImpulseEnabled
        val previousSupernova = gpuSupernovaEnabled
        // A deliberate retry owns a fresh backend generation, including a fresh failure latch.
        selectCanvasBackend()
        if (previousWitness) setGpuWitnessEnabled(true)
        if (previousPulse) setGpuPulseEnabled(true)
        if (previousSnake) setGpuSnakeEnabled(true)
        if (previousImpulse) setGpuImpulseEnabled(true)
        if (previousSupernova) setGpuSupernovaEnabled(true)
        val requestedBackend = when {
            gpuWitnessEnabled -> VisualEffectsDemoRequestedBackend.GPU_WITNESS
            gpuSnakeEnabled -> VisualEffectsDemoRequestedBackend.GPU_SNAKE
            gpuSupernovaEnabled -> VisualEffectsDemoRequestedBackend.GPU_SUPERNOVA
            gpuImpulseEnabled -> VisualEffectsDemoRequestedBackend.GPU_IMPULSE
            gpuPulseEnabled -> VisualEffectsDemoRequestedBackend.GPU_PULSE
            else -> VisualEffectsDemoRequestedBackend.CANVAS
        }
        val capability = when (requestedBackend) {
            VisualEffectsDemoRequestedBackend.GPU_SUPERNOVA -> "Admitted: GPU Supernova Baseline or exact production optical stack."
            VisualEffectsDemoRequestedBackend.GPU_IMPULSE -> "Admitted: GPU normal Impulse Baseline only."
            VisualEffectsDemoRequestedBackend.GPU_SNAKE -> "Admitted: GPU normal Snake baseline or exact production optical stack."
            VisualEffectsDemoRequestedBackend.GPU_PULSE ->
                if (selectedFamily == VisualEffectsDemoFamily.FULL_CONTOUR && selectedMode == VisualEffectsDemoMode.BASELINE) {
                    "Admitted: GPU Pulse requires Full Contour baseline."
                } else "Not admitted: GPU Pulse requires Full Contour baseline."
            VisualEffectsDemoRequestedBackend.GPU_WITNESS -> "Admitted: DEBUG RuntimeShader witness baseline."
            VisualEffectsDemoRequestedBackend.CANVAS -> "Not a GPU request; Canvas selected."
        }
        validationSession = GpuValidationSession.start(
            id = ++nextValidationSessionId,
            requestedBackend = requestedBackend,
            selectedOperation = selectedFamily.label,
            selectedMode = selectedMode.label,
            activeModifiers = selectedMode.activeModifierLabels(),
            capabilityDecision = capability,
            appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown",
            deviceModel = Build.MODEL,
            androidApi = Build.VERSION.SDK_INT,
            generation = when (requestedBackend) {
                VisualEffectsDemoRequestedBackend.GPU_PULSE, VisualEffectsDemoRequestedBackend.GPU_SNAKE, VisualEffectsDemoRequestedBackend.GPU_IMPULSE, VisualEffectsDemoRequestedBackend.GPU_SUPERNOVA -> gpuPulseGeneration
                VisualEffectsDemoRequestedBackend.GPU_WITNESS -> gpuWitnessGeneration
                VisualEffectsDemoRequestedBackend.CANVAS -> null
            }
        )
        validationDeadline = android.os.SystemClock.uptimeMillis() + 2500L
        restoreAfterValidation = {
            haloView.cancelAnimation()
            selectCanvasBackend()
            if (previousWitness) setGpuWitnessEnabled(true)
            if (previousPulse) setGpuPulseEnabled(true)
            if (previousSnake) setGpuSnakeEnabled(true)
            if (previousImpulse) setGpuImpulseEnabled(true)
            if (previousSupernova) setGpuSupernovaEnabled(true)
            restart()
        }
        restart()
        return gpuValidationSnapshot()
    }

    /** No renderer decision is read from this contract; it is presentation/copy data only. */
    fun gpuValidationSnapshot(): GpuValidationSessionSnapshot {
        completedValidation?.let { return it }
        val session = validationSession ?: return GpuValidationSession.start(
            id = 0L,
            requestedBackend = VisualEffectsDemoRequestedBackend.CANVAS,
            selectedOperation = selectedFamily.label,
            selectedMode = selectedMode.label,
            activeModifiers = selectedMode.activeModifierLabels(),
            capabilityDecision = "No self-test session has been started.",
            appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown",
            deviceModel = Build.MODEL,
            androidApi = Build.VERSION.SDK_INT,
            generation = null
        ).also { it.cancel() }.snapshot(currentPulse = null, currentWitness = null)
        val observed = session.snapshot(
            currentPulse = gpuPulseGeneration?.let(GpuPulseEvidence::snapshot),
            currentWitness = gpuWitnessGeneration?.let(GpuWitnessEvidence::snapshot)
        )
        if (observed.active && (observed.firstFailure != null || observed.gpuSubmittedFrames > 0 ||
                android.os.SystemClock.uptimeMillis() >= validationDeadline)) {
            session.complete()
            val result = session.snapshot(gpuPulseGeneration?.let(GpuPulseEvidence::snapshot), gpuWitnessGeneration?.let(GpuWitnessEvidence::snapshot))
            completedValidation = result
            val restore = restoreAfterValidation
            restoreAfterValidation = null
            restore?.invoke()
            return result
        }
        return observed
    }

    /** Report source is only the immutable observed session snapshot; clipboard remains UI-owned. */
    fun gpuValidationReport(): String = buildString {
        val current = diagnosticsSnapshot()
        appendLine("Luminote DEBUG CURRENT PREVIEW")
        appendLine("deviceModel=${Build.MODEL} androidApi=${Build.VERSION.SDK_INT} appVersion=${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")
        appendLine("generation=${current.generation} requested=${current.requestedBackend} actual=${current.actualBackend}")
        appendLine("operation=${current.selectedOperation} modifiers=${selectedMode.activeModifierLabels()} capability=${current.capabilitySummary}")
        appendLine("shaderInitialized=${current.shaderInitialized} submitted=${current.gpuSubmitted} pixelVerification=${current.pixelVerification}")
        appendLine("initializationAttempts=${current.initializationAttempts} gpuRenderAttempts=${current.gpuAttempts} gpuSubmittedFrames=${current.gpuSubmittedFrames} canvasFallbackFrames=${current.fallbackCount}")
        appendLine("fallbackReason=${current.fallbackReason.orEmpty()}")
        val pulse = if (current.requestedBackend in setOf(VisualEffectsDemoRequestedBackend.GPU_PULSE, VisualEffectsDemoRequestedBackend.GPU_SNAKE, VisualEffectsDemoRequestedBackend.GPU_IMPULSE, VisualEffectsDemoRequestedBackend.GPU_SUPERNOVA)) current.generation?.let(GpuPulseEvidence::snapshot) else null
        appendLine("geometryField=${pulse?.fieldKey} surfaceEpoch=${pulse?.surfaceEpoch}")
        pulse?.firstFailure?.let { failure ->
            appendLine("firstFailure=${failure.classification} stage=${failure.stage} type=${failure.type} message=${failure.message}")
            failure.stack.forEach(::appendLine)
        }
        appendLine("SELF-TEST (separate session; compare generation before interpreting)")
        append(gpuValidationSnapshot().toDiagnosticReport())
        appendLine()
        append(com.yp.luminote.app.rendering.gpu.ProductionRenderEvidence.report())
    }

    /** Restarts the identical motion at phase zero without changing its timing. */
    fun restart() {
        haloView.repeatAnimation(
            duration = config.durationSeconds,
            interval = config.intervalSeconds,
            count = 0,
            motion = config.motion
        )
    }

    /** Activity hosts call this before removing the view; detach disposes the renderer. */
    fun dispose() {
        validationSession?.cancel()
        haloView.cancelAnimation()
        container.removeView(haloView)
    }

    private fun applyPlan(mode: VisualEffectsDemoMode) {
        check(select(mode) is VisualEffectsDemoSelection.Applied) {
            "Every demo family must admit its baseline plan."
        }
    }
}

/** The D1 contract keeps unsupported combinations explicit instead of substituting another plan. */
internal fun supportsDemoMode(family: VisualEffectsDemoFamily, mode: VisualEffectsDemoMode): Boolean = if (mode == VisualEffectsDemoMode.PRODUCTION) {
    family == VisualEffectsDemoFamily.LUMINOUS_SEGMENT
} else if (mode == VisualEffectsDemoMode.PRODUCTION_SUPERNOVA) {
    family == VisualEffectsDemoFamily.SUPERNOVA
} else when (family) {
    VisualEffectsDemoFamily.FULL_CONTOUR,
    VisualEffectsDemoFamily.LUMINOUS_SEGMENT,
    VisualEffectsDemoFamily.SUPERNOVA -> true
    VisualEffectsDemoFamily.LIGHT_IMPULSE,
    VisualEffectsDemoFamily.FORCE_BLADE_AZURE,
    VisualEffectsDemoFamily.FORCE_BLADE_CRIMSON,
    VisualEffectsDemoFamily.FORCE_BLADE_CLASH -> mode in setOf(
        VisualEffectsDemoMode.BASELINE,
        VisualEffectsDemoMode.INTENSITY_PULSE
    )
}

internal fun visualEffectsDemoPlan(
    family: VisualEffectsDemoFamily,
    mode: VisualEffectsDemoMode
): VisualPlanSpec? {
    if (!supportsDemoMode(family, mode)) return null
    val base = HaloEffectVisualPlanMapper.map(family.config()).emitters.single()
    return VisualPlanSpec(listOf(base.copy(modifiers = mode.modifiers)))
}

private val VisualEffectsDemoMode.modifiers: List<VisualModifier>
    get() = when (this) {
        VisualEffectsDemoMode.BASELINE -> emptyList()
        VisualEffectsDemoMode.GLOW -> listOf(VisualModifier.Glow(radiusPx = 18f, alphaMultiplier = 0.75f))
        VisualEffectsDemoMode.BLUR -> listOf(VisualModifier.Blur(radiusPx = 10f))
        VisualEffectsDemoMode.INTENSITY_PULSE -> listOf(VisualModifier.IntensityPulse(amplitude = 0.55f, frequencyHz = 1.2f))
        VisualEffectsDemoMode.COMBINED -> listOf(
            VisualModifier.IntensityPulse(amplitude = 0.55f, frequencyHz = 1.2f),
            VisualModifier.Blur(radiusPx = 10f),
            VisualModifier.Glow(radiusPx = 18f, alphaMultiplier = 0.75f)
        )
        // These are intentionally not production presets. They isolate each real modifier at
        // the largest admitted optical radius (or pulse amplitude) so device captures have an
        // unmistakable paired-output witness without changing release rendering.
        VisualEffectsDemoMode.PRODUCTION -> GpuSnakeCapability.productionModifiers
        VisualEffectsDemoMode.PRODUCTION_SUPERNOVA -> GpuSupernovaCapability.productionModifiers
        VisualEffectsDemoMode.DIAGNOSTIC_GLOW -> listOf(VisualModifier.Glow(radiusPx = 64f, alphaMultiplier = 1f))
        VisualEffectsDemoMode.DIAGNOSTIC_BLUR -> listOf(VisualModifier.Blur(radiusPx = 64f))
        VisualEffectsDemoMode.DIAGNOSTIC_INTENSITY_PULSE -> listOf(
            VisualModifier.IntensityPulse(amplitude = 1f, frequencyHz = 1f)
        )
    }

internal fun unsupportedDescription(family: VisualEffectsDemoFamily, mode: VisualEffectsDemoMode): String =
    "${mode.label} is not supported for ${family.label}."

internal val VisualEffectsDemoMode.label: String
    get() = when (this) {
        VisualEffectsDemoMode.BASELINE -> "Baseline"
        VisualEffectsDemoMode.GLOW -> "Glow"
        VisualEffectsDemoMode.BLUR -> "Blur"
        VisualEffectsDemoMode.INTENSITY_PULSE -> "Intensity Pulse"
        VisualEffectsDemoMode.COMBINED -> "Full Stack"
        VisualEffectsDemoMode.PRODUCTION -> "Production Snake Stack"
        VisualEffectsDemoMode.PRODUCTION_SUPERNOVA -> "Production Supernova Stack"
        VisualEffectsDemoMode.DIAGNOSTIC_GLOW -> "Diagnostic Glow (64 px)"
        VisualEffectsDemoMode.DIAGNOSTIC_BLUR -> "Diagnostic Blur (64 px)"
        VisualEffectsDemoMode.DIAGNOSTIC_INTENSITY_PULSE -> "Diagnostic Pulse (100%)"
    }

private fun VisualEffectsDemoMode.activeModifierLabels(): List<String> = when (this) {
    VisualEffectsDemoMode.BASELINE -> emptyList()
    VisualEffectsDemoMode.GLOW, VisualEffectsDemoMode.DIAGNOSTIC_GLOW -> listOf("Glow")
    VisualEffectsDemoMode.BLUR, VisualEffectsDemoMode.DIAGNOSTIC_BLUR -> listOf("Blur")
    VisualEffectsDemoMode.INTENSITY_PULSE, VisualEffectsDemoMode.DIAGNOSTIC_INTENSITY_PULSE -> listOf("Intensity Pulse")
    VisualEffectsDemoMode.COMBINED -> listOf("Intensity Pulse", "Blur", "Glow")
    VisualEffectsDemoMode.PRODUCTION -> listOf("Intensity Pulse (.12, .75 Hz)", "Blur (2.75 px)", "Glow (7.5 px, .35)")
    VisualEffectsDemoMode.PRODUCTION_SUPERNOVA -> listOf("Intensity Pulse (.18, 1.65 Hz)", "Blur (4.5 px)", "Glow (11 px, .50)")
}
