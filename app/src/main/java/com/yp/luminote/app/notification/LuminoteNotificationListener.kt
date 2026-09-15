package com.yp.luminote.app.notification

import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.yp.luminote.app.data.settings.LuminoteSettings
import com.yp.luminote.app.data.settings.LuminoteSettingsRepository
import com.yp.luminote.app.data.settings.NotificationSource
import com.yp.luminote.app.data.settings.NotificationPlayback
import com.yp.luminote.app.data.settings.GradientPalette
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.data.settings.HaloColorSource
import com.yp.luminote.app.effects.HaloOverlayService
import com.yp.luminote.app.effects.HaloConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

class LuminoteNotificationListener :
    NotificationListenerService() {

    private val serviceJob =
        SupervisorJob()

    private val serviceScope =
        CoroutineScope(
            serviceJob +
                    Dispatchers.IO
        )

    private lateinit var settingsRepository:
            LuminoteSettingsRepository

    private lateinit var appIconColorResolver: AppIconColorResolver

    private val cachedSettings =
        AtomicReference<LuminoteSettings?>(null)

    private var previousSettings: LuminoteSettings? = null

    private val settingsReady =
        CompletableDeferred<LuminoteSettings>()

    private val rankingByThread =
        ThreadLocal<NotificationListenerService.Ranking>()

    /*
     * Recently processed notification keys.
     *
     * Android can call onNotificationPosted()
     * multiple times for the same notification.
     */
    private val recentNotifications =
        mutableMapOf<String, RecentNotification>()

    private val recentNotificationOrder =
        ArrayDeque<RecentNotification>()

    private val dedupLock =
        Any()

    /* Keeps one entry per notification for the active app-color palette. */
    private val activeNotificationPackages = linkedMapOf<String, String>()
    /* All active notifications from apps allowed to contribute palette colors. */
    private val activePaletteNotificationPackages = linkedMapOf<String, String>()
    private val activeNotificationsLock = Any()

    override fun onCreate() {

        super.onCreate()

        settingsRepository =
            LuminoteSettingsRepository(
                this
            )

        appIconColorResolver = AppIconColorResolver(this)

        serviceScope.launch {
            settingsRepository.settings.collect { settings ->
                val previous = previousSettings
                if (settings.ambientEnabled) {
                    HaloOverlayService.start(
                        this@LuminoteNotificationListener,
                        HaloOverlayService.createAmbientIntent(this@LuminoteNotificationListener, settings)
                    )
                    previousSettings = settings
                    cachedSettings.set(settings)
                    settingsReady.complete(settings)
                    return@collect
                }
                if (!settings.haloEnabled) {
                    if (
                        previous?.ambientEnabled == true ||
                            previous?.haloEnabled == true ||
                            previous?.notificationPlayback == NotificationPlayback.KEEP_VISIBLE
                    ) {
                        HaloOverlayService.start(
                            this@LuminoteNotificationListener,
                            HaloOverlayService.createStopRepeatingIntent(this@LuminoteNotificationListener)
                        )
                    }
                    previousSettings = settings
                    cachedSettings.set(settings)
                    settingsReady.complete(settings)
                    return@collect
                }
                if (
                    previous?.ambientEnabled == true ||
                        (previous?.notificationPlayback == NotificationPlayback.KEEP_VISIBLE &&
                            settings.notificationPlayback != NotificationPlayback.KEEP_VISIBLE)
                ) {
                    HaloOverlayService.start(
                        this@LuminoteNotificationListener,
                        HaloOverlayService.createStopRepeatingIntent(this@LuminoteNotificationListener)
                    )
                }
                previousSettings = settings
                cachedSettings.set(settings)
                settingsReady.complete(settings)
                if (isPersistentReminder(settings)) {
                    refreshActiveNotifications(settings)
                }
            }
        }

        Log.d(
            TAG,
            "Notification listener created"
        )
    }

    override fun onListenerConnected() {

        super.onListenerConnected()

        val activeNotificationsSnapshot = activeNotifications
        val rankingMap = currentRanking
        serviceScope.launch {
            val settings = cachedSettings.get() ?: settingsReady.await()
            restoreActiveNotifications(activeNotificationsSnapshot, rankingMap, settings)
        }

        Log.d(
            TAG,
            "Notification listener connected"
        )
    }

    override fun onListenerDisconnected() {

        super.onListenerDisconnected()

        Log.d(
            TAG,
            "Notification listener disconnected"
        )
    }

    override fun onNotificationPosted(
        sbn: StatusBarNotification,
        rankingMap: NotificationListenerService.RankingMap
    ) {

        super.onNotificationPosted(sbn, rankingMap)

        Log.d(TAG, "Posted: key=${sbn.key}, package=${sbn.packageName}, postTime=${sbn.postTime}")

        if (cachedSettings.get()?.ambientEnabled == true) {
            Log.d(TAG, "Skipped: ambient halo is enabled")
            return
        }

        if (cachedSettings.get()?.haloEnabled == false) {
            Log.d(TAG, "Skipped: Luminote Halo is disabled")
            return
        }

        cachedSettings.get()?.let { settings ->
            if (shouldTrackPaletteNotification(sbn, settings)) {
                synchronized(activeNotificationsLock) {
                    activePaletteNotificationPackages[sbn.key] = sbn.packageName
                }
            }
        }

        if (!shouldShowEffect(sbn, rankingMap)) {
            Log.d(TAG, "Skipped: notification is silent")
            cachedSettings.get()?.let(::startPersistentReminderIfNeeded)
            return
        }

        /*
         * Ignore repeated callbacks for the same
         * notification inside the deduplication window.
         */
        if (
            isDuplicateNotification(
                sbn
            )
        ) {
            Log.d(TAG, "Skipped: duplicate callback")

            return
        }

        val settings = cachedSettings.get()
        if (settings != null) {
            handleEligibleNotification(sbn, settings)
            return
        }

        /* Only the first callback after listener startup can take this path. */
        serviceScope.launch {
            handleEligibleNotification(sbn, settingsReady.await())
        }
    }

    private fun handleEligibleNotification(
        sbn: StatusBarNotification,
        settings: LuminoteSettings
    ) {
        if (
            sbn.packageName == "android" &&
            sbn.key.contains("AlertWindowNotification - $packageName")
        ) {
            Log.d(
                TAG,
                "Ignoring own alert-window notification: key=${sbn.key}"
            )
            return
        }

        if (!settings.haloEnabled || settings.ambientEnabled) return

        if (settings.haloIntensity <= 0f) {
            Log.d(TAG, "Skipped: halo intensity is zero")
            return
        }

        val shouldHandle = shouldHandleSource(sbn, settings)

        if (!shouldHandle) {
            Log.d(TAG, "Skipped: package is not selected")
            return
        }

        synchronized(activeNotificationsLock) {
            activeNotificationPackages[sbn.key] = sbn.packageName
            if (shouldTrackPaletteNotification(sbn, settings)) {
                activePaletteNotificationPackages[sbn.key] = sbn.packageName
            }
        }

        val effectSettings = settings.copy(
            haloColor = if (settings.colorSource == HaloColorSource.APP_ICON) {
                appIconColorResolver.colorFor(sbn.packageName, settings.haloColor)
            } else {
                settings.haloColor
            },
            haloRepeatCount = when (settings.notificationPlayback) {
                NotificationPlayback.ONCE -> 1
                NotificationPlayback.REPEAT -> settings.haloRepeatCount
                NotificationPlayback.KEEP_VISIBLE -> -1
            }
        )
        val isGradient = settings.colorSource == HaloColorSource.GRADIENT
        val palette = when {
            isGradient && settings.gradientPalette == GradientPalette.NOTIFICATION_APPS -> activePaletteColors(
                settings = settings,
                useAppColors = true
            )
            isGradient -> HaloConfig.defaultGradientPalette()
            settings.notificationPlayback == NotificationPlayback.KEEP_VISIBLE -> activePaletteColors(settings)
            else -> null
        }
        Log.d(TAG, "Effect started: key=${sbn.key}, postTime=${sbn.postTime}")
        startTransientEffect(
            settings = effectSettings,
            paletteColors = palette,
            restart = true,
            packageName = sbn.packageName,
            notificationKey = sbn.key
        )
    }

    private fun startTransientEffect(
        settings: LuminoteSettings,
        paletteColors: IntArray? = null,
        restart: Boolean = false,
        packageName: String? = null,
        notificationKey: String? = null
    ) {
        HaloOverlayService.start(
            context = this,
            intent = HaloOverlayService.createIntent(
                context = this,
                settings = settings,
                paletteColors = paletteColors,
                restart = restart,
                packageName = packageName,
                notificationKey = notificationKey
            )
        )
    }

    private fun activePaletteColors(
        settings: LuminoteSettings,
        useAppColors: Boolean = settings.colorSource == HaloColorSource.APP_ICON
    ): IntArray = synchronized(activeNotificationsLock) {
        activePaletteNotificationPackages.values
            .distinct()
            .map { packageName ->
                if (useAppColors) {
                    appIconColorResolver.colorFor(packageName, settings.haloColor)
                } else {
                    settings.haloColor
                }
            }
            .toIntArray()
    }

    private fun restoreActiveNotifications(
        notifications: Array<StatusBarNotification>,
        rankingMap: NotificationListenerService.RankingMap,
        settings: LuminoteSettings
    ) {
        if (!settings.haloEnabled || settings.ambientEnabled) return

        synchronized(activeNotificationsLock) {
            activeNotificationPackages.clear()
            activePaletteNotificationPackages.clear()
            notifications.forEach { notification ->
                if (shouldTrackPaletteNotification(notification, settings)) {
                    activePaletteNotificationPackages[notification.key] = notification.packageName
                }
                if (
                    shouldShowEffect(notification, rankingMap) &&
                    shouldHandleSource(notification, settings)
                ) {
                    activeNotificationPackages[notification.key] = notification.packageName
                }
            }
        }

        startPersistentReminderIfNeeded(settings)
    }

    /**
     * Keep Visible must also reflect alerts that existed before a mode or app
     * filter changed. Notification callbacks alone cannot provide that
     * guarantee, so rebuild the small in-memory snapshot from the system list.
     */
    private fun refreshActiveNotifications(settings: LuminoteSettings) {
        restoreActiveNotifications(
            notifications = activeNotifications,
            rankingMap = currentRanking,
            settings = settings
        )
    }

    private fun isPersistentReminder(settings: LuminoteSettings): Boolean =
        settings.haloEnabled && settings.notificationPlayback == NotificationPlayback.KEEP_VISIBLE

    private fun startPersistentReminderIfNeeded(settings: LuminoteSettings) {
        if (!isPersistentReminder(settings)) return
        if (!hasActiveNotifications()) return
        val paletteColors = when {
            settings.colorSource != HaloColorSource.GRADIENT -> activePaletteColors(settings)
            settings.gradientPalette == GradientPalette.NOTIFICATION_APPS -> activePaletteColors(settings, useAppColors = true)
            else -> HaloConfig.defaultGradientPalette()
        }
        startTransientEffect(
            settings = settings,
            paletteColors = paletteColors
        )
    }

    private fun hasActiveNotifications(): Boolean = synchronized(activeNotificationsLock) {
        activeNotificationPackages.isNotEmpty()
    }

    private fun isDuplicateNotification(
        sbn: StatusBarNotification
    ): Boolean {

        val key =
            sbn.key

        val now =
            SystemClock.elapsedRealtime()

        synchronized(
            dedupLock
        ) {

            cleanupRecentNotifications(now)

            val previous =
                recentNotifications[key]

            if (
                previous != null &&
                previous.postTime == sbn.postTime &&
                now - previous.timestamp <
                DEDUP_WINDOW_MS
            ) {

                return true
            }

            val entry = RecentNotification(
                key = key,
                timestamp = now,
                postTime = sbn.postTime
            )
            recentNotifications[key] = entry
            recentNotificationOrder.addLast(entry)

            return false
        }
    }

    private fun cleanupRecentNotifications(
        now: Long
    ) {

        val expiration =
            now -
                    RECENT_NOTIFICATION_RETENTION_MS

        while (recentNotificationOrder.isNotEmpty()) {
            val entry = recentNotificationOrder.peekFirst()
            if (entry.timestamp >= expiration) {
                return
            }

            recentNotificationOrder.removeFirst()
            if (recentNotifications[entry.key]?.timestamp == entry.timestamp) {
                recentNotifications.remove(entry.key)
            }
        }
    }

    /*
     * NotificationListenerService does not expose a reliable "heads-up is
     * currently visible" flag. Channel importance is the system signal used
     * to make a notification eligible for a heads-up card, while the sound
     * fields cover audible notifications at lower importance.
     */
    private fun shouldShowEffect(
        sbn: StatusBarNotification,
        rankingMap: NotificationListenerService.RankingMap
    ): Boolean {
        if (cachedSettings.get()?.includeSilentUpdates == true) {
            return true
        }

        val ranking = rankingByThread.get() ?: NotificationListenerService.Ranking().also {
            rankingByThread.set(it)
        }
        val hasRanking = rankingMap.getRanking(sbn.key, ranking)
        val isHeadsUpEligible =
            hasRanking &&
                    ranking.importance >= NotificationManager.IMPORTANCE_HIGH

        if (isHeadsUpEligible) {
            return true
        }

        val notification = sbn.notification
        val hasExplicitSound =
            notification.sound != null ||
                    (notification.defaults and Notification.DEFAULT_SOUND) != 0
        val hasChannelSound =
            hasRanking &&
                    ranking.channel?.sound != null
        val hasExplicitVibration =
            notification.vibrate != null ||
                    (notification.defaults and Notification.DEFAULT_VIBRATE) != 0
        val hasChannelVibration =
            hasRanking && ranking.channel?.shouldVibrate() == true

        return hasExplicitSound ||
                hasChannelSound ||
                hasExplicitVibration ||
                hasChannelVibration
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification
    ) {

        super.onNotificationRemoved(sbn)

        val noRelevantNotifications = synchronized(activeNotificationsLock) {
            activeNotificationPackages.remove(sbn.key)
            activePaletteNotificationPackages.remove(sbn.key)
            activeNotificationPackages.isEmpty()
        }
        val settings = cachedSettings.get()
        if (settings?.ambientEnabled == true || settings?.haloEnabled == false) return
        if (settings?.notificationPlayback == NotificationPlayback.KEEP_VISIBLE) {
            if (noRelevantNotifications) {
                HaloOverlayService.start(this, HaloOverlayService.createStopRepeatingIntent(this))
            } else {
                startPersistentReminderIfNeeded(settings)
            }
        }
    }

    private fun shouldHandleSource(
        sbn: StatusBarNotification,
        settings: LuminoteSettings
    ): Boolean = when (settings.notificationSource) {
        NotificationSource.ALL_APPS -> true
        NotificationSource.SELECTED_APPS -> sbn.packageName in settings.selectedApps
    }

    private fun shouldTrackPaletteNotification(
        sbn: StatusBarNotification,
        settings: LuminoteSettings
    ): Boolean =
        sbn.packageName != packageName &&
            shouldHandleSource(sbn, settings)

    override fun onDestroy() {

        synchronized(
            dedupLock
        ) {
            recentNotifications.clear()
            recentNotificationOrder.clear()
        }

        synchronized(activeNotificationsLock) {
            activeNotificationPackages.clear()
            activePaletteNotificationPackages.clear()
        }

        cachedSettings.set(null)
        settingsReady.cancel()

        serviceJob.cancel()

        Log.d(
            TAG,
            "Notification listener destroyed"
        )

        super.onDestroy()
    }

    companion object {

        private const val TAG =
            "LuminoteNotification"

        /*
         * Callbacks for the same notification
         * inside this window are treated as duplicates.
         */
        /* Only suppress the same framework callback, never a second message. */
        private const val DEDUP_WINDOW_MS =
            100L

        /*
         * A key cannot be a duplicate after the deduplication window ends.
         */
        private const val RECENT_NOTIFICATION_RETENTION_MS =
            DEDUP_WINDOW_MS

    }

    private data class RecentNotification(
        val key: String,
        val timestamp: Long,
        val postTime: Long
    )
}
