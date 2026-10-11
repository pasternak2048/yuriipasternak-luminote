package com.yp.luminote.app.animation.definitions

import androidx.annotation.StringRes
import com.yp.luminote.app.R

/**
 * Shared parameterized blade construction. Variants differ only in preserved
 * identity and envelope data; Canvas owns the corresponding backend drawing.
 */
private fun bladeAnimation(
    id: String,
    @StringRes titleRes: Int,
    durationSeconds: Float,
    fadeInMs: Long,
    fadeOutMs: Long,
    variant: BladeVariant
): HaloAnimationDefinition = object : HaloAnimationDefinition {
    override val id = HaloAnimationId(id)
    override val titleRes = titleRes
    override val baseDurationSeconds = durationSeconds
    override val ambientEligible = false
    override val runtimePolicy = HaloRuntimePolicy(fadeInMs, fadeOutMs)
    override val effectSpec = HaloEffectSpec.Blade(variant)
}

object AzureBladeAnimation : HaloAnimationDefinition by bladeAnimation(
    id = "AZURE_BLADE",
    titleRes = R.string.motion_azure_blade,
    durationSeconds = 3.4f,
    fadeInMs = 80L,
    fadeOutMs = 220L,
    variant = BladeVariant.AZURE
)

object CrimsonBladeAnimation : HaloAnimationDefinition by bladeAnimation(
    id = "CRIMSON_BLADE",
    titleRes = R.string.motion_crimson_blade,
    durationSeconds = 3.0f,
    fadeInMs = 55L,
    fadeOutMs = 160L,
    variant = BladeVariant.CRIMSON
)

object ForceClashAnimation : HaloAnimationDefinition by bladeAnimation(
    id = "FORCE_CLASH",
    titleRes = R.string.motion_force_clash,
    durationSeconds = 3.6f,
    fadeInMs = 70L,
    fadeOutMs = 180L,
    variant = BladeVariant.CLASH
)
