package com.yp.luminote.app.animation.definitions

/**
 * Backend-neutral description of an animation's visual output. Canvas (or a
 * future backend) translates this stable description into drawing operations;
 * definitions do not know about a drawing API.
 */
sealed interface HaloEffectSpec {
    object SpecializedField : HaloEffectSpec
    object FullContour : HaloEffectSpec
    data class LuminousSegment(val lengthFraction: Float) : HaloEffectSpec
    data class Blade(val variant: BladeVariant) : HaloEffectSpec
}

enum class BladeVariant { AZURE, CRIMSON, CLASH }

/** Ambient alpha choreography selected once for an ambient session. */
enum class AmbientPlayback { CONTINUOUS, PULSE_WITH_SILENCE }

/** Runtime envelope remains animation-owned, with no lifecycle dependency. */
data class HaloRuntimePolicy(
    val fadeInMs: Long,
    val fadeOutMs: Long,
    val ambientPlayback: AmbientPlayback = AmbientPlayback.CONTINUOUS
)
