package com.yp.luminote.app.effects

import android.content.Context
import android.graphics.Canvas
import android.util.Log
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View
import android.view.WindowInsets
import android.view.animation.LinearInterpolator

/** Bridges the overlay window lifecycle to geometry, animation and rendering. */
internal class HaloView(
    context: Context,
    config: HaloConfig
) : View(context) {
    private val outline = DisplayOutline(resources.displayMetrics.density)
    private val renderer = HaloRenderer(config, outline)
    private var animationProgress = 0f
    private var animationPhase = 0f
    private var gradientPhase = 0f
    private var ambientEffectSpeed = 1f
    private var ambientCycle = 0L
    private var pendingFiniteAnimation: FiniteAnimation? = null
    private var hasLoggedDrawFrame = false
    private var animationStartToken = 0L
    private val ambientEffectAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = AMBIENT_ROTATION_DURATION_MS
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { animator ->
            val ambientProgress = ambientCycle + animator.animatedFraction
            animationPhase = ambientPhaseStart + ambientProgress * AMBIENT_PHASE_SPAN * ambientEffectSpeed
            gradientPhase = ambientGradientPhaseStart + ambientProgress * AMBIENT_PHASE_SPAN
            animationProgress = 1f
            invalidate()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationRepeat(animation: Animator) {
                ambientCycle += 1L
            }
        })
    }
    private var ambientPhaseStart = 0f
    private var ambientGradientPhaseStart = 0f
    private val animation = HaloAnimation(
        onProgressChanged = { progress ->
            animationProgress = progress
            invalidate()
        },
        onPhaseChanged = { phase ->
            animationPhase = phase
            gradientPhase = phase
        }
    )

    fun update(config: HaloConfig) {
        renderer.update(config)
        if (ambientEffectAnimator.isStarted) {
            ambientEffectSpeed = config.effectSpeed.coerceIn(MIN_EFFECT_SPEED, MAX_EFFECT_SPEED)
        }
        invalidate()
    }

    fun repeatAnimation(
        duration: Float,
        interval: Float,
        count: Int,
        motion: com.yp.luminote.app.data.settings.HaloMotion
    ) {
        stopAmbientEffect()
        val request = FiniteAnimation(
            duration = duration,
            interval = interval,
            count = count,
            motion = motion
        )
        if (width <= 0 || height <= 0) {
            pendingFiniteAnimation = request
            return
        }
        startFiniteAnimation(request)
    }

    private fun startFiniteAnimation(request: FiniteAnimation) {
        pendingFiniteAnimation = null
        val startToken = ++animationStartToken
        hasLoggedDrawFrame = false
        post {
            if (
                startToken != animationStartToken ||
                !isAttachedToWindow ||
                width <= 0 ||
                height <= 0
            ) {
                return@post
            }
            Log.d(TAG, "Starting finite animation at ${width}x${height}")
            animation.start(
                duration = request.duration,
                interval = request.interval,
                repeat = request.count != 1,
                maxCycles = request.count.takeIf { it > 0 },
                motion = request.motion
            )
        }
    }

    fun cancelAnimation() {
        animationStartToken += 1L
        pendingFiniteAnimation = null
        stopAmbientEffect()
        animation.cancel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        outline.resize(w, h)
        requestApplyInsets()
        Log.d(TAG, "Halo view laid out at ${w}x${h}, pending=${pendingFiniteAnimation != null}")
        if (w > 0 && h > 0) {
            pendingFiniteAnimation?.let(::startFiniteAnimation)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
        if (width > 0 && height > 0) {
            pendingFiniteAnimation?.let(::startFiniteAnimation)
        }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        outline.updateInsets(insets)
        invalidate()
        return super.onApplyWindowInsets(insets)
    }

    override fun onDetachedFromWindow() {
        cancelAnimation()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (animationProgress > 0f && !hasLoggedDrawFrame) {
            hasLoggedDrawFrame = true
            Log.d(TAG, "Drawing animation frame; outlineVersion=${outline.version}")
        }
        renderer.draw(canvas, animationProgress, animationPhase, gradientPhase)
    }

    fun startAmbientEffect(effectSpeed: Float) {
        animationStartToken += 1L
        pendingFiniteAnimation = null
        animation.cancel()
        ambientPhaseStart = animationPhase
        ambientGradientPhaseStart = gradientPhase
        ambientEffectSpeed = effectSpeed.coerceIn(MIN_EFFECT_SPEED, MAX_EFFECT_SPEED)
        ambientCycle = 0L
        animationProgress = 1f
        if (!ambientEffectAnimator.isStarted) {
            ambientEffectAnimator.start()
        }
    }

    private fun stopAmbientEffect() {
        if (ambientEffectAnimator.isStarted) {
            ambientEffectAnimator.cancel()
        }
    }

    private data class FiniteAnimation(
        val duration: Float,
        val interval: Float,
        val count: Int,
        val motion: com.yp.luminote.app.data.settings.HaloMotion
    )

    private companion object {
        const val TAG = "HaloView"
        const val AMBIENT_ROTATION_DURATION_MS = 16_000L
        const val AMBIENT_PHASE_SPAN = 360f / 220f
        const val MIN_EFFECT_SPEED = 0.25f
        const val MAX_EFFECT_SPEED = 2f
    }

}
