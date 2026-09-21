package com.yp.luminote.app.update

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.updateDataStore by
    preferencesDataStore(
        name = "update_settings"
    )

class UpdatePreferences(
    private val context: Context
) {

    suspend fun channel(): UpdateChannel =
        context.updateDataStore.data.first()[CHANNEL]
            ?.let { stored ->
                runCatching {
                    UpdateChannel.valueOf(stored)
                }.getOrNull()
            }
            ?: UpdateChannel.STABLE

    suspend fun setChannel(channel: UpdateChannel) {
        context.updateDataStore.edit { preferences ->
            preferences[CHANNEL] = channel.name
        }
    }

    suspend fun notificationsEnabled(): Boolean =
        context.updateDataStore.data.first()[NOTIFICATIONS_ENABLED]
            ?: false

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.updateDataStore.edit { preferences ->
            preferences[NOTIFICATIONS_ENABLED] = enabled
        }
    }

    suspend fun wasNotified(channel: UpdateChannel, versionCode: Long): Boolean =
        context.updateDataStore.data.first()[notifiedVersionKey(channel)] == versionCode

    suspend fun markNotified(channel: UpdateChannel, versionCode: Long) {
        context.updateDataStore.edit { preferences ->
            preferences[notifiedVersionKey(channel)] = versionCode
        }
    }

    private companion object {
        val CHANNEL =
            stringPreferencesKey(
                "update_channel"
            )

        val NOTIFICATIONS_ENABLED =
            booleanPreferencesKey(
                "update_notifications_enabled"
            )

        fun notifiedVersionKey(channel: UpdateChannel) =
            longPreferencesKey(
                "notified_update_${channel.name.lowercase()}"
            )
    }
}
