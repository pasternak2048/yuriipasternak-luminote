package com.yp.luminote.app.rendering.gpu

import android.graphics.Paint
import android.graphics.PathMeasure
import android.graphics.RuntimeShader
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.util.Log
import com.yp.luminote.app.animation.lightImpulseTravelProgress
import com.yp.luminote.app.animation.LightImpulseEndpoints
import com.yp.luminote.app.animation.plan.*
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.geometry.EdgePathGeometryProvider
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.rendering.api.*
import com.yp.luminote.app.rendering.canvas.CanvasRenderTarget
import com.yp.luminote.app.rendering.canvas.lightImpulseEndpoints

/** D1 source only. The shared contour data is cached; RuntimeShader owns all pixel rendering. */
internal class GpuImpulseRenderBackend(private val outline: DisplayOutline) : HaloRenderBackend {
    private val generation = GpuPulseEvidence.beginGeneration()
    private val provider = EdgePathGeometryProvider(outline)
    private val commands = FrameCommandBuffer()
    private val failures = GpuPulseFailureLatch<GpuSnakeContourField.Key>()
    private val profile = GpuImpulseSourceProfile()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var plan: VisualPlanSpec? = null
    private var field: GpuSnakeContourField? = null
    private var endpoints: LightImpulseEndpoints? = null
    private var shader: RuntimeShader? = null
    private var glowShader: RuntimeShader? = null
    private var sourceNode: RenderNode? = null
    private var glowNode: RenderNode? = null
    private var blurEffect: RenderEffect? = null
    private var glowEffect: RenderEffect? = null
    private var epoch = 0
    private var disposed = false

    internal fun generationForDebugEvidence() = generation
    override fun updateConfig(config: HaloConfig) {
        if (!disposed) { plan = HaloEffectVisualPlanMapper.map(config); failures.clear() }
    }
    override fun updateDebugVisualPlan(plan: VisualPlanSpec) {
        if (!disposed) { this.plan = plan; failures.clear() }
    }
    override fun invalidateSurface() {
        if (!disposed) {
            epoch++; releaseResources(); failures.clear()
            GpuPulseEvidence.recordSurfaceInvalidation(generation, epoch)
        }
    }

    override fun render(target: HaloRenderTarget, frame: RenderFrame): RenderOutcome {
        if (disposed) return RenderOutcome.Unsupported("GPU Impulse backend is disposed")
        val canvas = (target as? CanvasRenderTarget)?.canvas
            ?: return RenderOutcome.Unsupported("GPU Impulse requires CanvasRenderTarget")
        if (!canvas.isHardwareAccelerated) return RenderOutcome.Unsupported("GPU Impulse requires hardware acceleration")
        val currentPlan = plan ?: return RenderOutcome.Unsupported("GPU Impulse has no visual plan")
        FrameCommandCompiler.compile(currentPlan, frame, commands)
        val command = if (commands.size == 1) commands[0] else null
        GpuImpulseCapability.unsupportedReason(frame.intent, frame.mode, commands.size, command,
            currentPlan.emitters.singleOrNull()?.modifierProfile == VisualModifierProfile.NORMAL_IMPULSE)
            ?.let { return RenderOutcome.Unsupported(it) }
        command!!
        val key = GpuSnakeContourField.key(outline, epoch, frame.viewportWidth, frame.viewportHeight,
            command.geometry as GeometryRequest.DisplayOutlineContour)
        failures.failureFor(key)?.let {
            GpuPulseEvidence.recordLatchedFailure(generation)
            return RenderOutcome.Error(it)
        }
        GpuPulseEvidence.recordPreSubmitAttempt(generation)
        var stage = "geometry-field"
        var submitted = false
        var save = -1
        return try {
            val current = field?.takeIf { it.key == key } ?: run {
                releaseResources()
                val built = GpuSnakeContourField.build(provider, key)
                    ?: return RenderOutcome.Unsupported("GPU Impulse field exceeds bounded size or contour topology is unsupported")
                field = built
                val geometry = provider.geometryFor(key.stroke, key.edgePx, key.cornerPx, key.shape, 0f)!!
                // Same calibrated top/bottom anchors and 960-sample search as the Canvas source.
                // Field topology validation advances its borrowed PathMeasure to EOF. Use an
                // independent cursor over the same authoritative path, only at resource creation.
                endpoints = lightImpulseEndpoints(PathMeasure(geometry.path, false), built.length, geometry.bounds)
                GpuPulseEvidence.recordResourceInitialization(generation, key.hashCode(), epoch)
                built
            }
            val activeShader = shader ?: run {
                stage = "shader-compile"
                GpuPulseEvidence.recordShaderInitializationAttempt(generation)
                val compiled = RuntimeShader(IMPULSE_SHADER)
                stage = "input-buffer-bind"
                compiled.setInputBuffer("field", current.input)
                stage = "paint-attach"
                paint.shader = compiled
                shader = compiled
                GpuPulseEvidence.recordShaderInitialization(generation)
                compiled
            }
            stage = "impulse-profile-uniforms"
            profile.update(command.phase, current.length, endpoints!!)
            val optical = command.modifiers == GpuImpulseCapability.productionModifiers
            if (optical && glowShader == null) {
                stage = "optical-resources"
                glowShader = RuntimeShader(IMPULSE_SHADER).also { it.setInputBuffer("field", current.input) }
                sourceNode = RenderNode("GpuImpulseSource")
                glowNode = RenderNode("GpuImpulseGlow")
                blurEffect = RenderEffect.createBlurEffect(3.5f, 3.5f, Shader.TileMode.CLAMP)
                glowEffect = RenderEffect.createBlurEffect(7f, 7f, Shader.TileMode.CLAMP)
            }
            stage = "impulse-profile-uniforms"
            updateUniforms(activeShader, current, command, 1f)
            if (optical) {
                updateUniforms(glowShader!!, current, command, .42f)
                stage = "optical-recording"
                record(sourceNode!!, activeShader, current, blurEffect!!)
                record(glowNode!!, glowShader!!, current, glowEffect!!)
            }
            stage = "physical-outline-clip"
            save = canvas.save()
            canvas.clipPath(outline.path)
            stage = "frame-submit"
            submitted = true
            if (optical) {
                canvas.drawRenderNode(glowNode!!)
                canvas.drawRenderNode(sourceNode!!)
            } else canvas.drawRect(0f, 0f, key.width.toFloat(), key.height.toFloat(), paint)
            GpuPulseEvidence.recordSubmission(generation, 255)
            RenderOutcome.Rendered
        } catch (error: RuntimeException) {
            val failure = GpuPulsePreSubmitFailure.at(stage, error, "GPU Impulse", if (submitted) "post-submit" else "pre-submit")
            failures.record(key, failure)
            GpuPulseEvidence.recordFirstFailure(generation, key.hashCode(), failure)
            Log.e("HaloGpuWitness", "GPU Impulse failure generation=$generation key=${key.hashCode()} stage=$stage submitted=$submitted", error)
            RenderOutcome.Error(failure, fallbackEligible = !submitted)
        } finally {
            if (save >= 0) runCatching { canvas.restoreToCount(save) }
        }
    }

    private fun updateUniforms(activeShader: RuntimeShader, current: GpuSnakeContourField, command: FrameVisualCommand, multiplier: Float) {
            activeShader.setFloatUniform("anchors", endpoints!!.topFraction * current.length, endpoints!!.bottomFraction * current.length,
                endpoints!!.forwardDistance, endpoints!!.reverseDistance)
            activeShader.setFloatUniform("timing", profile.progress, current.length, lightImpulseTravelProgress(profile.progress), profile.plan.mode.ordinal.toFloat())
            activeShader.setFloatUniform("energy", profile.energy.originBloom, profile.energy.tailPerBranch, profile.energy.corePerBranch, profile.energy.bottomBloom)
            activeShader.setFloatUniform("footprint", profile.plan.forwardFieldLength, profile.plan.reverseFieldLength, profile.plan.sourceBloomRadius)
            activeShader.setFloatUniform("strokeWidth", current.key.stroke)
            val color = command.appearance.color
            // LightImpulse owns its alpha. Match Canvas: do not multiply by host fade twice.
            activeShader.setFloatUniform("color", GpuPulseRenderBackend.red(color),
                GpuPulseRenderBackend.green(color), GpuPulseRenderBackend.blue(color),
                command.appearance.intensity * command.intensityPulseMultiplier * multiplier)
    }

    private fun record(node: RenderNode, runtimeShader: RuntimeShader, current: GpuSnakeContourField, effect: RenderEffect) {
        node.setPosition(0, 0, current.key.width, current.key.height)
        node.setRenderEffect(effect)
        val recording = node.beginRecording(current.key.width, current.key.height)
        try {
            recording.clipPath(outline.path)
            paint.shader = runtimeShader
            recording.drawRect(0f, 0f, current.key.width.toFloat(), current.key.height.toFloat(), paint)
        } finally {
            node.endRecording()
            paint.shader = shader
        }
    }

    private fun releaseResources() {
        sourceNode?.discardDisplayList(); glowNode?.discardDisplayList()
        sourceNode?.setRenderEffect(null); glowNode?.setRenderEffect(null)
        sourceNode = null; glowNode = null; glowShader = null; blurEffect = null; glowEffect = null
        paint.shader = null; shader = null; endpoints = null
        field?.let { it.dispose(); GpuPulseEvidence.recordResourceDisposal(generation) }; field = null
    }
    override fun dispose() {
        if (!disposed) {
            disposed = true; releaseResources(); failures.clear(); plan = null
            GpuPulseEvidence.recordDisposal(generation)
        }
    }

    companion object {
        const val IMPULSE_SHADER = """
            uniform shader field;
            uniform float strokeWidth;
            uniform float4 anchors;
            uniform float4 timing;
            uniform float4 energy;
            uniform float3 footprint;
            uniform float4 color;
            float eased(float v) { float t=clamp(v,0.0,1.0); return t*t*(3.0-2.0*t); }
            float tip(float u) { return eased(u/.22)*(1.0-eased((u-.20)/.80)); }
            float2 sharedBloom(float q, float strength, bool terminal) {
                float s=max(1.0-q*q,0.0); s*=s;
                float e=strength*s;
                return float2(e,.70+1.20*(terminal ? s : e));
            }
            float2 bloom(float q, float strength) {
                float s=max(1.0-q*q,0.0); s*=s;
                return float2(strength*(.025+.58*s),.72+1.75*s);
            }
            float2 birth(float q, float radius) {
                float source=0.0;
                if(radius>0.0 && q<=radius) { float d=q/radius; float s=max(1.0-d*d,0.0); source=energy.x*s*s; }
                float d=radius<1.0 ? clamp((q-radius)/(1.0-radius),0.0,1.0) : 0.0;
                float activation=radius>0.0 ? eased((1.0-radius)/radius) : 1.0;
                float e=clamp(source+tip(1.0-d)*energy.z*activation,0.0,1.0);
                return float2(e,.70+1.20*e);
            }
            float2 flow(float behind, float tailLength, float organic) {
                if(behind<0.0 || tailLength<=0.0) return float2(0);
                float coreSpan=min(timing.y*.022,tailLength*.60)*4.0;
                if(behind<coreSpan && coreSpan>0.0) {
                    float e=tip(behind/coreSpan);
                    return float2(energy.z*organic*e,(.70+1.20*e)*(.58+.42*energy.z));
                }
                float body=max(tailLength-coreSpan,0.0)*.55;
                if(body<=0.0 || behind>=coreSpan+body) return float2(0);
                float p=clamp((behind-coreSpan)/body,0.0,1.0);
                float retained=1.0-p;
                float e=clamp(1.10*retained*retained*retained*eased(p/.24),0.0,1.0);
                return float2(energy.y*.46*e*organic,(.46+.76*e)*(.60+.40*energy.y));
            }
            float2 sourceAt(float q) {
                if(timing.x>=1.0) return float2(0);
                float forward=fract(q-anchors.x/timing.y)*timing.y;
                float reverse=timing.y-forward;
                bool positive=forward<=anchors.z;
                float distance=positive ? forward : reverse;
                float maximum=positive ? anchors.z : anchors.w;
                float fieldLength=positive ? footprint.x : footprint.y;
                if(timing.w<.5) {
                    float local=min(forward,reverse);
                    return local<=footprint.z && footprint.z>0.0 ? sharedBloom(local/footprint.z,energy.x,false) : float2(0);
                }
                if(timing.w<1.5) {
                    return distance<=fieldLength && fieldLength>0.0 ? birth(distance/fieldLength,footprint.z/fieldLength) : float2(0);
                }
                if(timing.w<2.5) {
                    float radius=timing.y*(.018+energy.x*.020);
                    float local=min(forward,reverse);
                    if(energy.x>0.0 && local<radius) return bloom(local/radius,energy.x);
                    return flow(maximum*timing.z-distance,fieldLength,1.0);
                }
                if(timing.w<3.5) {
                    float local=abs(distance-maximum);
                    if(footprint.z>0.0 && local<footprint.z) return bloom(local/footprint.z,energy.w);
                    float direction=positive ? -1.0 : 1.0;
                    return flow(local-footprint.z,max(fieldLength-footprint.z,0.0),.94+.06*sin(timing.x*19.0+direction*.7));
                }
                float local=abs(fract(q-anchors.y/timing.y+.5)-.5)*timing.y;
                return footprint.z>0.0 && local<=footprint.z ? sharedBloom(local/footprint.z,energy.w,true) : float2(0);
            }
            float progress(float4 v) {
                return (floor(v.g*255.0+.5)*256.0+floor(v.b*255.0+.5))/65535.0;
            }
            float unwrap(float a, float reference) { return reference+fract(a-reference+.5)-.5; }
            half4 main(float2 p) {
                float2 base = floor(p-.5)+.5;
                float2 t = fract(p-.5);
                float4 a = float4(field.eval(base));
                float4 b = float4(field.eval(base+float2(1,0)));
                float4 c = float4(field.eval(base+float2(0,1)));
                float4 d = float4(field.eval(base+float2(1,1)));
                float distance = mix(mix(a.r,b.r,t.x),mix(c.r,d.r,t.x),t.y)*24.0;
                if (distance > strokeWidth*1.4+2.0) return half4(0);
                float ref = progress(a);
                float q = fract(mix(mix(ref,unwrap(progress(b),ref),t.x),
                    mix(unwrap(progress(c),ref),unwrap(progress(d),ref),t.x),t.y));
                float2 source = sourceAt(q);
                float halfWidth = strokeWidth*source.y*.5;
                float coverage = 1.0-smoothstep(halfWidth-.75,halfWidth+.75,distance);
                float alpha = color.a*source.x*coverage;
                return half4(half3(color.rgb*alpha),half(alpha));
            }
        """
    }
}
