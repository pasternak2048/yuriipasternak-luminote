package com.yp.luminote.app.notification

/** Pure time-window decision; storage and cleanup remain listener-owned. */
internal object NotificationDeduplicationPolicy {

    fun isDuplicate(
        previousPostTime: Long?,
        previousTimestamp: Long?,
        postTime: Long,
        now: Long,
        windowMs: Long
    ): Boolean =
        previousPostTime == postTime &&
                previousTimestamp != null &&
                now - previousTimestamp < windowMs
}
