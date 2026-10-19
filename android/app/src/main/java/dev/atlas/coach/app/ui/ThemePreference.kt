package dev.atlas.coach.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** User-facing theme choice. DARK is the independent, persisted first-run default. */
enum class AtlasThemePreference(val storageValue: String, val label: String) {
    DARK("dark", "Sombre"),
    AMOLED("amoled", "Noir AMOLED"),
    FOLLOW_SYSTEM("system", "Suivre le système"),
    LIGHT("light", "Clair");

    fun resolvesToDark(systemIsDark: Boolean): Boolean = when (this) {
        DARK, AMOLED -> true
        FOLLOW_SYSTEM -> systemIsDark
        LIGHT -> false
    }

    companion object {
        val DEFAULT = DARK

        fun fromStorageValue(value: String?): AtlasThemePreference =
            entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}

/** Small theme-only preference store; intentionally separate from AtlasController state. */
class ThemePreferenceStore private constructor(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    var currentPreference by mutableStateOf(readPreference())
        private set

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_PREFERENCE) currentPreference = readPreference()
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun setPreference(preference: AtlasThemePreference) {
        currentPreference = preference
        preferences.edit { putString(KEY_PREFERENCE, preference.storageValue) }
    }

    private fun readPreference(): AtlasThemePreference =
        AtlasThemePreference.fromStorageValue(preferences.getString(KEY_PREFERENCE, null))

    companion object {
        const val PREFERENCES_NAME = "atlas_theme_preference"
        const val KEY_PREFERENCE = "theme"

        @Volatile
        private var instance: ThemePreferenceStore? = null

        fun get(context: Context): ThemePreferenceStore = instance ?: synchronized(this) {
            instance ?: ThemePreferenceStore(context.applicationContext).also { instance = it }
        }
    }
}

/** Standalone settings content; the settings host can place it inside its own section/card. */
@Composable
fun ThemePreferenceSection() {
    val store = ThemePreferenceStore.get(LocalContext.current)
    val selected = store.currentPreference
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Thème", style = MaterialTheme.typography.titleMedium)
        Text(
            "Le thème ATLAS est indépendant de l’apparence Android.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AtlasThemePreference.entries.forEach { option ->
            Row(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                    .semantics { contentDescription = "Thème ${option.label}" }
                    .clickable(role = Role.RadioButton) { store.setPreference(option) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == option, onClick = { store.setPreference(option) })
                Text(option.label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
