package dev.atlas.coach.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

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
fun AtlasTheme(
    preference: AtlasThemePreference = AtlasThemePreference.DEFAULT,
    content: @Composable () -> Unit,
) {
    val systemIsDark = isSystemInDarkTheme()
    val dark = preference.resolvesToDark(systemIsDark)
    val palette = when (preference) {
        AtlasThemePreference.AMOLED -> AtlasThemePalettes.amoled
        AtlasThemePreference.LIGHT -> AtlasThemePalettes.light
        AtlasThemePreference.FOLLOW_SYSTEM -> if (systemIsDark) AtlasThemePalettes.dark else AtlasThemePalettes.light
        AtlasThemePreference.DARK -> AtlasThemePalettes.dark
    }
    val configuration = LocalConfiguration.current
    val themedConfiguration = remember(configuration, dark) {
        Configuration(configuration).apply {
            val nightMode = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
        }
    }
    val view = LocalView.current
    val indication = if (preference == AtlasThemePreference.AMOLED) {
        StaticPressIndication(Color(palette.onSurface).copy(alpha = 0.14f))
    } else LocalIndication.current
    val scheme = if (dark) darkColorScheme(
        primary = Color(palette.primary), onPrimary = Color(palette.onPrimary),
        primaryContainer = Color(palette.primaryContainer), onPrimaryContainer = Color(palette.onPrimaryContainer),
        secondary = Color(palette.secondary), onSecondary = Color(palette.onSecondary),
        secondaryContainer = Color(palette.secondaryContainer), onSecondaryContainer = Color(palette.onSecondaryContainer),
        background = Color(palette.background), onBackground = Color(palette.onBackground),
        surface = Color(palette.surface), onSurface = Color(palette.onSurface),
        surfaceDim = Color(palette.surfaceDim), surfaceBright = Color(palette.surfaceBright),
        surfaceContainerLowest = Color(palette.surfaceContainerLowest), surfaceContainerLow = Color(palette.surfaceContainerLow),
        surfaceContainer = Color(palette.surfaceContainer), surfaceContainerHigh = Color(palette.surfaceContainerHigh),
        surfaceContainerHighest = Color(palette.surfaceContainerHighest),
        surfaceVariant = Color(palette.surfaceVariant), onSurfaceVariant = Color(palette.onSurfaceVariant),
        surfaceTint = Color(palette.primary), outline = Color(palette.outline),
        error = Color(palette.error), onError = Color(palette.onError),
    ) else lightColorScheme(
        primary = Color(palette.primary), onPrimary = Color(palette.onPrimary),
        primaryContainer = Color(palette.primaryContainer), onPrimaryContainer = Color(palette.onPrimaryContainer),
        secondary = Color(palette.secondary), onSecondary = Color(palette.onSecondary),
        secondaryContainer = Color(palette.secondaryContainer), onSecondaryContainer = Color(palette.onSecondaryContainer),
        background = Color(palette.background), onBackground = Color(palette.onBackground),
        surface = Color(palette.surface), onSurface = Color(palette.onSurface),
        surfaceDim = Color(palette.surfaceDim), surfaceBright = Color(palette.surfaceBright),
        surfaceContainerLowest = Color(palette.surfaceContainerLowest), surfaceContainerLow = Color(palette.surfaceContainerLow),
        surfaceContainer = Color(palette.surfaceContainer), surfaceContainerHigh = Color(palette.surfaceContainerHigh),
        surfaceContainerHighest = Color(palette.surfaceContainerHighest),
        surfaceVariant = Color(palette.surfaceVariant), onSurfaceVariant = Color(palette.onSurfaceVariant),
        surfaceTint = Color(palette.primary), outline = Color(palette.outline),
        error = Color(palette.error), onError = Color(palette.onError),
    )

    CompositionLocalProvider(
        LocalConfiguration provides themedConfiguration,
        LocalIndication provides indication,
    ) {
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                @Suppress("DEPRECATION")
                run {
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    window.navigationBarColor = android.graphics.Color.TRANSPARENT
                }
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        MaterialTheme(colorScheme = scheme, typography = AtlasTypography) {
            Surface(color = scheme.background, contentColor = scheme.onBackground, content = content)
        }
    }
}

/** Static pressed overlay for AMOLED: tactile feedback without a ripple animation. */
internal class StaticPressIndication(private val pressedColor: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = StaticPressNode(interactionSource, pressedColor)

    override fun equals(other: Any?): Boolean = other is StaticPressIndication && other.pressedColor == pressedColor

    override fun hashCode(): Int = pressedColor.hashCode()
}

private class StaticPressNode(
    private val interactionSource: InteractionSource,
    private val pressedColor: Color,
) : Modifier.Node(), DrawModifierNode {
    private var pressed = false

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressed = true
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (pressed) drawRect(pressedColor)
    }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
