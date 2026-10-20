package com.yp.luminote.app.rendering.api

import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.animation.plan.VisualPlanSpec

/**
 * Backend-neutral rendering boundary. Targets are host-owned, ephemeral adapters and must never
 * be retained by an implementation.
 */
internal interface HaloRenderBackend {
    fun updateConfig(config: HaloConfig)

    /** Debug-only plan injection remains shared so a diagnostic backend cannot observe stale input. */
    fun updateDebugVisualPlan(plan: VisualPlanSpec) = Unit

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

/** Rendering intent is host-owned; DEBUG_GPU_WITNESS never describes a production animation. */
internal enum class RenderIntent {
    PRODUCTION,
    DEBUG_GPU_WITNESS,
    /** Deliberate DEBUG-only Gate B route. Production remains Canvas-selected. */
    DEBUG_GPU_PULSE
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
    /** Set only by the debug host selector; production plans never opt into the Gate A witness. */
    var intent: RenderIntent = RenderIntent.PRODUCTION
}

internal sealed interface RenderOutcome {
    object Rendered : RenderOutcome
    data class Unsupported(val reason: String) : RenderOutcome
    /** A post-submission error must not trigger a Canvas draw over partially written GPU pixels. */
    data class Error(val cause: Throwable, val fallbackEligible: Boolean = true) : RenderOutcome
}
