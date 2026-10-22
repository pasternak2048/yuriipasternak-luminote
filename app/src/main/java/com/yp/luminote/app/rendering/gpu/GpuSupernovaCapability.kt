package com.yp.luminote.app.rendering.gpu

import com.yp.luminote.app.animation.plan.*
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.rendering.api.*

/** Gate E admission is deliberately closed to the proven Supernova source and optical stack. */
internal object GpuSupernovaCapability {
    val productionModifiers: List<VisualModifier> = listOf(
        VisualModifier.IntensityPulse(.18f, 1.65f),
        VisualModifier.Blur(4.5f),
        VisualModifier.Glow(11f, .50f)
    )

    fun supports(emitter: VisualEmitterSpec): Boolean = reason(
        emitter.appearance, emitter.operation, emitter.modifiers
    ) == null

    fun unsupportedReason(intent: RenderIntent, mode: RenderMode, count: Int, command: FrameVisualCommand?): String? = when {
        intent != RenderIntent.DEBUG_GPU_SUPERNOVA && intent != RenderIntent.PRODUCTION -> "GPU Supernova intent is unsupported"
        mode != RenderMode.ANIMATION -> "GPU Supernova does not support static calibration"
        count != 1 || command == null -> "GPU Supernova requires exactly one contour command"
        command.geometry !is GeometryRequest.DisplayOutlineContour -> "GPU Supernova requires DisplayOutlineContour"
        else -> reason(command.appearance, command.operation, command.modifiers)
    }

    private fun reason(appearance: AppearancePayload, operation: PrimaryVisualOperation,
        modifiers: List<VisualModifier>): String? = when {
        operation !is PrimaryVisualOperation.Supernova -> "GPU Supernova requires Supernova"
        appearance.colorMode != HaloColorMode.SOLID || appearance.palette.size > 1 -> "GPU Supernova supports solid single-color sources only"
        // Opacity is already consumed by FrameCommandCompiler, not by this executor.
        modifiers.filterNot { it is VisualModifier.Opacity }.let { it.isNotEmpty() && it != productionModifiers } ->
            "GPU Supernova supports Baseline or the exact production Supernova optical stack only"
        else -> null
    }
}
