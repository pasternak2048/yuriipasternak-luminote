package com.yp.luminote.app.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Layout values that adapt to the usable window height without overriding the
 * system font scale. This keeps the UI accessible while leaving enough room
 * for controls on compact Samsung windows and in split-screen mode.
 */
@Immutable
data class LuminoteUiMetrics(
    val isCompactHeight: Boolean,
    val headerTopSpacing: Dp,
    val sectionSpacing: Dp,
    val cardPadding: Dp,
    val cardCornerRadius: Dp,
    val backButtonSize: Dp
)

@Composable
fun rememberLuminoteUiMetrics(): LuminoteUiMetrics {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val heightDp = with(density) { windowInfo.containerSize.height.toDp().value }
    val isCompactHeight = heightDp < 720f || density.fontScale > 1.15f

    return remember(isCompactHeight) {
        if (isCompactHeight) {
            LuminoteUiMetrics(
                isCompactHeight = true,
                headerTopSpacing = 16.dp,
                sectionSpacing = 12.dp,
                cardPadding = 16.dp,
                cardCornerRadius = 24.dp,
                backButtonSize = 44.dp
            )
        } else {
            LuminoteUiMetrics(
                isCompactHeight = false,
                headerTopSpacing = 24.dp,
                sectionSpacing = 16.dp,
                cardPadding = 20.dp,
                cardCornerRadius = 28.dp,
                backButtonSize = 48.dp
            )
        }
    }
}
