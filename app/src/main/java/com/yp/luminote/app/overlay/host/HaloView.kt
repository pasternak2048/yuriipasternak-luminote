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
import com.yp.luminote.app.animation.LIGHT_IMPULSE_DURATION_SECONDS

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.WindowInsets
import com.yp.luminote.app.R
import com.yp.luminote.app.animation.definitions.HaloAnimationId
import java.util.Locale

internal fun finiteDurationFor(renderMode: HaloRenderMode, requestedDuration: Float): Float =
    if (renderMode == HaloRenderMode.LIGHT_IMPULSE) {
        LIGHT_IMPULSE_DURATION_SECONDS
    } else {
        requestedDuration
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
    private val renderingPipeline = HaloRenderingPipeline(CanvasRenderBackend(config, outline))

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
                duration = finiteDurationFor(renderMode, duration),
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

        Choreographer.getInstance().postFrameCallback(frameTimeSampler)

        requestApplyInsets()

        startPendingFiniteAnimationIfReady()
    }

    override fun onApplyWindowInsets(
        insets: WindowInsets
    ): WindowInsets {
        outline.updateInsets(
            insets
        )

        renderingPipeline.invalidateSurface()

        invalidate()

        return super.onApplyWindowInsets(
            insets
        )
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameTimeSampler)

        cancelAnimation()

        renderingPipeline.dispose()

        super.onDetachedFromWindow()
    }

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
        renderFrame.animationProgress = animationState.progress
        renderFrame.effectPhase = animationState.phase
        renderFrame.gradientPhase = animationState.gradientPhase
        renderFrame.frameTimeNanos = lastFrameTimeNanos
        renderFrame.viewportWidth = width
        renderFrame.viewportHeight = height
        if (staticFrameMode) {
            renderFrame.calibrationRulerLegend = resources.getString(R.string.calibration_ruler_legend)
            renderFrame.calibrationRegistrationLabel = localizedCalibrationRegistrationLabel()
        }
        val outcome = canvasTarget.withCanvas(canvas) {
            renderingPipeline.render(canvasTarget, renderFrame)
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

    private companion object {
        const val MIN_EFFECT_SPEED =
            0.25f

        const val MAX_EFFECT_SPEED =
            2f

        const val TAG =
            "HaloRender"
    }
}
