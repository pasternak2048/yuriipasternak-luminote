package com.yp.luminote.app.effects

import android.content.Context
import android.graphics.Canvas
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
        animation.start(
            duration = duration,
            interval = interval,
            repeat = count != 1,
            maxCycles = count.takeIf { it > 0 },
            motion = motion
        )
    }

    fun cancelAnimation() {
        stopAmbientEffect()
        animation.cancel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        outline.resize(w, h)
        requestApplyInsets()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
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
        renderer.draw(canvas, animationProgress, animationPhase, gradientPhase)
    }

    fun startAmbientEffect(effectSpeed: Float) {
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

    private companion object {
        const val AMBIENT_ROTATION_DURATION_MS = 16_000L
        const val AMBIENT_PHASE_SPAN = 360f / 220f
        const val MIN_EFFECT_SPEED = 0.25f
        const val MAX_EFFECT_SPEED = 2f
    }

}
