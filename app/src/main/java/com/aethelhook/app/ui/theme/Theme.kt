package com.aethelhook.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary          = DarkPalette.accentCyan,
    onPrimary        = DarkPalette.bgDeep,
    secondary        = DarkPalette.accentPurple,
    onSecondary      = DarkPalette.textPrimary,
    tertiary         = DarkPalette.accentGreen,
    background       = DarkPalette.bgDeep,
    surface          = DarkPalette.bgCard,
    onBackground     = DarkPalette.textPrimary,
    onSurface        = DarkPalette.textPrimary,
    surfaceVariant   = DarkPalette.bgCardAlt,
    onSurfaceVariant = DarkPalette.textSecondary,
    outline          = DarkPalette.divider,
    error            = DarkPalette.accentRed,
)

private val LightScheme = lightColorScheme(
    primary          = LightPalette.accentCyan,
    onPrimary        = Color.White,
    secondary        = LightPalette.accentPurple,
    onSecondary      = Color.White,
    tertiary         = LightPalette.accentGreen,
    background       = LightPalette.bgDeep,
    surface          = LightPalette.bgCard,
    onBackground     = LightPalette.textPrimary,
    onSurface        = LightPalette.textPrimary,
    surfaceVariant   = LightPalette.bgCardAlt,
    onSurfaceVariant = LightPalette.textSecondary,
    outline          = LightPalette.divider,
    error            = LightPalette.accentRed,
)

@Composable
fun AethelHookTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    val colorScheme = if (darkTheme) DarkScheme else LightScheme
    CompositionLocalProvider(LocalAethelColors provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = Typography,
            content     = content
        )
    }
}
