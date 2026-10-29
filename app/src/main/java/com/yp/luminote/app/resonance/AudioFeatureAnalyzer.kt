package com.yp.luminote.app.resonance

import kotlin.math.*

/** No callback audio arrays escape the visualization worker. Only these bounded, immutable statistics are published. */
internal data class AudioFeatureSnapshot(
    val generation: Long = 0L,
    val timestampNanos: Long = 0L,
    val low: Float = 0f,
    val mid: Float = 0f,
    val high: Float = 0f,
    val loudness: Float = 0f,
    val onset: Float = 0f,
    val trend: Float = 0f
)

/** All evolution consumes HaloView VSYNC time; missing capture frames decay without an audio timer. */
internal class ResonanceBehavior {
    var low = 0f; private set
    var mid = 0f; private set
    var high = 0f; private set
    var loudness = 0f; private set
    var impulse = 0f; private set
    var buildup = 0f; private set
    var phase = 0f; private set
    private var lastTime = 0L
    private var consumedSnapshot = 0L

    fun update(snapshot: AudioFeatureSnapshot, generation: Long, timeNanos: Long) {
        val dt = if (lastTime == 0L) 0f else ((timeNanos - lastTime) / 1e9f).coerceIn(0f, .1f)
        lastTime = timeNanos
        val age = ((timeNanos - snapshot.timestampNanos) / 1e9f).coerceAtLeast(0f)
        val freshness = if (snapshot.generation != generation) 0f else exp(-max(0f, age - .15f) * 5f)
        val blend = 1f - exp(-dt * 18f)
        low += (snapshot.low * freshness - low) * blend
        mid += (snapshot.mid * freshness - mid) * blend
        high += (snapshot.high * freshness - high) * blend
        loudness += (snapshot.loudness * freshness - loudness) * blend
        impulse *= exp(-dt * 4f)
        if (snapshot.generation == generation && snapshot.timestampNanos != consumedSnapshot) {
            impulse = max(impulse, snapshot.onset * freshness)
            consumedSnapshot = snapshot.timestampNanos
        }
        buildup += ((loudness + max(0f, snapshot.trend) * freshness) * .65f - buildup) * (1f - exp(-dt * 1.4f))
        phase = (phase + dt * (.035f + .1f * impulse + .035f * buildup)) % 1f
    }
}
