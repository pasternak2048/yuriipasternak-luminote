package com.yp.luminote.app.effects

import com.yp.luminote.app.data.settings.HaloBladeVariant
import kotlin.math.abs
import kotlin.math.sin

/** Choreography for reusable, retained contour optical primitives. */
internal object GpuEffectPrograms {
    private val HOT_CORE = 0xFFF6FAFF.toInt()
    private val AZURE_BODY = 0xFF0874FF.toInt()
    private val AZURE_BLOOM = 0xFF004CFF.toInt()
    private val CRIMSON_BODY = 0xFFFF0808.toInt()
    private val CRIMSON_BLOOM = 0xFFFF0000.toInt()
    private const val SNAKE_LENGTH = 0.20f
    private const val SOURCE_RADIUS = 0.045f
    private const val IMPULSE_LENGTH = 0.26f
    private const val IMPULSE_TRAVEL = 0.46f
    private const val IGNITION_END = 0.09f
    private const val TRAVEL_END = 0.80f
    private const val CONVERGENCE_END = 0.92f
    private const val BLADE_LENGTH = 0.34f
    private const val BLADE_BLOOM_LENGTH = 0.42f
    private const val BLADE_EXTEND_END = 0.20f
    private const val BLADE_RETRACT_START = 0.70f
    private const val BLADE_FINISH = 0.93f
    private const val AZURE_ORIGIN = 0.08f
    private const val CRIMSON_ORIGIN = 0.58f
    private const val DUEL_APPROACH_END = 0.30f
    private const val DUEL_APPROACH_LENGTH = 0.22f
    private const val DUEL_COLLISION = 0.42f
    private const val DUEL_RETRACT_START = 0.78f
    private const val DUEL_FLASH_END = 0.68f

    private val snakeCore = GpuBand(0.026f, 0.30f, 1f, headTaperFraction = 0.04f, releaseFraction = 0.86f, featherExponent = 2.45f)
    private val snakeBody = GpuBand(0.16f, 0.66f, 0.80f, headTaperFraction = 0.09f, releaseFraction = 0.64f, featherExponent = 1.95f)
    private val snakeBloom = GpuBand(0.24f, 1f, 0.46f, headTaperFraction = 0.14f, releaseFraction = 0.48f, featherExponent = 1.45f)
    private val impulseCore = GpuBand(0.028f, 0.27f, 1f, headTaperFraction = 0.035f, releaseFraction = 0.88f, featherExponent = 2.65f)
    private val impulseBody = GpuBand(0.12f, 0.62f, 0.82f, headTaperFraction = 0.08f, releaseFraction = 0.58f, featherExponent = 2.0f)
    private val impulseBloom = GpuBand(IMPULSE_LENGTH, 1f, 0.56f, headTaperFraction = 0.16f, releaseFraction = 0.38f, featherExponent = 1.35f)
    private val azureCore = GpuBand(0.022f, 0.25f, 1f, HOT_CORE, 0.03f, 0.92f, 3.0f)
    private val azureBody = GpuBand(BLADE_LENGTH, 0.56f, 0.92f, AZURE_BODY, 0.07f, 0.76f, 2.05f)
    private val azureBloom = GpuBand(BLADE_BLOOM_LENGTH, 1f, 0.54f, AZURE_BLOOM, 0.14f, 0.48f, 1.35f)
    private val crimsonCore = GpuBand(0.022f, 0.25f, 1f, HOT_CORE, 0.03f, 0.92f, 3.0f)
    private val crimsonBody = GpuBand(BLADE_LENGTH, 0.56f, 0.92f, CRIMSON_BODY, 0.07f, 0.76f, 2.05f)
    private val crimsonBloom = GpuBand(BLADE_BLOOM_LENGTH, 1f, 0.54f, CRIMSON_BLOOM, 0.14f, 0.48f, 1.35f)

    fun closedPulse(frame: GpuEffectFrame, energy: Float) =
        frame.closedEmitter(energy = energy, width = 0.82f, featherExponent = 1.65f)

    fun snake(frame: GpuEffectFrame, head: Float, energy: Float) =
        frame.directedBeam(head, 1, SNAKE_LENGTH, snakeCore, snakeBody, snakeBloom, energyScale = energy)

    /** One top ignition opens to symmetric branches and ends in a single bottom flash. */
    fun impulse(frame: GpuEffectFrame, phase: Float, origin: Float, convergence: Float, energy: Float) {
        val t = phase.normalized()
        when {
            t < IGNITION_END -> frame.localizedSource(origin, SOURCE_RADIUS, energy * (0.50f + 0.50f * (t / IGNITION_END).smooth()), featherExponent = 2.4f)
            t < TRAVEL_END -> {
                val travel = ((t - IGNITION_END) / (TRAVEL_END - IGNITION_END)).smooth()
                frame.localizedSource(origin, SOURCE_RADIUS, energy * (1f - travel) * 0.62f, featherExponent = 2.25f)
                movingImpulseBranches(frame, origin, travel, energy * (0.36f + 0.64f * travel))
            }
            t < CONVERGENCE_END -> {
                val converge = ((t - TRAVEL_END) / (CONVERGENCE_END - TRAVEL_END)).smooth()
                // Branches have completed: no stale tail can wrap back toward the source.
                frame.localizedSource(convergence, SOURCE_RADIUS * (1.15f + 0.85f * converge), energy * (0.70f + 0.30f * converge), featherExponent = 1.75f)
            }
            else -> {
                val fade = 1f - ((t - CONVERGENCE_END) / (1f - CONVERGENCE_END)).smooth()
                frame.localizedSource(convergence, SOURCE_RADIUS * (1.35f + 0.55f * fade), energy * fade, featherExponent = 1.55f)
            }
        }
    }

    fun laser(frame: GpuEffectFrame, phase: Float, variant: HaloBladeVariant, energy: Float) {
        when (variant) {
            HaloBladeVariant.AZURE -> singleBlade(frame, phase, AZURE_ORIGIN, azureCore, azureBody, azureBloom, energy, 1f)
            HaloBladeVariant.CRIMSON -> singleBlade(frame, phase, CRIMSON_ORIGIN, crimsonCore, crimsonBody, crimsonBloom, energy, crimsonFlicker(phase))
            HaloBladeVariant.CLASH -> duel(frame, phase, energy)
        }
    }

    private fun movingImpulseBranches(frame: GpuEffectFrame, source: Float, travel: Float, energy: Float) {
        val spread = IMPULSE_TRAVEL * travel
        frame.directedBeam((source + spread).normalized(), 1, IMPULSE_LENGTH, impulseCore, impulseBody, impulseBloom, energyScale = energy)
        frame.directedBeam((source - spread).normalized(), -1, IMPULSE_LENGTH, impulseCore, impulseBody, impulseBloom, energyScale = energy)
    }

    private fun singleBlade(frame: GpuEffectFrame, phase: Float, origin: Float, core: GpuBand, body: GpuBand, bloom: GpuBand, energy: Float, flicker: Float) {
        val t = phase.normalized()
        val extent = when {
            t < BLADE_EXTEND_END -> (t / BLADE_EXTEND_END).easeOut()
            t < BLADE_RETRACT_START -> 1f
            t < BLADE_FINISH -> 1f - ((t - BLADE_RETRACT_START) / (BLADE_FINISH - BLADE_RETRACT_START)).smooth()
            else -> 0f
        }
        if (extent > 0f) {
            frame.directedBeam((origin + BLADE_LENGTH * extent).normalized(), 1, BLADE_LENGTH * extent, core, body, bloom, energyScale = energy * flicker)
        } else {
            frame.localizedSource(origin, SOURCE_RADIUS * 0.76f, energy * (1f - ((t - BLADE_FINISH) / (1f - BLADE_FINISH)).smooth()), color = body.color, featherExponent = 1.7f)
        }
    }

    private fun duel(frame: GpuEffectFrame, phase: Float, energy: Float) {
        val t = phase.normalized()
        if (t < DUEL_APPROACH_END) {
            val approach = (t / DUEL_APPROACH_END).easeOut() * DUEL_APPROACH_LENGTH
            frame.directedBeam((AZURE_ORIGIN + approach).normalized(), 1, approach, azureCore, azureBody, azureBloom, energyScale = energy)
            frame.directedBeam((AZURE_ORIGIN - approach).normalized(), -1, approach, azureCore, azureBody, azureBloom, energyScale = energy)
            frame.directedBeam((CRIMSON_ORIGIN + approach).normalized(), 1, approach, crimsonCore, crimsonBody, crimsonBloom, energyScale = energy * crimsonFlicker(t))
            frame.directedBeam((CRIMSON_ORIGIN - approach).normalized(), -1, approach, crimsonCore, crimsonBody, crimsonBloom, energyScale = energy * crimsonFlicker(t))
            return
        }
        val retract = if (t < DUEL_RETRACT_START) 1f else 1f - ((t - DUEL_RETRACT_START) / (1f - DUEL_RETRACT_START)).smooth()
        // Adjacent territories meet at a white impact; the blades never occupy a mixed-color lobe.
        frame.directedBeam(DUEL_COLLISION, 1, (DUEL_COLLISION - AZURE_ORIGIN) * retract, azureCore, azureBody, azureBloom, energyScale = energy)
        frame.directedBeam(DUEL_COLLISION, -1, (CRIMSON_ORIGIN - DUEL_COLLISION) * retract, crimsonCore, crimsonBody, crimsonBloom, energyScale = energy * crimsonFlicker(t))
        if (t < DUEL_FLASH_END) frame.localizedSource(DUEL_COLLISION, SOURCE_RADIUS * (1.35f - 0.25f * retract), energy, color = HOT_CORE, featherExponent = 1.45f)
    }

    private fun crimsonFlicker(phase: Float): Float = if (phase < 0.13f) 0.62f + 0.38f * abs(sin(phase * 141.37f)) else 0.96f + 0.04f * abs(sin(phase * 41f))
    private fun Float.smooth(): Float { val t = coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
    private fun Float.easeOut(): Float { val t = coerceIn(0f, 1f); return 1f - (1f - t) * (1f - t) }
    private fun Float.normalized(): Float = ((this % 1f) + 1f) % 1f

}
