package dev.atlas.coach.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasThemePreferenceTest {
    @Test
    fun missingOrUnknownValueUsesIndependentDarkDefault() {
        assertEquals(AtlasThemePreference.DARK, AtlasThemePreference.fromStorageValue(null))
        assertEquals(AtlasThemePreference.DARK, AtlasThemePreference.fromStorageValue("unknown"))
    }

    @Test
    fun eachPreferenceHasStableStorageValueAndLabel() {
        AtlasThemePreference.entries.forEach { preference ->
            assertEquals(preference, AtlasThemePreference.fromStorageValue(preference.storageValue))
            assertTrue(preference.label.isNotBlank())
        }
    }

    @Test
    fun explicitChoicesIgnoreAndroidThemeAndFollowSystemTracksIt() {
        assertTrue(AtlasThemePreference.DARK.resolvesToDark(systemIsDark = false))
        assertTrue(AtlasThemePreference.AMOLED.resolvesToDark(systemIsDark = false))
        assertTrue(AtlasThemePreference.LIGHT.resolvesToDark(systemIsDark = true).not())
        assertTrue(AtlasThemePreference.FOLLOW_SYSTEM.resolvesToDark(systemIsDark = true))
        assertTrue(AtlasThemePreference.FOLLOW_SYSTEM.resolvesToDark(systemIsDark = false).not())
    }

    @Test
    fun everyPaletteTextPairMeetsWcagAaForNormalText() {
        val palettes = mapOf(
            "dark" to AtlasThemePalettes.dark,
            "amoled" to AtlasThemePalettes.amoled,
            "light" to AtlasThemePalettes.light,
        )
        palettes.forEach { (paletteName, palette) ->
            palette.textContrastPairs().forEach { pair ->
                val ratio = wcagContrastRatio(pair.foreground, pair.background)
                assertTrue("$paletteName ${pair.name}: contrast $ratio is below 4.5", ratio >= 4.5)
            }
        }
    }

    @Test
    fun amoledPaletteUsesBlackAndNearBlackSurfaces() {
        assertEquals(0xFF000000.toInt(), AtlasThemePalettes.amoled.background)
        assertEquals(0xFF000000.toInt(), AtlasThemePalettes.amoled.surface)
        assertEquals(0xFF0A0A0A.toInt(), AtlasThemePalettes.amoled.surfaceContainerLow)
        assertEquals(0xFF202020.toInt(), AtlasThemePalettes.amoled.surfaceContainerHighest)
    }
}
