package dev.atlas.coach.sync.relay

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Base64
import java.util.UUID

/** Pure validation shared by the settings UI and the controller. */
object RelayConfigurationValidator {
    private val SERVER_ID = Regex("^[A-Za-z0-9._-]{1,128}$")
    private val DEVICE_TOKEN = Regex("^[A-Za-z0-9_-]{43}$")
    // 10.0.2.2 is Android Emulator's host bridge address for the local development relay.
    private val LOOPBACK_WS_HOSTS = setOf("127.0.0.1", "localhost", "[::1]", "10.0.2.2")

    fun relayUrlError(value: String, debugBuild: Boolean = false): String? {
        if (value != value.trim() || value.isEmpty()) return "URL de relais invalide"
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return "URL de relais invalide"
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("ws", "wss")) return "Le relais doit utiliser ws:// ou wss://"
        val authority = uri.rawAuthority.orEmpty()
        if (authority.isEmpty() || authority.contains('@') || uri.rawUserInfo != null) {
            return "URL de relais non autorisée"
        }
        if (uri.rawQuery != null || uri.rawFragment != null) return "URL de relais non autorisée"
        if (uri.rawPath.orEmpty().isNotEmpty()) return "Le chemin du relais doit rester vide"

        val host = uri.host ?: return "Hôte du relais manquant ou invalide"
        if (!isValidRelayHost(host)) return "Hôte du relais invalide"
        if (!hasValidPort(authority, uri.port)) return "Port du relais invalide"

        if (scheme == "ws") {
            if (!debugBuild) return "Un relais distant doit utiliser wss://"
            if (host.lowercase() !in LOOPBACK_WS_HOSTS) return "ws:// est réservé au relais local de développement en debug"
        }
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
        debugBuild: Boolean = false,
    ): String? = sequenceOf(
        relayUrlError(relayUrl, debugBuild),
        serverIdError(serverId),
        publicKeyError(serverPublicKey),
        deviceIdError(deviceId),
        if (requireToken) deviceTokenError(deviceToken.orEmpty()) else null,
    ).firstOrNull { it != null }

    private fun isValidRelayHost(host: String): Boolean {
        val hostname = if (host.startsWith("[") && host.endsWith("]")) host.substring(1, host.length - 1) else host
        if (hostname.contains(':')) {
            return runCatching { InetAddress.getByName(hostname) is Inet6Address }.getOrDefault(false)
        }
        if (hostname.matches(Regex("^[0-9.]+$"))) {
            val octets = hostname.split('.')
            return octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 }
        }
        if (hostname.length > 253 || !hostname.matches(Regex("^[A-Za-z0-9.-]+$"))) return false
        val dnsName = hostname.removeSuffix(".")
        if (dnsName.isEmpty()) return false
        return dnsName.split('.').all { label ->
            label.length in 1..63 && label.matches(Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$"))
        }
    }

    private fun hasValidPort(authority: String, parsedPort: Int): Boolean {
        val explicitPort = if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return false
            val suffix = authority.substring(close + 1)
            when {
                suffix.isEmpty() -> null
                suffix.startsWith(":") -> suffix.substring(1)
                else -> return false
            }
        } else {
            val firstColon = authority.indexOf(':')
            val lastColon = authority.lastIndexOf(':')
            when {
                firstColon < 0 -> null
                firstColon != lastColon -> return false
                else -> authority.substring(firstColon + 1)
            }
        }

        if (explicitPort == null) return parsedPort == -1
        if (explicitPort.isEmpty() || !explicitPort.all(Char::isDigit)) return false
        val port = explicitPort.toIntOrNull() ?: return false
        return port in 1..65_535 && parsedPort == port
    }
}
