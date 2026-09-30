package com.yp.luminote.app.effects

/** Keeps ordinary finite-preview cleanup from terminating the separately leased calibration view. */
internal object CalibrationPreviewCommandPolicy {
    fun ignoreTokenlessPreviewStop(calibrationToken: String?): Boolean =
        CalibrationPreviewSession.isCurrent(calibrationToken)

    /** Calibration is an in-app foreground preview, never an accessibility/lock-screen overlay. */
    fun bypassAccessibilityRoute(calibrationToken: String?, stopCalibration: Boolean): Boolean =
        calibrationToken != null || stopCalibration
}
