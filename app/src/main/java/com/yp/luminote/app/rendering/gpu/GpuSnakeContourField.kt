package com.yp.luminote.app.rendering.gpu

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import com.yp.luminote.app.animation.plan.GeometryRequest
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.geometry.EdgePathGeometryProvider
import kotlin.math.*

/** Provider-derived data only: R unsigned distance/24px, GB unsigned 16-bit arclength fraction.
 * Native texels, no color-managed input sampling; periodic interpolation belongs to the shader.
 * Preparation uses one temporary IntArray, released after upload, and no full-size float array.
 */
internal class GpuSnakeContourField(val key: Key, val bitmap: Bitmap, val input: BitmapShader, val length: Float) {
    data class Key(val outlineVersion: Int, val epoch: Int, val width: Int, val height: Int,
        val stroke: Float, val edgePx: Float, val cornerPx: Float, val shape: Float, val format: Int = 1)
    fun dispose() = bitmap.recycle()

    companion object {
        const val MAX_DISTANCE = 24f
        const val MAX_PIXELS = 8_388_608
        fun key(outline: DisplayOutline, epoch: Int, width: Int, height: Int, geometry: GeometryRequest.DisplayOutlineContour) = Key(
            outline.version, epoch, width, height, 4f + geometry.thickness * 10f,
            outline.opticalInsetPx + outline.dpToPx(geometry.edgeCalibrationDp),
            outline.dpToPx(geometry.cornerCalibrationDp), geometry.cornerShape)

        fun build(provider: EdgePathGeometryProvider, key: Key): GpuSnakeContourField? {
            if (key.width !in 1..4096 || key.height !in 1..4096 || key.width.toLong() * key.height > MAX_PIXELS) return null
            val snapshot = provider.geometryFor(key.stroke, key.edgePx, key.cornerPx, key.shape, 0f) ?: return null
            val length = snapshot.measure.length
            if (length <= 0f || snapshot.measure.nextContour()) return null
            val points = snapshot.path.approximate(.125f)
            if (points.size < 6) return null
            val pixels = IntArray(key.width * key.height) { 0xffff0000.toInt() }
            var i = 0
            while (i + 5 < points.size) {
                val ax = points[i+1]; val ay = points[i+2]; val bx = points[i+4]; val by = points[i+5]
                val dx = bx-ax; val dy = by-ay; val span = dx*dx+dy*dy
                val left = floor(min(ax,bx)-MAX_DISTANCE).toInt().coerceAtLeast(0)
                val right = ceil(max(ax,bx)+MAX_DISTANCE).toInt().coerceAtMost(key.width-1)
                val top = floor(min(ay,by)-MAX_DISTANCE).toInt().coerceAtLeast(0)
                val bottom = ceil(max(ay,by)+MAX_DISTANCE).toInt().coerceAtMost(key.height-1)
                for (y in top..bottom) for (x in left..right) {
                    val t = if (span <= 0f) 0f else (((x+.5f-ax)*dx+(y+.5f-ay)*dy)/span).coerceIn(0f,1f)
                    val distance = hypot(x+.5f-ax-dx*t,y+.5f-ay-dy*t)
                    val encoded = (distance/MAX_DISTANCE*255f).roundToInt().coerceIn(0,255)
                    val index = y*key.width+x
                    if (encoded < ((pixels[index] ushr 16) and 255)) {
                        val progress = ((points[i]+(points[i+3]-points[i])*t)*65535f).roundToInt().coerceIn(0,65535)
                        pixels[index] = 0xff000000.toInt() or (encoded shl 16) or progress
                    }
                }
                i += 3
            }
            val bitmap = Bitmap.createBitmap(key.width,key.height,Bitmap.Config.ARGB_8888)
            try {
                bitmap.setPixels(pixels,0,key.width,0,0,key.width,key.height)
                val input = BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)
                input.setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
                return GpuSnakeContourField(key,bitmap,input,length)
            } catch (error: RuntimeException) { bitmap.recycle(); throw error }
        }
    }
}
