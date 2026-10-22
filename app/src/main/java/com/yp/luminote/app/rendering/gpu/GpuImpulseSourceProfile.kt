package com.yp.luminote.app.rendering.gpu

import com.yp.luminote.app.animation.*

/** Small scalar packet from shared choreography; no sampled pixels or independent clock.
 * RuntimeShader evaluates the existing source equations continuously on the contour field.
 */
internal class GpuImpulseSourceProfile {
    val plan = LightImpulseProductionDrawPlan()
    var progress = 1f
        private set
    var energy = lightImpulsePassEnergy(1f)
        private set

    fun update(progress: Float, length: Float, endpoints: LightImpulseEndpoints) {
        this.progress = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 1f
        lightImpulseProductionDrawPlan(this.progress, length, endpoints.forwardDistance, endpoints.reverseDistance, plan)
        energy = lightImpulsePassEnergy(this.progress)
    }
}
