package com.yp.luminote.app.effects

import kotlin.math.abs
import kotlin.math.ceil

/** Pure policy shared by the static calibration witness and its focused unit tests. */
internal object CalibrationDiagnostics {
    fun shouldRender(staticFrameMode: Boolean, width: Int, height: Int): Boolean =
        staticFrameMode && width > 0 && height > 0

    fun registration(edgeCalibrationPx: Float): Registration =
        when {
            edgeCalibrationPx > EPSILON_PX -> Registration.IN
            edgeCalibrationPx < -EPSILON_PX -> Registration.OUT
            else -> Registration.ZERO
        }

    /** Rulers measure physical pixels, including the next whole tick after a fractional value. */
    fun rulerDepthPx(edgeCalibrationPx: Float): Float =
        (ceil(abs(edgeCalibrationPx)).toInt() + RULER_PADDING_PX).toFloat()

    fun rulerMarkerPx(edgeCalibrationPx: Float): Float = abs(edgeCalibrationPx)

    /** Text is formatted by Android resources so its direction and units follow the app locale. */
    fun registrationMagnitudePx(edgeCalibrationPx: Float): Float = abs(edgeCalibrationPx)

    enum class Registration { ZERO, IN, OUT }

    private const val EPSILON_PX = 0.01f
    private const val RULER_PADDING_PX = 4
}
