package com.yp.luminote.app.effects

import android.view.Choreographer
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import com.yp.luminote.app.data.settings.HaloMotion
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Test seam for deterministic VSYNC-driven finite animation tests. */
internal interface HaloFrameScheduler {
    fun post(callback: Choreographer.FrameCallback)

    fun remove(callback: Choreographer.FrameCallback)
}

internal class AndroidHaloFrameScheduler : HaloFrameScheduler {
    private val choreographer = Choreographer.getInstance()

    override fun post(callback: Choreographer.FrameCallback) {
        choreographer.postFrameCallback(callback)
    }

    override fun remove(callback: Choreographer.FrameCallback) {
        choreographer.removeFrameCallback(callback)
    }
}

/**
 * Finite animations are driven by the main-thread clock, so an LTPO display
 * lowering its VSYNC rate cannot delay the first visible frame. Ambient
 * animation remains VSYNC-driven through Choreographer.
 */
internal class HaloAnimation(
    private val onFrame: (HaloAnimationState) -> Unit,
    private val shouldContinueRepeating: (() -> Boolean)? = null,
    private val onCompleted: () -> Unit = {},
    private val frameScheduler: HaloFrameScheduler = AndroidHaloFrameScheduler()
) : HaloAnimationEngine {

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

    private var finiteCycleStartedAtNanos =
        0L

    private var finiteRestartAtNanos =
        0L

    private var ambientStartedAtNanos =
        0L

    private var ambientEffectSpeed =
        DEFAULT_EFFECT_SPEED

    private var ambientPhaseStart =
        0f

    private var ambientGradientPhaseStart =
        0f

    private var ambientMotion =
        HaloMotion.PULSE

    private var currentPhase =
        0f

    private var currentGradientPhase =
        0f

    private var generation =
        0L

    private var frameCallbackPosted =
        false

    private var strategy:
            HaloAnimationStrategy =
        HaloAnimationStrategies.forMotion(
            HaloMotion.PULSE
        )

    private var frameCallback:
            Choreographer.FrameCallback? = null

    private fun createFrameCallback(
        frameGeneration: Long
    ): Choreographer.FrameCallback =
        Choreographer.FrameCallback {
            frameCallbackPosted =
                false

            frameCallback =
                null

            if (
                !running ||
                generation != frameGeneration
            ) {
                return@FrameCallback
            }

            if (ambient) {
                runAmbientFrame(it, frameGeneration)
            } else {
                runFiniteFrame(it, frameGeneration)
            }
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

        ambientMotion =
            request.motion

        currentPhase =
            ambientPhaseStart

        currentGradientPhase =
            ambientGradientPhaseStart

        ambientStartedAtNanos =
            0L

        dispatchState(
            progress = ambientProgressFor(0.0)
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

        finiteCycleStartedAtNanos =
            0L

        postFrame()
    }

    private fun runFiniteFrame(
        frameTimeNanos: Long,
        frameGeneration: Long
    ) {
        if (
            !running ||
            ambient ||
            generation != frameGeneration
        ) {
            return
        }

        if (
            finiteRestartAtNanos > 0L
        ) {
            if (
                frameTimeNanos < finiteRestartAtNanos
            ) {
                postFrame()

                return
            }

            finiteRestartAtNanos = 0L
            startFiniteCycle()

            return
        }

        if (
            finiteCycleStartedAtNanos == 0L
        ) {
            finiteCycleStartedAtNanos = frameTimeNanos
        }

        val elapsedNanos =
            (
                    frameTimeNanos -
                            finiteCycleStartedAtNanos
                    )
                .coerceAtLeast(0L)

        val fraction =
            (
                    elapsedNanos.toDouble() /
                            (currentDurationMs * NANOS_PER_MILLISECOND).toDouble()
                    )
                .coerceIn(
                    0.0,
                    1.0
                )
                .toFloat()

        currentPhase =
            phaseOffset +
                    fraction

        currentGradientPhase =
            currentPhase

        val progress =
            alphaFor(
                fraction
            )

        dispatchState(
            progress = progress
        )

        if (
            fraction >=
            1f
        ) {
            finishFiniteCycle(
                frameTimeNanos
            )

            return
        }

        postFrame()
    }

    private fun finishFiniteCycle(
        frameTimeNanos: Long
    ) {
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

        finiteCycleStartedAtNanos =
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

        finiteRestartAtNanos =
            frameTimeNanos +
                    intervalMs * NANOS_PER_MILLISECOND

        postFrame()
    }

    private fun runAmbientFrame(
        frameTimeNanos: Long,
        frameGeneration: Long
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
            progress = ambientProgressFor(
                elapsedSeconds
            )
        )

        postFrame()
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

        removeFrameCallback()

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

        val callback =
            createFrameCallback(
                generation
            )

        frameCallback =
            callback

        frameScheduler.post(
            callback
        )
    }

    private fun removeFrameCallback() {
        if (
            !frameCallbackPosted
        ) {
            return
        }

        frameCallback?.let(frameScheduler::remove)

        frameCallback =
            null

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

        running =
            false

        ambient =
            false

        repeat =
            false

        remainingCycles =
            null

        removeFrameCallback()

        finiteCycleStartedAtNanos =
            0L

        finiteRestartAtNanos =
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

    private fun ambientProgressFor(
        elapsedSeconds: Double
    ): Float {
        if (
            ambientMotion != HaloMotion.PULSE
        ) {
            return 1f
        }

        val pulsePositionSeconds =
            elapsedSeconds % PULSE_CYCLE_SECONDS

        if (
            pulsePositionSeconds < PULSE_SILENCE_SECONDS
        ) {
            return 0f
        }

        val pulseFraction =
            (
                    pulsePositionSeconds -
                            PULSE_SILENCE_SECONDS
                    ) /
                    PULSE_DURATION_SECONDS

        return sin(
            PI * pulseFraction
        ).toFloat()
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

        private const val NANOS_PER_MILLISECOND =
            1_000_000L

        private const val AMBIENT_ROTATION_DURATION_SECONDS =
            16.0

        private const val PULSE_SILENCE_SECONDS =
            10.0

        private const val PULSE_DURATION_SECONDS =
            2.5

        private const val PULSE_CYCLE_SECONDS =
            PULSE_SILENCE_SECONDS +
                    PULSE_DURATION_SECONDS

        private const val AMBIENT_PHASE_SPAN =
            360.0 /
                    220.0

        private const val MIN_EFFECT_SPEED =
            0.25f

        private const val MAX_EFFECT_SPEED =
            2f
    }
}
