package dev.atlas.coach.sync.relay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AtlasRemoteProtocolTest {
    @Test
    fun encodesAndParsesHelloAndRequestEnvelopes() {
        val (credentials, _) = testCredentials()
        val hello = AtlasRemoteProtocol.hello(credentials, "0.1.0-r3a", Instant.parse("2026-10-26T12:00:00Z"))
        val parsedHello = AtlasRemoteProtocol.parse(AtlasRemoteProtocol.encode(hello))
        assertEquals("hello", parsedHello.getString("kind"))
        assertEquals("device-token-v1", parsedHello.getJSONObject("auth").getString("scheme"))
        assertEquals(16, AtlasRemoteProtocol.decodeCanonicalBase64(parsedHello.getString("client_nonce")).size)

        val now = Instant.parse("2026-10-26T12:00:00Z")
        val request = AtlasRemoteProtocol.request(
            requestId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            sessionId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            operation = "plan.revision.request",
            idempotencyKey = "mobile:plan-revision:1",
            createdAt = now,
            expiresAt = now.plusSeconds(60),
            payload = JSONObject().put("message", "revise my day"),
        )
        assertEquals(request.toString(), AtlasRemoteProtocol.parse(AtlasRemoteProtocol.encode(request)).toString())
    }

    @Test
    fun helloCanRequestChatAlongsidePlanAndOldOverloadRemainsPlanOnly() {
        val (credentials, _) = testCredentials()
        val now = Instant.parse("2026-10-26T12:00:00Z")
        val oldHello = AtlasRemoteProtocol.hello(credentials, "0.1.0-r3a", now)
        assertEquals(listOf("plan.revision"), (0 until oldHello.getJSONArray("capabilities").length())
            .map { oldHello.getJSONArray("capabilities").getString(it) })

        val chatHello = AtlasRemoteProtocol.hello(credentials, "0.1.0-r3b", setOf("plan.revision", "coach.chat"), now)
        assertEquals(setOf("plan.revision", "coach.chat"), (0 until chatHello.getJSONArray("capabilities").length())
            .map { chatHello.getJSONArray("capabilities").getString(it) }.toSet())
        val request = AtlasRemoteProtocol.request(
            requestId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            sessionId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            operation = "coach.chat.request",
            idempotencyKey = "chat-send-001",
            createdAt = now,
            expiresAt = now.plusSeconds(30),
            payload = JSONObject().put("message", "synthetic"),
        )
        assertEquals("coach.chat.request", AtlasRemoteProtocol.parse(AtlasRemoteProtocol.encode(request)).getString("operation"))
    }

    @Test
    fun rejectsUnknownFieldsInvalidTypesAndClosedEnums() {
        val valid = JSONObject()
            .put("protocol", AtlasRemoteProtocol.PROTOCOL).put("kind", "ping").put("nonce", "n1")
        assertEquals("ping", AtlasRemoteProtocol.parse(AtlasRemoteProtocol.encode(valid)).getString("kind"))
        assertThrows(RelayProtocolException::class.java) {
            AtlasRemoteProtocol.validate(JSONObject(valid.toString()).put("extra", true))
        }
        assertThrows(RelayProtocolException::class.java) {
            AtlasRemoteProtocol.validate(JSONObject().put("protocol", AtlasRemoteProtocol.PROTOCOL).put("kind", "ping").put("nonce", 1))
        }
        val invalidError = JSONObject()
            .put("protocol", AtlasRemoteProtocol.PROTOCOL).put("kind", "error")
            .put("request_id", JSONObject.NULL).put("session_id", JSONObject.NULL)
            .put("error", JSONObject().put("code", "SHELL_COMMAND").put("message", "invalid").put("retryable", false))
            .put("completed_at", "2026-10-26T12:00:00Z")
        assertThrows(RelayProtocolException::class.java) { AtlasRemoteProtocol.validate(invalidError) }
    }

    @Test
    fun enforcesRequestExpiryAndRequestByteLimit() {
        val now = Instant.parse("2026-10-26T12:00:00Z")
        fun request(expires: Instant, payload: JSONObject = JSONObject()) = AtlasRemoteProtocol.request(
            requestId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            sessionId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            operation = "plan.revision.request",
            idempotencyKey = "same-key",
            createdAt = now,
            expiresAt = expires,
            payload = payload,
        )
        assertThrows(RelayProtocolException::class.java) { request(now.plusSeconds(61)) }
        val tooLarge = JSONObject().put("body", "x".repeat(33_000))
        assertThrows(RelayProtocolException::class.java) { request(now.plusSeconds(1), tooLarge) }
        assertEquals(32_768, AtlasRemoteProtocol.MAX_REQUEST_BYTES)
        assertEquals(262_144, AtlasRemoteProtocol.MAX_RESPONSE_BYTES)
    }

    @Test
    fun rejectsOversizedChatPayloadAndUnsupportedProtocolVersion() {
        val now = Instant.parse("2026-10-26T12:00:00Z")
        assertThrows(RelayProtocolException::class.java) {
            AtlasRemoteProtocol.request(
                requestId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                sessionId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                operation = "coach.chat.request",
                idempotencyKey = "chat-large-1",
                createdAt = now,
                expiresAt = now.plusSeconds(30),
                payload = JSONObject().put("message", "x".repeat(33_000)),
            )
        }

        val wrongVersion = JSONObject()
            .put("protocol", "atlas.remote.v2")
            .put("kind", "ping")
            .put("nonce", "synthetic")
        assertThrows(RelayProtocolException::class.java) { AtlasRemoteProtocol.parse(wrongVersion.toString().toByteArray()) }
    }

    @Test
    fun enforcesUtf8ApplicationFrameBoundAndCanonicalDeviceToken() {
        assertTrue(AtlasRemoteProtocol.isDeviceToken("A".repeat(43)))
        assertTrue(!AtlasRemoteProtocol.isDeviceToken("A".repeat(42)))
        assertThrows(RelayProtocolException::class.java) {
            AtlasRemoteProtocol.parse(ByteArray(AtlasRemoteProtocol.MAX_APPLICATION_FRAME_BYTES + 1))
        }
        assertThrows(RelayProtocolException::class.java) { AtlasRemoteProtocol.decodeCanonicalBase64("AA=A") }
        assertThrows(RelayProtocolException::class.java) { AtlasRemoteProtocol.parse(byteArrayOf(0xc3.toByte(), 0x28)) }
    }

    @Test
    fun validatesNegotiatedLimitsAndSessionCapabilities() {
        val helloOk = JSONObject()
            .put("protocol", AtlasRemoteProtocol.PROTOCOL).put("kind", "hello_ok")
            .put("session_id", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
            .put("server_time", "2026-10-26T12:00:00Z").put("expires_in_seconds", 3600)
            .put("capabilities", JSONArray().put("plan.revision").put("coach.status"))
            .put("max_request_bytes", 32768).put("max_response_bytes", 262144)
        AtlasRemoteProtocol.validate(helloOk)
        assertThrows(RelayProtocolException::class.java) {
            AtlasRemoteProtocol.validate(JSONObject(helloOk.toString()).put("max_request_bytes", 65_536))
        }
    }
}
