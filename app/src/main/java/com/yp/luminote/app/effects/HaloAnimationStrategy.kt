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
        HaloMotion.CORNER_PULSE -> CornerPulse
        HaloMotion.RAIN -> Rain
        HaloMotion.RIPPLE_EDGE -> RippleEdge
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

    private object RippleEdge : HaloAnimationStrategy {
        override fun envelope(totalDurationMs: Long) = HaloAnimationEnvelope(200L, 420L)
    }
}
