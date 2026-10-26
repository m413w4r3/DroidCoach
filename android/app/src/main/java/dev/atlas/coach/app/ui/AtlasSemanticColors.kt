package dev.atlas.coach.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Immutable

/** A semantic role exposes foreground, solid fill, and readable text for both. */
@Immutable
data class AtlasSemanticRoleColors(
    val foreground: Color,
    val onForeground: Color,
    val container: Color,
    val onContainer: Color,
)

/** Shared semantic roles for UI components; use [LocalAtlasSemanticColors] in Compose. */
@Immutable
data class AtlasSemanticColors(
    val accent: AtlasSemanticRoleColors,
    val success: AtlasSemanticRoleColors,
    val warning: AtlasSemanticRoleColors,
    val error: AtlasSemanticRoleColors,
    val neutral: AtlasSemanticRoleColors,
    val category: AtlasSemanticRoleColors,
)

val LocalAtlasSemanticColors = staticCompositionLocalOf {
    AtlasSemanticPalettes.forPalette(AtlasThemePalettes.dark, isDark = true).toColors()
}

internal data class AtlasSemanticRoleTokens(
    val foreground: Int,
    val onForeground: Int,
    val container: Int,
    val onContainer: Int,
)

internal data class AtlasSemanticPaletteTokens(
    val accent: AtlasSemanticRoleTokens,
    val success: AtlasSemanticRoleTokens,
    val warning: AtlasSemanticRoleTokens,
    val error: AtlasSemanticRoleTokens,
    val neutral: AtlasSemanticRoleTokens,
    val category: AtlasSemanticRoleTokens,
) {
    fun roles(): List<Pair<String, AtlasSemanticRoleTokens>> = listOf(
        "accent" to accent,
        "success" to success,
        "warning" to warning,
        "error" to error,
        "neutral" to neutral,
        "category" to category,
    )

    fun textContrastPairs(): List<ThemeTextContrastPair> = roles().flatMap { (name, role) ->
        listOf(
            ThemeTextContrastPair("$name/onForeground", role.onForeground, role.foreground),
            ThemeTextContrastPair("$name/onContainer", role.onContainer, role.container),
        )
    }

    fun nonTextContrastPairs(background: Int, surface: Int): List<ThemeTextContrastPair> =
        roles().flatMap { (name, role) ->
            listOf(
                ThemeTextContrastPair("$name/background", role.foreground, background),
                ThemeTextContrastPair("$name/surface", role.foreground, surface),
            )
        }

    fun toColors(accentOverride: AtlasSemanticRoleColors? = null): AtlasSemanticColors =
        AtlasSemanticColors(
            accent = accentOverride ?: accent.toColors(),
            success = success.toColors(),
            warning = warning.toColors(),
            error = error.toColors(),
            neutral = neutral.toColors(),
            category = category.toColors(),
        )
}

internal object AtlasSemanticPalettes {
    fun forPalette(palette: AtlasThemePalette, isDark: Boolean): AtlasSemanticPaletteTokens {
        val accent = AtlasSemanticRoleTokens(
            foreground = palette.primary,
            onForeground = palette.onPrimary,
            container = palette.primaryContainer,
            onContainer = palette.onPrimaryContainer,
        )
        val error = AtlasSemanticRoleTokens(
            foreground = palette.error,
            onForeground = palette.onError,
            container = palette.errorContainer,
            onContainer = palette.onErrorContainer,
        )
        return if (isDark) {
            AtlasSemanticPaletteTokens(
                accent = accent,
                success = AtlasSemanticRoleTokens(
                    0xFF8FCFA4.toInt(), 0xFF102C19.toInt(), 0xFF1E3B29.toInt(), 0xFFD9F2DF.toInt(),
                ),
                warning = AtlasSemanticRoleTokens(
                    0xFFE5C179.toInt(), 0xFF34240A.toInt(), 0xFF443512.toInt(), 0xFFF4E7C8.toInt(),
                ),
                error = error,
                neutral = AtlasSemanticRoleTokens(
                    0xFFB3AA94.toInt(), 0xFF2A271F.toInt(), 0xFF39352B.toInt(), 0xFFEFE8D5.toInt(),
                ),
                category = AtlasSemanticRoleTokens(
                    0xFFB8B2E3.toInt(), 0xFF211F35.toInt(), 0xFF312D4A.toInt(), 0xFFEEEAFB.toInt(),
                ),
            )
        } else {
            AtlasSemanticPaletteTokens(
                accent = accent,
                success = AtlasSemanticRoleTokens(
                    0xFF3E704B.toInt(), 0xFFFFFFFF.toInt(), 0xFFDCEBDD.toInt(), 0xFF1D3621.toInt(),
                ),
                warning = AtlasSemanticRoleTokens(
                    0xFF805A12.toInt(), 0xFFFFFFFF.toInt(), 0xFFF3E4BD.toInt(), 0xFF3B2A08.toInt(),
                ),
                error = error,
                neutral = AtlasSemanticRoleTokens(
                    0xFF5B5443.toInt(), 0xFFFFFFFF.toInt(), 0xFFE6E0D2.toInt(), 0xFF28241D.toInt(),
                ),
                category = AtlasSemanticRoleTokens(
                    0xFF55527D.toInt(), 0xFFFFFFFF.toInt(), 0xFFE4E0F4.toInt(), 0xFF292541.toInt(),
                ),
            )
        }
    }
}

private fun AtlasSemanticRoleTokens.toColors(): AtlasSemanticRoleColors = AtlasSemanticRoleColors(
    foreground = Color(foreground),
    onForeground = Color(onForeground),
    container = Color(container),
    onContainer = Color(onContainer),
)
