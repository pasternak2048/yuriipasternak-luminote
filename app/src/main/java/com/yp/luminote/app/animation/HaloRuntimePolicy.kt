package com.yp.luminote.app.animation

import com.yp.luminote.app.animation.definitions.AmbientPlayback
import com.yp.luminote.app.animation.definitions.HaloAnimationDefinition
import com.yp.luminote.app.animation.definitions.HaloRuntimePolicy
import kotlin.math.PI
import kotlin.math.sin

/** Runtime-only envelopes and ambient progression; persisted catalog entries merely select one. */
internal typealias HaloAnimationEnvelopePolicy = HaloRuntimePolicy

internal fun interface AmbientProgressPolicy { fun alphaAt(elapsedSeconds: Double): Float }

internal object AmbientProgressPolicies {
    val continuous = AmbientProgressPolicy { 1f }
    val pulseWithSilence = AmbientProgressPolicy { elapsedSeconds ->
        val position = elapsedSeconds % PULSE_CYCLE_SECONDS
        if (position < PULSE_SILENCE_SECONDS) 0f
        else sin(PI * ((position - PULSE_SILENCE_SECONDS) / PULSE_DURATION_SECONDS)).toFloat()
    }

    private const val PULSE_SILENCE_SECONDS = 10.0
    private const val PULSE_DURATION_SECONDS = 2.5
    private const val PULSE_CYCLE_SECONDS = PULSE_SILENCE_SECONDS + PULSE_DURATION_SECONDS
}

/** Translation occurs once when a finite or ambient session is accepted. */
internal fun HaloAnimationDefinition.runtimeEnvelope(): HaloAnimationEnvelopePolicy =
    runtimePolicy

internal fun HaloAnimationDefinition.runtimeAmbientPolicy(): AmbientProgressPolicy = when (runtimePolicy.ambientPlayback) {
    AmbientPlayback.CONTINUOUS -> AmbientProgressPolicies.continuous
    AmbientPlayback.PULSE_WITH_SILENCE -> AmbientProgressPolicies.pulseWithSilence
}
