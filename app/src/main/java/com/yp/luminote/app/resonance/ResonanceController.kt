package com.yp.luminote.app.resonance

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ResonanceStatus { STOPPED, STARTING, WAITING_FOR_AUDIO, RUNNING, UNSUPPORTED, ERROR }
data class ResonanceState(
    val status: ResonanceStatus = ResonanceStatus.STOPPED,
    val intensity: Float = .65f,
    val detail: String? = null,
    val gpuFrames: Long = 0L,
    val analysisFrames: Long = 0L,
    val cleanupPending: Boolean = false,
    val providerInitialized: Boolean = false,
    val nonzeroFeatureFrames: Long = 0L
)

/** Explicit output-mix visualization boundary; a dead process never resumes the session. */
object ResonanceController {
    private val mutableState = MutableStateFlow(ResonanceState())
    val state: StateFlow<ResonanceState> = mutableState.asStateFlow()

    @Synchronized fun start(context: Context): Boolean {
        if (!ResonanceRuntime.canStart()) return false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionDenied(); return false
        }
        if (!Settings.canDrawOverlays(context)) {
            mutableState.value = mutableState.value.copy(status = ResonanceStatus.ERROR, detail = "Overlay permission is required")
            return false
        }
        val generation = ResonanceRuntime.begin()
        mutableState.value = mutableState.value.copy(status = ResonanceStatus.STARTING, detail = null, gpuFrames = 0,
            analysisFrames = 0, providerInitialized = false, nonzeroFeatureFrames = 0)
        return try {
            context.startForegroundService(Intent(context, ResonanceCaptureService::class.java)
                .putExtra("generation", generation))
            true
        } catch (error: RuntimeException) {
            finish(generation, ResonanceStatus.ERROR, "Android rejected visualization service start")
            cleanupComplete(generation)
            false
        }
    }

    fun stop(context: Context) {
        val generation = ResonanceRuntime.activeGeneration
        if (generation == 0L) return
        finish(generation, ResonanceStatus.STOPPED, null)
    }

    @Synchronized fun setIntensity(value: Float) {
        if (value.isFinite()) mutableState.value = mutableState.value.copy(intensity = value.coerceIn(0f, 1f))
    }
    @Synchronized fun permissionDenied() {
        if (ResonanceRuntime.activeGeneration == 0L) mutableState.value = mutableState.value.copy(status = ResonanceStatus.STOPPED, detail = "Audio visualization permission was not granted")
    }
    internal fun fail(generation: Long, detail: String) = finish(generation,
        if (detail.startsWith("GPU unavailable:")) ResonanceStatus.UNSUPPORTED else ResonanceStatus.ERROR, detail)
    internal fun unsupported(generation: Long, detail: String) = finish(generation, ResonanceStatus.UNSUPPORTED, detail)
    @Synchronized internal fun finish(generation: Long, status: ResonanceStatus, detail: String?) {
        if (!ResonanceRuntime.end(generation)) return
        mutableState.value = mutableState.value.copy(status = status, detail = detail, cleanupPending = true,
            providerInitialized = false, gpuFrames = ResonanceRuntime.gpuFrames,
            analysisFrames = ResonanceRuntime.analysisCount, nonzeroFeatureFrames = ResonanceRuntime.nonzeroFeatureCount)
        ResonanceCaptureService.terminate(generation)
    }
    @Synchronized internal fun cleanupComplete(generation: Long) {
        ResonanceRuntime.cleanupComplete(generation)
        if (!ResonanceRuntime.cleanupPending) mutableState.value = mutableState.value.copy(cleanupPending = false)
    }
    @Synchronized internal fun providerReady(generation: Long) {
        if (ResonanceRuntime.isCurrent(generation)) mutableState.value = mutableState.value.copy(
            providerInitialized = true, status = ResonanceStatus.WAITING_FOR_AUDIO)
    }
    @Synchronized internal fun waitingForFrames(generation: Long) {
        if (ResonanceRuntime.isCurrent(generation) && mutableState.value.providerInitialized) {
            mutableState.value = mutableState.value.copy(status = ResonanceStatus.WAITING_FOR_AUDIO, gpuFrames = ResonanceRuntime.gpuFrames)
        }
    }
    @Synchronized internal fun publish(snapshot: AudioFeatureSnapshot, analysisNanos: Long, nonzero: Boolean = snapshot.loudness > .025f) {
        if (!ResonanceRuntime.isCurrent(snapshot.generation)) return
        ResonanceRuntime.snapshot = snapshot
        ResonanceRuntime.analysisNanos += analysisNanos
        if (nonzero) ResonanceRuntime.nonzeroFeatureCount++
        val old = mutableState.value
        // UI notifications are bounded to about 5Hz, independent from analysis and host rendering.
        if (ResonanceRuntime.analysisCount % 5L == 0L || old.status == ResonanceStatus.STARTING) {
            mutableState.value = old.copy(status = if (nonzero) ResonanceStatus.RUNNING else ResonanceStatus.WAITING_FOR_AUDIO,
                analysisFrames = ResonanceRuntime.analysisCount + 1L, gpuFrames = ResonanceRuntime.gpuFrames,
                nonzeroFeatureFrames = ResonanceRuntime.nonzeroFeatureCount)
        }
        ResonanceRuntime.analysisCount++
    }
}

internal object ResonanceRuntime {
    private var nextGeneration = 0L
    @Volatile var activeGeneration = 0L; private set
    @Volatile var snapshot = AudioFeatureSnapshot()
    @Volatile var gpuFrames = 0L
    @Volatile var analysisCount = 0L
    @Volatile var analysisNanos = 0L
    @Volatile var nonzeroFeatureCount = 0L
    @Volatile var cleanupPending = false; private set
    @Volatile private var cleanupGeneration = 0L
    fun generationForTermination() = activeGeneration.takeIf { it != 0L }
        ?: cleanupGeneration.takeIf { cleanupPending } ?: 0L
    fun canStart() = activeGeneration == 0L && !cleanupPending
    @Synchronized fun begin(): Long {
        val value = ++nextGeneration
        snapshot = AudioFeatureSnapshot(generation = value); gpuFrames = 0; analysisCount = 0; analysisNanos = 0; nonzeroFeatureCount = 0
        activeGeneration = value
        return value
    }
    fun isCurrent(generation: Long) = generation != 0L && activeGeneration == generation
    @Synchronized fun end(generation: Long): Boolean {
        if (!isCurrent(generation)) return false
        activeGeneration = 0L
        cleanupGeneration = generation
        cleanupPending = true
        snapshot = AudioFeatureSnapshot()
        return true
    }
    @Synchronized fun cleanupComplete(generation: Long) {
        if (cleanupGeneration == generation) cleanupPending = false
    }
}
