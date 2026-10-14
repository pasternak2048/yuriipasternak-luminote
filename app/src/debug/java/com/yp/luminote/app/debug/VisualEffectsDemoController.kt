package com.yp.luminote.app.debug

import android.content.Context
import android.view.ViewGroup
import com.yp.luminote.app.animation.definitions.HaloEffectSpec
import com.yp.luminote.app.animation.definitions.PulseAnimation
import com.yp.luminote.app.animation.plan.HaloEffectVisualPlanMapper
import com.yp.luminote.app.animation.plan.PrimaryVisualOperation
import com.yp.luminote.app.animation.plan.VisualModifier
import com.yp.luminote.app.animation.plan.VisualPlanSpec
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.overlay.host.HaloView

/** The five fixed comparison states exposed by the debug-only visual-effects demo. */
internal enum class VisualEffectsDemoMode {
    BASELINE,
    GLOW,
    BLUR,
    INTENSITY_PULSE,
    COMBINED
}

/**
 * Debug-only bridge between a simple UI and the production [HaloView] Canvas host.
 *
 * All modes derive from one immutable FullContourPulse plan, so only the admitted
 * modifier list differs. The view retains production display-outline geometry,
 * Canvas backend, lifecycle cleanup, and animation engine.
 */
internal class VisualEffectsDemoController(
    context: Context,
    private val container: ViewGroup
) {
    private val config = HaloConfig(
        motion = PulseAnimation.id,
        effectPlan = HaloEffectSpec.FullContour
    ).sanitized()

    private val haloView = HaloView(context, config)
    private val plans = visualEffectsDemoPlans(config)

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
        select(VisualEffectsDemoMode.BASELINE)
    }

    fun select(mode: VisualEffectsDemoMode) {
        selectedMode = mode
        haloView.replaceVisualPlanForDebug(plans.getValue(mode))
        restart()
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

}

internal fun visualEffectsDemoPlans(config: HaloConfig): Map<VisualEffectsDemoMode, VisualPlanSpec> {
    val base = HaloEffectVisualPlanMapper.map(config).emitters.single()
    check(base.operation is PrimaryVisualOperation.FullContourPulse) {
        "The visual-effects demo requires FullContourPulse."
    }
    return mapOf(
        VisualEffectsDemoMode.BASELINE to VisualPlanSpec(listOf(base)),
        VisualEffectsDemoMode.GLOW to planWith(base, listOf(VisualModifier.Glow(radiusPx = 18f, alphaMultiplier = 0.75f))),
        VisualEffectsDemoMode.BLUR to planWith(base, listOf(VisualModifier.Blur(radiusPx = 10f))),
        VisualEffectsDemoMode.INTENSITY_PULSE to planWith(base, listOf(VisualModifier.IntensityPulse(amplitude = 0.55f, frequencyHz = 1.2f))),
        VisualEffectsDemoMode.COMBINED to planWith(
            base,
            listOf(
                VisualModifier.IntensityPulse(amplitude = 0.55f, frequencyHz = 1.2f),
                VisualModifier.Blur(radiusPx = 10f),
                VisualModifier.Glow(radiusPx = 18f, alphaMultiplier = 0.75f)
            )
        )
    )
}

private fun planWith(
    base: com.yp.luminote.app.animation.plan.VisualEmitterSpec,
    modifiers: List<VisualModifier>
): VisualPlanSpec = VisualPlanSpec(listOf(base.copy(modifiers = modifiers)))
