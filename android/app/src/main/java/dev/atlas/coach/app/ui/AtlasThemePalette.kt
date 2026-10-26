package dev.atlas.coach.app.ui

/** ARGB tokens kept as integers so contrast checks run in plain JVM tests. */
internal data class AtlasThemePalette(
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val onSecondary: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val background: Int,
    val onBackground: Int,
    val surface: Int,
    val onSurface: Int,
    val surfaceDim: Int,
    val surfaceBright: Int,
    val surfaceContainerLowest: Int,
    val surfaceContainerLow: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val surfaceVariant: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
    val error: Int,
    val onError: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
) {
    fun textContrastPairs(): List<ThemeTextContrastPair> = listOf(
        ThemeTextContrastPair("primary/onPrimary", onPrimary, primary),
        ThemeTextContrastPair("primaryContainer/onPrimaryContainer", onPrimaryContainer, primaryContainer),
        ThemeTextContrastPair("secondary/onSecondary", onSecondary, secondary),
        ThemeTextContrastPair("secondaryContainer/onSecondaryContainer", onSecondaryContainer, secondaryContainer),
        ThemeTextContrastPair("background/secondary", secondary, background),
        ThemeTextContrastPair("surface/secondary", secondary, surface),
        ThemeTextContrastPair("surfaceContainerLow/secondary", secondary, surfaceContainerLow),
        ThemeTextContrastPair("background/onBackground", onBackground, background),
        ThemeTextContrastPair("surface/onSurface", onSurface, surface),
        ThemeTextContrastPair("surfaceContainerLowest/onSurface", onSurface, surfaceContainerLowest),
        ThemeTextContrastPair("surfaceContainerLow/onSurface", onSurface, surfaceContainerLow),
        ThemeTextContrastPair("surfaceContainer/onSurface", onSurface, surfaceContainer),
        ThemeTextContrastPair("surfaceContainerHigh/onSurface", onSurface, surfaceContainerHigh),
        ThemeTextContrastPair("surfaceContainerHighest/onSurface", onSurface, surfaceContainerHighest),
        ThemeTextContrastPair("background/primary", primary, background),
        ThemeTextContrastPair("surface/primary", primary, surface),
        ThemeTextContrastPair("surfaceContainerLow/primary", primary, surfaceContainerLow),
        ThemeTextContrastPair("surfaceVariant/onSurfaceVariant", onSurfaceVariant, surfaceVariant),
        // onSurfaceVariant is used for secondary copy on the main surface too.
        ThemeTextContrastPair("surface/onSurfaceVariant", onSurfaceVariant, surface),
        ThemeTextContrastPair("surfaceContainerLow/onSurfaceVariant", onSurfaceVariant, surfaceContainerLow),
        ThemeTextContrastPair("background/onSurfaceVariant", onSurfaceVariant, background),
        ThemeTextContrastPair("background/error", error, background),
        ThemeTextContrastPair("surface/error", error, surface),
        ThemeTextContrastPair("error/onError", onError, error),
        ThemeTextContrastPair("errorContainer/onErrorContainer", onErrorContainer, errorContainer),
    )
}

internal data class ThemeTextContrastPair(val name: String, val foreground: Int, val background: Int)

internal object AtlasThemePalettes {
    val dark = AtlasThemePalette(
        primary = 0xFFB49A63.toInt(), onPrimary = 0xFF211D15.toInt(),
        primaryContainer = 0xFF332D20.toInt(), onPrimaryContainer = 0xFFF0E7D1.toInt(),
        secondary = 0xFFC7BDA9.toInt(), onSecondary = 0xFF252219.toInt(),
        secondaryContainer = 0xFF383329.toInt(), onSecondaryContainer = 0xFFEDE4D0.toInt(),
        background = 0xFF11100F.toInt(), onBackground = 0xFFF2EBDD.toInt(),
        surface = 0xFF151412.toInt(), onSurface = 0xFFF2EBDD.toInt(),
        surfaceDim = 0xFF10100F.toInt(), surfaceBright = 0xFF343126.toInt(),
        surfaceContainerLowest = 0xFF0C0B09.toInt(), surfaceContainerLow = 0xFF171613.toInt(),
        surfaceContainer = 0xFF1C1B17.toInt(), surfaceContainerHigh = 0xFF24221D.toInt(),
        surfaceContainerHighest = 0xFF2C2A24.toInt(),
        surfaceVariant = 0xFF25231E.toInt(), onSurfaceVariant = 0xFFC7BDA9.toInt(),
        outline = 0xFF8D8574.toInt(), error = 0xFFD99A8F.toInt(), onError = 0xFF2A1210.toInt(),
        errorContainer = 0xFF3A211D.toInt(), onErrorContainer = 0xFFF3DCD5.toInt(),
    )

    val amoled = AtlasThemePalette(
        primary = 0xFFB49A63.toInt(), onPrimary = 0xFF211D15.toInt(),
        primaryContainer = 0xFF1F1B13.toInt(), onPrimaryContainer = 0xFFF0E7D1.toInt(),
        secondary = 0xFFC7BDA9.toInt(), onSecondary = 0xFF211F19.toInt(),
        secondaryContainer = 0xFF242119.toInt(), onSecondaryContainer = 0xFFEDE4D0.toInt(),
        background = 0xFF000000.toInt(), onBackground = 0xFFF2EBDD.toInt(),
        surface = 0xFF000000.toInt(), onSurface = 0xFFF2EBDD.toInt(),
        surfaceDim = 0xFF000000.toInt(), surfaceBright = 0xFF29271F.toInt(),
        surfaceContainerLowest = 0xFF000000.toInt(), surfaceContainerLow = 0xFF0B0A08.toInt(),
        surfaceContainer = 0xFF12110F.toInt(), surfaceContainerHigh = 0xFF1A1915.toInt(),
        surfaceContainerHighest = 0xFF24221D.toInt(),
        surfaceVariant = 0xFF12110F.toInt(), onSurfaceVariant = 0xFFC7BDA9.toInt(),
        outline = 0xFF8D8574.toInt(), error = 0xFFD99A8F.toInt(), onError = 0xFF2A1210.toInt(),
        errorContainer = 0xFF3A211D.toInt(), onErrorContainer = 0xFFF3DCD5.toInt(),
    )

    val light = AtlasThemePalette(
        primary = 0xFF705D37.toInt(), onPrimary = 0xFFFFFBF2.toInt(),
        primaryContainer = 0xFFE8DEC4.toInt(), onPrimaryContainer = 0xFF292319.toInt(),
        secondary = 0xFF5B5443.toInt(), onSecondary = 0xFFFFFBF2.toInt(),
        secondaryContainer = 0xFFE6E0D2.toInt(), onSecondaryContainer = 0xFF28241D.toInt(),
        background = 0xFFF2EEE4.toInt(), onBackground = 0xFF201D18.toInt(),
        surface = 0xFFF7F4EC.toInt(), onSurface = 0xFF211E19.toInt(),
        surfaceDim = 0xFFD8D1C3.toInt(), surfaceBright = 0xFFFFFCF4.toInt(),
        surfaceContainerLowest = 0xFFFFFCF4.toInt(), surfaceContainerLow = 0xFFF3EFE6.toInt(),
        surfaceContainer = 0xFFEEEAE0.toInt(), surfaceContainerHigh = 0xFFE8E3D8.toInt(),
        surfaceContainerHighest = 0xFFE1DCCF.toInt(),
        surfaceVariant = 0xFFE8E3D8.toInt(), onSurfaceVariant = 0xFF4B473D.toInt(),
        outline = 0xFF6F685A.toInt(), error = 0xFF92524B.toInt(), onError = 0xFFFFF9F0.toInt(),
        errorContainer = 0xFFF3DFD9.toInt(), onErrorContainer = 0xFF3D1D18.toInt(),
    )

    fun forPreference(
        preference: AtlasThemePreference,
        systemIsDark: Boolean = false,
        accent: AtlasAccentPreference = AtlasAccentPreference.CLASSIC,
    ): AtlasThemePalette {
        val isDark = preference.resolvesToDark(systemIsDark)
        val base = when (preference) {
            AtlasThemePreference.DARK -> dark
            AtlasThemePreference.AMOLED -> amoled
            AtlasThemePreference.FOLLOW_SYSTEM -> if (systemIsDark) dark else light
            AtlasThemePreference.LIGHT -> light
        }
        return accent.applyTo(base, isDark)
    }
}

private data class AtlasAccentTokens(
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val onSecondary: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
)

private fun AtlasAccentPreference.applyTo(base: AtlasThemePalette, isDark: Boolean): AtlasThemePalette {
    if (this == AtlasAccentPreference.CLASSIC) return base
    val tokens = when (this) {
        AtlasAccentPreference.CLASSIC -> return base
        AtlasAccentPreference.FOREST -> if (isDark) AtlasAccentTokens(
            primary = 0xFFA1C591.toInt(), onPrimary = 0xFF182317.toInt(),
            primaryContainer = 0xFF293B27.toInt(), onPrimaryContainer = 0xFFE0F0D9.toInt(),
            secondary = 0xFFB3C9AC.toInt(), onSecondary = 0xFF20271D.toInt(),
            secondaryContainer = 0xFF30392D.toInt(), onSecondaryContainer = 0xFFE1EBDD.toInt(),
        ) else AtlasAccentTokens(
            primary = 0xFF3C683F.toInt(), onPrimary = 0xFFFFFFFF.toInt(),
            primaryContainer = 0xFFDAEAD7.toInt(), onPrimaryContainer = 0xFF1B321D.toInt(),
            secondary = 0xFF4E6950.toInt(), onSecondary = 0xFFFFFFFF.toInt(),
            secondaryContainer = 0xFFE0E8DC.toInt(), onSecondaryContainer = 0xFF202C21.toInt(),
        )
        AtlasAccentPreference.OCEAN -> if (isDark) AtlasAccentTokens(
            primary = 0xFF8CBED3.toInt(), onPrimary = 0xFF142732.toInt(),
            primaryContainer = 0xFF1B3A47.toInt(), onPrimaryContainer = 0xFFD9EDF5.toInt(),
            secondary = 0xFFB3CED9.toInt(), onSecondary = 0xFF1B2C34.toInt(),
            secondaryContainer = 0xFF2B3D46.toInt(), onSecondaryContainer = 0xFFE0EDF2.toInt(),
        ) else AtlasAccentTokens(
            primary = 0xFF32627A.toInt(), onPrimary = 0xFFFFFFFF.toInt(),
            primaryContainer = 0xFFD5E8F1.toInt(), onPrimaryContainer = 0xFF152F3C.toInt(),
            secondary = 0xFF4B6978.toInt(), onSecondary = 0xFFFFFFFF.toInt(),
            secondaryContainer = 0xFFE0E9ED.toInt(), onSecondaryContainer = 0xFF1C2B32.toInt(),
        )
        AtlasAccentPreference.SLATE -> if (isDark) AtlasAccentTokens(
            primary = 0xFFA8B8CD.toInt(), onPrimary = 0xFF1D2836.toInt(),
            primaryContainer = 0xFF2B394A.toInt(), onPrimaryContainer = 0xFFE1E9F3.toInt(),
            secondary = 0xFFB9C4D2.toInt(), onSecondary = 0xFF222A34.toInt(),
            secondaryContainer = 0xFF343D48.toInt(), onSecondaryContainer = 0xFFE4E9EF.toInt(),
        ) else AtlasAccentTokens(
            primary = 0xFF50657F.toInt(), onPrimary = 0xFFFFFFFF.toInt(),
            primaryContainer = 0xFFDDE6F0.toInt(), onPrimaryContainer = 0xFF1E2C3A.toInt(),
            secondary = 0xFF5B6979.toInt(), onSecondary = 0xFFFFFFFF.toInt(),
            secondaryContainer = 0xFFE4E8ED.toInt(), onSecondaryContainer = 0xFF202832.toInt(),
        )
    }
    return base.copy(
        primary = tokens.primary,
        onPrimary = tokens.onPrimary,
        primaryContainer = tokens.primaryContainer,
        onPrimaryContainer = tokens.onPrimaryContainer,
        secondary = tokens.secondary,
        onSecondary = tokens.onSecondary,
        secondaryContainer = tokens.secondaryContainer,
        onSecondaryContainer = tokens.onSecondaryContainer,
    )
}

/** WCAG 2.x relative-luminance contrast ratio. */
internal fun wcagContrastRatio(foreground: Int, background: Int): Double {
    fun luminance(color: Int): Double {
        fun linear(channel: Int): Double {
            val normalized = channel / 255.0
            return if (normalized <= 0.04045) normalized / 12.92
            else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }

        val red = linear((color shr 16) and 0xFF)
        val green = linear((color shr 8) and 0xFF)
        val blue = linear(color and 0xFF)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }

    val first = luminance(foreground)
    val second = luminance(background)
    val lighter = maxOf(first, second)
    val darker = minOf(first, second)
    return (lighter + 0.05) / (darker + 0.05)
}
