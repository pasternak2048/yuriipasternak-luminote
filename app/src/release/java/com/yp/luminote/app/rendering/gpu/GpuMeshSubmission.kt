package com.yp.luminote.app.rendering.gpu

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Mesh
import android.graphics.Paint
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.rendering.api.HaloRenderTarget

/** Release implementation: no witness uniform, shader branch, logging, or diagnostic allocation. */
internal interface GpuMeshSubmission {
    fun drawFull(canvas: Canvas, mesh: Mesh, paint: Paint)
    fun drawLight(canvas: Canvas, mesh: Mesh, paint: Paint)
    fun fallback(reason: String, cause: Throwable, config: HaloConfig, target: HaloRenderTarget, hardware: Boolean?)
    fun invalidate()
    fun dispose()

    companion object { fun create(config: HaloConfig, outline: DisplayOutline): GpuMeshSubmission = ReleaseGpuMeshSubmission }
}

private object ReleaseGpuMeshSubmission : GpuMeshSubmission {
    override fun drawFull(canvas: Canvas, mesh: Mesh, paint: Paint) { canvas.drawMesh(mesh, BlendMode.SRC_OVER, paint) }
    override fun drawLight(canvas: Canvas, mesh: Mesh, paint: Paint) { canvas.drawMesh(mesh, BlendMode.DST, paint) }
    override fun fallback(reason: String, cause: Throwable, config: HaloConfig, target: HaloRenderTarget, hardware: Boolean?) = Unit
    override fun invalidate() = Unit
    override fun dispose() = Unit
}
