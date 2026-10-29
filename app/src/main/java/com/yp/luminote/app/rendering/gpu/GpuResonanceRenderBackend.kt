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
            uniform float strokeWidth;
            float progress(float4 v) {
                return (floor(v.g*255.0+.5)*256.0+floor(v.b*255.0+.5))/65535.0;
            }
            float unwrap(float a, float r) { return r+fract(a-r+.5)-.5; }
            half4 main(float2 p) {
                float2 base=floor(p-.5)+.5;
                float2 t=fract(p-.5);
                float4 a=float4(field.eval(base));
                if (a.r*24.0>22.0) return half4(0);
                float4 b=float4(field.eval(base+float2(1,0)));
                float4 c=float4(field.eval(base+float2(0,1)));
                float4 d=float4(field.eval(base+float2(1,1)));
                float distance=mix(mix(a.r,b.r,t.x),mix(c.r,d.r,t.x),t.y)*24.0;
                float r=progress(a);
                float q=fract(mix(mix(r,unwrap(progress(b),r),t.x),mix(unwrap(progress(c),r),unwrap(progress(d),r),t.x),t.y));
                float wave=.5+.5*sin(6.283185*(q*5.0-motion.x*1.3));
                float fine=.5+.5*sin(6.283185*(q*11.0+motion.x*.7));
                float peaks=.12+.62*pow(wave,5.0)+.26*pow(fine,7.0);
                float travel=fract(q-motion.x);
                float impulse=exp(-pow(min(travel,1.0-travel)*19.0,2.0))*motion.y;
                // Audio modifies local light density without shifting the physical contour.
                float deformation=audio.x*(.4+1.8*peaks);
                float width=clamp(strokeWidth*(.30+.54*audio.x)+deformation+impulse*2.0,1.2,12.0);
                float sd=max(0.0,distance-width*.5);
                float core=1.0-smoothstep(width*.5-.75,width*.5+.75,distance);
                float nearGlow=exp(-sd/(2.0+audio.z*1.6))*.36;
                float outerGlow=exp(-sd/(4.5+audio.x*1.8))*.24;
                float fade=1.0-smoothstep(17.0,20.0,distance);
                float density=(.08+.92*peaks+.45*impulse)*smoothstep(.015,.7,audio.x+audio.y+audio.z);
                float light=(core*.55+nearGlow+outerGlow)*(audio.w*.80+audio.y*.12+audio.z*.08)*density*motion.w;
                float alpha=(1.0-exp(-light*.85))*fade;
                // Three fixed spectral territories; only light activity travels, never hue.
                float angle=q*6.283185*3.0;
                float wb=audio.x*pow(.5+.5*cos(angle),2.0);
                float wm=audio.y*pow(.5+.5*cos(angle-2.094395),2.0);
                float wh=audio.z*pow(.5+.5*cos(angle-4.18879),2.0);
                float3 tint=(float3(.02,.86,.79)*wb+float3(.76,.12,.98)*wm+float3(1.0,.60,.22)*wh)/max(wb+wm+wh,.0001);
                return half4(half3(tint*alpha),half(alpha));
            }
        """
    }
}
