package com.yp.luminote.app.overlay.service
import com.yp.luminote.app.overlay.coordination.CalibrationOperation
import com.yp.luminote.app.overlay.coordination.CalibrationPreviewSession
import com.yp.luminote.app.overlay.coordination.HaloEffectCommand
import com.yp.luminote.app.overlay.coordination.HaloEffectHost
import com.yp.luminote.app.overlay.coordination.HaloEffectHostCoordinator
import com.yp.luminote.app.overlay.coordination.HaloEffectHostPort

import android.content.Context
import com.yp.luminote.app.data.settings.LuminoteSettings
import com.yp.luminote.app.resonance.ResonanceRuntime
import com.yp.luminote.app.resonance.ResonanceController
import com.yp.luminote.app.data.settings.LuminoteSettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

internal fun shouldRestoreAmbientAfterResonance(ambientEnabled: Boolean, sessionActive: Boolean,
    expectedRevision: Long, actualRevision: Long, generation: Long, suppressedGeneration: Long): Boolean =
    ambientEnabled && !sessionActive && expectedRevision == actualRevision && generation != suppressedGeneration

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

    private var ambientRevision = 0L
    private var suppressRestoreGeneration = 0L
    private val restorationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun ambient(context: Context, settings: LuminoteSettings): Boolean {
        ambientRevision++
        suppressRestoreGeneration = 0L
        if (ResonanceRuntime.activeGeneration != 0L) return true
        return coordinator(context).dispatch(HaloEffectCommand.Ambient(settings))
    }

    internal fun suspendAmbientForResonance(context: Context) {
        // The application host is reused by Resonance; only the second window needs suppression.
        HaloAccessibilityService.dispatch(HaloOverlayService.createStopAmbientIntent(context))
    }

    internal fun restoreAmbientAfterResonance(context: Context, generation: Long) {
        if (suppressRestoreGeneration == generation) return
        val revision = ambientRevision
        val appContext = context.applicationContext
        restorationScope.launch {
            val settings = runCatching { LuminoteSettingsRepository(appContext).settings.first() }.getOrNull() ?: return@launch
            if (shouldRestoreAmbientAfterResonance(settings.ambientEnabled, ResonanceRuntime.activeGeneration != 0L,
                    revision, ambientRevision, generation, suppressRestoreGeneration)) ambient(appContext, settings)
        }
    }

    fun stopAmbient(context: Context): Boolean {
        ambientRevision++
        suppressRestoreGeneration = ResonanceRuntime.generationForTermination()
        HaloOverlayService.invalidatePendingApplicationAmbient()
        return coordinator(context).dispatch(HaloEffectCommand.StopAmbient)
    }

    fun globalOff(context: Context): Boolean {
        ambientRevision++
        suppressRestoreGeneration = ResonanceRuntime.generationForTermination()
        ResonanceController.stop(context)
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
