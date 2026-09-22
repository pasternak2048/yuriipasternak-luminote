package com.yp.luminote.app.data.settings

const val MIN_HALO_VALUE = 0f
const val MAX_HALO_VALUE = 1f
const val MIN_HALO_INTERVAL_SECONDS = 0f
const val MAX_HALO_INTERVAL_SECONDS = 10f

enum class NotificationSource {
    ALL_APPS,
    SELECTED_APPS
}

enum class NotificationPlayback {
    ONCE,
    REPEAT,
    KEEP_VISIBLE
}

/** Only one screen-edge experience can own the overlay at a time. */
enum class HaloMode {
    NOTIFICATIONS,
    AMBIENT,
    OFF
}

data class LuminoteSettings(
    val haloMode: HaloMode = HaloMode.NOTIFICATIONS,

    val haloColor: Int =
        0xFF3E91FF.toInt(),

    val colorSource: HaloColorSource = HaloColorSource.CUSTOM,

    val haloIntensity: Float =
        0.7f,

    val haloThickness: Float =
        0.5f,

    val haloInterval: Float =
        1.0f,

    /** Number of replay cycles used only when notificationPlayback is REPEAT. */
    val haloRepeatCount: Int =
        1,

    val notificationPlayback: NotificationPlayback = NotificationPlayback.ONCE,

    val haloFrame: HaloFrame = HaloFrame.CLASSIC,

    val haloMotion: HaloMotion = HaloMotion.PULSE,

    /** Tempo for the selected effect, independent of gradient color flow. */
    val haloEffectSpeed: Float = 1f,

    /** Multiplier for movement inside gradient color treatments. */
    val gradientFlowSpeed: Float = 1f,

    val gradientPalette: GradientPalette = GradientPalette.LUMINOTE,

    val notificationSource: NotificationSource =
        NotificationSource.ALL_APPS,

    val selectedApps: Set<String> =
        emptySet(),

    val ambientColor: Int = 0xFF3E91FF.toInt(),

    val ambientColorMode: HaloColorMode = HaloColorMode.SOLID,

    val ambientIntensity: Float = 0.35f,

    val ambientThickness: Float = 0.5f,

    val ambientMotion: HaloMotion = HaloMotion.PULSE,

    val ambientEffectSpeed: Float = 1f,

    val ambientGradientFlowSpeed: Float = 1f
) {
    val haloEnabled: Boolean
        get() = haloMode == HaloMode.NOTIFICATIONS

    val ambientEnabled: Boolean
        get() = haloMode == HaloMode.AMBIENT
}
