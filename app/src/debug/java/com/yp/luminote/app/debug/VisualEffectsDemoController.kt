package com.yp.luminote.app.debug

import android.content.Context
import android.view.ViewGroup
import com.yp.luminote.app.animation.definitions.AzureBladeAnimation
import com.yp.luminote.app.animation.definitions.BladeVariant
import com.yp.luminote.app.animation.definitions.CrimsonBladeAnimation
import com.yp.luminote.app.animation.definitions.ForceClashAnimation
import com.yp.luminote.app.animation.definitions.HaloAnimationId
import com.yp.luminote.app.animation.definitions.HaloEffectSpec
import com.yp.luminote.app.animation.definitions.ImpulseAnimation
import com.yp.luminote.app.animation.definitions.PulseAnimation
import com.yp.luminote.app.animation.definitions.SnakeAnimation
import com.yp.luminote.app.animation.definitions.SupernovaAnimation
import com.yp.luminote.app.animation.plan.HaloEffectVisualPlanMapper
import com.yp.luminote.app.animation.plan.PrimaryVisualOperation
import com.yp.luminote.app.animation.plan.VisualModifier
import com.yp.luminote.app.animation.plan.VisualPlanSpec
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.overlay.host.HaloView

/** The modifier stack variants exposed by the debug-only visual-effects demo. */
internal enum class VisualEffectsDemoMode {
    BASELINE,
    GLOW,
    BLUR,
    INTENSITY_PULSE,
    COMBINED,
    /** Deliberately conspicuous DEBUG-only witness for framebuffer comparison. */
    DIAGNOSTIC_GLOW,
    /** Deliberately conspicuous DEBUG-only witness for framebuffer comparison. */
    DIAGNOSTIC_BLUR,
    /** The low point of the real frame-time pulse; it changes alpha without a new clock. */
    DIAGNOSTIC_INTENSITY_PULSE
}

/** Each family is backed by its real production operation, renderer, and motion id. */
internal enum class VisualEffectsDemoFamily(
    val label: String,
    private val motionId: HaloAnimationId,
    private val effectSpec: HaloEffectSpec
) {
    FULL_CONTOUR("Full Contour", PulseAnimation.id, HaloEffectSpec.FullContour),
    LUMINOUS_SEGMENT("Traveling Segment", SnakeAnimation.id, HaloEffectSpec.LuminousSegment(0.18f)),
    LIGHT_IMPULSE("Light Impulse", ImpulseAnimation.id, HaloEffectSpec.SpecializedField),
    SUPERNOVA("Supernova", SupernovaAnimation.id, HaloEffectSpec.Supernova),
    FORCE_BLADE_AZURE("ForceBlade Azure", AzureBladeAnimation.id, HaloEffectSpec.Blade(BladeVariant.AZURE)),
    FORCE_BLADE_CRIMSON("ForceBlade Crimson", CrimsonBladeAnimation.id, HaloEffectSpec.Blade(BladeVariant.CRIMSON)),
    FORCE_BLADE_CLASH("ForceBlade Clash", ForceClashAnimation.id, HaloEffectSpec.Blade(BladeVariant.CLASH));

    fun config(): HaloConfig = HaloConfig(motion = motionId, effectPlan = effectSpec).sanitized()
}

internal sealed interface VisualEffectsDemoSelection {
    data object Applied : VisualEffectsDemoSelection
    data class Unsupported(val reason: String) : VisualEffectsDemoSelection
}

/**
 * Debug-only bridge between a simple UI and the production [HaloView] Canvas host.
 *
 * The family configuration always rebuilds the real plan and restarts its native motion.
 * The view retains production display-outline geometry, Canvas backend, and lifecycle.
 */
internal class VisualEffectsDemoController(
    context: Context,
    private val container: ViewGroup
) {
    private var config = VisualEffectsDemoFamily.FULL_CONTOUR.config()
    private val haloView = HaloView(context, config)

    var selectedFamily: VisualEffectsDemoFamily = VisualEffectsDemoFamily.FULL_CONTOUR
        private set
    var selectedMode: VisualEffectsDemoMode = VisualEffectsDemoMode.BASELINE
        private set

    init {
        container.addView(
            haloView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        applyPlan(VisualEffectsDemoMode.BASELINE)
    }

    fun selectFamily(family: VisualEffectsDemoFamily) {
        selectedFamily = family
        selectedMode = VisualEffectsDemoMode.BASELINE
        config = family.config()
        haloView.update(config)
        applyPlan(VisualEffectsDemoMode.BASELINE)
    }

    fun select(mode: VisualEffectsDemoMode): VisualEffectsDemoSelection {
        val plan = visualEffectsDemoPlan(selectedFamily, mode)
            ?: return VisualEffectsDemoSelection.Unsupported(unsupportedDescription(selectedFamily, mode))
        selectedMode = mode
        haloView.replaceVisualPlanForDebug(plan)
        restart()
        return VisualEffectsDemoSelection.Applied
    }

    /** Restarts the identical motion at phase zero without changing its timing. */
    fun restart() {
        haloView.repeatAnimation(
            duration = config.durationSeconds,
            interval = config.intervalSeconds,
            count = 0,
            motion = config.motion
        )
    }

    /** Activity hosts call this before removing the view; detach disposes the renderer. */
    fun dispose() {
        haloView.cancelAnimation()
        container.removeView(haloView)
    }

    private fun applyPlan(mode: VisualEffectsDemoMode) {
        check(select(mode) is VisualEffectsDemoSelection.Applied) {
            "Every demo family must admit its baseline plan."
        }
    }
}

/** The D1 contract keeps unsupported combinations explicit instead of substituting another plan. */
internal fun supportsDemoMode(family: VisualEffectsDemoFamily, mode: VisualEffectsDemoMode): Boolean = when (family) {
    VisualEffectsDemoFamily.FULL_CONTOUR,
    VisualEffectsDemoFamily.LUMINOUS_SEGMENT,
    VisualEffectsDemoFamily.SUPERNOVA -> true
    VisualEffectsDemoFamily.LIGHT_IMPULSE,
    VisualEffectsDemoFamily.FORCE_BLADE_AZURE,
    VisualEffectsDemoFamily.FORCE_BLADE_CRIMSON,
    VisualEffectsDemoFamily.FORCE_BLADE_CLASH -> mode in setOf(
        VisualEffectsDemoMode.BASELINE,
        VisualEffectsDemoMode.INTENSITY_PULSE
    )
}

internal fun visualEffectsDemoPlan(
    family: VisualEffectsDemoFamily,
    mode: VisualEffectsDemoMode
): VisualPlanSpec? {
    if (!supportsDemoMode(family, mode)) return null
    val base = HaloEffectVisualPlanMapper.map(family.config()).emitters.single()
    return VisualPlanSpec(listOf(base.copy(modifiers = mode.modifiers)))
}

private val VisualEffectsDemoMode.modifiers: List<VisualModifier>
    get() = when (this) {
        VisualEffectsDemoMode.BASELINE -> emptyList()
        VisualEffectsDemoMode.GLOW -> listOf(VisualModifier.Glow(radiusPx = 18f, alphaMultiplier = 0.75f))
        VisualEffectsDemoMode.BLUR -> listOf(VisualModifier.Blur(radiusPx = 10f))
        VisualEffectsDemoMode.INTENSITY_PULSE -> listOf(VisualModifier.IntensityPulse(amplitude = 0.55f, frequencyHz = 1.2f))
        VisualEffectsDemoMode.COMBINED -> listOf(
            VisualModifier.IntensityPulse(amplitude = 0.55f, frequencyHz = 1.2f),
            VisualModifier.Blur(radiusPx = 10f),
            VisualModifier.Glow(radiusPx = 18f, alphaMultiplier = 0.75f)
        )
        // These are intentionally not production presets. They isolate each real modifier at
        // the largest admitted optical radius (or pulse amplitude) so device captures have an
        // unmistakable paired-output witness without changing release rendering.
        VisualEffectsDemoMode.DIAGNOSTIC_GLOW -> listOf(VisualModifier.Glow(radiusPx = 64f, alphaMultiplier = 1f))
        VisualEffectsDemoMode.DIAGNOSTIC_BLUR -> listOf(VisualModifier.Blur(radiusPx = 64f))
        VisualEffectsDemoMode.DIAGNOSTIC_INTENSITY_PULSE -> listOf(
            VisualModifier.IntensityPulse(amplitude = 1f, frequencyHz = 1f)
        )
    }

internal fun unsupportedDescription(family: VisualEffectsDemoFamily, mode: VisualEffectsDemoMode): String =
    "${mode.label} is not supported for ${family.label}."

internal val VisualEffectsDemoMode.label: String
    get() = when (this) {
        VisualEffectsDemoMode.BASELINE -> "Baseline"
        VisualEffectsDemoMode.GLOW -> "Glow"
        VisualEffectsDemoMode.BLUR -> "Blur"
        VisualEffectsDemoMode.INTENSITY_PULSE -> "Intensity Pulse"
        VisualEffectsDemoMode.COMBINED -> "Full Stack"
        VisualEffectsDemoMode.DIAGNOSTIC_GLOW -> "Diagnostic Glow (64 px)"
        VisualEffectsDemoMode.DIAGNOSTIC_BLUR -> "Diagnostic Blur (64 px)"
        VisualEffectsDemoMode.DIAGNOSTIC_INTENSITY_PULSE -> "Diagnostic Pulse (100%)"
    }
