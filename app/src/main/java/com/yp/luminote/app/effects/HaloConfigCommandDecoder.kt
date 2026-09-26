package com.yp.luminote.app.effects

import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.data.settings.HaloFrame
import com.yp.luminote.app.data.settings.HaloMotion
import com.yp.luminote.app.data.settings.NotificationPlayback

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
            repeatCount = command.repeatCount ?: defaults.repeatCount,
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
                    ?.let { name ->
                        runCatching {
                            HaloMotion.valueOf(name)
                        }.getOrNull()
                    }
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
            notificationPlayback =
                command.notificationPlaybackName
                    ?.let { name ->
                        runCatching {
                            NotificationPlayback.valueOf(name)
                        }.getOrNull()
                    }
                    ?: defaults.notificationPlayback
        ).sanitized()
}

internal data class RawHaloConfigCommand(
    val color: Int? = null,
    val intervalSeconds: Float? = null,
    val repeatCount: Int? = null,
    val intensity: Float? = null,
    val thickness: Float? = null,
    val frameName: String? = null,
    val motionName: String? = null,
    val effectSpeed: Float? = null,
    val gradientFlowSpeed: Float? = null,
    val colorModeName: String? = null,
    val notificationPlaybackName: String? = null
)
