package dev.atlas.coach.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/** Persisted accent palettes. Storage values are part of the local preference contract. */
enum class AtlasAccentPreference(val storageValue: String, val label: String) {
    CLASSIC("classic", "ATLAS classique"),
    FOREST("forest", "Forêt"),
    OCEAN("ocean", "Océan"),
    SLATE("slate", "Ardoise");

    companion object {
        val DEFAULT = CLASSIC

        fun fromStorageValue(value: String?): AtlasAccentPreference =
            entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}

/** Pure codec kept separate from Android storage for JVM verification. */
internal object AtlasAccentPreferenceCodec {
    fun encode(preference: AtlasAccentPreference): String = preference.storageValue

    fun decode(value: String?): AtlasAccentPreference =
        AtlasAccentPreference.fromStorageValue(value)
}

/** Returns true only when the user enabled dynamic color and the OS supports it. */
internal fun shouldUseDynamicColor(apiLevel: Int, enabled: Boolean): Boolean =
    enabled && apiLevel >= 31

/** Accent and dynamic-color settings live apart from the independent theme-mode store. */
class AccentPreferenceStore private constructor(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    var currentAccent by mutableStateOf(readAccent())
        private set

    var dynamicColorEnabled by mutableStateOf(readDynamicColorEnabled())
        private set

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            KEY_ACCENT -> currentAccent = readAccent()
            KEY_DYNAMIC_COLOR -> dynamicColorEnabled = readDynamicColorEnabled()
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun setAccent(accent: AtlasAccentPreference) {
        currentAccent = accent
        preferences.edit { putString(KEY_ACCENT, AtlasAccentPreferenceCodec.encode(accent)) }
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        dynamicColorEnabled = enabled
        preferences.edit { putBoolean(KEY_DYNAMIC_COLOR, enabled) }
    }

    private fun readAccent(): AtlasAccentPreference =
        AtlasAccentPreferenceCodec.decode(preferences.getString(KEY_ACCENT, null))

    private fun readDynamicColorEnabled(): Boolean =
        preferences.getBoolean(KEY_DYNAMIC_COLOR, false)

    companion object {
        const val PREFERENCES_NAME = "atlas_accent_preference"
        const val KEY_ACCENT = "accent"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"

        @Volatile
        private var instance: AccentPreferenceStore? = null

        fun get(context: Context): AccentPreferenceStore = instance ?: synchronized(this) {
            instance ?: AccentPreferenceStore(context.applicationContext).also { instance = it }
        }
    }
}
