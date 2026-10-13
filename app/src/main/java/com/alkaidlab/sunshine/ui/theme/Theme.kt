package com.alkaidlab.sunshine.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF345ECC), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE3FA), onPrimaryContainer = Color(0xFF284CA8),
    secondary = Color(0xFF596275), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E9EF), onSecondaryContainer = Color(0xFF1B2435),
    background = Color(0xFFF4F1EA), onBackground = Color(0xFF1B2435),
    surface = Color(0xFFFBFAF7), onSurface = Color(0xFF1B2435),
    surfaceVariant = Color(0xFFE8E9EF), onSurfaceVariant = Color(0xFF596275),
    outline = Color(0xFF788198), outlineVariant = Color(0xFFD4D9E3),
    error = Color(0xFFA52828), onError = Color.White
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CAEFF), onPrimary = Color(0xFF1B2435),
    primaryContainer = Color(0xFF2B3A61), onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = Color(0xFFB4BFD5), onSecondary = Color(0xFF1B2435),
    secondaryContainer = Color(0xFF222D46), onSecondaryContainer = Color(0xFFEDF2FF),
    background = Color(0xFF101624), onBackground = Color(0xFFEDF2FF),
    surface = Color(0xFF172035), onSurface = Color(0xFFEDF2FF),
    surfaceVariant = Color(0xFF222D46), onSurfaceVariant = Color(0xFFB4BFD5),
    outline = Color(0xFF8895B0), outlineVariant = Color(0xFF38465F),
    error = Color(0xFFFFC0B5), onError = Color(0xFF690005)
)
private val SunshineTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun SunshineTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                window.statusBarColor = colors.background.toArgb()
                window.navigationBarColor = colors.surface.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }
    val status = if (darkTheme) StatusColors(Color(0xFF89DBAD), Color(0xFFF1C477))
        else StatusColors(Color(0xFF176944), Color(0xFF855400))
    CompositionLocalProvider(LocalStatusColors provides status) {
        MaterialTheme(colorScheme = colors, typography = SunshineTypography,
            shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp),
                large = RoundedCornerShape(24.dp)), content = content)
    }
}
