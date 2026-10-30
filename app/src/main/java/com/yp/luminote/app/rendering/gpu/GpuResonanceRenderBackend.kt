package com.yp.luminote.app.rendering.gpu

import android.graphics.*
import com.yp.luminote.app.animation.plan.*
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.geometry.EdgePathGeometryProvider
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.rendering.api.*
import com.yp.luminote.app.rendering.canvas.CanvasRenderTarget
import com.yp.luminote.app.resonance.*

/** Strict executor: shader-generated source/optics, existing hardware RenderNode submission only. */
internal class GpuResonanceRenderBackend(private val outline: DisplayOutline, private val generation: Long) : HaloRenderBackend {
    private val provider = EdgePathGeometryProvider(outline)
    private val commands = FrameCommandBuffer()
    private val behavior = ResonanceBehavior()
    private var plan: VisualPlanSpec? = null
    private var field: GpuSnakeContourField? = null
    private var shader: RuntimeShader? = null
    private var node: RenderNode? = null
    private var paint: Paint? = null
    private var epoch = 0
    private var disposed = false
    private var failed = false

    override fun updateConfig(config: HaloConfig) {
        if (disposed) return
        plan = VisualPlanSpec(listOf(VisualEmitterSpec(
            geometry = GeometryRequest.DisplayOutlineContour(config.thickness, config.frame,
                config.edgeCalibrationDp, config.cornerCalibrationDp, config.cornerShape),
            appearance = AppearancePayload(config.color, 1f, config.thickness, config.colorMode, emptyList()),
            operation = PrimaryVisualOperation.ResonanceFluidContour)))
    }
    override fun invalidateSurface() { if (!disposed) { epoch++; releaseResources() } }
    override fun render(target: HaloRenderTarget, frame: RenderFrame): RenderOutcome {
        if (disposed || failed || !ResonanceRuntime.isCurrent(generation)) return RenderOutcome.Unsupported("Resonance GPU session ended")
        val canvas = (target as? CanvasRenderTarget)?.canvas ?: return RenderOutcome.Unsupported("GPU target unavailable")
        if (!canvas.isHardwareAccelerated) return RenderOutcome.Unsupported("Resonance requires hardware GPU rendering")
        val currentPlan = plan ?: return RenderOutcome.Unsupported("Resonance visual plan unavailable")
        FrameCommandCompiler.compile(currentPlan, frame, commands)
        val command = commands[0]
        if (commands.size != 1 || command.operation != PrimaryVisualOperation.ResonanceFluidContour) return RenderOutcome.Unsupported("Invalid Resonance operation")
        val key = GpuSnakeContourField.key(outline, epoch, frame.viewportWidth, frame.viewportHeight,
            command.geometry as GeometryRequest.DisplayOutlineContour)
        var save = -1
        return try {
            val current = field?.takeIf { it.key == key } ?: run {
                releaseResources()
                val built = GpuSnakeContourField.build(provider, key) ?: return RenderOutcome.Unsupported("Display geometry exceeds Resonance GPU budget")
                field = built
                built
            }
            if (shader == null) {
                shader = RuntimeShader(FLUID_SHADER).also { it.setInputBuffer("field", current.input) }
                paint = Paint().apply { shader = this@GpuResonanceRenderBackend.shader }
                node = RenderNode("ResonanceFluidContour")
            }
            behavior.update(command.resonanceFeatures, generation, frame.frameTimeNanos)
            shader!!.setFloatUniform("audio", behavior.low, behavior.mid, behavior.high, behavior.loudness)
            shader!!.setFloatUniform("spectrum0", behavior.band(0), behavior.band(1), behavior.band(2), behavior.band(3))
            shader!!.setFloatUniform("spectrum1", behavior.band(4), behavior.band(5), behavior.band(6), behavior.band(7))
            shader!!.setFloatUniform("spectrum2", behavior.band(8), behavior.band(9), behavior.band(10), behavior.band(11))
            shader!!.setFloatUniform("motion", behavior.phase, behavior.impulse, behavior.buildup,
                ResonanceController.state.value.intensity)
            shader!!.setFloatUniform("strokeWidth", key.stroke)
            node!!.setPosition(0, 0, key.width, key.height)
            val recording = node!!.beginRecording(key.width, key.height)
            try {
                recording.clipPath(outline.path)
                recording.drawRect(0f, 0f, key.width.toFloat(), key.height.toFloat(), paint!!)
            } finally { node!!.endRecording() }
            save = canvas.save()
            canvas.clipPath(outline.path)
            canvas.drawRenderNode(node!!)
            ResonanceRuntime.gpuFrames++
            RenderOutcome.Rendered
        } catch (error: RuntimeException) {
            failed = true
            RenderOutcome.Error(error, fallbackEligible = false)
        } finally { if (save >= 0) canvas.restoreToCount(save) }
    }
    private fun releaseResources() {
        node?.discardDisplayList(); node = null
        paint?.shader = null; paint = null; shader = null
        field?.dispose(); field = null
    }
    override fun dispose() { if (!disposed) { disposed = true; releaseResources(); plan = null } }

    companion object {
        const val FLUID_SHADER = """
            uniform shader field;
            uniform float4 audio;
            uniform float4 motion;
            uniform float4 spectrum0;
            uniform float4 spectrum1;
            uniform float4 spectrum2;
            uniform float strokeWidth;

            float progress(float4 v) {
                return (floor(v.g * 255.0 + .5) * 256.0 + floor(v.b * 255.0 + .5)) / 65535.0;
            }
            float unwrap(float a, float r) { return r + fract(a - r + .5) - .5; }

            half4 main(float2 p) {
                float2 base = floor(p - .5) + .5;
                float2 t = fract(p - .5);
                float4 a = float4(field.eval(base));
                if (a.r * 24.0 > 22.0) return half4(0);
                float4 b = float4(field.eval(base + float2(1.0, 0.0)));
                float4 c = float4(field.eval(base + float2(0.0, 1.0)));
                float4 d = float4(field.eval(base + float2(1.0, 1.0)));
                float distance = mix(mix(a.r, b.r, t.x), mix(c.r, d.r, t.x), t.y) * 24.0;
                float r = progress(a);
                float q = fract(mix(mix(r, unwrap(progress(b), r), t.x),
                    mix(unwrap(progress(c), r), unwrap(progress(d), r), t.x), t.y));

                // Fixed, overlapping frequency ownership. The palette never travels.
                float w0 = (1.0 - smoothstep(0.030, 0.128, abs(fract(q - 0.0416667 + 0.5) - 0.5))) * clamp(spectrum0.x, 0.0, 1.0);
                float w1 = (1.0 - smoothstep(0.030, 0.128, abs(fract(q - 0.1250000 + 0.5) - 0.5))) * clamp(spectrum0.y, 0.0, 1.0);
                float w2 = (1.0 - smoothstep(0.030, 0.128, abs(fract(q - 0.2083333 + 0.5) - 0.5))) * clamp(spectrum0.z, 0.0, 1.0);
                float w3 = (1.0 - smoothstep(0.030, 0.128, abs(fract(q - 0.2916667 + 0.5) - 0.5))) * clamp(spectrum0.w, 0.0, 1.0);
                float w4 = (1.0 - smoothstep(0.030, 0.128, abs(fract(q - 0.3750000 + 0.5) - 0.5))) * clamp(spectrum1.x, 0.0, 1.0);
                float w5 = (1.0 - smoothstep(0.030, 0.128, abs(fract(q - 0.4583333 + 0.5) - 0.5))) * clamp(spectrum1.y, 0.0, 1.0);
                float w6 = (1.0 - smoothstep(0.030, 0.128, abs(fract(q - 0.5416667 + 0.5) - 0.5))) * clamp(spectrum1.z, 0.0, 1.0);
                float w7 = (1.0 - smoothstep(0.030, 0.128, abs(fract(q - 0.6250000 + 0.5) - 0.5))) * clamp(spectrum1.w, 0.0, 1.0);
                float w8 = (1.0 - smoothstep(0.030, 0.128, abs(fract(q - 0.7083333 + 0.5) - 0.5))) * clamp(spectrum2.x, 0.0, 1.0);
                float w9 = (1.0 - smoothstep(0.030, 0.128, abs(fract(q - 0.7916667 + 0.5) - 0.5))) * clamp(spectrum2.y, 0.0, 1.0);
                float w10 = (1.0 - smoothstep(0.030, 0.128, abs(fract(q - 0.8750000 + 0.5) - 0.5))) * clamp(spectrum2.z, 0.0, 1.0);
                float w11 = (1.0 - smoothstep(0.030, 0.128, abs(fract(q - 0.9583333 + 0.5) - 0.5))) * clamp(spectrum2.w, 0.0, 1.0);
                float spectral = w0 + w1 + w2 + w3 + w4 + w5 + w6 + w7 + w8 + w9 + w10 + w11;
                float3 weightedColor =
                    float3(0.02,0.88,0.77) * w0 +
                    float3(0.02,0.88,0.77) * w1 +
                    float3(0.02,0.88,0.77) * w2 +
                    float3(0.02,0.88,0.77) * w3 +
                    float3(0.72,0.14,0.98) * w4 +
                    float3(0.72,0.14,0.98) * w5 +
                    float3(0.72,0.14,0.98) * w6 +
                    float3(0.72,0.14,0.98) * w7 +
                    float3(1,0.58,0.18) * w8 +
                    float3(1,0.58,0.18) * w9 +
                    float3(1,0.58,0.18) * w10 +
                    float3(1,0.58,0.18) * w11;
                float3 tint = weightedColor / max(spectral, .00001);

                float bass = clamp(audio.x, 0.0, 1.0);
                float loudness = clamp(audio.w, 0.0, 1.0);
                float impulse = clamp(motion.y, 0.0, 1.0);
                float intensity = clamp(motion.w, 0.0, 1.0);
                const float TAU = 6.283185;

                // Local moving pockets are deliberately nonuniform, even at full gain.
                // Integer phase windings remain seamless across phase 1 -> 0.
                float waveA = .5 + .5 * sin(TAU * (q * 7.0 - motion.x * 2.0));
                float waveB = .5 + .5 * sin(TAU * (q * 13.0 + motion.x));
                float texture = .10 + .64 * pow(waveA, 4.0) + .35 * pow(waveB, 5.0);
                float travel = fract(q - motion.x);
                float beat = exp(-pow(min(travel, 1.0 - travel) * 15.0, 2.0)) * impulse;
                float localEnergy = clamp(spectral * .90, 0.0, 1.0);
                float active = smoothstep(.018, .75, localEnergy);
                float peak = active * (texture + beat * .65);

                // Strong local width swelling, but never deform the actual contour.
                // Broader optical source. The wave swells into the 24px field rather
                // than painting a uniform opaque stroke around the display.
                float width = clamp(strokeWidth * (.24 + .42 * bass) +
                    peak * (5.0 + 6.0 * bass), 1.5, 18.0);
                float outside = max(0.0, distance - width * .5);
                float core = 1.0 - smoothstep(width * .5 - 2.2, width * .5 + 3.0, distance);
                float nearHalo = exp(-outside / (3.8 + 1.8 * localEnergy)) * .78;
                float outerHalo = exp(-outside / (7.0 + 2.0 * localEnergy)) * .68;
                float fade = 1.0 - smoothstep(18.0, 21.5, distance);

                // Stronger hot spots, but local low-energy gaps stay dark.
                float light = (core * .75 + nearHalo + outerHalo) *
                    pow(max(peak, 0.0), 1.25) *
                    (.42 + .58 * loudness) * intensity * 3.0;
                float alpha = (1.0 - exp(-light * 1.35)) * fade;
                return half4(half3(tint * alpha), half(alpha));
            }
        """
    }
}
