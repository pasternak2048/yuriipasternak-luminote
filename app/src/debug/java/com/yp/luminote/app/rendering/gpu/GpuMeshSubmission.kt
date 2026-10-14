package com.yp.luminote.app.rendering.gpu

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Mesh
import android.graphics.Paint
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.rendering.api.HaloRenderTarget

/** Debug-only Mesh submission and AGSL witness injection. */
internal interface GpuMeshSubmission {
    fun drawFull(canvas: Canvas, mesh: Mesh, paint: Paint)
    fun drawLight(canvas: Canvas, mesh: Mesh, paint: Paint)
    fun fallback(reason: String, cause: Throwable, config: HaloConfig, target: HaloRenderTarget, hardware: Boolean?)
    fun invalidate()
    fun dispose()

    companion object { fun create(config: HaloConfig, outline: DisplayOutline): GpuMeshSubmission = DebugGpuMeshSubmission(GpuDebugProof.enabled(), config, outline) }
}

private class DebugGpuMeshSubmission(private val witnessEnabled: Boolean, private val config: HaloConfig, private val outline: DisplayOutline) : GpuMeshSubmission {
    private val witnessPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.TRANSPARENT; alpha = 0 }
    private val compositeWitnessEnabled = GpuDebugProof.compositeEnabled(witnessEnabled)
    private val compositeWitnessPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 255 }
    private var sessionLogged = false
    private var witnessUniformLogged = false
    private var witnessDrawLogged = false
    private var fallbackLogged = false
    private var invalidateLogged = false
    private var disposeLogged = false
    override fun drawFull(canvas: Canvas, mesh: Mesh, paint: Paint) {
        submitWithWitness(canvas, mesh, paint, BlendMode.SRC_OVER)
    }
    override fun drawLight(canvas: Canvas, mesh: Mesh, paint: Paint) {
        submitWithWitness(canvas, mesh, paint, BlendMode.DST)
    }
    private fun submitWithWitness(canvas: Canvas, mesh: Mesh, normalPaint: Paint, normalBlend: BlendMode) {
        logSessionOnce(canvas)
        mesh.setIntUniform("uDebugGpuWitness", if (witnessEnabled) 1 else 0)
        if (witnessEnabled && !witnessUniformLogged) {
            witnessUniformLogged = true
            GpuDebugProof.log("gpu-proof witness-uniform-set witness=1")
        }
        val blend = when {
            !witnessEnabled -> normalBlend
            compositeWitnessEnabled -> BlendMode.MODULATE
            else -> BlendMode.DST
        }
        val drawPaint = when {
            !witnessEnabled -> normalPaint
            compositeWitnessEnabled -> compositeWitnessPaint
            else -> witnessPaint
        }
        canvas.drawMesh(mesh, blend, drawPaint)
        if (witnessEnabled && !witnessDrawLogged) {
            witnessDrawLogged = true
            GpuDebugProof.log("gpu-proof witness-draw-returned witness=1 blend=$blend paintAlpha=${drawPaint.alpha}")
        }
    }
    override fun fallback(reason: String, cause: Throwable, config: HaloConfig, target: HaloRenderTarget, hardware: Boolean?) {
        if (witnessEnabled && !fallbackLogged) {
            fallbackLogged = true
            android.util.Log.w(
                GpuDebugProof.TAG,
                "gpu-proof fallback reason=$reason engine=${config.renderingEngine} renderMode=${config.renderMode} effect=${config.effectPlan} colorMode=${config.colorMode} paletteSize=${config.palette.size} target=${target.javaClass.simpleName} hardware=$hardware",
                cause
            )
        }
    }
    override fun invalidate() { if (witnessEnabled && !invalidateLogged) { invalidateLogged = true; GpuDebugProof.log("gpu-proof invalidate") } }
    override fun dispose() { if (witnessEnabled && !disposeLogged) { disposeLogged = true; GpuDebugProof.log("gpu-proof dispose") } }
    private fun logSessionOnce(canvas: Canvas) {
        if (witnessEnabled && !sessionLogged) {
            sessionLogged = true
            GpuDebugProof.log("gpu-proof session build=${com.yp.luminote.app.BuildConfig.VERSION_NAME}(${com.yp.luminote.app.BuildConfig.VERSION_CODE}) engine=${config.renderingEngine} renderMode=${config.renderMode} effect=${config.effectPlan} hardware=${canvas.isHardwareAccelerated} size=${canvas.width}x${canvas.height} outlineVersion=${outline.version} witness=$witnessEnabled compositeWitness=$compositeWitnessEnabled")
        }
    }
}
