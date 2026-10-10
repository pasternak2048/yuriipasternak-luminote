package com.yp.luminote.app.animation

import com.yp.luminote.app.data.settings.AmbientPolicyKey
import com.yp.luminote.app.data.settings.HaloMotionDefinition
import kotlin.math.PI
import kotlin.math.sin

/** Runtime-only envelopes and ambient progression; persisted catalog entries merely select one. */
internal data class HaloAnimationEnvelopePolicy(val fadeInMs: Long, val fadeOutMs: Long)

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
internal fun HaloMotionDefinition.runtimeEnvelope(): HaloAnimationEnvelopePolicy =
    HaloAnimationEnvelopePolicy(fadeInMs, fadeOutMs)

internal fun HaloMotionDefinition.runtimeAmbientPolicy(): AmbientProgressPolicy = when (ambientPolicyKey) {
    AmbientPolicyKey.CONTINUOUS -> AmbientProgressPolicies.continuous
    AmbientPolicyKey.PULSE_WITH_SILENCE -> AmbientProgressPolicies.pulseWithSilence
}
