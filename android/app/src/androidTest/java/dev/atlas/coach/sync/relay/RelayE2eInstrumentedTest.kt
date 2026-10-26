package dev.atlas.coach.sync.relay

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.plan.PlanRevisionProposalV3
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Real emulator proof: Android Kotlin -> Paseo relay -> protected connector ->
 * FastAPI -> Android. It uses the emulator's 10.0.2.2 host alias and never
 * relies on `adb reverse`.
 *
 * Required instrumentation arguments: serverId, serverPublicKey, deviceId,
 * deviceToken. All values are synthetic and supplied by the parent.
 */
@RunWith(AndroidJUnit4::class)
class RelayE2eInstrumentedTest {
    private val arguments: Bundle = InstrumentationRegistry.getArguments()
    private val relayUrl = arguments.getString("relayUrl") ?: "ws://10.0.2.2:4400"
    private val serverId = requireArg("serverId")
    private val serverPublicKey = requireArg("serverPublicKey")
    private val deviceId = requireArg("deviceId")
    private val deviceToken = requireArg("deviceToken")

    @Test
    fun protectedRelayPlanRevisionRoundTrip() {
        val transport = transport(maxReconnectAttempts = 2)
        try {
            val connected = awaitState(transport, RelayConnectionState.RELAY_CONNECTED, 25_000)
            assertTrue("relay did not connect; last state=${transport.state()}", connected)

            val health = transport.execute("backend.health", JSONObject(), timeoutMillis = 20_000)
            assertTrue("health failed: $health", health is RelayCallResult.Success)
            assertEquals("ok", (health as RelayCallResult.Success).result.getString("status"))

            val idempotencyKey = UUID.randomUUID().toString()
            val payload = planRequest()
            val first = transport.execute(
                operation = "plan.revision.request",
                payload = payload,
                timeoutMillis = 30_000,
                idempotencyKey = idempotencyKey,
            )
            assertTrue("plan revision failed: $first", first is RelayCallResult.Success)
            val firstResult = (first as RelayCallResult.Success).result
            assertEquals("ok", firstResult.getString("agent_status"))
            val envelope = firstResult.getJSONObject("proposal")
            val parameters = envelope.getJSONObject("proposal").getJSONObject("parameters")
            assertEquals(PLAN_ID, parameters.getString("plan_id"))
            assertEquals(4, parameters.getInt("base_version"))
            val parsed = PlanRevisionProposalV3.parse(firstResult.toString())
            assertEquals("ok", parsed.agentStatus)
            assertNotEquals("", parsed.proposal?.proposalId)

            val second = transport.execute(
                operation = "plan.revision.request",
                payload = payload,
                timeoutMillis = 30_000,
                idempotencyKey = idempotencyKey,
            )
            assertTrue("idempotent replay failed: $second", second is RelayCallResult.Success)
            val replay = second as RelayCallResult.Success
            assertTrue("second request was not marked replayed", replay.replayed)
            assertEquals(firstResult.toString(), replay.result.toString())
        } finally {
            transport.close()
        }
    }

    @Test
    fun relayUnavailableIsReportedAsOffline() {
        val transport = transport(maxReconnectAttempts = 2)
        try {
            val offline = awaitState(transport, RelayConnectionState.OFFLINE, 12_000) ||
                transport.state() == RelayConnectionState.ERROR
            assertNotEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
            assertTrue("expected OFFLINE/ERROR, got ${transport.state()}", offline)
            val attempt = transport.execute("backend.health", JSONObject(), timeoutMillis = 2_000)
            assertTrue("offline request unexpectedly succeeded: $attempt", attempt !is RelayCallResult.Success)
        } finally {
            transport.close()
        }
    }

    private fun transport(maxReconnectAttempts: Int): RelayBackendTransport {
        val credentials = RelayCredentials(
            deviceId = deviceId,
            deviceToken = deviceToken,
            remoteStaticPublicKey = Base64.getDecoder().decode(serverPublicKey),
        )
        return RelayBackendTransport(
            config = RelayConfig(
                relayUrl = relayUrl,
                serverId = serverId,
                connectTimeoutMillis = 5_000,
                reconnectBaseMillis = 200,
                reconnectMaxMillis = 1_000,
                maxReconnectAttempts = maxReconnectAttempts,
            ),
            credentialStore = InMemoryRelayCredentialStore(credentials),
        ).also { it.start() }
    }

    private fun awaitState(
        transport: RelayBackendTransport,
        expected: RelayConnectionState,
        timeoutMillis: Long,
    ): Boolean {
        if (transport.state() == expected) return true
        val latch = CountDownLatch(1)
        val listener: (RelayConnectionState) -> Unit = { state ->
            if (state == expected) latch.countDown()
        }
        transport.addStateListener(listener)
        return try {
            if (transport.state() == expected) true
            else latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } finally {
            transport.removeStateListener(listener)
        }
    }

    private fun planRequest(): JSONObject = JSONObject()
        .put("schema_version", 3)
        .put("request_id", UUID.randomUUID().toString())
        .put("conversation_id", "conversation-r3a-android-e2e")
        .put("message", "Propose un décalage du bloc de révision.")
        .put("plan_context_consented", true)
        .put("mandate_summary", JSONObject()
            .put("id", "mandate-r3a")
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
