package dev.atlas.coach.sync.relay

import java.net.URI
import java.util.Base64
import java.util.UUID

/** Pure validation shared by the settings UI and the controller. */
object RelayConfigurationValidator {
    private val SERVER_ID = Regex("^[A-Za-z0-9._-]{1,128}$")
    private val DEVICE_TOKEN = Regex("^[A-Za-z0-9_-]{43}$")

    fun relayUrlError(value: String): String? {
        val uri = try {
            URI(value.trim())
        } catch (_: Exception) {
            return "URL de relais invalide"
        }
        if (uri.scheme !in setOf("ws", "wss")) return "Le relais doit utiliser ws:// ou wss://"
        if (uri.host.isNullOrBlank()) return "Hôte du relais manquant"
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return "URL de relais non autorisée"
        if (uri.path.orEmpty() !in setOf("", "/")) return "Le chemin du relais doit rester vide"
        return null
    }

    fun serverIdError(value: String): String? =
        if (SERVER_ID.matches(value)) null else "Identifiant serveur invalide"

    fun publicKeyError(value: String): String? {
        val bytes = try {
            Base64.getDecoder().decode(value)
        } catch (_: Exception) {
            return "Clé publique invalide"
        }
        if (bytes.size != NOISE_DH_BYTES) return "Clé publique invalide"
        val canonical = Base64.getEncoder().encodeToString(bytes)
        return if (canonical == value) null else "Clé publique non canonique"
    }

    fun deviceIdError(value: String): String? {
        val parsed = try {
            UUID.fromString(value)
        } catch (_: Exception) {
            return "Identifiant d'appareil invalide"
        }
        return if (parsed.toString() == value) null else "Identifiant d'appareil non canonique"
    }

    fun deviceTokenError(value: String): String? =
        if (DEVICE_TOKEN.matches(value)) null else "Jeton d'appareil invalide"

    fun validate(
        relayUrl: String,
        serverId: String,
        serverPublicKey: String,
        deviceId: String,
        deviceToken: String?,
        requireToken: Boolean,
    ): String? = sequenceOf(
        relayUrlError(relayUrl),
        serverIdError(serverId),
        publicKeyError(serverPublicKey),
        deviceIdError(deviceId),
        if (requireToken) deviceTokenError(deviceToken.orEmpty()) else null,
    ).firstOrNull { it != null }
}
