package com.yp.luminote.app.animation.definitions

/** Production composition root. Ordering is the persisted picker order. */
object LuminoteHaloAnimations {
    val registry = HaloAnimationRegistry(
        listOf(
            ImpulseAnimation,
            PulseAnimation,
            SnakeAnimation,
            AzureBladeAnimation,
            CrimsonBladeAnimation,
            ForceClashAnimation
        )
    )

    val all: List<HaloAnimationDefinition> = registry.definitions
    val ambient: List<HaloAnimationDefinition> = registry.ambientDefinitions
}
