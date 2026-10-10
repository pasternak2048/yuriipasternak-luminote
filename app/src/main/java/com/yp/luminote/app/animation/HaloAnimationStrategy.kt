package com.yp.luminote.app.animation

import com.yp.luminote.app.data.settings.HaloAnimationRegistry
import com.yp.luminote.app.data.settings.HaloMotion

/**
 * Compatibility seam for focused timing tests. Runtime sessions bind the
 * registry definition directly and do not consult this facade per frame.
 */
internal fun interface HaloAnimationStrategy {
    fun envelope(totalDurationMs: Long): HaloAnimationEnvelope
}

internal data class HaloAnimationEnvelope(val fadeInMs: Long, val fadeOutMs: Long)

internal object HaloAnimationStrategies {
    fun forMotion(motion: HaloMotion): HaloAnimationStrategy {
        val policy = HaloAnimationRegistry.definition(motion).runtimeEnvelope()
        return HaloAnimationStrategy { HaloAnimationEnvelope(policy.fadeInMs, policy.fadeOutMs) }
    }
}
