package com.yp.luminote.app.effects

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import com.yp.luminote.app.data.settings.HaloMotion
import kotlin.math.max
import kotlin.math.min

/**
 * Finite animations use the proven QA scheduler:
 * Handler + SystemClock.elapsedRealtime().
 *
 * Ambient animation stays VSYNC-driven through Choreographer.
 */
internal class HaloAnimation(
    private val onFrame: (HaloAnimationState) -> Unit,
    private val shouldContinueRepeating: (() -> Boolean)? = null,
    private val onCompleted: () -> Unit = {}
) : HaloAnimationEngine {

    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    private val choreographer =
        Choreographer.getInstance()

    private val fadeInInterpolator =
        DecelerateInterpolator()

    private val fadeOutInterpolator =
        AccelerateInterpolator()

    private var running =
        false

    private var ambient =
        false

    private var repeat =
        false

    private var intervalMs =
        0L

    private var remainingCycles:
            Int? = null

    private var phaseOffset =
        0f

    private var currentDurationMs =
        DEFAULT_DURATION_MS

    private var fadeInFraction =
        0f

    private var fadeOutStartFraction =
        1f

    private var finiteCycleStartedAtMs =
        0L

    private var ambientStartedAtNanos =
        0L

    private var ambientEffectSpeed =
        DEFAULT_EFFECT_SPEED

    private var ambientPhaseStart =
        0f

    private var ambientGradientPhaseStart =
        0f

    private var currentPhase =
        0f

    private var currentGradientPhase =
        0f

    private var generation =
        0L

    private var ambientFrameCallbackPosted =
        false

    private var strategy:
            HaloAnimationStrategy =
        HaloAnimationStrategies.forMotion(
            HaloMotion.PULSE
        )

    private val ambientFrameCallback =
        Choreographer.FrameCallback {
            ambientFrameCallbackPosted =
                false

            val frameGeneration =
                generation

            if (
                !running ||
                !ambient ||
                generation != frameGeneration
            ) {
                return@FrameCallback
            }

            runAmbientFrame(
                frameGeneration
            )
        }

    override fun start(
        request: HaloAnimationRequest
    ) {
        val preservedPhase =
            if (
                request.preservePhase
            ) {
                currentPhase
            } else {
                0f
            }

        cancelInternal(
            dispatchFrame = false
        )

        ambient =
            false

        running =
            true

        phaseOffset =
            preservedPhase

        currentPhase =
            preservedPhase

        currentGradientPhase =
            preservedPhase

        val safeDuration =
            request.duration.takeIf {
                it.isFinite() &&
                        it > 0f
            } ?: DEFAULT_DURATION

        currentDurationMs =
            max(
                MIN_DURATION_MS,
                (
                        safeDuration *
                                MILLIS_PER_SECOND
                        ).toLong()
            )

        intervalMs =
            if (
                request.repeat &&
                request.interval.isFinite() &&
                request.interval > 0f
            ) {
                (
                        request.interval *
                                MILLIS_PER_SECOND
                        )
                    .toLong()
                    .coerceAtLeast(
                        0L
                    )
            } else {
                0L
            }

        repeat =
            request.repeat

        remainingCycles =
            request.maxCycles

        strategy =
            HaloAnimationStrategies.forMotion(
                request.motion
            )

        startFiniteCycle()
    }

    override fun startAmbient(
        request: HaloAmbientAnimationRequest
    ) {
        cancelInternal(
            dispatchFrame = false
        )

        ambient =
            true

        running =
            true

        ambientEffectSpeed =
            sanitizeEffectSpeed(
                request.effectSpeed
            )

        ambientPhaseStart =
            request.phaseStart

        ambientGradientPhaseStart =
            request.gradientPhaseStart

        currentPhase =
            ambientPhaseStart

        currentGradientPhase =
            ambientGradientPhaseStart

        ambientStartedAtNanos =
            0L

        dispatchState(
            progress = 1f
        )

        postAmbientFrame()
    }

    override fun updateAmbientEffectSpeed(
        effectSpeed: Float
    ) {
        if (
            !running ||
            !ambient
        ) {
            return
        }

        val nextSpeed =
            sanitizeEffectSpeed(
                effectSpeed
            )

        if (
            nextSpeed ==
            ambientEffectSpeed
        ) {
            return
        }

        ambientPhaseStart =
            currentPhase

        ambientGradientPhaseStart =
            currentGradientPhase

        ambientStartedAtNanos =
            0L

        ambientEffectSpeed =
            nextSpeed
    }

    override fun cancel() {
        cancelInternal(
            dispatchFrame = true
        )
    }

    private fun startFiniteCycle() {
        if (
            !running ||
            ambient
        ) {
            return
        }

        val envelope =
            strategy.envelope(
                currentDurationMs
            )

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
            (
                    fadeInDuration +
                            holdDuration
                    ).toFloat() /
                    currentDurationMs.toFloat()

        currentPhase =
            phaseOffset

        currentGradientPhase =
            phaseOffset

        dispatchState(
            progress = 0f
        )

        finiteCycleStartedAtMs =
            SystemClock.elapsedRealtime()

        val frameGeneration =
            generation

        scheduleFiniteFrame(
            frameGeneration =
                frameGeneration,
            delayMs =
                0L
        )
    }

    private fun scheduleFiniteFrame(
        frameGeneration: Long,
        delayMs: Long =
            FINITE_FRAME_DELAY_MS
    ) {
        handler.postDelayed(
            {
                runFiniteFrame(
                    frameGeneration
                )
            },
            delayMs
        )
    }

    private fun runFiniteFrame(
        frameGeneration: Long
    ) {
        if (
            !running ||
            ambient ||
            generation != frameGeneration
        ) {
            return
        }

        val now =
            SystemClock.elapsedRealtime()

        val elapsedMs =
            (
                    now -
                            finiteCycleStartedAtMs
                    )
                .coerceAtLeast(
                    0L
                )

        val fraction =
            (
                    elapsedMs.toFloat() /
                            currentDurationMs.toFloat()
                    )
                .coerceIn(
                    0f,
                    1f
                )

        currentPhase =
            phaseOffset +
                    fraction

        currentGradientPhase =
            currentPhase

        dispatchState(
            progress =
                alphaFor(
                    fraction
                )
        )

        if (
            fraction >=
            1f
        ) {
            finishFiniteCycle()

            return
        }

        scheduleFiniteFrame(
            frameGeneration
        )
    }

    private fun finishFiniteCycle() {
        if (
            !running ||
            ambient
        ) {
            return
        }

        currentPhase =
            phaseOffset +
                    1f

        currentGradientPhase =
            currentPhase

        dispatchState(
            progress = 0f
        )

        phaseOffset +=
            1f

        finiteCycleStartedAtMs =
            0L

        if (
            !repeat
        ) {
            finishAnimation()

            return
        }

        remainingCycles
            ?.let { cycles ->

                remainingCycles =
                    cycles - 1

                if (
                    cycles <=
                    1
                ) {
                    repeat =
                        false

                    finishAnimation()

                    return
                }
            }

        if (
            shouldContinueRepeating
                ?.invoke() ==
            false
        ) {
            repeat =
                false

            finishAnimation()

            return
        }

        if (
            intervalMs <=
            0L
        ) {
            startFiniteCycle()

            return
        }

        val restartGeneration =
            generation

        handler.postDelayed(
            {
                if (
                    running &&
                    !ambient &&
                    generation ==
                    restartGeneration
                ) {
                    startFiniteCycle()
                }
            },
            intervalMs
        )
    }

    private fun runAmbientFrame(
        frameGeneration: Long
    ) {
        if (
            !running ||
            !ambient ||
            generation != frameGeneration
        ) {
            return
        }

        val frameTimeNanos =
            System.nanoTime()

        if (
            ambientStartedAtNanos ==
            0L
        ) {
            ambientStartedAtNanos =
                frameTimeNanos
        }

        val elapsedNanos =
            (
                    frameTimeNanos -
                            ambientStartedAtNanos
                    )
                .coerceAtLeast(
                    0L
                )

        val elapsedSeconds =
            elapsedNanos.toDouble() /
                    NANOS_PER_SECOND.toDouble()

        val ambientProgress =
            elapsedSeconds /
                    AMBIENT_ROTATION_DURATION_SECONDS

        currentPhase =
            ambientPhaseStart +
                    (
                            ambientProgress *
                                    AMBIENT_PHASE_SPAN *
                                    ambientEffectSpeed
                            )
                        .toFloat()

        currentGradientPhase =
            ambientGradientPhaseStart +
                    (
                            ambientProgress *
                                    AMBIENT_PHASE_SPAN
                            )
                        .toFloat()

        dispatchState(
            progress = 1f
        )

        postAmbientFrame()
    }

    private fun finishAnimation() {
        if (
            !running
        ) {
            return
        }

        running =
            false

        ambient =
            false

        repeat =
            false

        remainingCycles =
            null

        handler.removeCallbacksAndMessages(
            null
        )

        removeAmbientFrameCallback()

        dispatchState(
            progress = 0f
        )

        onCompleted()
    }

    private fun dispatchState(
        progress: Float
    ) {
        onFrame(
            HaloAnimationState(
                progress =
                    progress.coerceIn(
                        0f,
                        1f
                    ),
                phase =
                    currentPhase,
                gradientPhase =
                    currentGradientPhase,
                running =
                    running,
                ambient =
                    ambient
            )
        )
    }

    private fun postAmbientFrame() {
        if (
            !running ||
            !ambient ||
            ambientFrameCallbackPosted
        ) {
            return
        }

        ambientFrameCallbackPosted =
            true

        choreographer.postFrameCallback(
            ambientFrameCallback
        )
    }

    private fun removeAmbientFrameCallback() {
        if (
            !ambientFrameCallbackPosted
        ) {
            return
        }

        choreographer.removeFrameCallback(
            ambientFrameCallback
        )

        ambientFrameCallbackPosted =
            false
    }

    private fun alphaFor(
        fraction: Float
    ): Float {
        val safeFraction =
            fraction.coerceIn(
                0f,
                1f
            )

        val progress =
            when {
                safeFraction <
                        fadeInFraction &&
                        fadeInFraction >
                        0f -> {

                    fadeInInterpolator
                        .getInterpolation(
                            safeFraction /
                                    fadeInFraction
                        )
                }

                safeFraction <
                        fadeOutStartFraction -> {

                    1f
                }

                fadeOutStartFraction <
                        1f -> {

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
                            fadeOutInterpolator
                                .getInterpolation(
                                    fadeOutProgress
                                )
                }

                else -> {
                    0f
                }
            }

        return if (
            progress.isFinite()
        ) {
            progress.coerceIn(
                0f,
                1f
            )
        } else {
            0f
        }
    }

    private fun cancelInternal(
        dispatchFrame: Boolean
    ) {
        generation++

        running =
            false

        ambient =
            false

        repeat =
            false

        remainingCycles =
            null

        handler.removeCallbacksAndMessages(
            null
        )

        removeAmbientFrameCallback()

        finiteCycleStartedAtMs =
            0L

        ambientStartedAtNanos =
            0L

        if (
            dispatchFrame
        ) {
            dispatchState(
                progress = 0f
            )
        }
    }

    private fun sanitizeEffectSpeed(
        effectSpeed: Float
    ): Float =
        effectSpeed
            .takeIf {
                it.isFinite()
            }
            ?.coerceIn(
                MIN_EFFECT_SPEED,
                MAX_EFFECT_SPEED
            )
            ?: DEFAULT_EFFECT_SPEED

    companion object {

        const val MIN_DURATION_MS =
            600L

        private const val DEFAULT_DURATION =
            2.5f

        private const val DEFAULT_DURATION_MS =
            2_500L

        private const val DEFAULT_EFFECT_SPEED =
            1f

        private const val MILLIS_PER_SECOND =
            1_000f

        private const val NANOS_PER_SECOND =
            1_000_000_000L

        private const val FINITE_FRAME_DELAY_MS =
            16L

        private const val AMBIENT_ROTATION_DURATION_SECONDS =
            16.0

        private const val AMBIENT_PHASE_SPAN =
            360.0 /
                    220.0

        private const val MIN_EFFECT_SPEED =
            0.25f

        private const val MAX_EFFECT_SPEED =
            2f
    }
}