package com.yp.luminote.app.effects

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import com.yp.luminote.app.data.settings.AmbientProgressPolicy
import com.yp.luminote.app.data.settings.AmbientProgressPolicies
import com.yp.luminote.app.data.settings.HaloAnimationEnvelopePolicy
import kotlin.math.max
import kotlin.math.min

/** Test seam for deterministic VSYNC-driven ambient animation tests. */
internal interface HaloFrameScheduler {
    fun post(callback: Choreographer.FrameCallback)

    fun remove(callback: Choreographer.FrameCallback)
}

/** Test seam for finite frames that are independent from display VSYNC. */
internal interface HaloFiniteFrameScheduler {
    fun postDelayed(
        runnable: Runnable,
        delayMs: Long
    )

    fun removeCallbacks(
        runnable: Runnable
    )
}

internal class AndroidHaloFiniteFrameScheduler : HaloFiniteFrameScheduler {
    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    override fun postDelayed(
        runnable: Runnable,
        delayMs: Long
    ) {
        handler.postDelayed(
            runnable,
            delayMs
        )
    }

    override fun removeCallbacks(
        runnable: Runnable
    ) {
        handler.removeCallbacks(
            runnable
        )
    }
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
    private val frameScheduler: HaloFrameScheduler = AndroidHaloFrameScheduler(),
    private val finiteFrameScheduler: HaloFiniteFrameScheduler = AndroidHaloFiniteFrameScheduler(),
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime
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

    /** Preselected at ambient session start; no ID branching on VSYNC. */
    private var ambientProgressPolicy: AmbientProgressPolicy =
        AmbientProgressPolicies.pulseWithSilence

    private var currentPhase =
        0f

    private var currentGradientPhase =
        0f

    private var generation =
        0L

    private var frameCallbackPosted =
        false

    /** Preselected at finite session start; no catalog lookup during frames. */
    private var envelope = HaloAnimationEnvelopePolicy(250L, 350L)

    private var frameCallback:
            Choreographer.FrameCallback? = null

    private var finiteFrameRunnable:
            Runnable? = null

    private var finiteRestartRunnable:
            Runnable? = null

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
                runFiniteFrame(frameGeneration)
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

        envelope = request.definition.envelope

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

        ambientProgressPolicy = request.definition.ambientProgressPolicy

        currentPhase =
            ambientPhaseStart

        currentGradientPhase =
            ambientGradientPhaseStart

        ambientStartedAtNanos =
            0L

        dispatchState(
            progress = ambientProgressPolicy.alphaAt(0.0)
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
            elapsedRealtimeMs()

        scheduleFiniteFrame(
            frameGeneration = generation,
            delayMs = 0L
        )
    }

    private fun scheduleFiniteFrame(
        frameGeneration: Long,
        delayMs: Long = FINITE_FRAME_DELAY_MS
    ) {
        removeFiniteFrameRunnable()

        val runnable =
            object : Runnable {
                override fun run() {
                    if (
                        finiteFrameRunnable === this
                    ) {
                        finiteFrameRunnable =
                            null
                    }

                    runFiniteFrame(
                        frameGeneration
                    )
                }
            }

        finiteFrameRunnable =
            runnable

        finiteFrameScheduler.postDelayed(
            runnable,
            delayMs
        )
    }

    private fun scheduleFiniteRestart(
        restartGeneration: Long
    ) {
        removeFiniteRestartRunnable()

        val runnable =
            object : Runnable {
                override fun run() {
                    if (
                        finiteRestartRunnable === this
                    ) {
                        finiteRestartRunnable =
                            null
                    }

                    if (
                        running &&
                        !ambient &&
                        generation == restartGeneration
                    ) {
                        startFiniteCycle()
                    }
                }
            }

        finiteRestartRunnable =
            runnable

        finiteFrameScheduler.postDelayed(
            runnable,
            intervalMs
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

        val elapsedMs =
            (
                    elapsedRealtimeMs() -
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

        scheduleFiniteRestart(
            generation
        )
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
            progress = ambientProgressPolicy.alphaAt(elapsedSeconds)
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

        removeFiniteFrameRunnable()

        removeFiniteRestartRunnable()

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

    private fun removeFiniteFrameRunnable() {
        finiteFrameRunnable
            ?.let(finiteFrameScheduler::removeCallbacks)

        finiteFrameRunnable =
            null
    }

    private fun removeFiniteRestartRunnable() {
        finiteRestartRunnable
            ?.let(finiteFrameScheduler::removeCallbacks)

        finiteRestartRunnable =
            null
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

        removeFiniteFrameRunnable()

        removeFiniteRestartRunnable()

        removeFrameCallback()

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
            8L

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
