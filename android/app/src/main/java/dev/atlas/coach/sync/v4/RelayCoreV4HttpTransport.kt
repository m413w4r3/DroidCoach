package dev.atlas.coach.sync.v4

import dev.atlas.coach.sync.relay.AtlasRemoteProtocol
import dev.atlas.coach.sync.relay.RelayBackendTransport
import dev.atlas.coach.sync.relay.RelayCallResult
import dev.atlas.coach.sync.relay.RelayConnectionState
import dev.atlas.coach.sync.relay.RelayTransportMode
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4HttpPoster
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** The small surface the V4 route adapter needs from an authenticated relay session. */
internal interface RelayCoreV4Session {
    val state: RelayConnectionState
    fun hasCapability(capability: String): Boolean
    fun executeDeferred(
        operation: String,
        timeoutMillis: Long,
        idempotencyKey: String,
        requestId: String,
        payloadFactory: () -> JSONObject,
        beforeDispatch: () -> Boolean,
    ): RelayCallResult
}

internal class RelayBackendCoreV4Session(
    private val transport: RelayBackendTransport,
) : RelayCoreV4Session {
    override val state: RelayConnectionState get() = transport.state()

    override fun hasCapability(capability: String): Boolean =
        transport.hasNegotiatedCapability(capability)

    override fun executeDeferred(
        operation: String,
        timeoutMillis: Long,
        idempotencyKey: String,
        requestId: String,
        payloadFactory: () -> JSONObject,
        beforeDispatch: () -> Boolean,
    ): RelayCallResult = transport.executeDeferred(
        operation = operation,
        timeoutMillis = timeoutMillis,
        idempotencyKey = idempotencyKey,
        requestId = requestId,
        payloadFactory = payloadFactory,
        beforeDispatch = beforeDispatch,
    )
}

/** Process-local handle to the one user-connected relay session. */
internal object RelayCoreV4SessionRegistry {
    @Volatile private var active: RelayCoreV4Session? = null

    fun current(): RelayCoreV4Session? = active

    fun install(session: RelayCoreV4Session) {
        active = session
    }

    fun clear(session: RelayCoreV4Session) {
        if (active === session) active = null
    }
}

/**
 * Maps the existing Core V4 client requests onto the closed relay operation set.
 * It consumes only the fixed path and JSON body: the Core bearer in the request is
 * deliberately discarded and no URL, method, or caller-supplied header is forwarded.
 */
class RelayCoreV4HttpTransport internal constructor(
    private val sessionProvider: () -> RelayCoreV4Session? = RelayCoreV4SessionRegistry::current,
    private val requestAllowed: (CoreV4HttpRequest) -> Boolean = { true },
    private val requestStart: ((CoreV4HttpRequest, () -> Unit) -> Boolean)? = null,
) : CoreV4HttpTransport, InboxV4HttpPoster {
    private val activeThread = AtomicReference<Thread?>(null)
    @Volatile private var cancelled = false

    override val requiresBearerToken: Boolean get() = false

    override fun post(request: CoreV4HttpRequest): CoreV4HttpResponse {
        if (cancelled) fail(CoreV4FailureKind.OFFLINE, errorCode = "CANCELLED")
        if (request.body.toByteArray(Charsets.UTF_8).size > CoreV4SyncClient.MAX_REQUEST_BYTES) {
            fail(CoreV4FailureKind.PROTOCOL, errorCode = "PAYLOAD_TOO_LARGE")
        }
        val route = routeFor(request.path) ?: fail(CoreV4FailureKind.CONFIGURATION, errorCode = "ROUTE_NOT_ALLOWED")
        val payload = try {
            JSONObject(request.body)
        } catch (_: Exception) {
            fail(CoreV4FailureKind.PROTOCOL)
        }
        route.proposalId?.let { expectedId ->
            if (!AtlasRemoteProtocol.isUuid(expectedId) || payload.optString("proposal_id") != expectedId) {
                fail(CoreV4FailureKind.PROTOCOL, errorCode = "PROPOSAL_ID_MISMATCH")
            }
        }
        val idempotencyKey = when (route.operation) {
            "v4.proposals.ack" -> payload.optString("ack_id")
            else -> if (payload.has("idempotency_key")) {
                payload.optString("idempotency_key")
            } else {
                payload.optString("request_id")
            }
        }
        if (!AtlasRemoteProtocol.isUuid(idempotencyKey)) {
            fail(CoreV4FailureKind.PROTOCOL, errorCode = "IDEMPOTENCY_KEY_REQUIRED")
        }
        val requestId = if (payload.has("request_id")) {
            payload.optString("request_id").takeIf(AtlasRemoteProtocol::isUuid)
                ?: fail(CoreV4FailureKind.PROTOCOL, errorCode = "REQUEST_ID_INVALID")
        } else {
            if (route.operation != "v4.proposals.ack") {
                fail(CoreV4FailureKind.PROTOCOL, errorCode = "REQUEST_ID_REQUIRED")
            }
            AtlasRemoteProtocol.newUuid()
        }
        val relayCapability = AtlasRemoteProtocol.requiredCapability(route.operation)
            ?: fail(CoreV4FailureKind.PROTOCOL, errorCode = "CAPABILITY_UNDEFINED")

        val safeRequest = CoreV4HttpRequest(request.path, bearerToken = "", body = request.body)
        if (!requestAllowed(safeRequest)) fail(CoreV4FailureKind.CONSENT)
        val session = sessionProvider() ?: fail(CoreV4FailureKind.OFFLINE)
        if (session.state != RelayConnectionState.RELAY_CONNECTED) {
            when (session.state) {
                RelayConnectionState.AUTH_REQUIRED,
                RelayConnectionState.REVOKED,
                RelayConnectionState.PAIRING_REQUIRED,
                -> fail(CoreV4FailureKind.AUTHENTICATION, errorCode = session.state.name)
                else -> fail(CoreV4FailureKind.OFFLINE, errorCode = session.state.name)
            }
        }
        if (!session.hasCapability(relayCapability)) {
            fail(CoreV4FailureKind.UNAVAILABLE, httpStatus = 503, errorCode = "CAPABILITY_UNAVAILABLE")
        }
        if (!activeThread.compareAndSet(null, Thread.currentThread())) {
            fail(CoreV4FailureKind.UNAVAILABLE, errorCode = "QUEUE_FULL")
        }
        val result = try {
            session.executeDeferred(
                operation = route.operation,
                timeoutMillis = REQUEST_TIMEOUT_MILLIS,
                idempotencyKey = idempotencyKey,
                requestId = requestId,
                payloadFactory = { JSONObject(payload.toString()) },
                beforeDispatch = { dispatchAllowed(safeRequest) },
            )
        } finally {
            activeThread.compareAndSet(Thread.currentThread(), null)
        }
        val response = when (result) {
            is RelayCallResult.Success -> CoreV4HttpResponse(200, result.result.toString())
            is RelayCallResult.RemoteError -> result.toCoreV4Exception()
            is RelayCallResult.Failure -> result.toCoreV4Exception()
        }
        if (response.body.toByteArray(Charsets.UTF_8).size > CoreV4SyncClient.MAX_RESPONSE_BYTES) {
            fail(CoreV4FailureKind.PROTOCOL, errorCode = "RESPONSE_TOO_LARGE")
        }
        return response
    }

    override fun cancel() {
        cancelled = true
        activeThread.getAndSet(null)?.interrupt()
    }

    private fun dispatchAllowed(request: CoreV4HttpRequest): Boolean {
        if (cancelled || !requestAllowed(request)) return false
        return requestStart?.invoke(request) { Unit } ?: true
    }

    private fun routeFor(path: String): Route? = when (path) {
        "/v4/sync/day-plan-snapshots" -> Route("v4.plan.snapshot.upsert")
        "/v4/sync/activity-status-events" -> Route("v4.activity-status.events.append")
        "/v4/sync/usage-summaries" -> Route("v4.usage.summary.upsert")
        "/v4/sync/goal-progress-snapshots" -> Route("v4.goal.snapshot.upsert")
        "/v4/sync/training-snapshots" -> Route("v4.training.snapshot.upsert")
        "/v4/sync/consent-state" -> Route("v4.consent.state.upsert")
        "/v4/agent/proposals/pull" -> Route("v4.proposals.pull")
        else -> proposalAckRoute(path)
    }

    private fun proposalAckRoute(path: String): Route? {
        val prefix = "/v4/agent/proposals/"
        val suffix = "/ack"
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) return null
        val proposalId = path.substring(prefix.length, path.length - suffix.length)
        if (!AtlasRemoteProtocol.isUuid(proposalId)) return null
        return Route("v4.proposals.ack", proposalId)
    }

    private data class Route(val operation: String, val proposalId: String? = null)

    companion object {
        private const val REQUEST_TIMEOUT_MILLIS = 8_000L
    }
}

internal object CoreV4OfflineHttpTransport : CoreV4HttpTransport {
    override val requiresBearerToken: Boolean get() = false

    override fun post(request: CoreV4HttpRequest): CoreV4HttpResponse =
        throw CoreV4SyncException(CoreV4FailureKind.OFFLINE, retryable = false)
}

internal fun selectCoreV4HttpTransport(
    mode: RelayTransportMode,
    direct: CoreV4HttpTransport,
    relay: CoreV4HttpTransport,
): CoreV4HttpTransport = when (mode) {
    RelayTransportMode.DIRECT -> direct
    RelayTransportMode.RELAY -> relay
    RelayTransportMode.OFFLINE -> CoreV4OfflineHttpTransport
}

private fun RelayCallResult.RemoteError.toCoreV4Exception(): Nothing {
    val code = remoteCode ?: code
    val status = httpStatus ?: code.removePrefix("HTTP_").toIntOrNull()
    val kind = when {
        code == "IDEMPOTENCY_CONFLICT" -> CoreV4FailureKind.IDEMPOTENCY_CONFLICT
        code in setOf("AUTH_REQUIRED", "AUTH_FAILED", "DEVICE_REVOKED") || status == 401 ->
            CoreV4FailureKind.AUTHENTICATION
        code in setOf("CONSENT_REQUIRED", "CONSENT_REVOKED", "CONSENT_VERSION_MISMATCH") || status == 403 ->
            CoreV4FailureKind.CONSENT
        code in setOf("CONFLICT", "STALE_VERSION", "STALE_EVENT") || status == 409 ->
            CoreV4FailureKind.CONFLICT
        status == 422 -> CoreV4FailureKind.REJECTED
        status == 408 || status == 429 || status in 500..599 -> CoreV4FailureKind.UNAVAILABLE
        status in 400..499 -> CoreV4FailureKind.REJECTED
        code in setOf("INVALID_REQUEST", "UNKNOWN_OPERATION", "PAYLOAD_TOO_LARGE", "PROTOCOL") ->
            CoreV4FailureKind.PROTOCOL
        code == "BACKEND_REJECTED" -> CoreV4FailureKind.REJECTED
        else -> CoreV4FailureKind.UNAVAILABLE
    }
    throw CoreV4SyncException(kind, retryable = retryable, httpStatus = status, errorCode = code)
}

private fun RelayCallResult.Failure.toCoreV4Exception(): Nothing {
    val kind = when (reason) {
        RelayCallResult.Failure.Reason.POLICY_DENIED -> CoreV4FailureKind.CONSENT
        RelayCallResult.Failure.Reason.PROTOCOL -> CoreV4FailureKind.PROTOCOL
        RelayCallResult.Failure.Reason.NOT_STARTED,
        RelayCallResult.Failure.Reason.OFFLINE,
        RelayCallResult.Failure.Reason.CANCELLED,
        -> CoreV4FailureKind.OFFLINE
        RelayCallResult.Failure.Reason.QUEUE_FULL,
        RelayCallResult.Failure.Reason.EXPIRED,
        RelayCallResult.Failure.Reason.TIMEOUT,
        -> CoreV4FailureKind.UNAVAILABLE
    }
    throw CoreV4SyncException(kind, retryable = false, errorCode = reason.name)
}

private fun fail(
    kind: CoreV4FailureKind,
    httpStatus: Int? = null,
    errorCode: String? = null,
): Nothing = throw CoreV4SyncException(
    kind = kind,
    retryable = false,
    httpStatus = httpStatus,
    errorCode = errorCode,
)
