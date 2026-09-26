package com.yp.luminote.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class LuminoteStatusColors(
    val successText: Color,
    val warningText: Color
)

internal fun luminoteStatusColors(darkTheme: Boolean): LuminoteStatusColors =
    if (darkTheme) {
        LuminoteStatusColors(LuminoteSuccess, LuminoteWarning)
    } else {
        LuminoteStatusColors(
            successText = Color(0xFF147A38),
            warningText = Color(0xFF805600)
        )
    }

val LocalLuminoteStatusColors = staticCompositionLocalOf {
    luminoteStatusColors(darkTheme = false)
}
