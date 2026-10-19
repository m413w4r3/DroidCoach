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
) {
    fun textContrastPairs(): List<ThemeTextContrastPair> = listOf(
        ThemeTextContrastPair("primary/onPrimary", onPrimary, primary),
        ThemeTextContrastPair("primaryContainer/onPrimaryContainer", onPrimaryContainer, primaryContainer),
        ThemeTextContrastPair("secondary/onSecondary", onSecondary, secondary),
        ThemeTextContrastPair("secondaryContainer/onSecondaryContainer", onSecondaryContainer, secondaryContainer),
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
    )
}

internal data class ThemeTextContrastPair(val name: String, val foreground: Int, val background: Int)

internal object AtlasThemePalettes {
    val dark = AtlasThemePalette(
        primary = 0xFFD6EF83.toInt(), onPrimary = 0xFF202416.toInt(),
        primaryContainer = 0xFF303820.toInt(), onPrimaryContainer = 0xFFE5F5AD.toInt(),
        secondary = 0xFFC1CAAA.toInt(), onSecondary = 0xFF2A3320.toInt(),
        secondaryContainer = 0xFF3A4430.toInt(), onSecondaryContainer = 0xFFDDE8C4.toInt(),
        background = 0xFF111318.toInt(), onBackground = 0xFFF1F0E9.toInt(),
        surface = 0xFF1B1D23.toInt(), onSurface = 0xFFF1F0E9.toInt(),
        surfaceDim = 0xFF111318.toInt(), surfaceBright = 0xFF373940.toInt(),
        surfaceContainerLowest = 0xFF0C0D10.toInt(), surfaceContainerLow = 0xFF17191E.toInt(),
        surfaceContainer = 0xFF1B1D23.toInt(), surfaceContainerHigh = 0xFF25272D.toInt(),
        surfaceContainerHighest = 0xFF303239.toInt(),
        surfaceVariant = 0xFF292C33.toInt(), onSurfaceVariant = 0xFFC4C6CE.toInt(),
        outline = 0xFF858893.toInt(), error = 0xFFFFB4AB.toInt(), onError = 0xFF690005.toInt(),
    )

    val amoled = AtlasThemePalette(
        primary = 0xFFD6EF83.toInt(), onPrimary = 0xFF202416.toInt(),
        primaryContainer = 0xFF1B2112.toInt(), onPrimaryContainer = 0xFFE5F5AD.toInt(),
        secondary = 0xFFC1CAAA.toInt(), onSecondary = 0xFF171A12.toInt(),
        secondaryContainer = 0xFF20231B.toInt(), onSecondaryContainer = 0xFFDDE8C4.toInt(),
        background = 0xFF000000.toInt(), onBackground = 0xFFF5F5F5.toInt(),
        surface = 0xFF000000.toInt(), onSurface = 0xFFF5F5F5.toInt(),
        surfaceDim = 0xFF000000.toInt(), surfaceBright = 0xFF262626.toInt(),
        surfaceContainerLowest = 0xFF000000.toInt(), surfaceContainerLow = 0xFF0A0A0A.toInt(),
        surfaceContainer = 0xFF111111.toInt(), surfaceContainerHigh = 0xFF181818.toInt(),
        surfaceContainerHighest = 0xFF202020.toInt(),
        surfaceVariant = 0xFF111111.toInt(), onSurfaceVariant = 0xFFC9C9C9.toInt(),
        outline = 0xFF8A8A8A.toInt(), error = 0xFFFFB4AB.toInt(), onError = 0xFF690005.toInt(),
    )

    val light = AtlasThemePalette(
        primary = 0xFF3F5600.toInt(), onPrimary = 0xFFFFFFFF.toInt(),
        primaryContainer = 0xFFE5F2C2.toInt(), onPrimaryContainer = 0xFF222E00.toInt(),
        secondary = 0xFF4C5D38.toInt(), onSecondary = 0xFFFFFFFF.toInt(),
        secondaryContainer = 0xFFE1EBCF.toInt(), onSecondaryContainer = 0xFF202B15.toInt(),
        background = 0xFFF7F8F2.toInt(), onBackground = 0xFF191C16.toInt(),
        surface = 0xFFFFFFFF.toInt(), onSurface = 0xFF191C16.toInt(),
        surfaceDim = 0xFFDADCD3.toInt(), surfaceBright = 0xFFFFFFFF.toInt(),
        surfaceContainerLowest = 0xFFFFFFFF.toInt(), surfaceContainerLow = 0xFFF7F8F2.toInt(),
        surfaceContainer = 0xFFF1F2EA.toInt(), surfaceContainerHigh = 0xFFECEDE5.toInt(),
        surfaceContainerHighest = 0xFFE6E7DF.toInt(),
        surfaceVariant = 0xFFE8EAE1.toInt(), onSurfaceVariant = 0xFF3D4136.toInt(),
        outline = 0xFF686C60.toInt(), error = 0xFFB3261E.toInt(), onError = 0xFFFFFFFF.toInt(),
    )

    fun forPreference(preference: AtlasThemePreference): AtlasThemePalette = when (preference) {
        AtlasThemePreference.DARK -> dark
        AtlasThemePreference.AMOLED -> amoled
        AtlasThemePreference.FOLLOW_SYSTEM, AtlasThemePreference.LIGHT -> light
    }
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
