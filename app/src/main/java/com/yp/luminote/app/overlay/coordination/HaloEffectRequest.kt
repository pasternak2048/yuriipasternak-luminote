package com.yp.luminote.app.overlay.coordination
import com.yp.luminote.app.effects.model.HaloConfig


/**
 * Immutable snapshot of a transient notification Halo effect.
 *
 * Requests are queued by notification source so effects from different apps
 * can play sequentially without interrupting each other.
 */
internal data class HaloEffectRequest(
    val packageName: String,
    val notificationKey: String,
    val config: HaloConfig,
    val paletteColors: IntArray?,
    val reminderColors: IntArray = intArrayOf(),
    /** Rebases only when a normal request waited behind an active reminder lease. */
    var enqueuedAt: Long = monotonicMillis()
)

/** JVM monotonic source; hosts never provide wall-clock time to coordination. */
internal fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
