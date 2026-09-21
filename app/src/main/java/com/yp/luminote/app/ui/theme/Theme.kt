package com.yp.luminote.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LuminoteLightColorScheme = lightColorScheme(
    primary = LuminoteLightPrimary,
    onPrimary = LuminoteLightSurface,

    secondary = LuminoteLightPrimary,
    onSecondary = LuminoteLightSurface,

    background = LuminoteLightBackground,
    onBackground = LuminoteLightOnBackground,

    surface = LuminoteLightSurface,
    onSurface = LuminoteLightOnSurface,

    surfaceVariant = LuminoteLightSurfaceVariant,
    onSurfaceVariant = LuminoteLightSecondaryText,

    error = LuminoteError
)

private val LuminoteDarkColorScheme = darkColorScheme(
    primary = LuminoteDarkPrimary,
    onPrimary = LuminoteDarkBackground,

    secondary = LuminoteDarkPrimary,
    onSecondary = LuminoteDarkBackground,

    background = LuminoteDarkBackground,
    onBackground = LuminoteDarkOnBackground,

    surface = LuminoteDarkSurface,
    onSurface = LuminoteDarkOnSurface,

    surfaceVariant = LuminoteDarkSurfaceVariant,
    onSurfaceVariant = LuminoteDarkSecondaryText,

    error = LuminoteError
)

@Composable
fun LuminoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor -> {
            if (darkTheme) {
                androidx.compose.material3.dynamicDarkColorScheme(
                    androidx.compose.ui.platform.LocalContext.current
                )
            } else {
                androidx.compose.material3.dynamicLightColorScheme(
                    androidx.compose.ui.platform.LocalContext.current
                )
            }
        }

        darkTheme -> LuminoteDarkColorScheme
        else -> LuminoteLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LuminoteTypography,
        shapes = LuminoteShapes,
        content = content
    )
}
