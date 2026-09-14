package com.yp.luminote.app.effects

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import com.yp.luminote.app.data.settings.HaloMotion
import kotlin.math.max
import kotlin.math.min

class HaloAnimation(
    private val onProgressChanged: (Float) -> Unit,
    private val onPhaseChanged: (Float) -> Unit = {},
    private val shouldContinueRepeating: (() -> Boolean)? = null
) {
    private val handler = Handler(Looper.getMainLooper())
    private val fadeInInterpolator = DecelerateInterpolator()
    private val fadeOutInterpolator = AccelerateInterpolator()
    private val restartGlowTask = Runnable {
        if (repeat) startGlow()
    }

    /*
     * The same animator is reused for notification effects and preview cycles.
     * Rebuilding keyframes and a ValueAnimator for each cycle produced avoidable
     * short-lived allocations while the preview was visible.
     */
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        addUpdateListener { valueAnimator ->
            lastPhaseFraction = valueAnimator.animatedFraction
            onPhaseChanged(phaseOffset + lastPhaseFraction)
            onProgressChanged(alphaFor(valueAnimator.animatedFraction))
        }
        addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onProgressChanged(0f)
                    phaseOffset += lastPhaseFraction

                    if (!repeat) return
                    remainingCycles?.let { cycles ->
                        remainingCycles = cycles - 1
                        if (cycles <= 1) { repeat = false; return }
                    }
                    if (shouldContinueRepeating?.invoke() == false) {
                        repeat = false
                        return
                    }

                    if (intervalMs > 0L) {
                        handler.postDelayed(restartGlowTask, intervalMs)
                    } else {
                        startGlow()
                    }
                }
            }
        )
    }

    private var repeat = false
    private var intervalMs = 0L
    private var remainingCycles: Int? = null
    private var phaseOffset = 0f
    private var lastPhaseFraction = 0f
    private var currentDuration = DEFAULT_DURATION
    private var fadeInFraction = 0f
    private var fadeOutStartFraction = 1f
    private var strategy: HaloAnimationStrategy = HaloAnimationStrategies.forMotion(HaloMotion.PULSE)

    fun start(
        duration: Float,
        interval: Float = 0f,
        repeat: Boolean = false,
        maxCycles: Int? = null,
        preservePhase: Boolean = false,
        motion: HaloMotion = HaloMotion.PULSE
    ) {
        cancel()

        if (!preservePhase) {
            phaseOffset = 0f
        }

        currentDuration = duration.takeIf { it.isFinite() && it > 0f }
            ?: DEFAULT_DURATION
        intervalMs = if (repeat && interval.isFinite() && interval > 0f) {
            (interval * 1000f).toLong().coerceAtLeast(0L)
        } else {
            0L
        }
        this.repeat = repeat
        remainingCycles = maxCycles
        strategy = HaloAnimationStrategies.forMotion(motion)

        startGlow()
    }

    private fun startGlow() {
        val totalDuration = max(MIN_DURATION_MS, (currentDuration * 1000f).toLong())
        val envelope = strategy.envelope(totalDuration)
        val fadeInDuration = min(envelope.fadeInMs, totalDuration / 3)
        val fadeOutDuration = min(envelope.fadeOutMs, totalDuration / 3)
        val holdDuration = max(0L, totalDuration - fadeInDuration - fadeOutDuration)

        fadeInFraction = fadeInDuration.toFloat() / totalDuration
        fadeOutStartFraction = (fadeInDuration + holdDuration).toFloat() / totalDuration

        onProgressChanged(0f)
        lastPhaseFraction = 0f
        animator.duration = totalDuration
        animator.start()
    }

    private fun alphaFor(fraction: Float): Float {
        val safeFraction = fraction.coerceIn(0f, 1f)
        val progress = when {
            safeFraction < fadeInFraction && fadeInFraction > 0f ->
                fadeInInterpolator.getInterpolation(safeFraction / fadeInFraction)

            safeFraction < fadeOutStartFraction -> 1f

            fadeOutStartFraction < 1f -> {
                val fadeOutProgress =
                    (safeFraction - fadeOutStartFraction) / (1f - fadeOutStartFraction)
                1f - fadeOutInterpolator.getInterpolation(fadeOutProgress)
            }

            else -> 0f
        }

        return if (progress.isFinite()) {
            progress.coerceIn(0f, 1f)
        } else {
            0f
        }
    }

    fun cancel() {
        repeat = false
        remainingCycles = null
        handler.removeCallbacksAndMessages(null)
        animator.cancel()
        onProgressChanged(0f)
    }

    companion object {
        const val MIN_DURATION_MS = 600L
        private const val DEFAULT_DURATION = 2.5f
    }
}
