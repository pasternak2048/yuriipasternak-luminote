package com.yp.luminote.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.data.settings.HaloColorSource
import com.yp.luminote.app.data.settings.HaloMotion
import com.yp.luminote.app.data.settings.HaloMode
import com.yp.luminote.app.data.settings.GradientPalette
import com.yp.luminote.app.data.settings.LuminoteSettings
import com.yp.luminote.app.data.settings.LuminoteSettingsRepository
import com.yp.luminote.app.data.settings.NotificationSource
import com.yp.luminote.app.data.settings.NotificationPlayback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LuminoteSettingsViewModel(
    private val repository: LuminoteSettingsRepository
) : ViewModel() {

    private val _settingsLoaded =
        MutableStateFlow(false)

    val settingsLoaded:
            StateFlow<Boolean> =
        _settingsLoaded

    private val _settings =
        MutableStateFlow(
            LuminoteSettings()
        )

    val settings:
            StateFlow<LuminoteSettings> =
        _settings.asStateFlow()

    private var pendingSaveJob: Job? = null

    private var hasPendingSave = false

    /*
     * A cancelled or slow DataStore write must never mark a newer edit as
     * persisted. Each UI change therefore owns a monotonically increasing
     * revision, and only the latest revision may clear hasPendingSave.
     */
    private var settingsRevision = 0L

    init {
        viewModelScope.launch {
            repository.settings.collect { persistedSettings ->
                if (!hasPendingSave) {
                    _settings.value = persistedSettings
                }
                _settingsLoaded.value = true
            }
        }
    }

    fun setHaloColor(
        color: Int
    ) {
        updateSettings {
            copy(haloColor = color)
        }
    }

    fun setHaloMode(mode: HaloMode) {
        updateSettings { copy(haloMode = mode) }
    }

    fun setHaloIntensity(
        intensity: Float
    ) {
        updateSettings(debounce = true) {
            copy(haloIntensity = intensity)
        }
    }

    fun setHaloThickness(
        thickness: Float
    ) {
        updateSettings(debounce = true) {
            copy(haloThickness = thickness)
        }
    }

    fun setHaloInterval(
        interval: Float
    ) {
        updateSettings(debounce = true) {
            copy(haloInterval = interval)
        }
    }

    fun setHaloColorMode(mode: HaloColorMode) {
        updateSettings {
            copy(
                colorSource = if (mode == HaloColorMode.GRADIENT) HaloColorSource.GRADIENT else HaloColorSource.CUSTOM,
            )
        }
    }

    fun setHaloMotion(motion: HaloMotion) {
        updateSettings { copy(haloMotion = motion) }
    }

    fun setGradientFlowSpeed(speed: Float) {
        updateSettings(debounce = true) {
            copy(gradientFlowSpeed = speed.coerceIn(0.5f, 2.5f))
        }
    }

    fun setHaloEffectSpeed(speed: Float) {
        updateSettings(debounce = true) {
            copy(haloEffectSpeed = speed.coerceIn(0.25f, 2f))
        }
    }

    fun setGradientPalette(palette: GradientPalette) {
        updateSettings { copy(gradientPalette = palette) }
    }

    fun setNotificationSource(
        source: NotificationSource
    ) {
        updateSettings {
            copy(notificationSource = source)
        }
    }

    fun setSelectedApps(
        apps: Set<String>
    ) {
        updateSettings {
            copy(selectedApps = apps)
        }
    }

    fun setAppSelected(
        packageName: String,
        selected: Boolean
    ) {
        val currentApps =
            settings.value.selectedApps
                .toMutableSet()

        if (selected) {
            currentApps.add(
                packageName
            )
        } else {
            currentApps.remove(
                packageName
            )
        }

        setSelectedApps(
            currentApps
        )
    }

    fun setAppIconBasedColor(enabled: Boolean) {
        updateSettings {
            copy(
                colorSource = if (enabled) HaloColorSource.APP_ICON else HaloColorSource.CUSTOM,
            )
        }
    }

    fun setHaloRepeatCount(count: Int) {
        updateSettings {
            copy(haloRepeatCount = count.coerceIn(2, 5))
        }
    }

    fun setIncludeSilentUpdates(enabled: Boolean) {
        updateSettings {
            copy(includeSilentUpdates = enabled)
        }
    }

    fun setNotificationPlayback(playback: NotificationPlayback) {
        updateSettings {
            copy(
                notificationPlayback = playback,
                haloRepeatCount = if (playback == NotificationPlayback.REPEAT && haloRepeatCount < 2) 2 else haloRepeatCount
            )
        }
    }

    fun setAmbientColor(color: Int) {
        updateSettings { copy(ambientColor = color, ambientColorMode = HaloColorMode.SOLID) }
    }

    fun setAmbientColorMode(mode: HaloColorMode) {
        updateSettings { copy(ambientColorMode = mode) }
    }

    fun setAmbientIntensity(intensity: Float) {
        updateSettings(debounce = true) { copy(ambientIntensity = intensity.coerceIn(0f, 1f)) }
    }

    fun setAmbientThickness(thickness: Float) {
        updateSettings(debounce = true) { copy(ambientThickness = thickness.coerceIn(0f, 1f)) }
    }

    fun setAmbientMotion(motion: HaloMotion) {
        updateSettings {
            copy(ambientMotion = motion.takeIf { it == HaloMotion.PULSE || it == HaloMotion.SNAKE } ?: HaloMotion.PULSE)
        }
    }

    fun setAmbientEffectSpeed(speed: Float) {
        updateSettings(debounce = true) { copy(ambientEffectSpeed = speed.coerceIn(0.25f, 2f)) }
    }

    fun setAmbientGradientFlowSpeed(speed: Float) {
        updateSettings(debounce = true) { copy(ambientGradientFlowSpeed = speed.coerceIn(0.5f, 2.5f)) }
    }

    fun flushPendingSettings() {
        if (!hasPendingSave) {
            return
        }

        pendingSaveJob?.cancel()
        persistSettings(
            settings = _settings.value,
            revision = settingsRevision
        )
    }

    private fun updateSettings(
        debounce: Boolean = false,
        transform: LuminoteSettings.() -> LuminoteSettings
    ) {
        _settings.value = _settings.value.transform()
        val updatedSettings = _settings.value
        val revision = ++settingsRevision
        hasPendingSave = true
        pendingSaveJob?.cancel()

        if (!debounce) {
            persistSettings(
                settings = updatedSettings,
                revision = revision
            )
            return
        }

        pendingSaveJob = viewModelScope.launch {
            delay(SETTINGS_WRITE_DEBOUNCE_MS)
            persistSettings(
                settings = updatedSettings,
                revision = revision
            )
        }
    }

    private fun persistSettings(
        settings: LuminoteSettings,
        revision: Long
    ) {
        viewModelScope.launch {
            try {
                repository.saveSettings(settings)
            } finally {
                if (revision == settingsRevision) {
                    hasPendingSave = false
                }
            }
        }
    }

    companion object {
        private const val SETTINGS_WRITE_DEBOUNCE_MS = 250L
    }
}

class LuminoteSettingsViewModelFactory(
    private val repository:
    LuminoteSettingsRepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(
        modelClass: Class<T>
    ): T {

        if (
            modelClass.isAssignableFrom(
                LuminoteSettingsViewModel::class.java
            )
        ) {
            return LuminoteSettingsViewModel(
                repository
            ) as T
        }

        throw IllegalArgumentException(
            "Unknown ViewModel class: " +
                    modelClass.name
        )
    }
}
