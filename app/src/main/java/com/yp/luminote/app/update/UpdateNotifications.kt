package com.yp.luminote.app.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yp.luminote.app.MainActivity
import com.yp.luminote.app.R

object UpdateNotifications {

    fun show(context: Context, update: UpdateInfo): Boolean {
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        createChannel(context)

        val openAppIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags =
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        NotificationManagerCompat.from(context).notify(
            UPDATE_NOTIFICATION_ID,
            NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
                .setSmallIcon(com.yp.luminote.app.R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.update_notification_title, update.versionName))
                .setContentText(context.getString(R.string.update_notification_text, context.getString(update.channel.labelRes)))
                .setContentIntent(openAppIntent)
                .setAutoCancel(true)
                .build()
        )

        return true
    }

    private fun createChannel(context: Context) {
        val manager =
            context.getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                UPDATE_CHANNEL_ID,
                context.getString(R.string.update_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.update_notification_channel_description)
            }
        )
    }

    private const val UPDATE_CHANNEL_ID = "app_updates"
    private const val UPDATE_NOTIFICATION_ID = 2_401
}
