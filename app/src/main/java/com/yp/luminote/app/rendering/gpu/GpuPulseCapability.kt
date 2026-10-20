package com.yp.luminote.app.rendering.gpu

import com.yp.luminote.app.animation.plan.AppearancePayload
import com.yp.luminote.app.animation.plan.PrimaryVisualOperation
import com.yp.luminote.app.animation.plan.VisualModifier
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.rendering.api.FrameVisualCommand
import com.yp.luminote.app.rendering.api.RenderIntent
import com.yp.luminote.app.rendering.api.RenderMode

/** Gate B's deliberately narrow admission boundary. Unsupported is an explicit Canvas route. */
internal object GpuPulseCapability {
    fun unsupportedReason(intent: RenderIntent, mode: RenderMode, commandCount: Int, command: FrameVisualCommand?): String? = when {
        intent != RenderIntent.DEBUG_GPU_PULSE -> "GPU Pulse is DEBUG-only"
        mode != RenderMode.ANIMATION -> "GPU Pulse does not support static calibration"
        commandCount != 1 || command == null -> "GPU Pulse requires exactly one DisplayOutlineContour command"
        command.operation !is PrimaryVisualOperation.FullContourPulse -> "GPU Pulse supports FullContourPulse only"
        command.modifiers.isNotEmpty() -> "GPU Pulse supports an empty modifier stack only"
        command.appearance.colorMode != HaloColorMode.SOLID -> "GPU Pulse has no proven Canvas-equivalent gradient source"
        command.appearance.palette.size > 1 -> "GPU Pulse does not silently reinterpret a phase-blended solid palette"
        else -> null
    }

    /** Kept public-to-module for unit tests without exposing executor implementation details. */
    fun supports(appearance: AppearancePayload, operation: PrimaryVisualOperation, modifiers: List<VisualModifier>): Boolean =
        appearance.colorMode == HaloColorMode.SOLID && appearance.palette.size <= 1 && operation is PrimaryVisualOperation.FullContourPulse && modifiers.isEmpty()
}
