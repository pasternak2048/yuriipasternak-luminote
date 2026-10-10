package com.yp.luminote.app.rendering.canvas
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.geometry.EdgePathGeometryProvider
import com.yp.luminote.app.effects.geometry.WrappedSegmentRange
import com.yp.luminote.app.effects.geometry.EdgeSegmentRenderer
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.effects.model.HaloRenderMode
import com.yp.luminote.app.effects.model.MotionRenderCommand
import com.yp.luminote.app.effects.model.MotionRenderCommandKind
import com.yp.luminote.app.effects.model.MotionRenderEmitter
import com.yp.luminote.app.effects.model.MotionRenderEmitters
import com.yp.luminote.app.effects.model.MotionRenderFrame
import com.yp.luminote.app.effects.model.MotionBladeVariant
import com.yp.luminote.app.animation.*

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.SweepGradient
import com.yp.luminote.app.data.settings.HaloColorMode
import com.yp.luminote.app.data.settings.HaloFrame
import com.yp.luminote.app.data.settings.HaloMotion
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Reminder owns the dedicated Light Impulse route; normal Impulse is registry-dispatched. */
internal fun usesLightImpulseRenderer(config: HaloConfig): Boolean =
    config.renderMode == HaloRenderMode.LIGHT_IMPULSE

/** Normal-app Impulse retains the conventional color preparation; dedicated impulse keeps its source color. */
internal fun impulseUsesNormalColorTreatment(config: HaloConfig): Boolean =
    config.renderMode != HaloRenderMode.LIGHT_IMPULSE && config.motion == HaloMotion.IMPULSE



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
    internal val outlinePath: Path
        get() = outline.path

    internal fun lightImpulseGeometry(): StyleGeometry? {
        if (cachedOutlineVersion != outline.version) {
            clearStylePathCache()
            cachedOutlineVersion = outline.version
        }
        return styleGeometryFor(stylePathForCurrentConfig()).takeIf { it.length > 0f }
    }

    internal fun lightImpulseAlpha(): Int = (255f * config.intensity).roundToInt().coerceIn(0, 255)
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

    private var gradientPalette =
        intArrayOf()

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

    /** Separate multi-head surface; conventional motions keep their established renderer path. */
    private val forceBlades =
        ForceBladesRenderer(outline)

    /** Resolved with the config/session; a VSYNC emits directly without registry lookup. */
    private var motionEmitter: MotionRenderEmitter = MotionRenderEmitters.resolve(this.config.motion)
    private val renderFrame = MotionRenderFrame()
    private val renderCommand = MotionRenderCommand()

    fun update(
        config: HaloConfig
    ) {
        val next =
            config.sanitized()

        val geometryChanged =
            this.config.thickness !=
                    next.thickness ||
                    this.config.frame != next.frame ||
                    this.config.edgeCalibrationDp != next.edgeCalibrationDp ||
                    this.config.cornerCalibrationDp != next.cornerCalibrationDp
                    || this.config.cornerShape != next.cornerShape

        this.config =
            next
        motionEmitter = MotionRenderEmitters.resolve(next.motion)

        colorRgb =
            next.color and RGB_MASK

        if (geometryChanged) {
            updateStrokeParameters()

            clearStylePathCache()
            outline.clearRenderCaches()
        }
    }

    fun draw(
        canvas: Canvas,
        animationProgress: Float,
        effectPhase: Float,
        gradientPhase: Float,
        frameTimeNanos: Long = 0L
    ) {
        if (usesLightImpulseRenderer(config)) {
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
        motionEmitter.emit(renderFrame, renderCommand)
        when (renderCommand.kind) {
            MotionRenderCommandKind.SPECIALIZED_FIELD ->
                lightImpulseRenderer.draw(canvas, 1f, renderFrame.phase, renderFrame.gradientPhase)
            MotionRenderCommandKind.FULL_CONTOUR ->
                conventionalRenderer.drawPulse(canvas, renderFrame.alpha, renderFrame.phase, renderFrame.gradientPhase)
            MotionRenderCommandKind.LUMINOUS_SEGMENT ->
                conventionalRenderer.drawLuminousSegment(
                    canvas,
                    renderFrame.alpha,
                    renderFrame.phase,
                    renderFrame.gradientPhase,
                    renderCommand.segmentStartFraction,
                    renderCommand.segmentLengthFraction
                )
            MotionRenderCommandKind.BLADE ->
                drawBlade(canvas, renderFrame, renderCommand.bladeVariant, renderFrame.frameTimeNanos)
        }
    }

    private fun drawBlade(canvas: Canvas, frame: MotionRenderFrame, variant: MotionBladeVariant, frameTimeNanos: Long) {
        when (variant) {
            MotionBladeVariant.AZURE -> forceBlades.drawAzure(canvas, frame.phase, frame.alpha, renderStrokeWidth, outline.dpToPx(config.edgeCalibrationDp), outline.dpToPx(config.cornerCalibrationDp), config.cornerShape, frameTimeNanos)
            MotionBladeVariant.CRIMSON -> forceBlades.drawCrimson(canvas, frame.phase, frame.alpha, renderStrokeWidth, outline.dpToPx(config.edgeCalibrationDp), outline.dpToPx(config.cornerCalibrationDp), config.cornerShape, frameTimeNanos)
            MotionBladeVariant.CLASH -> forceBlades.drawClash(canvas, frame.phase, frame.alpha, renderStrokeWidth, outline.dpToPx(config.edgeCalibrationDp), outline.dpToPx(config.cornerCalibrationDp), config.cornerShape, frameTimeNanos)
        }
    }

    /** Keeps normal Impulse on the app's existing solid/palette/gradient paint path. */
    internal fun prepareLightImpulsePaint(alpha: Int, effectPhase: Float, gradientPhase: Float) {
        if (impulseUsesNormalColorTreatment(config)) {
            prepareConventionalPaint(alpha, effectPhase, gradientPhase)
        } else {
            corePaint.shader = null
            corePaint.color = colorWithAlpha(alpha, colorRgb)
        }
    }

    /** Complete calibrated frame used by the static calibration preview. */
    fun drawStaticFrame(canvas: Canvas) {
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
            config.colorMode ==
            HaloColorMode.GRADIENT &&
            config.palette.size > 1
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
        val edgeCalibrationPx = outline.opticalInsetPx + outline.dpToPx(config.edgeCalibrationDp)
        return conventionalPathCache.pathFor(
            strokeWidth = renderStrokeWidth,
            edgeCalibrationPx = edgeCalibrationPx,
            cornerCalibrationPx = outline.dpToPx(config.cornerCalibrationDp),
            cornerShape = config.cornerShape,
            extraEnvelopePx = 0f
        )
    }

    /** Static calibration intentionally reuses the normal runtime contour. */
    internal fun staticPreviewPathForCurrentConfig(): Path =
        stylePathForCurrentConfig()

    private fun updateStrokeParameters() {
        coreStrokeWidth =
            calculateCoreStrokeWidth(
                config.thickness
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
                edgeCalibrationPx = outline.opticalInsetPx + outline.dpToPx(config.edgeCalibrationDp),
                cornerCalibrationPx = outline.dpToPx(config.cornerCalibrationDp),
                cornerShape = config.cornerShape,
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
            gradientPalette.contentEquals(
                config.palette
            )
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
                config.palette.size +
                        1
            ) { index ->
                config.palette[
                    index %
                            config.palette.size
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
                config.palette.copyOf()
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
            config.colorMode ==
            HaloColorMode.GRADIENT ||
            config.palette.size <
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
                    config.palette.size

        val startIndex =
            scaled.toInt() %
                    config.palette.size

        val endIndex =
            (
                    startIndex +
                            1
                    ) %
                    config.palette.size

        val fraction =
            scaled -
                    scaled.toInt()

        return blend(
            config.palette[startIndex],
            config.palette[endIndex],
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

    fun draw(canvas: Canvas, animationProgress: Float, effectPhase: Float, gradientPhase: Float, frameTimeNanos: Long = 0L) =
        pipeline.draw(canvas, animationProgress, effectPhase, gradientPhase, frameTimeNanos)

    fun drawStaticFrame(canvas: Canvas) = pipeline.drawStaticFrame(canvas)

    fun drawCalibrationDiagnostics(canvas: Canvas, rulerLegend: String, registrationLabel: String) =
        pipeline.drawCalibrationDiagnostics(canvas, rulerLegend, registrationLabel)

    fun calibrationEdgePx(): Float = pipeline.calibrationEdgePx()

    internal fun stylePathForCurrentConfig(): Path = pipeline.stylePathForCurrentConfig()

    internal fun staticPreviewPathForCurrentConfig(): Path = pipeline.staticPreviewPathForCurrentConfig()
}
