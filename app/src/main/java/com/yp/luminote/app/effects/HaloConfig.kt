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
            effectSpeed.takeIf { it.isFinite() }?.coerceIn(0.25f, 2f) ?: 1f

        return copy(
            effectSpeed = sanitizedEffectSpeed,
            durationSeconds = motion.durationFor(sanitizedEffectSpeed),
            gradientFlowSpeed = gradientFlowSpeed.takeIf { it.isFinite() }?.coerceIn(0.5f, 2.5f) ?: 1f,
            intervalSeconds = intervalSeconds.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 1f,
            repeatCount = repeatCount.takeIf { it == -1 || it in 1..5 } ?: 1,
            intensity = intensity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.7f,
            thickness = thickness.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f,
            palette = palette.distinct().toIntArray()
        )
    }

    companion object {
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
