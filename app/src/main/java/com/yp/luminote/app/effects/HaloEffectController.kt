package com.yp.luminote.app.effects

import android.content.Context
import com.yp.luminote.app.data.settings.LuminoteSettings

/**
 * App-facing commands for the finite and persistent effects initiated by the UI.
 *
 * These are deliberately semantic: callers never select a service, create an
 * Intent, or know which kind of overlay is currently available.
 */
sealed interface HaloEffectCommand {
    data class Preview(val settings: LuminoteSettings) : HaloEffectCommand
    data object StopPreview : HaloEffectCommand
    data class Calibration(
        val settings: LuminoteSettings?,
        val token: String,
        val generation: Long,
        val operation: CalibrationOperation
    ) : HaloEffectCommand
    data class Ambient(val settings: LuminoteSettings) : HaloEffectCommand
    data object StopAmbient : HaloEffectCommand
    data object GlobalOff : HaloEffectCommand
}

enum class CalibrationOperation { START, UPDATE, PAUSE, STOP }

internal enum class HaloEffectHost { ACCESSIBILITY, OVERLAY }

/** The narrow host port lets the ownership rules be tested without Android services. */
internal interface HaloEffectHostPort {
    fun isAvailable(host: HaloEffectHost): Boolean
    fun dispatch(host: HaloEffectHost, command: HaloEffectCommand): Boolean
}

/**
 * Resolves one host for each app-owned effect session and retains that choice
 * for updates and stops. This avoids directing a stop at whichever host happens
 * to be available later, which can leave the original renderer alive.
 */
internal class HaloEffectHostCoordinator(
    private val port: HaloEffectHostPort
) {
    private var previewOwner: HaloEffectHost? = null
    private var ambientOwner: HaloEffectHost? = null

    @Synchronized
    fun dispatch(command: HaloEffectCommand): Boolean = when (command) {
        is HaloEffectCommand.Preview -> startPreview(command)
        HaloEffectCommand.StopPreview -> stopPreview()
        is HaloEffectCommand.Calibration -> {
            // Calibration is intentionally never routed to accessibility.
            port.dispatch(HaloEffectHost.OVERLAY, command)
        }
        is HaloEffectCommand.Ambient -> startAmbient(command)
        HaloEffectCommand.StopAmbient -> stopAmbient()
        HaloEffectCommand.GlobalOff -> globalOff()
    }

    /** Called by the accessibility host after it atomically adopts overlay Ambient. */
    @Synchronized
    fun onAccessibilityAmbientTakeover() {
        ambientOwner = HaloEffectHost.ACCESSIBILITY
    }

    @Synchronized
    fun onAmbientStarted(host: HaloEffectHost) {
        ambientOwner = host
    }

    private fun startPreview(command: HaloEffectCommand.Preview): Boolean {
        val target = selectHost() ?: return false
        handoff(previewOwner, target, HaloEffectCommand.StopPreview)
        return dispatchWithFallback(target, command).let { delivered ->
            if (delivered != null) previewOwner = delivered
            delivered != null
        }
    }

    private fun stopPreview(): Boolean = previewOwner?.let { owner ->
        port.dispatch(owner, HaloEffectCommand.StopPreview).also { previewOwner = null }
    } ?: false

    private fun startAmbient(command: HaloEffectCommand.Ambient): Boolean {
        val target = selectHost() ?: return false
        handoff(ambientOwner, target, HaloEffectCommand.StopAmbient)
        return dispatchWithFallback(target, command).let { delivered ->
            if (delivered != null) ambientOwner = delivered
            delivered != null
        }
    }

    private fun stopAmbient(): Boolean {
        val owner = ambientOwner
        ambientOwner = null
        return if (owner != null) {
            port.dispatch(owner, HaloEffectCommand.StopAmbient)
        } else {
            // Ambient may have been started by the notification/settings source
            // before this controller was instantiated. These commands cannot
            // terminate transient notification or reminder playback.
            val accessibility = port.dispatch(HaloEffectHost.ACCESSIBILITY, HaloEffectCommand.StopAmbient)
            val overlay = port.dispatch(HaloEffectHost.OVERLAY, HaloEffectCommand.StopAmbient)
            accessibility || overlay
        }
    }

    private fun globalOff(): Boolean {
        // Global Off retains its broader legacy scope: both independent hosts
        // receive the terminal command, while ordinary preview/ambient stops do not.
        val accessibility = port.dispatch(HaloEffectHost.ACCESSIBILITY, HaloEffectCommand.GlobalOff)
        val overlay = port.dispatch(HaloEffectHost.OVERLAY, HaloEffectCommand.GlobalOff)
        previewOwner = null
        ambientOwner = null
        return accessibility || overlay
    }

    private fun selectHost(): HaloEffectHost? =
        if (port.isAvailable(HaloEffectHost.ACCESSIBILITY)) {
            HaloEffectHost.ACCESSIBILITY
        } else {
            HaloEffectHost.OVERLAY
        }

    private fun handoff(
        previous: HaloEffectHost?,
        target: HaloEffectHost,
        stop: HaloEffectCommand
    ) {
        if (previous != null && previous != target) port.dispatch(previous, stop)
    }

    private fun dispatchWithFallback(
        selected: HaloEffectHost,
        command: HaloEffectCommand
    ): HaloEffectHost? {
        if (port.dispatch(selected, command)) return selected
        if (selected == HaloEffectHost.ACCESSIBILITY && port.dispatch(HaloEffectHost.OVERLAY, command)) {
            return HaloEffectHost.OVERLAY
        }
        return null
    }

}

/** Stable application boundary consumed by Compose screens. */
object HaloEffectController {
    fun preview(context: Context, settings: LuminoteSettings): Boolean =
        coordinator(context).dispatch(HaloEffectCommand.Preview(settings))

    fun stopPreview(context: Context): Boolean =
        coordinator(context).dispatch(HaloEffectCommand.StopPreview)

    fun startCalibration(context: Context, settings: LuminoteSettings, token: String): Long {
        val generation = CalibrationPreviewSession.activate(token)
        coordinator(context).dispatch(
            HaloEffectCommand.Calibration(settings, token, generation, CalibrationOperation.START)
        )
        return generation
    }

    fun updateCalibration(context: Context, settings: LuminoteSettings, token: String, generation: Long): Boolean =
        coordinator(context).dispatch(
            HaloEffectCommand.Calibration(settings, token, generation, CalibrationOperation.UPDATE)
        )

    fun pauseCalibration(context: Context, token: String, generation: Long): Boolean =
        coordinator(context).dispatch(
            HaloEffectCommand.Calibration(null, token, generation, CalibrationOperation.PAUSE)
        )

    fun stopCalibration(context: Context, token: String, generation: Long): Boolean =
        coordinator(context).dispatch(
            HaloEffectCommand.Calibration(null, token, generation, CalibrationOperation.STOP)
        )

    fun ambient(context: Context, settings: LuminoteSettings): Boolean =
        coordinator(context).dispatch(HaloEffectCommand.Ambient(settings))

    fun stopAmbient(context: Context): Boolean {
        HaloOverlayService.invalidatePendingApplicationAmbient()
        return coordinator(context).dispatch(HaloEffectCommand.StopAmbient)
    }

    fun globalOff(context: Context): Boolean {
        HaloOverlayService.invalidatePendingApplicationAmbient()
        return coordinator(context).dispatch(HaloEffectCommand.GlobalOff)
    }

    internal fun onAccessibilityAmbientTakeover(context: Context) {
        coordinator(context).onAccessibilityAmbientTakeover()
    }

    internal fun onAmbientStarted(context: Context, host: HaloEffectHost) {
        coordinator(context).onAmbientStarted(host)
    }

    private fun coordinator(context: Context): HaloEffectHostCoordinator =
        synchronized(lock) {
            coordinator ?: HaloEffectHostCoordinator(AndroidHostPort(context.applicationContext)).also {
                coordinator = it
            }
        }

    private class AndroidHostPort(private val context: Context) : HaloEffectHostPort {
        override fun isAvailable(host: HaloEffectHost): Boolean =
            host == HaloEffectHost.OVERLAY || HaloAccessibilityService.isAvailable()

        override fun dispatch(host: HaloEffectHost, command: HaloEffectCommand): Boolean {
            val intent = command.toIntent(context)
            return when (host) {
                HaloEffectHost.ACCESSIBILITY -> HaloAccessibilityService.dispatch(intent)
                HaloEffectHost.OVERLAY -> HaloOverlayService.startOverlay(context, intent)
            }
        }
    }

    private fun HaloEffectCommand.toIntent(context: Context) = when (this) {
        is HaloEffectCommand.Preview -> HaloOverlayService.createPreviewIntent(context, settings)
        HaloEffectCommand.StopPreview -> HaloOverlayService.createStopPreviewIntent(context)
        is HaloEffectCommand.Calibration -> when (operation) {
            CalibrationOperation.START,
            CalibrationOperation.UPDATE -> HaloOverlayService.createCalibrationIntent(
                context, requireNotNull(settings), token, generation, start = operation == CalibrationOperation.START
            )
            CalibrationOperation.PAUSE -> HaloOverlayService.createPauseCalibrationIntent(context, token, generation)
            CalibrationOperation.STOP -> HaloOverlayService.createStopCalibrationIntent(context, token, generation)
        }
        is HaloEffectCommand.Ambient -> HaloOverlayService.createAmbientIntent(context, settings)
        HaloEffectCommand.StopAmbient -> HaloOverlayService.createStopAmbientIntent(context)
        HaloEffectCommand.GlobalOff -> HaloOverlayService.createStopAllIntent(context)
    }

    private val lock = Any()
    @Volatile private var coordinator: HaloEffectHostCoordinator? = null
}
