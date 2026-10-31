package com.yp.luminote.app.overlay.service
import com.yp.luminote.app.overlay.coordination.CalibrationOperation
import com.yp.luminote.app.overlay.coordination.ResonanceModeActivation
import com.yp.luminote.app.overlay.coordination.SelectedModeSourceAuthority
import com.yp.luminote.app.overlay.coordination.ResonancePresentationLease
import com.yp.luminote.app.overlay.coordination.CalibrationPreviewSession
import com.yp.luminote.app.overlay.coordination.HaloEffectCommand
import com.yp.luminote.app.overlay.coordination.HaloEffectHost
import com.yp.luminote.app.overlay.coordination.HaloEffectHostCoordinator
import com.yp.luminote.app.overlay.coordination.HaloEffectHostPort

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import com.yp.luminote.app.data.settings.HaloMode
import com.yp.luminote.app.effects.model.HaloRenderMode
import com.yp.luminote.app.viewmodel.SettingsPublication
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import com.yp.luminote.app.data.settings.LuminoteSettings
import com.yp.luminote.app.resonance.ResonanceRuntime
import com.yp.luminote.app.resonance.ResonanceController
import com.yp.luminote.app.data.settings.LuminoteSettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

internal fun shouldRestoreAmbientAfterResonance(ambientEnabled: Boolean, sessionActive: Boolean,
    expectedRevision: Long, actualRevision: Long, generation: Long, suppressedGeneration: Long): Boolean =
    ambientEnabled && !sessionActive && expectedRevision == actualRevision && generation != suppressedGeneration

data class ResonanceAccessState(val audioGranted: Boolean = false, val overlayGranted: Boolean = false) {
    val ready: Boolean get() = audioGranted && overlayGranted
}

/** Stable application boundary consumed by Compose screens. */
object HaloEffectController {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var selectedSettings: LuminoteSettings? = null
    private var application: Context? = null
    private var persistedJob: Job? = null
    private var publisherJob: Job? = null
    private var publisherFlow: StateFlow<SettingsPublication>? = null
    private val sourceAuthority = SelectedModeSourceAuthority()
    private val activation = ResonanceModeActivation()
    private var foregroundSettingsReady = false
    private var foregroundLease = 0L
    private var nextForegroundLease = 0L
    private var lastPublication: SettingsPublication? = null
    private var modeEpoch = 0L
    private val commandSession = java.util.UUID.randomUUID().toString()
    private const val MODE_SESSION = "luminote_mode_session"
    private const val MODE_EPOCH = "luminote_mode_epoch"
    internal fun isCurrentModeCommand(intent: android.content.Intent?): Boolean =
        intent?.hasExtra(MODE_SESSION) != true ||
            (intent.getStringExtra(MODE_SESSION) == commandSession && intent.getLongExtra(MODE_EPOCH, -1L) == modeEpoch)
    private var accessEpoch = 0L
    private var appliedOrdinaryEpoch = -1L
    private var suspendedOrdinaryEpoch = -1L
    private var appliedAmbient: LuminoteSettings? = null
    private val mutableAccess = MutableStateFlow(ResonanceAccessState())
    val resonanceAccessState: StateFlow<ResonanceAccessState> = mutableAccess.asStateFlow()

    fun initialize(context: Context) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (application != null) return
        application = context.applicationContext
        coordinator(context).selectMode(HaloMode.OFF)
        HaloOverlayService.selectMode(HaloMode.OFF)
        restorationScope.launch { ResonanceController.state.collect {
            HaloOverlayService.updateCaptureCleanup(ResonanceRuntime.cleanupPending)
            reconcile()
        } }
        observePersisted()
    }

    private fun observePersisted() {
        val context = application ?: return
        persistedJob?.cancel()
        val epoch = sourceAuthority.newPersistedFeed()
        persistedJob = restorationScope.launch {
            LuminoteSettingsRepository(context).settings.collect { settings ->
                ensureActive()
                if (sourceAuthority.acceptPersisted(epoch)) publishSettings(settings)
            }
        }
    }

    /** Adopt before setContent, so replacement UI and runtime never disagree on selection. */
    fun prepareSelectedModeViewModel(model: LuminoteSettingsViewModel) {
        check(Looper.myLooper() == Looper.getMainLooper())
        model.adoptPendingSettings(lastPublication)
    }

    /** Foreground eligibility is separate from the existing VM/DataStore selected mode. */
    fun bindSelectedMode(context: Context, model: LuminoteSettingsViewModel): Long {
        check(Looper.myLooper() == Looper.getMainLooper())
        initialize(context)
        prepareSelectedModeViewModel(model)
        val publications = model.publication
        persistedJob?.cancel(); persistedJob = null
        publisherJob?.cancel()
        val lease = ++nextForegroundLease
        foregroundLease = 0L
        foregroundSettingsReady = false
        sourceAuthority.claimPublisher(lease)
        publisherFlow = publications
        refreshSelectedModeAccess(context)
        foregroundLease = lease
        publisherJob = restorationScope.launch {
            publications.collect { publication ->
                if (!sourceAuthority.acceptPublisher(lease)) return@collect
                // Only an accepted owner may replace the retained pending publication.
                lastPublication = publication
                if (publication.loaded) {
                    foregroundSettingsReady = foregroundLease == lease
                    publishSettings(publication.settings)
                }
                if (foregroundLease != lease && publication.committed) releasePublisher(lease)
            }
        }
        return lease
    }

    fun releaseSelectedModeForeground(lease: Long) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (foregroundLease != lease) return
        // Revoke permission to start before asynchronous cleanup can emit another state.
        foregroundLease = 0L
        foregroundSettingsReady = false
        if (lastPublication?.committed == true) releasePublisher(lease)
    }

    private fun releasePublisher(lease: Long) {
        if (!sourceAuthority.releasePublisher(lease, lastPublication?.committed == true)) return
        publisherJob?.cancel(); publisherJob = null
        publisherFlow = null
        lastPublication = null
        // Restart after exact successful acknowledgment; discard queued old persisted emissions.
        observePersisted()
    }

    private fun publishSettings(settings: LuminoteSettings) {
        val old = selectedSettings
        selectedSettings = settings
        if (old?.haloMode != settings.haloMode) { modeEpoch++; appliedAmbient = null }
        coordinator(application!!).selectMode(settings.haloMode)
        HaloOverlayService.selectMode(settings.haloMode)
        reconcile()
    }

    fun refreshSelectedModeAccess(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { refreshSelectedModeAccess(context.applicationContext) }; return
        }
        val access = ResonanceAccessState(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            Settings.canDrawOverlays(context))
        if (mutableAccess.value != access) { mutableAccess.value = access; accessEpoch++ }
        reconcile()
    }

    private fun reconcile() {
        val context = application ?: return
        val settings = selectedSettings ?: return
        if (settings.haloMode == HaloMode.RESONANCE) {
            if (suspendedOrdinaryEpoch != modeEpoch) { suspendedOrdinaryEpoch = modeEpoch; stopAmbient(context) }
            if (!mutableAccess.value.ready) { ResonanceController.stop(context); return }
            if (!activation.takeAttempt(modeEpoch, foregroundLease, accessEpoch, foregroundSettingsReady,
                    mutableAccess.value.ready, ResonanceRuntime.canStart())) return
            ResonanceController.start(context)
            return
        }
        if (ResonanceRuntime.activeGeneration != 0L) ResonanceController.stop(context)
        if (ResonanceRuntime.cleanupPending) return
        when (settings.haloMode) {
            HaloMode.AMBIENT -> if (appliedAmbient != settings &&
                (foregroundLease != 0L || HaloAccessibilityService.isAvailable())) {
                appliedAmbient = settings
                ambient(context, settings)
            }
            HaloMode.NOTIFICATIONS -> if (appliedOrdinaryEpoch != modeEpoch) { appliedOrdinaryEpoch = modeEpoch; stopAmbient(context) }
            HaloMode.OFF -> if (appliedOrdinaryEpoch != modeEpoch) { appliedOrdinaryEpoch = modeEpoch; globalOff(context) }
            HaloMode.RESONANCE -> Unit
        }
    }

    internal fun currentModeSettings(fallback: LuminoteSettings?): LuminoteSettings? = selectedSettings ?: fallback

    internal fun permitsPresentation(mode: HaloRenderMode, preview: Boolean = false): Boolean {
        val desired = selectedSettings?.haloMode ?: return true
        if (desired == HaloMode.RESONANCE || ResonanceRuntime.activeGeneration != 0L || ResonanceRuntime.cleanupPending) return false
        return if (preview) true else when (mode) {
            HaloRenderMode.AMBIENT -> desired == HaloMode.AMBIENT
            else -> desired == HaloMode.NOTIFICATIONS
        }
    }
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
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { ambient(context.applicationContext, settings) }; return true
        }
        val current = selectedSettings
        if (current != null && current.haloMode != HaloMode.AMBIENT) return false
        ambientRevision++
        suppressRestoreGeneration = 0L
        if (ResonanceRuntime.activeGeneration != 0L) return true
        return coordinator(context).dispatch(HaloEffectCommand.Ambient(current ?: settings))
    }

    internal fun acquireResonance(context: Context, generation: Long): Boolean = coordinator(context).acquireResonance(generation)
    internal fun hasResonance(context: Context): Boolean = coordinator(context).hasResonance()
    internal fun newResonancePresentation(context: Context, generation: Long, host: HaloEffectHost) =
        coordinator(context).newResonancePresentation(generation, host)
    internal fun ownsResonancePresentation(context: Context, lease: ResonancePresentationLease) =
        coordinator(context).ownsResonancePresentation(lease)
    internal fun invalidateResonancePresentation(context: Context, lease: ResonancePresentationLease) =
        coordinator(context).invalidateResonancePresentation(lease)
    internal fun releaseResonance(context: Context, generation: Long) = coordinator(context).releaseResonance(generation)

    internal fun restoreAmbientAfterResonance(context: Context, generation: Long) {
        if (suppressRestoreGeneration == generation) return
        val revision = ambientRevision
        val appContext = context.applicationContext
        restorationScope.launch {
            val settings = runCatching { LuminoteSettingsRepository(appContext).settings.first() }.getOrNull() ?: return@launch
            if (shouldRestoreAmbientAfterResonance(settings.ambientEnabled, ResonanceRuntime.activeGeneration != 0L,
                    revision, ambientRevision, generation, suppressRestoreGeneration) &&
                (selectedSettings == null || selectedSettings?.haloMode == HaloMode.AMBIENT)) ambient(appContext, settings)
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
        HaloOverlayService.cancelTransientPlaybackForGlobalStop()
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
            val intent = command.toIntent(context).putExtra(MODE_SESSION, commandSession).putExtra(MODE_EPOCH, modeEpoch)
            return when (host) {
                HaloEffectHost.ACCESSIBILITY -> HaloAccessibilityService.dispatch(intent)
                HaloEffectHost.OVERLAY -> if (command == HaloEffectCommand.StopAmbient ||
                    command == HaloEffectCommand.StopPreview || command == HaloEffectCommand.GlobalOff ||
                    command is HaloEffectCommand.Calibration && command.operation in listOf(CalibrationOperation.STOP, CalibrationOperation.PAUSE)) {
                    HaloOverlayService.dispatchToRunningHost(intent)
                } else HaloOverlayService.startOverlay(context, intent)
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
