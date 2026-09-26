package com.yp.luminote.app.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDeduplicationPolicyTest {

    @Test
    fun `same post within window is duplicate`() {
        assertTrue(
            NotificationDeduplicationPolicy.isDuplicate(
                previousPostTime = 42L,
                previousTimestamp = 1_000L,
                postTime = 42L,
                now = 1_099L,
                windowMs = 100L
            )
        )
    }

    @Test
    fun `dedup window boundary and changed post are not duplicates`() {
        assertFalse(
            NotificationDeduplicationPolicy.isDuplicate(
                previousPostTime = 42L,
                previousTimestamp = 1_000L,
                postTime = 42L,
                now = 1_100L,
                windowMs = 100L
            )
        )
        assertFalse(
            NotificationDeduplicationPolicy.isDuplicate(
                previousPostTime = 42L,
                previousTimestamp = 1_050L,
                postTime = 43L,
                now = 1_051L,
                windowMs = 100L
            )
        )
    }

    @Test
    fun `missing prior entry is not duplicate`() {
        assertFalse(
            NotificationDeduplicationPolicy.isDuplicate(
                previousPostTime = null,
                previousTimestamp = null,
                postTime = 42L,
                now = 1_000L,
                windowMs = 100L
            )
        )
    }
}
