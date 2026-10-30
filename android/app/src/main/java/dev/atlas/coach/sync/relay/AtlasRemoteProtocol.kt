package dev.atlas.coach.sync.relay

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/** Strict codec for the frozen relay V1/V2 envelope schemas. */
object AtlasRemoteProtocol {
    const val PROTOCOL = "atlas.remote.v1"
    const val PROTOCOL_V2 = "atlas.remote.v2"
    const val MAX_APPLICATION_FRAME_BYTES = 65_536
    const val MAX_REQUEST_BYTES = 32_768
    const val MAX_RESPONSE_BYTES = 262_144
    const val MAX_REQUEST_TTL_SECONDS = 60L

    val OPERATIONS = setOf(
        "backend.health", "coach.status", "plan.today.read", "plan.revision.request",
        "plan.revision.result", "sport.plan.read", "sync.status", "coach.chat.request",
        "v4.coach.chat.request", "v4.proposals.pull",
        "v4.plan.snapshot.upsert", "v4.activity-status.events.append", "v4.usage.summary.upsert",
        "v4.goal.snapshot.upsert", "v4.training.snapshot.upsert", "v4.proposals.ack",
        "v4.consent.state.upsert",
    )
    val ERROR_CODES = setOf(
        "INVALID_REQUEST", "UNKNOWN_OPERATION", "PAYLOAD_TOO_LARGE", "EXPIRED", "AUTH_REQUIRED",
        "AUTH_FAILED", "DEVICE_REVOKED", "RATE_LIMITED", "IDEMPOTENCY_CONFLICT", "BACKEND_UNAVAILABLE",
        "BACKEND_REJECTED", "OPERATION_UNAVAILABLE", "CANCELLED", "INTERNAL",
        "HTTP_401", "HTTP_403", "HTTP_408", "HTTP_409", "HTTP_422", "HTTP_503",
    )
    val HELLO_CAPABILITIES = setOf(
        "plan.revision", "coach.chat", "v4.coach.chat", "v4.proposal.ack",
        "coach.status", "plan.today", "sport.plan", "sync.status",
        "v4.day_plan.snapshot.write", "v4.activity_status.write", "v4.usage_summary.write",
        "v4.goal_progress.write", "v4.training_plan.write", "v4.consent.state.write",
    )
    const val MAX_V1_HELLO_CAPABILITIES = 8
    const val MAX_V2_HELLO_CAPABILITIES = 14
    private val V1_HELLO_CAPABILITIES = HELLO_CAPABILITIES.filterNot { it.startsWith("v4.") }.toSet()
    private val V1_OPERATIONS = OPERATIONS.filterNot { it.startsWith("v4.") }.toSet()
    private val uuidPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val tokenPattern = Regex("^[A-Za-z0-9_-]{43}$")
    private val idempotencyPattern = Regex("^[A-Za-z0-9._:-]{1,160}$")
    private val httpErrorCodePattern = Regex("^HTTP_[45][0-9]{2}$")
    private val remoteCodePattern = Regex("^[A-Za-z0-9_.-]{1,64}$")
    private val noncePattern = Regex("^[A-Za-z0-9+/]{22}==$")

    fun protocolForCapabilities(capabilities: Set<String>): String =
        if (capabilities.any { it.startsWith("v4.") }) PROTOCOL_V2 else PROTOCOL

    fun supportsOperation(protocol: String, operation: String): Boolean = when (protocol) {
        PROTOCOL -> operation in V1_OPERATIONS
        PROTOCOL_V2 -> operation in OPERATIONS
        else -> false
    }

    fun parse(bytes: ByteArray, expectedProtocol: String = PROTOCOL): JSONObject {
        if (bytes.size > MAX_APPLICATION_FRAME_BYTES) fail("application frame too large")
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: Exception) {
            throw RelayProtocolException("invalid UTF-8", error)
        }
        val envelope = try { JSONObject(text) } catch (error: Exception) {
            throw RelayProtocolException("invalid JSON envelope", error)
        }
        validateMeasured(envelope, bytes.size, expectedProtocol)
        return envelope
    }

    fun encode(envelope: JSONObject): ByteArray {
        val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
        validateMeasured(envelope, bytes.size, string(envelope, "protocol"))
        return bytes
    }

    /** Used by schema-focused tests and builders; size is measured as UTF-8 JSON bytes. */
    fun validate(envelope: JSONObject, expectedProtocol: String = string(envelope, "protocol")) {
        validateMeasured(envelope, envelope.toString().toByteArray(Charsets.UTF_8).size, expectedProtocol)
    }

    private fun validateMeasured(envelope: JSONObject, encodedSize: Int, expectedProtocol: String) {
        if (encodedSize > MAX_APPLICATION_FRAME_BYTES) fail("application frame too large")
        if (encodedSize < 2) fail("empty envelope")
        if (expectedProtocol !in setOf(PROTOCOL, PROTOCOL_V2)) fail("unsupported protocol")
        requireString(envelope, "protocol", expectedProtocol)
        when (val kind = string(envelope, "kind")) {
            "hello" -> validateHello(envelope, expectedProtocol)
            "hello_ok" -> validateHelloOk(envelope, expectedProtocol)
            "request" -> validateRequest(envelope, encodedSize, expectedProtocol)
            "response" -> validateResponse(envelope, encodedSize, expectedProtocol)
            "error" -> validateError(envelope, expectedProtocol)
            "cancel" -> validateCancel(envelope)
            "ping", "pong" -> validatePingPong(envelope)
            else -> fail("unknown envelope kind: $kind")
        }
    }

    fun hello(credentials: RelayCredentials, clientVersion: String, now: Instant = Instant.now()): JSONObject =
        hello(credentials, clientVersion, setOf("plan.revision"), now)

    fun hello(
        credentials: RelayCredentials,
        clientVersion: String,
        requestedCapabilities: Set<String>,
        now: Instant = Instant.now(),
        protocol: String = PROTOCOL,
    ): JSONObject {
        require(clientVersion.isNotBlank() && clientVersion.length <= 40)
        require(protocol in setOf(PROTOCOL, PROTOCOL_V2))
        val maxCapabilities = if (protocol == PROTOCOL) MAX_V1_HELLO_CAPABILITIES else MAX_V2_HELLO_CAPABILITIES
        require(requestedCapabilities.isNotEmpty() && requestedCapabilities.size <= maxCapabilities)
        val allowedCapabilities = if (protocol == PROTOCOL) V1_HELLO_CAPABILITIES else HELLO_CAPABILITIES
        require(requestedCapabilities.all { it in allowedCapabilities })
        val nonce = ByteArray(16).also(java.security.SecureRandom()::nextBytes)
        val envelope = JSONObject()
            .put("protocol", protocol)
            .put("kind", "hello")
            .put("device_id", credentials.deviceId)
            .put("client_nonce", Base64.getEncoder().encodeToString(nonce))
            .put("client_version", clientVersion)
            .put("capabilities", JSONArray(requestedCapabilities.sorted()))
            .put("auth", JSONObject().put("scheme", "device-token-v1").put("token", credentials.deviceToken))
            .put("timestamp", now.toString())
        validate(envelope, protocol)
        return envelope
    }

    fun request(
        requestId: String,
        sessionId: String,
        operation: String,
        idempotencyKey: String,
        createdAt: Instant,
        expiresAt: Instant,
        payload: JSONObject,
        protocol: String = PROTOCOL,
    ): JSONObject {
        val envelope = JSONObject()
            .put("protocol", protocol).put("kind", "request")
            .put("request_id", requestId).put("session_id", sessionId)
            .put("operation", operation).put("idempotency_key", idempotencyKey)
            .put("created_at", createdAt.toString()).put("expires_at", expiresAt.toString())
            .put("payload", payload)
        validate(envelope, protocol)
        return envelope
    }

    fun newUuid(): String = UUID.randomUUID().toString()

    fun isUuid(value: String): Boolean = uuidPattern.matches(value)

    fun requiredCapability(operation: String): String? = when (operation) {
        "plan.revision.request", "plan.revision.result" -> "plan.revision"
        "coach.chat.request" -> "coach.chat"
        "v4.coach.chat.request" -> "v4.coach.chat"
        "v4.plan.snapshot.upsert" -> "v4.day_plan.snapshot.write"
        "v4.activity-status.events.append" -> "v4.activity_status.write"
        "v4.usage.summary.upsert" -> "v4.usage_summary.write"
        "v4.goal.snapshot.upsert" -> "v4.goal_progress.write"
        "v4.training.snapshot.upsert" -> "v4.training_plan.write"
        "v4.proposals.pull", "v4.proposals.ack" -> "v4.proposal.ack"
        "v4.consent.state.upsert" -> "v4.consent.state.write"
        else -> null
    }

    fun isDeviceToken(value: String): Boolean {
        if (!tokenPattern.matches(value)) return false
        return try {
            val decoded = Base64.getUrlDecoder().decode(value)
            decoded.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value
        } catch (_: IllegalArgumentException) { false }
    }

    fun decodeCanonicalBase64(value: String): ByteArray {
        val decoded = try { Base64.getDecoder().decode(value) } catch (error: IllegalArgumentException) {
            throw RelayProtocolException("invalid base64", error)
        }
        if (Base64.getEncoder().encodeToString(decoded) != value) fail("non-canonical base64")
        return decoded
    }

    private fun validateHello(value: JSONObject, protocol: String) {
        exactKeys(value, setOf("protocol", "kind", "device_id", "client_nonce", "client_version", "capabilities", "auth", "timestamp"))
        requireString(value, "kind", "hello")
        requireUuid(value, "device_id")
        val nonce = string(value, "client_nonce")
        if (!noncePattern.matches(nonce) || decodeCanonicalBase64(nonce).size != 16) fail("invalid client_nonce")
        val version = string(value, "client_version")
        if (version.isBlank() || version.length > 40) fail("invalid client_version")
        val capabilities = array(value, "capabilities")
        val maxCapabilities = if (protocol == PROTOCOL) MAX_V1_HELLO_CAPABILITIES else MAX_V2_HELLO_CAPABILITIES
        if (capabilities.length() > maxCapabilities) fail("too many capabilities")
        val seen = mutableSetOf<String>()
        for (i in 0 until capabilities.length()) {
            val cap = stringValue(capabilities.get(i), "capability")
            val allowedCapabilities = if (protocol == PROTOCOL) V1_HELLO_CAPABILITIES else HELLO_CAPABILITIES
            if (cap !in allowedCapabilities || !seen.add(cap)) fail("invalid capability")
        }
        val auth = objectValue(value, "auth")
        exactKeys(auth, setOf("scheme", "token"))
        requireString(auth, "scheme", "device-token-v1")
        if (!isDeviceToken(string(auth, "token"))) fail("invalid device token")
        parseTimestamp(value, "timestamp")
    }

    private fun validateHelloOk(value: JSONObject, protocol: String) {
        exactKeys(value, setOf("protocol", "kind", "session_id", "server_time", "expires_in_seconds", "capabilities", "max_request_bytes", "max_response_bytes"))
        requireString(value, "kind", "hello_ok")
        requireUuid(value, "session_id")
        parseTimestamp(value, "server_time")
        val ttl = integer(value, "expires_in_seconds")
        if (ttl !in 60L..3600L) fail("invalid session expiry")
        val capabilities = array(value, "capabilities")
        val maxCapabilities = if (protocol == PROTOCOL) MAX_V1_HELLO_CAPABILITIES else MAX_V2_HELLO_CAPABILITIES
        if (capabilities.length() > maxCapabilities) fail("too many capabilities")
        val seen = mutableSetOf<String>()
        for (i in 0 until capabilities.length()) {
            val cap = stringValue(capabilities.get(i), "capability")
            if (cap.length > 40 || !seen.add(cap)) fail("invalid capability")
        }
        if (integer(value, "max_request_bytes") !in 1024L..MAX_REQUEST_BYTES.toLong()) fail("invalid request limit")
        if (integer(value, "max_response_bytes") !in 1024L..MAX_RESPONSE_BYTES.toLong()) fail("invalid response limit")
    }

    private fun validateRequest(value: JSONObject, encodedSize: Int, protocol: String) {
        exactKeys(value, setOf("protocol", "kind", "request_id", "session_id", "operation", "idempotency_key", "created_at", "expires_at", "payload"))
        requireString(value, "kind", "request")
        requireUuid(value, "request_id")
        requireUuid(value, "session_id")
        requireOperation(value, protocol)
        val key = string(value, "idempotency_key")
        if (!idempotencyPattern.matches(key)) fail("invalid idempotency_key")
        val createdAt = parseTimestamp(value, "created_at")
        val expiresAt = parseTimestamp(value, "expires_at")
        val ttl = Duration.between(createdAt, expiresAt)
        if (ttl.isNegative || ttl.isZero || ttl > Duration.ofSeconds(MAX_REQUEST_TTL_SECONDS)) fail("invalid request expiry")
        val payload = objectValue(value, "payload")
        if (payload.length() > 40) fail("too many payload properties")
        if (encodedSize > MAX_REQUEST_BYTES) fail("request too large")
    }

    private fun validateResponse(value: JSONObject, encodedSize: Int, protocol: String) {
        exactKeys(value, setOf("protocol", "kind", "request_id", "session_id", "operation", "status", "result", "completed_at", "replayed"))
        requireString(value, "kind", "response")
        requireUuid(value, "request_id")
        requireUuid(value, "session_id")
        requireOperation(value, protocol)
        requireString(value, "status", "OK")
        objectValue(value, "result")
        parseTimestamp(value, "completed_at")
        boolean(value, "replayed")
        if (encodedSize > MAX_RESPONSE_BYTES) fail("response too large")
    }

    private fun validateError(value: JSONObject, protocol: String) {
        val baseKeys = setOf("protocol", "kind", "request_id", "session_id", "error", "completed_at")
        exactKeys(value, baseKeys)
        requireString(value, "kind", "error")
        nullableUuid(value, "request_id")
        nullableUuid(value, "session_id")
        val error = objectValue(value, "error")
        val optionalV2Keys = setOf("http_status", "remote_code")
        val allowedErrorKeys = setOf("code", "message", "retryable") +
            if (protocol == PROTOCOL_V2) optionalV2Keys else emptySet()
        val actualErrorKeys = error.keys().asSequence().toSet()
        if (!actualErrorKeys.containsAll(setOf("code", "message", "retryable")) ||
            actualErrorKeys.any { it !in allowedErrorKeys }
        ) {
            fail("unexpected or missing remote error field")
        }
        val code = string(error, "code")
        if (code !in ERROR_CODES && !(protocol == PROTOCOL_V2 && httpErrorCodePattern.matches(code))) {
            fail("unknown error code")
        }
        val message = string(error, "message")
        if (message.isBlank() || message.length > 120) fail("invalid error message")
        boolean(error, "retryable")
        if (error.has("http_status") && integer(error, "http_status") !in 400L..599L) {
            fail("invalid http_status")
        }
        if (error.has("remote_code") && !remoteCodePattern.matches(string(error, "remote_code"))) {
            fail("invalid remote_code")
        }
        parseTimestamp(value, "completed_at")
    }

    private fun validateCancel(value: JSONObject) {
        exactKeys(value, setOf("protocol", "kind", "request_id", "target_request_id"))
        requireString(value, "kind", "cancel")
        requireUuid(value, "request_id")
        requireUuid(value, "target_request_id")
    }

    private fun validatePingPong(value: JSONObject) {
        exactKeys(value, setOf("protocol", "kind", "nonce"))
        val nonce = string(value, "nonce")
        if (nonce.isEmpty() || nonce.length > 32) fail("invalid nonce")
    }

    private fun requireOperation(value: JSONObject, protocol: String) {
        if (!supportsOperation(protocol, string(value, "operation"))) fail("unknown operation")
    }

    private fun requireUuid(value: JSONObject, key: String) {
        if (!isUuid(string(value, key))) fail("invalid $key")
    }

    private fun nullableUuid(value: JSONObject, key: String) {
        val item = required(value, key)
        if (item !== JSONObject.NULL && (item !is String || !isUuid(item))) fail("invalid $key")
    }

    private fun parseTimestamp(value: JSONObject, key: String): Instant {
        val raw = string(value, key)
        return try { OffsetDateTime.parse(raw).toInstant() } catch (error: Exception) {
            throw RelayProtocolException("invalid $key", error)
        }
    }

    private fun exactKeys(value: JSONObject, expected: Set<String>) {
        val actual = value.keys().asSequence().toSet()
        if (actual != expected) fail("unexpected or missing envelope field")
    }

    private fun requireString(value: JSONObject, key: String, expected: String) {
        if (string(value, key) != expected) fail("invalid $key")
    }

    private fun string(value: JSONObject, key: String): String = stringValue(required(value, key), key)
    private fun stringValue(value: Any?, label: String): String = value as? String ?: fail("$label must be a string")
    private fun boolean(value: JSONObject, key: String): Boolean = required(value, key) as? Boolean ?: fail("$key must be boolean")
    private fun array(value: JSONObject, key: String): JSONArray = required(value, key) as? JSONArray ?: fail("$key must be array")
    private fun objectValue(value: JSONObject, key: String): JSONObject = required(value, key) as? JSONObject ?: fail("$key must be object")
    private fun required(value: JSONObject, key: String): Any = if (value.has(key)) value.get(key) else fail("missing $key")
    private fun integer(value: JSONObject, key: String): Long {
        val number = required(value, key) as? Number ?: fail("$key must be integer")
        val longValue = number.toLong()
        if (number.toDouble() != longValue.toDouble()) fail("$key must be integer")
        return longValue
    }

    private fun fail(message: String): Nothing = throw RelayProtocolException(message)
}

class RelayProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
