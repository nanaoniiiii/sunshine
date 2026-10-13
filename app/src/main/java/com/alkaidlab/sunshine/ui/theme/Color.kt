package com.alkaidlab.sunshine.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class StatusColors(val success: Color, val warning: Color)
val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(Color(0xFF176944), Color(0xFF855400))
}
val DarkBg: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
val CardBg: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface
val CardBorder: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outlineVariant
val PrimaryCyan: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
val AccentGreen: Color @Composable @ReadOnlyComposable get() = LocalStatusColors.current.success
val AccentOrange: Color @Composable @ReadOnlyComposable get() = LocalStatusColors.current.warning
val AccentRed: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.error
val TextPrimary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
val TextSecondary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
val TextMuted: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
