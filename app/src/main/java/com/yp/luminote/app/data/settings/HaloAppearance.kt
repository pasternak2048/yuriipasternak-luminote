package com.yp.luminote.app.data.settings

enum class HaloFrame {
    CLASSIC
}

enum class HaloMotion {
    PULSE,
    SNAKE,
    CORNER_PULSE,
    RAIN,
    RIPPLE_EDGE,
    EQUALIZER
}

data class HaloFrameDefinition(
    val frame: HaloFrame,
    val title: String,
    val description: String,
    val supportedMotions: List<HaloMotion>
)

data class HaloMotionDefinition(
    val motion: HaloMotion,
    val title: String,
    val description: String,
    val baseDurationSeconds: Float,
    val ambientDescription: String
)

/** Single source of truth for selectable frames, animations and their capabilities. */
object HaloEffectCatalog {
    private val frames = listOf(
        HaloFrameDefinition(
            frame = HaloFrame.CLASSIC,
            title = "Edge Frame",
            description = "Follows the display contour",
            supportedMotions = listOf(HaloMotion.PULSE, HaloMotion.SNAKE, HaloMotion.CORNER_PULSE, HaloMotion.RAIN, HaloMotion.RIPPLE_EDGE)
        )
    )

    private val motions = listOf(
        HaloMotionDefinition(
            motion = HaloMotion.PULSE,
            title = "Pulse",
            description = "A clean edge pulse",
            baseDurationSeconds = 2.5f,
            ambientDescription = "Stays gently visible around the edge"
        ),
        HaloMotionDefinition(
            motion = HaloMotion.SNAKE,
            title = "Snake",
            description = "A segment runs around the edge",
            baseDurationSeconds = 2.5f,
            ambientDescription = "Keeps moving around the edge"
        ),
        HaloMotionDefinition(
            motion = HaloMotion.CORNER_PULSE,
            title = "Corner pulse",
            description = "Light moves between the four corners",
            baseDurationSeconds = 2.8f,
            ambientDescription = "Gently moves attention between corners"
        ),
        HaloMotionDefinition(
            motion = HaloMotion.RAIN,
            title = "Rain",
            description = "Light falls down both edges",
            baseDurationSeconds = 2.6f,
            ambientDescription = "Keeps a gentle rainfall along the edges"
        ),
        HaloMotionDefinition(
            motion = HaloMotion.RIPPLE_EDGE,
            title = "Ripple edge",
            description = "Two waves spread around the frame",
            baseDurationSeconds = 2.8f,
            ambientDescription = "Keeps soft waves moving around the edge"
        ),
        HaloMotionDefinition(
            motion = HaloMotion.EQUALIZER,
            title = "Music equalizer",
            description = "Audio-reactive light from music playback",
            baseDurationSeconds = 2.5f,
            ambientDescription = "Responds to the frequencies in current music playback"
        )
    )

    fun frame(frame: HaloFrame): HaloFrameDefinition = frames.first { it.frame == frame }

    fun motion(motion: HaloMotion): HaloMotionDefinition = motions.first { it.motion == motion }

    fun supports(frame: HaloFrame, motion: HaloMotion): Boolean =
        motion in frame(frame).supportedMotions
}

val HaloFrame.definition: HaloFrameDefinition
    get() = HaloEffectCatalog.frame(this)

val HaloMotion.definition: HaloMotionDefinition
    get() = HaloEffectCatalog.motion(this)

fun HaloMotion.durationFor(effectSpeed: Float): Float =
    definition.baseDurationSeconds / effectSpeed.coerceIn(MIN_EFFECT_SPEED, MAX_EFFECT_SPEED)

private const val MIN_EFFECT_SPEED = 0.25f
private const val MAX_EFFECT_SPEED = 2f

enum class HaloColorMode {
    SOLID,
    GRADIENT
}

enum class HaloColorSource { CUSTOM, APP_ICON, GRADIENT }

enum class GradientPalette {
    LUMINOTE,
    NOTIFICATION_APPS
}
