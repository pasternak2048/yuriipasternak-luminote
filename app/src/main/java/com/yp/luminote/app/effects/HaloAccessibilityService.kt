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
    private var activeQueuedCompletion: (() -> Unit)? = null
    private var queuedCompletionWatchdog: Runnable? = null

    private val removeOverlayTask = Runnable { removeOverlay() }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> pausePersistentAmbientForScreenOff()
                Intent.ACTION_SCREEN_ON ->
                    handler.post { resumePersistentAmbientAfterScreenOn() }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        displayManager =
            getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        powerManager =
            getSystemService(Context.POWER_SERVICE) as PowerManager

        registerReceiver(
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            Context.RECEIVER_NOT_EXPORTED
        )

        activeService = this

        HaloOverlayService.takePendingApplicationAmbientIntent()
            ?.let { ambientIntent ->
                handleCommand(ambientIntent)
                HaloOverlayService.stopApplicationAmbientOverlay(this)
            }
    }

    override fun onAccessibilityEvent(
        event: android.view.accessibility.AccessibilityEvent?
    ) = Unit

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateBounds()
    }

    override fun onDestroy() {
        if (activeService === this) {
            activeService = null
        }

        handler.removeCallbacks(removeOverlayTask)

        queuedCompletionWatchdog?.let(handler::removeCallbacks)
        queuedCompletionWatchdog = null

        unregisterReceiver(screenStateReceiver)
        removeOverlay()

        super.onDestroy()
    }

    private fun handleCommand(
        intent: Intent?,
        onFiniteAnimationCompleted: (() -> Unit)? = null
    ) {
        if (
            intent?.getBooleanExtra(
                HaloOverlayService.EXTRA_STOP_REPEATING,
                false
            ) == true
        ) {
            if (activeQueuedCompletion == null) {
                removeOverlay()
            }
            return
        }

        if (
            intent?.getBooleanExtra(
                HaloOverlayService.EXTRA_STOP_AMBIENT,
                false
            ) == true
        ) {
            if (
                activeQueuedCompletion == null &&
                (
                        activeConfig?.notificationPlayback ==
                                NotificationPlayback.KEEP_VISIBLE ||
                                overlayView == null
                        )
            ) {
                removeOverlay()
            }
            return
        }

        if (
            intent?.getBooleanExtra(
                HaloOverlayService.EXTRA_STOP_PREVIEW,
                false
            ) == true
        ) {
            if (
                activeQueuedCompletion == null &&
                (previewMode || overlayView == null)
            ) {
                removeOverlay()
            }
            return
        }

        // Queued notification playback has exclusive ownership of the overlay.
        if (activeQueuedCompletion != null) {
            return
        }

        val config = readConfig(intent)

        val palette =
            intent?.getIntArrayExtra(
                HaloOverlayService.EXTRA_PALETTE_COLORS
            )
                ?.distinct()
                ?.takeIf { it.isNotEmpty() }
                ?.toIntArray()
                ?: if (config.colorMode == HaloColorMode.GRADIENT) {
                    HaloConfig.defaultGradientPalette()
                } else {
                    intArrayOf(config.color)
                }

        val resolvedConfig = config.copy(
            color = palette.first(),
            palette = palette
        )

        val restart =
            intent?.getBooleanExtra(
                HaloOverlayService.EXTRA_RESTART,
                false
            ) == true

        val preview =
            intent?.getBooleanExtra(
                HaloOverlayService.EXTRA_PREVIEW,
                false
            ) == true

        overlayView?.let { view ->
            if (restart) {
                previewMode = preview
            }

            activeConfig = resolvedConfig
            view.update(resolvedConfig)
            view.setOnFiniteAnimationCompletedListener(
                onFiniteAnimationCompleted
            )

            if (
                restart ||
                resolvedConfig.notificationPlayback ==
                NotificationPlayback.KEEP_VISIBLE
            ) {
                startAnimation(view, resolvedConfig)
                scheduleRemoval(resolvedConfig)
            }

            return
        }

        if (resolvedConfig.intensity <= 0f) {
            return
        }

        val display =
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                ?: return

        val metrics = OverlayDisplayMetrics.realMetrics(display)

        val windowContext =
            createDisplayContext(display).createWindowContext(
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                null
            )

        windowManager =
            windowContext.getSystemService(WindowManager::class.java)

        val view = HaloView(
            windowContext,
            resolvedConfig
        )

        view.setOnFiniteAnimationCompletedListener(
            onFiniteAnimationCompleted
        )

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

            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams
                    .LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

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
            startAnimation(view, resolvedConfig)
            scheduleRemoval(resolvedConfig)
        }.onFailure {
            Log.e(
                TAG,
                "Unable to attach accessibility halo overlay",
                it
            )
            removeOverlay()
        }
    }

    private fun startAnimation(
        view: HaloView,
        config: HaloConfig
    ) {
        if (
            config.notificationPlayback ==
            NotificationPlayback.KEEP_VISIBLE
        ) {
            handler.removeCallbacks(removeOverlayTask)

            view.startAmbientEffect(config.effectSpeed)

            if (!powerManager.isInteractive) {
                view.pauseAmbientEffect()
            }
        } else {
            view.repeatAnimation(
                config.durationSeconds,
                config.intervalSeconds,
                config.repeatCount,
                config.motion
            )
        }
    }

    private fun scheduleRemoval(config: HaloConfig) {
        if (
            config.notificationPlayback ==
            NotificationPlayback.KEEP_VISIBLE
        ) {
            return
        }

        handler.removeCallbacks(removeOverlayTask)

        val duration = max(
            HaloAnimation.MIN_DURATION_MS,
            (config.durationSeconds * 1000f).toLong()
        )

        val interval =
            (config.intervalSeconds * 1000f)
                .toLong()
                .coerceAtLeast(0L)

        handler.postDelayed(
            removeOverlayTask,
            duration * config.repeatCount +
                    interval * (config.repeatCount - 1) +
                    50L
        )
    }

    private fun updateBounds() {
        val view = overlayView ?: return
        val params = layoutParams ?: return

        val display =
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                ?: return

        val metrics =
            OverlayDisplayMetrics.realMetrics(display)

        params.width = metrics.widthPixels
        params.height = metrics.heightPixels

        runCatching {
            windowManager.updateViewLayout(view, params)
        }
    }

    private fun pausePersistentAmbientForScreenOff() {
        val config = activeConfig ?: return

        if (
            config.notificationPlayback !=
            NotificationPlayback.KEEP_VISIBLE
        ) {
            return
        }

        overlayView?.pauseAmbientEffect()
    }

    private fun resumePersistentAmbientAfterScreenOn() {
        val config = activeConfig ?: return

        if (
            config.notificationPlayback !=
            NotificationPlayback.KEEP_VISIBLE
        ) {
            return
        }

        overlayView?.resumeAmbientEffect()
    }

    private fun removeOverlay() {
        val queuedCompletion = activeQueuedCompletion
        activeQueuedCompletion = null

        overlayView?.let { view ->
            view.cancelAnimation()

            runCatching {
                if (view.isAttachedToWindow) {
                    windowManager.removeView(view)
                }
            }
        }

        overlayView = null
        layoutParams = null
        activeConfig = null
        previewMode = false

        queuedCompletion?.invoke()
    }

    private fun readConfig(intent: Intent?): HaloConfig {
        val defaults = HaloConfig()

        return HaloConfig(
            color = intent?.getIntExtra(
                HaloOverlayService.EXTRA_COLOR,
                defaults.color
            ) ?: defaults.color,

            intervalSeconds = intent?.getFloatExtra(
                HaloOverlayService.EXTRA_INTERVAL,
                defaults.intervalSeconds
            ) ?: defaults.intervalSeconds,

            repeatCount = intent?.getIntExtra(
                HaloOverlayService.EXTRA_REPEAT_COUNT,
                defaults.repeatCount
            ) ?: defaults.repeatCount,

            intensity = intent?.getFloatExtra(
                HaloOverlayService.EXTRA_INTENSITY,
                defaults.intensity
            ) ?: defaults.intensity,

            thickness = intent?.getFloatExtra(
                HaloOverlayService.EXTRA_THICKNESS,
                defaults.thickness
            ) ?: defaults.thickness,

            frame = intent?.getStringExtra(
                HaloOverlayService.EXTRA_FRAME
            )
                ?.let {
                    runCatching {
                        HaloFrame.valueOf(it)
                    }.getOrNull()
                }
                ?: defaults.frame,

            motion = intent?.getStringExtra(
                HaloOverlayService.EXTRA_MOTION
            )
                ?.let {
                    runCatching {
                        HaloMotion.valueOf(it)
                    }.getOrNull()
                }
                ?: defaults.motion,

            effectSpeed = intent?.getFloatExtra(
                HaloOverlayService.EXTRA_EFFECT_SPEED,
                defaults.effectSpeed
            ) ?: defaults.effectSpeed,

            gradientFlowSpeed = intent?.getFloatExtra(
                HaloOverlayService.EXTRA_GRADIENT_FLOW_SPEED,
                defaults.gradientFlowSpeed
            ) ?: defaults.gradientFlowSpeed,

            colorMode = intent?.getStringExtra(
                HaloOverlayService.EXTRA_COLOR_MODE
            )
                ?.let {
                    runCatching {
                        HaloColorMode.valueOf(it)
                    }.getOrNull()
                }
                ?: defaults.colorMode,

            notificationPlayback = intent?.getStringExtra(
                HaloOverlayService.EXTRA_NOTIFICATION_PLAYBACK
            )
                ?.let {
                    runCatching {
                        NotificationPlayback.valueOf(it)
                    }.getOrNull()
                }
                ?: defaults.notificationPlayback
        ).sanitized()
    }

    private fun showQueuedEffect(
        request: HaloEffectRequest,
        onFiniteAnimationCompleted: () -> Unit
    ) {
        /*
         * A queued notification effect owns the accessibility overlay until it
         * completes. A removal timer left by preview/legacy playback must not
         * terminate the queued effect.
         */
        handler.removeCallbacks(removeOverlayTask)

        activeQueuedCompletion = onFiniteAnimationCompleted

        /*
         * Completion can come from either the ValueAnimator or the watchdog.
         * Only the first completion is allowed to finish the queued request.
         */
        var completed = false

        val complete: () -> Unit = complete@{
            if (completed) {
                return@complete
            }

            completed = true

            queuedCompletionWatchdog?.let(handler::removeCallbacks)
            queuedCompletionWatchdog = null

            val completion = activeQueuedCompletion
            activeQueuedCompletion = null

            removeOverlay()
            completion?.invoke()
        }

        val palette =
            request.paletteColors
                ?.distinct()
                ?.takeIf { it.isNotEmpty() }
                ?.toIntArray()
                ?: if (
                    request.config.colorMode ==
                    HaloColorMode.GRADIENT
                ) {
                    HaloConfig.defaultGradientPalette()
                } else {
                    intArrayOf(request.config.color)
                }

        val resolvedConfig = request.config.copy(
            color = palette.first(),
            palette = palette
        )

        overlayView
            ?.takeIf { it.isAttachedToWindow }
            ?.let { view ->
                activeConfig = resolvedConfig
                previewMode = false

                view.update(resolvedConfig)
                view.setOnFiniteAnimationCompletedListener(
                    complete
                )

                startAnimation(view, resolvedConfig)

                scheduleQueuedCompletionFallback(
                    config = resolvedConfig,
                    onCompleted = complete
                )

                return
            }

        /*
         * A stale HaloView reference must never consume a queued request.
         * HaloView intentionally refuses to start a finite animation while
         * detached, which would otherwise leave the coordinator waiting
         * forever.
         */
        overlayView?.let { staleView ->
            staleView.setOnFiniteAnimationCompletedListener(null)
            staleView.cancelAnimation()
        }

        overlayView = null
        layoutParams = null
        activeConfig = null
        previewMode = false

        if (resolvedConfig.intensity <= 0f) {
            complete()
            return
        }

        val display =
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)

        if (display == null) {
            complete()
            return
        }

        val metrics =
            OverlayDisplayMetrics.realMetrics(display)

        val windowContext =
            createDisplayContext(display).createWindowContext(
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                null
            )

        windowManager =
            windowContext.getSystemService(WindowManager::class.java)

        val view = HaloView(
            windowContext,
            resolvedConfig
        ).apply {
            setOnFiniteAnimationCompletedListener(complete)
        }

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

            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams
                    .LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

            setFitInsetsTypes(0)
            setFitInsetsSides(0)
            setFitInsetsIgnoringVisibility(true)
        }

        overlayView = view
        layoutParams = params
        activeConfig = resolvedConfig
        previewMode = false

        runCatching {
            windowManager.addView(view, params)
            startAnimation(view, resolvedConfig)

            scheduleQueuedCompletionFallback(
                config = resolvedConfig,
                onCompleted = complete
            )
        }.onFailure {
            Log.e(
                TAG,
                "Unable to attach queued accessibility halo overlay",
                it
            )
            complete()
        }
    }

    private fun scheduleQueuedCompletionFallback(
        config: HaloConfig,
        onCompleted: () -> Unit
    ) {
        /*
         * KEEP_VISIBLE intentionally has no finite lifetime and therefore
         * cannot use the transient completion watchdog.
         */
        if (
            config.notificationPlayback ==
            NotificationPlayback.KEEP_VISIBLE
        ) {
            return
        }

        /*
         * The watchdog belongs to exactly one queued effect. Remove the
         * previous runnable before scheduling a new one so a stale timeout
         * cannot complete the next request.
         */
        queuedCompletionWatchdog?.let(handler::removeCallbacks)
        queuedCompletionWatchdog = null

        val duration = max(
            HaloAnimation.MIN_DURATION_MS,
            (config.durationSeconds * 1000f).toLong()
        )

        val interval =
            (config.intervalSeconds * 1000f)
                .toLong()
                .coerceAtLeast(0L)

        val cycles =
            config.repeatCount.coerceAtLeast(1)

        val totalDuration =
            duration * cycles +
                    interval * (cycles - 1)

        val watchdogDelay =
            totalDuration + QUEUED_COMPLETION_GRACE_MS

        lateinit var watchdog: Runnable

        watchdog = Runnable {
            /*
             * Ignore a runnable that no longer owns the active queued effect.
             */
            if (queuedCompletionWatchdog !== watchdog) {
                return@Runnable
            }

            queuedCompletionWatchdog = null

            Log.w(
                TAG,
                "Queued animation completion watchdog fired " +
                        "after ${watchdogDelay}ms " +
                        "(expected animation duration=${totalDuration}ms)"
            )

            onCompleted()
        }

        queuedCompletionWatchdog = watchdog
        handler.postDelayed(
            watchdog,
            watchdogDelay
        )
    }

    companion object {
        @Volatile
        private var activeService: HaloAccessibilityService? = null

        fun dispatch(intent: Intent?): Boolean {
            val service = activeService ?: run {
                Log.w(
                    TAG,
                    "Accessibility halo unavailable; " +
                            "falling back to application overlay"
                )
                return false
            }

            Log.d(
                TAG,
                "Dispatch received: " +
                        "main=${Looper.myLooper() == Looper.getMainLooper()}, " +
                        "service=${System.identityHashCode(service)}"
            )

            /*
             * onStartCommand() and normal notification callbacks are already
             * on the main thread. Handle them immediately so a lock-screen
             * effect is not deferred behind a paused/frozen app queue.
             */
            if (Looper.myLooper() == Looper.getMainLooper()) {
                service.handleCommand(intent)
            } else {
                service.handler.post {
                    if (activeService === service) {
                        service.handleCommand(intent)
                    }
                }
            }

            return true
        }

        internal fun dispatchQueuedEffect(
            request: HaloEffectRequest,
            onFiniteAnimationCompleted: () -> Unit
        ): Boolean {
            val service =
                activeService ?: return false

            val command = {
                if (activeService === service) {
                    service.showQueuedEffect(
                        request = request,
                        onFiniteAnimationCompleted =
                            onFiniteAnimationCompleted
                    )
                } else {
                    onFiniteAnimationCompleted()
                }
            }

            if (Looper.myLooper() == Looper.getMainLooper()) {
                command()
            } else {
                service.handler.post(command)
            }

            return true
        }

        private const val TAG = "HaloAccessibility"

        // The watchdog is a recovery path, not the normal animation timer.
        // Give ValueAnimator enough time to finish naturally even if a frame
        // or the main thread is briefly delayed.
        private const val QUEUED_COMPLETION_GRACE_MS = 1_000L
    }
}
