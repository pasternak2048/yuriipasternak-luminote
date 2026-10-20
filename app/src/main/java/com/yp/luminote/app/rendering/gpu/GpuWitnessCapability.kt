package com.yp.luminote.app.rendering.gpu

import com.yp.luminote.app.rendering.api.RenderMode
import com.yp.luminote.app.rendering.api.RenderIntent

/**
 * Gate A deliberately supports no production plan.  The RuntimeShader is a diagnostic witness,
 * not an alternative interpretation of an animation.  Keeping this policy pure also makes the
 * eventual per-operation capability matrix independently testable.
 */
internal object GpuWitnessCapability {
    fun unsupportedReason(
        intent: RenderIntent,
        mode: RenderMode,
        commandCount: Int,
        hasModifiers: Boolean
    ): String? = when {
        intent != RenderIntent.DEBUG_GPU_WITNESS -> "GPU witness is DEBUG-only; production visual operations are unsupported"
        mode != RenderMode.ANIMATION -> "GPU witness does not support static calibration"
        hasModifiers -> "GPU witness does not support production visual modifiers"
        commandCount == 0 -> "GPU witness requires exactly one compiled visual command"
        commandCount != 1 -> "GPU witness supports exactly one debug witness command"
        else -> null
    }
}
