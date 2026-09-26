package com.yp.luminote.app.notification

import com.yp.luminote.app.data.settings.NotificationSource

/**
 * Pure compatibility boundary for notification source filtering.
 *
 * Group summaries and non-clearable notifications intentionally remain
 * eligible. The listener's existing behavior has never excluded them here;
 * semantic classification is performed separately by [NotificationEventClassifier].
 */
internal object NotificationEligibilityDecider {

    fun shouldHandleSource(
        packageName: String,
        notificationSource: NotificationSource,
        selectedApps: Set<String>
    ): Boolean =
        when (notificationSource) {
            NotificationSource.ALL_APPS -> true
            NotificationSource.SELECTED_APPS -> packageName in selectedApps
        }
}
