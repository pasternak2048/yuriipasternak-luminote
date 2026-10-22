package com.yp.luminote.app.overlay.host
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.effects.model.HaloRenderMode
import com.yp.luminote.app.animation.HaloAnimation
import com.yp.luminote.app.animation.HaloAnimationEngine
import com.yp.luminote.app.animation.HaloAnimationState
import com.yp.luminote.app.animation.HaloAnimationRequest
import com.yp.luminote.app.animation.HaloAmbientAnimationRequest
import com.yp.luminote.app.rendering.HaloRenderingPipeline
import com.yp.luminote.app.rendering.api.RenderFrame
import com.yp.luminote.app.rendering.api.RenderMode
import com.yp.luminote.app.rendering.api.RenderOutcome
import com.yp.luminote.app.rendering.canvas.CanvasRenderBackend
import com.yp.luminote.app.rendering.canvas.CanvasRenderTarget
import com.yp.luminote.app.rendering.canvas.CalibrationDiagnostics
import com.yp.luminote.app.rendering.gpu.DebugGpuFallbackBackend
import com.yp.luminote.app.rendering.gpu.GpuWitnessDiagnostics
import com.yp.luminote.app.rendering.gpu.GpuWitnessRenderBackend
import com.yp.luminote.app.rendering.gpu.GpuPulseRenderBackend
import com.yp.luminote.app.rendering.gpu.GpuSnakeRenderBackend
import com.yp.luminote.app.rendering.gpu.GpuSupernovaRenderBackend
import com.yp.luminote.app.rendering.gpu.GpuImpulseRenderBackend
import com.yp.luminote.app.animation.plan.VisualPlanSpec
import com.yp.luminote.app.animation.LIGHT_IMPULSE_DURATION_SECONDS
import com.yp.luminote.app.animation.definitions.HaloEffectSpec

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.WindowInsets
import com.yp.luminote.app.R
import com.yp.luminote.app.animation.definitions.HaloAnimationId
import java.util.Locale
import com.yp.luminote.app.data.settings.RenderingEngine
import com.yp.luminote.app.data.settings.LuminoteSettingsRepository
import com.yp.luminote.app.rendering.gpu.ProductionRenderBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

internal fun finiteDurationFor(renderMode: HaloRenderMode, requestedDuration: Float): Float =
    if (renderMode == HaloRenderMode.LIGHT_IMPULSE) {
        LIGHT_IMPULSE_DURATION_SECONDS
    } else {
        requestedDuration
    }

/**
 * Reminder lifecycle remains Light Impulse, while its resolved visual plan may
 * own a shorter finite timeline.
 */
internal fun finiteDurationFor(
    renderMode: HaloRenderMode,
    effectPlan: HaloEffectSpec,
    requestedDuration: Float
): Float =
    if (effectPlan is HaloEffectSpec.Supernova) {
        requestedDuration
    } else {
        finiteDurationFor(renderMode, requestedDuration)
    }

/**
 * Bridges the overlay window lifecycle to geometry, animation and rendering.
 *
 * It is instantiated programmatically by overlay and accessibility services, so XML constructors
 * are intentionally omitted.
 */
@SuppressLint("ViewConstructor")
internal class HaloView(
    context: Context,
    config: HaloConfig
) : View(context) {

    private val outline =
        DisplayOutline(
            resources.displayMetrics.density
        )

    /** Composition remains at the Android host; animation remains backend-neutral. */
    private var canvasRenderBackend = CanvasRenderBackend(config, outline)

    private var renderingPipeline = HaloRenderingPipeline(ProductionRenderBackend(config, outline, RenderingEngine.CANVAS, canvasRenderBackend))
    private var productionEngine = RenderingEngine.CANVAS
    private var preferenceScope: CoroutineScope? = null
    private var preferenceEpoch = 0L
    private var debugHost = false
    private var pipelineDisposed = false

    private fun isolateDebugHost() {
        if (debugHost) return
        debugHost = true
        preferenceEpoch++
        preferenceScope?.cancel(); preferenceScope = null
        renderingPipeline.dispose()
        canvasRenderBackend = CanvasRenderBackend(currentConfig, outline)
        renderingPipeline = HaloRenderingPipeline(canvasRenderBackend)
        pipelineDisposed = false
    }

    private fun switchProductionEngine(engine: RenderingEngine, force: Boolean = false) {
        if (debugHost || (!force && productionEngine == engine)) return
        renderingPipeline.dispose()
        canvasRenderBackend = CanvasRenderBackend(currentConfig, outline)
        renderingPipeline = HaloRenderingPipeline(ProductionRenderBackend(currentConfig, outline, engine, canvasRenderBackend))
        productionEngine = engine
        pipelineDisposed = false
        invalidate()
    }

    private var gpuWitnessEnabledForDebug = false
    private var gpuPulseEnabledForDebug = false
    private var gpuSnakeEnabledForDebug = false
    private var gpuSupernovaEnabledForDebug = false
    private var gpuImpulseEnabledForDebug = false

    /** Retained only for the debug demo so a backend recreation cannot discard selected input. */
    private var debugVisualPlan: VisualPlanSpec? = null
    private var debugFrozenFrame: DebugFrozenFrame? = null

    private val renderFrame = RenderFrame()

    private val canvasTarget = CanvasRenderTarget()

    private var currentConfig = config.sanitized()

    private var animationState =
        HaloAnimationState()

    private var ambientPaused =
        false

    private var staticFrameMode = false

    private var ambientEffectSpeed =
        config.effectSpeed.coerceIn(
            MIN_EFFECT_SPEED,
            MAX_EFFECT_SPEED
        )

    private var ambientMotion =
        config.motion

    private var renderMode = config.renderMode

    private var pendingFiniteAnimation:
            FiniteAnimation? = null

    private var animationStartToken =
        0L

    private var onFiniteAnimationCompleted:
            (() -> Unit)? = null

    private var onFiniteAnimationStarted:
            (() -> Unit)? = null

    private var hardwareAccelerationLogged =
        false

    /* The rendering boundary receives a host-owned VSYNC timestamp, never a renderer clock. */
    private var lastFrameTimeNanos = 0L

    private val frameTimeSampler = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            lastFrameTimeNanos = frameTimeNanos
            if (isAttachedToWindow) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    private val animationEngine:
            HaloAnimationEngine =
        HaloAnimation(
            onFrame = { state ->
                animationState =
                    state

                invalidate()
            },
            onCompleted = {
                if (
                    !animationState.ambient
                ) {
                    onFiniteAnimationCompleted
                        ?.invoke()
                }
            }
        )

    fun update(
        config: HaloConfig
    ) {
        currentConfig = config.sanitized()
        renderingPipeline.updateConfig(config)
        renderMode = config.renderMode

        ambientEffectSpeed =
            config.effectSpeed.coerceIn(
                MIN_EFFECT_SPEED,
                MAX_EFFECT_SPEED
            )

        if (
            animationState.running &&
            animationState.ambient
        ) {
            animationEngine
                .updateAmbientEffectSpeed(
                    ambientEffectSpeed
                )
        }

        invalidate()
    }

    /**
     * Host-only visual-plan substitution used by the debug visual-effects demo.
     * It retains this view's production Canvas backend, geometry, and animation
     * lifecycle; callers supply only an already-admitted visual plan.
     */
    internal fun replaceVisualPlanForDebug(plan: VisualPlanSpec) {
        isolateDebugHost()
        debugVisualPlan = plan
        renderingPipeline.updateDebugVisualPlan(plan)
        invalidate()
    }

    /** Device-test-only frame override; it borrows the host frame contract and owns no clock. */
    internal fun setFrozenRenderFrameForDebug(frame: DebugFrozenFrame?) {
        debugFrozenFrame = frame
        invalidate()
    }

    /**
     * DEBUG-only selector for the Gate A RuntimeShader witness. It replaces neither this host's
     * VSYNC/animation ownership nor DisplayOutline. Release builds reject the request.
     */
    internal fun setGpuWitnessEnabledForDebug(enabled: Boolean): Boolean {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
        isolateDebugHost()
        if (gpuWitnessEnabledForDebug == enabled) return true
        renderingPipeline.dispose()
        canvasRenderBackend = CanvasRenderBackend(currentConfig, outline)
        renderingPipeline = if (enabled) {
            val gpuWitnessBackend = GpuWitnessRenderBackend(outline)
            HaloRenderingPipeline(
                DebugGpuFallbackBackend(
                    gpu = gpuWitnessBackend,
                    canvas = canvasRenderBackend,
                    initialConfig = currentConfig,
                    gpuGeneration = gpuWitnessBackend.generationForDebugEvidence()
                )
            )
        } else {
            HaloRenderingPipeline(canvasRenderBackend)
        }
        gpuWitnessEnabledForDebug = enabled
        if (enabled) { gpuPulseEnabledForDebug = false; gpuSnakeEnabledForDebug = false; gpuImpulseEnabledForDebug = false; gpuSupernovaEnabledForDebug = false }
        debugVisualPlan?.let(renderingPipeline::updateDebugVisualPlan)
        GpuWitnessDiagnostics.record("selection", if (enabled) "requested=GPU actual=pending" else "requested=Canvas actual=Canvas")
        invalidate()
        return true
    }

    /** Gate B's independent DEBUG selector. It admits only the narrow GPU Pulse capability. */
    internal fun setGpuPulseEnabledForDebug(enabled: Boolean): Boolean {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
        isolateDebugHost()
        if (gpuPulseEnabledForDebug == enabled) return true
        renderingPipeline.dispose()
        canvasRenderBackend = CanvasRenderBackend(currentConfig, outline)
        renderingPipeline = if (enabled) {
            val gpuPulseBackend = GpuPulseRenderBackend(outline)
            HaloRenderingPipeline(DebugGpuFallbackBackend(
                gpu = gpuPulseBackend,
                canvas = canvasRenderBackend,
                initialConfig = currentConfig,
                onCanvasFallback = { reason, canvasRendered ->
                    com.yp.luminote.app.rendering.gpu.GpuPulseEvidence.recordFallback(
                        gpuPulseBackend.generationForDebugEvidence(),
                        reason,
                        canvasRendered
                    )
                    com.yp.luminote.app.rendering.gpu.GpuPulseEvidence.recordCanvasPulse(gpuPulseBackend.generationForDebugEvidence())
                }
            ))
        } else HaloRenderingPipeline(canvasRenderBackend)
        gpuPulseEnabledForDebug = enabled
        if (enabled) { gpuWitnessEnabledForDebug = false; gpuSnakeEnabledForDebug = false; gpuImpulseEnabledForDebug = false; gpuSupernovaEnabledForDebug = false }
        debugVisualPlan?.let(renderingPipeline::updateDebugVisualPlan)
        GpuWitnessDiagnostics.record("pulse-selection", if (enabled) "requested=GPU actual=pending" else "requested=Canvas actual=Canvas")
        invalidate()
        return true
    }

    /** Gate C selection uses the existing host/pipeline and leaves production settings untouched. */
    internal fun setGpuSnakeEnabledForDebug(enabled: Boolean): Boolean {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
        isolateDebugHost()
        if (gpuSnakeEnabledForDebug == enabled) return true
        renderingPipeline.dispose()
        canvasRenderBackend = CanvasRenderBackend(currentConfig, outline)
        renderingPipeline = if (enabled) {
            val gpu = GpuSnakeRenderBackend(outline)
            HaloRenderingPipeline(DebugGpuFallbackBackend(gpu,canvasRenderBackend,currentConfig,
                onCanvasFallback = { reason, rendered ->
                    com.yp.luminote.app.rendering.gpu.GpuPulseEvidence.recordFallback(gpu.generationForDebugEvidence(),reason,rendered)
                }))
        } else HaloRenderingPipeline(canvasRenderBackend)
        gpuSnakeEnabledForDebug = enabled
        if (enabled) { gpuWitnessEnabledForDebug = false; gpuPulseEnabledForDebug = false; gpuImpulseEnabledForDebug = false; gpuSupernovaEnabledForDebug = false }
        debugVisualPlan?.let(renderingPipeline::updateDebugVisualPlan)
        invalidate()
        return true
    }

    /** Gate D1 normal Impulse source, separate from the dedicated reminder route. */
    internal fun setGpuImpulseEnabledForDebug(enabled: Boolean): Boolean {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
        isolateDebugHost()
        if (gpuImpulseEnabledForDebug == enabled) return true
        renderingPipeline.dispose()
        canvasRenderBackend = CanvasRenderBackend(currentConfig, outline)
        renderingPipeline = if (enabled) {
            val gpu = GpuImpulseRenderBackend(outline)
            HaloRenderingPipeline(DebugGpuFallbackBackend(gpu, canvasRenderBackend, currentConfig,
                onCanvasFallback = { reason, rendered ->
                    com.yp.luminote.app.rendering.gpu.GpuPulseEvidence.recordFallback(gpu.generationForDebugEvidence(), reason, rendered)
                }))
        } else HaloRenderingPipeline(canvasRenderBackend)
        gpuImpulseEnabledForDebug = enabled
        if (enabled) { gpuWitnessEnabledForDebug = false; gpuPulseEnabledForDebug = false; gpuSnakeEnabledForDebug = false; gpuSupernovaEnabledForDebug = false }
        debugVisualPlan?.let(renderingPipeline::updateDebugVisualPlan)
        invalidate()
        return true
    }

    /** Gate E Supernova source and exact optical stack. */
    internal fun setGpuSupernovaEnabledForDebug(enabled: Boolean): Boolean {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
        isolateDebugHost()
        if (gpuSupernovaEnabledForDebug == enabled) return true
        renderingPipeline.dispose()
        canvasRenderBackend = CanvasRenderBackend(currentConfig, outline)
        renderingPipeline = if (enabled) {
            val gpu = GpuSupernovaRenderBackend(outline)
            HaloRenderingPipeline(DebugGpuFallbackBackend(gpu, canvasRenderBackend, currentConfig,
                onCanvasFallback = { reason, rendered ->
                    com.yp.luminote.app.rendering.gpu.GpuPulseEvidence.recordFallback(gpu.generationForDebugEvidence(), reason, rendered)
                }))
        } else HaloRenderingPipeline(canvasRenderBackend)
        gpuSupernovaEnabledForDebug = enabled
        if (enabled) { gpuWitnessEnabledForDebug = false; gpuPulseEnabledForDebug = false; gpuSnakeEnabledForDebug = false; gpuImpulseEnabledForDebug = false }
        debugVisualPlan?.let(renderingPipeline::updateDebugVisualPlan)
        invalidate()
        return true
    }

    fun setOnFiniteAnimationCompletedListener(
        listener: (() -> Unit)?
    ) {
        onFiniteAnimationCompleted =
            listener
    }

    fun setOnFiniteAnimationStartedListener(
        listener: (() -> Unit)?
    ) {
        onFiniteAnimationStarted =
            listener
    }

    fun repeatAnimation(
        duration: Float,
        interval: Float,
        count: Int,
        motion: HaloAnimationId
    ) {
        staticFrameMode = false
        stopAmbientEffect()

        val request =
            FiniteAnimation(
                duration = finiteDurationFor(renderMode, currentConfig.effectPlan, duration),
                interval = interval,
                count = count,
                motion = motion
            )

        if (
            !isAttachedToWindow ||
            width <= 0 ||
            height <= 0
        ) {
            pendingFiniteAnimation =
                request

            post(
                ::startPendingFiniteAnimationIfReady
            )

            return
        }

        startFiniteAnimation(
            request
        )
    }

    private fun startFiniteAnimation(
        request: FiniteAnimation
    ) {
        ambientPaused =
            false

        val startToken =
            ++animationStartToken

        post {
            if (
                startToken != animationStartToken
            ) {
                return@post
            }

            if (
                !isAttachedToWindow ||
                width <= 0 ||
                height <= 0
            ) {
                pendingFiniteAnimation =
                    request

                return@post
            }

            pendingFiniteAnimation =
                null

            animationEngine.start(
                HaloAnimationRequest(
                    duration =
                        request.duration,
                    interval =
                        request.interval,
                    repeat =
                        request.count != 1,
                    maxCycles =
                        request.count
                            .takeIf {
                                it > 0
                            },
                    preservePhase =
                        false,
                    motion =
                        request.motion
                )
            )

            onFiniteAnimationStarted?.invoke()
        }
    }

    private fun startPendingFiniteAnimationIfReady() {
        if (
            !isAttachedToWindow ||
            width <= 0 ||
            height <= 0
        ) {
            return
        }

        pendingFiniteAnimation
            ?.let(
                ::startFiniteAnimation
            )
    }

    fun cancelAnimation() {
        animationStartToken++

        pendingFiniteAnimation =
            null

        ambientPaused =
            false

        animationEngine.cancel()

    }

    /** Calibration has no timer, finite callback, or motion envelope. */
    fun showStaticFrame() {
        animationStartToken++
        pendingFiniteAnimation = null
        ambientPaused = false
        animationEngine.cancel()
        staticFrameMode = true
        invalidate()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int
    ) {
        super.onSizeChanged(
            w,
            h,
            oldw,
            oldh
        )

        outline.resize(
            w,
            h
        )

        renderingPipeline.invalidateSurface()

        requestApplyInsets()

        startPendingFiniteAnimationIfReady()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        if (!debugHost) {
            if (pipelineDisposed) switchProductionEngine(productionEngine, force = true)
            val epoch = ++preferenceEpoch
            preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
                scope.launch {
                    LuminoteSettingsRepository(context.applicationContext).settings
                        .map { it.renderingEngine }.distinctUntilChanged()
                        .catch { error ->
                            Log.e(TAG, "Rendering preference unavailable; retaining Canvas", error)
                            emit(RenderingEngine.CANVAS)
                        }.collect { engine ->
                            if (epoch == preferenceEpoch && isAttachedToWindow && !debugHost) switchProductionEngine(engine)
                        }
                }
            }
        } else if (pipelineDisposed) {
            pipelineDisposed = false
            when {
                gpuWitnessEnabledForDebug -> { gpuWitnessEnabledForDebug = false; setGpuWitnessEnabledForDebug(true) }
                gpuPulseEnabledForDebug -> { gpuPulseEnabledForDebug = false; setGpuPulseEnabledForDebug(true) }
                gpuSnakeEnabledForDebug -> { gpuSnakeEnabledForDebug = false; setGpuSnakeEnabledForDebug(true) }
                gpuImpulseEnabledForDebug -> { gpuImpulseEnabledForDebug = false; setGpuImpulseEnabledForDebug(true) }
                gpuSupernovaEnabledForDebug -> { gpuSupernovaEnabledForDebug = false; setGpuSupernovaEnabledForDebug(true) }
                else -> {
                    canvasRenderBackend = CanvasRenderBackend(currentConfig, outline)
                    renderingPipeline = HaloRenderingPipeline(canvasRenderBackend)
                    debugVisualPlan?.let(renderingPipeline::updateDebugVisualPlan)
                }
            }
        }

        Choreographer.getInstance().postFrameCallback(frameTimeSampler)

        requestApplyInsets()

        startPendingFiniteAnimationIfReady()
    }

    override fun onApplyWindowInsets(
        insets: WindowInsets
    ): WindowInsets {
        val previousOutlineVersion = outline.version
        outline.updateInsets(
            insets
        )

        if (outline.version != previousOutlineVersion) renderingPipeline.invalidateSurface()

        invalidate()

        return super.onApplyWindowInsets(
            insets
        )
    }

    override fun onDetachedFromWindow() {
        preferenceEpoch++
        preferenceScope?.cancel(); preferenceScope = null
        Choreographer.getInstance().removeFrameCallback(frameTimeSampler)

        cancelAnimation()

        renderingPipeline.dispose()
        pipelineDisposed = true

        super.onDetachedFromWindow()
    }

    private var debugCanvasRenderedFrames = 0L
    internal fun canvasRenderedFramesForDebug(): Long = debugCanvasRenderedFrames

    override fun onDraw(
        canvas: Canvas
    ) {
        super.onDraw(
            canvas
        )

        logHardwareAccelerationOnce(
            canvas
        )

        renderFrame.mode = if (staticFrameMode) RenderMode.STATIC_CALIBRATION else RenderMode.ANIMATION
        val frozen = debugFrozenFrame
        renderFrame.animationProgress = frozen?.progress ?: animationState.progress
        renderFrame.effectPhase = frozen?.phase ?: animationState.phase
        renderFrame.gradientPhase = frozen?.gradientPhase ?: animationState.gradientPhase
        renderFrame.frameTimeNanos = frozen?.frameTimeNanos ?: lastFrameTimeNanos
        renderFrame.viewportWidth = width
        renderFrame.viewportHeight = height
        renderFrame.intent = if (gpuWitnessEnabledForDebug) {
            com.yp.luminote.app.rendering.api.RenderIntent.DEBUG_GPU_WITNESS
        } else if (gpuSupernovaEnabledForDebug) {
            com.yp.luminote.app.rendering.api.RenderIntent.DEBUG_GPU_SUPERNOVA
        } else if (gpuImpulseEnabledForDebug) {
            com.yp.luminote.app.rendering.api.RenderIntent.DEBUG_GPU_IMPULSE
        } else if (gpuSnakeEnabledForDebug) {
            com.yp.luminote.app.rendering.api.RenderIntent.DEBUG_GPU_SNAKE
        } else if (gpuPulseEnabledForDebug) {
            com.yp.luminote.app.rendering.api.RenderIntent.DEBUG_GPU_PULSE
        } else {
            com.yp.luminote.app.rendering.api.RenderIntent.PRODUCTION
        }
        if (staticFrameMode) {
            renderFrame.calibrationRulerLegend = resources.getString(R.string.calibration_ruler_legend)
            renderFrame.calibrationRegistrationLabel = localizedCalibrationRegistrationLabel()
        }
        val outcome = canvasTarget.withCanvas(canvas) {
            renderingPipeline.render(canvasTarget, renderFrame)
        }
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            !gpuWitnessEnabledForDebug && !gpuPulseEnabledForDebug && !gpuSnakeEnabledForDebug && !gpuImpulseEnabledForDebug && !gpuSupernovaEnabledForDebug && outcome == RenderOutcome.Rendered) {
            debugCanvasRenderedFrames++
        }
        when (outcome) {
            is RenderOutcome.Error -> Log.e(TAG, "Render backend failed", outcome.cause)
            is RenderOutcome.Unsupported -> Log.w(TAG, "Render backend unsupported: ${outcome.reason}")
            RenderOutcome.Rendered -> Unit
        }
    }

    private fun localizedCalibrationRegistrationLabel(): String {
        val edgeCalibrationPx = outline.dpToPx(currentConfig.edgeCalibrationDp)
        val magnitude = String.format(
            resources.configuration.locales[0],
            "%.1f",
            CalibrationDiagnostics.registrationMagnitudePx(edgeCalibrationPx)
        )
        return when (CalibrationDiagnostics.registration(edgeCalibrationPx)) {
            CalibrationDiagnostics.Registration.ZERO -> resources.getString(R.string.calibration_registration_zero)
            CalibrationDiagnostics.Registration.IN -> resources.getString(R.string.calibration_registration_in, magnitude)
            CalibrationDiagnostics.Registration.OUT -> resources.getString(R.string.calibration_registration_out, magnitude)
        }
    }

    fun startAmbientEffect(
        effectSpeed: Float,
        motion: HaloAnimationId
    ) {
        staticFrameMode = false
        animationStartToken++

        pendingFiniteAnimation =
            null

        ambientPaused =
            false

        ambientEffectSpeed =
            effectSpeed.coerceIn(
                MIN_EFFECT_SPEED,
                MAX_EFFECT_SPEED
            )

        ambientMotion =
            motion

        animationEngine.startAmbient(
            HaloAmbientAnimationRequest(
                effectSpeed =
                    ambientEffectSpeed,
                phaseStart =
                    animationState.phase,
                gradientPhaseStart =
                    animationState.gradientPhase,
                motion = ambientMotion
            )
        )
    }

    fun pauseAmbientEffect() {
        if (
            !animationState.running ||
            !animationState.ambient
        ) {
            return
        }

        val pausedState =
            animationState.copy(
                progress = 1f,
                running = false,
                ambient = true
            )

        animationEngine.cancel()

        animationState =
            pausedState

        ambientPaused =
            true

        invalidate()
    }

    fun resumeAmbientEffect() {
        if (
            !ambientPaused ||
            animationState.running
        ) {
            return
        }

        ambientPaused =
            false

        animationEngine.startAmbient(
            HaloAmbientAnimationRequest(
                effectSpeed =
                    ambientEffectSpeed,
                phaseStart =
                    animationState.phase,
                gradientPhaseStart =
                    animationState.gradientPhase,
                motion = ambientMotion
            )
        )
    }

    private fun stopAmbientEffect() {
        if (
            animationState.ambient ||
            ambientPaused
        ) {
            animationEngine.cancel()
        }

        ambientPaused =
            false
    }

    private fun logHardwareAccelerationOnce(
        canvas: Canvas
    ) {
        if (
            hardwareAccelerationLogged
        ) {
            return
        }

        hardwareAccelerationLogged =
            true

        Log.d(
            TAG,
            "Render pipeline: " +
                    "viewHw=$isHardwareAccelerated, " +
                    "canvasHw=${canvas.isHardwareAccelerated}, " +
                    "attached=$isAttachedToWindow"
        )

        if (
            !canvas.isHardwareAccelerated
        ) {
            Log.w(
                TAG,
                "Halo is rendering on a software Canvas"
            )
        }
    }

    private data class FiniteAnimation(
        val duration: Float,
        val interval: Float,
        val count: Int,
        val motion: HaloAnimationId
    )

    internal data class DebugFrozenFrame(
        val progress: Float,
        val phase: Float,
        val gradientPhase: Float,
        val frameTimeNanos: Long
    )

    private companion object {
        const val MIN_EFFECT_SPEED =
            0.25f

        const val MAX_EFFECT_SPEED =
            2f

        const val TAG =
            "HaloRender"
    }
}
