package com.yp.luminote.app.notification

import com.yp.luminote.app.data.settings.NotificationSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationEligibilityDeciderTest {

    @Test
    fun `all apps source accepts every package`() {
        assertTrue(
            NotificationEligibilityDecider.shouldHandleSource(
                packageName = "com.example.unselected",
                notificationSource = NotificationSource.ALL_APPS,
                selectedApps = emptySet()
            )
        )
    }

    @Test
    fun `selected apps source accepts only selected package`() {
        val selectedApps = setOf("com.example.selected")

        assertTrue(
            NotificationEligibilityDecider.shouldHandleSource(
                packageName = "com.example.selected",
                notificationSource = NotificationSource.SELECTED_APPS,
                selectedApps = selectedApps
            )
        )
        assertFalse(
            NotificationEligibilityDecider.shouldHandleSource(
                packageName = "com.example.unselected",
                notificationSource = NotificationSource.SELECTED_APPS,
                selectedApps = selectedApps
            )
        )
    }

    @Test
    fun `source selection has no group summary or clearability exclusion`() {
        /*
         * This characterizes the legacy listener behavior: those framework
         * properties are not source-filter criteria. Their semantic meaning
         * remains the responsibility of NotificationEventClassifier.
         */
        assertTrue(
            NotificationEligibilityDecider.shouldHandleSource(
                packageName = "com.example.group.summary",
                notificationSource = NotificationSource.ALL_APPS,
                selectedApps = emptySet()
            )
        )
        assertTrue(
            NotificationEligibilityDecider.shouldHandleSource(
                packageName = "com.example.non.clearable",
                notificationSource = NotificationSource.SELECTED_APPS,
                selectedApps = setOf("com.example.non.clearable")
            )
        )
    }
}
