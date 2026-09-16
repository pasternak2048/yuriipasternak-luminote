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

    /**
     * Classifies a live notification callback.
     *
     * Media notifications are stateful here: a changed title/text pair is a
     * meaningful event, while repeated callbacks with identical metadata are
     * treated as technical refreshes.
     */
    fun classify(
        sbn: StatusBarNotification,
        ranking: NotificationListenerService.Ranking?
    ): NotificationEvent {
        val snapshotEvent =
            classifySnapshot(
                sbn = sbn,
                ranking = ranking
            )

        if (
            snapshotEvent !is
                    NotificationEvent.Media
        ) {
            return snapshotEvent
        }

        return classifyMediaUpdate(
            sbn = sbn,
            media = snapshotEvent
        )
    }

    /**
     * Stateless classification of an existing notification.
     *
     * This is safe for rebuilding KEEP_VISIBLE state because it does not
     * mutate the media fingerprint cache.
     */
    fun classifySnapshot(
        sbn: StatusBarNotification,
        ranking: NotificationListenerService.Ranking?
    ): NotificationEvent {
        val notification =
            sbn.notification

        val category =
            notification.category

        val isOngoing =
            (
                    notification.flags and
                            Notification.FLAG_ONGOING_EVENT
                    ) != 0

        val isForegroundService =
            (
                    notification.flags and
                            Notification.FLAG_FOREGROUND_SERVICE
                    ) != 0

        val isMedia =
            category ==
                    Notification.CATEGORY_TRANSPORT ||
                    notification.extras.containsKey(
                        Notification.EXTRA_MEDIA_SESSION
                    )

        /*
         * Media must be detected before foreground-service state.
         *
         * Media apps commonly expose their player through a foreground
         * service. The notification itself is relevant to KEEP_VISIBLE;
         * whether a live callback represents a new track is handled by
         * classifyMediaUpdate().
         */
        if (isMedia) {
            return NotificationEvent.Media(
                title =
                    notification.extras
                        .getCharSequence(
                            Notification.EXTRA_TITLE
                        )
                        ?.toString(),
                text =
                    notification.extras
                        .getCharSequence(
                            Notification.EXTRA_TEXT
                        )
                        ?.toString(),
                ongoing = isOngoing
            )
        }

        /*
         * Message notifications are explicitly user-facing, including
         * notifications delivered through silent channels.
         */
        if (
            category ==
            Notification.CATEGORY_MESSAGE
        ) {
            return NotificationEvent.UserVisible(
                category = category,
                ongoing = isOngoing
            )
        }

        /*
         * Foreground-service notifications represent service state rather
         * than a new user-facing notification event.
         */
        if (isForegroundService) {
            return NotificationEvent.TechnicalUpdate(
                reason = "foreground-service"
            )
        }

        /*
         * Do not infer progress merely from the presence of progress extras.
         * A positive progress maximum indicates an actual progress
         * notification.
         */
        val progressMax =
            notification.extras.getInt(
                Notification.EXTRA_PROGRESS_MAX,
                0
            )

        if (progressMax > 0) {
            return NotificationEvent.TechnicalUpdate(
                reason = "progress"
            )
        }

        return NotificationEvent.UserVisible(
            category = category,
            ongoing = isOngoing
        )
    }

    private fun classifyMediaUpdate(
        sbn: StatusBarNotification,
        media: NotificationEvent.Media
    ): NotificationEvent {
        val fingerprint =
            MediaFingerprint(
                title = media.title,
                text = media.text
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

    fun onNotificationRemoved(
        sbn: StatusBarNotification
    ) {
        mediaFingerprints.remove(
            sbn.key
        )
    }
}

internal sealed interface NotificationEvent {

    data class UserVisible(
        val category: String?,
        val ongoing: Boolean
    ) : NotificationEvent

    /**
     * Stateless representation of an active media notification.
     *
     * Used by KEEP_VISIBLE snapshot reconstruction only.
     */
    data class Media(
        val title: String?,
        val text: String?,
        val ongoing: Boolean
    ) : NotificationEvent

    /**
     * Meaningful live change of media metadata.
     */
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