package dev.atlas.coach.sync.relay

import dev.atlas.coach.data.AgentConsentGate
import dev.atlas.coach.sync.v4.CoreV4Diagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

sealed interface RelayCallResult {
    data class Success(val result: JSONObject, val replayed: Boolean) : RelayCallResult
    data class RemoteError(
        val code: String,
        val message: String,
        val retryable: Boolean,
        val httpStatus: Int? = null,
        val remoteCode: String? = null,
    ) : RelayCallResult
    data class Failure(val reason: Reason) : RelayCallResult {
        enum class Reason { NOT_STARTED, OFFLINE, QUEUE_FULL, EXPIRED, TIMEOUT, PROTOCOL, POLICY_DENIED, CANCELLED }
    }
}

/**
 * Bounded, correlated request queue over the authenticated relay session. A
 * request is sent at most once; only requests that have not reached the socket
 * survive a reconnect.
 */
class RelayBackendTransport internal constructor(
    private val config: RelayConfig,
    private val credentialStore: RelayCredentialStore,
    private val consentGate: AgentConsentGate = AgentConsentGate(),
    socketFactory: RelaySocketFactory = OkHttpRelaySocketFactory(config),
    private val scheduler: java.util.concurrent.ScheduledExecutorService =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it, "atlas-relay-transport").apply { isDaemon = true } },
    private val heartbeatIntervalMillis: Long = 15_000,
    private val heartbeatTimeoutMillis: Long = 8_000,
) : AutoCloseable {
    private val applicationProtocol = AtlasRemoteProtocol.protocolForCapabilities(config.requestedCapabilities)

    init {
        require(heartbeatIntervalMillis > 0 && heartbeatTimeoutMillis > 0) {
            "heartbeat interval and timeout must be positive"
        }
    }
    private val lock = Any()
    private val client = PaseoRelayClient(
        config = config,
        credentialsProvider = credentialStore,
        listener = ClientListener(),
        socketFactory = socketFactory,
        scheduler = scheduler,
    )
    private val queue = ArrayDeque<Pending>()
    private val inFlight = LinkedHashMap<String, Pending>()
    private val timedOutIds = LinkedHashMap<String, Long>()
    private val maxQueue = config.maxQueuedRequests
    private val maxConcurrent = config.maxConcurrentRequests
    private val stateListeners = CopyOnWriteArrayList<(RelayConnectionState) -> Unit>()
    private val failureListeners = CopyOnWriteArrayList<(RelayFailure) -> Unit>()
    private val stateNotifier = java.util.concurrent.Executors.newSingleThreadExecutor {
        Thread(it, "atlas-relay-state").apply { isDaemon = true }
    }
    @Volatile private var connectionState = RelayConnectionState.LOCAL
    @Volatile var sessionGeneration: Long = 0L
        private set
    @Volatile var lastCloseCode: Int? = null
        private set
    private var appSessionId: String? = null
    private var appSessionExpiry: Instant? = null
    private var heartbeatTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var heartbeatNonce: String? = null
    private var heartbeatSentAtNanos: Long = 0L
    private var negotiatedRequestLimit = AtlasRemoteProtocol.MAX_REQUEST_BYTES
    private var negotiatedResponseLimit = AtlasRemoteProtocol.MAX_RESPONSE_BYTES
    private var negotiatedCapabilities = emptySet<String>()
    private var capabilityNegotiationComplete = false
    private var coachChatCapabilityFailureReported = false
    private var started = false

    fun state(): RelayConnectionState = connectionState
    /** True only after an authenticated V2 session negotiated this capability. */
    fun hasNegotiatedCapability(capability: String): Boolean = synchronized(lock) {
        started && connectionState == RelayConnectionState.RELAY_CONNECTED &&
            capabilityNegotiationComplete && capability in negotiatedCapabilities
    }
    fun addStateListener(listener: (RelayConnectionState) -> Unit) { stateListeners.add(listener) }
    fun removeStateListener(listener: (RelayConnectionState) -> Unit) { stateListeners.remove(listener) }
    fun addFailureListener(listener: (RelayFailure) -> Unit) { failureListeners.add(listener) }
    fun removeFailureListener(listener: (RelayFailure) -> Unit) { failureListeners.remove(listener) }

    fun start() {
        synchronized(lock) {
            started = true
        }
        client.start()
        scheduleHeartbeat()
    }

    fun execute(
        operation: String,
        payload: JSONObject,
        timeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
        idempotencyKey: String = AtlasRemoteProtocol.newUuid(),
        beforeDispatch: () -> Boolean = { true },
        requestId: String = AtlasRemoteProtocol.newUuid(),
    ): RelayCallResult {
        val payloadSnapshot = JSONObject(payload.toString())
        return executeDeferred(
            operation = operation,
            timeoutMillis = timeoutMillis,
            idempotencyKey = idempotencyKey,
            payloadFactory = { JSONObject(payloadSnapshot.toString()) },
            beforeDispatch = beforeDispatch,
            requestId = requestId,
        )
    }

    /** Defers payload assembly until the request is leaving the queue for dispatch. */
    fun executeDeferred(
        operation: String,
        timeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
        idempotencyKey: String = AtlasRemoteProtocol.newUuid(),
        payloadFactory: () -> JSONObject,
        beforeDispatch: () -> Boolean = { true },
        requestId: String = AtlasRemoteProtocol.newUuid(),
    ): RelayCallResult {
        if (!AtlasRemoteProtocol.supportsOperation(applicationProtocol, operation) || timeoutMillis <= 0) {
            return RelayCallResult.Failure(RelayCallResult.Failure.Reason.PROTOCOL)
        }
        val requiredCapability = AtlasRemoteProtocol.requiredCapability(operation)
        if (requiredCapability != null && requiredCapability !in config.requestedCapabilities) {
            return unavailableCapability(requiredCapability)
        }
        synchronized(lock) {
            if (requiredCapability != null && capabilityNegotiationComplete && requiredCapability !in negotiatedCapabilities) {
                return unavailableCapability(requiredCapability)
            }
        }
        val now = Instant.now()
        val boundedTimeout = minOf(timeoutMillis, AtlasRemoteProtocol.MAX_REQUEST_TTL_SECONDS * 1000)
        val ttlSeconds = ((boundedTimeout + 999) / 1000)
            .coerceIn(1, AtlasRemoteProtocol.MAX_REQUEST_TTL_SECONDS)
        val pending = Pending(
            requestId = requestId,
            operation = operation,
            idempotencyKey = idempotencyKey,
            payloadFactory = payloadFactory,
            createdAt = now,
            expiresAt = now.plusSeconds(ttlSeconds),
            deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(boundedTimeout),
            beforeDispatch = beforeDispatch,
            diagnosticContext = CoreV4Diagnostics.currentExecutionContext(),
            diagnosticCategory = CoreV4Diagnostics.categoryForOperation(operation),
            diagnosticSessionGeneration = sessionGeneration,
        )
        synchronized(lock) {
            if (!started) {
                recordDiagnostic(pending, CoreV4Diagnostics.Stage.OFFLINE, CoreV4Diagnostics.State.FAILED,
                    CoreV4Diagnostics.Result.OFFLINE)
                return RelayCallResult.Failure(RelayCallResult.Failure.Reason.NOT_STARTED)
            }
            if (connectionState == RelayConnectionState.AUTH_REQUIRED) {
                return RelayCallResult.RemoteError(
                    "AUTH_REQUIRED",
                    RelayFailure.fromRemoteCode("AUTH_REQUIRED").message,
                    false,
                )
            }
            if (connectionState == RelayConnectionState.REVOKED) {
                return RelayCallResult.RemoteError(
                    "DEVICE_REVOKED",
                    RelayFailure.fromRemoteCode("DEVICE_REVOKED").message,
                    false,
                )
            }
            if (connectionState == RelayConnectionState.PAIRING_REQUIRED) {
                recordDiagnostic(pending, CoreV4Diagnostics.Stage.OFFLINE, CoreV4Diagnostics.State.FAILED,
                    CoreV4Diagnostics.Result.OFFLINE)
                return RelayCallResult.Failure(RelayCallResult.Failure.Reason.OFFLINE)
            }
            if (queue.any { it.requestId == requestId } || inFlight.containsKey(requestId)) {
                recordDiagnostic(pending, CoreV4Diagnostics.Stage.ERROR, CoreV4Diagnostics.State.FAILED,
                    CoreV4Diagnostics.Result.PROTOCOL)
                return RelayCallResult.Failure(RelayCallResult.Failure.Reason.PROTOCOL)
            }
            if (queue.size + inFlight.size >= maxQueue) {
                recordDiagnostic(pending, CoreV4Diagnostics.Stage.QUEUE_FULL, CoreV4Diagnostics.State.FAILED,
                    CoreV4Diagnostics.Result.QUEUE_FULL)
                return RelayCallResult.Failure(RelayCallResult.Failure.Reason.QUEUE_FULL)
            }
            queue.addLast(pending)
            recordDiagnostic(pending, CoreV4Diagnostics.Stage.QUEUED, CoreV4Diagnostics.State.QUEUED,
                CoreV4Diagnostics.Result.ACCEPTED)
        }
        pump()
        val remaining = (pending.deadlineNanos - System.nanoTime()).coerceAtLeast(0)
        val completed = try { pending.done.await(remaining, TimeUnit.NANOSECONDS) } catch (_: InterruptedException) {
            finishCancelledRequest(pending)
            Thread.currentThread().interrupt()
            return RelayCallResult.Failure(RelayCallResult.Failure.Reason.CANCELLED)
        }
        if (!completed) {
            var sendCancel = false
            synchronized(lock) {
                if (pending.result != null) return pending.result!!
                if (pending.sent) {
                    inFlight.remove(pending.requestId)
                    rememberTimedOutLocked(pending.requestId)
                    sendCancel = true
                } else {
                    queue.remove(pending)
                }
                pending.result = RelayCallResult.Failure(RelayCallResult.Failure.Reason.TIMEOUT)
            }
            pending.done.countDown()
            recordDiagnostic(pending, CoreV4Diagnostics.Stage.TIMEOUT, CoreV4Diagnostics.State.FAILED,
                CoreV4Diagnostics.Result.TIMEOUT)
            if (sendCancel) sendCancel(pending.requestId)
            pump()
            return RelayCallResult.Failure(RelayCallResult.Failure.Reason.TIMEOUT)
        }
        return pending.result ?: RelayCallResult.Failure(RelayCallResult.Failure.Reason.CANCELLED)
    }

    override fun close() {
        val failures: List<Pending>
        val closedGeneration: Long
        synchronized(lock) {
            if (!started) return
            started = false
            closedGeneration = sessionGeneration
            appSessionId = null
            appSessionExpiry = null
            negotiatedCapabilities = emptySet()
            capabilityNegotiationComplete = false
            heartbeatTask?.cancel(false)
            heartbeatTask = null
            heartbeatNonce = null
            setStateLocked(RelayConnectionState.LOCAL)
            failures = (queue.toList() + inFlight.values).distinct()
            queue.clear()
            inFlight.clear()
            failures.forEach { completeLocked(it, RelayCallResult.Failure(RelayCallResult.Failure.Reason.OFFLINE)) }
        }
        CoreV4Diagnostics.record(
            stage = CoreV4Diagnostics.Stage.SESSION_CLOSED,
            state = CoreV4Diagnostics.State.CLOSED,
            result = CoreV4Diagnostics.Result.LOCAL_CLOSE,
            sessionGeneration = closedGeneration,
        )
        failures.forEach {
            recordDiagnostic(it, CoreV4Diagnostics.Stage.OFFLINE, CoreV4Diagnostics.State.FAILED,
                CoreV4Diagnostics.Result.OFFLINE)
        }
        client.stop()
    }

    private fun onSecureChannelReady() {
        val credentials = try { credentialStore.credentials() } catch (_: Exception) { null }
        if (credentials == null) {
            setState(RelayConnectionState.PAIRING_REQUIRED)
            return
        }
        val hello = try {
            AtlasRemoteProtocol.hello(
                credentials,
                CLIENT_VERSION,
                config.requestedCapabilities,
                protocol = applicationProtocol,
            )
                .toString().toByteArray(Charsets.UTF_8)
        }
        catch (_: Exception) {
            setState(RelayConnectionState.AUTH_REQUIRED)
            reportFailure(RelayFailure.fromRemoteCode("AUTH_FAILED"))
            return
        }
        if (!client.sendTextFrame(hello)) setState(RelayConnectionState.ERROR)
    }

    private fun handleApplicationFrame(bytes: ByteArray) {
        val envelope = try { AtlasRemoteProtocol.parse(bytes, applicationProtocol) } catch (error: Exception) {
            failProtocol(error)
            return
        }
        when (envelope.optString("kind")) {
            "hello_ok" -> handleHelloOk(envelope)
            "response" -> handleResponse(envelope)
            "error" -> handleError(envelope)
            "ping" -> handlePing(envelope)
            "pong" -> handlePong(envelope)
            else -> failProtocol(RelayProtocolException("unexpected server envelope"))
        }
    }

    private fun handleHelloOk(envelope: JSONObject) {
        val session = envelope.optString("session_id")
        val caps = envelope.optJSONArray("capabilities") ?: JSONArray()
        val serverCapabilities = (0 until caps.length())
            .mapNotNull { caps.optString(it).takeIf(String::isNotEmpty) }
            .toSet()
        var reportMissingCoachChat = false
        var replacedSessionGeneration: Long? = null
        val accepted = synchronized(lock) {
            if (appSessionId != null || !started) {
                false
            } else {
                negotiatedCapabilities = serverCapabilities.intersect(config.requestedCapabilities)
                capabilityNegotiationComplete = true
                appSessionId = session
                if (sessionGeneration > 0L) replacedSessionGeneration = sessionGeneration
                sessionGeneration = NEXT_SESSION_GENERATION.incrementAndGet()
                appSessionExpiry = Instant.now().plusSeconds(envelope.optLong("expires_in_seconds"))
                heartbeatNonce = null
                heartbeatSentAtNanos = 0L
                negotiatedRequestLimit = minOf(AtlasRemoteProtocol.MAX_REQUEST_BYTES, envelope.optInt("max_request_bytes"))
                negotiatedResponseLimit = minOf(AtlasRemoteProtocol.MAX_RESPONSE_BYTES, envelope.optInt("max_response_bytes"))

                val unsupported = queue.mapNotNull { pending ->
                    AtlasRemoteProtocol.requiredCapability(pending.operation)
                        ?.takeIf { it !in negotiatedCapabilities }
                        ?.let { capability -> pending to capability }
                }
                unsupported.forEach { (pending, capability) ->
                    queue.remove(pending)
                    completeLocked(pending, unavailableCapability(capability))
                }

                if ("coach.chat" in config.requestedCapabilities &&
                    "coach.chat" !in negotiatedCapabilities && !coachChatCapabilityFailureReported
                ) {
                    coachChatCapabilityFailureReported = true
                    reportMissingCoachChat = true
                }
                setStateLocked(RelayConnectionState.RELAY_CONNECTED)
                true
            }
        }
        if (!accepted) {
            failProtocol(RelayProtocolException("unexpected hello_ok"))
            return
        }
        replacedSessionGeneration?.let {
            CoreV4Diagnostics.record(
                stage = CoreV4Diagnostics.Stage.SESSION_REPLACED,
                state = CoreV4Diagnostics.State.STARTED,
                result = CoreV4Diagnostics.Result.SESSION_REPLACED,
                sessionGeneration = sessionGeneration,
            )
        }
        if (reportMissingCoachChat) reportFailure(RelayFailure.of(RelayFailureKind.CAPABILITY_UNAVAILABLE))
        pump()
    }

    private fun handleResponse(envelope: JSONObject) {
        val requestId = envelope.optString("request_id")
        val sessionId = envelope.optString("session_id")
        val operation = envelope.optString("operation")
        synchronized(lock) {
            if (sessionId != appSessionId) {
                if (sessionId.isNotBlank() && appSessionId != null) return
                failProtocolLocked(RelayProtocolException("response session correlation mismatch"))
                return
            }
            val pending = inFlight[requestId]
            pending?.let(::recordSocketSendAccepted)
            CoreV4Diagnostics.record(
                stage = CoreV4Diagnostics.Stage.RESPONSE_RECEIVED,
                state = CoreV4Diagnostics.State.RESPONSE,
                result = CoreV4Diagnostics.Result.SUCCESS,
                category = pending?.diagnosticCategory ?: CoreV4Diagnostics.categoryForOperation(operation),
                consentVersion = pending?.diagnosticConsentVersion,
                durationMs = pending?.let(::elapsedMillisSince) ?: 0L,
                sessionGeneration = pending?.diagnosticSessionGeneration ?: sessionGeneration,
                requestId = requestId,
            )
            if (pending == null) {
                if (timedOutIds.remove(requestId) != null) return
                failProtocolLocked(RelayProtocolException("response has no matching request"))
                return
            }
            if (operation != pending.operation) {
                failProtocolLocked(RelayProtocolException("response correlation mismatch"))
                return
            }
            recordDiagnostic(
                pending,
                CoreV4Diagnostics.Stage.CORRELATED,
                CoreV4Diagnostics.State.CORRELATED,
                CoreV4Diagnostics.Result.CORRELATED,
            )
            inFlight.remove(requestId)
            val responseBytes = envelope.toString().toByteArray(Charsets.UTF_8)
            if (responseBytes.size > negotiatedResponseLimit) {
                completeLocked(pending, RelayCallResult.Failure(RelayCallResult.Failure.Reason.PROTOCOL))
            } else {
                completeLocked(pending, RelayCallResult.Success(envelope.getJSONObject("result"), envelope.getBoolean("replayed")))
            }
        }
        pump()
    }

    private fun handleError(envelope: JSONObject) {
        val error = envelope.getJSONObject("error")
        val relayCode = error.getString("code")
        val remoteCode = error.optString("remote_code").takeIf { error.has("remote_code") }
        val code = remoteCode ?: relayCode
        val message = RelayFailure.fromRemoteCode(relayCode).message
        val retryable = error.getBoolean("retryable")
        val httpStatus = error.optInt("http_status").takeIf { error.has("http_status") }
        val remoteError = RelayCallResult.RemoteError(code, message, retryable, httpStatus, remoteCode)
        val requestId = if (envelope.isNull("request_id")) null else envelope.getString("request_id")
        val sessionId = if (envelope.isNull("session_id")) null else envelope.getString("session_id")
        if (requestId == null) {
            synchronized(lock) {
                if (sessionId != null && sessionId != appSessionId) {
                    if (sessionId.isNotBlank() && appSessionId != null) return
                    failProtocolLocked(RelayProtocolException("session error correlation mismatch"))
                    return
                }
                when (relayCode) {
                    "AUTH_REQUIRED", "AUTH_FAILED" -> setStateLocked(RelayConnectionState.AUTH_REQUIRED)
                    "DEVICE_REVOKED" -> setStateLocked(RelayConnectionState.REVOKED)
                    else -> setStateLocked(RelayConnectionState.ERROR)
                }
                reportRemoteFailure(relayCode)
                val waiting = queue.toList()
                queue.clear()
                waiting.forEach { completeLocked(it, remoteError) }
                val sent = inFlight.values.toList()
                inFlight.clear()
                sent.forEach { completeLocked(it, remoteError) }
                appSessionId = null
                appSessionExpiry = null
            }
            return
        }
        synchronized(lock) {
            if (sessionId != appSessionId) {
                if (!sessionId.isNullOrBlank() && appSessionId != null) return
                failProtocolLocked(RelayProtocolException("error session correlation mismatch"))
                return
            }
            val pending = inFlight[requestId]
            CoreV4Diagnostics.record(
                stage = CoreV4Diagnostics.Stage.RESPONSE_RECEIVED,
                state = CoreV4Diagnostics.State.RESPONSE,
                result = CoreV4Diagnostics.Result.REJECTED,
                category = pending?.diagnosticCategory,
                consentVersion = pending?.diagnosticConsentVersion,
                durationMs = pending?.let(::elapsedMillisSince) ?: 0L,
                sessionGeneration = pending?.diagnosticSessionGeneration ?: sessionGeneration,
                requestId = requestId,
            )
            if (pending == null) {
                if (timedOutIds.remove(requestId) != null) return
                failProtocolLocked(RelayProtocolException("error has no matching request"))
                return
            }
            recordDiagnostic(
                pending,
                CoreV4Diagnostics.Stage.CORRELATED,
                CoreV4Diagnostics.State.CORRELATED,
                CoreV4Diagnostics.Result.REJECTED,
            )
            inFlight.remove(requestId)
            if (relayCode == "DEVICE_REVOKED") setStateLocked(RelayConnectionState.REVOKED)
            if (relayCode == "AUTH_REQUIRED" || relayCode == "AUTH_FAILED") setStateLocked(RelayConnectionState.AUTH_REQUIRED)
            reportRemoteFailure(relayCode)
            completeLocked(pending, remoteError)
        }
        pump()
    }

    private fun handlePing(envelope: JSONObject) {
        val pong = JSONObject()
            .put("protocol", applicationProtocol)
            .put("kind", "pong")
            .put("nonce", envelope.getString("nonce"))
        if (!client.sendTextFrame(AtlasRemoteProtocol.encode(pong))) failProtocol(RelayProtocolException("could not reply to ping"))
    }

    private fun scheduleHeartbeat() {
        synchronized(lock) {
            heartbeatTask?.cancel(false)
            heartbeatTask = try {
                scheduler.scheduleAtFixedRate(
                    { heartbeatTick() },
                    heartbeatIntervalMillis,
                    heartbeatIntervalMillis,
                    TimeUnit.MILLISECONDS,
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun heartbeatTick() {
        var pendingPing: Pair<String, ByteArray>? = null
        val shouldReconnect = synchronized(lock) {
            if (!started || connectionState != RelayConnectionState.RELAY_CONNECTED || appSessionId == null) {
                heartbeatNonce = null
                return
            }
            val outstanding = heartbeatNonce
            if (outstanding != null) {
                if (System.nanoTime() - heartbeatSentAtNanos < TimeUnit.MILLISECONDS.toNanos(heartbeatTimeoutMillis)) {
                    return
                }
                heartbeatNonce = null
                true
            } else {
                val nonce = AtlasRemoteProtocol.newUuid().replace("-", "").take(32)
                val ping = JSONObject()
                    .put("protocol", applicationProtocol)
                    .put("kind", "ping")
                    .put("nonce", nonce)
                val encoded = runCatching { AtlasRemoteProtocol.encode(ping) }.getOrNull()
                if (encoded == null) {
                    heartbeatNonce = null
                } else {
                    heartbeatNonce = nonce
                    heartbeatSentAtNanos = System.nanoTime()
                    pendingPing = nonce to encoded
                }
                false
            }
        }
        pendingPing?.let { (nonce, encoded) ->
            if (!client.sendTextFrame(encoded)) {
                synchronized(lock) { if (heartbeatNonce == nonce) heartbeatNonce = null }
            }
        }
        if (shouldReconnect) client.reconnect("heartbeat timeout")
    }

    private fun handlePong(envelope: JSONObject) {
        val nonce = envelope.optString("nonce")
        synchronized(lock) {
            if (heartbeatNonce != null && heartbeatNonce == nonce) heartbeatNonce = null
        }
    }

    private fun pump() {
        // Hold the same gate as consent/revocation mutations from the pre-dispatch check through
        // the WebSocket write. A withdrawal cannot interleave between the check and the frame.
        consentGate.withLock { pumpUnderConsentGate() }
    }

    private fun pumpUnderConsentGate() {
        val outgoing = mutableListOf<Pair<Pending, ByteArray>>()
        var refreshSession = false
        synchronized(lock) {
            val sessionId = appSessionId ?: return
            if (connectionState != RelayConnectionState.RELAY_CONNECTED) return
            if (appSessionExpiry?.isAfter(Instant.now()) != true) {
                setStateLocked(RelayConnectionState.AUTH_REQUIRED)
                appSessionId = null
                appSessionExpiry = null
                refreshSession = true
            } else while (queue.isNotEmpty() && inFlight.size < maxConcurrent) {
                val pending = queue.removeFirst()
                val stillAllowed = try { pending.beforeDispatch() } catch (_: Exception) { false }
                if (!stillAllowed) {
                    recordDiagnostic(pending, CoreV4Diagnostics.Stage.CANCELLED,
                        CoreV4Diagnostics.State.FAILED, CoreV4Diagnostics.Result.REJECTED)
                    completeLocked(pending, RelayCallResult.Failure(RelayCallResult.Failure.Reason.POLICY_DENIED))
                    continue
                }
                if (!pending.expiresAt.isAfter(Instant.now())) {
                    recordDiagnostic(pending, CoreV4Diagnostics.Stage.EXPIRED,
                        CoreV4Diagnostics.State.FAILED, CoreV4Diagnostics.Result.EXPIRED)
                    completeLocked(pending, RelayCallResult.Failure(RelayCallResult.Failure.Reason.EXPIRED))
                    continue
                }
                val payload = try { pending.payloadFactory() } catch (_: Exception) {
                    recordDiagnostic(pending, CoreV4Diagnostics.Stage.ERROR,
                        CoreV4Diagnostics.State.FAILED, CoreV4Diagnostics.Result.PROTOCOL)
                    completeLocked(pending, RelayCallResult.Failure(RelayCallResult.Failure.Reason.PROTOCOL))
                    continue
                }
                pending.diagnosticCategory = CoreV4Diagnostics.categoryForOperation(pending.operation, payload)
                pending.diagnosticConsentVersion = payload.optInt("consent_version").takeIf { it > 0 }
                pending.diagnosticSessionGeneration = sessionGeneration
                val request = try {
                    AtlasRemoteProtocol.request(
                        requestId = pending.requestId,
                        sessionId = sessionId,
                        operation = pending.operation,
                        idempotencyKey = pending.idempotencyKey,
                        createdAt = pending.createdAt,
                        expiresAt = pending.expiresAt,
                        payload = payload,
                        protocol = applicationProtocol,
                    )
                } catch (_: Exception) {
                    recordDiagnostic(pending, CoreV4Diagnostics.Stage.ERROR,
                        CoreV4Diagnostics.State.FAILED, CoreV4Diagnostics.Result.PROTOCOL)
                    completeLocked(pending, RelayCallResult.Failure(RelayCallResult.Failure.Reason.PROTOCOL))
                    continue
                }
                val bytes = try { AtlasRemoteProtocol.encode(request) } catch (_: Exception) {
                    recordDiagnostic(pending, CoreV4Diagnostics.Stage.ERROR,
                        CoreV4Diagnostics.State.FAILED, CoreV4Diagnostics.Result.PROTOCOL)
                    completeLocked(pending, RelayCallResult.Failure(RelayCallResult.Failure.Reason.PROTOCOL))
                    continue
                }
                if (bytes.size > negotiatedRequestLimit) {
                    recordDiagnostic(pending, CoreV4Diagnostics.Stage.ERROR,
                        CoreV4Diagnostics.State.FAILED, CoreV4Diagnostics.Result.PROTOCOL)
                    completeLocked(pending, RelayCallResult.Failure(RelayCallResult.Failure.Reason.PROTOCOL))
                    continue
                }
                pending.sent = true
                inFlight[pending.requestId] = pending
                recordDiagnostic(pending, CoreV4Diagnostics.Stage.DISPATCHED,
                    CoreV4Diagnostics.State.DISPATCHED, CoreV4Diagnostics.Result.ACCEPTED)
                outgoing.add(pending to bytes)
            }
        }
        if (refreshSession) {
            client.reconnect("application session expired")
            return
        }
        outgoing.forEach { (pending, bytes) ->
            if (client.sendTextFrame(bytes)) {
                recordSocketSendAccepted(pending)
            } else {
                if (!pending.socketSendAcceptedLogged) {
                    recordDiagnostic(pending, CoreV4Diagnostics.Stage.SOCKET_SEND_REJECTED,
                        CoreV4Diagnostics.State.FAILED, CoreV4Diagnostics.Result.REJECTED)
                }
                synchronized(lock) {
                    if (inFlight.remove(pending.requestId) != null) {
                        completeLocked(pending, RelayCallResult.Failure(RelayCallResult.Failure.Reason.OFFLINE))
                    }
                }
                recordDiagnostic(pending, CoreV4Diagnostics.Stage.OFFLINE,
                    CoreV4Diagnostics.State.FAILED, CoreV4Diagnostics.Result.OFFLINE)
            }
        }
    }

    private fun sendCancel(targetRequestId: String) {
        val cancel = JSONObject()
            .put("protocol", applicationProtocol)
            .put("kind", "cancel")
            .put("request_id", AtlasRemoteProtocol.newUuid())
            .put("target_request_id", targetRequestId)
        runCatching { client.sendTextFrame(AtlasRemoteProtocol.encode(cancel)) }
    }

    private fun failProtocol(error: Throwable) {
        synchronized(lock) { failProtocolLocked(error) }
    }

    private fun finishCancelledRequest(pending: Pending) {
        var sendCancel = false
        synchronized(lock) {
            if (pending.result != null) return
            if (pending.sent) {
                inFlight.remove(pending.requestId)
                rememberTimedOutLocked(pending.requestId)
                sendCancel = true
            } else {
                queue.remove(pending)
            }
            pending.result = RelayCallResult.Failure(RelayCallResult.Failure.Reason.CANCELLED)
        }
        pending.done.countDown()
        recordDiagnostic(pending, CoreV4Diagnostics.Stage.CANCELLED,
            CoreV4Diagnostics.State.FAILED, CoreV4Diagnostics.Result.CANCELLED)
        if (sendCancel) sendCancel(pending.requestId)
        pump()
    }

    private fun recordClientProtocolFailure() {
        synchronized(lock) {
            setStateLocked(RelayConnectionState.ERROR)
            appSessionId = null
            inFlight.values.toList().forEach {
                completeLocked(it, RelayCallResult.Failure(RelayCallResult.Failure.Reason.PROTOCOL))
            }
            inFlight.clear()
        }
    }

    private fun failProtocolLocked(@Suppress("UNUSED_PARAMETER") error: Throwable) {
        setStateLocked(RelayConnectionState.ERROR)
        appSessionId = null
        inFlight.values.toList().forEach { completeLocked(it, RelayCallResult.Failure(RelayCallResult.Failure.Reason.PROTOCOL)) }
        inFlight.clear()
        client.abort(4003, "application protocol failure")
    }

    private fun onClientState(state: RelayConnectionState) {
        synchronized(lock) {
            if (state == RelayConnectionState.RELAY_CONNECTED) return // app auth must finish first
            if (state == RelayConnectionState.RELAY_CONNECTING || state == RelayConnectionState.OFFLINE ||
                state == RelayConnectionState.PAIRING_REQUIRED || state == RelayConnectionState.AUTH_REQUIRED ||
                state == RelayConnectionState.REVOKED || state == RelayConnectionState.ERROR || state == RelayConnectionState.LOCAL) {
                val mappedState = if (state == RelayConnectionState.AUTH_REQUIRED && connectionState == RelayConnectionState.REVOKED) {
                    RelayConnectionState.REVOKED
                } else state
                setStateLocked(mappedState)
                if (mappedState != RelayConnectionState.RELAY_CONNECTED) {
                    appSessionId = null
                    appSessionExpiry = null
                    heartbeatNonce = null
                    val sent = inFlight.values.toList()
                    inFlight.clear()
                    sent.forEach {
                        recordDiagnostic(it, CoreV4Diagnostics.Stage.OFFLINE,
                            CoreV4Diagnostics.State.FAILED, CoreV4Diagnostics.Result.OFFLINE)
                        completeLocked(it, RelayCallResult.Failure(RelayCallResult.Failure.Reason.OFFLINE))
                    }
                }
            }
        }
        pump()
    }

    private fun onSocketClosed(code: Int, @Suppress("UNUSED_PARAMETER") reason: String) {
        lastCloseCode = code
        CoreV4Diagnostics.record(
            stage = CoreV4Diagnostics.Stage.SESSION_CLOSED,
            state = CoreV4Diagnostics.State.CLOSED,
            result = when (code) {
                1000 -> CoreV4Diagnostics.Result.CLOSE_1000
                1001 -> CoreV4Diagnostics.Result.CLOSE_1001
                1006 -> CoreV4Diagnostics.Result.CLOSE_1006
                4001 -> CoreV4Diagnostics.Result.CLOSE_4001
                4003 -> CoreV4Diagnostics.Result.CLOSE_4003
                else -> CoreV4Diagnostics.Result.CLOSE_OTHER
            },
            sessionGeneration = sessionGeneration,
        )
        val alreadyRevoked = synchronized(lock) { connectionState == RelayConnectionState.REVOKED }
        if (code == 4001 && !alreadyRevoked) reportRemoteFailure("AUTH_REQUIRED")
        synchronized(lock) {
            appSessionId = null
            appSessionExpiry = null
            heartbeatNonce = null
            val sent = inFlight.values.toList()
            inFlight.clear()
            sent.forEach {
                val failure = when {
                    code == 4001 && alreadyRevoked -> RelayCallResult.RemoteError(
                        "DEVICE_REVOKED",
                        RelayFailure.fromRemoteCode("DEVICE_REVOKED").message,
                        false,
                    )
                    code == 4001 -> RelayCallResult.RemoteError(
                        "AUTH_REQUIRED",
                        RelayFailure.fromRemoteCode("AUTH_REQUIRED").message,
                        false,
                    )
                    else -> RelayCallResult.Failure(RelayCallResult.Failure.Reason.OFFLINE)
                }
                completeLocked(it, failure)
                recordDiagnostic(it, CoreV4Diagnostics.Stage.OFFLINE,
                    CoreV4Diagnostics.State.FAILED, CoreV4Diagnostics.Result.OFFLINE)
            }
        }
    }

    private fun setState(value: RelayConnectionState) = synchronized(lock) { setStateLocked(value) }

    private fun recordDiagnostic(
        pending: Pending,
        stage: CoreV4Diagnostics.Stage,
        state: CoreV4Diagnostics.State,
        result: CoreV4Diagnostics.Result,
    ) {
        CoreV4Diagnostics.record(
            stage = stage,
            state = state,
            result = result,
            category = pending.diagnosticCategory,
            consentVersion = pending.diagnosticConsentVersion,
            durationMs = elapsedMillisSince(pending),
            sessionGeneration = pending.diagnosticSessionGeneration,
            requestId = pending.requestId,
            context = pending.diagnosticContext,
        )
    }

    private fun elapsedMillisSince(pending: Pending): Long =
        ((System.nanoTime() - pending.diagnosticStartedNanos) / 1_000_000L).coerceAtLeast(0L)

    private fun recordSocketSendAccepted(pending: Pending) {
        val shouldRecord = synchronized(pending) {
            if (pending.socketSendAcceptedLogged) false else {
                pending.socketSendAcceptedLogged = true
                true
            }
        }
        if (shouldRecord) {
            recordDiagnostic(pending, CoreV4Diagnostics.Stage.SOCKET_SEND_ACCEPTED,
                CoreV4Diagnostics.State.SENT, CoreV4Diagnostics.Result.ACCEPTED)
        }
    }

    private fun reportRemoteFailure(code: String) {
        val failure = RelayFailure.fromRemoteCode(code)
        if (failure.kind != RelayFailureKind.UNKNOWN) reportFailure(failure)
    }

    private fun reportFailure(failure: RelayFailure) {
        stateNotifier.execute {
            failureListeners.forEach { listener -> runCatching { listener(failure) } }
        }
    }

    private fun setStateLocked(value: RelayConnectionState) {
        if (connectionState == value) return
        connectionState = value
        stateNotifier.execute {
            stateListeners.forEach { listener -> runCatching { listener(value) } }
        }
    }

    private fun completeLocked(pending: Pending, result: RelayCallResult) {
        if (pending.result != null) return
        pending.result = result
        pending.done.countDown()
    }

    private fun unavailableCapability(capability: String): RelayCallResult.RemoteError = RelayCallResult.RemoteError(
        "OPERATION_UNAVAILABLE",
        if (capability == "coach.chat") {
            "L'appareil appairé ne prend pas en charge coach.chat. Réappairez-le pour utiliser le chat relayé."
        } else {
            "Cette opération n'est pas autorisée par la session relayée."
        },
        false,
    )

    private fun rememberTimedOutLocked(requestId: String) {
        timedOutIds[requestId] = System.currentTimeMillis()
        while (timedOutIds.size > MAX_TIMED_OUT_IDS) timedOutIds.remove(timedOutIds.keys.first())
    }

    private inner class ClientListener : PaseoRelayClient.Listener {
        override fun onStateChanged(state: RelayConnectionState) = onClientState(state)
        override fun onSecureChannelReady() = this@RelayBackendTransport.onSecureChannelReady()
        override fun onApplicationFrame(frame: ByteArray) = handleApplicationFrame(frame)
        override fun onSocketClosed(code: Int, reason: String) = this@RelayBackendTransport.onSocketClosed(code, reason)
        override fun onProtocolFailure(@Suppress("UNUSED_PARAMETER") error: Throwable) { recordClientProtocolFailure() }
        override fun onRelayFailure(failure: RelayFailure) { reportFailure(failure) }
    }

    private data class Pending(
        val requestId: String,
        val operation: String,
        val idempotencyKey: String,
        val payloadFactory: () -> JSONObject,
        val createdAt: Instant,
        val expiresAt: Instant,
        val deadlineNanos: Long,
        val beforeDispatch: () -> Boolean,
        val diagnosticContext: CoreV4Diagnostics.ExecutionContext?,
        var diagnosticCategory: dev.atlas.coach.sync.v4.CoreV4Category?,
        var diagnosticConsentVersion: Int? = null,
        var diagnosticSessionGeneration: Long = 0L,
        @Volatile var socketSendAcceptedLogged: Boolean = false,
        val diagnosticStartedNanos: Long = System.nanoTime(),
        val done: CountDownLatch = CountDownLatch(1),
        var sent: Boolean = false,
        @Volatile var result: RelayCallResult? = null,
    )

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 30_000L
        private const val CLIENT_VERSION = "0.1.0-r3b"
        private const val MAX_TIMED_OUT_IDS = 256
        private val NEXT_SESSION_GENERATION = AtomicLong()
    }
}
