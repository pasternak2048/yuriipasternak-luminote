package com.yp.luminote.app.rendering.gpu

import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.Mesh
import android.graphics.MeshSpecification
import android.graphics.Paint
import android.graphics.RectF
import com.yp.luminote.app.animation.definitions.HaloEffectSpec
import com.yp.luminote.app.animation.LightImpulseProductionDrawPlan
import com.yp.luminote.app.animation.LightImpulseEndpoints
import com.yp.luminote.app.animation.lightImpulseProductionDrawPlan
import com.yp.luminote.app.animation.LightImpulsePhase
import com.yp.luminote.app.animation.LightImpulseDrawMode
import com.yp.luminote.app.animation.lightImpulsePhase
import com.yp.luminote.app.animation.lightImpulseOriginGlow
import com.yp.luminote.app.animation.lightImpulseBranchEnergy
import com.yp.luminote.app.animation.lightImpulseConvergenceEnergy
import com.yp.luminote.app.animation.lightImpulseTailPassEnergy
import com.yp.luminote.app.animation.lightImpulseCorePassEnergy
import com.yp.luminote.app.animation.lightImpulseBottomBloomPassEnergy
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.geometry.EdgePathGeometry
import com.yp.luminote.app.effects.geometry.EdgePathGeometryProvider
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.effects.model.HaloRenderMode
import com.yp.luminote.app.rendering.api.HaloRenderBackend
import com.yp.luminote.app.rendering.api.HaloRenderTarget
import com.yp.luminote.app.rendering.api.RenderFrame
import com.yp.luminote.app.rendering.api.RenderMode
import com.yp.luminote.app.rendering.api.RenderOutcome
import com.yp.luminote.app.rendering.canvas.CanvasRenderTarget
import com.yp.luminote.app.rendering.canvas.lightImpulseEndpoints
import com.yp.luminote.app.rendering.canvas.usesLightImpulseRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** Hardware-Canvas Mesh/AGSL renderer for the deliberately narrow FullContour contract. */
internal class GpuRenderBackend(config: HaloConfig, private val outline: DisplayOutline) : HaloRenderBackend {
    private var config = config.sanitized()
    private val submission = GpuMeshSubmission.create(config, outline)
    private val composer = GpuFullContourComposer(outline, EdgePathGeometryProvider(outline), submission)
    private val lightComposer = GpuLightImpulseComposer(outline, EdgePathGeometryProvider(outline), submission)
    /** White, opaque and dithered: AGSL owns resolved RGB and alpha. */
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { color = Color.WHITE; alpha = 255 }
    private var disposed = false
    override fun updateConfig(config: HaloConfig) { if (!disposed) this.config = config.sanitized() }
    override fun invalidateSurface() { if (!disposed) { composer.invalidate(); submission.invalidate() } }
    override fun render(target: HaloRenderTarget, frame: RenderFrame): RenderOutcome {
        if (disposed) return RenderOutcome.Unsupported("GPU backend is disposed")
        val canvas = (target as? CanvasRenderTarget)?.canvas
        val admission = gpuAdmissionReason(config, frame.mode)
        if (admission != null) return fallback(UnsupportedOperationException("GPU mesh path does not implement this effect's Canvas semantics: $admission"), admission, target, canvas?.isHardwareAccelerated)
        if (canvas == null) return fallback(IllegalArgumentException("GPU backend requires a CanvasRenderTarget"), "target-not-canvas", target, null)
        if (!canvas.isHardwareAccelerated) return fallback(IllegalStateException("GPU backend requires a hardware Canvas"), "software-canvas", target, false)
        return try {
            val alpha = if (usesLightImpulseRenderer(config)) config.intensity.coerceIn(0f, 1f)
                else config.intensity.coerceIn(0f, 1f) * frame.animationProgress.coerceIn(0f, 1f)
            if (alpha <= 0f) return RenderOutcome.Rendered
            val save = canvas.save()
            try {
                canvas.clipPath(outline.path)
                if (usesLightImpulseRenderer(config)) lightComposer.draw(canvas, config, frame.effectPhase, alpha) else composer.draw(canvas, config, alpha, paint)
            } finally { canvas.restoreToCount(save) }
            RenderOutcome.Rendered
        } catch (failure: RuntimeException) { fallback(failure, failure.javaClass.simpleName, target, canvas.isHardwareAccelerated) }
    }
    override fun dispose() { if (!disposed) { disposed = true; composer.dispose(); lightComposer.dispose(); submission.dispose() } }

    private fun fallback(cause: Throwable, reason: String, target: HaloRenderTarget, hardware: Boolean?): RenderOutcome.FallbackToCanvas {
        submission.fallback(reason, cause, config, target, hardware)
        return RenderOutcome.FallbackToCanvas(cause)
    }
}

/** Unsupported families/modes fall back before GPU submission rather than approximating Canvas. */
internal fun gpuCanFaithfullyRender(config: HaloConfig, mode: RenderMode): Boolean = gpuAdmissionReason(config, mode) == null
internal fun gpuAdmissionReason(config: HaloConfig, mode: RenderMode): String? = when {
    mode != RenderMode.ANIMATION -> "mode=$mode"
    config.colorMode != HaloColorMode.SOLID -> "colorMode=${config.colorMode}"
    config.palette.size >= 2 -> "paletteSize=${config.palette.size}"
    config.renderMode != HaloRenderMode.LIGHT_IMPULSE && config.effectPlan != HaloEffectSpec.FullContour -> "effect=${config.effectPlan}"
    else -> null
}
internal fun gpuDebugProofEnabled(isDebugBuild: Boolean, isTagLoggable: Boolean): Boolean = isDebugBuild && isTagLoggable

internal data class GpuContourSample(val x: Float, val y: Float, val tx: Float, val ty: Float)

/** Pure bounded tessellation policy: midpoint deviation <= .25px and tangent delta <= 2 degrees. */
internal fun adaptiveContourFractions(sampleAt: (Float) -> GpuContourSample, maxSegments: Int = 1024): FloatArray? {
    val out = ArrayList<Float>(64).apply { add(0f) }; val stack = ArrayDeque<Span>().apply { addLast(Span(0f, 1f)) }
    while (stack.isNotEmpty() && out.size - 1 < maxSegments) {
        val span = stack.removeLast(); val middle = (span.start + span.end) / 2f
        if (span.end - span.start <= 1f / 16_384f || tessellationAcceptable(sampleAt(span.start), sampleAt(middle), sampleAt(span.end))) out += span.end
        else { stack.addLast(Span(middle, span.end)); stack.addLast(Span(span.start, middle)) }
    }
    if (stack.isNotEmpty()) return null
    return out.toFloatArray()
}
internal fun tessellationAcceptable(a: GpuContourSample, mid: GpuContourSample, b: GpuContourSample): Boolean {
    val deviation = distance(mid.x, mid.y, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
    return deviation <= .25f && tangentAngleDegrees(a, b) <= 2f
}
internal fun featherCoverage(lateralDistance: Float, coreHalfWidth: Float): Float = ((coreHalfWidth + 1f - abs(lateralDistance)) / 1f).coerceIn(0f, 1f)
internal fun fullContourVerticesPerSegment(): Int = 18
internal fun lightImpulseMeshCacheBytes(segmentCount: Int): Int = segmentCount.coerceAtLeast(0) * 12 * 16
internal fun lightImpulseTransverseProfile(lateral: Float): Float {
    val t = lateral.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
/**
 * A symmetric, bounded soft selection rather than an additive blend.  It is continuous at
 * crossings, preserves either isolated lobe, and cannot brighten above the stronger lobe.
 */
internal fun lightImpulseSmoothUnion(first: Float, second: Float): Float {
    val a = finiteNonNegativeGpuUniform(first).coerceAtMost(1f)
    val b = finiteNonNegativeGpuUniform(second).coerceAtMost(1f)
    val a2 = a * a; val b2 = b * b
    return ((a2 * a) + (b2 * b)) / (a2 + b2 + .000001f)
}
internal fun lightImpulseLongitudinalProfile(distance: Float, length: Float, sourceRadius: Float, tail: Float, core: Float): Float {
    if (!length.isFinite() || length <= .0001f) return 0f
    val safeLength = length.coerceAtLeast(.0001f); val t = (distance / safeLength).coerceIn(0f, 1f)
    val release = ((distance - sourceRadius) / .012f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    val tailShape = 1f - ((t - .10f) / .86f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    val coreIn = ((t - .52f) / .32f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    val coreOut = 1f - ((t - .84f) / .16f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    return release * lightImpulseSmoothUnion(tailShape * tail, coreIn * coreOut * core)
}
internal fun lightImpulseGpuSourceIsBottom(phase: Float): Boolean = when (lightImpulsePhase(phase.coerceIn(0f, 1f))) {
    LightImpulsePhase.IGNITION, LightImpulsePhase.TRAVEL -> false
    LightImpulsePhase.CONVERGE, LightImpulsePhase.FADE -> true
}
/** Fields start outside source reservation; fade has no travelling field. */
internal fun lightImpulseGpuFieldMask(distanceFromSource: Float, sourceRadius: Float, fadeOnly: Boolean): Float =
    if (fadeOnly || distanceFromSource < sourceRadius) 0f else 1f
/** Canvas's owned-travel source footprint is intentionally independent of the production field length. */
internal fun lightImpulseGpuSourceReservation(
    phase: Float,
    drawMode: LightImpulseDrawMode,
    contourLength: Float,
    productionRadius: Float
): Float = if (drawMode == LightImpulseDrawMode.OWNED_TRAVEL_FIELDS) {
    contourLength.coerceAtLeast(0f) * (.018f + lightImpulseOriginGlow(phase.coerceIn(0f, 1f)) * .020f)
} else productionRadius.coerceAtLeast(0f)
internal fun lightImpulseGpuCacheMatches(
    cachedVersion: Int, cachedStroke: Float, cachedEdge: Float, cachedCorner: Float, cachedShape: Float,
    version: Int, stroke: Float, edge: Float, corner: Float, shape: Float
): Boolean = cachedVersion == version && cachedStroke == stroke && cachedEdge == edge && cachedCorner == corner && cachedShape == shape
internal fun finiteNonNegativeGpuUniform(value: Float): Float = if (value.isFinite()) value.coerceAtLeast(0f) else 0f
/** `resolvedColor` is a plain AGSL vec4, so bind normalized opaque RGBA with the float4 API. */
internal fun gpuResolvedColorComponent(color: Int, shift: Int): Float = ((color ushr shift) and 0xFF) / 255f
private fun Mesh.setResolvedColorUniform(color: Int) = setFloatUniform(
    "resolvedColor",
    gpuResolvedColorComponent(color, 16),
    gpuResolvedColorComponent(color, 8),
    gpuResolvedColorComponent(color, 0),
    1f
)
/** BaseCanvas treats Mesh output as destination and Paint as source; DST retains premultiplied AGSL output. */
internal fun lightImpulseMeshBlendMode(): BlendMode = BlendMode.DST
internal fun fullContourCoveragePattern(): FloatArray = floatArrayOf(
    0f, 1f, 0f, 0f, 1f, 1f, // left feather
    1f, 1f, 1f, 1f, 1f, 1f, // opaque core
    1f, 0f, 1f, 1f, 0f, 0f  // right feather
)
internal data class GpuMeshBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)
internal fun expandedMeshBounds(left: Float, top: Float, right: Float, bottom: Float, strokeWidth: Float): GpuMeshBounds {
    val envelope = strokeWidth / 2f + 1f
    return GpuMeshBounds(left - envelope, top - envelope, right + envelope, bottom + envelope)
}
private fun tangentAngleDegrees(a: GpuContourSample, b: GpuContourSample): Float {
    val al = sqrt(a.tx*a.tx + a.ty*a.ty).coerceAtLeast(.0001f); val bl = sqrt(b.tx*b.tx + b.ty*b.ty).coerceAtLeast(.0001f)
    return Math.toDegrees(acos(((a.tx*b.tx + a.ty*b.ty) / (al*bl)).coerceIn(-1f, 1f)).toDouble()).toFloat()
}
private fun distance(ax: Float, ay: Float, bx: Float, by: Float) = sqrt((ax-bx)*(ax-bx) + (ay-by)*(ay-by))
private data class Span(val start: Float, val end: Float)

/** Cached topology; RGB and animated alpha are per-frame uniforms, not cache inputs. */
private class GpuFullContourComposer(private val outline: DisplayOutline, private val geometryProvider: EdgePathGeometryProvider, private val submission: GpuMeshSubmission) {
    private var key: MeshKey? = null; private var mesh: Mesh? = null
    fun invalidate() { key = null; mesh = null }; fun dispose() = invalidate()
    fun draw(canvas: android.graphics.Canvas, config: HaloConfig, alpha: Float, paint: Paint) {
        val stroke = 4f + config.thickness * 10f
        val geometry = geometryProvider.geometryFor(stroke, outline.opticalInsetPx + outline.dpToPx(config.edgeCalibrationDp), outline.dpToPx(config.cornerCalibrationDp), config.cornerShape, 0f) ?: return
        val nextKey = MeshKey(outline.version, stroke, config.edgeCalibrationDp, config.cornerCalibrationDp, config.cornerShape)
        val resolved = (if (key == nextKey) mesh else buildMesh(geometry, stroke).also { key = nextKey; mesh = it }) ?: return
        resolved.setResolvedColorUniform(config.color); resolved.setFloatUniform("animatedAlpha", alpha)
        submission.drawFull(canvas, resolved, paint)
    }
    private fun buildMesh(geometry: EdgePathGeometry, width: Float): Mesh? {
        val position = FloatArray(2); val tangent = FloatArray(2)
        val fractions = adaptiveContourFractions({ f -> geometry.pointAt(f, position, tangent); GpuContourSample(position[0], position[1], tangent[0], tangent[1]) })
            ?: throw IllegalStateException("Adaptive GPU tessellation exceeded its fidelity cap")
        if (fractions.size < 2) return null
        val buffer = ByteBuffer.allocateDirect((fractions.size-1)*fullContourVerticesPerSegment()*12).order(ByteOrder.nativeOrder())
        for (i in 0 until fractions.lastIndex) { val a = vertices(geometry, fractions[i], width); val b = vertices(geometry, fractions[i+1], width); quad(buffer,a[0],a[1],b[0],b[1]); quad(buffer,a[1],a[2],b[1],b[2]); quad(buffer,a[2],a[3],b[2],b[3]) }
        buffer.rewind(); val bounds = expandedMeshBounds(geometry.bounds.left, geometry.bounds.top, geometry.bounds.right, geometry.bounds.bottom, width)
        return Mesh(spec, Mesh.TRIANGLES, buffer, (fractions.size-1)*fullContourVerticesPerSegment(), RectF(bounds.left, bounds.top, bounds.right, bounds.bottom))
    }
    /** Outer vertices are coverage zero; inner vertices coverage one: exactly a 1px feather band. */
    private fun vertices(geometry: EdgePathGeometry, f: Float, width: Float): Array<V> {
        val p=FloatArray(2); val t=FloatArray(2); geometry.pointAt(f,p,t); val m=sqrt(t[0]*t[0]+t[1]*t[1]).coerceAtLeast(.001f); val nx=-t[1]/m; val ny=t[0]/m; val inner=width/2f; val outer=inner+1f
        return arrayOf(V(p[0]+nx*outer,p[1]+ny*outer,0f),V(p[0]+nx*inner,p[1]+ny*inner,1f),V(p[0]-nx*inner,p[1]-ny*inner,1f),V(p[0]-nx*outer,p[1]-ny*outer,0f))
    }
    private fun quad(b: ByteBuffer,a:V,c:V,d:V,e:V) { put(b,a);put(b,c);put(b,d);put(b,d);put(b,c);put(b,e) }
    private fun put(b:ByteBuffer,v:V) { b.putFloat(v.x).putFloat(v.y).putFloat(v.coverage) }
    private data class V(val x:Float,val y:Float,val coverage:Float)
    private data class MeshKey(val version:Int,val stroke:Float,val edge:Float,val corner:Float,val shape:Float)
    private val spec by lazy { MeshSpecification.make(arrayOf(MeshSpecification.Attribute(MeshSpecification.TYPE_FLOAT2,0,"position"),MeshSpecification.Attribute(MeshSpecification.TYPE_FLOAT,8,"coverage")),12,arrayOf(MeshSpecification.Varying(MeshSpecification.TYPE_FLOAT,"coverage")),"Varyings main(const Attributes a) { Varyings v; v.position = a.position; v.coverage = a.coverage; return v; }",GpuMeshShaders.FULL) }
}

/**
 * Three cached, adaptive meshes for the dedicated reminder impulse.  Its only changing inputs
 * are shader uniforms derived from the existing production plan; it owns no timeline or clock.
 */
private class GpuLightImpulseComposer(private val outline: DisplayOutline, private val geometryProvider: EdgePathGeometryProvider, private val submission: GpuMeshSubmission) {
    private var cacheVersion = -1
    private var cacheStroke = Float.NaN
    private var cacheEdge = Float.NaN
    private var cacheCorner = Float.NaN
    private var cacheShape = Float.NaN
    private var contourLength = 0f
    private var meshes: Array<Mesh>? = null
    private var endpoints: LightImpulseEndpoints? = null
    private val plan = LightImpulseProductionDrawPlan()
    private val position = FloatArray(2)
    private val tangent = FloatArray(2)
    // drawMesh blends Paint as source over Mesh shader output; DST makes this paint intentionally inert.
    private val sourcePreservingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.TRANSPARENT; alpha = 0 }

    fun invalidate() { cacheVersion = -1; meshes = null; endpoints = null }
    fun dispose() = invalidate()

    fun draw(canvas: android.graphics.Canvas, config: HaloConfig, effectPhase: Float, intensity: Float) {
        val stroke = 4f + config.thickness * 10f
        if (!lightImpulseGpuCacheMatches(cacheVersion, cacheStroke, cacheEdge, cacheCorner, cacheShape,
                outline.version, stroke, config.edgeCalibrationDp, config.cornerCalibrationDp, config.cornerShape)) {
            val geometry = geometryProvider.geometryFor(stroke, outline.opticalInsetPx + outline.dpToPx(config.edgeCalibrationDp), outline.dpToPx(config.cornerCalibrationDp), config.cornerShape, 0f)
                ?: return
            meshes = buildMeshes(geometry, stroke)
            endpoints = meshes?.let { lightImpulseEndpoints(geometry.measure, geometry.length, geometry.bounds) }
            contourLength = geometry.length
            cacheVersion = outline.version; cacheStroke = stroke; cacheEdge = config.edgeCalibrationDp; cacheCorner = config.cornerCalibrationDp; cacheShape = config.cornerShape
        }
        val resolved = meshes ?: throw IllegalStateException("Adaptive GPU Light Impulse tessellation exceeded its fidelity cap")
        val phase = effectPhase.coerceIn(0f, 1f)
        val endpoints = requireNotNull(endpoints)
        // Reuses the same phase/ownership/field-length authority as the Canvas production renderer.
        val drawPlan = lightImpulseProductionDrawPlan(phase, contourLength, endpoints.forwardDistance, endpoints.reverseDistance, plan)
        val phaseKind = lightImpulsePhase(phase)
        val branch = when (phaseKind) {
            LightImpulsePhase.TRAVEL -> lightImpulseBranchEnergy(phase)
            LightImpulsePhase.CONVERGE -> lightImpulseConvergenceEnergy(phase)
            else -> 0f
        }
        val tailEnergy = lightImpulseTailPassEnergy(branch)
        val coreEnergy = lightImpulseCorePassEnergy(branch)
        val bloomEnergy = when (phaseKind) {
            LightImpulsePhase.IGNITION, LightImpulsePhase.TRAVEL -> lightImpulseOriginGlow(phase)
            LightImpulsePhase.CONVERGE, LightImpulsePhase.FADE -> lightImpulseBottomBloomPassEnergy(phase)
        }
        val top = endpoints.topFraction
        val bottom = endpoints.bottomFraction
        val source = if (lightImpulseGpuSourceIsBottom(phase)) bottom else top
        val fadeOnly = phaseKind == LightImpulsePhase.FADE
        val sourceReservation = finiteNonNegativeGpuUniform(lightImpulseGpuSourceReservation(phase, drawPlan.mode, contourLength, drawPlan.sourceBloomRadius))
        val forwardLength = finiteNonNegativeGpuUniform(drawPlan.forwardFieldLength)
        val reverseLength = finiteNonNegativeGpuUniform(drawPlan.reverseFieldLength)
        val safeContourLength = finiteNonNegativeGpuUniform(contourLength)
        // A single distance-profile mesh replaces three coincident opaque rails.
        for (index in resolved.indices) {
            val mesh = resolved[index]
            mesh.setResolvedColorUniform(config.color)
            mesh.setFloatUniform("baseAlpha", intensity)
            mesh.setFloatUniform("source", source)
            mesh.setFloatUniform("forwardLength", if (safeContourLength > 0f) finiteNonNegativeGpuUniform(forwardLength / safeContourLength) else 0f)
            mesh.setFloatUniform("reverseLength", if (safeContourLength > 0f) finiteNonNegativeGpuUniform(reverseLength / safeContourLength) else 0f)
            mesh.setFloatUniform("sourceRadius", if (safeContourLength > 0f) finiteNonNegativeGpuUniform(sourceReservation / safeContourLength) else 0f)
            mesh.setFloatUniform("tailEnergy", tailEnergy)
            mesh.setFloatUniform("coreEnergy", coreEnergy)
            mesh.setFloatUniform("bloomEnergy", bloomEnergy)
            mesh.setIntUniform("fadeOnly", if (fadeOnly) 1 else 0)
            submission.drawLight(canvas, mesh, sourcePreservingPaint)
        }
    }

    private fun buildMeshes(geometry: EdgePathGeometry, stroke: Float): Array<Mesh>? {
        val fractions = adaptiveContourFractions({ fraction ->
            geometry.pointAt(fraction, position, tangent)
            GpuContourSample(position[0], position[1], tangent[0], tangent[1])
        }) ?: return null
        if (fractions.size < 2) return null
        return arrayOf(buildProfileMesh(geometry, fractions, stroke * LIGHT_PROFILE_WIDTH))
    }

    private fun buildProfileMesh(geometry: EdgePathGeometry, fractions: FloatArray, width: Float): Mesh {
        val buffer = ByteBuffer.allocateDirect((fractions.size - 1) * 12 * LIGHT_VERTEX_STRIDE).order(ByteOrder.nativeOrder())
        for (index in 0 until fractions.lastIndex) {
            val a = lightVertices(geometry, fractions[index], width)
            val b = lightVertices(geometry, fractions[index + 1], width)
            lightQuad(buffer, a[0], a[1], b[0], b[1]); lightQuad(buffer, a[2], a[3], b[2], b[3])
        }
        buffer.rewind()
        val bounds = expandedMeshBounds(geometry.bounds.left, geometry.bounds.top, geometry.bounds.right, geometry.bounds.bottom, width)
        return Mesh(lightSpec, Mesh.TRIANGLES, buffer, (fractions.size - 1) * 12, RectF(bounds.left, bounds.top, bounds.right, bounds.bottom))
    }

    /** Vertex coordinate is the wrapped contour coordinate consumed by AGSL; no per-frame path work. */
    private fun lightVertices(geometry: EdgePathGeometry, fraction: Float, width: Float): Array<LightVertex> {
        geometry.pointAt(fraction, position, tangent)
        val length = sqrt(tangent[0]*tangent[0] + tangent[1]*tangent[1]).coerceAtLeast(.001f)
        val nx = -tangent[1] / length; val ny = tangent[0] / length; val outer = width / 2f + 1f
        // 0=outer edge, 1=centerline. Both sides interpolate a single continuous glow profile.
        return arrayOf(LightVertex(position[0]+nx*outer, position[1]+ny*outer, fraction, 0f), LightVertex(position[0], position[1], fraction, 1f), LightVertex(position[0], position[1], fraction, 1f), LightVertex(position[0]-nx*outer, position[1]-ny*outer, fraction, 0f))
    }

    private fun lightQuad(buffer: ByteBuffer, a: LightVertex, b: LightVertex, c: LightVertex, d: LightVertex) { lightPut(buffer,a); lightPut(buffer,b); lightPut(buffer,c); lightPut(buffer,c); lightPut(buffer,b); lightPut(buffer,d) }
    private fun lightPut(buffer: ByteBuffer, v: LightVertex) { buffer.putFloat(v.x).putFloat(v.y).putFloat(v.coordinate).putFloat(v.lateral) }
    private data class LightVertex(val x: Float, val y: Float, val coordinate: Float, val lateral: Float)
    private companion object {
        const val LIGHT_VERTEX_STRIDE = 16
        const val LIGHT_PROFILE_WIDTH = 2.35f
        val lightSpec by lazy { MeshSpecification.make(
            arrayOf(MeshSpecification.Attribute(MeshSpecification.TYPE_FLOAT2,0,"position"), MeshSpecification.Attribute(MeshSpecification.TYPE_FLOAT,8,"contour"), MeshSpecification.Attribute(MeshSpecification.TYPE_FLOAT,12,"lateral")),
            LIGHT_VERTEX_STRIDE, arrayOf(MeshSpecification.Varying(MeshSpecification.TYPE_FLOAT,"contour"), MeshSpecification.Varying(MeshSpecification.TYPE_FLOAT,"lateral")),
            "Varyings main(const Attributes a) { Varyings v; v.position=a.position; v.contour=a.contour; v.lateral=a.lateral; return v; }",
            GpuMeshShaders.LIGHT) }
    }
}
