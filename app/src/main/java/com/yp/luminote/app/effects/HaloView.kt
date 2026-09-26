package com.yp.luminote.app.effects

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.view.WindowInsets
import com.yp.luminote.app.data.settings.HaloMotion

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

    private val renderer =
        HaloRenderer(
            config,
            outline
        )

    private var animationState =
        HaloAnimationState()

    private var ambientPaused =
        false

    private var ambientEffectSpeed =
        config.effectSpeed.coerceIn(
            MIN_EFFECT_SPEED,
            MAX_EFFECT_SPEED
        )

    private var ambientMotion =
        config.motion

    private var pendingFiniteAnimation:
            FiniteAnimation? = null

    private var animationStartToken =
        0L

    private var onFiniteAnimationCompleted:
            (() -> Unit)? = null

    private var hardwareAccelerationLogged =
        false

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
        renderer.update(
            config
        )

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

    fun repeatAnimation(
        duration: Float,
        interval: Float,
        count: Int,
        motion: HaloMotion
    ) {
        stopAmbientEffect()

        val request =
            FiniteAnimation(
                duration = duration,
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

        requestApplyInsets()

        startPendingFiniteAnimationIfReady()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        requestApplyInsets()

        startPendingFiniteAnimationIfReady()
    }

    override fun onApplyWindowInsets(
        insets: WindowInsets
    ): WindowInsets {
        outline.updateInsets(
            insets
        )

        invalidate()

        return super.onApplyWindowInsets(
            insets
        )
    }

    override fun onDetachedFromWindow() {
        cancelAnimation()

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

        renderer.draw(
            canvas =
                canvas,
            animationProgress =
                animationState.progress,
            effectPhase =
                animationState.phase,
            gradientPhase =
                animationState.gradientPhase
        )
    }

    fun startAmbientEffect(
        effectSpeed: Float,
        motion: HaloMotion
    ) {
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
        val motion: HaloMotion
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
