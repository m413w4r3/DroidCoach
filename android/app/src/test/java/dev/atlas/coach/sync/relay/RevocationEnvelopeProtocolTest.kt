package dev.atlas.coach.sync.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RevocationEnvelopeProtocolTest {
    @Test
    fun v1RevocationEnvelopeIsRejectedByV2TransportWithoutDowngrade() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(
            staticPair,
            serverCapabilities = setOf("v4.coach.chat"),
        )
        val transport = v2Transport(credentials, sockets)
        val observedFailure = AtomicReference<RelayFailure?>()
        transport.addFailureListener(observedFailure::set)

        try {
            transport.start()
            assertEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
            assertEquals(AtlasRemoteProtocol.PROTOCOL_V2, sockets.appHellos.single().getString("protocol"))

            val socket = sockets.sockets.single()
            val protocolField = socket.javaClass.getDeclaredField("applicationProtocol")
            protocolField.isAccessible = true
            protocolField.set(socket, AtlasRemoteProtocol.PROTOCOL)
            socket.sendErrorEnvelope("DEVICE_REVOKED", "device revoked")

            assertEquals(RelayConnectionState.ERROR, transport.state())
            assertNull("a V1 envelope must not be parsed as a revocation", observedFailure.get())
        } finally {
            transport.close()
        }
    }

    @Test
    fun v2RevocationEnvelopeMapsToRevokedStateAndFailureKind() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(
            staticPair,
            serverCapabilities = setOf("v4.coach.chat"),
        )
        val transport = v2Transport(credentials, sockets)
        val observedFailure = AtomicReference<RelayFailure?>()
        val revocationReported = CountDownLatch(1)
        transport.addFailureListener { failure ->
            observedFailure.set(failure)
            if (failure.kind == RelayFailureKind.REVOKED) revocationReported.countDown()
        }

        try {
            transport.start()
            assertEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
            sockets.sockets.single().sendErrorEnvelope("DEVICE_REVOKED", "device revoked")

            assertTrue("V2 DEVICE_REVOKED failure was not reported", revocationReported.await(1, TimeUnit.SECONDS))
            assertEquals(RelayConnectionState.REVOKED, transport.state())
            assertEquals(RelayFailureKind.REVOKED, observedFailure.get()?.kind)
        } finally {
            transport.close()
        }
    }

    private fun v2Transport(
        credentials: RelayCredentials,
        sockets: FakeRelaySocketFactory,
    ) = RelayBackendTransport(
        config = RelayConfig(
            relayUrl = "ws://127.0.0.1:4400",
            serverId = "atlas-test",
            maxReconnectAttempts = 0,
            requestedCapabilities = setOf("v4.coach.chat"),
        ),
        credentialStore = InMemoryRelayCredentialStore(credentials),
        socketFactory = sockets,
    )
}
