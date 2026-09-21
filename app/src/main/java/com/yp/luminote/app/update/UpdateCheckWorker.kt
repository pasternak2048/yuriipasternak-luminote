package com.yp.luminote.app.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class UpdateCheckWorker(
    appContext: Context,
    parameters: WorkerParameters
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result =
        runCatching {
            val preferences =
                UpdatePreferences(applicationContext)
            val channel =
                preferences.channel()
            if (!preferences.notificationsEnabled()) {
                return@runCatching Result.success()
            }
            val update =
                GitHubUpdateRepository(applicationContext)
                    .findUpdate(channel)

            if (
                update != null &&
                !preferences.wasNotified(
                    channel,
                    update.versionCode
                )
            ) {
                if (UpdateNotifications.show(applicationContext, update)) {
                    preferences.markNotified(channel, update.versionCode)
                }
            }

            Result.success()
        }.getOrElse {
            Result.retry()
        }
}
