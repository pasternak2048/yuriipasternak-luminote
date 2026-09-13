package com.yp.luminote.app.data.settings

enum class NotificationSource {
    ALL_APPS,
    SELECTED_APPS
}

enum class NotificationPlayback {
    ONCE,
    REPEAT,
    KEEP_VISIBLE
}

data class LuminoteSettings(
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

    /** Tempo for the selected effect, independent from gradient color flow. */
    val haloEffectSpeed: Float = 1f,

    /** Multiplier for movement inside gradient color treatments. */
    val gradientFlowSpeed: Float = 1f,

    val gradientPalette: GradientPalette = GradientPalette.LUMINOTE,

    val notificationSource: NotificationSource =
        NotificationSource.ALL_APPS,

    val includeSilentUpdates: Boolean =
        false,

    val selectedApps: Set<String> =
        emptySet(),

    /** A screen-personalisation effect that deliberately takes precedence over alerts. */
    val ambientEnabled: Boolean = false,

    val ambientColor: Int = 0xFF3E91FF.toInt(),

    val ambientColorMode: HaloColorMode = HaloColorMode.SOLID,

    val ambientIntensity: Float = 0.35f,

    val ambientThickness: Float = 0.5f,

    val ambientMotion: HaloMotion = HaloMotion.PULSE,

    val ambientEffectSpeed: Float = 1f,

    val ambientGradientFlowSpeed: Float = 1f
)
