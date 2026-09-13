package com.yp.luminote.app.effects

import android.util.DisplayMetrics
import android.view.Display

/** Physical display measurements required for a full-screen overlay window. */
internal object OverlayDisplayMetrics {

    @Suppress("DEPRECATION")
    fun realMetrics(display: Display): DisplayMetrics =
        DisplayMetrics().also(display::getRealMetrics)
}
