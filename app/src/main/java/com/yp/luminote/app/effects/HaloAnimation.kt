package com.yp.luminote.app.effects

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import com.yp.luminote.app.data.settings.HaloMotion
import kotlin.math.max
import kotlin.math.min

class HaloAnimation(
    private val onProgressChanged: (Float) -> Unit,
    private val onPhaseChanged: (Float) -> Unit = {},
    private val shouldContinueRepeating: (() -> Boolean)? = null,
    private val onCompleted: () -> Unit = {}
) {
    private val handler = Handler(Looper.getMainLooper())

    private val fadeInInterpolator = DecelerateInterpolator()
    private val fadeOutInterpolator = AccelerateInterpolator()

    private var running = false
    private var repeat = false

    private var intervalMs = 0L
    private var remainingCycles: Int? = null

    private var phaseOffset = 0f
    private var lastPhaseFraction = 0f

    private var currentDuration = DEFAULT_DURATION
    private var currentDurationMs = DEFAULT_DURATION_MS

    private var fadeInFraction = 0f
    private var fadeOutStartFraction = 1f

    private var cycleStartedAt = 0L

    /*
     * Incremented whenever the current animation is cancelled/replaced.
     *
     * Every scheduled frame/restart captures the current generation so a
     * callback belonging to an older animation cannot affect a newer one.
     */
    private var generation = 0L

    private var strategy: HaloAnimationStrategy =
        HaloAnimationStrategies.forMotion(HaloMotion.PULSE)

    fun start(
        duration: Float,
        interval: Float = 0f,
        repeat: Boolean = false,
        maxCycles: Int? = null,
        preservePhase: Boolean = false,
        motion: HaloMotion = HaloMotion.PULSE
    ) {
        val previousPhase = phaseOffset

        cancel()

        phaseOffset =
            if (preservePhase) {
                previousPhase
            } else {
                0f
            }

        currentDuration =
            duration.takeIf {
                it.isFinite() && it > 0f
            } ?: DEFAULT_DURATION

        currentDurationMs =
            max(
                MIN_DURATION_MS,
                (currentDuration * 1000f).toLong()
            )

        intervalMs =
            if (
                repeat &&
                interval.isFinite() &&
                interval > 0f
            ) {
                (interval * 1000f)
                    .toLong()
                    .coerceAtLeast(0L)
            } else {
                0L
            }

        this.repeat = repeat
        remainingCycles = maxCycles

        strategy =
            HaloAnimationStrategies.forMotion(motion)

        running = true

        startGlow()
    }

    private fun startGlow() {
        if (!running) {
            return
        }

        val envelope =
            strategy.envelope(currentDurationMs)

        val fadeInDuration =
            min(
                envelope.fadeInMs,
                currentDurationMs / 3
            )

        val fadeOutDuration =
            min(
                envelope.fadeOutMs,
                currentDurationMs / 3
            )

        val holdDuration =
            max(
                0L,
                currentDurationMs -
                        fadeInDuration -
                        fadeOutDuration
            )

        fadeInFraction =
            fadeInDuration.toFloat() /
                    currentDurationMs.toFloat()

        fadeOutStartFraction =
            (fadeInDuration + holdDuration)
                .toFloat() /
                    currentDurationMs.toFloat()

        lastPhaseFraction = 0f

        onProgressChanged(0f)
        onPhaseChanged(phaseOffset)

        cycleStartedAt =
            SystemClock.elapsedRealtime()

        val frameGeneration = generation

        scheduleFrame(
            frameGeneration = frameGeneration,
            delayMs = 0L
        )
    }

    private fun scheduleFrame(
        frameGeneration: Long,
        delayMs: Long = FRAME_DELAY_MS
    ) {
        handler.postDelayed(
            {
                runFrame(frameGeneration)
            },
            delayMs
        )
    }

    private fun runFrame(frameGeneration: Long) {
        /*
         * Ignore callbacks belonging to an animation that has already been
         * cancelled or replaced.
         */
        if (
            !running ||
            generation != frameGeneration
        ) {
            return
        }

        val now =
            SystemClock.elapsedRealtime()

        val elapsed =
            (now - cycleStartedAt)
                .coerceAtLeast(0L)

        val fraction =
            (
                    elapsed.toFloat() /
                            currentDurationMs.toFloat()
                    )
                .coerceIn(0f, 1f)

        lastPhaseFraction = fraction

        onPhaseChanged(
            phaseOffset + fraction
        )

        onProgressChanged(
            alphaFor(fraction)
        )

        if (fraction >= 1f) {
            finishCycle()
            return
        }

        scheduleFrame(
            frameGeneration = frameGeneration
        )
    }

    private fun finishCycle() {
        if (!running) {
            return
        }

        /*
         * Force the final visual state before completing the cycle.
         */
        lastPhaseFraction = 1f

        onPhaseChanged(
            phaseOffset + 1f
        )

        onProgressChanged(0f)

        phaseOffset += 1f
        lastPhaseFraction = 0f

        if (!repeat) {
            finishAnimation()
            return
        }

        remainingCycles?.let { cycles ->
            remainingCycles = cycles - 1

            if (cycles <= 1) {
                repeat = false
                finishAnimation()
                return
            }
        }

        if (shouldContinueRepeating?.invoke() == false) {
            repeat = false
            finishAnimation()
            return
        }

        if (intervalMs <= 0L) {
            startGlow()
            return
        }

        val restartGeneration = generation

        handler.postDelayed(
            {
                if (
                    running &&
                    generation == restartGeneration
                ) {
                    startGlow()
                }
            },
            intervalMs
        )
    }

    private fun finishAnimation() {
        if (!running) {
            return
        }

        running = false
        repeat = false
        remainingCycles = null

        /*
         * All frame callbacks for this animation are no longer needed.
         */
        handler.removeCallbacksAndMessages(null)

        onProgressChanged(0f)

        onCompleted()
    }

    private fun alphaFor(fraction: Float): Float {
        val safeFraction =
            fraction.coerceIn(0f, 1f)

        val progress =
            when {
                safeFraction < fadeInFraction &&
                        fadeInFraction > 0f -> {
                    fadeInInterpolator.getInterpolation(
                        safeFraction / fadeInFraction
                    )
                }

                safeFraction < fadeOutStartFraction -> {
                    1f
                }

                fadeOutStartFraction < 1f -> {
                    val fadeOutProgress =
                        (
                                safeFraction -
                                        fadeOutStartFraction
                                ) /
                                (
                                        1f -
                                                fadeOutStartFraction
                                        )

                    1f -
                            fadeOutInterpolator.getInterpolation(
                                fadeOutProgress
                            )
                }

                else -> {
                    0f
                }
            }

        return if (progress.isFinite()) {
            progress.coerceIn(0f, 1f)
        } else {
            0f
        }
    }

    fun cancel() {
        generation++

        running = false
        repeat = false
        remainingCycles = null

        handler.removeCallbacksAndMessages(null)

        lastPhaseFraction = 0f

        onProgressChanged(0f)
    }

    companion object {
        const val MIN_DURATION_MS = 600L

        private const val DEFAULT_DURATION = 2.5f
        private const val DEFAULT_DURATION_MS = 2_500L

        /*
         * ~60 updates/sec when the main looper is running normally.
         *
         * elapsedRealtime() remains the source of truth, so a delayed frame
         * does not make the animation itself longer.
         */
        private const val FRAME_DELAY_MS = 16L
    }
}