package com.yp.luminote.app.data.settings

import androidx.annotation.StringRes
import com.yp.luminote.app.R

enum class HaloFrame {
    CLASSIC
}

enum class HaloMotion {
    PULSE,
    IMPULSE,
    SNAKE,
    CORNER_PULSE,
    RAIN,
    AZURE_BLADE,
    CRIMSON_BLADE,
    FORCE_CLASH;

    companion object {
        /**
         * Parses persisted and external motion identifiers while preserving the
         * one-way migration from the former Ripple Edge name.
         */
        fun fromStorage(value: String?): HaloMotion? =
            when (value) {
                "RIPPLE_EDGE" -> IMPULSE
                null -> null
                else -> entries.firstOrNull { it.name == value }
            }
    }
}

data class HaloFrameDefinition(
    val frame: HaloFrame,
    @get:StringRes val titleRes: Int,
    val supportedMotions: List<HaloMotion>
)

data class HaloMotionDefinition(
    val motion: HaloMotion,
    @get:StringRes val titleRes: Int,
    val baseDurationSeconds: Float
)

/** Single source of truth for selectable frames, animations and their capabilities. */
object HaloEffectCatalog {
    private val frames = listOf(
        HaloFrameDefinition(
            frame = HaloFrame.CLASSIC,
            titleRes = R.string.frame_edge,
            supportedMotions = listOf(
                HaloMotion.PULSE,
                HaloMotion.IMPULSE,
                HaloMotion.SNAKE,
                HaloMotion.CORNER_PULSE,
                HaloMotion.RAIN,
                HaloMotion.AZURE_BLADE,
                HaloMotion.CRIMSON_BLADE,
                HaloMotion.FORCE_CLASH
            )
        )
    )

    private val motions = listOf(
        HaloMotionDefinition(
            motion = HaloMotion.PULSE,
            titleRes = R.string.motion_pulse,
            baseDurationSeconds = 2.5f
        ),
        HaloMotionDefinition(
            motion = HaloMotion.IMPULSE,
            titleRes = R.string.motion_impulse,
            baseDurationSeconds = 2.2f
        ),
        HaloMotionDefinition(
            motion = HaloMotion.SNAKE,
            titleRes = R.string.motion_snake,
            baseDurationSeconds = 2.5f
        ),
        HaloMotionDefinition(
            motion = HaloMotion.CORNER_PULSE,
            titleRes = R.string.motion_corner_pulse,
            baseDurationSeconds = 2.8f
        ),
        HaloMotionDefinition(
            motion = HaloMotion.RAIN,
            titleRes = R.string.motion_rain,
            baseDurationSeconds = 2.6f
        ),
        HaloMotionDefinition(
            motion = HaloMotion.AZURE_BLADE,
            titleRes = R.string.motion_azure_blade,
            baseDurationSeconds = 3.4f
        ),
        HaloMotionDefinition(
            motion = HaloMotion.CRIMSON_BLADE,
            titleRes = R.string.motion_crimson_blade,
            baseDurationSeconds = 3.0f
        ),
        HaloMotionDefinition(
            motion = HaloMotion.FORCE_CLASH,
            titleRes = R.string.motion_force_clash,
            baseDurationSeconds = 3.6f
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
