package dev.atlas.coach.sync.relay

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/** Observable transport states. LOCAL means relay transport is deliberately not active. */
enum class RelayConnectionState {
    LOCAL,
    RELAY_CONNECTING,
    RELAY_CONNECTED,
    OFFLINE,
    AUTH_REQUIRED,
    PAIRING_REQUIRED,
    REVOKED,
    ERROR,
}

data class RelayConfig(
    val relayUrl: String,
    val serverId: String,
    val connectTimeoutMillis: Long = 8_000,
    val reconnectBaseMillis: Long = 500,
    val reconnectMaxMillis: Long = 30_000,
    val maxReconnectAttempts: Int = 8,
    val maxQueuedRequests: Int = 16,
    val maxConcurrentRequests: Int = 4,
    /** Defaults to the R3-A capability for source compatibility with older clients. */
    val requestedCapabilities: Set<String> = setOf("plan.revision"),
) {
    init {
        val uri = URI(relayUrl)
        require(uri.scheme in setOf("ws", "wss") && !uri.host.isNullOrBlank())
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.orEmpty() in setOf("", "/"))
        require(serverId.matches(Regex("^[A-Za-z0-9._-]{1,128}$")))
        require(connectTimeoutMillis > 0)
        require(reconnectBaseMillis > 0 && reconnectMaxMillis >= reconnectBaseMillis)
        require(maxReconnectAttempts >= 0 && maxQueuedRequests in 1..256)
        require(maxConcurrentRequests in 1..4)
        val maximumCapabilities = if (requestedCapabilities.any { it.startsWith("v4.") }) {
            AtlasRemoteProtocol.MAX_V2_HELLO_CAPABILITIES
        } else {
            AtlasRemoteProtocol.MAX_V1_HELLO_CAPABILITIES
        }
        require(requestedCapabilities.isNotEmpty() && requestedCapabilities.size <= maximumCapabilities)
        require(requestedCapabilities.all { it in AtlasRemoteProtocol.HELLO_CAPABILITIES })
    }

    /** Paseo Relay v2 client endpoint; credentials are never placed in this URL. */
    fun webSocketUrl(): String {
        val base = relayUrl.trimEnd('/')
        val encoded = URLEncoder.encode(serverId, StandardCharsets.UTF_8.name()).replace("+", "%20")
        return "$base/ws?serverId=$encoded&role=client&v=2"
    }
}

data class RelayCredentials(
    val deviceId: String,
    val deviceToken: String,
    /** The PC's pinned X25519 public key, decoded from its canonical 32-byte base64 form. */
    val remoteStaticPublicKey: ByteArray,
) {
    init {
        require(AtlasRemoteProtocol.isUuid(deviceId))
        require(AtlasRemoteProtocol.isDeviceToken(deviceToken))
        require(remoteStaticPublicKey.size == NOISE_DH_BYTES)
    }

    override fun equals(other: Any?): Boolean = other is RelayCredentials &&
        deviceId == other.deviceId && deviceToken == other.deviceToken &&
        remoteStaticPublicKey.contentEquals(other.remoteStaticPublicKey)

    override fun hashCode(): Int = 31 * (31 * deviceId.hashCode() + deviceToken.hashCode()) +
        remoteStaticPublicKey.contentHashCode()
}

fun interface RelayCredentialsProvider {
    fun credentials(): RelayCredentials?
}

interface RelayCredentialStore : RelayCredentialsProvider {
    fun store(credentials: RelayCredentials)
    fun clear()
}

/** JVM fake for tests; production storage is intentionally supplied by the parent integration. */
class InMemoryRelayCredentialStore(initial: RelayCredentials? = null) : RelayCredentialStore {
    private val value = AtomicReference(initial)
    override fun credentials(): RelayCredentials? = value.get()
    override fun store(credentials: RelayCredentials) { value.set(credentials) }
    override fun clear() { value.set(null) }
}

internal const val NOISE_DH_BYTES = 32
