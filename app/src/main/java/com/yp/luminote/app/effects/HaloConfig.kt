package com.yp.luminote.app.effects

import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.data.settings.HaloFrame
import com.yp.luminote.app.data.settings.HaloMotion
import com.yp.luminote.app.data.settings.NotificationPlayback
import com.yp.luminote.app.data.settings.durationFor

/** Immutable rendering parameters shared by every halo entry point. */
data class HaloConfig(
    val color: Int = 0xFF3E91FF.toInt(),
    val durationSeconds: Float = 2.5f,
    val intervalSeconds: Float = 1f,
    val repeatCount: Int = 1,
    val intensity: Float = 0.7f,
    val thickness: Float = 0.5f,
    val frame: HaloFrame = HaloFrame.CLASSIC,
    val motion: HaloMotion = HaloMotion.PULSE,
    val effectSpeed: Float = 1f,
    val gradientFlowSpeed: Float = 1f,
    val colorMode: HaloColorMode = HaloColorMode.SOLID,
    val notificationPlayback: NotificationPlayback = NotificationPlayback.ONCE,
    /** Colors used by a gradient treatment. */
    val palette: IntArray = intArrayOf()
) {
    fun sanitized(): HaloConfig {
        val sanitizedEffectSpeed =
            effectSpeed
                .takeIf { it.isFinite() }
                ?.coerceIn(MIN_EFFECT_SPEED, MAX_EFFECT_SPEED)
                ?: DEFAULT_EFFECT_SPEED

        return copy(
            effectSpeed = sanitizedEffectSpeed,
            durationSeconds = motion.durationFor(sanitizedEffectSpeed),
            gradientFlowSpeed =
                gradientFlowSpeed
                    .takeIf { it.isFinite() }
                    ?.coerceIn(
                        MIN_GRADIENT_FLOW_SPEED,
                        MAX_GRADIENT_FLOW_SPEED
                    )
                    ?: DEFAULT_GRADIENT_FLOW_SPEED,
            intervalSeconds =
                intervalSeconds
                    .takeIf { it.isFinite() }
                    ?.coerceAtLeast(MIN_INTERVAL_SECONDS)
                    ?: DEFAULT_INTERVAL_SECONDS,
            repeatCount =
                repeatCount
                    .takeIf {
                        it == CONTINUOUS_REPEAT_COUNT ||
                            it in MIN_REPEAT_COUNT..MAX_REPEAT_COUNT
                    }
                    ?: DEFAULT_REPEAT_COUNT,
            intensity =
                intensity
                    .takeIf { it.isFinite() }
                    ?.coerceIn(MIN_NORMALIZED_VALUE, MAX_NORMALIZED_VALUE)
                    ?: DEFAULT_INTENSITY,
            thickness =
                thickness
                    .takeIf { it.isFinite() }
                    ?.coerceIn(MIN_NORMALIZED_VALUE, MAX_NORMALIZED_VALUE)
                    ?: DEFAULT_THICKNESS,
            palette = palette.distinct().toIntArray()
        )
    }

    companion object {
        private const val MIN_EFFECT_SPEED = 0.25f
        private const val MAX_EFFECT_SPEED = 2f
        private const val DEFAULT_EFFECT_SPEED = 1f

        private const val MIN_GRADIENT_FLOW_SPEED = 0.5f
        private const val MAX_GRADIENT_FLOW_SPEED = 2.5f
        private const val DEFAULT_GRADIENT_FLOW_SPEED = 1f

        private const val MIN_INTERVAL_SECONDS = 0f
        private const val DEFAULT_INTERVAL_SECONDS = 1f

        private const val CONTINUOUS_REPEAT_COUNT = -1
        private const val MIN_REPEAT_COUNT = 1
        private const val MAX_REPEAT_COUNT = 5
        private const val DEFAULT_REPEAT_COUNT = 1

        private const val MIN_NORMALIZED_VALUE = 0f
        private const val MAX_NORMALIZED_VALUE = 1f
        private const val DEFAULT_INTENSITY = 0.7f
        private const val DEFAULT_THICKNESS = 0.5f

        fun defaultGradientPalette(): IntArray = DEFAULT_GRADIENT_COLORS.copyOf()

        private val DEFAULT_GRADIENT_COLORS = intArrayOf(
            0xFF007AFF.toInt(),
            0xFF5E5CE6.toInt(),
            0xFFBF5AF2.toInt(),
            0xFFFF375F.toInt(),
            0xFFFF9F0A.toInt(),
            0xFFFFD60A.toInt(),
            0xFF00C7BE.toInt(),
            0xFF007AFF.toInt()
        )
    }
}
