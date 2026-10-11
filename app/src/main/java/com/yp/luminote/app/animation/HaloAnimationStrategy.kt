package com.yp.luminote.app.animation

import com.yp.luminote.app.animation.definitions.HaloAnimationId
import com.yp.luminote.app.animation.definitions.LuminoteHaloAnimations

/**
 * Compatibility seam for focused timing tests. Runtime sessions bind the
 * registry definition directly and do not consult this facade per frame.
 */
internal fun interface HaloAnimationStrategy {
    fun envelope(totalDurationMs: Long): HaloAnimationEnvelope
}

internal data class HaloAnimationEnvelope(val fadeInMs: Long, val fadeOutMs: Long)

internal object HaloAnimationStrategies {
    fun forMotion(motion: HaloAnimationId): HaloAnimationStrategy {
        val policy = LuminoteHaloAnimations.registry.definition(motion).runtimeEnvelope()
        return HaloAnimationStrategy { HaloAnimationEnvelope(policy.fadeInMs, policy.fadeOutMs) }
    }
}
