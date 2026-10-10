package com.yp.luminote.app.animation.definitions

import com.yp.luminote.app.R

/** Normal app halo impulse; notification/reminder Light Impulse remains a separate render mode. */
object ImpulseAnimation : HaloAnimationDefinition {
    override val id = HaloAnimationId("IMPULSE")
    override val titleRes = R.string.motion_impulse
    override val baseDurationSeconds = 2.2f
    override val ambientEligible = false
    override val runtimePolicy = HaloRuntimePolicy(fadeInMs = 0L, fadeOutMs = 0L)
    override val effectSpec = HaloEffectSpec.SpecializedField
}
