package com.yp.luminote.app.animation.definitions

import com.yp.luminote.app.R

object PulseAnimation : HaloAnimationDefinition {
    override val id = HaloAnimationId("PULSE")
    override val titleRes = R.string.motion_pulse
    override val baseDurationSeconds = 2.5f
    override val ambientEligible = true
    override val runtimePolicy = HaloRuntimePolicy(250L, 350L, AmbientPlayback.PULSE_WITH_SILENCE)
    override val effectSpec = HaloEffectSpec.FullContour
}
