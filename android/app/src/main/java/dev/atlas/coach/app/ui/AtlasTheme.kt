package dev.atlas.coach.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val AtlasDarkColors = darkColorScheme(
    primary = Color(0xFFD6EF83),
    onPrimary = Color(0xFF202416),
    primaryContainer = Color(0xFF303820),
    onPrimaryContainer = Color(0xFFE5F5AD),
    secondary = Color(0xFFC1CAAA),
    onSecondary = Color(0xFF2A3320),
    secondaryContainer = Color(0xFF3A4430),
    onSecondaryContainer = Color(0xFFDDE8C4),
    background = Color(0xFF111318),
    onBackground = Color(0xFFF1F0E9),
    surface = Color(0xFF1B1D23),
    onSurface = Color(0xFFF1F0E9),
    surfaceVariant = Color(0xFF292C33),
    surfaceTint = Color(0xFFD6EF83),
    onSurfaceVariant = Color(0xFFC4C6CE),
    outline = Color(0xFF858893),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val AtlasLightColors = lightColorScheme(
    primary = Color(0xFF405900),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3F0C3),
    onPrimaryContainer = Color(0xFF1B2600),
    secondary = Color(0xFF53633E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E9D0),
    onSecondaryContainer = Color(0xFF17210A),
    background = Color(0xFFF7F8F2),
    onBackground = Color(0xFF191C16),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C16),
    surfaceVariant = Color(0xFFE8EAE1),
    surfaceTint = Color(0xFF405900),
    onSurfaceVariant = Color(0xFF41443A),
    outline = Color(0xFF6C7063),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

private val AtlasTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun AtlasTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) AtlasDarkColors else AtlasLightColors,
        typography = AtlasTypography,
        content = content,
    )
}
