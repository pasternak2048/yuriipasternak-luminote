package com.yp.luminote.app.animation.plan

import com.yp.luminote.app.animation.definitions.HaloEffectSpec
import com.yp.luminote.app.effects.model.HaloConfig

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
                operation = config.effectPlan.toPrimaryOperation()
            )
        )
    )

    private fun HaloEffectSpec.toPrimaryOperation(): PrimaryVisualOperation = when (this) {
        HaloEffectSpec.SpecializedField -> PrimaryVisualOperation.LightImpulse
        HaloEffectSpec.Supernova -> PrimaryVisualOperation.Supernova
        HaloEffectSpec.FullContour -> PrimaryVisualOperation.FullContourPulse
        is HaloEffectSpec.LuminousSegment -> PrimaryVisualOperation.TravelingLuminousSegment(lengthFraction)
        is HaloEffectSpec.Blade -> PrimaryVisualOperation.ForceBlade(variant)
    }
}
