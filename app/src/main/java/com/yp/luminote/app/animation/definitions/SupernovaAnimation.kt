package com.yp.luminote.app.animation.definitions

import com.yp.luminote.app.R

/** A compact, finite top-edge ignition pulse with no travelling contour phase. */
object SupernovaAnimation : HaloAnimationDefinition {
    override val id = HaloAnimationId("SUPERNOVA")
    override val titleRes = R.string.motion_supernova
    override val baseDurationSeconds = 0.6f
    override val ambientEligible = false
    override val runtimePolicy = HaloRuntimePolicy(fadeInMs = 0L, fadeOutMs = 0L)
    override val effectSpec = HaloEffectSpec.Supernova
}
