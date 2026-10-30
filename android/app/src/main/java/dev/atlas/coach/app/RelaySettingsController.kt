package dev.atlas.coach.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.atlas.coach.plan.PlanRevisionProposalClient
import dev.atlas.coach.sync.agent.context.AgentContextSnapshot
import dev.atlas.coach.sync.agent.coach.AgentChatV4Sharing
import dev.atlas.coach.sync.agent.coach.AgentChatClient
import dev.atlas.coach.sync.agent.coach.AgentRequestContext
import dev.atlas.coach.sync.agent.coach.RelayAgentChatClient
import dev.atlas.coach.sync.AndroidCoreV4SyncStore
import dev.atlas.coach.sync.relay.AndroidRelaySettingsStore
import dev.atlas.coach.sync.relay.KeystoreRelayCredentialStore
import dev.atlas.coach.sync.relay.RelayBackendTransport
import dev.atlas.coach.sync.relay.RelayConfig
import dev.atlas.coach.sync.relay.RelayConfigurationValidator
import dev.atlas.coach.sync.relay.RelayConnectionState
import dev.atlas.coach.sync.relay.RelayCredentialStore
import dev.atlas.coach.sync.relay.RelayCredentials
import dev.atlas.coach.sync.relay.RelayFailure
import dev.atlas.coach.sync.relay.RelayFailureKind
import dev.atlas.coach.sync.relay.RelayPlanRevisionClient
import dev.atlas.coach.sync.relay.RelaySettings
import dev.atlas.coach.sync.agent.coach.RelayChatRequestExecutor
import dev.atlas.coach.sync.relay.RelayTransportMode
import dev.atlas.coach.sync.v4.CoreV4ConsentState
import java.util.Base64

internal const val V4_CHAT_DEFAULT_ENABLED = false

internal fun relayChatCapabilities(v4ChatEnabled: Boolean): Set<String> =
    setOf("plan.revision", if (v4ChatEnabled) "v4.coach.chat" else "coach.chat")

/** Chooses one version-consistent set; the V4 chat contract carries a single consent version. */
internal fun v4ChatSharing(records: List<dev.atlas.coach.sync.CoreV4ConsentRecord>): AgentChatV4Sharing {
    val eligible = records.filter { record ->
        record.snapshot.state == CoreV4ConsentState.GRANTED &&
            record.snapshot.syncedVersion == record.snapshot.version
    }
    val selected = eligible.groupBy { it.snapshot.version }.entries.maxWithOrNull(
        compareBy<Map.Entry<Int, List<dev.atlas.coach.sync.CoreV4ConsentRecord>>> { it.value.size }
            .thenBy { it.key },
    )
    return AgentChatV4Sharing(
        consentVersion = selected?.key ?: records.maxOfOrNull { it.snapshot.version } ?: 1,
        sharedCategories = selected?.value.orEmpty().mapTo(linkedSetOf()) { it.snapshot.category.wireValue },
    )
}

/**
 * Owns the optional relayed connection. It never opens a socket in DIRECT or
 * OFFLINE mode, never bypasses the capability/mandate gate, and never displays
 * or logs the device token.
 */
class RelaySettingsController(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val consentGate: dev.atlas.coach.data.AgentConsentGate =
        dev.atlas.coach.data.AgentConsentGate(),
    private val transportFactory: (RelayConfig, RelayCredentialStore) -> RelayBackendTransport =
        { config, credentials -> RelayBackendTransport(config, credentials, consentGate = consentGate) },
) {
    private val allowLoopbackWs = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val settingsStore = AndroidRelaySettingsStore(context)
    private val credentialStore = KeystoreRelayCredentialStore(context, settingsStore)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var settings: RelaySettings = settingsStore.read()
    @Volatile private var transport: RelayBackendTransport? = null
    private val coreV4SyncStore = AndroidCoreV4SyncStore(context.applicationContext)
    @Volatile private var transportGeneration = 0L
    private var activeListener: ((RelayConnectionState) -> Unit)? = null
    private var activeFailureListener: ((RelayFailure) -> Unit)? = null
    private var lastStartAttemptAt = 0L

    var mode by mutableStateOf(settings.mode)
        private set
    var relayUrl by mutableStateOf(settings.relayUrl)
        private set
    var serverId by mutableStateOf(settings.serverId)
        private set
    var serverPublicKey by mutableStateOf(settings.serverPublicKey)
        private set
    var deviceId by mutableStateOf(settings.deviceId)
        private set
    var state by mutableStateOf(
        when (settings.mode) {
            RelayTransportMode.OFFLINE -> RelayConnectionState.OFFLINE
            RelayTransportMode.DIRECT -> RelayConnectionState.LOCAL
            RelayTransportMode.RELAY -> if (credentialStore.credentials() == null) {
                RelayConnectionState.PAIRING_REQUIRED
            } else {
                RelayConnectionState.OFFLINE
            }
        },
    )
        private set
    var lastConnectedAtUtcMillis by mutableStateOf(settings.lastConnectedAtUtcMillis)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var credentialsConfigured by mutableStateOf(credentialStore.credentials() != null)
        private set
    private var v4ChatOptIn by mutableStateOf(V4_CHAT_DEFAULT_ENABLED)
        private set
    val v4ChatEnabled: Boolean get() = v4ChatOptIn

    /** WebSocket close code observed by the active transport; 4001 is the revocation close. */
    val lastCloseCode: Int? get() = transport?.lastCloseCode

    val isRelayMode: Boolean get() = mode == RelayTransportMode.RELAY

    fun saveConfiguration(
        relayUrl: String,
        serverId: String,
        serverPublicKey: String,
        deviceId: String,
        deviceToken: String?,
    ): Result<Unit> {
        val normalizedUrl = relayUrl.trim()
        val normalizedServerId = serverId.trim()
        val normalizedKey = serverPublicKey.trim()
        val normalizedDeviceId = deviceId.trim()
        val token = deviceToken?.trim()?.takeIf(String::isNotEmpty)
        val existing = credentialStore.credentials()
        val requireToken = token == null && existing == null
        RelayConfigurationValidator.validate(
            relayUrl = normalizedUrl,
            serverId = normalizedServerId,
            serverPublicKey = normalizedKey,
            deviceId = normalizedDeviceId,
            deviceToken = token,
            requireToken = requireToken,
            debugBuild = allowLoopbackWs,
        )?.let { return Result.failure(IllegalArgumentException(it)) }

        if (token == null && existing != null) {
            val decodedKey = Base64.getDecoder().decode(normalizedKey)
            if (existing.deviceId != normalizedDeviceId ||
                !existing.remoteStaticPublicKey.contentEquals(decodedKey)
            ) {
                return Result.failure(
                    IllegalArgumentException("Jeton requis pour changer l'identité de l'appareil"),
                )
            }
        }

        val updated = settings.copy(
            relayUrl = normalizedUrl,
            serverId = normalizedServerId,
            serverPublicKey = normalizedKey,
            deviceId = normalizedDeviceId,
        )
        settingsStore.write(updated)
        if (token != null) {
            credentialStore.store(
                RelayCredentials(
                    deviceId = normalizedDeviceId,
                    deviceToken = token,
                    remoteStaticPublicKey = Base64.getDecoder().decode(normalizedKey),
                ),
            )
        }
        settings = updated
        this.relayUrl = updated.relayUrl
        this.serverId = updated.serverId
        this.serverPublicKey = updated.serverPublicKey
        this.deviceId = updated.deviceId
        credentialsConfigured = credentialStore.credentials() != null
        if (mode == RelayTransportMode.RELAY) {
            stopTransport()
            state = RelayConnectionState.OFFLINE
            lastError = null
        }
        return Result.success(Unit)
    }

    fun selectMode(newMode: RelayTransportMode) {
        if (newMode != RelayTransportMode.RELAY) v4ChatOptIn = V4_CHAT_DEFAULT_ENABLED
        settingsStore.updateMode(newMode)
        settings = settings.copy(mode = newMode)
        mode = newMode
        stopTransport()
        when (newMode) {
            RelayTransportMode.RELAY -> {
                state = if (credentialsConfigured) RelayConnectionState.OFFLINE else RelayConnectionState.PAIRING_REQUIRED
                lastError = if (credentialsConfigured) null else "Aucun appareil appairé"
            }
            RelayTransportMode.OFFLINE -> state = RelayConnectionState.OFFLINE
            RelayTransportMode.DIRECT -> state = RelayConnectionState.LOCAL
        }
    }

    fun connect() {
        if (mode != RelayTransportMode.RELAY) {
            selectMode(RelayTransportMode.RELAY)
        }
        startTransport(force = true)
    }

    fun disconnect() {
        stopTransport()
        state = when (mode) {
            RelayTransportMode.RELAY -> RelayConnectionState.OFFLINE
            RelayTransportMode.OFFLINE -> RelayConnectionState.OFFLINE
            RelayTransportMode.DIRECT -> RelayConnectionState.LOCAL
        }
    }

    /** V4 is an explicit session capability and starts disabled on every app launch. */
    fun setV4ChatEnabled(enabled: Boolean) {
        if (v4ChatOptIn == enabled) return
        v4ChatOptIn = enabled
        if (mode == RelayTransportMode.RELAY && state in setOf(
                RelayConnectionState.RELAY_CONNECTED,
                RelayConnectionState.RELAY_CONNECTING,
            )
        ) {
            startTransport(force = true)
        }
    }

    fun clearCredentials() {
        stopTransport()
        credentialStore.clear()
        val current = settingsStore.read()
        settings = current
        serverPublicKey = ""
        deviceId = ""
        credentialsConfigured = false
        state = when (mode) {
            RelayTransportMode.RELAY -> RelayConnectionState.PAIRING_REQUIRED
            RelayTransportMode.OFFLINE -> RelayConnectionState.OFFLINE
            RelayTransportMode.DIRECT -> RelayConnectionState.LOCAL
        }
    }

    /** Connections are started only by the explicit Connect action, never on app resume. */
    fun onAppResumed() = Unit

    /** Non-null only in RELAY mode with an open, authenticated session. */
    fun relayPlanRevisionClient(requestAllowed: () -> Boolean): PlanRevisionProposalClient? {
        if (mode != RelayTransportMode.RELAY || state != RelayConnectionState.RELAY_CONNECTED) return null
        val active = transport ?: return null
        return RelayPlanRevisionClient(active, requestAllowed)
    }

    /** Non-null only for a connected, authenticated RELAY session. */
    fun relayAgentChatClient(
        freshRequestContext: (AgentContextSnapshot) -> AgentRequestContext?,
        onContextWritten: (AgentContextSnapshot) -> Boolean,
        requestAllowed: () -> Boolean,
        snapshotStillAuthorized: (AgentContextSnapshot) -> Boolean = { true },
    ): AgentChatClient? {
        if (mode != RelayTransportMode.RELAY || state != RelayConnectionState.RELAY_CONNECTED) return null
        val active = transport ?: return null
        val selectedV4 = v4ChatOptIn
        val executor = RelayChatRequestExecutor { operation, timeoutMillis, idempotencyKey, payloadFactory, beforeDispatch ->
            active.executeDeferred(
                operation = operation,
                timeoutMillis = timeoutMillis,
                idempotencyKey = idempotencyKey,
                payloadFactory = payloadFactory,
                beforeDispatch = beforeDispatch,
            )
        }
        return RelayAgentChatClient(
            executor,
            freshRequestContext,
            onContextWritten,
            requestAllowed,
            snapshotStillAuthorized,
            v4ChatEnabled = selectedV4,
            v4Sharing = { v4ChatSharing(coreV4SyncStore.consents()) },
            v4SharingStillAuthorized = { selected ->
                v4ChatSharing(coreV4SyncStore.consents()) == selected
            },
        )
    }

    fun close() {
        stopTransport()
    }

    private fun startTransport(force: Boolean = false) {
        if (mode != RelayTransportMode.RELAY) return
        val now = clock()
        if (!force && now - lastStartAttemptAt < MIN_START_INTERVAL_MILLIS) return
        lastStartAttemptAt = now
        stopTransport()
        lastError = null
        val credentials = credentialStore.credentials()
        if (credentials == null) {
            state = RelayConnectionState.PAIRING_REQUIRED
            lastError = "Aucun appareil appairé"
            return
        }
        val current = settingsStore.read()
        RelayConfigurationValidator.validate(
            relayUrl = current.relayUrl,
            serverId = current.serverId,
            serverPublicKey = current.serverPublicKey,
            deviceId = current.deviceId,
            deviceToken = null,
            requireToken = false,
            debugBuild = allowLoopbackWs,
        )?.let {
            state = RelayConnectionState.ERROR
            lastError = it
            return
        }
        val config = try {
            RelayConfig(
                relayUrl = current.relayUrl,
                serverId = current.serverId,
                connectTimeoutMillis = 8_000,
                reconnectBaseMillis = 500,
                reconnectMaxMillis = 30_000,
                maxReconnectAttempts = 8,
                maxQueuedRequests = 16,
                maxConcurrentRequests = 4,
                requestedCapabilities = relayChatCapabilities(v4ChatOptIn),
            )
        } catch (_: Exception) {
            state = RelayConnectionState.ERROR
            lastError = "Configuration relayée invalide"
            return
        }
        val generation = ++transportGeneration
        val listener: (RelayConnectionState) -> Unit = { newState ->
            mainHandler.post {
                if (generation == transportGeneration) applyState(newState)
            }
        }
        val failureListener: (RelayFailure) -> Unit = { failure ->
            mainHandler.post {
                if (generation == transportGeneration) {
                    lastError = RelaySettingsErrorPolicy.afterFailure(state, failure, lastError)
                }
            }
        }
        val created = try {
            transportFactory(config, credentialStore).also { candidate ->
                candidate.addStateListener(listener)
                candidate.addFailureListener(failureListener)
                candidate.start()
            }
        } catch (error: Exception) {
            state = RelayConnectionState.ERROR
            lastError = RelayFailure.fromThrowable(error).message
            return
        }
        activeListener = listener
        activeFailureListener = failureListener
        transport = created
        state = RelayConnectionState.RELAY_CONNECTING
        lastError = null
    }

    private fun stopTransport() {
        transportGeneration += 1
        activeListener?.let { listener ->
            transport?.removeStateListener(listener)
        }
        activeFailureListener?.let { listener ->
            transport?.removeFailureListener(listener)
        }
        activeListener = null
        activeFailureListener = null
        val active = transport
        transport = null
        if (active != null) runCatching { active.close() }
    }

    private fun applyState(newState: RelayConnectionState) {
        state = newState
        when (newState) {
            RelayConnectionState.RELAY_CONNECTED -> {
                val now = clock()
                lastConnectedAtUtcMillis = now
                settingsStore.updateLastConnected(now)
                lastError = RelaySettingsErrorPolicy.afterStateChange(newState, lastError)
            }
            RelayConnectionState.OFFLINE,
            RelayConnectionState.AUTH_REQUIRED,
            RelayConnectionState.REVOKED,
            RelayConnectionState.ERROR,
            RelayConnectionState.PAIRING_REQUIRED -> {
                lastError = RelaySettingsErrorPolicy.afterStateChange(newState, lastError)
            }
            RelayConnectionState.RELAY_CONNECTING, RelayConnectionState.LOCAL -> Unit
        }
    }

    private companion object {
        const val MIN_START_INTERVAL_MILLIS = 5_000L
    }
}

/** Keeps terminal revocation presentation stable when close/network failures arrive later. */
internal object RelaySettingsErrorPolicy {
    fun afterStateChange(state: RelayConnectionState, current: String?): String? = when (state) {
        RelayConnectionState.RELAY_CONNECTED -> null
        RelayConnectionState.OFFLINE -> current ?: "Relais indisponible"
        RelayConnectionState.AUTH_REQUIRED -> current ?: RelayFailure.fromRemoteCode("AUTH_REQUIRED").message
        RelayConnectionState.REVOKED -> RelayFailure.fromRemoteCode("DEVICE_REVOKED").message
        RelayConnectionState.ERROR -> current ?: RelayFailure.of(RelayFailureKind.UNKNOWN).message
        RelayConnectionState.PAIRING_REQUIRED -> current ?: "Appairage requis"
        RelayConnectionState.RELAY_CONNECTING, RelayConnectionState.LOCAL -> current
    }

    fun afterFailure(
        currentState: RelayConnectionState,
        failure: RelayFailure,
        current: String?,
    ): String? = if (currentState == RelayConnectionState.REVOKED && failure.kind != RelayFailureKind.REVOKED) {
        current
    } else {
        failure.message
    }
}
