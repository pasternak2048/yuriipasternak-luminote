package com.yp.luminote.app.effects

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.audiofx.Visualizer
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.yp.luminote.app.R

/**
 * Captures the system output mix for the user-enabled Music Equalizer ambient
 * mode. It never opens the microphone and publishes only a low-resolution FFT
 * used for drawing, not recording or storing audio.
 */
class MusicVisualizerService : Service() {
    private var visualizer: Visualizer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasRecordAudioPermission()) {
            Log.w(TAG, "Music equalizer started without RECORD_AUDIO permission")
            MusicSpectrum.clear()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startAsForeground()
        startVisualizerIfNeeded()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        visualizer?.runCatching {
            enabled = false
            release()
        }
        visualizer = null
        MusicSpectrum.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startVisualizerIfNeeded() {
        if (visualizer != null) return

        runCatching {
            Visualizer(OUTPUT_MIX_SESSION_ID).apply {
                val captureRange = Visualizer.getCaptureSizeRange()
                captureSize = captureRange.first()
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer,
                            waveform: ByteArray,
                            samplingRate: Int
                        ) = Unit

                        override fun onFftDataCapture(
                            visualizer: Visualizer,
                            fft: ByteArray,
                            samplingRate: Int
                        ) {
                            MusicSpectrum.publishFft(fft)
                        }
                    },
                    minOf(REQUESTED_CAPTURE_RATE, Visualizer.getMaxCaptureRate()),
                    false,
                    true
                )
                enabled = true
            }
        }.onSuccess { createdVisualizer ->
            visualizer = createdVisualizer
            Log.d(TAG, "Output-mix visualizer attached")
        }.onFailure { error ->
            MusicSpectrum.clear()
            Log.w(TAG, "Output-mix visualizer is unavailable on this device", error)
            stopSelf()
        }
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.music_visualizer_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_halo_notification)
            .setContentTitle(getString(R.string.music_visualizer_notification_title))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "MusicVisualizer"
        private const val CHANNEL_ID = "music_equalizer"
        private const val NOTIFICATION_ID = 1002
        private const val OUTPUT_MIX_SESSION_ID = 0
        private const val REQUESTED_CAPTURE_RATE = 30_000

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MusicVisualizerService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MusicVisualizerService::class.java))
        }
    }
}
