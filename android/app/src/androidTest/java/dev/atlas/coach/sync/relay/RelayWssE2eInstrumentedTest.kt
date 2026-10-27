package dev.atlas.coach.sync.relay

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Real Pixel proof for the WSS predeployment path:
 * Android -> TLS test proxy -> real Paseo Relay -> Noise protected -> PC connector
 * -> loopback FastAPI.
 *
 * The test CA is trusted only by the debug network-security config. DNS for the
 * .atlas.test names is injected through RelayDebugDns; no system DNS or hosts
 * file is modified. The parent supplies synthetic credentials as
 * instrumentation arguments and never prints the token.
 */
@RunWith(AndroidJUnit4::class)
class RelayWssE2eInstrumentedTest {
    private val arguments: Bundle = InstrumentationRegistry.getArguments()
    private val relayUrl = requireArg("relayUrl")
    private val serverId = requireArg("serverId")
    private val serverPublicKey = requireArg("serverPublicKey")
    private val deviceId = requireArg("deviceId")
    private val deviceToken = requireArg("deviceToken")
    private val wrongHostPort = requireArg("wrongHostPort").toInt()
    private val untrustedPort = requireArg("untrustedPort").toInt()

    @Test
    fun wssProtectedRoundTripReachesFastApi() {
        val transport = transport(relayUrl, maxReconnectAttempts = 3)
        try {
            assertTrue(
                "relay did not connect to the WSS test relay",
                awaitState(transport, RelayConnectionState.RELAY_CONNECTED, 25_000),
            )
            val health = transport.execute("backend.health", JSONObject(), timeoutMillis = 20_000)
            assertTrue("WSS health failed: $health", health is RelayCallResult.Success)
            assertEquals("ok", (health as RelayCallResult.Success).result.getString("status"))

            val first = transport.execute(
                operation = "plan.revision.request",
                payload = planRequest(),
                timeoutMillis = 30_000,
                idempotencyKey = UUID.randomUUID().toString(),
            )
            assertTrue("WSS plan revision failed: $first", first is RelayCallResult.Success)
            val result = (first as RelayCallResult.Success).result
            assertEquals("ok", result.getString("agent_status"))
            assertNotNull(result.getJSONObject("proposal"))
        } finally {
            transport.close()
        }
    }

    @Test
    fun wssWrongHostnameIsRefused() {
        val transport = transport("wss://relay.atlas.test:$wrongHostPort", maxReconnectAttempts = 1)
        try {
            assertFalse(
                "wrong-host certificate unexpectedly reached a protected session",
                awaitState(transport, RelayConnectionState.RELAY_CONNECTED, 8_000),
            )
            assertNotEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
        } finally {
            transport.close()
        }
    }

    @Test
    fun wssUntrustedCertificateIsRefused() {
        val transport = transport("wss://relay.atlas.test:$untrustedPort", maxReconnectAttempts = 1)
        try {
            assertFalse(
                "untrusted certificate unexpectedly reached a protected session",
                awaitState(transport, RelayConnectionState.RELAY_CONNECTED, 8_000),
            )
            assertNotEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
        } finally {
            transport.close()
        }
    }

    @Test
    fun badPinnedNoiseIdentityIsRefused() {
        val wrongKey = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val transport = transport(relayUrl, maxReconnectAttempts = 1, pinnedKeyOverride = wrongKey)
        try {
            assertFalse(
                "a wrong pinned Noise identity unexpectedly reached a protected session",
                awaitState(transport, RelayConnectionState.RELAY_CONNECTED, 10_000),
            )
            assertNotEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
        } finally {
            transport.close()
        }
    }

    @Test
    fun wsRemoteIsRejectedByConfigurationPolicy() {
        assertNotNull(
            "a remote ws:// relay must be rejected",
            RelayConfigurationValidator.relayUrlError("ws://relay.example.org", debugBuild = true),
        )
        assertEquals(
            null,
            RelayConfigurationValidator.relayUrlError("wss://relay.example.org", debugBuild = true),
        )
        assertNotNull(
            "a remote ws:// relay must also be rejected in release",
            RelayConfigurationValidator.relayUrlError("ws://relay.example.org", debugBuild = false),
        )
    }

    @Test
    fun fastApiIsNotReachableThroughTheTlsProxy() {
        val proxyPort = requireArg("proxyPort").toInt()
        val response = plainHttpProbe("127.0.0.1", proxyPort)
        assertFalse("the TLS proxy returned a FastAPI health payload: $response", response.contains("\"status\":\"ok\""))
        assertFalse("the TLS proxy returned a FastAPI route payload: $response", response.contains("/v1/plan/today"))
    }

    /**
     * Phase used by the parent to interrupt and restore the TLS proxy. The
     * parent watches the marker file, stops the proxy, then restarts it.
     */
    @Test
    fun tlsInterruptionDisconnectsAndReconnects() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val marker = File(context.cacheDir, "wss-connected.marker")
        marker.delete()
        val transport = transport(relayUrl, maxReconnectAttempts = 8)
        try {
            assertTrue(
                "relay did not connect before TLS interruption",
                awaitState(transport, RelayConnectionState.RELAY_CONNECTED, 25_000),
            )
            marker.writeText("connected\n")
            assertTrue(
                "the client stayed connected after the TLS proxy was interrupted",
                awaitStateNot(transport, RelayConnectionState.RELAY_CONNECTED, 20_000),
            )
            assertTrue(
                "the client did not reconnect after the TLS proxy returned",
                awaitState(transport, RelayConnectionState.RELAY_CONNECTED, 30_000),
            )
            val health = transport.execute("backend.health", JSONObject(), timeoutMillis = 20_000)
            assertTrue("health after TLS reconnect failed: $health", health is RelayCallResult.Success)
        } finally {
            marker.delete()
            transport.close()
        }
    }

    private fun transport(
        url: String,
        maxReconnectAttempts: Int,
        pinnedKeyOverride: String? = null,
    ): RelayBackendTransport {
        val credentials = RelayCredentials(
            deviceId = deviceId,
            deviceToken = deviceToken,
            remoteStaticPublicKey = Base64.getDecoder().decode(pinnedKeyOverride ?: serverPublicKey),
        )
        val config = RelayConfig(
            relayUrl = url,
            serverId = serverId,
            connectTimeoutMillis = 6_000,
            reconnectBaseMillis = 200,
            reconnectMaxMillis = 1_000,
            maxReconnectAttempts = maxReconnectAttempts,
        )
        return RelayBackendTransport(
            config = config,
            credentialStore = InMemoryRelayCredentialStore(credentials),
            socketFactory = OkHttpRelaySocketFactory(config, RelayDebugDns),
        ).also { it.start() }
    }

    private fun awaitState(
        transport: RelayBackendTransport,
        expected: RelayConnectionState,
        timeoutMillis: Long,
    ): Boolean {
        if (transport.state() == expected) return true
        val latch = CountDownLatch(1)
        val listener: (RelayConnectionState) -> Unit = { if (it == expected) latch.countDown() }
        transport.addStateListener(listener)
        return try {
            if (transport.state() == expected) true else latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } finally {
            transport.removeStateListener(listener)
        }
    }

    private fun awaitStateNot(
        transport: RelayBackendTransport,
        unexpected: RelayConnectionState,
        timeoutMillis: Long,
    ): Boolean {
        if (transport.state() != unexpected) return true
        val latch = CountDownLatch(1)
        val listener: (RelayConnectionState) -> Unit = { if (it != unexpected) latch.countDown() }
        transport.addStateListener(listener)
        return try {
            if (transport.state() != unexpected) true else latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } finally {
            transport.removeStateListener(listener)
        }
    }

    private fun plainHttpProbe(host: String, port: Int): String {
        val address = InetAddress.getByName(host)
        return runCatching {
            Socket(address, port).use { socket ->
                socket.soTimeout = 2_000
                socket.getOutputStream().write(
                    "GET /health HTTP/1.1\r\nHost: relay.atlas.test:$port\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII),
                )
                socket.getOutputStream().flush()
                socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            }
        }.getOrDefault("")
    }

    private fun planRequest(): JSONObject = JSONObject()
        .put("schema_version", 3)
        .put("request_id", UUID.randomUUID().toString())
        .put("conversation_id", "conversation-r3ap-wss-pixel")
        .put("message", "Propose un petit décalage du bloc de révision.")
        .put("plan_context_consented", true)
        .put("mandate_summary", JSONObject()
            .put("id", "mandate-r3ap")
            .put("version", 2)
            .put("allowed_actions", org.json.JSONArray().put("focus.propose")))
        .put("plan", JSONObject()
            .put("plan_id", PLAN_ID)
            .put("local_date", "2026-10-27")
            .put("timezone", "Europe/Paris")
            .put("version", 4)
            .put("state", "DRAFT")
            .put("activities", org.json.JSONArray().put(JSONObject()
                .put("activity_id", ACTIVITY_ID)
                .put("kind", "FOCUS")
                .put("title", "Réviser les notes")
                .put("start_minute", 480)
                .put("duration_minutes", 50)
                .put("priority", "IMPORTANT")
                .put("goal_id", JSONObject.NULL))))

    private fun requireArg(name: String): String =
        arguments.getString(name)?.takeIf { it.isNotBlank() }
            ?: error("missing instrumentation argument: $name")

    private companion object {
        const val PLAN_ID = "11111111-1111-4111-8111-111111111111"
        const val ACTIVITY_ID = "22222222-2222-4222-8222-222222222222"
    }
}
