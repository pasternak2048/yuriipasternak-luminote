package com.yp.luminote.app.animation

/** Backend-neutral phase ownership for the dedicated 2.2-second reminder impulse. */
internal enum class LightImpulsePhase { IGNITION, TRAVEL, CONVERGE, FADE }

internal const val LIGHT_IMPULSE_DURATION_SECONDS = 2.2f
internal const val LIGHT_IGNITION_END = 0.09f
internal const val LIGHT_TRAVEL_END = 0.80f
internal const val LIGHT_CONVERGE_END = 0.92f
internal const val LIGHT_TAIL_FRACTION = 0.165f
internal const val LIGHT_CORE_FRACTION = 0.022f
internal const val LIGHT_CORE_SPAN_MULTIPLIER = 4.0f
internal const val LIGHT_ATTACHED_TAIL_BODY_SHARE = 0.55f
internal const val LIGHT_TERMINAL_SOURCE_RADIUS_FRACTION = 0.040f
internal const val LIGHT_TAIL_SAMPLES = 40
internal const val LIGHT_CORE_SAMPLES = 28
internal const val LIGHT_BLOOM_SAMPLES = 40
internal const val LIGHT_BRANCH_EMERGENCE_TRAVEL = 0.08f
internal const val LIGHT_TAIL_PASS_SHARE = 0.46f
internal const val LIGHT_CORE_PASS_SHARE = 1f

/** Boundary inclusivity is intentional: a boundary belongs to its following phase. */
internal fun lightImpulsePhase(progress: Float): LightImpulsePhase = when {
    progress < LIGHT_IGNITION_END -> LightImpulsePhase.IGNITION
    progress < LIGHT_TRAVEL_END -> LightImpulsePhase.TRAVEL
    progress < LIGHT_CONVERGE_END -> LightImpulsePhase.CONVERGE
    else -> LightImpulsePhase.FADE
}
