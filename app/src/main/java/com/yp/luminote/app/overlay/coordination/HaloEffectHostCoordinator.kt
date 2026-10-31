package com.yp.luminote.app.overlay.coordination

import com.yp.luminote.app.data.settings.LuminoteSettings
import com.yp.luminote.app.data.settings.HaloMode

/** Semantic commands issued by the app; Android hosts translate them at the boundary. */
sealed interface HaloEffectCommand {
    data class Preview(val settings: LuminoteSettings) : HaloEffectCommand
    data object StopPreview : HaloEffectCommand
    data class Calibration(val settings: LuminoteSettings?, val token: String, val generation: Long, val operation: CalibrationOperation) : HaloEffectCommand
    data class Ambient(val settings: LuminoteSettings) : HaloEffectCommand
    data object StopAmbient : HaloEffectCommand
    data object GlobalOff : HaloEffectCommand
}

enum class CalibrationOperation { START, UPDATE, PAUSE, STOP }
internal enum class HaloEffectHost { ACCESSIBILITY, OVERLAY }
internal data class ResonancePresentationLease(val generation: Long, val sequence: Long, val host: HaloEffectHost)

/** Host-neutral dispatch boundary. Implementations own Android availability and delivery. */
internal interface HaloEffectHostPort {
    fun isAvailable(host: HaloEffectHost): Boolean
    fun dispatch(host: HaloEffectHost, command: HaloEffectCommand): Boolean
}

/**
 * Retains the selected host for a semantic effect session. Its state is deliberately
 * Android-free: replacement selects a host before delivery, and terminal commands
 * are idempotent after their owner is cleared.
 */
internal class HaloEffectHostCoordinator(private val port: HaloEffectHostPort) {
    private var selectedMode: HaloMode? = null
    @Synchronized fun selectMode(mode: HaloMode) { selectedMode = mode }
    @Synchronized fun permitsAmbient() = selectedMode == null || selectedMode == HaloMode.AMBIENT
    @Synchronized fun permitsOrdinaryTools() = selectedMode != HaloMode.RESONANCE
    private var resonanceGeneration = 0L
    private var presentationSequence = 0L
    private var resonancePresentation: ResonancePresentationLease? = null

    @Synchronized fun acquireResonance(generation: Long): Boolean {
        if (generation == 0L || (resonanceGeneration != 0L && resonanceGeneration != generation)) return false
        resonanceGeneration = generation
        return true
    }
    @Synchronized fun hasResonance(): Boolean = resonanceGeneration != 0L
    @Synchronized fun newResonancePresentation(generation: Long, host: HaloEffectHost): ResonancePresentationLease? {
        if (resonanceGeneration != generation || generation == 0L) return null
        return ResonancePresentationLease(generation, ++presentationSequence, host).also { resonancePresentation = it }
    }
    @Synchronized fun ownsResonancePresentation(lease: ResonancePresentationLease): Boolean = resonancePresentation == lease
    @Synchronized fun invalidateResonancePresentation(lease: ResonancePresentationLease) {
        if (resonancePresentation == lease) resonancePresentation = null
    }
    @Synchronized fun releaseResonance(generation: Long): Boolean {
        if (generation == 0L || resonanceGeneration != generation) return false
        resonancePresentation = null
        resonanceGeneration = 0L
        return true
    }
    private var previewOwner: HaloEffectHost? = null
    private var ambientOwner: HaloEffectHost? = null

    @Synchronized
    fun dispatch(command: HaloEffectCommand): Boolean {
        if (command is HaloEffectCommand.Ambient && !permitsAmbient()) return false
        if ((hasResonance() || !permitsOrdinaryTools()) && (command is HaloEffectCommand.Preview || command is HaloEffectCommand.Ambient ||
                command is HaloEffectCommand.Calibration && command.operation in listOf(CalibrationOperation.START, CalibrationOperation.UPDATE))) return false
        return when (command) {
        is HaloEffectCommand.Preview -> startPreview(command)
        HaloEffectCommand.StopPreview -> stopPreview()
        is HaloEffectCommand.Calibration -> port.dispatch(HaloEffectHost.OVERLAY, command)
        is HaloEffectCommand.Ambient -> startAmbient(command)
        HaloEffectCommand.StopAmbient -> stopAmbient()
        HaloEffectCommand.GlobalOff -> globalOff()
        }
    }

    @Synchronized fun onAccessibilityAmbientTakeover() { ambientOwner = HaloEffectHost.ACCESSIBILITY }
    @Synchronized fun onAmbientStarted(host: HaloEffectHost) { ambientOwner = host }

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
        return if (owner != null) port.dispatch(owner, HaloEffectCommand.StopAmbient) else {
            // Both independent hosts must receive ownerless cleanup, even if the first succeeds.
            val accessibility = port.dispatch(HaloEffectHost.ACCESSIBILITY, HaloEffectCommand.StopAmbient)
            val overlay = port.dispatch(HaloEffectHost.OVERLAY, HaloEffectCommand.StopAmbient)
            accessibility || overlay
        }
    }

    private fun globalOff(): Boolean {
        val accessibility = port.dispatch(HaloEffectHost.ACCESSIBILITY, HaloEffectCommand.GlobalOff)
        val overlay = port.dispatch(HaloEffectHost.OVERLAY, HaloEffectCommand.GlobalOff)
        previewOwner = null
        ambientOwner = null
        return accessibility || overlay
    }

    private fun selectHost(): HaloEffectHost? = if (port.isAvailable(HaloEffectHost.ACCESSIBILITY)) HaloEffectHost.ACCESSIBILITY else HaloEffectHost.OVERLAY
    private fun handoff(previous: HaloEffectHost?, target: HaloEffectHost, stop: HaloEffectCommand) { if (previous != null && previous != target) port.dispatch(previous, stop) }
    private fun dispatchWithFallback(selected: HaloEffectHost, command: HaloEffectCommand): HaloEffectHost? {
        if (port.dispatch(selected, command)) return selected
        return HaloEffectHost.OVERLAY.takeIf { selected == HaloEffectHost.ACCESSIBILITY && port.dispatch(it, command) }
    }
}
