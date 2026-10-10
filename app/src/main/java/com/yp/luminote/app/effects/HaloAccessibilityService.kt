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
import com.yp.luminote.app.data.settings.HaloAnimationRegistry
import com.yp.luminote.app.notification.HaloReminderRuntime
import kotlin.math.max

/**
 * Trusted overlay host used only after the user explicitly enables Luminote
 * in Accessibility settings. It never wakes the display or accepts touches.
 */
class HaloAccessibilityService : AccessibilityService() {

    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    private lateinit var displayManager:
            DisplayManager

    private lateinit var powerManager:
            PowerManager

    private lateinit var windowManager:
            WindowManager

    private var overlayView:
            HaloView? = null

    private var layoutParams:
            WindowManager.LayoutParams? = null

    private var activeConfig:
            HaloConfig? = null

    private var previewMode =
        false

    private var calibrationToken: String? = null

    private var pendingCalibrationIntent: Intent? = null

    private var lastCalibrationIntent: Intent? = null

    private var activeQueuedCompletion:
            (() -> Unit)? = null

    private var queuedCompletionWatchdog:
            Runnable? = null

    private var queuedRequestToken =
        0L

    private val queuedCompletionLease =
        QueuedCompletionLease()

    private val removeOverlayTask =
        Runnable {
            removeOverlay()
        }

    private val screenStateReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context,
                intent: Intent
            ) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF ->
                        pausePersistentAmbientForScreenOff()

                    Intent.ACTION_SCREEN_ON ->
                        handler.post {
                            resumePersistentAmbientAfterScreenOn()
                        }
                }
            }
        }

    override fun onServiceConnected() {
        super.onServiceConnected()

        displayManager =
            getSystemService(
                Context.DISPLAY_SERVICE
            ) as DisplayManager

        powerManager =
            getSystemService(
                Context.POWER_SERVICE
            ) as PowerManager

        registerReceiver(
            screenStateReceiver,
            IntentFilter().apply {
                addAction(
                    Intent.ACTION_SCREEN_OFF
                )

                addAction(
                    Intent.ACTION_SCREEN_ON
                )
            },
            Context.RECEIVER_NOT_EXPORTED
        )

        activeService =
            this

        HaloOverlayService
            .takePendingApplicationAmbientIntent()
            ?.let { ambientIntent ->
                handleCommand(
                    ambientIntent
                )

                HaloEffectController.onAccessibilityAmbientTakeover(this)

                HaloOverlayService
                    .stopApplicationAmbientOverlay(
                        this,
                        ambientIntent
                    )
            }
    }

    override fun onAccessibilityEvent(
        event:
        android.view.accessibility.AccessibilityEvent?
    ) = Unit

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(
        newConfig: Configuration
    ) {
        super.onConfigurationChanged(
            newConfig
        )

        updateBounds()
    }

    override fun onDestroy() {
        if (
            activeService ===
            this
        ) {
            activeService =
                null
        }

        handler.removeCallbacks(
            removeOverlayTask
        )

        queuedCompletionLease.invalidate(
            queuedRequestToken
        )

        queuedRequestToken++

        cancelQueuedWatchdog()

        queuedRequestToken++

        unregisterReceiver(
            screenStateReceiver
        )

        removeOverlay()

        super.onDestroy()
    }

    private fun handleCommand(
        intent: Intent?,
        onFiniteAnimationCompleted:
        (() -> Unit)? = null
    ) {
        val commandCalibrationToken = intent?.getStringExtra(HaloOverlayService.EXTRA_CALIBRATION_TOKEN)
        if (intent?.getBooleanExtra(HaloOverlayService.EXTRA_STOP_CALIBRATION, false) == true) {
            if (CalibrationPreviewSession.isCurrent(commandCalibrationToken)) {
                CalibrationPreviewSession.stop(commandCalibrationToken)
                pendingCalibrationIntent = null
                lastCalibrationIntent = null
                if (activeQueuedCompletion == null && previewMode) removeOverlay()
            }
            return
        }
        if (commandCalibrationToken != null && !CalibrationPreviewSession.isCurrent(commandCalibrationToken)) return
        if (commandCalibrationToken != null && activeConfig?.renderMode == HaloRenderMode.AMBIENT && !CalibrationPreviewSession.isCurrent(calibrationToken)) {
            CalibrationPreviewSession.stop(commandCalibrationToken)
            return
        }
        if (commandCalibrationToken != null && activeQueuedCompletion != null) {
            pendingCalibrationIntent = intent
            return
        }
        if (commandCalibrationToken != null) {
            calibrationToken = commandCalibrationToken
            lastCalibrationIntent = intent
        }
        if (commandCalibrationToken == null && CalibrationPreviewSession.isCurrent(calibrationToken) &&
            intent?.getBooleanExtra(HaloOverlayService.EXTRA_AMBIENT, false) == true) {
            return
        }
        if (
            intent?.getBooleanExtra(
                HaloOverlayService.EXTRA_STOP_ALL,
                false
            ) == true
        ) {
            cancelQueuedPlaybackForGlobalStop()
            HaloOverlayService.cancelTransientPlaybackForGlobalStop()
            HaloReminderRuntime.onReminderPlaybackCancelled(
                recoverWithFreshInterval = false
            )
            removeOverlay()

            return
        }

        if (
            intent?.getBooleanExtra(
                HaloOverlayService.EXTRA_STOP_AMBIENT,
                false
            ) == true
        ) {
            if (CalibrationPreviewSession.isCurrent(calibrationToken)) return
            if (
                activeQueuedCompletion ==
                null &&
                (
                        activeConfig?.renderMode == HaloRenderMode.AMBIENT ||
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
            if (CalibrationPreviewCommandPolicy.ignoreTokenlessPreviewStop(calibrationToken)) return
            if (
                activeQueuedCompletion ==
                null &&
                (
                        previewMode ||
                                overlayView == null
                        )
            ) {
                removeOverlay()
            }

            return
        }

        /*
         * Queued notification playback owns the overlay exclusively.
         * Normal commands must not interfere with the active queued request.
         */
        if (
            activeQueuedCompletion !=
            null
        ) {
            return
        }

        val config =
            readConfig(
                intent
            )

        val palette =
            intent?.getIntArrayExtra(
                HaloOverlayService.EXTRA_PALETTE_COLORS
            )
                ?.distinct()
                ?.takeIf {
                    it.isNotEmpty()
                }
                ?.toIntArray()
                ?: if (
                    config.colorMode ==
                    HaloColorMode.GRADIENT
                ) {
                    HaloConfig
                        .defaultGradientPalette()
                } else {
                    intArrayOf(
                        config.color
                    )
                }

        val resolvedConfig =
            config.copy(
                color =
                    palette.first(),
                palette =
                    palette
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

        overlayView
            ?.let { view ->

                if (CalibrationPreviewSession.isCurrent(calibrationToken)) {
                    activeConfig = resolvedConfig
                    view.update(resolvedConfig)
                    return
                }

                if (restart) {
                    previewMode =
                        preview
                }

                activeConfig =
                    resolvedConfig

                view.update(
                    resolvedConfig
                )

                view.setOnFiniteAnimationCompletedListener(
                    onFiniteAnimationCompleted
                )

                if (restart) {
                    startAnimation(
                        view,
                        resolvedConfig
                    )

                    scheduleRemoval(
                        resolvedConfig
                    )
                }

                return
            }

        if (
            resolvedConfig.intensity <=
            0f
        ) {
            return
        }

        val display =
            displayManager.getDisplay(
                Display.DEFAULT_DISPLAY
            )
                ?: return

        val metrics =
            OverlayDisplayMetrics.realMetrics(
                display
            )

        val windowContext =
            createDisplayContext(
                display
            )
                .createWindowContext(
                    WindowManager.LayoutParams
                        .TYPE_ACCESSIBILITY_OVERLAY,
                    null
                )

        windowManager =
            windowContext.getSystemService(
                WindowManager::class.java
            )

        val view =
            HaloView(
                windowContext,
                resolvedConfig
            )

        view.setOnFiniteAnimationCompletedListener(
            onFiniteAnimationCompleted
        )

        view.setOnGpuTerminalFailureListener {
            terminateGpuOverlay(view)
        }

        val params =
            WindowManager.LayoutParams(
                metrics.widthPixels,
                metrics.heightPixels,
                WindowManager.LayoutParams
                    .TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams
                    .FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams
                            .FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams
                            .FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams
                            .FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity =
                    Gravity.TOP or
                            Gravity.START

                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams
                        .LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

                setFitInsetsTypes(
                    0
                )

                setFitInsetsSides(
                    0
                )

                setFitInsetsIgnoringVisibility(
                    true
                )
            }

        overlayView =
            view

        layoutParams =
            params

        activeConfig =
            resolvedConfig

        previewMode =
            preview

        runCatching {
            windowManager.addView(
                view,
                params
            )

            if (CalibrationPreviewSession.isCurrent(calibrationToken)) {
                view.showStaticFrame()
            } else {
                startAnimation(view, resolvedConfig)
                scheduleRemoval(resolvedConfig)
            }
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
        if (config.renderMode == HaloRenderMode.AMBIENT) {
            handler.removeCallbacks(
                removeOverlayTask
            )

            view.startAmbientEffect(
                effectSpeed = config.effectSpeed,
                motion = config.motion
            )

            if (
                !powerManager.isInteractive
            ) {
                view.pauseAmbientEffect()
            }

            return
        }

        view.repeatAnimation(
            config.durationSeconds,
            config.intervalSeconds,
            1,
            config.motion
        )
    }

    private fun scheduleRemoval(
        config: HaloConfig
    ) {
        if (config.renderMode == HaloRenderMode.AMBIENT) {
            return
        }

        handler.removeCallbacks(
            removeOverlayTask
        )

        val duration =
            max(
                HaloAnimation.MIN_DURATION_MS,
                (
                        finiteDurationFor(
                            config.renderMode,
                            config.durationSeconds
                        ) *
                                1000f
                        ).toLong()
            )

        val interval =
            (
                    config.intervalSeconds *
                            1000f
                    )
                .toLong()
                .coerceAtLeast(
                    0L
                )

        handler.postDelayed(
            removeOverlayTask,
            duration +
                    50L
        )
    }

    private fun updateBounds() {
        val view =
            overlayView
                ?: return

        val params =
            layoutParams
                ?: return

        val display =
            displayManager.getDisplay(
                Display.DEFAULT_DISPLAY
            )
                ?: return

        val metrics =
            OverlayDisplayMetrics.realMetrics(
                display
            )

        params.width =
            metrics.widthPixels

        params.height =
            metrics.heightPixels

        runCatching {
            windowManager.updateViewLayout(
                view,
                params
            )
        }
    }

    private fun pausePersistentAmbientForScreenOff() {
        val config =
            activeConfig
                ?: return

        if (config.renderMode != HaloRenderMode.AMBIENT) {
            return
        }

        overlayView
            ?.pauseAmbientEffect()
    }

    private fun resumePersistentAmbientAfterScreenOn() {
        val config =
            activeConfig
                ?: return

        if (config.renderMode != HaloRenderMode.AMBIENT) {
            return
        }

        overlayView
            ?.resumeAmbientEffect()
    }

    private fun removeOverlay() {
        val queuedCompletion =
            activeQueuedCompletion

        val queuedTerminalClaimed =
            queuedCompletion != null &&
                    queuedCompletionLease.terminateForTeardown(
                        queuedRequestToken
                    )

        queuedRequestToken++

        cancelQueuedWatchdog()

        activeQueuedCompletion =
            null

        overlayView
            ?.let { view ->

                view.setOnFiniteAnimationCompletedListener(
                    null
                )

                view.setOnFiniteAnimationStartedListener(
                    null
                )

                view.cancelAnimation()

                runCatching {
                    if (
                        view.isAttachedToWindow
                    ) {
                        windowManager.removeView(
                            view
                        )
                    }
                }
            }

        overlayView =
            null

        layoutParams =
            null

        activeConfig =
            null

        previewMode =
            false

        if (queuedTerminalClaimed) {
            completeQueuedTerminal(queuedCompletion)
        }
    }

    /** The accessibility host stays alive, but the failed GPU overlay must not remain attached. */
    private fun terminateGpuOverlay(view: HaloView) {
        handler.post {
            if (overlayView !== view) return@post
            Log.e(TAG, "Terminal GPU failure; removing accessibility overlay renderer")
            handler.removeCallbacks(removeOverlayTask)
            view.setOnGpuTerminalFailureListener(null)
            removeOverlay()
        }
    }

    /** Global Off must not translate renderer teardown into a queued completion callback. */
    private fun cancelQueuedPlaybackForGlobalStop() {
        handler.removeCallbacks(removeOverlayTask)
        queuedCompletionLease.invalidate(queuedRequestToken)
        queuedRequestToken++
        cancelQueuedWatchdog()
        activeQueuedCompletion = null
        overlayView?.let { view ->
            view.setOnFiniteAnimationCompletedListener(null)
            view.setOnFiniteAnimationStartedListener(null)
            view.cancelAnimation()
        }
    }

    /** The only teardown exit for an already invalidated queued lease. */
    private fun completeQueuedTerminal(
        completion: (() -> Unit)?
    ) {
        completion?.invoke()
    }

    private fun readConfig(
        intent: Intent?
    ): HaloConfig {
        val defaults =
            HaloConfig()

        return HaloConfig(
            color =
                intent?.getIntExtra(
                    HaloOverlayService.EXTRA_COLOR,
                    defaults.color
                ) ?: defaults.color,

            intervalSeconds =
                intent?.getFloatExtra(
                    HaloOverlayService.EXTRA_INTERVAL,
                    defaults.intervalSeconds
                ) ?: defaults.intervalSeconds,


            intensity =
                intent?.getFloatExtra(
                    HaloOverlayService.EXTRA_INTENSITY,
                    defaults.intensity
                ) ?: defaults.intensity,

            thickness =
                intent?.getFloatExtra(
                    HaloOverlayService.EXTRA_THICKNESS,
                    defaults.thickness
                ) ?: defaults.thickness,

            edgeCalibrationDp = intent?.getFloatExtra(HaloOverlayService.EXTRA_EDGE_CALIBRATION_DP, defaults.edgeCalibrationDp) ?: defaults.edgeCalibrationDp,
            cornerCalibrationDp = intent?.getFloatExtra(HaloOverlayService.EXTRA_CORNER_CALIBRATION_DP, defaults.cornerCalibrationDp) ?: defaults.cornerCalibrationDp,
            cornerShape = intent?.getFloatExtra(HaloOverlayService.EXTRA_CORNER_SHAPE, defaults.cornerShape) ?: defaults.cornerShape,

            frame =
                intent?.getStringExtra(
                    HaloOverlayService.EXTRA_FRAME
                )
                    ?.let {
                        runCatching {
                            HaloFrame.valueOf(
                                it
                            )
                        }.getOrNull()
                    }
                    ?: defaults.frame,

            motion =
                intent?.getStringExtra(
                    HaloOverlayService.EXTRA_MOTION
                )
                    ?.let(HaloAnimationRegistry::resolveNormal)
                    ?: defaults.motion,

            effectSpeed =
                intent?.getFloatExtra(
                    HaloOverlayService.EXTRA_EFFECT_SPEED,
                    defaults.effectSpeed
                ) ?: defaults.effectSpeed,

            gradientFlowSpeed =
                intent?.getFloatExtra(
                    HaloOverlayService.EXTRA_GRADIENT_FLOW_SPEED,
                    defaults.gradientFlowSpeed
                ) ?: defaults.gradientFlowSpeed,

            colorMode =
                intent?.getStringExtra(
                    HaloOverlayService.EXTRA_COLOR_MODE
                )
                    ?.let {
                        runCatching {
                            HaloColorMode.valueOf(
                                it
                            )
                        }.getOrNull()
                    }
                    ?: defaults.colorMode,

        ).sanitized().copy(
            renderMode = when {
                intent?.getBooleanExtra(HaloOverlayService.EXTRA_AMBIENT, false) == true -> HaloRenderMode.AMBIENT
                intent?.getBooleanExtra(HaloOverlayService.EXTRA_LIGHT_IMPULSE, false) == true -> HaloRenderMode.LIGHT_IMPULSE
                else -> HaloRenderMode.NORMAL
            }
        ).let { config ->
            if (config.renderMode == HaloRenderMode.AMBIENT) {
                config.copy(motion = HaloAnimationRegistry.resolveAmbient(intent?.getStringExtra(HaloOverlayService.EXTRA_MOTION)))
            } else config
        }
    }

    private fun showQueuedEffect(
        request: HaloEffectRequest,
        onFiniteAnimationCompleted: () -> Unit
    ) {
        if (CalibrationPreviewSession.isCurrent(calibrationToken)) {
            pendingCalibrationIntent = lastCalibrationIntent
        }
        handler.removeCallbacks(
            removeOverlayTask
        )

        queuedCompletionLease.invalidate(
            queuedRequestToken
        )

        queuedRequestToken++

        /*
         * Every queued playback owns a unique token.
         *
         * A completion callback created by an older request is ignored once
         * another request takes ownership.
         */
        val requestToken =
            queuedCompletionLease.begin()

        queuedRequestToken =
            requestToken

        cancelQueuedWatchdog()

        /*
         * Detach the previous listener before cancelling. This guarantees that
         * cancelAnimation() cannot complete the next queued request through a
         * listener that has already been replaced.
         */
        overlayView
            ?.setOnFiniteAnimationCompletedListener(
                null
            )

        overlayView
            ?.cancelAnimation()

        activeQueuedCompletion =
            onFiniteAnimationCompleted

        var completed =
            false

        var watchdogTerminalClaimed =
            false

        val complete:
                    () -> Unit =
            complete@{

                if (completed) {
                    return@complete
                }

                if (
                    requestToken !=
                    queuedRequestToken
                ) {
                    Log.d(
                        TAG,
                        "Ignoring stale queued completion " +
                                "token=$requestToken, " +
                                "activeToken=$queuedRequestToken, " +
                                "key=${request.notificationKey}"
                    )

                    return@complete
                }

                if (!watchdogTerminalClaimed &&
                    !queuedCompletionLease.completeNaturally(requestToken)) {
                    return@complete
                }

                watchdogTerminalClaimed =
                    false

                completed =
                    true

                cancelQueuedWatchdog()

                val completion =
                    activeQueuedCompletion

                activeQueuedCompletion =
                    null

                /*
                 * Invalidate this request token before touching the View.
                 * Any callback produced while cancelling/removing the overlay
                 * is therefore stale by definition.
                 */
                queuedRequestToken++

                overlayView
                    ?.setOnFiniteAnimationCompletedListener(
                        null
                    )

                overlayView
                    ?.setOnFiniteAnimationStartedListener(
                        null
                    )

                overlayView
                    ?.cancelAnimation()

                overlayView
                    ?.let { view ->
                        runCatching {
                            if (
                                view.isAttachedToWindow
                            ) {
                                windowManager.removeView(
                                    view
                                )
                            }
                        }
                    }

                overlayView =
                    null

                layoutParams =
                    null

                activeConfig =
                    null

                previewMode =
                    false

                completion
                    ?.invoke()

                handler.post {
                    pendingCalibrationIntent?.takeIf { activeQueuedCompletion == null }?.let { pending ->
                        pendingCalibrationIntent = null
                        handleCommand(pending)
                    }
                }
            }

        val palette =
            request.paletteColors
                ?.distinct()
                ?.takeIf {
                    it.isNotEmpty()
                }
                ?.toIntArray()
                ?: if (
                    request.config.colorMode ==
                    HaloColorMode.GRADIENT
                ) {
                    HaloConfig
                        .defaultGradientPalette()
                } else {
                    intArrayOf(
                        request.config.color
                    )
                }

        val resolvedConfig =
            request.config.copy(
                color =
                    palette.first(),
                palette =
                    palette
            )

        overlayView
            ?.takeIf {
                it.isAttachedToWindow
            }
            ?.let { view ->

                activeConfig =
                    resolvedConfig

                previewMode =
                    false

                view.update(
                    resolvedConfig
                )

                view.setOnFiniteAnimationCompletedListener(
                    complete
                )

                view.setOnFiniteAnimationStartedListener {
                    queuedCompletionLease.onStarted(
                        requestToken
                    )

                    scheduleQueuedCompletionFallback(
                        requestToken,
                        resolvedConfig,
                        request,
                        complete,
                        onWatchdogClaimed = {
                            watchdogTerminalClaimed = true
                        }
                    )
                }

                startAnimation(
                    view,
                    resolvedConfig
                )

                return
            }

        /*
         * Any detached View is stale. It must not own callbacks for the new
         * queued request.
         */
        overlayView
            ?.let { staleView ->

                staleView.setOnFiniteAnimationCompletedListener(
                    null
                )

                staleView.cancelAnimation()
            }

        overlayView =
            null

        layoutParams =
            null

        activeConfig =
            null

        previewMode =
            false

        if (
            resolvedConfig.intensity <=
            0f
        ) {
            complete()

            return
        }

        val display =
            displayManager.getDisplay(
                Display.DEFAULT_DISPLAY
            )

        if (
            display ==
            null
        ) {
            complete()

            return
        }

        val metrics =
            OverlayDisplayMetrics.realMetrics(
                display
            )

        val windowContext =
            createDisplayContext(
                display
            )
                .createWindowContext(
                    WindowManager.LayoutParams
                        .TYPE_ACCESSIBILITY_OVERLAY,
                    null
                )

        windowManager =
            windowContext.getSystemService(
                WindowManager::class.java
            )

        val view =
            HaloView(
                windowContext,
                resolvedConfig
            ).apply {
                setOnFiniteAnimationCompletedListener(
                    complete
                )

                setOnGpuTerminalFailureListener {
                    terminateGpuOverlay(this@apply)
                }

                setOnFiniteAnimationStartedListener {
                    queuedCompletionLease.onStarted(
                        requestToken
                    )

                    scheduleQueuedCompletionFallback(
                        requestToken,
                        resolvedConfig,
                        request,
                        complete,
                        onWatchdogClaimed = {
                            watchdogTerminalClaimed = true
                        }
                    )
                }
            }

        val params =
            WindowManager.LayoutParams(
                metrics.widthPixels,
                metrics.heightPixels,
                WindowManager.LayoutParams
                    .TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams
                    .FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams
                            .FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams
                            .FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams
                            .FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity =
                    Gravity.TOP or
                            Gravity.START

                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams
                        .LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

                setFitInsetsTypes(
                    0
                )

                setFitInsetsSides(
                    0
                )

                setFitInsetsIgnoringVisibility(
                    true
                )
            }

        overlayView =
            view

        layoutParams =
            params

        activeConfig =
            resolvedConfig

        previewMode =
            false

        runCatching {
            windowManager.addView(
                view,
                params
            )

            startAnimation(
                view,
                resolvedConfig
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
        requestToken: Long,
        config: HaloConfig,
        request: HaloEffectRequest,
        onCompleted: () -> Unit,
        onWatchdogClaimed: () -> Unit
    ) {
        if (config.renderMode == HaloRenderMode.AMBIENT) {
            return
        }

        cancelQueuedWatchdog()

        val duration =
            max(
                HaloAnimation.MIN_DURATION_MS,
                (
                        finiteDurationFor(
                            config.renderMode,
                            config.durationSeconds
                        ) *
                                1000f
                        ).toLong()
            )

        val interval =
            (
                    config.intervalSeconds *
                            1000f
                    )
                .toLong()
                .coerceAtLeast(
                    0L
                )

        val totalDuration = duration

        /*
         * This watchdog is recovery-only.
         *
         * It has a generous grace window and is bound to the same request
         * token as the natural animation completion.
         */
        val watchdogDelay =
            totalDuration +
                    QUEUED_WATCHDOG_GRACE_MS

        lateinit var watchdog:
                Runnable

        watchdog =
            Runnable {

                if (
                    queuedCompletionWatchdog !==
                    watchdog
                ) {
                    return@Runnable
                }

                if (
                    requestToken !=
                    queuedRequestToken
                ) {
                    Log.d(
                        TAG,
                        "Ignoring stale queued watchdog " +
                                "token=$requestToken, " +
                                "activeToken=$queuedRequestToken, " +
                                "key=${request.notificationKey}"
                    )

                    return@Runnable
                }

                queuedCompletionWatchdog =
                    null

                if (
                    !queuedCompletionLease.completeFromWatchdog(
                        requestToken
                    )
                ) {
                    return@Runnable
                }

                onWatchdogClaimed()

                onCompleted()
            }

        queuedCompletionWatchdog =
            watchdog

        handler.postDelayed(
            watchdog,
            watchdogDelay
        )
    }

    private fun cancelQueuedWatchdog() {
        queuedCompletionWatchdog
            ?.let(
                handler::removeCallbacks
            )

        queuedCompletionWatchdog =
            null
    }

    companion object {

        @Volatile
        private var activeService:
                HaloAccessibilityService? = null

        fun dispatch(
            intent: Intent?
        ): Boolean {
            if (requiresCoordinatorOwnedReminderDelivery(intent)) {
                Log.d(
                    TAG,
                    "Reminder command requires coordinator-owned delivery; falling back to overlay service"
                )
                return false
            }

            val service =
                activeService
                    ?: run {
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

            if (
                Looper.myLooper() ==
                Looper.getMainLooper()
            ) {
                service.handleCommand(
                    intent
                )
            } else {
                service.handler.post {
                    if (
                        activeService ===
                        service
                    ) {
                        service.handleCommand(
                            intent
                        )
                    }
                }
            }

            return true
        }

        internal fun isAvailable(): Boolean = activeService != null

        /**
         * Generic accessibility commands have no terminal callback. A reminder
         * must instead enter HaloEffectCoordinator, whose queued delivery owns
         * the full multi-colour lease and reports terminal completion.
         */
        internal fun requiresCoordinatorOwnedReminderDelivery(intent: Intent?): Boolean =
            requiresCoordinatorOwnedReminderDelivery(
                lightImpulse = intent?.getBooleanExtra(HaloOverlayService.EXTRA_LIGHT_IMPULSE, false) == true,
                reminderColors = intent?.getIntArrayExtra(HaloOverlayService.EXTRA_REMINDER_COLORS)
            )

        internal fun requiresCoordinatorOwnedReminderDelivery(
            lightImpulse: Boolean,
            reminderColors: IntArray?
        ): Boolean = lightImpulse && reminderColors?.isNotEmpty() == true

        internal fun dispatchQueuedEffect(
            request: HaloEffectRequest,
            onFiniteAnimationCompleted: () -> Unit
        ): Boolean {
            val service =
                activeService
                    ?: return false

            val command = {
                if (
                    activeService ===
                    service
                ) {
                    service.showQueuedEffect(
                        request =
                            request,
                        onFiniteAnimationCompleted =
                            onFiniteAnimationCompleted
                    )
                } else {
                    onFiniteAnimationCompleted()
                }
            }

            if (
                Looper.myLooper() ==
                Looper.getMainLooper()
            ) {
                command()
            } else {
                service.handler.post(
                    command
                )
            }

            return true
        }

        private const val TAG =
            "HaloAccessibility"

        private const val QUEUED_WATCHDOG_GRACE_MS =
            2_000L

        /*
         * Recovery timeout only.
         *
         * Natural HaloAnimation completion is the normal path. A larger grace
         * period keeps transient frame/main-thread delays from being mistaken
         * for a dead animation.
         */
    }
}
