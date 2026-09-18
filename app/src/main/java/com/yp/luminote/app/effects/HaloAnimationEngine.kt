package com.yp.luminote.app.effects

import com.yp.luminote.app.data.settings.HaloMotion

data class HaloAnimationState(
    val progress: Float = 0f,
    val phase: Float = 0f,
    val gradientPhase: Float = 0f,
    val running: Boolean = false,
    val ambient: Boolean = false
)

data class HaloAnimationRequest(
    val duration: Float,
    val interval: Float = 0f,
    val repeat: Boolean = false,
    val maxCycles: Int? = null,
    val preservePhase: Boolean = false,
    val motion: HaloMotion
)

data class HaloAmbientAnimationRequest(
    val effectSpeed: Float,
    val phaseStart: Float,
    val gradientPhaseStart: Float
)

internal interface HaloAnimationEngine {

    fun start(
        request: HaloAnimationRequest
    )

    fun startAmbient(
        request: HaloAmbientAnimationRequest
    )

    fun updateAmbientEffectSpeed(
        effectSpeed: Float
    )

    fun cancel()
}