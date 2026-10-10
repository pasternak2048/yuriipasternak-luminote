package com.yp.luminote.app.animation.definitions

import com.yp.luminote.app.R

object SnakeAnimation : HaloAnimationDefinition {
    override val id = HaloAnimationId("SNAKE")
    override val titleRes = R.string.motion_snake
    override val baseDurationSeconds = 2.5f
    override val ambientEligible = true
    override val runtimePolicy = HaloRuntimePolicy(160L, 460L)
    override val effectSpec = HaloEffectSpec.LuminousSegment(lengthFraction = 0.18f)
}
