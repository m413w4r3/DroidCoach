package dev.atlas.coach.sync

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private token storage configured by the settings integration; values are never logged. */
class SharedPreferencesBearerTokenStore(context: Context) : BearerTokenProvider {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun token(): String? = runCatching {
        val encoded = preferences.getString(TOKEN_KEY, null) ?: return null
        val parts = encoded.split(':', limit = 2)
        require(parts.size == 2)
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.doFinal(ciphertext).toString(StandardCharsets.UTF_8)
            .trim()
            .takeIf(String::isNotEmpty)
    }.getOrNull()

    fun configureToken(token: String?) {
        val normalized = token?.trim()?.takeIf(String::isNotEmpty)
        require(normalized == null || normalized.length <= MAX_TOKEN_LENGTH) {
            "Bearer token is too long"
        }
        preferences.edit().apply {
            if (normalized == null) {
                remove(TOKEN_KEY)
            } else {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
                val encrypted = cipher.doFinal(normalized.toByteArray(StandardCharsets.UTF_8))
                putString(
                    TOKEN_KEY,
                    Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                        Base64.encodeToString(encrypted, Base64.NO_WRAP),
                )
            }
        }.apply()
    }

    @Synchronized
    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        const val PREFERENCES_NAME = "atlas_sync_configuration"
        const val TOKEN_KEY = "backend_bearer_token"
        private const val MAX_TOKEN_LENGTH = 4096
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "atlas_backend_bearer_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}

object AndroidSyncStorage {
    fun outbox(context: Context): EventOutbox = DurableEventOutbox(
        FileOutboxStorage(File(context.applicationContext.filesDir, "atlas_sync_outbox_v1.json")),
    )

    fun settings(context: Context): AndroidSyncSettingsStore = AndroidSyncSettingsStore(context)
}

data class PersistedSyncSettings(
    val baseUrl: String = "",
    val selectedCategories: Set<SyncDataCategory> = emptySet(),
)

/** Private non-secret backend configuration; bearer credentials live in the Keystore-backed store above. */
class AndroidSyncSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): PersistedSyncSettings = PersistedSyncSettings(
        baseUrl = preferences.getString(BASE_URL_KEY, "").orEmpty(),
        selectedCategories = preferences.getStringSet(CATEGORIES_KEY, emptySet()).orEmpty()
            .mapNotNull { stored -> SyncDataCategory.entries.firstOrNull { it.wireValue == stored } }
            .toSet(),
    )

    fun write(baseUrl: String, selectedCategories: Set<SyncDataCategory>) {
        preferences.edit {
            putString(BASE_URL_KEY, baseUrl.trim())
            putStringSet(CATEGORIES_KEY, selectedCategories.mapTo(mutableSetOf(), SyncDataCategory::wireValue))
        }
    }

    companion object {
        const val PREFERENCES_NAME = "atlas_sync_preferences"
        private const val BASE_URL_KEY = "backend_base_url"
        private const val CATEGORIES_KEY = "consented_categories"
    }
}
