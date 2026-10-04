package com.yp.luminote.app.effects

import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.data.settings.HaloFrame
import com.yp.luminote.app.data.settings.HaloMotion

/**
 * Decodes the configuration payload of a Halo command without owning its
 * Android Intent, delivery path, or renderer lifecycle.
 */
internal object HaloConfigCommandDecoder {

    fun decode(
        command: RawHaloConfigCommand,
        defaults: HaloConfig = HaloConfig()
    ): HaloConfig =
        HaloConfig(
            color = command.color ?: defaults.color,
            intervalSeconds = command.intervalSeconds ?: defaults.intervalSeconds,
            intensity = command.intensity ?: defaults.intensity,
            thickness = command.thickness ?: defaults.thickness,
            frame =
                command.frameName
                    ?.let { name ->
                        runCatching {
                            HaloFrame.valueOf(name)
                        }.getOrNull()
                    }
                    ?: defaults.frame,
            motion =
                command.motionName
                    .let(HaloMotion::fromStorage)
                    ?: defaults.motion,
            effectSpeed = command.effectSpeed ?: defaults.effectSpeed,
            gradientFlowSpeed =
                command.gradientFlowSpeed ?: defaults.gradientFlowSpeed,
            colorMode =
                command.colorModeName
                    ?.let { name ->
                        runCatching {
                            HaloColorMode.valueOf(name)
                        }.getOrNull()
                    }
                    ?: defaults.colorMode,
            edgeCalibrationDp = command.edgeCalibrationDp ?: defaults.edgeCalibrationDp,
            cornerCalibrationDp = command.cornerCalibrationDp ?: defaults.cornerCalibrationDp,
            cornerShape = command.cornerShape ?: defaults.cornerShape
        ).sanitized()
}

internal data class RawHaloConfigCommand(
    val color: Int? = null,
    val intervalSeconds: Float? = null,
    val intensity: Float? = null,
    val thickness: Float? = null,
    val frameName: String? = null,
    val motionName: String? = null,
    val effectSpeed: Float? = null,
    val gradientFlowSpeed: Float? = null,
    val colorModeName: String? = null,
    /** Parsed only to make legacy commands inert during migration. */
    val legacyNotificationPlaybackName: String? = null,
    val edgeCalibrationDp: Float? = null,
    val cornerCalibrationDp: Float? = null,
    val cornerShape: Float? = null
)
