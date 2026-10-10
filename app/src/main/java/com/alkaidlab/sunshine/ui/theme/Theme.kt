package com.alkaidlab.sunshine.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryCyan,
    onPrimary = DarkBg,
    primaryContainer = CardBorder,
    onPrimaryContainer = TextPrimary,
    secondary = AccentGreen,
    onSecondary = DarkBg,
    background = DarkBg,
    onBackground = TextPrimary,
    surface = CardBg,
    onSurface = TextPrimary,
    surfaceVariant = CardBorder,
    onSurfaceVariant = TextSecondary,
    error = AccentRed,
    onError = TextPrimary
)

@Composable
fun SunshineTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
