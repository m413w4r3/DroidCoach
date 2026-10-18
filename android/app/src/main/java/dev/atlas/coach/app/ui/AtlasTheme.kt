package dev.atlas.coach.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AtlasColors = darkColorScheme(
    primary = Color(0xFFD6EF83),
    onPrimary = Color(0xFF202416),
    background = Color(0xFF111318),
    onBackground = Color(0xFFF1F0E9),
    surface = Color(0xFF1B1D23),
    onSurface = Color(0xFFF1F0E9),
    onSurfaceVariant = Color(0xFFB4B6BD),
)

@Composable
fun AtlasTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AtlasColors,
        content = content,
    )
}
