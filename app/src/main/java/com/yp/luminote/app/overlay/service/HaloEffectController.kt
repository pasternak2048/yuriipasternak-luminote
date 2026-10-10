package com.yp.luminote.app.overlay.service
import com.yp.luminote.app.overlay.coordination.CalibrationOperation
import com.yp.luminote.app.overlay.coordination.CalibrationPreviewSession
import com.yp.luminote.app.overlay.coordination.HaloEffectCommand
import com.yp.luminote.app.overlay.coordination.HaloEffectHost
import com.yp.luminote.app.overlay.coordination.HaloEffectHostCoordinator
import com.yp.luminote.app.overlay.coordination.HaloEffectHostPort

import android.content.Context
import com.yp.luminote.app.data.settings.LuminoteSettings

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
