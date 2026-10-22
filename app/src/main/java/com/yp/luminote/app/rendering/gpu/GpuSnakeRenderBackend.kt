package com.yp.luminote.app.rendering.gpu

import android.graphics.*
import android.util.Log
import com.yp.luminote.app.animation.plan.*
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.geometry.EdgePathGeometryProvider
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.rendering.api.*
import com.yp.luminote.app.rendering.canvas.CanvasRenderTarget
import kotlin.math.roundToInt

/** Gate C executes the existing command, never a second choreography or Canvas animation.
 * Two reusable nodes independently blur raw RuntimeShader pixels, matching Canvas optical order.
 * Each owns its shader/paint so deferred display-list playback cannot observe another pass's alpha.
 * Geometry preparation is bounded to 32MiB + one temporary 32MiB array. At 1440x3120 the data
 * bitmap/upload, temporary preparation and two optical surfaces may approach 85–100MiB. No
 * performance improvement is claimed; the two optical passes preserve distinct Blur/Glow semantics.
 */
internal class GpuSnakeRenderBackend(private val outline: DisplayOutline) : HaloRenderBackend {
    private val generation = GpuPulseEvidence.beginGeneration()
    private val provider = EdgePathGeometryProvider(outline)
    private val commands = FrameCommandBuffer()
    private val failures = GpuPulseFailureLatch<GpuSnakeContourField.Key>()
    private var plan: VisualPlanSpec? = null
    private var field: GpuSnakeContourField? = null
    private var source: Source? = null
    private var glow: Source? = null
    private var epoch = 0
    private var disposed = false
    private var blurEffect: RenderEffect? = null
    private var glowEffect: RenderEffect? = null

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
        if (disposed) return RenderOutcome.Unsupported("GPU Snake backend is disposed")
        val canvas = (target as? CanvasRenderTarget)?.canvas
            ?: return RenderOutcome.Unsupported("GPU Snake requires CanvasRenderTarget")
        if (!canvas.isHardwareAccelerated) return RenderOutcome.Unsupported("GPU Snake requires hardware acceleration")
        val currentPlan = plan ?: return RenderOutcome.Unsupported("GPU Snake has no visual plan")
        FrameCommandCompiler.compile(currentPlan,frame,commands)
        val command = if (commands.size == 1) commands[0] else null
        GpuSnakeCapability.unsupportedReason(frame.intent,frame.mode,commands.size,command)?.let { return RenderOutcome.Unsupported(it) }
        command!!
        val geometry = command.geometry as GeometryRequest.DisplayOutlineContour
        val key = GpuSnakeContourField.key(outline,epoch,frame.viewportWidth,frame.viewportHeight,geometry)
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
                val built = GpuSnakeContourField.build(provider,key)
                    ?: return RenderOutcome.Unsupported("GPU Snake field exceeds bounded size or contour topology is unsupported")
                field = built
                GpuPulseEvidence.recordResourceInitialization(generation,key.hashCode(),epoch)
                built
            }
            if (source == null) {
                stage = "shader-compile"
                GpuPulseEvidence.recordShaderInitializationAttempt(generation)
                val sourceShader = RuntimeShader(SNAKE_SHADER)
                val glowShader = RuntimeShader(SNAKE_SHADER)
                stage = "input-buffer-bind"
                sourceShader.setInputBuffer("field",current.input)
                glowShader.setInputBuffer("field",current.input)
                val nextSource = Source("GpuSnakeSource",sourceShader)
                val nextGlow = Source("GpuSnakeGlow",glowShader)
                stage = "optical-resources"
                val nextBlurEffect = RenderEffect.createBlurEffect(2.75f,2.75f,Shader.TileMode.CLAMP)
                val nextGlowEffect = RenderEffect.createBlurEffect(7.5f,7.5f,Shader.TileMode.CLAMP)
                source = nextSource; glow = nextGlow
                blurEffect = nextBlurEffect; glowEffect = nextGlowEffect
                GpuPulseEvidence.recordShaderInitialization(generation)
            }
            val optical = command.modifiers.any { it is VisualModifier.Blur }
            stage = "source-uniforms-and-recording"
            record(source!!,current,command,command.alpha,if (optical) blurEffect else null)
            if (optical) {
                stage = "glow-uniforms-and-recording"
                record(glow!!,current,command,(command.alpha*.35f).roundToInt(),glowEffect)
            }
            if (disposed || field !== current) return RenderOutcome.Unsupported("stale GPU Snake field rejected")
            stage = "physical-outline-clip"
            save = canvas.save()
            canvas.clipPath(outline.path)
            stage = "frame-submit"
            // drawRenderNode can throw after partially writing: Canvas must never overpaint it.
            submitted = true
            if (optical) canvas.drawRenderNode(glow!!.node)
            canvas.drawRenderNode(source!!.node)
            GpuPulseEvidence.recordSubmission(generation,command.alpha)
            RenderOutcome.Rendered
        } catch (error: RuntimeException) {
            val failure = GpuPulsePreSubmitFailure.at(stage,error,"GPU Snake",if (submitted) "post-submit" else "pre-submit")
            // A post-submit error is not overpainted this frame. The next clean host frame may
            // safely use Canvas without repeating the unchanged failing GPU submission.
            failures.record(key,failure)
            GpuPulseEvidence.recordFirstFailure(generation,key.hashCode(),failure)
            // A stable pre-submit failure is latched above, so this stack is emitted once per attempt.
            Log.e("HaloGpuWitness","GPU Snake failure generation=$generation key=${key.hashCode()} stage=$stage submitted=$submitted",error)
            RenderOutcome.Error(failure,fallbackEligible = !submitted)
        } finally {
            if (save >= 0) runCatching { canvas.restoreToCount(save) }
        }
    }

    private fun record(source: Source, field: GpuSnakeContourField, command: FrameVisualCommand, alpha: Int, effect: RenderEffect?) {
        val shader = source.shader
        shader.setFloatUniform("strokeWidth",field.key.stroke)
        shader.setFloatUniform("contourLength",field.length)
        shader.setFloatUniform("tail",GpuSnakeSourceProfile.wrap(command.phase))
        shader.setFloatUniform("segmentLength",(command.operation as PrimaryVisualOperation.TravelingLuminousSegment).lengthFraction)
        val color = command.appearance.color
        shader.setFloatUniform("color",GpuPulseRenderBackend.red(color),GpuPulseRenderBackend.green(color),GpuPulseRenderBackend.blue(color),alpha/255f)
        source.node.setPosition(0,0,field.key.width,field.key.height)
        source.node.setRenderEffect(effect)
        val recording = source.node.beginRecording(field.key.width,field.key.height)
        try {
            // Canvas's source clips before blur as well as after optics. Preserve both boundaries.
            recording.clipPath(outline.path)
            recording.drawRect(0f,0f,field.key.width.toFloat(),field.key.height.toFloat(),source.paint)
        } finally { source.node.endRecording() }
    }

    private class Source(name: String,val shader: RuntimeShader) {
        val node = RenderNode(name)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = this@Source.shader }
        fun dispose() { node.discardDisplayList(); node.setRenderEffect(null); paint.shader = null }
    }
    private fun releaseResources() {
        source?.dispose(); glow?.dispose(); source = null; glow = null
        blurEffect = null; glowEffect = null
        field?.let { it.dispose(); GpuPulseEvidence.recordResourceDisposal(generation) }; field = null
    }
    override fun dispose() {
        if (!disposed) {
            disposed = true; releaseResources(); failures.clear(); plan = null
            GpuPulseEvidence.recordDisposal(generation)
        }
    }

    companion object {
        const val SNAKE_SHADER = """
            uniform shader field;
            uniform float strokeWidth;
            uniform float contourLength;
            uniform float tail;
            uniform float segmentLength;
            uniform float4 color;
            float progress(float4 v) {
                return (floor(v.g * 255.0 + .5) * 256.0 + floor(v.b * 255.0 + .5)) / 65535.0;
            }
            float unwrap(float a, float reference) { return reference + fract(a-reference+.5)-.5; }
            half4 main(float2 p) {
                float2 base = floor(p-.5)+.5;
                float2 t = fract(p-.5);
                float4 a = float4(field.eval(base));
                float4 b = float4(field.eval(base+float2(1,0)));
                float4 c = float4(field.eval(base+float2(0,1)));
                float4 d = float4(field.eval(base+float2(1,1)));
                float distance = mix(mix(a.r,b.r,t.x),mix(c.r,d.r,t.x),t.y)*24.0;
                if (distance > strokeWidth*.6+2.0) return half4(0);
                float ref = progress(a);
                float q = fract(mix(mix(ref,unwrap(progress(b),ref),t.x),
                    mix(unwrap(progress(c),ref),unwrap(progress(d),ref),t.x),t.y));
                float along = fract(q-tail)*contourLength;
                float segmentPixels = segmentLength*contourLength;
                if (along > segmentPixels && contourLength-along < along-segmentPixels) along -= contourLength;
                float u = clamp(along/segmentPixels,0.0,1.0);
                float body = smoothstep(0.0,.74,u);
                float head = smoothstep(.74,1.0,u);
                float emission = .02+.82*body+.16*head;
                float width = strokeWidth*(.76+.26*body+.10*head);
                float cap = max(max(-along,along-segmentPixels),0.0);
                float sd = length(float2(distance,cap));
                float coverage = 1.0-smoothstep(width*.5-.75,width*.5+.75,sd);
                float alpha = color.a*emission*coverage;
                return half4(half3(color.rgb*alpha),half(alpha));
            }
        """
    }
}
