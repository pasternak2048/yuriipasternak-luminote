package com.yp.luminote.app.rendering.gpu

/** Supernova's finite cycle, independently matching its Canvas ignition and terminal fade. */
internal object GpuSupernovaSourceProfile {
    fun intensity(phase: Float): Float {
        if (!phase.isFinite()) return 0f
        val progress = (phase % 1f + 1f) % 1f
        val value = if (progress < .09f) progress/.09f else (progress-.09f)/.91f
        val t = value.coerceIn(0f,1f)
        val eased = t*t*(3f-2f*t)
        return if (progress < .09f) eased else 1f-eased
    }
}
