package com.yp.luminote.app.rendering.canvas
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.geometry.EdgePathGeometryProvider
import com.yp.luminote.app.effects.geometry.WrappedSegmentRange
import com.yp.luminote.app.effects.geometry.EdgeSegmentRenderer
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.effects.model.HaloRenderMode
import com.yp.luminote.app.animation.LightImpulseEndpoints
import com.yp.luminote.app.animation.definitions.HaloEffectSpec
import com.yp.luminote.app.animation.plan.GeometryRequest
import com.yp.luminote.app.animation.plan.HaloEffectVisualPlanMapper
import com.yp.luminote.app.animation.plan.AppearancePayload
import com.yp.luminote.app.animation.plan.PrimaryVisualOperation
import com.yp.luminote.app.animation.plan.VisualPlanSpec
import com.yp.luminote.app.rendering.api.FrameCommandBuffer
import com.yp.luminote.app.rendering.api.FrameCommandCompiler
import com.yp.luminote.app.rendering.api.RenderFrame

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.SweepGradient
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.data.settings.HaloFrame
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Reminder owns the dedicated Light Impulse route; normal Impulse is registry-dispatched. */
internal fun usesLightImpulseRenderer(config: HaloConfig): Boolean =
    config.renderMode == HaloRenderMode.LIGHT_IMPULSE &&
        config.effectPlan !is HaloEffectSpec.Supernova

/** Normal-app Impulse retains the conventional color preparation; dedicated impulse keeps its source color. */
internal fun impulseUsesNormalColorTreatment(config: HaloConfig): Boolean =
    config.renderMode != HaloRenderMode.LIGHT_IMPULSE &&
        config.effectPlan is HaloEffectSpec.SpecializedField



/** Owned cores and tails use a non-projecting cap at the shared-source boundary. */
internal fun lightImpulseOwnedBeamCap(): Paint.Cap = Paint.Cap.BUTT

/** Source bloom is contained by its reservation; round caps would extend past that footprint. */
internal fun lightImpulseBloomCap(): Paint.Cap = Paint.Cap.BUTT


internal fun lightImpulseEndpoints(
    measure: PathMeasure,
    length: Float,
    bounds: RectF
): LightImpulseEndpoints {
    val topFraction = nearestPathFraction(measure, length, bounds.centerX(), bounds.top)
    val bottomFraction = nearestPathFraction(measure, length, bounds.centerX(), bounds.bottom)
    return lightImpulseEndpoints(topFraction, bottomFraction, length)
}

internal fun lightImpulseEndpoints(
    topFraction: Float,
    bottomFraction: Float,
    length: Float
): LightImpulseEndpoints {
    if (length <= 0f) {
        return LightImpulseEndpoints(0f, 0f, 0f, 0f)
    }
    val forwardDistance = ((bottomFraction - topFraction) * length + length) % length
    return LightImpulseEndpoints(
        topFraction = topFraction,
        bottomFraction = bottomFraction,
        forwardDistance = forwardDistance,
        reverseDistance = length - forwardDistance
    )
}

private fun nearestPathFraction(
    measure: PathMeasure,
    length: Float,
    targetX: Float,
    targetY: Float
): Float {
    val position = FloatArray(2)
    var nearestFraction = 0f
    var nearestDistance = Float.MAX_VALUE
    for (sample in 0..LIGHT_ENDPOINT_SAMPLES) {
        val fraction = sample.toFloat() / LIGHT_ENDPOINT_SAMPLES
        measure.getPosTan(length * fraction, position, null)
        val deltaX = position[0] - targetX
        val deltaY = position[1] - targetY
        val distance = deltaX * deltaX + deltaY * deltaY
        if (distance < nearestDistance) {
            nearestDistance = distance
            nearestFraction = fraction
        }
    }
    return nearestFraction
}

private const val LIGHT_ENDPOINT_SAMPLES = 960

/** Draws prepared geometry; the View supplies window size, insets and animation. */
/** Rendering implementation separated from HaloRenderer's public lifecycle facade. */
internal class EdgeRenderPipeline(
    config: HaloConfig,
    private val outline: DisplayOutline
) {
    /** Test-only witness emitted at the point an established Canvas family is invoked. */
    internal data class RenderTrace(
        val operation: PrimaryVisualOperation,
        val phase: Float,
        val gradientPhase: Float,
        val alpha: Int,
        val frameTimeNanos: Long,
        val geometry: GeometryRequest.DisplayOutlineContour,
        val appearance: AppearancePayload
    )

    internal var renderTraceForTest: MutableList<RenderTrace>? = null
    internal val outlinePath: Path
        get() = outline.path

    internal fun lightImpulseGeometry(): StyleGeometry? {
        if (cachedOutlineVersion != outline.version) {
            clearStylePathCache()
            cachedOutlineVersion = outline.version
        }
        return styleGeometryFor(stylePathForCurrentConfig()).takeIf { it.length > 0f }
    }

    internal fun lightImpulseAlpha(): Int = (255f * appearance().intensity).roundToInt().coerceIn(0, 255)
    private var config =
        config.sanitized()

    private var colorRgb =
        this.config.color and RGB_MASK

    private var coreStrokeWidth =
        calculateCoreStrokeWidth(
            this.config.thickness
        )

    private var renderStrokeWidth =
        calculateRenderStrokeWidth(
            coreStrokeWidth
        )

    /** Bound with the selected effect definition, never inferred during a frame. */
    private var lightImpulseUsesConventionalColorTreatment =
        impulseUsesNormalColorTreatment(this.config)

    private var cachedOutlineVersion =
        -1

    private var cachedStyleGeometry:
            StyleGeometry? = null

    /** Conventional and impulse use the shared 0px-envelope physical surface. */
    private val conventionalPathCache = EdgePathGeometryProvider(outline)
    private val calibrationRenderer = CalibrationEdgeRenderer(outline)

    private var gradientShader:
            SweepGradient? = null

    private var gradientOutlineVersion =
        -1

    private var gradientPalette: List<Int> = emptyList()

    private val gradientMatrix =
        Matrix()

    private var gradientCenterX =
        0f

    private var gradientCenterY =
        0f

    internal val corePaint =
        createPaint().apply {
            strokeWidth =
                renderStrokeWidth
        }

    private val conventionalRenderer = ConventionalEdgeRenderer(object : ConventionalRenderSurface {
        override val outlinePath: Path
            get() = this@EdgeRenderPipeline.outlinePath
        override val corePaint: Paint
            get() = this@EdgeRenderPipeline.corePaint
        override fun preparePaint(baseAlpha: Int, effectPhase: Float, gradientPhase: Float) {
            prepareConventionalPaint(baseAlpha, effectPhase, gradientPhase)
        }

        override fun stylePath(): Path = stylePathForCurrentConfig()

        override fun styleGeometry(path: Path): StyleGeometry = styleGeometryFor(path)
    })

    private val lightImpulseRenderer = LightImpulseRenderer(object : LightImpulseRenderSurface {
        override val outlinePath: Path
            get() = this@EdgeRenderPipeline.outlinePath
        override val corePaint: Paint
            get() = this@EdgeRenderPipeline.corePaint
        override val renderStrokeWidth: Float
            get() = this@EdgeRenderPipeline.renderStrokeWidth
        override val colorRgb: Int
            get() = this@EdgeRenderPipeline.colorRgb

        override fun geometry(): StyleGeometry? = lightImpulseGeometry()

        override fun alpha(): Int = lightImpulseAlpha()

        override fun preparePaint(alpha: Int, effectPhase: Float, gradientPhase: Float) {
            prepareLightImpulsePaint(alpha, effectPhase, gradientPhase)
        }
    })

    private val supernovaRenderer = SupernovaRenderer(object : SupernovaRenderSurface {
        override val outlinePath: Path
            get() = this@EdgeRenderPipeline.outlinePath
        override val corePaint: Paint
            get() = this@EdgeRenderPipeline.corePaint
        override val renderStrokeWidth: Float
            get() = this@EdgeRenderPipeline.renderStrokeWidth

        override fun geometry(): StyleGeometry? = lightImpulseGeometry()

        override fun preparePaint(alpha: Int, effectPhase: Float, gradientPhase: Float) {
            prepareConventionalPaint(alpha, effectPhase, gradientPhase)
        }
    })

    /** Separate multi-head surface; conventional motions keep their established renderer path. */
    private val forceBlades =
        ForceBladesRenderer(outline)

    private val canvasEffectDispatcher = CanvasEffectDispatcher(object : CanvasEffectRenderSurface {
        override fun drawSpecializedField(canvas: Canvas, frame: CanvasEffectFrame) {
            // Ordinary Impulse retains its historical full-intensity primitive path.
            lightImpulseRenderer.draw(canvas, 1f, frame.phase, frame.gradientPhase)
        }

        override fun drawSupernova(canvas: Canvas, frame: CanvasEffectFrame) {
            supernovaRenderer.draw(canvas, frame.phase, frame.alpha, frame.gradientPhase)
        }

        override fun drawFullContour(canvas: Canvas, frame: CanvasEffectFrame) {
            conventionalRenderer.drawPulse(canvas, frame.alpha, frame.phase, frame.gradientPhase)
        }

        override fun drawLuminousSegment(
            canvas: Canvas,
            frame: CanvasEffectFrame,
            startFraction: Float,
            lengthFraction: Float
        ) {
            conventionalRenderer.drawLuminousSegment(
                canvas,
                frame.alpha,
                frame.phase,
                frame.gradientPhase,
                startFraction,
                lengthFraction
            )
        }

        override fun drawAzureBlade(canvas: Canvas, frame: CanvasEffectFrame) {
            val geometry = geometry()
            val edgeCalibrationPx = outline.dpToPx(geometry.edgeCalibrationDp)
            val cornerCalibrationPx = outline.dpToPx(geometry.cornerCalibrationDp)
            forceBlades.drawAzure(canvas, frame.phase, frame.alpha, renderStrokeWidth, edgeCalibrationPx, cornerCalibrationPx, geometry.cornerShape, frame.frameTimeNanos)
        }

        override fun drawCrimsonBlade(canvas: Canvas, frame: CanvasEffectFrame) {
            val geometry = geometry()
            val edgeCalibrationPx = outline.dpToPx(geometry.edgeCalibrationDp)
            val cornerCalibrationPx = outline.dpToPx(geometry.cornerCalibrationDp)
            forceBlades.drawCrimson(canvas, frame.phase, frame.alpha, renderStrokeWidth, edgeCalibrationPx, cornerCalibrationPx, geometry.cornerShape, frame.frameTimeNanos)
        }

        override fun drawClashBlade(canvas: Canvas, frame: CanvasEffectFrame) {
            val geometry = geometry()
            val edgeCalibrationPx = outline.dpToPx(geometry.edgeCalibrationDp)
            val cornerCalibrationPx = outline.dpToPx(geometry.cornerCalibrationDp)
            forceBlades.drawClash(canvas, frame.phase, frame.alpha, renderStrokeWidth, edgeCalibrationPx, cornerCalibrationPx, geometry.cornerShape, frame.frameTimeNanos)
        }
    })

    /** Resolved once per config/session; a VSYNC delegates to this cached target. */
    private var canvasEffectTarget = canvasEffectDispatcher.resolve(
        this.config.effectPlan
    )
    private val renderFrame = CanvasEffectFrame()
    private var visualPlan: VisualPlanSpec = HaloEffectVisualPlanMapper.map(this.config)
    /** Targets are bound when the immutable session plan changes, never selected at VSYNC. */
    private var planTargets: List<CanvasEffectTarget> = visualPlan.emitters.map {
        canvasEffectDispatcher.resolve(it.operation)
    }
    private val commandBuffer = FrameCommandBuffer()
    /** Bound only while a plan command is executing; static calibration uses config unchanged. */
    private var activeAppearance: AppearancePayload? = null
    private var activeGeometry: GeometryRequest.DisplayOutlineContour? = null

    /** Test seam for verifying config-bound cache replacement, never used by rendering. */
    internal fun cachedEffectTargetForTest(): CanvasEffectTarget = canvasEffectTarget

    fun update(
        config: HaloConfig
    ) {
        val next =
            config.sanitized()
        val effectPlanChanged = this.config.effectPlan != next.effectPlan

        val geometryChanged =
            this.config.thickness !=
                    next.thickness ||
                    this.config.frame != next.frame ||
                    this.config.edgeCalibrationDp != next.edgeCalibrationDp ||
                    this.config.cornerCalibrationDp != next.cornerCalibrationDp
                    || this.config.cornerShape != next.cornerShape

        this.config =
            next
        lightImpulseUsesConventionalColorTreatment =
            impulseUsesNormalColorTreatment(next)
        if (effectPlanChanged) {
            canvasEffectTarget = canvasEffectDispatcher.resolve(next.effectPlan)
        }
        visualPlan = HaloEffectVisualPlanMapper.map(next)
        planTargets = visualPlan.emitters.map { canvasEffectDispatcher.resolve(it.operation) }

        colorRgb =
            next.color and RGB_MASK

        if (geometryChanged) {
            updateStrokeParameters()

            clearStylePathCache()
            outline.clearRenderCaches()
        }
    }

    /** Explicit host boundary for size/inset changes; dispatch resolution remains config-owned. */
    fun invalidateSurface() {
        clearStylePathCache()
        cachedOutlineVersion = -1
        gradientOutlineVersion = -1
        outline.clearRenderCaches()
    }

    fun draw(
        canvas: Canvas,
        animationProgress: Float,
        effectPhase: Float,
        gradientPhase: Float,
        frameTimeNanos: Long = 0L
    ) {
        drawLegacy(canvas, animationProgress, effectPhase, gradientPhase, frameTimeNanos)
    }

    /** Compiles host-owned frame data into reusable commands and draws in emitter order. */
    fun drawPlan(canvas: Canvas, frame: RenderFrame) {
        FrameCommandCompiler.compile(visualPlan, frame, commandBuffer)
        try {
            for (index in 0 until commandBuffer.size) {
                val command = commandBuffer[index]
                bind(command.geometry, command.appearance)
                trace(
                    command.operation,
                    command.phase,
                    command.gradientPhase,
                    if (command.operation is PrimaryVisualOperation.LightImpulse) lightImpulseAlpha() else command.alpha,
                    command.frameTimeNanos
                )
                drawCommand(canvas, planTargets[index], command.operation, command.animationProgress, command.phase,
                    command.gradientPhase, command.alpha, command.frameTimeNanos)
            }
        } finally {
            activeAppearance = null
            activeGeometry = null
            updateStrokeParameters()
        }
    }

    internal fun replaceVisualPlanForTest(plan: VisualPlanSpec) {
        visualPlan = plan
        planTargets = visualPlan.emitters.map { canvasEffectDispatcher.resolve(it.operation) }
    }

    private fun trace(operation: PrimaryVisualOperation, phase: Float, gradientPhase: Float, alpha: Int, frameTimeNanos: Long) {
        val sink = renderTraceForTest ?: return
        sink += RenderTrace(operation, phase, gradientPhase, alpha, frameTimeNanos, geometry(), appearance())
    }

    private fun bind(geometry: GeometryRequest, appearance: AppearancePayload) {
        val contour = geometry as? GeometryRequest.DisplayOutlineContour
            ?: error("Canvas only supports DisplayOutlineContour geometry.")
        if (activeGeometry != contour) {
            activeGeometry = contour
            coreStrokeWidth = calculateCoreStrokeWidth(contour.thickness)
            renderStrokeWidth = calculateRenderStrokeWidth(coreStrokeWidth)
            corePaint.strokeWidth = renderStrokeWidth
            clearStylePathCache()
        }
        activeAppearance = appearance
        colorRgb = appearance.color and RGB_MASK
    }

    private fun drawCommand(
        canvas: Canvas,
        target: CanvasEffectTarget,
        operation: PrimaryVisualOperation,
        animationProgress: Float,
        effectPhase: Float,
        gradientPhase: Float,
        baseAlpha: Int,
        frameTimeNanos: Long
    ) {
        if (operation is PrimaryVisualOperation.LightImpulse && usesLightImpulseRenderer(config)) {
            lightImpulseRenderer.draw(canvas, animationProgress, effectPhase, gradientPhase)
            return
        }
        if (baseAlpha == 0) return
        if (cachedOutlineVersion != outline.version) {
            clearStylePathCache()
            cachedOutlineVersion = outline.version
        }
        renderFrame.phase = effectPhase
        renderFrame.alpha = baseAlpha
        renderFrame.gradientPhase = gradientPhase
        renderFrame.frameTimeNanos = frameTimeNanos
        target.draw(canvas, renderFrame)
    }

    /** Legacy direct entry point retained for renderer-level callers and tests. */
    fun drawLegacy(
        canvas: Canvas,
        animationProgress: Float,
        effectPhase: Float,
        gradientPhase: Float,
        frameTimeNanos: Long = 0L
    ) {
        bind(visualPlan.emitters.single().geometry, visualPlan.emitters.single().appearance)
        if (usesLightImpulseRenderer(config)) {
            trace(PrimaryVisualOperation.LightImpulse, effectPhase, gradientPhase, lightImpulseAlpha(), frameTimeNanos)
            lightImpulseRenderer.draw(canvas, animationProgress, effectPhase, gradientPhase)
            return
        }
        val baseAlpha =
            (
                    255f *
                            config.intensity *
                            animationProgress.coerceIn(
                                0f,
                                1f
                            )
                    )
                .roundToInt()
                .coerceIn(
                    0,
                    255
                )

        if (baseAlpha == 0) {
            return
        }

        if (
            cachedOutlineVersion !=
            outline.version
        ) {
            clearStylePathCache()

            cachedOutlineVersion =
                outline.version
        }

        renderFrame.phase = effectPhase
        renderFrame.alpha = baseAlpha
        renderFrame.gradientPhase = gradientPhase
        renderFrame.frameTimeNanos = frameTimeNanos
        val operation = visualPlan.emitters.single().operation
        trace(
            operation,
            effectPhase,
            gradientPhase,
            if (operation is PrimaryVisualOperation.LightImpulse) lightImpulseAlpha() else baseAlpha,
            frameTimeNanos
        )
        canvasEffectTarget.draw(canvas, renderFrame)
    }

    /** Keeps normal Impulse on the app's existing solid/palette/gradient paint path. */
    internal fun prepareLightImpulsePaint(alpha: Int, effectPhase: Float, gradientPhase: Float) {
        if (lightImpulseUsesConventionalColorTreatment) {
            prepareConventionalPaint(alpha, effectPhase, gradientPhase)
        } else {
            corePaint.shader = null
            corePaint.color = colorWithAlpha(alpha, colorRgb)
        }
    }

    private fun appearance(): AppearancePayload = activeAppearance ?: AppearancePayload(
        color = config.color,
        intensity = config.intensity,
        thickness = config.thickness,
        colorMode = config.colorMode,
        palette = config.palette.toList()
    )

    private fun geometry(): GeometryRequest.DisplayOutlineContour = activeGeometry
        ?: GeometryRequest.DisplayOutlineContour(
            thickness = config.thickness,
            frame = config.frame,
            edgeCalibrationDp = config.edgeCalibrationDp,
            cornerCalibrationDp = config.cornerCalibrationDp,
            cornerShape = config.cornerShape
        )

    /** Complete calibrated frame used by the static calibration preview. */
    fun drawStaticFrame(canvas: Canvas) {
        colorRgb = config.color and RGB_MASK
        if (config.intensity <= 0f) return
        if (cachedOutlineVersion != outline.version) {
            clearStylePathCache()
            cachedOutlineVersion = outline.version
        }
        prepareConventionalPaint((255f * config.intensity).roundToInt().coerceIn(0, 255), 0f, 0f)
        calibrationRenderer.drawStaticFrame(canvas, staticPreviewPathForCurrentConfig(), corePaint)
    }

    /** Calibration-only witnesses; never called by the animated renderer. */
    fun drawCalibrationDiagnostics(canvas: Canvas, rulerLegend: String, registrationLabel: String) {
        val edgeCalibrationPx = outline.dpToPx(config.edgeCalibrationDp)
        calibrationRenderer.drawDiagnostics(canvas, staticPreviewPathForCurrentConfig(), edgeCalibrationPx, rulerLegend, registrationLabel)
    }

    fun calibrationEdgePx(): Float = outline.dpToPx(config.edgeCalibrationDp)

    /** Shared paint primitive consumed by conventional and dedicated-impulse renderers. */
    internal fun prepareConventionalPaint(
        baseAlpha: Int,
        effectPhase: Float,
        gradientPhase: Float
    ) {
        if (
            appearance().colorMode ==
            HaloColorMode.GRADIENT &&
            appearance().palette.size > 1
        ) {
            val shader =
                gradientShader()

            gradientMatrix.setRotate(
                (
                        gradientPhase *
                                GRADIENT_ROTATION_PER_CYCLE_DEGREES *
                                config.gradientFlowSpeed
                        ) %
                        FULL_ROTATION_DEGREES,
                gradientCenterX,
                gradientCenterY
            )

            shader.setLocalMatrix(
                gradientMatrix
            )

            corePaint.shader =
                shader

            corePaint.alpha =
                baseAlpha

            return
        }

        corePaint.shader =
            null

        val color =
            colorForPhase(
                effectPhase
            )

        corePaint.color =
            colorWithAlpha(
                alpha = baseAlpha,
                rgb = color
            )
    }

    internal fun stylePathForCurrentConfig(): Path {
        val geometry = geometry()
        val edgeCalibrationPx = outline.opticalInsetPx + outline.dpToPx(geometry.edgeCalibrationDp)
        return conventionalPathCache.pathFor(
            strokeWidth = renderStrokeWidth,
            edgeCalibrationPx = edgeCalibrationPx,
            cornerCalibrationPx = outline.dpToPx(geometry.cornerCalibrationDp),
            cornerShape = geometry.cornerShape,
            extraEnvelopePx = 0f
        )
    }

    /** Static calibration intentionally reuses the normal runtime contour. */
    internal fun staticPreviewPathForCurrentConfig(): Path =
        stylePathForCurrentConfig()

    private fun updateStrokeParameters() {
        coreStrokeWidth =
            calculateCoreStrokeWidth(
                geometry().thickness
            )

        renderStrokeWidth =
            calculateRenderStrokeWidth(
                coreStrokeWidth
            )

        corePaint.strokeWidth =
            renderStrokeWidth
    }

    private fun calculateCoreStrokeWidth(
        thickness: Float
    ): Float =
        BASE_STROKE_WIDTH +
                thickness *
                STROKE_WIDTH_RANGE

    /**
     * Rendering width is now exactly the configured Halo width.
     *
     * Geometry containment is handled by centerline placement rather than
     * artificially widening the stroke near the display edge.
     */
    private fun calculateRenderStrokeWidth(
        strokeWidth: Float
    ): Float =
        strokeWidth

    private fun clearStylePathCache() {
        cachedStyleGeometry =
            null
    }

    private fun styleGeometryFor(
        path: Path
    ): StyleGeometry {
        cachedStyleGeometry
            ?.takeIf {
                it.path === path
            }
            ?.let {
                return it
            }

        return StyleGeometry(
            conventionalPathCache.geometrySnapshotFor(
                strokeWidth = renderStrokeWidth,
                edgeCalibrationPx = outline.opticalInsetPx + outline.dpToPx(geometry().edgeCalibrationDp),
                cornerCalibrationPx = outline.dpToPx(geometry().cornerCalibrationDp),
                cornerShape = geometry().cornerShape,
                extraEnvelopePx = 0f
            )
        ).also { cachedStyleGeometry = it }
    }

    private fun gradientShader():
            SweepGradient {

        if (
            gradientShader != null &&
            gradientOutlineVersion ==
            outline.version &&
            gradientPalette == appearance().palette
        ) {
            return gradientShader!!
        }

        val bounds =
            boundsOf(
                outline.path
            )

        gradientCenterX =
            bounds.centerX()

        gradientCenterY =
            bounds.centerY()

        val colors =
            IntArray(
                appearance().palette.size +
                        1
            ) { index ->
                appearance().palette[
                    index %
                            appearance().palette.size
                ] or
                        OPAQUE_ALPHA
            }

        val positions =
            FloatArray(
                colors.size
            ) { index ->
                index.toFloat() /
                        (
                                colors.size -
                                        1
                                )
                            .coerceAtLeast(
                                1
                            )
            }

        return SweepGradient(
            gradientCenterX,
            gradientCenterY,
            colors,
            positions
        ).also { shader ->

            gradientShader =
                shader

            gradientOutlineVersion =
                outline.version

            gradientPalette =
                appearance().palette
        }
    }

    private fun boundsOf(
        path: Path
    ): RectF =
        RectF().also {
            path.computeBounds(
                it,
                true
            )
        }

    private fun colorForPhase(
        phase: Float
    ): Int {
        if (
            appearance().colorMode ==
            HaloColorMode.GRADIENT ||
            appearance().palette.size <
            2
        ) {
            return colorRgb
        }

        val normalizedPhase =
            normalizedPhase(
                phase
            )

        val scaled =
            normalizedPhase *
                    appearance().palette.size

        val startIndex =
            scaled.toInt() %
                    appearance().palette.size

        val endIndex =
            (
                    startIndex +
                            1
                    ) %
                    appearance().palette.size

        val fraction =
            scaled -
                    scaled.toInt()

        return blend(
            appearance().palette[startIndex],
            appearance().palette[endIndex],
            fraction
        ) and
                RGB_MASK
    }

    private fun blend(
        start: Int,
        end: Int,
        fraction: Float
    ): Int {
        val safeFraction =
            fraction.coerceIn(
                0f,
                1f
            )

        val startRed =
            component(
                start,
                RED_SHIFT
            )

        val startGreen =
            component(
                start,
                GREEN_SHIFT
            )

        val startBlue =
            component(
                start,
                BLUE_SHIFT
            )

        val endRed =
            component(
                end,
                RED_SHIFT
            )

        val endGreen =
            component(
                end,
                GREEN_SHIFT
            )

        val endBlue =
            component(
                end,
                BLUE_SHIFT
            )

        val red =
            interpolateComponent(
                startRed,
                endRed,
                safeFraction
            )

        val green =
            interpolateComponent(
                startGreen,
                endGreen,
                safeFraction
            )

        val blue =
            interpolateComponent(
                startBlue,
                endBlue,
                safeFraction
            )

        return (
                red shl
                        RED_SHIFT
                ) or
                (
                        green shl
                                GREEN_SHIFT
                        ) or
                blue
    }

    private fun component(
        color: Int,
        shift: Int
    ): Int =
        (
                color shr
                        shift
                ) and
                0xFF

    private fun interpolateComponent(
        start: Int,
        end: Int,
        fraction: Float
    ): Int =
        (
                start +
                        (
                                end -
                                        start
                                ) *
                        fraction
                )
            .roundToInt()

    private fun normalizedPhase(
        phase: Float
    ): Float =
        (
                phase %
                        1f +
                        1f
                ) %
                1f

    private fun normalizeDistance(
        value: Float,
        length: Float
    ): Float =
        (
                value %
                        length +
                        length
                ) %
                length

    private fun colorWithAlpha(
        alpha: Int,
        rgb: Int
    ): Int =
        (
                alpha
                    .coerceIn(
                        0,
                        255
                    ) shl
                        ALPHA_SHIFT
                ) or
                (
                        rgb and
                                RGB_MASK
                        )

    private fun createPaint():
            Paint =
        Paint(
            Paint.ANTI_ALIAS_FLAG or
                    Paint.DITHER_FLAG
        ).apply {
            style =
                Paint.Style.STROKE

            strokeCap =
                Paint.Cap.ROUND

            strokeJoin =
                Paint.Join.ROUND
        }

    private companion object {
        private const val BASE_STROKE_WIDTH =
            4f

        private const val STROKE_WIDTH_RANGE =
            10f

        private const val GRADIENT_ROTATION_PER_CYCLE_DEGREES =
            220f

        private const val FULL_ROTATION_DEGREES =
            360f

        private const val RGB_MASK =
            0x00FFFFFF

        private const val ALPHA_SHIFT =
            24

        private const val RED_SHIFT =
            16

        private const val GREEN_SHIFT =
            8

        private const val BLUE_SHIFT =
            0

        private const val OPAQUE_ALPHA =
            -0x1000000
    }
}

/**
 * Public renderer lifecycle. Rendering families and their mutable paint/path state live in
 * [EdgeRenderPipeline], leaving this type as the explicit config/lifecycle entry point used by
 * HaloView and preview callers.
 */
internal class HaloRenderer(config: HaloConfig, outline: DisplayOutline) {
    private val pipeline = EdgeRenderPipeline(config, outline)

    fun update(config: HaloConfig) = pipeline.update(config)

    /** Host calls this after it changes DisplayOutline size or insets. */
    fun invalidateSurface() = pipeline.invalidateSurface()

    fun draw(canvas: Canvas, animationProgress: Float, effectPhase: Float, gradientPhase: Float, frameTimeNanos: Long = 0L) =
        pipeline.draw(canvas, animationProgress, effectPhase, gradientPhase, frameTimeNanos)

    fun drawPlan(canvas: Canvas, frame: RenderFrame) = pipeline.drawPlan(canvas, frame)

    internal fun setRenderTraceForTest(sink: MutableList<EdgeRenderPipeline.RenderTrace>?) {
        pipeline.renderTraceForTest = sink
    }

    internal fun replaceVisualPlanForTest(plan: VisualPlanSpec) = pipeline.replaceVisualPlanForTest(plan)

    fun drawStaticFrame(canvas: Canvas) = pipeline.drawStaticFrame(canvas)

    fun drawCalibrationDiagnostics(canvas: Canvas, rulerLegend: String, registrationLabel: String) =
        pipeline.drawCalibrationDiagnostics(canvas, rulerLegend, registrationLabel)

    fun calibrationEdgePx(): Float = pipeline.calibrationEdgePx()

    internal fun stylePathForCurrentConfig(): Path = pipeline.stylePathForCurrentConfig()

    internal fun staticPreviewPathForCurrentConfig(): Path = pipeline.staticPreviewPathForCurrentConfig()
}
