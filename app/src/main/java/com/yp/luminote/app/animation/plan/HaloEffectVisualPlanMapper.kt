package com.yp.luminote.app.animation.plan

import com.yp.luminote.app.animation.definitions.HaloEffectSpec
import com.yp.luminote.app.animation.definitions.ImpulseAnimation
import com.yp.luminote.app.animation.definitions.SnakeAnimation
import com.yp.luminote.app.animation.definitions.SupernovaAnimation
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.effects.model.HaloRenderMode

/** Compatibility bridge retained while definitions still publish HaloEffectSpec. */
internal object HaloEffectVisualPlanMapper {
    fun map(config: HaloConfig): VisualPlanSpec = VisualPlanSpec(
        emitters = listOf(
            VisualEmitterSpec(
                geometry = GeometryRequest.DisplayOutlineContour(
                    thickness = config.thickness,
                    frame = config.frame,
                    edgeCalibrationDp = config.edgeCalibrationDp,
                    cornerCalibrationDp = config.cornerCalibrationDp,
                    cornerShape = config.cornerShape
                ),
                appearance = AppearancePayload(
                    color = config.color,
                    intensity = config.intensity,
                    thickness = config.thickness,
                    colorMode = config.colorMode,
                    palette = config.palette.toList()
                ),
                operation = config.effectPlan.toPrimaryOperation(),
                modifierProfile = if (isNormalImpulse(config)) {
                    VisualModifierProfile.NORMAL_IMPULSE
                } else {
                    VisualModifierProfile.DEFAULT
                },
                luminousSegmentAppearance = if (isNormalSnake(config)) {
                    LuminousSegmentAppearance.FLOWING_SNAKE
                } else {
                    LuminousSegmentAppearance.DEFAULT
                },
                // The registry's first normal Impulse is the sole production
                // opt-in.  In particular, LIGHT_IMPULSE is the reminder-owned
                // renderer route and must retain its established profile.
                modifiers = productionModifiers(config)
            )
        )
    )

    private fun productionModifiers(config: HaloConfig): List<VisualModifier> = when {
        isNormalImpulse(config) -> {
            // Pixels rather than dp: RenderEffect radii are evaluated in the
            // hardware surface's pixel space. These retain a bright core while
            // producing a visible envelope on current phone-density displays.
            listOf(
                VisualModifier.IntensityPulse(amplitude = 0.22f, frequencyHz = 1.15f),
                VisualModifier.Blur(radiusPx = 3.5f),
                VisualModifier.Glow(radiusPx = 7f, alphaMultiplier = 0.42f)
            )
        }
        isNormalSupernova(config) -> listOf(
            VisualModifier.IntensityPulse(amplitude = 0.18f, frequencyHz = 1.65f),
            VisualModifier.Blur(radiusPx = 4.5f),
            VisualModifier.Glow(radiusPx = 11f, alphaMultiplier = 0.50f)
        )
        isNormalSnake(config) -> listOf(
            VisualModifier.IntensityPulse(amplitude = 0.12f, frequencyHz = 0.75f),
            VisualModifier.Blur(radiusPx = 2.75f),
            VisualModifier.Glow(radiusPx = 7.5f, alphaMultiplier = 0.35f)
        )
        else -> emptyList()
    }

    private fun isNormalImpulse(config: HaloConfig): Boolean =
        (
            config.motion == ImpulseAnimation.id &&
            config.renderMode == HaloRenderMode.NORMAL &&
            config.effectPlan == HaloEffectSpec.SpecializedField
        )

    /**
     * Supernova owns a deliberately distinct release profile. This exact gate
     * keeps malformed or reminder-owned routes modifier-free while preserving
     * the Supernova Canvas choreography under its production stack.
     */
    private fun isNormalSupernova(config: HaloConfig): Boolean =
        (
            config.motion == SupernovaAnimation.id &&
            config.renderMode == HaloRenderMode.NORMAL &&
            config.effectPlan == HaloEffectSpec.Supernova
        )

    private fun isNormalSnake(config: HaloConfig): Boolean =
        config.motion == SnakeAnimation.id &&
            config.renderMode == HaloRenderMode.NORMAL &&
            config.effectPlan is HaloEffectSpec.LuminousSegment

    private fun HaloEffectSpec.toPrimaryOperation(): PrimaryVisualOperation = when (this) {
        HaloEffectSpec.SpecializedField -> PrimaryVisualOperation.LightImpulse
        HaloEffectSpec.Supernova -> PrimaryVisualOperation.Supernova
        HaloEffectSpec.FullContour -> PrimaryVisualOperation.FullContourPulse
        is HaloEffectSpec.LuminousSegment -> PrimaryVisualOperation.TravelingLuminousSegment(lengthFraction)
        is HaloEffectSpec.Blade -> PrimaryVisualOperation.ForceBlade(variant)
    }
}
