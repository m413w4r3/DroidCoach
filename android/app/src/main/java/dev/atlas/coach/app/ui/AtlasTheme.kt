package dev.atlas.coach.app.ui

import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

private val AtlasTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 36.sp, lineHeight = 42.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 38.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

private val AtlasShapes = Shapes(
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(10.dp),
)

@Composable
fun AtlasTheme(
    preference: AtlasThemePreference = AtlasThemePreference.DEFAULT,
    content: @Composable () -> Unit,
) {
    val systemIsDark = isSystemInDarkTheme()
    val dark = preference.resolvesToDark(systemIsDark)
    val context = LocalContext.current
    val accentStore = AccentPreferenceStore.get(context)
    val accent = accentStore.currentAccent
    val dynamicColorEnabled = accentStore.dynamicColorEnabled
    val useDynamicColor = shouldUseDynamicColor(Build.VERSION.SDK_INT, dynamicColorEnabled, preference)
    val palette = AtlasThemePalettes.forPreference(preference, systemIsDark, accent)
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
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && useDynamicColor && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && useDynamicColor -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(
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
            errorContainer = Color(palette.errorContainer), onErrorContainer = Color(palette.onErrorContainer),
        )
        else -> lightColorScheme(
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
            errorContainer = Color(palette.errorContainer), onErrorContainer = Color(palette.onErrorContainer),
        )
    }
    val semanticAccentOverride = if (useDynamicColor) scheme.toAccentRoleColors() else null
    val semanticColors = AtlasSemanticPalettes.forPalette(palette, dark).toColors(semanticAccentOverride)

    CompositionLocalProvider(
        LocalConfiguration provides themedConfiguration,
        LocalIndication provides indication,
        LocalAtlasSemanticColors provides semanticColors,
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
        MaterialTheme(colorScheme = scheme, typography = AtlasTypography, shapes = AtlasShapes) {
            Surface(color = scheme.background, contentColor = scheme.onBackground, content = content)
        }
    }
}

private fun ColorScheme.toAccentRoleColors() = AtlasSemanticRoleColors(
    foreground = primary,
    onForeground = onPrimary,
    container = primaryContainer,
    onContainer = onPrimaryContainer,
)

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
