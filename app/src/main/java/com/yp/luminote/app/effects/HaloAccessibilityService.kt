package com.yp.luminote.app.effects

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.data.settings.HaloFrame
import com.yp.luminote.app.data.settings.HaloMotion
import com.yp.luminote.app.data.settings.NotificationPlayback
import kotlin.math.max

/**
 * Trusted overlay host used only after the user explicitly enables Luminote
 * in Accessibility settings. It never wakes the display or accepts touches.
 */
class HaloAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var displayManager: DisplayManager
    private lateinit var powerManager: PowerManager
    private lateinit var windowManager: WindowManager
    private var overlayView: HaloView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var activeConfig: HaloConfig? = null
    private var previewMode = false

    private val removeOverlayTask = Runnable { removeOverlay() }
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> pausePersistentAmbientForScreenOff()
                Intent.ACTION_SCREEN_ON -> handler.post { resumePersistentAmbientAfterScreenOn() }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        registerReceiver(
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            Context.RECEIVER_NOT_EXPORTED
        )
        activeService = this
        Log.i(TAG, "Lock-screen accessibility service connected")
        HaloOverlayService.takePendingApplicationAmbientIntent()?.let { ambientIntent ->
            handleCommand(ambientIntent)
            HaloOverlayService.stopApplicationAmbientOverlay(this)
            Log.i(TAG, "Persistent Ambient Halo handed off from application overlay")
        }
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateBounds()
    }

    override fun onDestroy() {
        Log.w(TAG, "Lock-screen accessibility service destroyed")
        if (activeService === this) activeService = null
        handler.removeCallbacks(removeOverlayTask)
        unregisterReceiver(screenStateReceiver)
        removeOverlay()
        super.onDestroy()
    }

    private fun handleCommand(intent: Intent?) {
        Log.d(TAG, "Accessibility halo command received")
        if (intent?.getBooleanExtra(HaloOverlayService.EXTRA_STOP_REPEATING, false) == true) {
            removeOverlay()
            return
        }
        if (intent?.getBooleanExtra(HaloOverlayService.EXTRA_STOP_AMBIENT, false) == true) {
            if (activeConfig?.notificationPlayback == NotificationPlayback.KEEP_VISIBLE || overlayView == null) {
                removeOverlay()
            }
            return
        }
        if (intent?.getBooleanExtra(HaloOverlayService.EXTRA_STOP_PREVIEW, false) == true) {
            if (previewMode || overlayView == null) removeOverlay()
            return
        }

        val config = readConfig(intent)
        val palette = intent?.getIntArrayExtra(HaloOverlayService.EXTRA_PALETTE_COLORS)
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?.toIntArray()
            ?: if (config.colorMode == HaloColorMode.GRADIENT) {
                HaloConfig.defaultGradientPalette()
            } else {
                intArrayOf(config.color)
            }
        val resolvedConfig = config.copy(color = palette.first(), palette = palette)
        val restart = intent?.getBooleanExtra(HaloOverlayService.EXTRA_RESTART, false) == true
        val preview = intent?.getBooleanExtra(HaloOverlayService.EXTRA_PREVIEW, false) == true

        overlayView?.let { view ->
            if (restart) previewMode = preview
            activeConfig = resolvedConfig
            view.update(resolvedConfig)
            if (restart || resolvedConfig.notificationPlayback == NotificationPlayback.KEEP_VISIBLE) {
                startAnimation(view, resolvedConfig)
                scheduleRemoval(resolvedConfig)
            }
            return
        }

        if (resolvedConfig.intensity <= 0f) return
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val metrics = OverlayDisplayMetrics.realMetrics(display)
        val windowContext = createDisplayContext(display).createWindowContext(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            null
        )
        windowManager = windowContext.getSystemService(WindowManager::class.java)
        val view = HaloView(windowContext, resolvedConfig)
        val params = WindowManager.LayoutParams(
            metrics.widthPixels,
            metrics.heightPixels,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            setFitInsetsSides(0)
            setFitInsetsIgnoringVisibility(true)
        }
        overlayView = view
        layoutParams = params
        activeConfig = resolvedConfig
        previewMode = preview
        runCatching {
            windowManager.addView(view, params)
            Log.d(TAG, "Accessibility halo overlay attached")
            startAnimation(view, resolvedConfig)
            scheduleRemoval(resolvedConfig)
        }.onFailure {
            Log.e(TAG, "Unable to attach accessibility halo overlay", it)
            removeOverlay()
        }
    }

    private fun startAnimation(view: HaloView, config: HaloConfig) {
        if (config.notificationPlayback == NotificationPlayback.KEEP_VISIBLE) {
            handler.removeCallbacks(removeOverlayTask)
            view.startAmbientEffect(config.effectSpeed)
            if (!powerManager.isInteractive) {
                view.pauseAmbientEffect()
                Log.d(TAG, "Persistent ambient halo paused because the screen is off")
            }
        } else {
            view.repeatAnimation(config.durationSeconds, config.intervalSeconds, config.repeatCount, config.motion)
        }
    }

    private fun scheduleRemoval(config: HaloConfig) {
        if (config.notificationPlayback == NotificationPlayback.KEEP_VISIBLE) return
        handler.removeCallbacks(removeOverlayTask)
        val duration = max(HaloAnimation.MIN_DURATION_MS, (config.durationSeconds * 1000f).toLong())
        val interval = (config.intervalSeconds * 1000f).toLong().coerceAtLeast(0L)
        handler.postDelayed(removeOverlayTask, duration * config.repeatCount + interval * (config.repeatCount - 1) + 50L)
    }

    private fun updateBounds() {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val metrics = OverlayDisplayMetrics.realMetrics(display)
        params.width = metrics.widthPixels
        params.height = metrics.heightPixels
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun pausePersistentAmbientForScreenOff() {
        val config = activeConfig ?: return
        if (config.notificationPlayback != NotificationPlayback.KEEP_VISIBLE) return
        overlayView?.pauseAmbientEffect()
        Log.d(TAG, "Persistent ambient halo paused for screen off")
    }

    private fun resumePersistentAmbientAfterScreenOn() {
        val config = activeConfig ?: return
        if (config.notificationPlayback != NotificationPlayback.KEEP_VISIBLE) return
        overlayView?.resumeAmbientEffect()
        Log.d(TAG, "Persistent ambient halo resumed for screen on")
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            view.cancelAnimation()
            runCatching { if (view.isAttachedToWindow) windowManager.removeView(view) }
        }
        overlayView = null
        layoutParams = null
        activeConfig = null
        previewMode = false
    }

    private fun readConfig(intent: Intent?): HaloConfig {
        val defaults = HaloConfig()
        return HaloConfig(
            color = intent?.getIntExtra(HaloOverlayService.EXTRA_COLOR, defaults.color) ?: defaults.color,
            intervalSeconds = intent?.getFloatExtra(HaloOverlayService.EXTRA_INTERVAL, defaults.intervalSeconds) ?: defaults.intervalSeconds,
            repeatCount = intent?.getIntExtra(HaloOverlayService.EXTRA_REPEAT_COUNT, defaults.repeatCount) ?: defaults.repeatCount,
            intensity = intent?.getFloatExtra(HaloOverlayService.EXTRA_INTENSITY, defaults.intensity) ?: defaults.intensity,
            thickness = intent?.getFloatExtra(HaloOverlayService.EXTRA_THICKNESS, defaults.thickness) ?: defaults.thickness,
            frame = intent?.getStringExtra(HaloOverlayService.EXTRA_FRAME)
                ?.let { runCatching { HaloFrame.valueOf(it) }.getOrNull() } ?: defaults.frame,
            motion = intent?.getStringExtra(HaloOverlayService.EXTRA_MOTION)
                ?.let { runCatching { HaloMotion.valueOf(it) }.getOrNull() } ?: defaults.motion,
            effectSpeed = intent?.getFloatExtra(HaloOverlayService.EXTRA_EFFECT_SPEED, defaults.effectSpeed) ?: defaults.effectSpeed,
            gradientFlowSpeed = intent?.getFloatExtra(HaloOverlayService.EXTRA_GRADIENT_FLOW_SPEED, defaults.gradientFlowSpeed) ?: defaults.gradientFlowSpeed,
            colorMode = intent?.getStringExtra(HaloOverlayService.EXTRA_COLOR_MODE)
                ?.let { runCatching { HaloColorMode.valueOf(it) }.getOrNull() } ?: defaults.colorMode,
            notificationPlayback = intent?.getStringExtra(HaloOverlayService.EXTRA_NOTIFICATION_PLAYBACK)
                ?.let { runCatching { NotificationPlayback.valueOf(it) }.getOrNull() } ?: defaults.notificationPlayback
        ).sanitized()
    }

    companion object {
        @Volatile private var activeService: HaloAccessibilityService? = null

        fun dispatch(intent: Intent?): Boolean {
            val service = activeService ?: run {
                Log.w(TAG, "Accessibility halo unavailable; falling back to application overlay")
                return false
            }
            /*
             * onStartCommand() and normal notification callbacks are already on
             * the main thread. Handle them immediately so a lock-screen effect
             * is not deferred behind a paused/frozen app queue.
             */
            if (Looper.myLooper() == Looper.getMainLooper()) {
                service.handleCommand(intent)
            } else {
                service.handler.post {
                    if (activeService === service) service.handleCommand(intent)
                }
            }
            return true
        }

        private const val TAG = "HaloAccessibility"
    }
}
