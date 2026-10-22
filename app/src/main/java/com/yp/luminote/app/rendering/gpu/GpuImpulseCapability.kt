package com.yp.luminote.app.rendering.gpu

import com.yp.luminote.app.animation.plan.*
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.rendering.api.*

/** D1 admits only the normal registry Impulse base source; reminder and optics stay closed. */
internal object GpuImpulseCapability {
    val productionModifiers: List<VisualModifier> = listOf(
        VisualModifier.IntensityPulse(.22f, 1.15f), VisualModifier.Blur(3.5f), VisualModifier.Glow(7f, .42f)
    )
    fun supports(emitter: VisualEmitterSpec): Boolean =
        emitter.modifierProfile == VisualModifierProfile.NORMAL_IMPULSE && reason(
            emitter.appearance, emitter.operation, emitter.modifiers) == null

    fun unsupportedReason(intent: RenderIntent, mode: RenderMode, count: Int,
        command: FrameVisualCommand?, normalProfile: Boolean): String? = when {
        intent != RenderIntent.DEBUG_GPU_IMPULSE && intent != RenderIntent.PRODUCTION -> "GPU Impulse intent is unsupported"
        mode != RenderMode.ANIMATION -> "GPU Impulse does not support static calibration"
        count != 1 || command == null -> "GPU Impulse requires exactly one contour command"
        command.geometry !is GeometryRequest.DisplayOutlineContour -> "GPU Impulse requires DisplayOutlineContour"
        !normalProfile -> "GPU Impulse requires NORMAL_IMPULSE; Reminder Light Impulse is excluded"
        else -> reason(command.appearance, command.operation, command.modifiers)
    }

    private fun reason(appearance: AppearancePayload, operation: PrimaryVisualOperation,
        modifiers: List<VisualModifier>): String? = when {
        operation !is PrimaryVisualOperation.LightImpulse -> "GPU Impulse requires LightImpulse"
        appearance.colorMode != HaloColorMode.SOLID || appearance.palette.size > 1 -> "GPU Impulse supports solid single-color sources only"
        modifiers.isNotEmpty() && modifiers != productionModifiers -> "GPU Impulse supports Baseline or the exact production Impulse optical stack only"
        else -> null
    }
}
