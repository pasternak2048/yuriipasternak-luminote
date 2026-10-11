package com.yp.luminote.app.rendering.api

import com.yp.luminote.app.effects.model.HaloConfig

/**
 * Backend-neutral rendering boundary. Targets are host-owned, ephemeral adapters and must never
 * be retained by an implementation.
 */
internal interface HaloRenderBackend {
    fun updateConfig(config: HaloConfig)

    /** Called after the host mutates its display geometry or surface. */
    fun invalidateSurface()

    fun render(target: HaloRenderTarget, frame: RenderFrame): RenderOutcome

    /** Idempotent terminal cleanup for backend-owned resources. */
    fun dispose()
}

/** Opaque host target; graphics API types deliberately stay in backend adapters. */
internal interface HaloRenderTarget

internal enum class RenderMode {
    ANIMATION,
    STATIC_CALIBRATION
}

/** Mutable, host-owned frame values, reused across draws to keep frame work allocation-free. */
internal class RenderFrame {
    var mode: RenderMode = RenderMode.ANIMATION
    var animationProgress: Float = 0f
    var effectPhase: Float = 0f
    var gradientPhase: Float = 0f
    var frameTimeNanos: Long = 0L
    var viewportWidth: Int = 0
    var viewportHeight: Int = 0
    var calibrationRulerLegend: String = ""
    var calibrationRegistrationLabel: String = ""
}

internal sealed interface RenderOutcome {
    object Rendered : RenderOutcome
    data class Unsupported(val reason: String) : RenderOutcome
    data class Error(val cause: Throwable) : RenderOutcome
}
