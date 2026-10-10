package com.yp.luminote.app.data.settings

import androidx.annotation.StringRes
import com.yp.luminote.app.R
import com.yp.luminote.app.animation.definitions.HaloAnimationDefinition
import com.yp.luminote.app.animation.definitions.HaloAnimationId
import com.yp.luminote.app.animation.definitions.LuminoteHaloAnimations

enum class HaloFrame { CLASSIC }

/**
 * Legacy persisted-name adapter. Runtime requests, config, and sessions use
 * [HaloAnimationId]; do not add behavior to this enum.
 */
@Deprecated("Use HaloAnimationId")
enum class HaloMotion(val animationId: HaloAnimationId) {
    PULSE(HaloAnimationId("PULSE")),
    IMPULSE(HaloAnimationId("IMPULSE")),
    SNAKE(HaloAnimationId("SNAKE")),
    AZURE_BLADE(HaloAnimationId("AZURE_BLADE")),
    CRIMSON_BLADE(HaloAnimationId("CRIMSON_BLADE")),
    FORCE_CLASH(HaloAnimationId("FORCE_CLASH"));

    companion object {
        /** Parses retained IDs and known historical IDs, without granting historical IDs runtime behaviour. */
        fun fromStorage(value: String?): HaloMotion? = when (value) {
            "RIPPLE_EDGE" -> IMPULSE
            "CORNER_PULSE", "RAIN", null -> null
            else -> entries.firstOrNull { it.name == value }
        }
    }
}

data class HaloFrameDefinition(val frame: HaloFrame, @get:StringRes val titleRes: Int, val supportedMotions: List<HaloMotion>)

/** Storage compatibility and application fallback policy, intentionally outside the registry. */
object HaloAnimationStorage {
    val normalFallback = HaloAnimationId("IMPULSE")
    val ambientFallback = HaloAnimationId("PULSE")

    fun resolveNormal(value: String?): HaloAnimationId =
        resolveKnown(value) ?: normalFallback

    fun resolveAmbient(value: String?): HaloAnimationId =
        resolveKnown(value)?.takeIf { LuminoteHaloAnimations.registry.definition(it).ambientEligible }
            ?: ambientFallback

    private fun resolveKnown(value: String?): HaloAnimationId? {
        val id = when (value) {
            "RIPPLE_EDGE" -> normalFallback
            null, "", "CORNER_PULSE", "RAIN" -> return null
            else -> HaloAnimationId(value)
        }
        return LuminoteHaloAnimations.registry.find(id)?.id
    }
}

/** Compatibility facade while callers migrate to the registry's definition lists. */
object HaloEffectCatalog {
    private val frames = listOf(HaloFrameDefinition(HaloFrame.CLASSIC, R.string.frame_edge, HaloMotion.entries))
    fun frame(frame: HaloFrame): HaloFrameDefinition = frames.first { it.frame == frame }
    fun animation(id: HaloAnimationId): HaloAnimationDefinition = LuminoteHaloAnimations.registry.definition(id)
    fun supports(frame: HaloFrame, motion: HaloMotion): Boolean = motion in frame(frame).supportedMotions
}

val HaloFrame.definition: HaloFrameDefinition get() = HaloEffectCatalog.frame(this)
fun HaloAnimationId.durationFor(effectSpeed: Float): Float =
    LuminoteHaloAnimations.registry.definition(this).baseDurationSeconds / effectSpeed.coerceIn(MIN_EFFECT_SPEED, MAX_EFFECT_SPEED)
private const val MIN_EFFECT_SPEED = 0.25f
private const val MAX_EFFECT_SPEED = 2f

enum class HaloColorMode { SOLID, GRADIENT }
enum class HaloColorSource { CUSTOM, APP_ICON, GRADIENT }
enum class GradientPalette { LUMINOTE, NOTIFICATION_APPS }
