package com.yp.luminote.app.animation

import com.yp.luminote.app.animation.definitions.HaloAnimationDefinition
import com.yp.luminote.app.animation.definitions.HaloAnimationId
import com.yp.luminote.app.animation.definitions.LuminoteHaloAnimations

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
    val motion: HaloAnimationId,
    /** Resolved once at request construction; the frame engine never discovers by ID. */
    val definition: HaloAnimationDefinition = LuminoteHaloAnimations.registry.definition(motion)
)

data class HaloAmbientAnimationRequest(
    val effectSpeed: Float,
    val phaseStart: Float,
    val gradientPhaseStart: Float,
    val motion: HaloAnimationId,
    /** Resolved once at request construction; carries the ambient alpha policy. */
    val definition: HaloAnimationDefinition = LuminoteHaloAnimations.registry.definition(motion)
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
