package com.yp.luminote.app.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateUiState {
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(
        val update: UpdateInfo
    ) : UpdateUiState
    data class Downloading(
        val update: UpdateInfo
    ) : UpdateUiState
    data class ReadyToInstall(
        val update: UpdateInfo,
        val apk: File
    ) : UpdateUiState
    data object CheckError : UpdateUiState
    data object DownloadError : UpdateUiState
}

class UpdateViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val preferences =
        UpdatePreferences(application)
    private val repository =
        GitHubUpdateRepository(application)

    private val _channel =
        MutableStateFlow(UpdateChannel.STABLE)

    val channel: StateFlow<UpdateChannel> =
        _channel.asStateFlow()

    private val _notificationsEnabled =
        MutableStateFlow(false)

    val notificationsEnabled: StateFlow<Boolean> =
        _notificationsEnabled.asStateFlow()

    private val _state =
        MutableStateFlow<UpdateUiState>(UpdateUiState.Checking)

    val state: StateFlow<UpdateUiState> =
        _state.asStateFlow()

    init {
        viewModelScope.launch {
            _channel.value = preferences.channel()
            _notificationsEnabled.value = preferences.notificationsEnabled()
            checkForUpdate()
        }
    }

    fun selectChannel(channel: UpdateChannel) {
        if (_channel.value == channel) return

        viewModelScope.launch {
            _channel.value = channel
            preferences.setChannel(channel)
            checkForUpdate()
        }
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            _state.value = UpdateUiState.Checking
            _state.value = runCatching {
                repository.findUpdate(_channel.value)
            }.fold(
                onSuccess = { update ->
                    update?.let(UpdateUiState::Available)
                        ?: UpdateUiState.UpToDate
                },
                onFailure = { UpdateUiState.CheckError }
            )
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            _notificationsEnabled.value = enabled
            preferences.setNotificationsEnabled(enabled)
        }
    }

    fun download(update: UpdateInfo) {
        viewModelScope.launch {
            _state.value = UpdateUiState.Downloading(update)
            _state.value = runCatching {
                repository.download(update)
            }.fold(
                onSuccess = { apk ->
                    UpdateUiState.ReadyToInstall(update, apk)
                },
                onFailure = { UpdateUiState.DownloadError }
            )
        }
    }
}
