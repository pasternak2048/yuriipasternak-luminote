package com.yp.luminote.app.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

@Composable
fun rememberLuminoteWindowSizeClass():
        LuminoteWindowSizeClass {

    val windowInfo =
        LocalWindowInfo.current

    val density =
        LocalDensity.current

    val widthDp =
        with(density) {
            windowInfo.containerSize.width
                .toDp()
                .value
        }

    return when {
        widthDp < 600f ->
            LuminoteWindowSizeClass.COMPACT

        widthDp < 840f ->
            LuminoteWindowSizeClass.MEDIUM

        else ->
            LuminoteWindowSizeClass.EXPANDED
    }
}