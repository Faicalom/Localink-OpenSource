package com.localbridge.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = Mint300,
    onPrimary = Night900,
    secondary = Sky500,
    onSecondary = White,
    secondaryContainer = Night600,
    onSecondaryContainer = White,
    tertiary = Amber400,
    background = Night900,
    onBackground = White,
    surface = Night800,
    onSurface = White,
    surfaceVariant = Night700,
    onSurfaceVariant = Day200,
    surfaceTint = Mint400,
    error = ErrorSoft
)

private val LightColors = lightColorScheme(
    primary = InkBlue,
    onPrimary = White,
    secondary = Sky600,
    onSecondary = White,
    secondaryContainer = Sky100,
    onSecondaryContainer = InkBlue,
    tertiary = Amber400,
    background = Day50,
    onBackground = Day900,
    surface = Day100,
    onSurface = Day900,
    surfaceVariant = White,
    onSurfaceVariant = InkBlue,
    surfaceTint = Sky500
)

@Composable
fun LocalBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = LocalBridgeTypography,
        shapes = LocalBridgeShapes,
        content = content
    )
}
