package com.yp.luminote.app.animation.definitions

import androidx.annotation.StringRes

/**
 * Stable, backend-neutral runtime identity for an animation definition.
 *
 * It deliberately is not an enum: an application composition root can supply
 * another definition without changing any engine, renderer, or service type.
 */
@JvmInline
value class HaloAnimationId(val value: String) {
    init {
        require(value.isNotBlank()) { "Animation IDs must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Complete backend-neutral contract for a selectable Halo animation.
 * Definitions own their metadata, runtime policy, and requested effect; render
 * backends consume [effectSpec] without depending on a concrete definition.
 */
interface HaloAnimationDefinition {
    val id: HaloAnimationId
    @get:StringRes val titleRes: Int
    val baseDurationSeconds: Float
    val ambientEligible: Boolean
    val runtimePolicy: HaloRuntimePolicy
    val effectSpec: HaloEffectSpec
}
