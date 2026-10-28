package dev.atlas.coach.sync.relay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayCapabilityNegotiationTest {
    @Test
    fun olderPairingKeepsPlanRevisionAndRejectsChatWithoutClosingSession() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(staticPair, serverCapabilities = setOf("plan.revision"))
        val transport = transport(credentials, sockets, setOf("plan.revision", "coach.chat"))
        try {
            transport.start()

            assertEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
            assertNull(sockets.sockets.single().closedCode)

            val plan = transport.execute("plan.revision.request", JSONObject())
            assertTrue("plan.revision should remain available", plan is RelayCallResult.Success)

            val chat = transport.execute("coach.chat.request", JSONObject().put("message", "synthetic"))
            assertEquals("OPERATION_UNAVAILABLE", (chat as RelayCallResult.RemoteError).code)
            assertTrue(chat.message.contains("Réappairez"))
            assertEquals(listOf("plan.revision.request"), sockets.requests.map { it.getString("operation") })
            assertNull(sockets.sockets.single().closedCode)
            assertEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
        } finally {
            transport.close()
        }
    }

    @Test
    fun emptyServerCapabilitiesKeepSessionConnectedAndRejectCapabilityOperations() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(staticPair, serverCapabilities = emptySet())
        val transport = transport(credentials, sockets, setOf("plan.revision", "coach.chat"))
        try {
            transport.start()

            assertEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
            assertNull(sockets.sockets.single().closedCode)
            assertUnavailable(transport.execute("plan.revision.request", JSONObject()))
            assertUnavailable(transport.execute("coach.chat.request", JSONObject().put("message", "synthetic")))
            assertTrue(sockets.requests.isEmpty())
            assertNull(sockets.sockets.single().closedCode)
            assertEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
        } finally {
            transport.close()
        }
    }

    @Test
    fun serverCapabilityNotRequestedNeverExpandsClientAuthority() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(staticPair, serverCapabilities = setOf("plan.revision", "coach.chat"))
        val transport = transport(credentials, sockets, setOf("plan.revision"))
        try {
            transport.start()

            assertEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
            assertUnavailable(transport.execute("coach.chat.request", JSONObject().put("message", "synthetic")))
            assertTrue(sockets.requests.isEmpty())
            assertNull(sockets.sockets.single().closedCode)
            assertEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
        } finally {
            transport.close()
        }
    }

    @Test
    fun clientRequestingOnlyPlanRevisionRemainsCompatible() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(staticPair, serverCapabilities = setOf("plan.revision"))
        val transport = transport(credentials, sockets, setOf("plan.revision"))
        try {
            transport.start()

            assertEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
            assertTrue(transport.execute("plan.revision.request", JSONObject()) is RelayCallResult.Success)
            assertEquals(1, sockets.requests.size)
            assertNull(sockets.sockets.single().closedCode)
        } finally {
            transport.close()
        }
    }

    private fun assertUnavailable(result: RelayCallResult) {
        assertTrue(result is RelayCallResult.RemoteError)
        assertEquals("OPERATION_UNAVAILABLE", (result as RelayCallResult.RemoteError).code)
    }

    private fun transport(
        credentials: RelayCredentials,
        sockets: FakeRelaySocketFactory,
        requestedCapabilities: Set<String>,
    ) = RelayBackendTransport(
        config = RelayConfig(
            relayUrl = "ws://127.0.0.1:4400",
            serverId = "atlas-test",
            maxReconnectAttempts = 0,
            requestedCapabilities = requestedCapabilities,
        ),
        credentialStore = InMemoryRelayCredentialStore(credentials),
        socketFactory = sockets,
    )
}
