package com.yp.luminote.app.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

internal class NotificationEventClassifier {

    private data class MediaFingerprint(
        val title: String?,
        val text: String?
    )

    private val mediaFingerprints =
        mutableMapOf<String, MediaFingerprint>()

    fun classify(
        sbn: StatusBarNotification,
        ranking: NotificationListenerService.Ranking?
    ): NotificationEvent {
        val notification =
            sbn.notification

        val category =
            notification.category

        val isOngoing =
            (notification.flags and
                    Notification.FLAG_ONGOING_EVENT) != 0

        val isForegroundService =
            (notification.flags and
                    Notification.FLAG_FOREGROUND_SERVICE) != 0

        val isMedia =
            category == Notification.CATEGORY_TRANSPORT ||
                    notification.extras.containsKey(
                        Notification.EXTRA_MEDIA_SESSION
                    )

        /*
         * Media must be detected before foreground-service state.
         *
         * Media apps commonly keep their player notification through a
         * foreground service. A changed title/artist represents a meaningful
         * media event, while repeated updates with the same metadata are
         * treated as technical refreshes.
         */
        if (isMedia) {
            val fingerprint =
                MediaFingerprint(
                    title =
                        notification.extras
                            .getCharSequence(Notification.EXTRA_TITLE)
                            ?.toString(),
                    text =
                        notification.extras
                            .getCharSequence(Notification.EXTRA_TEXT)
                            ?.toString()
                )

            val previous =
                mediaFingerprints.put(
                    sbn.key,
                    fingerprint
                )

            return if (
                previous == null ||
                previous != fingerprint
            ) {
                NotificationEvent.MediaChanged(
                    title = fingerprint.title,
                    text = fingerprint.text
                )
            } else {
                NotificationEvent.TechnicalUpdate(
                    reason = "unchanged-media"
                )
            }
        }

        /*
         * Message notifications are explicitly user-facing.
         *
         * Do not infer progress state merely from the presence of progress
         * extras: real messaging apps can carry those keys as well.
         */
        if (category == Notification.CATEGORY_MESSAGE) {
            return NotificationEvent.UserVisible(
                category = category,
                ongoing = isOngoing
            )
        }

        if (isForegroundService) {
            return NotificationEvent.TechnicalUpdate(
                reason = "foreground-service"
            )
        }

        val progressMax =
            notification.extras.getInt(
                Notification.EXTRA_PROGRESS_MAX,
                0
            )

        val hasRealProgress =
            progressMax > 0

        if (hasRealProgress) {
            return NotificationEvent.TechnicalUpdate(
                reason = "progress"
            )
        }

        return NotificationEvent.UserVisible(
            category = category,
            ongoing = isOngoing
        )
    }

    fun onNotificationRemoved(
        sbn: StatusBarNotification
    ) {
        mediaFingerprints.remove(sbn.key)
    }
}

internal sealed interface NotificationEvent {

    data class UserVisible(
        val category: String?,
        val ongoing: Boolean
    ) : NotificationEvent

    data class MediaChanged(
        val title: String?,
        val text: String?
    ) : NotificationEvent

    data class TechnicalUpdate(
        val reason: String
    ) : NotificationEvent

    data class SystemEvent(
        val reason: String
    ) : NotificationEvent
}