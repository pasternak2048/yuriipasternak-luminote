package com.yp.luminote.app.notification

import com.yp.luminote.app.data.settings.HaloMode
import com.yp.luminote.app.data.settings.LuminoteSettings
import com.yp.luminote.app.data.settings.NotificationSource
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationEffectAdmissionPolicyTest {

    @Test
    fun `accepts an eligible notification`() {
        assertEquals(
            NotificationEffectAdmission.Accepted,
            evaluate()
        )
    }

    @Test
    fun `rejects own Android alert window notification first`() {
        assertRejected(
            expected = NotificationEffectRejectionReason.OWN_ALERT_WINDOW_NOTIFICATION,
            packageName = "android",
            key = "AlertWindowNotification - com.yp.luminote.app",
            settings = LuminoteSettings(
                haloMode = HaloMode.AMBIENT,
                haloIntensity = 0f,
                notificationSource = NotificationSource.SELECTED_APPS,
                selectedApps = emptySet()
            )
        )
    }

    @Test
    fun `rejects disabled notification mode or ambient mode before intensity and source`() {
        assertRejected(
            expected = NotificationEffectRejectionReason.NOTIFICATION_MODE_DISABLED_OR_AMBIENT,
            settings = LuminoteSettings(
                haloMode = HaloMode.OFF,
                haloIntensity = 0f,
                notificationSource = NotificationSource.SELECTED_APPS,
                selectedApps = emptySet()
            )
        )
        assertRejected(
            expected = NotificationEffectRejectionReason.NOTIFICATION_MODE_DISABLED_OR_AMBIENT,
            settings = LuminoteSettings(haloMode = HaloMode.AMBIENT)
        )
    }

    @Test
    fun `rejects zero intensity before excluded source`() {
        assertRejected(
            expected = NotificationEffectRejectionReason.ZERO_INTENSITY,
            settings = LuminoteSettings(
                haloIntensity = 0f,
                notificationSource = NotificationSource.SELECTED_APPS,
                selectedApps = emptySet()
            )
        )
    }

    @Test
    fun `rejects excluded selected-app source`() {
        assertRejected(
            expected = NotificationEffectRejectionReason.EXCLUDED_SOURCE,
            settings = LuminoteSettings(
                notificationSource = NotificationSource.SELECTED_APPS,
                selectedApps = setOf("com.example.selected")
            )
        )
    }

    @Test
    fun `retains existing all-apps and selected-app source semantics`() {
        assertEquals(
            NotificationEffectAdmission.Accepted,
            evaluate(
                settings = LuminoteSettings(
                    notificationSource = NotificationSource.ALL_APPS
                )
            )
        )
        assertEquals(
            NotificationEffectAdmission.Accepted,
            evaluate(
                settings = LuminoteSettings(
                    notificationSource = NotificationSource.SELECTED_APPS,
                    selectedApps = setOf(DEFAULT_PACKAGE_NAME)
                )
            )
        )
    }

    private fun assertRejected(
        expected: NotificationEffectRejectionReason,
        packageName: String = DEFAULT_PACKAGE_NAME,
        key: String = DEFAULT_KEY,
        settings: LuminoteSettings
    ) {
        assertEquals(
            NotificationEffectAdmission.Rejected(expected),
            evaluate(
                packageName = packageName,
                key = key,
                settings = settings
            )
        )
    }

    private fun evaluate(
        packageName: String = DEFAULT_PACKAGE_NAME,
        key: String = DEFAULT_KEY,
        settings: LuminoteSettings = LuminoteSettings()
    ): NotificationEffectAdmission =
        NotificationEffectAdmissionPolicy.evaluate(
            notificationPackageName = packageName,
            notificationKey = key,
            listenerPackageName = LISTENER_PACKAGE_NAME,
            settings = settings
        )

    private companion object {
        const val LISTENER_PACKAGE_NAME = "com.yp.luminote.app"
        const val DEFAULT_PACKAGE_NAME = "com.example.notification"
        const val DEFAULT_KEY = "notification-key"
    }
}
