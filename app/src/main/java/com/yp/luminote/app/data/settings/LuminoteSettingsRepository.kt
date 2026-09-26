package com.yp.luminote.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.luminoteDataStore by
preferencesDataStore(
    name = "luminote_settings"
)

private val defaultSettings =
    LuminoteSettings()

class LuminoteSettingsRepository(
    private val context: Context
) {

    private object Keys {

        val themeMode =
            stringPreferencesKey(
                "theme_mode"
            )

        val haloMode =
            stringPreferencesKey(
                "halo_mode"
            )

        /*
         * Read-only migration keys for versions that used
         * independent switches.
         */
        val legacyHaloEnabled =
            booleanPreferencesKey(
                "halo_enabled"
            )

        val haloColor =
            intPreferencesKey(
                "halo_color_int"
            )

        val haloIntensity =
            floatPreferencesKey(
                "halo_intensity"
            )

        val haloThickness =
            floatPreferencesKey(
                "halo_thickness"
            )

        val haloInterval =
            floatPreferencesKey(
                "halo_interval"
            )

        val haloRepeatCount =
            intPreferencesKey(
                "halo_repeat_count"
            )

        val notificationPlayback =
            stringPreferencesKey(
                "notification_playback"
            )

        val notificationSource =
            stringPreferencesKey(
                "notification_source"
            )

        val haloFrame =
            stringPreferencesKey(
                "halo_frame"
            )

        val haloMotion =
            stringPreferencesKey(
                "halo_motion"
            )

        val haloEffectSpeed =
            floatPreferencesKey(
                "halo_effect_speed"
            )

        val gradientFlowSpeed =
            floatPreferencesKey(
                "gradient_flow_speed"
            )

        val colorSource =
            stringPreferencesKey(
                "halo_color_source"
            )

        val gradientPalette =
            stringPreferencesKey(
                "gradient_palette"
            )

        val selectedApps =
            stringSetPreferencesKey(
                "selected_apps"
            )

        val legacyAmbientEnabled =
            booleanPreferencesKey(
                "ambient_enabled"
            )

        val ambientColor =
            intPreferencesKey(
                "ambient_color"
            )

        val ambientColorMode =
            stringPreferencesKey(
                "ambient_color_mode"
            )

        val ambientIntensity =
            floatPreferencesKey(
                "ambient_intensity"
            )

        val ambientThickness =
            floatPreferencesKey(
                "ambient_thickness"
            )

        val ambientMotion =
            stringPreferencesKey(
                "ambient_motion"
            )

        val ambientEffectSpeed =
            floatPreferencesKey(
                "ambient_effect_speed"
            )

        val ambientGradientFlowSpeed =
            floatPreferencesKey(
                "ambient_gradient_flow_speed"
            )
    }

    val settings: Flow<LuminoteSettings> =
        context
            .luminoteDataStore
            .data
            .map { preferences ->

                LuminoteSettings(

                    themeMode = ThemeMode.fromStorage(
                        preferences[Keys.themeMode]
                    ),

                    haloMode =
                        preferences[Keys.haloMode]
                            ?.let {
                                runCatching {
                                    HaloMode.valueOf(it)
                                }.getOrNull()
                            }
                            ?: when {
                                preferences[
                                    Keys.legacyAmbientEnabled
                                ] == true ->
                                    HaloMode.AMBIENT

                                preferences[
                                    Keys.legacyHaloEnabled
                                ] == false ->
                                    HaloMode.OFF

                                else ->
                                    defaultSettings.haloMode
                            },

                    haloColor =
                        preferences[
                            Keys.haloColor
                        ] ?: defaultSettings.haloColor,

                    haloIntensity =
                        preferences[
                            Keys.haloIntensity
                        ]
                            ?.takeIf {
                                it.isFinite()
                            }
                            ?.coerceIn(
                                MIN_HALO_VALUE,
                                MAX_HALO_VALUE
                            )
                            ?: defaultSettings.haloIntensity,

                    haloThickness =
                        preferences[
                            Keys.haloThickness
                        ]
                            ?.takeIf {
                                it.isFinite()
                            }
                            ?.coerceIn(
                                MIN_HALO_VALUE,
                                MAX_HALO_VALUE
                            )
                            ?: defaultSettings.haloThickness,

                    haloInterval =
                        preferences[
                            Keys.haloInterval
                        ]
                            ?.takeIf {
                                it.isFinite()
                            }
                            ?.coerceIn(
                                MIN_HALO_INTERVAL_SECONDS,
                                MAX_HALO_INTERVAL_SECONDS
                            )
                            ?: defaultSettings.haloInterval,

                    haloRepeatCount =
                        preferences[
                            Keys.haloRepeatCount
                        ]
                            ?.takeIf {
                                it in 1..5
                            }
                            ?: defaultSettings.haloRepeatCount,

                    notificationPlayback =
                        preferences[
                            Keys.notificationPlayback
                        ]
                            ?.let {
                                runCatching {
                                    NotificationPlayback.valueOf(
                                        it
                                    )
                                }.getOrNull()
                            }
                            ?: NotificationPlayback.ONCE,

                    haloFrame =
                        preferences[
                            Keys.haloFrame
                        ]
                            ?.let {
                                runCatching {
                                    HaloFrame.valueOf(it)
                                }.getOrNull()
                            }
                            ?: HaloFrame.CLASSIC,

                    haloMotion =
                        preferences[
                            Keys.haloMotion
                        ]
                            ?.let {
                                runCatching {
                                    HaloMotion.valueOf(it)
                                }.getOrNull()
                            }
                            ?.takeIf {
                                HaloEffectCatalog.supports(
                                    HaloFrame.CLASSIC,
                                    it
                                )
                            }
                            ?: HaloMotion.PULSE,

                    haloEffectSpeed =
                        preferences[
                            Keys.haloEffectSpeed
                        ]
                            ?.takeIf {
                                it.isFinite()
                            }
                            ?.coerceIn(
                                0.25f,
                                2f
                            )
                            ?: defaultSettings.haloEffectSpeed,

                    gradientFlowSpeed =
                        preferences[
                            Keys.gradientFlowSpeed
                        ]
                            ?.takeIf {
                                it.isFinite()
                            }
                            ?.coerceIn(
                                0.5f,
                                2.5f
                            )
                            ?: defaultSettings.gradientFlowSpeed,

                    colorSource =
                        preferences[
                            Keys.colorSource
                        ]
                            ?.let {
                                runCatching {
                                    HaloColorSource.valueOf(
                                        it
                                    )
                                }.getOrNull()
                            }
                            ?: HaloColorSource.CUSTOM,

                    gradientPalette =
                        preferences[
                            Keys.gradientPalette
                        ]
                            ?.let {
                                runCatching {
                                    GradientPalette.valueOf(
                                        it
                                    )
                                }.getOrNull()
                            }
                            ?: GradientPalette.LUMINOTE,

                    notificationSource =
                        preferences[
                            Keys.notificationSource
                        ]
                            ?.let { value ->
                                runCatching {
                                    NotificationSource.valueOf(
                                        value
                                    )
                                }.getOrDefault(
                                    NotificationSource.ALL_APPS
                                )
                            }
                            ?: NotificationSource.ALL_APPS,

                    selectedApps =
                        preferences[
                            Keys.selectedApps
                        ] ?: defaultSettings.selectedApps,

                    ambientColor =
                        preferences[
                            Keys.ambientColor
                        ] ?: defaultSettings.ambientColor,

                    ambientColorMode =
                        preferences[
                            Keys.ambientColorMode
                        ]
                            ?.let {
                                runCatching {
                                    HaloColorMode.valueOf(
                                        it
                                    )
                                }.getOrNull()
                            }
                            ?.takeIf {
                                it == HaloColorMode.SOLID ||
                                        it == HaloColorMode.GRADIENT
                            }
                            ?: defaultSettings.ambientColorMode,

                    ambientIntensity =
                        preferences[
                            Keys.ambientIntensity
                        ]
                            ?.takeIf {
                                it.isFinite()
                            }
                            ?.coerceIn(
                                0f,
                                1f
                            )
                            ?: defaultSettings.ambientIntensity,

                    ambientThickness =
                        preferences[
                            Keys.ambientThickness
                        ]
                            ?.takeIf {
                                it.isFinite()
                            }
                            ?.coerceIn(
                                0f,
                                1f
                            )
                            ?: defaultSettings.ambientThickness,

                    ambientMotion =
                        preferences[
                            Keys.ambientMotion
                        ]
                            ?.let {
                                runCatching {
                                    HaloMotion.valueOf(
                                        it
                                    )
                                }.getOrNull()
                            }
                            ?.takeIf {
                                it == HaloMotion.PULSE ||
                                        it == HaloMotion.SNAKE
                            }
                            ?: defaultSettings.ambientMotion,

                    ambientEffectSpeed =
                        preferences[
                            Keys.ambientEffectSpeed
                        ]
                            ?.takeIf {
                                it.isFinite()
                            }
                            ?.coerceIn(
                                0.25f,
                                2f
                            )
                            ?: defaultSettings.ambientEffectSpeed,

                    ambientGradientFlowSpeed =
                        preferences[
                            Keys.ambientGradientFlowSpeed
                        ]
                            ?.takeIf {
                                it.isFinite()
                            }
                            ?.coerceIn(
                                0.5f,
                                2.5f
                            )
                            ?: defaultSettings.ambientGradientFlowSpeed
                )
            }
            .distinctUntilChanged()

    /**
     * Persists one coherent settings snapshot in a single
     * DataStore transaction. Save ordering is intentionally owned by
     * LuminoteSettingsViewModel; do not add a competing debounce or revision
     * policy at this persistence boundary.
     */
    suspend fun saveSettings(
        settings: LuminoteSettings
    ) {
        context.luminoteDataStore.edit { preferences ->
            preferences[Keys.themeMode] =
                settings.themeMode.name

            preferences[Keys.haloMode] =
                settings.haloMode.name

            preferences[Keys.haloColor] =
                settings.haloColor

            preferences[Keys.haloIntensity] =
                settings.haloIntensity
                    .takeIf {
                        it.isFinite()
                    }
                    ?.coerceIn(
                        MIN_HALO_VALUE,
                        MAX_HALO_VALUE
                    )
                    ?: defaultSettings.haloIntensity

            preferences[Keys.haloThickness] =
                settings.haloThickness
                    .takeIf {
                        it.isFinite()
                    }
                    ?.coerceIn(
                        MIN_HALO_VALUE,
                        MAX_HALO_VALUE
                    )
                    ?: defaultSettings.haloThickness

            preferences[Keys.haloInterval] =
                settings.haloInterval
                    .takeIf {
                        it.isFinite()
                    }
                    ?.coerceIn(
                        MIN_HALO_INTERVAL_SECONDS,
                        MAX_HALO_INTERVAL_SECONDS
                    )
                    ?: defaultSettings.haloInterval

            preferences[Keys.haloRepeatCount] =
                settings.haloRepeatCount
                    .coerceIn(
                        1,
                        5
                    )

            preferences[Keys.notificationPlayback] =
                settings.notificationPlayback.name

            preferences[Keys.haloFrame] =
                settings.haloFrame.name

            preferences[Keys.haloMotion] =
                settings.haloMotion.name

            preferences[Keys.haloEffectSpeed] =
                settings.haloEffectSpeed

            preferences[Keys.gradientFlowSpeed] =
                settings.gradientFlowSpeed

            preferences[Keys.colorSource] =
                settings.colorSource.name

            preferences[Keys.gradientPalette] =
                settings.gradientPalette.name

            preferences[Keys.notificationSource] =
                settings.notificationSource.name

            preferences[Keys.selectedApps] =
                settings.selectedApps

            preferences[Keys.ambientColor] =
                settings.ambientColor

            preferences[Keys.ambientColorMode] =
                settings.ambientColorMode.name

            preferences[Keys.ambientIntensity] =
                settings.ambientIntensity

            preferences[Keys.ambientThickness] =
                settings.ambientThickness

            preferences[Keys.ambientMotion] =
                settings.ambientMotion.name

            preferences[Keys.ambientEffectSpeed] =
                settings.ambientEffectSpeed

            preferences[Keys.ambientGradientFlowSpeed] =
                settings.ambientGradientFlowSpeed
        }
    }
}
