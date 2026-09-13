package com.yp.luminote.app.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color

/** Extracts the dominant vivid color once per app package. */
internal class AppIconColorResolver(private val context: Context) {
    private val colors = mutableMapOf<String, Int>()

    fun colorFor(packageName: String, fallback: Int): Int = synchronized(colors) {
        colors.getOrPut(packageName) {
            runCatching { dominantIconColor(packageName) }.getOrNull() ?: fallback
        }
    }

    private fun dominantIconColor(packageName: String): Int {
        val icon = context.packageManager.getApplicationIcon(packageName)
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        return try {
            Canvas(bitmap).also { canvas ->
                icon.setBounds(0, 0, SIZE, SIZE)
                icon.draw(canvas)
            }

            val buckets = mutableMapOf<Int, ColorBucket>()
            for (x in 0 until SIZE step SAMPLE_STEP) {
                for (y in 0 until SIZE step SAMPLE_STEP) {
                    val color = bitmap.getPixel(x, y)
                    val alpha = Color.alpha(color)
                    val red = Color.red(color)
                    val green = Color.green(color)
                    val blue = Color.blue(color)
                    val value = maxOf(red, green, blue) / COLOR_MAX
                    val saturation = if (value > 0f) {
                        (maxOf(red, green, blue) - minOf(red, green, blue)) / (value * COLOR_MAX)
                    } else {
                        0f
                    }

                    if (alpha < MIN_ALPHA || value < MIN_VALUE || saturation < MIN_SATURATION) continue

                    val bucketKey =
                        ((red shr BUCKET_SHIFT) shl 6) or
                            ((green shr BUCKET_SHIFT) shl 3) or
                            (blue shr BUCKET_SHIFT)
                    buckets.getOrPut(bucketKey, ::ColorBucket).add(red, green, blue, saturation)
                }
            }

            val dominantBucket = buckets.values.maxByOrNull { bucket ->
                bucket.count * (BASE_FREQUENCY_WEIGHT + bucket.averageSaturation * SATURATION_BONUS)
            }
            dominantBucket?.averageColor() ?: Color.WHITE
        } finally {
            bitmap.recycle()
        }
    }

    private class ColorBucket {
        var count = 0
            private set
        private var redTotal = 0L
        private var greenTotal = 0L
        private var blueTotal = 0L
        private var saturationTotal = 0f

        val averageSaturation: Float
            get() = saturationTotal / count.coerceAtLeast(1)

        fun add(red: Int, green: Int, blue: Int, saturation: Float) {
            count++
            redTotal += red
            greenTotal += green
            blueTotal += blue
            saturationTotal += saturation
        }

        fun averageColor(): Int = Color.rgb(
            (redTotal / count).toInt(),
            (greenTotal / count).toInt(),
            (blueTotal / count).toInt()
        )
    }

    private companion object {
        const val SIZE = 48
        const val SAMPLE_STEP = 2
        const val MIN_ALPHA = 160
        const val MIN_VALUE = 0.12f
        const val MIN_SATURATION = 0.18f
        const val COLOR_MAX = 255f
        const val BUCKET_SHIFT = 5
        const val BASE_FREQUENCY_WEIGHT = 0.8f
        const val SATURATION_BONUS = 0.2f
    }
}
