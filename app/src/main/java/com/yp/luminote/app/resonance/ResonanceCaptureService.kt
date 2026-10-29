package com.yp.luminote.app.resonance

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.yp.luminote.app.MainActivity
import com.yp.luminote.app.R
import com.yp.luminote.app.data.settings.LuminoteSettingsRepository
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.overlay.service.HaloOverlayService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit session-0 visualization, owned by a non-restarting foreground service. */
class ResonanceCaptureService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var generation = 0L
    private var provider: VisualizerAudioProvider? = null
    private var destroyed = false
    private var releaseComplete = true
    private val closing = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getLongExtra("generation", 0L) ?: 0L
        if (generation != 0L || !ResonanceRuntime.isCurrent(requested)) {
            ResonanceController.cleanupComplete(requested)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        generation = requested
        instance = this
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.resonance_title), NotificationManager.IMPORTANCE_LOW))
            val stopIntent = PendingIntent.getBroadcast(this, 2203, Intent(this, ResonanceStopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
            val openIntent = PendingIntent.getActivity(this, 2204, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_halo_notification).setContentTitle(getString(R.string.resonance_title))
                .setContentText(getString(R.string.resonance_description))
                .setContentIntent(openIntent).addAction(Notification.Action.Builder(null, getString(R.string.resonance_stop), stopIntent).build())
                .setOngoing(true).build()
            // Low-fidelity output-mix visualization is neither microphone recording nor projection.
            startForeground(2202, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            scope.launch {
                try {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw SecurityException("Visualization permission missing")
                    val settings = LuminoteSettingsRepository(applicationContext).settings.first()
                    if (!ResonanceRuntime.isCurrent(generation)) return@launch
                    val config = HaloConfig(thickness = settings.haloThickness,
                        frame = settings.haloFrame, edgeCalibrationDp = settings.displayEdgeCalibrationDp,
                        cornerCalibrationDp = settings.displayCornerCalibrationDp, cornerShape = settings.displayCornerShape)
                    if (!HaloOverlayService.startResonance(this@ResonanceCaptureService, generation, config)) error("GPU overlay could not be started")
                    val source = VisualizerAudioProvider(applicationContext, generation, ResonanceRuntime::isCurrent,
                        onReady = { ResonanceController.providerReady(generation) },
                        onFeature = { snapshot, nanos, nonzero -> ResonanceController.publish(snapshot, nanos, nonzero) },
                        onFailure = { reason -> ResonanceController.unsupported(generation, reason) },
                        onMissingFrames = { ResonanceController.waitingForFrames(generation) })
                    provider = source
                    releaseComplete = false
                    source.start()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    ResonanceController.fail(generation, "Visualization session could not start: ${error.javaClass.simpleName}")
                }
            }
        } catch (error: RuntimeException) {
            ResonanceController.fail(generation, "Android refused visualization service: ${error.javaClass.simpleName}")
        }
        return START_NOT_STICKY
    }

    private fun terminateOwnedSession() {
        if (!closing.compareAndSet(false, true)) return
        scope.cancel()
        runCatching { HaloOverlayService.stopResonance(this, generation) }
        val source = provider
        provider = null
        if (source == null) {
            releaseComplete = true
            if (destroyed) ResonanceController.cleanupComplete(generation) else stopSelf()
        } else {
            source.close().thenRun {
                handler.post {
                    releaseComplete = true
                    if (destroyed) ResonanceController.cleanupComplete(generation) else stopSelf()
                }
            }
        }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    override fun onDestroy() {
        destroyed = true
        if (ResonanceRuntime.isCurrent(generation)) ResonanceController.finish(generation, ResonanceStatus.STOPPED, null)
        terminateOwnedSession()
        if (instance === this) instance = null
        if (releaseComplete) ResonanceController.cleanupComplete(generation)
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "resonance_visualization"
        @Volatile private var instance: ResonanceCaptureService? = null
        internal fun terminate(generation: Long) {
            instance?.takeIf { it.generation == generation }?.let { service ->
                service.handler.post { service.terminateOwnedSession() }
            }
        }
    }
}

class ResonanceStopReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) { ResonanceController.stop(context) }
}
