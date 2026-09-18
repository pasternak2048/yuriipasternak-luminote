package com.yp.luminote.app.effects

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import com.yp.luminote.app.data.settings.HaloMotion
import kotlin.math.max
import kotlin.math.min

internal class HaloAnimation(
    private val onFrame: (HaloAnimationState) -> Unit,
    private val shouldContinueRepeating: (() -> Boolean)? = null,
    private val onCompleted: () -> Unit = {}
) : HaloAnimationEngine {

    private val choreographer =
        Choreographer.getInstance()

    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    private val fadeInInterpolator =
        DecelerateInterpolator()

    private val fadeOutInterpolator =
        AccelerateInterpolator()

    private var running = false
    private var ambient = false
    private var repeat = false

    private var intervalMs = 0L
    private var remainingCycles: Int? = null

    private var phaseOffset = 0f

    private var currentDurationNanos =
        DEFAULT_DURATION_NANOS

    private var fadeInFraction = 0f
    private var fadeOutStartFraction = 1f

    private var cycleStartedAtNanos = 0L

    private var ambientStartedAtNanos = 0L
    private var ambientEffectSpeed =
        DEFAULT_EFFECT_SPEED

    private var ambientPhaseStart = 0f
    private var ambientGradientPhaseStart = 0f

    private var currentPhase = 0f
    private var currentGradientPhase = 0f

    private var generation = 0L
    private var frameCallbackPosted = false

    private var strategy: HaloAnimationStrategy =
        HaloAnimationStrategies.forMotion(
            HaloMotion.PULSE
        )

    private val frameCallback =
        Choreographer.FrameCallback {
                frameTimeNanos ->

            frameCallbackPosted = false

            val frameGeneration =
                generation

            if (
                !running ||
                generation != frameGeneration
            ) {
                return@FrameCallback
            }

            if (ambient) {
                runAmbientFrame(
                    frameGeneration =
                        frameGeneration,
                    frameTimeNanos =
                        frameTimeNanos
                )
            } else {
                runFiniteFrame(
                    frameGeneration =
                        frameGeneration,
                    frameTimeNanos =
                        frameTimeNanos
                )
            }
        }

    override fun start(
        request: HaloAnimationRequest
    ) {
        val preservedPhase =
            if (request.preservePhase) {
                currentPhase
            } else {
                0f
            }

        cancelInternal(
            dispatchFrame = false
        )

        ambient = false
        running = true

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

        val durationMs =
            max(
                MIN_DURATION_MS,
                (
                        safeDuration *
                                MILLIS_PER_SECOND
                        ).toLong()
            )

        currentDurationNanos =
            durationMs *
                    NANOS_PER_MILLISECOND

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

        ambient = true
        running = true

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

        ambientStartedAtNanos = 0L

        dispatchState(
            progress = 1f
        )

        postFrame()
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

        ambientStartedAtNanos = 0L
        ambientEffectSpeed = nextSpeed
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

        val currentDurationMs =
            currentDurationNanos /
                    NANOS_PER_MILLISECOND

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

        cycleStartedAtNanos = 0L

        currentPhase =
            phaseOffset

        currentGradientPhase =
            phaseOffset

        dispatchState(
            progress = 0f
        )

        postFrame()
    }

    private fun runFiniteFrame(
        frameGeneration: Long,
        frameTimeNanos: Long
    ) {
        if (
            !running ||
            ambient ||
            generation != frameGeneration
        ) {
            return
        }

        if (
            cycleStartedAtNanos ==
            0L
        ) {
            cycleStartedAtNanos =
                frameTimeNanos
        }

        val elapsedNanos =
            (
                    frameTimeNanos -
                            cycleStartedAtNanos
                    )
                .coerceAtLeast(
                    0L
                )

        val fraction =
            (
                    elapsedNanos.toDouble() /
                            currentDurationNanos.toDouble()
                    )
                .toFloat()
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

        postFrame()
    }

    private fun runAmbientFrame(
        frameGeneration: Long,
        frameTimeNanos: Long
    ) {
        if (
            !running ||
            !ambient ||
            generation != frameGeneration
        ) {
            return
        }

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

        postFrame()
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

        phaseOffset += 1f
        cycleStartedAtNanos = 0L

        if (!repeat) {
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
                    repeat = false
                    finishAnimation()
                    return
                }
            }

        if (
            shouldContinueRepeating
                ?.invoke() ==
            false
        ) {
            repeat = false
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

    private fun finishAnimation() {
        if (!running) {
            return
        }

        running = false
        ambient = false
        repeat = false
        remainingCycles = null

        removeFrameCallback()

        handler.removeCallbacksAndMessages(
            null
        )

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

    private fun postFrame() {
        if (
            !running ||
            frameCallbackPosted
        ) {
            return
        }

        frameCallbackPosted =
            true

        choreographer.postFrameCallback(
            frameCallback
        )
    }

    private fun removeFrameCallback() {
        if (
            !frameCallbackPosted
        ) {
            return
        }

        choreographer.removeFrameCallback(
            frameCallback
        )

        frameCallbackPosted =
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

        running = false
        ambient = false
        repeat = false
        remainingCycles = null

        removeFrameCallback()

        handler.removeCallbacksAndMessages(
            null
        )

        cycleStartedAtNanos = 0L
        ambientStartedAtNanos = 0L

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

        private const val DEFAULT_EFFECT_SPEED =
            1f

        private const val MILLIS_PER_SECOND =
            1_000f

        private const val NANOS_PER_MILLISECOND =
            1_000_000L

        private const val NANOS_PER_SECOND =
            1_000_000_000L

        private const val DEFAULT_DURATION_NANOS =
            2_500L *
                    NANOS_PER_MILLISECOND

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