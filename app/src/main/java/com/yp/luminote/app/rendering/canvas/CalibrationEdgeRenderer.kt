package com.yp.luminote.app.rendering.canvas
import com.yp.luminote.app.effects.geometry.DisplayOutline

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Calibration-only drawing; runtime animation never enters this component. */
internal class CalibrationEdgeRenderer(private val outline: DisplayOutline) {
    fun drawStaticFrame(canvas: Canvas, contour: Path, paint: Paint) {
        val saveCount = canvas.save()
        try {
            canvas.clipPath(outline.path)
            canvas.drawPath(contour, paint)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    fun drawDiagnostics(
        canvas: Canvas,
        contour: Path,
        edgeCalibrationPx: Float,
        rulerLegend: String,
        registrationLabel: String
    ) {
        if (outline.path.isEmpty) return
        val runtimeCentrelinePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0xFFFFD54F.toInt()
        }
        val rulerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0x99FFFFFF.toInt()
        }
        val rulerDepth = CalibrationDiagnostics.rulerDepthPx(edgeCalibrationPx)
        val saveCount = canvas.save()
        try {
            canvas.clipPath(outline.path)
            canvas.drawPath(contour, runtimeCentrelinePaint)
            var offset = 0f
            while (offset <= rulerDepth) {
                rulerPaint.strokeWidth = if (offset.toInt() % 5 == 0) 2f else 1f
                canvas.drawLine(offset, 0f, offset, rulerDepth, rulerPaint)
                canvas.drawLine(canvas.width - offset, 0f, canvas.width - offset, rulerDepth, rulerPaint)
                canvas.drawLine(offset, canvas.height.toFloat(), offset, canvas.height - rulerDepth, rulerPaint)
                canvas.drawLine(canvas.width - offset, canvas.height.toFloat(), canvas.width - offset, canvas.height - rulerDepth, rulerPaint)
                offset += 1f
            }
        } finally {
            canvas.restoreToCount(saveCount)
        }
        drawRegistrationWitness(canvas, CalibrationDiagnostics.registration(edgeCalibrationPx),
            "$rulerLegend · $registrationLabel", edgeCalibrationPx, rulerDepth, rulerPaint)
    }

    private fun drawRegistrationWitness(canvas: Canvas, registration: CalibrationDiagnostics.Registration, label: String, edgeCalibrationPx: Float, rulerDepth: Float, paint: Paint) {
        val midX = canvas.width / 2f
        val labelPaint = Paint(paint).apply {
            style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt()
            textSize = outline.dpToPx(12f).coerceAtLeast(16f); textAlign = Paint.Align.CENTER
        }
        val labelBaseline = labelPaint.textSize + 5f
        val labelWidth = labelPaint.measureText(label)
        val labelBackground = Paint(labelPaint).apply { color = 0xD9000000.toInt() }
        canvas.drawRoundRect(RectF(midX - labelWidth / 2f - 6f, 0f, midX + labelWidth / 2f + 6f, labelBaseline + 4f), 4f, 4f, labelBackground)
        canvas.drawText(label, midX, labelBaseline, labelPaint)
        when (registration) {
            CalibrationDiagnostics.Registration.ZERO -> canvas.drawCircle(midX, 0f, 2f, paint)
            CalibrationDiagnostics.Registration.IN -> {
                val markerY = CalibrationDiagnostics.rulerMarkerPx(edgeCalibrationPx).coerceIn(0f, rulerDepth)
                canvas.drawLine(midX, 0f, midX, markerY, paint)
                canvas.drawLine(midX, markerY, midX - 3f, markerY - 4f, paint)
                canvas.drawLine(midX, markerY, midX + 3f, markerY - 4f, paint)
            }
            CalibrationDiagnostics.Registration.OUT -> {
                canvas.drawLine(midX - 4f, 4f, midX, 0f, paint)
                canvas.drawLine(midX, 0f, midX + 4f, 4f, paint)
            }
        }
    }
}
