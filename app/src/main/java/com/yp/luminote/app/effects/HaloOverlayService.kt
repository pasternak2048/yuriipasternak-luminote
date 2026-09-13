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
import com.yp.luminote.app.data.settings.NotificationPlayback
import kotlin.math.max

/** Owns only the overlay window, incoming configuration and service lifetime. */
class HaloOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager
    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: HaloView? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var previewMode = false
    private var activeConfig: HaloConfig? = null
    private var foregroundStarted = false

    private val removeOverlayTask = Runnable {
        removeOverlay()
        stopSelf()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) updateOverlayBounds()
        }
    }

    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, handler)
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (HaloAccessibilityService.dispatch(intent)) {
            removeOverlay()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.getBooleanExtra(EXTRA_STOP_REPEATING, false) == true) {
            removeOverlay()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.getBooleanExtra(EXTRA_STOP_AMBIENT, false) == true) {
            if (activeConfig?.notificationPlayback == NotificationPlayback.KEEP_VISIBLE || overlayView == null) {
                removeOverlay()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (intent?.getBooleanExtra(EXTRA_STOP_PREVIEW, false) == true) {
            if (previewMode || overlayView == null) {
                removeOverlay()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val config = readConfig(intent)
        val paletteColors = intent?.getIntArrayExtra(EXTRA_PALETTE_COLORS)
        showOverlay(
            config = config,
            restart = intent?.getBooleanExtra(EXTRA_RESTART, false) == true,
            preview = intent?.getBooleanExtra(EXTRA_PREVIEW, false) == true,
            paletteColors = paletteColors
        )
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateOverlayBounds()
    }

    private fun readConfig(intent: Intent?): HaloConfig {
        val defaults = HaloConfig()
        val frameName = intent?.getStringExtra(EXTRA_FRAME) ?: defaults.frame.name
        val motionName = intent?.getStringExtra(EXTRA_MOTION) ?: defaults.motion.name
        val colorModeName = intent?.getStringExtra(EXTRA_COLOR_MODE) ?: defaults.colorMode.name
        return HaloConfig(
            color = intent?.getIntExtra(EXTRA_COLOR, defaults.color) ?: defaults.color,
            intervalSeconds = intent?.getFloatExtra(EXTRA_INTERVAL, defaults.intervalSeconds)
                ?: defaults.intervalSeconds,
            repeatCount = intent?.getIntExtra(EXTRA_REPEAT_COUNT, defaults.repeatCount)
                ?: defaults.repeatCount,
            intensity = intent?.getFloatExtra(EXTRA_INTENSITY, defaults.intensity) ?: defaults.intensity,
            thickness = intent?.getFloatExtra(EXTRA_THICKNESS, defaults.thickness) ?: defaults.thickness,
            frame = runCatching { HaloFrame.valueOf(frameName) }.getOrDefault(defaults.frame),
            motion = runCatching { HaloMotion.valueOf(motionName) }.getOrDefault(defaults.motion),
            effectSpeed = intent?.getFloatExtra(EXTRA_EFFECT_SPEED, defaults.effectSpeed)
                ?: defaults.effectSpeed,
            gradientFlowSpeed = intent?.getFloatExtra(EXTRA_GRADIENT_FLOW_SPEED, defaults.gradientFlowSpeed)
                ?: defaults.gradientFlowSpeed,
            colorMode = runCatching { HaloColorMode.valueOf(colorModeName) }.getOrDefault(defaults.colorMode),
            notificationPlayback = intent?.getStringExtra(EXTRA_NOTIFICATION_PLAYBACK)
                ?.let { runCatching { NotificationPlayback.valueOf(it) }.getOrNull() }
                ?: defaults.notificationPlayback
        ).sanitized()
    }

    private fun showOverlay(
        config: HaloConfig,
        restart: Boolean,
        preview: Boolean,
        paletteColors: IntArray?
    ) {
        overlayView?.let { view ->
            /*
             * A running overlay represents the whole notification burst.
             * Restarting its animator for every new notification makes the
             * transition jumpy and keeps the overlay alive unnecessarily.
             */
            if (restart) {
                previewMode = preview
                val resolvedConfig = configurePalette(config, paletteColors)
                activeConfig = resolvedConfig
                view.update(resolvedConfig)
                startAnimation(view, resolvedConfig)
                if (resolvedConfig.repeatCount > 0) {
                    scheduleRemoval(resolvedConfig)
                }
            } else if (paletteColors != null && activeConfig != null) {
                if (activeConfig?.repeatCount == -1) {
                    updatePersistentConfig(view, config, paletteColors)
                } else if (activeConfig?.colorMode == HaloColorMode.GRADIENT) {
                    updatePalette(paletteColors)
                }
            }
            return
        }

        if (config.intensity <= 0f) {
            stopSelf()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Halo requested without overlay permission")
            stopSelf()
            return
        }
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: run {
            stopSelf()
            return
        }
        val windowContext = createDisplayContext(display).createWindowContext(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            null
        )
        windowManager = windowContext.getSystemService(WindowManager::class.java)
        val metrics = OverlayDisplayMetrics.realMetrics(display)
        val resolvedConfig = configurePalette(config, paletteColors)
        val view = HaloView(windowContext, resolvedConfig)
        val params = WindowManager.LayoutParams(
            metrics.widthPixels,
            metrics.heightPixels,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            setFitInsetsSides(0)
            setFitInsetsIgnoringVisibility(true)
        }

        overlayView = view
        overlayLayoutParams = params
        previewMode = preview
        activeConfig = resolvedConfig
        try {
            windowManager.addView(view, params)
            Log.d(TAG, "Application halo overlay attached")
            startAnimation(view, resolvedConfig)
            if (resolvedConfig.repeatCount > 0) scheduleRemoval(resolvedConfig)
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to attach halo overlay", exception)
            removeOverlay()
            stopSelf()
        }
    }

    private fun configurePalette(
        config: HaloConfig,
        requestedColors: IntArray?
    ): HaloConfig {
        val palette = requestedColors
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?.toIntArray()
            ?: if (config.colorMode == HaloColorMode.GRADIENT) {
                HaloConfig.defaultGradientPalette()
            } else {
                intArrayOf(config.color)
            }
        return config.copy(
            color = palette.first(),
            palette = palette
        )
    }

    private fun updatePalette(requestedColors: IntArray) {
        val colors = requestedColors.distinct().takeIf { it.isNotEmpty() }?.toIntArray() ?: return
        activeConfig = activeConfig?.copy(palette = colors)
        overlayView?.let { view -> activeConfig?.let(view::update) }
    }

    private fun updatePersistentConfig(
        view: HaloView,
        config: HaloConfig,
        requestedColors: IntArray
    ) {
        val previous = activeConfig ?: return
        val wasAmbient = shouldRunAmbientLoop(previous)
        val colors = requestedColors
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.toIntArray()
            ?: intArrayOf(config.color)
        val currentColor = previous.color.takeIf { color -> color in colors }
            ?: colors.first()

        activeConfig = config.copy(
            color = currentColor,
            palette = colors
        )
        val updated = activeConfig ?: return
        val isAmbient = shouldRunAmbientLoop(updated)
        view.update(updated)

        /* Apply changes that affect a finite animation as one fresh cycle. */
        val needsAnimationRestart =
            previous.repeatCount != updated.repeatCount ||
                previous.durationSeconds != updated.durationSeconds ||
                previous.effectSpeed != updated.effectSpeed ||
                previous.intervalSeconds != updated.intervalSeconds ||
                previous.motion != updated.motion ||
                previous.colorMode != updated.colorMode ||
                previous.notificationPlayback != updated.notificationPlayback
        if (wasAmbient != isAmbient || (!isAmbient && needsAnimationRestart)) {
            startAnimation(view, updated)
        }
    }

    private fun startAnimation(view: HaloView, config: HaloConfig) {
        if (shouldRunAmbientLoop(config)) {
            view.startAmbientEffect(config.effectSpeed)
            return
        }
        view.repeatAnimation(
            duration = config.durationSeconds,
            interval = config.intervalSeconds,
            count = config.repeatCount,
            motion = config.motion
        )
    }

    private fun shouldRunAmbientLoop(config: HaloConfig): Boolean =
        config.notificationPlayback == NotificationPlayback.KEEP_VISIBLE &&
            config.repeatCount == -1

    private fun updateOverlayBounds() {
        val view = overlayView ?: return
        val params = overlayLayoutParams ?: return
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val metrics = OverlayDisplayMetrics.realMetrics(display)

        params.width = metrics.widthPixels
        params.height = metrics.heightPixels
        params.x = 0
        params.y = 0
        try {
            windowManager.updateViewLayout(view, params)
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to update halo overlay bounds", exception)
        }
    }

    private fun scheduleRemoval(config: HaloConfig) {
        handler.removeCallbacks(removeOverlayTask)
        val durationMs = max(
            HaloAnimation.MIN_DURATION_MS,
            (config.durationSeconds * 1000f).toLong()
        )
        val intervalMs = (config.intervalSeconds * 1000f).toLong().coerceAtLeast(0L)
        val totalDurationMs = durationMs * config.repeatCount + intervalMs * (config.repeatCount - 1)
        handler.postDelayed(
            removeOverlayTask,
            totalDurationMs + OVERLAY_REMOVAL_GRACE_MS
        )
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            try {
                if (view.isAttachedToWindow) windowManager.removeView(view)
            } catch (exception: Exception) {
                Log.w(TAG, "Unable to remove halo overlay", exception)
            }
        }
        overlayView = null
        overlayLayoutParams = null
        previewMode = false
        activeConfig = null
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        handler.removeCallbacks(removeOverlayTask)
        overlayView?.cancelAnimation()
        removeOverlay()
        if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                getString(R.string.halo_foreground_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
        val notification = Notification.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_halo_notification)
            .setContentTitle(getString(R.string.halo_foreground_notification_title))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        try {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            foregroundStarted = true
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to start halo foreground service", exception)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_COLOR = "extra_halo_color"
        const val EXTRA_INTERVAL = "extra_halo_interval"
        const val EXTRA_REPEAT_COUNT = "extra_halo_repeat_count"
        const val EXTRA_PALETTE_COLORS = "extra_halo_palette_colors"
        const val EXTRA_RESTART = "extra_restart_halo"
        const val EXTRA_PREVIEW = "extra_preview_halo"
        const val EXTRA_STOP_PREVIEW = "extra_stop_preview_halo"
        const val EXTRA_STOP_REPEATING = "extra_stop_repeating_halo"
        const val EXTRA_STOP_AMBIENT = "extra_stop_ambient_halo"
        const val EXTRA_INTENSITY = "extra_halo_intensity"
        const val EXTRA_THICKNESS = "extra_halo_thickness"
        const val EXTRA_FRAME = "extra_halo_frame"
        const val EXTRA_MOTION = "extra_halo_motion"
        const val EXTRA_EFFECT_SPEED = "extra_halo_effect_speed"
        const val EXTRA_GRADIENT_FLOW_SPEED = "extra_gradient_flow_speed"
        const val EXTRA_COLOR_MODE = "extra_halo_color_mode"
        const val EXTRA_NOTIFICATION_PLAYBACK = "extra_notification_playback"
        private const val TAG = "HaloOverlay"
        private const val FOREGROUND_CHANNEL_ID = "halo_overlay"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val OVERLAY_REMOVAL_GRACE_MS = 50L

        fun createIntent(
            context: Context,
            settings: LuminoteSettings,
            paletteColors: IntArray? = null,
            restart: Boolean = false
        ): Intent = Intent(context, HaloOverlayService::class.java).apply {
            putExtra(EXTRA_COLOR, settings.haloColor)
            putExtra(EXTRA_INTERVAL, settings.haloInterval)
            putExtra(
                EXTRA_REPEAT_COUNT,
                when (settings.notificationPlayback) {
                    NotificationPlayback.ONCE -> 1
                    NotificationPlayback.REPEAT -> settings.haloRepeatCount
                    NotificationPlayback.KEEP_VISIBLE -> -1
                }
            )
            putExtra(EXTRA_INTENSITY, settings.haloIntensity)
            putExtra(EXTRA_THICKNESS, settings.haloThickness)
            putExtra(EXTRA_FRAME, settings.haloFrame.name)
            putExtra(EXTRA_MOTION, settings.haloMotion.name)
            putExtra(EXTRA_EFFECT_SPEED, settings.haloEffectSpeed)
            putExtra(EXTRA_GRADIENT_FLOW_SPEED, settings.gradientFlowSpeed)
            putExtra(
                EXTRA_COLOR_MODE,
                if (settings.colorSource == HaloColorSource.GRADIENT) HaloColorMode.GRADIENT.name
                else HaloColorMode.SOLID.name
            )
            putExtra(EXTRA_NOTIFICATION_PLAYBACK, settings.notificationPlayback.name)
            paletteColors?.let { putExtra(EXTRA_PALETTE_COLORS, it) }
            if (restart) putExtra(EXTRA_RESTART, true)
        }

        /** Starts the overlay from a notification callback while the app is backgrounded. */
        fun start(context: Context, intent: Intent) {
            /*
             * Accessibility overlays are the lock-screen/AoD renderer. Route
             * there before attempting an FGS start, which Android may reject
             * while the device is locked.
             */
            if (HaloAccessibilityService.dispatch(intent)) {
                Log.d(TAG, "Command routed to accessibility halo")
                return
            }
            try {
                Log.d(TAG, "Starting application halo foreground service")
                context.startForegroundService(intent)
            } catch (exception: IllegalStateException) {
                Log.e(TAG, "System rejected background start for halo service", exception)
            } catch (exception: SecurityException) {
                Log.e(TAG, "Missing permission to start halo foreground service", exception)
            }
        }

        fun createStopRepeatingIntent(context: Context): Intent =
            Intent(context, HaloOverlayService::class.java).apply {
                putExtra(EXTRA_STOP_REPEATING, true)
            }

        /** Stops only the persistent Ambient Halo, never a notification effect. */
        fun createStopAmbientIntent(context: Context): Intent =
            Intent(context, HaloOverlayService::class.java).apply {
                putExtra(EXTRA_STOP_AMBIENT, true)
            }

        fun createPreviewIntent(
            context: Context,
            settings: LuminoteSettings
        ): Intent = createIntent(context, settings).apply {
            putExtra(EXTRA_RESTART, true)
            putExtra(EXTRA_PREVIEW, true)
        }

        fun createStopPreviewIntent(context: Context): Intent =
            Intent(context, HaloOverlayService::class.java).apply {
                putExtra(EXTRA_STOP_PREVIEW, true)
            }

        /**
         * Ambient Halo is a persistent visual customisation, not a reminder.
         * It uses the same renderer but its own independent appearance values.
         */
        fun createAmbientIntent(context: Context, settings: LuminoteSettings): Intent {
            val ambientSettings = settings.copy(
                haloColor = settings.ambientColor,
                colorSource = if (settings.ambientColorMode == HaloColorMode.GRADIENT) {
                    HaloColorSource.GRADIENT
                } else {
                    HaloColorSource.CUSTOM
                },
                haloIntensity = settings.ambientIntensity,
                haloThickness = settings.ambientThickness,
                haloMotion = settings.ambientMotion,
                haloEffectSpeed = settings.ambientEffectSpeed,
                gradientFlowSpeed = settings.ambientGradientFlowSpeed,
                notificationPlayback = NotificationPlayback.KEEP_VISIBLE
            )
            val palette = if (settings.ambientColorMode == HaloColorMode.GRADIENT) {
                HaloConfig.defaultGradientPalette()
            } else {
                intArrayOf(settings.ambientColor)
            }
            return createIntent(
                context = context,
                settings = ambientSettings,
                paletteColors = palette,
                restart = true
            )
        }

    }
}
