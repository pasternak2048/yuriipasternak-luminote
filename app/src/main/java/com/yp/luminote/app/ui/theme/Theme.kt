package com.yp.luminote.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.yp.luminote.app.data.settings.ThemeMode

private val LuminoteLightColorScheme = lightColorScheme(
    primary = LuminoteLightPrimary,
    onPrimary = LuminoteLightSurface,

    secondary = LuminoteLightPrimary,
    onSecondary = LuminoteLightSurface,
    secondaryContainer = LuminoteLightSurfaceVariant,
    onSecondaryContainer = LuminoteLightOnSurface,

    background = LuminoteLightBackground,
    onBackground = LuminoteLightOnBackground,

    surface = LuminoteLightSurface,
    onSurface = LuminoteLightOnSurface,

    surfaceVariant = LuminoteLightSurfaceVariant,
    onSurfaceVariant = LuminoteLightSecondaryText,

    error = LuminoteError,
    onError = LuminoteLightSurface,
    outline = Color(0xFF74747C),
    outlineVariant = Color(0xFFC4C6CD),
    surfaceContainerHighest = LuminoteLightSurfaceVariant
)

private val LuminoteDarkColorScheme = darkColorScheme(
    primary = LuminoteDarkPrimary,
    onPrimary = LuminoteDarkBackground,

    secondary = LuminoteDarkPrimary,
    onSecondary = LuminoteDarkBackground,
    secondaryContainer = Color(0xFF303030),
    onSecondaryContainer = LuminoteDarkOnSurface,

    background = LuminoteDarkBackground,
    onBackground = LuminoteDarkOnBackground,

    surface = LuminoteDarkSurface,
    onSurface = LuminoteDarkOnSurface,

    surfaceVariant = LuminoteDarkSurfaceVariant,
    onSurfaceVariant = LuminoteDarkSecondaryText,

    error = LuminoteError,
    onError = LuminoteDarkBackground,
    outline = LuminoteDarkOutline,
    outlineVariant = LuminoteDarkOutline,
    surfaceContainerHighest = LuminoteDarkSurfaceVariant
)

fun ThemeMode.resolveDarkTheme(systemIsDark: Boolean): Boolean =
    when (this) {
        ThemeMode.SYSTEM -> systemIsDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

@Composable
fun LuminoteTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = themeMode.resolveDarkTheme(isSystemInDarkTheme())
    val colorScheme = if (darkTheme) LuminoteDarkColorScheme else LuminoteLightColorScheme
    val view = LocalView.current

    SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LuminoteTypography,
        shapes = LuminoteShapes
    ) {
        CompositionLocalProvider(
            LocalLuminoteStatusColors provides luminoteStatusColors(darkTheme)
        ) { content() }
    }
}
