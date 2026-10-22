package com.yp.luminote.app.rendering.gpu

import com.yp.luminote.app.animation.plan.*
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.rendering.api.*

/** Gate C admission is deliberately closed to the proven normal Snake source and optical stack. */
internal object GpuSnakeCapability {
    val productionModifiers: List<VisualModifier> = listOf(
        VisualModifier.IntensityPulse(.12f, .75f),
        VisualModifier.Blur(2.75f),
        VisualModifier.Glow(7.5f, .35f)
    )

    fun supports(emitter: VisualEmitterSpec): Boolean = reason(
        emitter.appearance, emitter.operation, emitter.luminousSegmentAppearance, emitter.modifiers
    ) == null

    fun unsupportedReason(intent: RenderIntent, mode: RenderMode, count: Int, command: FrameVisualCommand?): String? = when {
        intent != RenderIntent.DEBUG_GPU_SNAKE && intent != RenderIntent.PRODUCTION -> "GPU Snake intent is unsupported"
        mode != RenderMode.ANIMATION -> "GPU Snake does not support static calibration"
        count != 1 || command == null -> "GPU Snake requires exactly one contour command"
        command.geometry !is GeometryRequest.DisplayOutlineContour -> "GPU Snake requires DisplayOutlineContour"
        else -> reason(command.appearance, command.operation, command.luminousSegmentAppearance, command.modifiers)
    }

    private fun reason(appearance: AppearancePayload, operation: PrimaryVisualOperation,
        source: LuminousSegmentAppearance, modifiers: List<VisualModifier>): String? = when {
        operation !is PrimaryVisualOperation.TravelingLuminousSegment -> "GPU Snake requires TravelingLuminousSegment"
        operation.lengthFraction !in .05f.. .5f -> "GPU Snake length must be within .05.. .50"
        source != LuminousSegmentAppearance.FLOWING_SNAKE -> "GPU Snake requires the normal flowing Snake appearance"
        appearance.colorMode != HaloColorMode.SOLID || appearance.palette.size > 1 -> "GPU Snake supports solid single-color sources only"
        // Opacity is already consumed by FrameCommandCompiler, not by this executor.
        modifiers.filterNot { it is VisualModifier.Opacity }.let { it.isNotEmpty() && it != productionModifiers } ->
            "GPU Snake supports Baseline or the exact production Snake optical stack only"
        else -> null
    }
}
