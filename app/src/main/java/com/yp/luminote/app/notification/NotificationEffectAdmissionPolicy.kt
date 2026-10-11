package com.yp.luminote.app.notification

import com.yp.luminote.app.data.settings.LuminoteSettings

/**
 * Pure, ordered admission decision for a semantically relevant, de-duplicated
 * notification. Source eligibility remains owned by
 * [NotificationEligibilityDecider].
 */
internal object NotificationEffectAdmissionPolicy {

    fun evaluate(
        notificationPackageName: String,
        notificationKey: String,
        listenerPackageName: String,
        settings: LuminoteSettings
    ): NotificationEffectAdmission {
        if (
            notificationPackageName == ANDROID_PACKAGE_NAME &&
            notificationKey.contains(
                "AlertWindowNotification - $listenerPackageName"
            )
        ) {
            return NotificationEffectAdmission.Rejected(
                NotificationEffectRejectionReason.OWN_ALERT_WINDOW_NOTIFICATION
            )
        }

        if (!settings.haloEnabled || settings.ambientEnabled) {
            return NotificationEffectAdmission.Rejected(
                NotificationEffectRejectionReason.NOTIFICATION_MODE_DISABLED_OR_AMBIENT
            )
        }

        if (settings.haloIntensity <= 0f) {
            return NotificationEffectAdmission.Rejected(
                NotificationEffectRejectionReason.ZERO_INTENSITY
            )
        }

        if (
            !NotificationEligibilityDecider.shouldHandleSource(
                packageName = notificationPackageName,
                notificationSource = settings.notificationSource,
                selectedApps = settings.selectedApps
            )
        ) {
            return NotificationEffectAdmission.Rejected(
                NotificationEffectRejectionReason.EXCLUDED_SOURCE
            )
        }

        return NotificationEffectAdmission.Accepted
    }

    private const val ANDROID_PACKAGE_NAME = "android"
}

internal sealed interface NotificationEffectAdmission {
    data object Accepted : NotificationEffectAdmission

    data class Rejected(
        val reason: NotificationEffectRejectionReason
    ) : NotificationEffectAdmission
}

internal enum class NotificationEffectRejectionReason {
    OWN_ALERT_WINDOW_NOTIFICATION,
    NOTIFICATION_MODE_DISABLED_OR_AMBIENT,
    ZERO_INTENSITY,
    EXCLUDED_SOURCE
}
