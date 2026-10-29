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

/** Strict codec for the frozen atlas.remote.v1 envelope schema. */
object AtlasRemoteProtocol {
    const val PROTOCOL = "atlas.remote.v1"
    const val MAX_APPLICATION_FRAME_BYTES = 65_536
    const val MAX_REQUEST_BYTES = 32_768
    const val MAX_RESPONSE_BYTES = 262_144
    const val MAX_REQUEST_TTL_SECONDS = 60L

    val OPERATIONS = setOf(
        "backend.health", "coach.status", "plan.today.read", "plan.revision.request",
        "plan.revision.result", "sport.plan.read", "sync.status", "coach.chat.request",
        "v4.coach.chat.request", "v4.proposals.pull",
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
    )
    private val uuidPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val tokenPattern = Regex("^[A-Za-z0-9_-]{43}$")
    private val idempotencyPattern = Regex("^[A-Za-z0-9._:-]{1,160}$")
    private val noncePattern = Regex("^[A-Za-z0-9+/]{22}==$")

    fun parse(bytes: ByteArray): JSONObject {
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
        validateMeasured(envelope, bytes.size)
        return envelope
    }

    fun encode(envelope: JSONObject): ByteArray {
        val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
        validateMeasured(envelope, bytes.size)
        return bytes
    }

    /** Used by schema-focused tests and builders; size is measured as UTF-8 JSON bytes. */
    fun validate(envelope: JSONObject) {
        validateMeasured(envelope, envelope.toString().toByteArray(Charsets.UTF_8).size)
    }

    private fun validateMeasured(envelope: JSONObject, encodedSize: Int) {
        if (encodedSize > MAX_APPLICATION_FRAME_BYTES) fail("application frame too large")
        if (encodedSize < 2) fail("empty envelope")
        requireString(envelope, "protocol", PROTOCOL)
        when (val kind = string(envelope, "kind")) {
            "hello" -> validateHello(envelope)
            "hello_ok" -> validateHelloOk(envelope)
            "request" -> validateRequest(envelope, encodedSize)
            "response" -> validateResponse(envelope, encodedSize)
            "error" -> validateError(envelope)
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
    ): JSONObject {
        require(clientVersion.isNotBlank() && clientVersion.length <= 40)
        require(requestedCapabilities.isNotEmpty() && requestedCapabilities.size <= 8)
        require(requestedCapabilities.all { it in HELLO_CAPABILITIES })
        val nonce = ByteArray(16).also(java.security.SecureRandom()::nextBytes)
        val envelope = JSONObject()
            .put("protocol", PROTOCOL)
            .put("kind", "hello")
            .put("device_id", credentials.deviceId)
            .put("client_nonce", Base64.getEncoder().encodeToString(nonce))
            .put("client_version", clientVersion)
            .put("capabilities", JSONArray(requestedCapabilities.sorted()))
            .put("auth", JSONObject().put("scheme", "device-token-v1").put("token", credentials.deviceToken))
            .put("timestamp", now.toString())
        validate(envelope)
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
    ): JSONObject {
        val envelope = JSONObject()
            .put("protocol", PROTOCOL).put("kind", "request")
            .put("request_id", requestId).put("session_id", sessionId)
            .put("operation", operation).put("idempotency_key", idempotencyKey)
            .put("created_at", createdAt.toString()).put("expires_at", expiresAt.toString())
            .put("payload", payload)
        validate(envelope)
        return envelope
    }

    fun newUuid(): String = UUID.randomUUID().toString()

    fun isUuid(value: String): Boolean = uuidPattern.matches(value)

    fun requiredCapability(operation: String): String? = when (operation) {
        "plan.revision.request", "plan.revision.result" -> "plan.revision"
        "coach.chat.request" -> "coach.chat"
        "v4.coach.chat.request" -> "v4.coach.chat"
        "v4.proposals.pull" -> "v4.proposal.ack"
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

    private fun validateHello(value: JSONObject) {
        exactKeys(value, setOf("protocol", "kind", "device_id", "client_nonce", "client_version", "capabilities", "auth", "timestamp"))
        requireString(value, "kind", "hello")
        requireUuid(value, "device_id")
        val nonce = string(value, "client_nonce")
        if (!noncePattern.matches(nonce) || decodeCanonicalBase64(nonce).size != 16) fail("invalid client_nonce")
        val version = string(value, "client_version")
        if (version.isBlank() || version.length > 40) fail("invalid client_version")
        val capabilities = array(value, "capabilities")
        if (capabilities.length() > 8) fail("too many capabilities")
        val seen = mutableSetOf<String>()
        for (i in 0 until capabilities.length()) {
            val cap = stringValue(capabilities.get(i), "capability")
            if (cap !in HELLO_CAPABILITIES || !seen.add(cap)) fail("invalid capability")
        }
        val auth = objectValue(value, "auth")
        exactKeys(auth, setOf("scheme", "token"))
        requireString(auth, "scheme", "device-token-v1")
        if (!isDeviceToken(string(auth, "token"))) fail("invalid device token")
        parseTimestamp(value, "timestamp")
    }

    private fun validateHelloOk(value: JSONObject) {
        exactKeys(value, setOf("protocol", "kind", "session_id", "server_time", "expires_in_seconds", "capabilities", "max_request_bytes", "max_response_bytes"))
        requireString(value, "kind", "hello_ok")
        requireUuid(value, "session_id")
        parseTimestamp(value, "server_time")
        val ttl = integer(value, "expires_in_seconds")
        if (ttl !in 60L..3600L) fail("invalid session expiry")
        val capabilities = array(value, "capabilities")
        if (capabilities.length() > 8) fail("too many capabilities")
        val seen = mutableSetOf<String>()
        for (i in 0 until capabilities.length()) {
            val cap = stringValue(capabilities.get(i), "capability")
            if (cap.length > 40 || !seen.add(cap)) fail("invalid capability")
        }
        if (integer(value, "max_request_bytes") !in 1024L..MAX_REQUEST_BYTES.toLong()) fail("invalid request limit")
        if (integer(value, "max_response_bytes") !in 1024L..MAX_RESPONSE_BYTES.toLong()) fail("invalid response limit")
    }

    private fun validateRequest(value: JSONObject, encodedSize: Int) {
        exactKeys(value, setOf("protocol", "kind", "request_id", "session_id", "operation", "idempotency_key", "created_at", "expires_at", "payload"))
        requireString(value, "kind", "request")
        requireUuid(value, "request_id")
        requireUuid(value, "session_id")
        requireOperation(value)
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

    private fun validateResponse(value: JSONObject, encodedSize: Int) {
        exactKeys(value, setOf("protocol", "kind", "request_id", "session_id", "operation", "status", "result", "completed_at", "replayed"))
        requireString(value, "kind", "response")
        requireUuid(value, "request_id")
        requireUuid(value, "session_id")
        requireOperation(value)
        requireString(value, "status", "OK")
        objectValue(value, "result")
        parseTimestamp(value, "completed_at")
        boolean(value, "replayed")
        if (encodedSize > MAX_RESPONSE_BYTES) fail("response too large")
    }

    private fun validateError(value: JSONObject) {
        exactKeys(value, setOf("protocol", "kind", "request_id", "session_id", "error", "completed_at"))
        requireString(value, "kind", "error")
        nullableUuid(value, "request_id")
        nullableUuid(value, "session_id")
        val error = objectValue(value, "error")
        exactKeys(error, setOf("code", "message", "retryable"))
        val code = string(error, "code")
        if (code !in ERROR_CODES) fail("unknown error code")
        val message = string(error, "message")
        if (message.isBlank() || message.length > 120) fail("invalid error message")
        boolean(error, "retryable")
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

    private fun requireOperation(value: JSONObject) {
        if (string(value, "operation") !in OPERATIONS) fail("unknown operation")
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
