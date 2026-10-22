package com.yp.luminote.app.rendering.gpu

import kotlin.math.floor

/** Continuous evaluation of the approved Canvas Snake profile; no animation clock. */
internal object GpuSnakeSourceProfile {
    fun wrap(value: Float): Float = value - floor(value)
    fun relative(progress: Float, tail: Float): Float = wrap(progress - tail)
    fun smooth(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
    fun emission(u: Float): Float = .02f + .82f * smooth(0f, .74f, u) + .16f * smooth(.74f, 1f, u)
    fun width(u: Float): Float = .76f + .26f * smooth(0f, .74f, u) + .10f * smooth(.74f, 1f, u)
    /** Decode first, then unwrap. Interpolating the packed G/B bytes is not equivalent. */
    fun interpolateProgress(a: Float, b: Float, weight: Float): Float {
        val delta = b - a
        return wrap(a + (delta - floor(delta + .5f)) * weight)
    }
}
