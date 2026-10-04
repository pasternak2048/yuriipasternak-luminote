package com.yp.luminote.app.effects

import com.yp.luminote.app.data.settings.HaloMotion

/** Runtime behavior for one catalogued Halo animation. */
internal interface HaloAnimationStrategy {
    fun envelope(totalDurationMs: Long): HaloAnimationEnvelope
}

internal data class HaloAnimationEnvelope(
    val fadeInMs: Long,
    val fadeOutMs: Long
)

internal object HaloAnimationStrategies {
    fun forMotion(motion: HaloMotion): HaloAnimationStrategy = when (motion) {
        HaloMotion.PULSE -> Pulse
        HaloMotion.SNAKE -> Snake
        HaloMotion.IMPULSE -> Impulse
        HaloMotion.CORNER_PULSE -> CornerPulse
        HaloMotion.RAIN -> Rain
        HaloMotion.AZURE_BLADE -> AzureBlade
        HaloMotion.CRIMSON_BLADE -> CrimsonBlade
        HaloMotion.FORCE_CLASH -> ForceClash
    }

    private object Pulse : HaloAnimationStrategy {
        override fun envelope(totalDurationMs: Long) = HaloAnimationEnvelope(250L, 350L)
    }

    private object Snake : HaloAnimationStrategy {
        override fun envelope(totalDurationMs: Long) = HaloAnimationEnvelope(160L, 460L)
    }

    private object CornerPulse : HaloAnimationStrategy {
        override fun envelope(totalDurationMs: Long) = HaloAnimationEnvelope(180L, 420L)
    }

    private object Rain : HaloAnimationStrategy {
        override fun envelope(totalDurationMs: Long) = HaloAnimationEnvelope(180L, 380L)
    }

    private object Impulse : HaloAnimationStrategy {
        // The Impulse renderer owns its birth and convergence fades. Applying
        // the generic finite-cycle envelope would dim those localized fields.
        override fun envelope(totalDurationMs: Long) = HaloAnimationEnvelope(0L, 0L)
    }

    private object AzureBlade : HaloAnimationStrategy {
        override fun envelope(totalDurationMs: Long) = HaloAnimationEnvelope(80L, 220L)
    }

    private object CrimsonBlade : HaloAnimationStrategy {
        override fun envelope(totalDurationMs: Long) = HaloAnimationEnvelope(55L, 160L)
    }

    private object ForceClash : HaloAnimationStrategy {
        override fun envelope(totalDurationMs: Long) = HaloAnimationEnvelope(70L, 180L)
    }
}
