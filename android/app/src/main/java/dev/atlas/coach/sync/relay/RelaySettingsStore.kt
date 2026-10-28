package dev.atlas.coach.sync.relay

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class RelayTransportMode {
    DIRECT,
    RELAY,
    OFFLINE,
}

data class RelaySettings(
    val mode: RelayTransportMode = RelayTransportMode.DIRECT,
    val relayUrl: String = DEFAULT_RELAY_URL,
    val serverId: String = "",
    val serverPublicKey: String = "",
    val deviceId: String = "",
    val lastConnectedAtUtcMillis: Long? = null,
) {
    companion object {
        const val DEFAULT_RELAY_URL = "wss://relay.example.org"
    }
}

/** Non-secret relay configuration. The device token lives only in [KeystoreRelayCredentialStore]. */
class AndroidRelaySettingsStore(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): RelaySettings = RelaySettings(
        mode = RelayTransportMode.entries.firstOrNull {
            it.name == preferences.getString(MODE_KEY, RelayTransportMode.DIRECT.name)
        } ?: RelayTransportMode.DIRECT,
        relayUrl = preferences.getString(RELAY_URL_KEY, RelaySettings.DEFAULT_RELAY_URL).orEmpty(),
        serverId = preferences.getString(SERVER_ID_KEY, "").orEmpty(),
        serverPublicKey = preferences.getString(SERVER_PUBLIC_KEY_KEY, "").orEmpty(),
        deviceId = preferences.getString(DEVICE_ID_KEY, "").orEmpty(),
        lastConnectedAtUtcMillis = preferences.getLong(LAST_CONNECTED_KEY, -1L).takeIf { it >= 0L },
    )

    fun write(settings: RelaySettings) {
        preferences.edit().apply {
            putString(MODE_KEY, settings.mode.name)
            putString(RELAY_URL_KEY, settings.relayUrl.trim())
            putString(SERVER_ID_KEY, settings.serverId.trim())
            putString(SERVER_PUBLIC_KEY_KEY, settings.serverPublicKey.trim())
            putString(DEVICE_ID_KEY, settings.deviceId.trim())
            if (settings.lastConnectedAtUtcMillis == null) remove(LAST_CONNECTED_KEY)
            else putLong(LAST_CONNECTED_KEY, settings.lastConnectedAtUtcMillis)
        }.commit()
    }

    fun updateIdentity(deviceId: String, serverPublicKey: String) {
        preferences.edit()
            .putString(DEVICE_ID_KEY, deviceId.trim())
            .putString(SERVER_PUBLIC_KEY_KEY, serverPublicKey.trim())
            .commit()
    }

    fun updateMode(mode: RelayTransportMode) {
        preferences.edit().putString(MODE_KEY, mode.name).commit()
    }

    fun updateLastConnected(atUtcMillis: Long) {
        preferences.edit().putLong(LAST_CONNECTED_KEY, atUtcMillis).commit()
    }

    companion object {
        const val PREFERENCES_NAME = "atlas_relay_configuration"
        private const val MODE_KEY = "relay_mode"
        private const val RELAY_URL_KEY = "relay_url"
        private const val SERVER_ID_KEY = "relay_server_id"
        private const val SERVER_PUBLIC_KEY_KEY = "relay_server_public_key"
        private const val DEVICE_ID_KEY = "relay_device_id"
        private const val LAST_CONNECTED_KEY = "relay_last_connected_at"
    }
}

/**
 * Keystore-backed device token storage. The token is never shown again by the
 * UI and never written to logs or the non-secret settings file.
 */
class KeystoreRelayCredentialStore(
    context: Context,
    private val settingsStore: AndroidRelaySettingsStore,
) : RelayCredentialStore {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(AndroidRelaySettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun credentials(): RelayCredentials? = runCatching {
        val token = readToken() ?: return null
        val settings = settingsStore.read()
        val publicKey = Base64.decode(settings.serverPublicKey, Base64.NO_WRAP)
        RelayCredentials(
            deviceId = settings.deviceId,
            deviceToken = token,
            remoteStaticPublicKey = publicKey,
        )
    }.getOrNull()

    override fun store(credentials: RelayCredentials) {
        require(credentials.remoteStaticPublicKey.size == NOISE_DH_BYTES)
        settingsStore.updateIdentity(
            credentials.deviceId,
            Base64.encodeToString(credentials.remoteStaticPublicKey, Base64.NO_WRAP),
        )
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = cipher.doFinal(credentials.deviceToken.toByteArray(StandardCharsets.UTF_8))
        preferences.edit().putString(
            TOKEN_KEY,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP),
        ).commit()
    }

    override fun clear() {
        preferences.edit().remove(TOKEN_KEY).commit()
        settingsStore.updateIdentity("", "")
        // Remove the per-relay AES alias too. The ciphertext is already gone, but deleting the
        // alias leaves no reusable key material behind after an explicit unpair.
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun readToken(): String? = runCatching {
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

    private companion object {
        const val TOKEN_KEY = "relay_device_token"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "atlas_relay_device_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
