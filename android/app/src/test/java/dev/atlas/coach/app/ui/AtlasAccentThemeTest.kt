package dev.atlas.coach.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasAccentThemeTest {
    @Test
    fun missingOrUnknownAccentFallsBackToClassic() {
        assertEquals(AtlasAccentPreference.CLASSIC, AtlasAccentPreferenceCodec.decode(null))
        assertEquals(AtlasAccentPreference.CLASSIC, AtlasAccentPreferenceCodec.decode("unknown"))
    }

    @Test
    fun eachAccentHasStableStorageValueAndLabel() {
        val expected = mapOf(
            AtlasAccentPreference.CLASSIC to "classic",
            AtlasAccentPreference.FOREST to "forest",
            AtlasAccentPreference.OCEAN to "ocean",
            AtlasAccentPreference.SLATE to "slate",
        )
        expected.forEach { (accent, storageValue) ->
            assertEquals(storageValue, AtlasAccentPreferenceCodec.encode(accent))
            assertEquals(accent, AtlasAccentPreferenceCodec.decode(storageValue))
            assertTrue(accent.label.isNotBlank())
        }
    }

    @Test
    fun accentAndModeAreIndependent() {
        val mode = AtlasThemePreference.AMOLED
        val accent = AtlasAccentPreference.FOREST
        val palette = AtlasThemePalettes.forPreference(mode, systemIsDark = false, accent = accent)

        assertEquals("atlas_theme_preference", ThemePreferenceStore.PREFERENCES_NAME)
        assertEquals("atlas_accent_preference", AccentPreferenceStore.PREFERENCES_NAME)
        assertEquals(AtlasThemePreference.AMOLED, AtlasThemePreference.fromStorageValue(mode.storageValue))
        assertEquals(AtlasAccentPreference.FOREST, AtlasAccentPreferenceCodec.decode(accent.storageValue))
        assertEquals(0xFF000000.toInt(), palette.background)
        assertEquals(0xFF000000.toInt(), palette.surface)
    }

    @Test
    fun classicDarkKeepsExistingAccentTokens() {
        val palette = AtlasThemePalettes.forPreference(
            AtlasThemePreference.DARK,
            systemIsDark = false,
            accent = AtlasAccentPreference.CLASSIC,
        )

        assertEquals(0xFFB49A63.toInt(), palette.primary)
        assertEquals(0xFF211D15.toInt(), palette.onPrimary)
        assertEquals(0xFF332D20.toInt(), palette.primaryContainer)
        assertEquals(0xFFF0E7D1.toInt(), palette.onPrimaryContainer)
        assertEquals(0xFFC7BDA9.toInt(), palette.secondary)
        assertEquals(0xFF252219.toInt(), palette.onSecondary)
        assertEquals(0xFF383329.toInt(), palette.secondaryContainer)
        assertEquals(0xFFEDE4D0.toInt(), palette.onSecondaryContainer)
    }

    @Test
    fun amoledKeepsPureBlackBackgroundForEveryAccent() {
        AtlasAccentPreference.entries.forEach { accent ->
            val palette = AtlasThemePalettes.forPreference(
                AtlasThemePreference.AMOLED,
                systemIsDark = false,
                accent = accent,
            )
            assertEquals("$accent background", 0xFF000000.toInt(), palette.background)
            assertEquals("$accent surface", 0xFF000000.toInt(), palette.surface)
        }
    }

    @Test
    fun everyModeAccentPairMeetsWcagAaForNormalText() {
        val bases = mapOf(
            "dark" to AtlasThemePreference.DARK,
            "amoled" to AtlasThemePreference.AMOLED,
            "light" to AtlasThemePreference.LIGHT,
        )
        AtlasAccentPreference.entries.forEach { accent ->
            bases.forEach { (baseName, mode) ->
                val palette = AtlasThemePalettes.forPreference(mode, systemIsDark = false, accent = accent)
                palette.textContrastPairs().forEach { pair ->
                    val ratio = wcagContrastRatio(pair.foreground, pair.background)
                    assertTrue(
                        "$accent/$baseName ${pair.name}: contrast $ratio is below 4.5",
                        ratio >= 4.5,
                    )
                }
            }
        }
    }

    @Test
    fun semanticRolesHaveAccessibleContrast() {
        val bases = listOf(
            AtlasThemePreference.DARK,
            AtlasThemePreference.AMOLED,
            AtlasThemePreference.LIGHT,
        )
        AtlasAccentPreference.entries.forEach { accent ->
            bases.forEach { mode ->
                val palette = AtlasThemePalettes.forPreference(mode, systemIsDark = false, accent = accent)
                val roles = AtlasSemanticPalettes.forPalette(
                    palette,
                    isDark = mode != AtlasThemePreference.LIGHT,
                )
                roles.textContrastPairs().forEach { pair ->
                    val ratio = wcagContrastRatio(pair.foreground, pair.background)
                    assertTrue("$accent/$mode ${pair.name}: $ratio is below 4.5", ratio >= 4.5)
                }
                roles.nonTextContrastPairs(palette.background, palette.surface).forEach { pair ->
                    val ratio = wcagContrastRatio(pair.foreground, pair.background)
                    assertTrue("$accent/$mode ${pair.name}: $ratio is below 3.0", ratio >= 3.0)
                }
            }
        }
    }

    @Test
    fun semanticRolesAreDistinct() {
        val roles = AtlasSemanticPalettes.forPalette(AtlasThemePalettes.dark, isDark = true)
        val stateColors = listOf(
            roles.success.foreground,
            roles.warning.foreground,
            roles.error.foreground,
            roles.neutral.foreground,
        )
        assertEquals(stateColors.size, stateColors.toSet().size)
    }

    @Test
    fun statusGlyphsAreDistinctForEveryKind() {
        val glyphs = AtlasStatusKind.entries.map { it.glyph() }
        assertEquals(AtlasStatusKind.entries.size, glyphs.toSet().size)
        assertTrue(glyphs.all { it.isNotBlank() })
    }

    @Test
    fun dynamicColorFallsBackSafelyWhenUnsupported() {
        assertFalse(shouldUseDynamicColor(apiLevel = 30, enabled = true))
        assertTrue(shouldUseDynamicColor(apiLevel = 31, enabled = true))
        assertFalse(shouldUseDynamicColor(apiLevel = 35, enabled = false))
    }

    @Test
    fun amoledAlwaysKeepsBlackEvenWhenDynamicColorRequested() {
        assertFalse(
            shouldUseDynamicColor(
                apiLevel = 31,
                enabled = true,
                themePreference = AtlasThemePreference.AMOLED,
            ),
        )
        assertFalse(
            shouldUseDynamicColor(
                apiLevel = 35,
                enabled = true,
                themePreference = AtlasThemePreference.AMOLED,
            ),
        )
        assertTrue(
            shouldUseDynamicColor(
                apiLevel = 31,
                enabled = true,
                themePreference = AtlasThemePreference.DARK,
            ),
        )
    }
}
