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
        mutableMapOf<String, Long>()

    private val recentNotificationOrder =
        ArrayDeque<RecentNotification>()

    private val dedupLock =
        Any()

    private val burstLock =
        Any()

    /* Keeps one entry per notification for the active app-color palette. */
    private val activeNotificationPackages = linkedMapOf<String, String>()
    private val activeNotificationsLock = Any()

    private var lastEffectRequestElapsedMs =
        0L

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
                    startService(HaloOverlayService.createAmbientIntent(this@LuminoteNotificationListener, settings))
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
                    startService(HaloOverlayService.createStopRepeatingIntent(this@LuminoteNotificationListener))
                }
                previousSettings = settings
                cachedSettings.set(settings)
                settingsReady.complete(settings)
                if (isPersistentReminder(settings)) {
                    startPersistentReminderIfNeeded(settings)
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

        if (cachedSettings.get()?.ambientEnabled == true) return

        if (!shouldShowEffect(sbn, rankingMap)) {
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
        if (settings.ambientEnabled) return

        if (settings.haloIntensity <= 0f) {
            return
        }

        val shouldHandle = shouldHandleSource(sbn, settings)

        if (!shouldHandle) {
            return
        }

        synchronized(activeNotificationsLock) {
            activeNotificationPackages[sbn.key] = sbn.packageName
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
        val isPersistentReminder = settings.notificationPlayback == NotificationPlayback.KEEP_VISIBLE
        val isGradient = settings.colorSource == HaloColorSource.GRADIENT
        val needsPaletteUpdate = isGradient || isPersistentReminder
        val palette = when {
            isGradient && settings.gradientPalette == GradientPalette.NOTIFICATION_APPS -> activePaletteColors(
                settings = settings,
                useAppColors = true
            )
            isGradient -> HaloConfig.defaultGradientPalette()
            isPersistentReminder -> activePaletteColors(settings)
            else -> null
        }
        if (isPersistentReminder || acquireEffectBurstSlot()) {
            startTransientEffect(
                settings = effectSettings,
                paletteColors = palette,
                restart = isPersistentReminder
            )
        } else if (needsPaletteUpdate) {
            /*
             * The burst already owns one animation, but a new app color must
             * still reach the gradient so simultaneous notifications share one
             * multi-color contour instead of becoming separate pulses.
             */
            startTransientEffect(
                settings = effectSettings,
                paletteColors = palette
            )
        }
    }

    private fun startTransientEffect(
        settings: LuminoteSettings,
        paletteColors: IntArray? = null,
        restart: Boolean = false
    ) {
        startService(
            HaloOverlayService.createIntent(
                context = this,
                settings = settings,
                paletteColors = paletteColors,
                restart = restart
            )
        )
    }

    private fun activePaletteColors(
        settings: LuminoteSettings,
        useAppColors: Boolean = settings.colorSource == HaloColorSource.APP_ICON
    ): IntArray = synchronized(activeNotificationsLock) {
        activeNotificationPackages.values
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
        synchronized(activeNotificationsLock) {
            activeNotificationPackages.clear()
            notifications.forEach { notification ->
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

    private fun isPersistentReminder(settings: LuminoteSettings): Boolean =
        settings.notificationPlayback == NotificationPlayback.KEEP_VISIBLE

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

            val previousTimestamp =
                recentNotifications[key]

            if (
                previousTimestamp != null &&
                now - previousTimestamp <
                DEDUP_WINDOW_MS
            ) {

                return true
            }

            recentNotifications[key] =
                now

            recentNotificationOrder.addLast(
                RecentNotification(
                    key = key,
                    timestamp = now
                )
            )

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
            if (recentNotifications[entry.key] == entry.timestamp) {
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

        return hasExplicitSound || hasChannelSound
    }

    /*
     * Different apps can post several notifications in the same UI frame.
     * One halo is enough for that burst, and avoiding redundant service
     * starts also avoids extra main-thread and overlay work.
     */
    private fun acquireEffectBurstSlot(): Boolean {
        val now = SystemClock.elapsedRealtime()

        synchronized(burstLock) {
            if (
                lastEffectRequestElapsedMs > 0L &&
                now - lastEffectRequestElapsedMs < EFFECT_BURST_WINDOW_MS
            ) {
                return false
            }

            lastEffectRequestElapsedMs = now
            return true
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification
    ) {

        super.onNotificationRemoved(sbn)

        val noRelevantNotifications = synchronized(activeNotificationsLock) {
            activeNotificationPackages.remove(sbn.key)
            activeNotificationPackages.isEmpty()
        }
        val settings = cachedSettings.get()
        if (settings?.ambientEnabled == true) return
        if (settings?.notificationPlayback == NotificationPlayback.KEEP_VISIBLE) {
            if (noRelevantNotifications) {
                startService(HaloOverlayService.createStopRepeatingIntent(this))
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

    override fun onDestroy() {

        synchronized(
            dedupLock
        ) {
            recentNotifications.clear()
            recentNotificationOrder.clear()
        }

        synchronized(burstLock) {
            lastEffectRequestElapsedMs = 0L
        }

        synchronized(activeNotificationsLock) { activeNotificationPackages.clear() }

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
        private const val DEDUP_WINDOW_MS =
            750L

        /*
         * A key cannot be a duplicate after the deduplication window ends.
         */
        private const val RECENT_NOTIFICATION_RETENTION_MS =
            DEDUP_WINDOW_MS

        /* Different notifications within this window share one effect. */
        private const val EFFECT_BURST_WINDOW_MS =
            400L

    }

    private data class RecentNotification(
        val key: String,
        val timestamp: Long
    )
}
