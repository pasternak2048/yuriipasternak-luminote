package com.yp.luminote.app.ui.adaptive

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection

@Composable
fun Modifier.luminoteSafeHorizontalPadding(): Modifier {
    val density =
        LocalDensity.current

    val layoutDirection =
        LocalLayoutDirection.current

    val safeDrawing =
        WindowInsets.safeDrawing

    val leftInset =
        with(density) {
            safeDrawing
                .getLeft(
                    this,
                    layoutDirection
                )
                .toDp()
        }

    val rightInset =
        with(density) {
            safeDrawing
                .getRight(
                    this,
                    layoutDirection
                )
                .toDp()
        }

    return this.padding(
        start =
            24.dp +
                    leftInset,
        end =
            24.dp +
                    rightInset
    )
}