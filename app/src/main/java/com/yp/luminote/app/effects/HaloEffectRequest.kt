package com.yp.luminote.app.effects

import android.os.SystemClock

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
    val enqueuedAt: Long = SystemClock.elapsedRealtime()
)