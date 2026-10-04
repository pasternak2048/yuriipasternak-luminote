package com.yp.luminote.app.effects

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import com.yp.luminote.app.R
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.data.settings.HaloColorSource
import com.yp.luminote.app.data.settings.HaloFrame
import com.yp.luminote.app.data.settings.HaloMotion
import com.yp.luminote.app.data.settings.LuminoteSettings
import com.yp.luminote.app.notification.HaloReminderRuntime
import kotlin.math.max

/** Owns only the overlay window, incoming configuration and service lifetime. */
class HaloOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager

    private val handler =
        Handler(Looper.getMainLooper())

    private var overlayView:
            HaloView? = null

    private var overlayLayoutParams:
            WindowManager.LayoutParams? = null

    private var previewMode =
        false

    private var calibrationToken: String? = null

    private var calibrationGeneration: Long? = null

    private var pendingCalibrationIntent: Intent? = null

    private var lastCalibrationIntent: Intent? = null

    private fun ownsStaticCalibration(token: String?, generation: Long?): Boolean =
        token != null && generation != null &&
            token == calibrationToken && generation == calibrationGeneration

    private fun hasCurrentStaticCalibration(): Boolean =
        calibrationToken != null && calibrationGeneration != null &&
            CalibrationPreviewSession.isCurrent(calibrationToken, calibrationGeneration!!)

    private fun clearPendingCalibrationIfOwned(token: String?, generation: Long?) {
        pendingCalibrationIntent?.takeIf {
            it.getStringExtra(EXTRA_CALIBRATION_TOKEN) == token &&
                it.getLongExtra(EXTRA_CALIBRATION_GENERATION, NO_CALIBRATION_GENERATION) == generation
        }?.let { pendingCalibrationIntent = null }
    }

    private var activeConfig:
            HaloConfig? = null

    private var foregroundStarted =
        false

    private val removeOverlayTask =
        Runnable {
            removeOverlay()
            stopSelf()
        }

    private var queuedCompletionWatchdog:
            Runnable? = null

    private var queuedWatchdogRequest:
            HaloEffectRequest? = null

    /* A request object may be requeued after renderer recreation; each run is a new lease. */
    private var queuedDeliveryToken =
        0L

    private val queuedCompletionLease =
        QueuedCompletionLease()

    private var activeCompletionLease:
            Long? = null

    /* A reminder keeps one coordinator lease while its per-app impulses advance. */
    private var activeReminderDelivery: HaloEffectDelivery? = null
    private var activeReminderColorIndex = 0

    private val displayListener =
        object : DisplayManager.DisplayListener {

            override fun onDisplayAdded(
                displayId: Int
            ) = Unit

            override fun onDisplayRemoved(
                displayId: Int
            ) = Unit

            override fun onDisplayChanged(
                displayId: Int
            ) {
                if (
                    displayId ==
                    Display.DEFAULT_DISPLAY
                ) {
                    updateOverlayBounds()
                }
            }
        }

    override fun onCreate() {
        super.onCreate()

        displayManager =
            getSystemService(
                Context.DISPLAY_SERVICE
            ) as DisplayManager

        displayManager.registerDisplayListener(
            displayListener,
            handler
        )

        startAsForeground()

        Log.d(
            TAG,
            "HaloOverlayService created"
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (!foregroundStarted) {
            if (isReminderIntent(intent)) {
                HaloReminderRuntime.onReminderPlaybackCancelled(
                    recoverWithFreshInterval = true
                )
            }
            stopSelf(startId)

            return START_NOT_STICKY
        }

        if (
            intent?.getBooleanExtra(
                EXTRA_STOP_APPLICATION_AMBIENT,
                false
            ) == true
        ) {
            pendingApplicationAmbientIntent =
                null

            removeOverlay()
            stopSelf()

            return START_NOT_STICKY
        }

        val packageName =
            intent?.getStringExtra(
                EXTRA_PACKAGE_NAME
            )

        val notificationKey =
            intent?.getStringExtra(
                EXTRA_NOTIFICATION_KEY
            )

        val isNotificationEffect =
            packageName != null &&
                    notificationKey != null

        if (intent?.getBooleanExtra(EXTRA_STOP_ALL, false) == true) {
            cancelQueuedEffectsForGlobalStop()
            HaloReminderRuntime.onReminderPlaybackCancelled(
                recoverWithFreshInterval = false
            )
            HaloAccessibilityService.dispatch(intent)
            removeOverlay(immediately = true)
            stopSelf()

            return START_NOT_STICKY
        }

        if (
            !isNotificationEffect &&
            !CalibrationPreviewCommandPolicy.bypassAccessibilityRoute(
                intent?.getStringExtra(EXTRA_CALIBRATION_TOKEN),
                intent?.getBooleanExtra(EXTRA_STOP_CALIBRATION, false) == true,
                intent?.getBooleanExtra(EXTRA_PAUSE_CALIBRATION, false) == true
            ) &&
            HaloAccessibilityService.dispatch(intent)
        ) {
            removeOverlay()
            stopSelf()

            return START_NOT_STICKY
        }

        if (
            intent?.getBooleanExtra(
                EXTRA_STOP_AMBIENT,
                false
            ) == true
        ) {
            if (hasCurrentStaticCalibration()) return START_NOT_STICKY
            if (activeConfig?.renderMode == HaloRenderMode.AMBIENT || overlayView == null) {
                removeOverlay()
                stopSelf()
            }

            return START_NOT_STICKY
        }

        if (
            intent?.getBooleanExtra(
                EXTRA_STOP_PREVIEW,
                false
            ) == true
        ) {
            if (CalibrationPreviewCommandPolicy.ignoreTokenlessPreviewStop(calibrationToken)) return START_NOT_STICKY
            if (
                previewMode ||
                overlayView == null
            ) {
                removeOverlay()
                stopSelf()
            }

            return START_NOT_STICKY
        }

        val commandCalibrationToken = intent?.getStringExtra(EXTRA_CALIBRATION_TOKEN)
        val commandCalibrationGeneration = intent?.getLongExtra(EXTRA_CALIBRATION_GENERATION, NO_CALIBRATION_GENERATION)
        if (intent?.getBooleanExtra(EXTRA_PAUSE_CALIBRATION, false) == true) {
            if (commandCalibrationGeneration == null ||
                !CalibrationPreviewSession.isCurrent(commandCalibrationToken, commandCalibrationGeneration)) {
                return START_NOT_STICKY
            }
            clearPendingCalibrationIfOwned(commandCalibrationToken, commandCalibrationGeneration)
            if (ownsStaticCalibration(commandCalibrationToken, commandCalibrationGeneration)) {
                calibrationToken = null
                calibrationGeneration = null
                lastCalibrationIntent = null
                removeOverlay()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (intent?.getBooleanExtra(EXTRA_STOP_CALIBRATION, false) == true) {
            if (commandCalibrationGeneration != null &&
                CalibrationPreviewSession.stop(commandCalibrationToken, commandCalibrationGeneration)) {
                clearPendingCalibrationIfOwned(commandCalibrationToken, commandCalibrationGeneration)
                if (ownsStaticCalibration(commandCalibrationToken, commandCalibrationGeneration)) {
                    calibrationToken = null
                    calibrationGeneration = null
                    lastCalibrationIntent = null
                    removeOverlay()
                    stopSelf()
                }
            }

            return START_NOT_STICKY
        }
        if (commandCalibrationToken != null &&
            (commandCalibrationGeneration == null ||
                !CalibrationPreviewSession.isCurrent(commandCalibrationToken, commandCalibrationGeneration))) return START_NOT_STICKY
        if (commandCalibrationToken != null && effectCoordinator.isBusy()) {
            pendingCalibrationIntent = intent
            return START_NOT_STICKY
        }
        if (commandCalibrationToken != null) {
            calibrationToken = commandCalibrationToken
            calibrationGeneration = commandCalibrationGeneration
            lastCalibrationIntent = intent
        }
        // Settings/ambient emissions are tokenless and must never replace a calibration owner.
        if (commandCalibrationToken == null && hasCurrentStaticCalibration() && intent?.let(::isPersistentAmbientIntent) == true) {
            return START_NOT_STICKY
        }

        val config =
            readConfig(intent)

        val paletteColors =
            intent?.getIntArrayExtra(
                EXTRA_PALETTE_COLORS
            )

        val reminderColors = intent?.getIntArrayExtra(EXTRA_REMINDER_COLORS) ?: intArrayOf()

        if (
            packageName != null &&
            notificationKey != null
        ) {
            if (hasCurrentStaticCalibration()) {
                if (isReminderIntent(intent)) {
                    HaloReminderRuntime.onReminderPlaybackCancelled(
                        recoverWithFreshInterval = true
                    )
                }
                Log.d(
                    TAG,
                    "Notification ignored by renderer while display calibration is active"
                )

                return START_NOT_STICKY
            }

            val request =
                HaloEffectRequest(
                    packageName = packageName,
                    notificationKey = notificationKey,
                    config = config,
                    paletteColors = paletteColors,
                    reminderColors = reminderColors
                )

            Log.d(
                TAG,
                "Notification effect request: " +
                        "package=${request.packageName}, " +
                        "key=${request.notificationKey}, " +
                        "enqueuedAt=${request.enqueuedAt}"
            )

            /*
             * Register this concrete service instance as the current renderer.
             *
             * HaloEffectCoordinator is process-scoped, while this Service
             * instance can be destroyed and recreated. Explicit ownership
             * prevents an old Service from detaching a newer renderer.
             */
            effectCoordinator.attachRenderer(
                owner = this,
                onRequestStarted = ::startQueuedEffect
            )

            effectCoordinator.enqueue(
                request
            )

            return START_NOT_STICKY
        }

        showOverlay(
            config = config,
            restart =
                intent?.getBooleanExtra(
                    EXTRA_RESTART,
                    false
                ) == true,
            preview =
                intent?.getBooleanExtra(
                    EXTRA_PREVIEW,
                    false
                ) == true,
            paletteColors = paletteColors
        )

        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(
        newConfig: Configuration
    ) {
        super.onConfigurationChanged(
            newConfig
        )

        updateOverlayBounds()
    }

    private fun readConfig(
        intent: Intent?
    ): HaloConfig {
        val defaults =
            HaloConfig()

        return HaloConfigCommandDecoder.decode(
            command =
                RawHaloConfigCommand(
                    color =
                        intent?.getIntExtra(
                            EXTRA_COLOR,
                            defaults.color
                        ),
                    intervalSeconds =
                        intent?.getFloatExtra(
                            EXTRA_INTERVAL,
                            defaults.intervalSeconds
                        ),
                    intensity =
                        intent?.getFloatExtra(
                            EXTRA_INTENSITY,
                            defaults.intensity
                        ),
                    thickness =
                        intent?.getFloatExtra(
                            EXTRA_THICKNESS,
                            defaults.thickness
                        ),
                    frameName =
                        intent?.getStringExtra(
                            EXTRA_FRAME
                        ),
                    motionName =
                        intent?.getStringExtra(
                            EXTRA_MOTION
                        ),
                    effectSpeed =
                        intent?.getFloatExtra(
                            EXTRA_EFFECT_SPEED,
                            defaults.effectSpeed
                        ),
                    gradientFlowSpeed =
                        intent?.getFloatExtra(
                            EXTRA_GRADIENT_FLOW_SPEED,
                            defaults.gradientFlowSpeed
                        ),
                    colorModeName =
                        intent?.getStringExtra(
                            EXTRA_COLOR_MODE
                        ),
                    legacyNotificationPlaybackName =
                        intent?.getStringExtra(
                            EXTRA_NOTIFICATION_PLAYBACK
                        ),
                    edgeCalibrationDp = intent?.getFloatExtra(EXTRA_EDGE_CALIBRATION_DP, defaults.edgeCalibrationDp),
                    cornerCalibrationDp = intent?.getFloatExtra(EXTRA_CORNER_CALIBRATION_DP, defaults.cornerCalibrationDp),
                    cornerShape = intent?.getFloatExtra(EXTRA_CORNER_SHAPE, defaults.cornerShape)
                ),
            defaults = defaults
        ).copy(
            renderMode = when {
                intent?.getBooleanExtra(EXTRA_AMBIENT, false) == true -> HaloRenderMode.AMBIENT
                intent?.getBooleanExtra(EXTRA_LIGHT_IMPULSE, false) == true -> HaloRenderMode.LIGHT_IMPULSE
                else -> HaloRenderMode.NORMAL
            }
        )
    }

    private fun showOverlay(
        config: HaloConfig,
        restart: Boolean,
        preview: Boolean,
        paletteColors: IntArray?,
        scheduleRemoval: Boolean = true,
        onFiniteAnimationCompleted: (() -> Unit)? = null,
        onFiniteAnimationStarted: (() -> Unit)? = null
    ) {
        val completeWithoutAnimation = {
            if (
                onFiniteAnimationCompleted !=
                null
            ) {
                onFiniteAnimationCompleted()
            } else {
                stopSelf()
            }
        }

        overlayView?.let { view ->
            if (hasCurrentStaticCalibration()) {
                activeConfig = configurePalette(config, paletteColors)
                view.update(activeConfig!!)
                view.showStaticFrame()
                return
            }
            /*
             * A running overlay represents the whole notification burst.
             * Restarting its animator for every new notification makes the
             * transition jumpy and keeps the overlay alive unnecessarily.
             */
            if (restart) {
                previewMode =
                    preview

                val resolvedConfig =
                    configurePalette(
                        config,
                        paletteColors
                    )

                activeConfig =
                    resolvedConfig

                view.update(
                    resolvedConfig
                )

                view.setOnFiniteAnimationCompletedListener(
                    onFiniteAnimationCompleted
                )

                view.setOnFiniteAnimationStartedListener(
                    onFiniteAnimationStarted
                )

                startAnimation(
                    view,
                    resolvedConfig
                )

                if (resolvedConfig.renderMode != HaloRenderMode.AMBIENT && scheduleRemoval) {
                    scheduleRemoval(
                        resolvedConfig
                    )
                }
            } else if (
                paletteColors != null &&
                activeConfig != null
            ) {
                if (activeConfig?.renderMode == HaloRenderMode.AMBIENT) {
                    updatePersistentConfig(
                        view,
                        config,
                        paletteColors
                    )
                } else if (
                    activeConfig?.colorMode ==
                    HaloColorMode.GRADIENT
                ) {
                    updatePalette(
                        paletteColors
                    )
                }
            }

            return
        }

        if (config.intensity <= 0f) {
            completeWithoutAnimation()

            return
        }

        if (
            !Settings.canDrawOverlays(
                this
            )
        ) {
            Log.e(
                TAG,
                "Halo requested without overlay permission"
            )

            completeWithoutAnimation()

            return
        }

        val display =
            displayManager.getDisplay(
                Display.DEFAULT_DISPLAY
            ) ?: run {
                completeWithoutAnimation()

                return
            }

        val windowContext =
            createDisplayContext(
                display
            ).createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                null
            )

        windowManager =
            windowContext.getSystemService(
                WindowManager::class.java
            )

        val metrics =
            OverlayDisplayMetrics.realMetrics(
                display
            )

        val resolvedConfig =
            configurePalette(
                config,
                paletteColors
            )

        val view =
            HaloView(
                windowContext,
                resolvedConfig
            )

        view.setOnFiniteAnimationCompletedListener(
            onFiniteAnimationCompleted
        )

        view.setOnFiniteAnimationStartedListener(
            onFiniteAnimationStarted
        )

        val params =
            WindowManager.LayoutParams(
                metrics.widthPixels,
                metrics.heightPixels,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity =
                    Gravity.TOP or
                            Gravity.START

                x = 0
                y = 0

                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams
                        .LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

                setFitInsetsTypes(0)
                setFitInsetsSides(0)
                setFitInsetsIgnoringVisibility(true)
            }

        overlayView =
            view

        overlayLayoutParams =
            params

        previewMode =
            preview

        activeConfig =
            resolvedConfig

        try {
            windowManager.addView(
                view,
                params
            )

            if (hasCurrentStaticCalibration()) {
                view.showStaticFrame()
            } else {
                startAnimation(view, resolvedConfig)
            }

            if (!hasCurrentStaticCalibration() && resolvedConfig.renderMode != HaloRenderMode.AMBIENT && scheduleRemoval) {
                scheduleRemoval(
                    resolvedConfig
                )
            }
        } catch (
            exception: Exception
        ) {
            Log.w(
                TAG,
                "Unable to attach halo overlay",
                exception
            )

            view.setOnFiniteAnimationCompletedListener(
                null
            )

            removeOverlay()
            completeWithoutAnimation()
        }
    }

    private fun configurePalette(
        config: HaloConfig,
        requestedColors: IntArray?
    ): HaloConfig {
        val palette =
            requestedColors
                ?.distinct()
                ?.takeIf {
                    it.isNotEmpty()
                }
                ?.toIntArray()
                ?: if (
                    config.colorMode ==
                    HaloColorMode.GRADIENT
                ) {
                    HaloConfig.defaultGradientPalette()
                } else {
                    intArrayOf(
                        config.color
                    )
                }

        return config.copy(
            color = palette.first(),
            palette = palette
        )
    }

    private fun updatePalette(
        requestedColors: IntArray
    ) {
        val colors =
            requestedColors
                .distinct()
                .takeIf {
                    it.isNotEmpty()
                }
                ?.toIntArray()
                ?: return

        activeConfig =
            activeConfig?.copy(
                palette = colors
            )

        overlayView?.let { view ->
            activeConfig?.let(
                view::update
            )
        }
    }

    private fun updatePersistentConfig(
        view: HaloView,
        config: HaloConfig,
        requestedColors: IntArray
    ) {
        val previous =
            activeConfig
                ?: return

        val wasAmbient =
            shouldRunAmbientLoop(
                previous
            )

        val colors =
            requestedColors
                .distinct()
                .takeIf {
                    it.isNotEmpty()
                }
                ?.toIntArray()
                ?: intArrayOf(
                    config.color
                )

        val currentColor =
            previous.color.takeIf { color ->
                color in colors
            } ?: colors.first()

        activeConfig =
            config.copy(
                color = currentColor,
                palette = colors
            )

        val updated =
            activeConfig
                ?: return

        val isAmbient =
            shouldRunAmbientLoop(
                updated
            )

        view.update(
            updated
        )

        /*
         * Apply changes that affect a finite animation
         * as one fresh cycle.
         */
        val needsAnimationRestart =
            previous.durationSeconds !=
                    updated.durationSeconds ||
                    previous.effectSpeed !=
                    updated.effectSpeed ||
                    previous.intervalSeconds !=
                    updated.intervalSeconds ||
                    previous.motion !=
                    updated.motion ||
                    previous.colorMode != updated.colorMode ||
                    previous.renderMode != updated.renderMode

        if (
            wasAmbient != isAmbient ||
            (
                    !isAmbient &&
                            needsAnimationRestart
                    )
        ) {
            startAnimation(
                view,
                updated
            )
        }
    }

    private fun startAnimation(
        view: HaloView,
        config: HaloConfig
    ) {
        if (
            shouldRunAmbientLoop(
                config
            )
        ) {
            view.startAmbientEffect(
                effectSpeed = config.effectSpeed,
                motion = config.motion
            )

            return
        }

        view.repeatAnimation(
            duration =
                config.durationSeconds,
            interval =
                config.intervalSeconds,
            count = 1,
            motion =
                config.motion
        )
    }

    private fun shouldRunAmbientLoop(
        config: HaloConfig
    ): Boolean =
        config.renderMode == HaloRenderMode.AMBIENT

    private fun updateOverlayBounds() {
        val view =
            overlayView
                ?: return

        val params =
            overlayLayoutParams
                ?: return

        val display =
            displayManager.getDisplay(
                Display.DEFAULT_DISPLAY
            ) ?: return

        val metrics =
            OverlayDisplayMetrics.realMetrics(
                display
            )

        params.width =
            metrics.widthPixels

        params.height =
            metrics.heightPixels

        params.x = 0
        params.y = 0

        try {
            windowManager.updateViewLayout(
                view,
                params
            )
        } catch (
            exception: Exception
        ) {
            Log.w(
                TAG,
                "Unable to update halo overlay bounds",
                exception
            )
        }
    }

    private fun scheduleRemoval(
        config: HaloConfig
    ) {
        handler.removeCallbacks(
            removeOverlayTask
        )

        val durationMs =
            max(
                HaloAnimation.MIN_DURATION_MS,
                (
                        config.durationSeconds *
                                1000f
                        ).toLong()
            )

        val intervalMs =
            (
                    config.intervalSeconds *
                            1000f
                    ).toLong()
                .coerceAtLeast(0L)

        val totalDurationMs = durationMs

        handler.postDelayed(
            removeOverlayTask,
            totalDurationMs +
                    OVERLAY_REMOVAL_GRACE_MS
        )
    }

    private fun removeOverlay(
        immediately: Boolean = false
    ) {
        overlayView?.let { view ->
            try {
                if (
                    view.isAttachedToWindow
                ) {
                    if (immediately) {
                        windowManager.removeViewImmediate(
                            view
                        )
                    } else {
                        windowManager.removeView(
                            view
                        )
                    }
                }
            } catch (
                exception: Exception
            ) {
                Log.w(
                    TAG,
                    "Unable to remove halo overlay",
                    exception
                )
            }
        }

        overlayView =
            null

        overlayLayoutParams =
            null

        previewMode =
            false

        activeConfig =
            null
    }

    override fun onDestroy() {
        activeCompletionLease?.let(queuedCompletionLease::invalidate)

        activeCompletionLease =
            null

        cancelQueuedCompletionWatchdog()

        /*
         * Detach only this concrete Service instance.
         *
         * If Android has already created a replacement service and that
         * instance attached itself as renderer, the coordinator ignores this
         * stale detach instead of disconnecting the newer renderer.
         */
        effectCoordinator.detachRenderer(
            this
        )

        displayManager.unregisterDisplayListener(
            displayListener
        )

        handler.removeCallbacks(
            removeOverlayTask
        )

        overlayView?.cancelAnimation()

        removeOverlay()

        if (foregroundStarted) {
            stopForeground(
                STOP_FOREGROUND_REMOVE
            )
        }

        Log.d(
            TAG,
            "HaloOverlayService destroyed"
        )

        super.onDestroy()
    }

    private fun startAsForeground() {
        val notificationManager =
            getSystemService(
                NotificationManager::class.java
            )

        notificationManager.createNotificationChannel(
            NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                getString(
                    R.string.halo_foreground_channel_name
                ),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )

        val notification =
            Notification.Builder(
                this,
                FOREGROUND_CHANNEL_ID
            )
                .setSmallIcon(
                    R.drawable.ic_halo_notification
                )
                .setContentTitle(
                    getString(
                        R.string.halo_foreground_notification_title
                    )
                )
                .setCategory(
                    Notification.CATEGORY_SERVICE
                )
                .setOngoing(true)
                .setShowWhen(false)
                .build()

        try {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )

            foregroundStarted =
                true
        } catch (
            exception: Exception
        ) {
            Log.e(
                TAG,
                "Unable to start halo foreground service",
                exception
            )

            stopSelf()
        }
    }

    private fun startQueuedEffect(
        delivery: HaloEffectDelivery
    ) {
        val cycleRequest = delivery.request
        if (cycleRequest.reminderColors.isNotEmpty() && activeReminderDelivery !== delivery) {
            activeReminderDelivery = delivery
            activeReminderColorIndex = 0
        }
        val request = if (cycleRequest.reminderColors.isNotEmpty()) {
            cycleRequest.copy(
                config = cycleRequest.config.copy(
                    color = cycleRequest.reminderColors[activeReminderColorIndex]
                )
            )
        } else cycleRequest
        Log.d(
            TAG,
            "Starting queued effect: " +
                    "package=${request.packageName}, " +
                    "key=${request.notificationKey}"
        )

        if (
            HaloAccessibilityService.dispatchQueuedEffect(
                request = request,
                onFiniteAnimationCompleted = {
                    onAccessibilityQueuedEffectCompleted(delivery, request)
                }
            )
        ) {
            Log.d(
                TAG,
                "Queued effect rendered by accessibility overlay"
            )

            return
        }

        Log.d(
            TAG,
            "Queued effect rendered by application overlay"
        )

        cancelQueuedCompletionWatchdog()

        queuedWatchdogRequest =
            request

        val deliveryToken =
            ++queuedDeliveryToken

        val completionLease =
            queuedCompletionLease.begin()

        activeCompletionLease =
            completionLease

        showOverlay(
            config = request.config,
            restart = true,
            preview = false,
            paletteColors =
                request.paletteColors,
            scheduleRemoval = false,
            onFiniteAnimationCompleted = {
                onQueuedEffectCompleted(
                    request,
                    deliveryToken,
                    delivery,
                    completionLease
                )
            },
            onFiniteAnimationStarted = {
                queuedCompletionLease.onStarted(
                    completionLease
                )

                scheduleQueuedCompletionWatchdog(
                    request,
                    deliveryToken,
                    delivery,
                    completionLease
                )
            }
        )
    }

    private fun onAccessibilityQueuedEffectCompleted(
        delivery: HaloEffectDelivery,
        request: HaloEffectRequest
    ) {
        if (request.reminderColors.isNotEmpty() && activeReminderColorIndex < request.reminderColors.lastIndex) {
            activeReminderColorIndex++
            startQueuedEffect(delivery)
            return
        }
        if (activeReminderDelivery === delivery) {
            activeReminderDelivery = null
            activeReminderColorIndex = 0
        }
        effectCoordinator.onRequestCompleted(delivery)
        HaloReminderRuntime.onPlaybackCompleted(
            reminder = request.config.renderMode == HaloRenderMode.LIGHT_IMPULSE,
            coordinatorDrained = !effectCoordinator.isBusy()
        )
    }

    private fun onQueuedEffectCompleted(
        request: HaloEffectRequest,
        deliveryToken: Long,
        delivery: HaloEffectDelivery,
        completionLease: Long,
        fromWatchdog: Boolean = false
    ) {
        if (
            queuedWatchdogRequest != null &&
            (
                    queuedWatchdogRequest !== request ||
                            deliveryToken != queuedDeliveryToken
                    )
        ) {
            Log.d(
                TAG,
                "Ignoring stale queued completion: " +
                        "package=${request.packageName}, " +
                        "key=${request.notificationKey}"
            )

            return
        }

        if (
            !(if (fromWatchdog) {
                queuedCompletionLease.completeFromWatchdog(completionLease)
            } else {
                queuedCompletionLease.completeNaturally(completionLease)
            })
        ) {
            return
        }

        Log.d(
            TAG,
            "Queued effect completed: " +
                    "package=${request.packageName}, " +
                    "key=${request.notificationKey}"
        )

        /* Invalidate callbacks before cancelling/removing the renderer. */
        queuedDeliveryToken++

        activeCompletionLease =
            null
        cancelQueuedCompletionWatchdog()

        handler.removeCallbacks(
            removeOverlayTask
        )

        overlayView?.let { view ->
            view.setOnFiniteAnimationCompletedListener(null)
            view.cancelAnimation()
        }

        removeOverlay(
            immediately = true
        )

        if (request.reminderColors.isNotEmpty() &&
            activeReminderColorIndex < request.reminderColors.lastIndex) {
            activeReminderColorIndex++
            startQueuedEffect(delivery)
            return
        }

        if (activeReminderDelivery === delivery) {
            activeReminderDelivery = null
            activeReminderColorIndex = 0
        }

        effectCoordinator.onRequestCompleted(delivery)

        HaloReminderRuntime.onPlaybackCompleted(
            reminder = request.config.renderMode == HaloRenderMode.LIGHT_IMPULSE,
            coordinatorDrained = !effectCoordinator.isBusy()
        )

        handler.post {
            pendingCalibrationIntent?.takeIf { !effectCoordinator.isBusy() }?.let { pending ->
                pendingCalibrationIntent = null
                onStartCommand(pending, 0, 0)
            }
        }

        if (
            overlayView == null
        ) {
            stopSelf()
        }
    }

    /** Global Off invalidates the current lease before removing either renderer route. */
    private fun cancelQueuedEffectsForGlobalStop() {
        activeCompletionLease?.let(queuedCompletionLease::invalidate)
        activeCompletionLease = null
        queuedDeliveryToken++
        cancelQueuedCompletionWatchdog()
        activeReminderDelivery = null
        activeReminderColorIndex = 0
        overlayView?.let { view ->
            view.setOnFiniteAnimationCompletedListener(null)
            view.setOnFiniteAnimationStartedListener(null)
            view.cancelAnimation()
        }
        effectCoordinator.clear()
    }

    private fun scheduleQueuedCompletionWatchdog(
        request: HaloEffectRequest,
        deliveryToken: Long,
        delivery: HaloEffectDelivery,
        completionLease: Long
    ) {
        val config =
            request.config

        val cycleDurationMs =
            max(
                HaloAnimation.MIN_DURATION_MS,
                (
                        config.durationSeconds *
                                1000f
                        ).toLong()
            )

        val intervalMs =
            (
                    config.intervalSeconds *
                            1000f
                    ).toLong()
                .coerceAtLeast(0L)

        val expectedDurationMs = cycleDurationMs

        val watchdogDelayMs =
            expectedDurationMs +
                    QUEUED_WATCHDOG_GRACE_MS

        lateinit var watchdog:
                Runnable

        watchdog =
            Runnable {
                if (
                    queuedCompletionWatchdog !== watchdog ||
                    queuedWatchdogRequest !== request ||
                            deliveryToken != queuedDeliveryToken
                ) {
                    return@Runnable
                }

                onQueuedEffectCompleted(
                    request,
                    deliveryToken,
                    delivery,
                    completionLease,
                    fromWatchdog = true
                )
            }

        queuedCompletionWatchdog =
            watchdog

        handler.postDelayed(
            watchdog,
            watchdogDelayMs
        )
    }

    private fun cancelQueuedCompletionWatchdog() {
        queuedCompletionWatchdog
            ?.let(
                handler::removeCallbacks
            )

        queuedCompletionWatchdog =
            null

        queuedWatchdogRequest =
            null
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? =
        null

    companion object {

        const val EXTRA_COLOR =
            "extra_halo_color"

        const val EXTRA_INTERVAL =
            "extra_halo_interval"


        const val EXTRA_PALETTE_COLORS =
            "extra_halo_palette_colors"

        const val EXTRA_REMINDER_COLORS = "extra_reminder_colors"

        const val EXTRA_LIGHT_IMPULSE = "extra_light_impulse"
        const val EXTRA_AMBIENT = "extra_halo_ambient"

        const val EXTRA_RESTART =
            "extra_restart_halo"

        const val EXTRA_PREVIEW =
            "extra_preview_halo"

        const val EXTRA_STOP_PREVIEW =
            "extra_stop_preview_halo"

        const val EXTRA_STOP_ALL = "extra_stop_all_halo"

        const val EXTRA_STOP_AMBIENT =
            "extra_stop_ambient_halo"

        private const val EXTRA_STOP_APPLICATION_AMBIENT =
            "extra_stop_application_ambient_halo"

        const val EXTRA_INTENSITY =
            "extra_halo_intensity"

        const val EXTRA_THICKNESS =
            "extra_halo_thickness"

        const val EXTRA_FRAME =
            "extra_halo_frame"

        const val EXTRA_MOTION =
            "extra_halo_motion"

        const val EXTRA_EFFECT_SPEED =
            "extra_halo_effect_speed"

        const val EXTRA_GRADIENT_FLOW_SPEED =
            "extra_gradient_flow_speed"

        const val EXTRA_COLOR_MODE =
            "extra_halo_color_mode"

        const val EXTRA_NOTIFICATION_PLAYBACK =
            "extra_notification_playback"

        const val EXTRA_EDGE_CALIBRATION_DP = "extra_display_edge_calibration_dp"
        const val EXTRA_CORNER_CALIBRATION_DP = "extra_display_corner_calibration_dp"
        const val EXTRA_CORNER_SHAPE = "extra_display_corner_shape"
        const val EXTRA_CALIBRATION_TOKEN = "extra_calibration_token"
        const val EXTRA_CALIBRATION_GENERATION = "extra_calibration_generation"
        const val EXTRA_PAUSE_CALIBRATION = "extra_pause_calibration"
        const val EXTRA_STOP_CALIBRATION = "extra_stop_calibration"
        private const val NO_CALIBRATION_GENERATION = -1L

        const val EXTRA_PACKAGE_NAME =
            "extra_notification_package_name"

        const val EXTRA_NOTIFICATION_KEY =
            "extra_notification_key"

        private const val TAG =
            "HaloOverlay"

        /* Recovery-only margin for delayed attachment or VSYNC delivery. */
        private const val QUEUED_WATCHDOG_GRACE_MS =
            2_000L

        private const val FOREGROUND_CHANNEL_ID =
            "halo_overlay"

        private const val FOREGROUND_NOTIFICATION_ID =
            1001

        private const val OVERLAY_REMOVAL_GRACE_MS =
            50L

        @Volatile
        private var pendingApplicationAmbientIntent:
                Intent? = null

        private val effectCoordinator =
            HaloEffectCoordinator()

        internal fun isTransientPlaybackBusy(): Boolean = effectCoordinator.isBusy()

        /** Used by the accessibility route when it receives Global Off directly. */
        internal fun cancelTransientPlaybackForGlobalStop() {
            effectCoordinator.clear()
        }

        internal fun isReminderIntent(intent: Intent?): Boolean =
            isReminderIntent(
                lightImpulse = intent?.getBooleanExtra(EXTRA_LIGHT_IMPULSE, false) == true,
                reminderColors = intent?.getIntArrayExtra(EXTRA_REMINDER_COLORS)
            )

        internal fun isReminderIntent(
            lightImpulse: Boolean,
            reminderColors: IntArray?
        ): Boolean = lightImpulse && reminderColors?.isNotEmpty() == true

        fun createIntent(
            context: Context,
            settings: LuminoteSettings,
            paletteColors: IntArray? = null,
            restart: Boolean = false,
            packageName: String? = null,
            notificationKey: String? = null
        ): Intent =
            Intent(
                context,
                HaloOverlayService::class.java
            ).apply {
                putExtra(
                    EXTRA_COLOR,
                    settings.haloColor
                )

                putExtra(
                    EXTRA_INTERVAL,
                    settings.haloInterval
                )

                putExtra(
                    EXTRA_INTENSITY,
                    settings.haloIntensity
                )

                putExtra(
                    EXTRA_THICKNESS,
                    settings.haloThickness
                )

                putExtra(EXTRA_EDGE_CALIBRATION_DP, settings.displayEdgeCalibrationDp)
                putExtra(EXTRA_CORNER_CALIBRATION_DP, settings.displayCornerCalibrationDp)
                putExtra(EXTRA_CORNER_SHAPE, settings.displayCornerShape)

                putExtra(
                    EXTRA_FRAME,
                    settings.haloFrame.name
                )

                putExtra(
                    EXTRA_MOTION,
                    settings.haloMotion.name
                )

                putExtra(
                    EXTRA_EFFECT_SPEED,
                    settings.haloEffectSpeed
                )

                putExtra(
                    EXTRA_GRADIENT_FLOW_SPEED,
                    settings.gradientFlowSpeed
                )

                putExtra(
                    EXTRA_COLOR_MODE,
                    if (
                        settings.colorSource ==
                        HaloColorSource.GRADIENT
                    ) {
                        HaloColorMode.GRADIENT.name
                    } else {
                        HaloColorMode.SOLID.name
                    }
                )

                paletteColors?.let {
                    putExtra(
                        EXTRA_PALETTE_COLORS,
                        it
                    )
                }

                packageName?.let {
                    putExtra(
                        EXTRA_PACKAGE_NAME,
                        it
                    )
                }

                notificationKey?.let {
                    putExtra(
                        EXTRA_NOTIFICATION_KEY,
                        it
                    )
                }

                if (restart) {
                    putExtra(
                        EXTRA_RESTART,
                        true
                    )
                }
            }

        fun createReminderIntent(context: Context, settings: LuminoteSettings, packageName: String, colors: IntArray): Intent =
            createIntent(
                context = context,
                settings = settings.copy(haloColor = colors.first()),
                packageName = packageName,
                notificationKey = "reminder-${android.os.SystemClock.elapsedRealtime()}"
            ).apply {
                putExtra(EXTRA_LIGHT_IMPULSE, true)
                putExtra(EXTRA_REMINDER_COLORS, colors)
            }

        /** Starts the overlay from a notification callback while the app is backgrounded. */
        fun start(
            context: Context,
            intent: Intent
        ): Boolean {
            /*
             * Accessibility overlays are the lock-screen/AoD renderer. Route
             * there before attempting an FGS start, which Android may reject
             * while the device is locked.
             */
            val isNotificationEffect =
                intent.hasExtra(
                    EXTRA_PACKAGE_NAME
                ) &&
                        intent.hasExtra(
                            EXTRA_NOTIFICATION_KEY
                        )

            if (
                !isNotificationEffect &&
                !CalibrationPreviewCommandPolicy.bypassAccessibilityRoute(
                    intent.getStringExtra(EXTRA_CALIBRATION_TOKEN),
                    intent.getBooleanExtra(EXTRA_STOP_CALIBRATION, false),
                    intent.getBooleanExtra(EXTRA_PAUSE_CALIBRATION, false)
                ) &&
                HaloAccessibilityService.dispatch(
                    intent
                )
            ) {
                if (
                    isPersistentAmbientIntent(
                        intent
                    )
                ) {
                    pendingApplicationAmbientIntent =
                        null
                }

                return true
            }

            if (
                isPersistentAmbientIntent(
                    intent
                )
            ) {
                pendingApplicationAmbientIntent =
                    Intent(intent)
            }

            try {
                context.startForegroundService(
                    intent
                )
                return true
            } catch (
                exception: IllegalStateException
            ) {
                Log.e(
                    TAG,
                    "System rejected background start for halo service",
                    exception
                )
                return false
            } catch (
                exception: SecurityException
            ) {
                Log.e(
                    TAG,
                    "Missing permission to start halo foreground service",
                    exception
                )
                return false
            }
        }

        /**
         * Returns the Ambient command currently rendered by the fallback
         * application overlay, if any. It is used once accessibility connects.
         */
        internal fun takePendingApplicationAmbientIntent():
                Intent? =
            pendingApplicationAmbientIntent?.let { pendingIntent ->
                Intent(
                    pendingIntent
                )
            }

        /** Stops only the fallback FGS overlay after accessibility takes it over. */
        internal fun stopApplicationAmbientOverlay(
            context: Context
        ) {
            context.startService(
                Intent(
                    context,
                    HaloOverlayService::class.java
                ).apply {
                    putExtra(
                        EXTRA_STOP_APPLICATION_AMBIENT,
                        true
                    )
                }
            )
        }

        private fun isPersistentAmbientIntent(
            intent: Intent
        ): Boolean =
            intent.getBooleanExtra(EXTRA_AMBIENT, false)

        fun createStopAllIntent(
            context: Context
        ): Intent =
            Intent(
                context,
                HaloOverlayService::class.java
            ).apply {
                putExtra(
                    EXTRA_STOP_ALL,
                    true
                )
            }

        /** Stops only the persistent Ambient Halo, never a notification effect. */
        fun createStopAmbientIntent(
            context: Context
        ): Intent =
            Intent(
                context,
                HaloOverlayService::class.java
            ).apply {
                putExtra(
                    EXTRA_STOP_AMBIENT,
                    true
                )
            }

        fun createPreviewIntent(
            context: Context,
            settings: LuminoteSettings
        ): Intent =
            createIntent(
                context,
                settings
            ).apply {
                putExtra(
                    EXTRA_RESTART,
                    true
                )

                putExtra(
                    EXTRA_PREVIEW,
                    true
                )
            }

        fun createStopPreviewIntent(
            context: Context
        ): Intent =
            Intent(
                context,
                HaloOverlayService::class.java
            ).apply {
                putExtra(
                    EXTRA_STOP_PREVIEW,
                    true
                )
            }

        fun createCalibrationIntent(context: Context, settings: LuminoteSettings, token: String, generation: Long, start: Boolean): Intent =
            createIntent(context, settings, restart = start).apply {
                putExtra(EXTRA_CALIBRATION_TOKEN, token)
                putExtra(EXTRA_CALIBRATION_GENERATION, generation)
                putExtra(EXTRA_PREVIEW, true)
            }

        fun createPauseCalibrationIntent(context: Context, token: String, generation: Long): Intent =
            Intent(context, HaloOverlayService::class.java).apply {
                putExtra(EXTRA_PAUSE_CALIBRATION, true)
                putExtra(EXTRA_CALIBRATION_TOKEN, token)
                putExtra(EXTRA_CALIBRATION_GENERATION, generation)
            }

        fun createStopCalibrationIntent(context: Context, token: String, generation: Long): Intent =
            Intent(context, HaloOverlayService::class.java).apply {
                putExtra(EXTRA_STOP_CALIBRATION, true)
                putExtra(EXTRA_CALIBRATION_TOKEN, token)
                putExtra(EXTRA_CALIBRATION_GENERATION, generation)
            }

        /**
         * Ambient Halo is a persistent visual customisation, not a reminder.
         * It uses the same renderer but its own independent appearance values.
         */
        fun createAmbientIntent(
            context: Context,
            settings: LuminoteSettings
        ): Intent {
            val ambientSettings =
                settings.copy(
                    haloColor =
                        settings.ambientColor,
                    colorSource =
                        if (
                            settings.ambientColorMode ==
                            HaloColorMode.GRADIENT
                        ) {
                            HaloColorSource.GRADIENT
                        } else {
                            HaloColorSource.CUSTOM
                        },
                    haloIntensity =
                        settings.ambientIntensity,
                    haloThickness =
                        settings.ambientThickness,
                    haloMotion =
                        settings.ambientMotion,
                    haloEffectSpeed =
                        settings.ambientEffectSpeed,
                    gradientFlowSpeed =
                        settings.ambientGradientFlowSpeed,
                )

            val palette =
                if (
                    settings.ambientColorMode ==
                    HaloColorMode.GRADIENT
                ) {
                    HaloConfig.defaultGradientPalette()
                } else {
                    intArrayOf(
                        settings.ambientColor
                    )
                }

            return createIntent(
                context = context,
                settings = ambientSettings,
                paletteColors = palette,
                restart = true
            ).apply { putExtra(EXTRA_AMBIENT, true) }
        }
    }
}
