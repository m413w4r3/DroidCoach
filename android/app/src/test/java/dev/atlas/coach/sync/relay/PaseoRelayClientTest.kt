package dev.atlas.coach.sync.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PaseoRelayClientTest {
    @Test
    fun rejectsRemoteCleartextBeforeOpeningASocket() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(staticPair)
        val error = runCatching {
            PaseoRelayClient(
                config = RelayConfig("ws://relay.example:4400", "atlas-test"),
                credentialsProvider = InMemoryRelayCredentialStore(credentials),
                listener = object : PaseoRelayClient.Listener {},
                socketFactory = sockets,
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(0, sockets.connectCount.get())
    }

    @Test
    fun rejectsNoiseResponderThatDoesNotMatchThePinnedStaticKey() {
        val (credentials, pinnedPair) = testCredentials()
        val wrongResponder = NoiseKeyPair.generate()
        val sockets = FakeRelaySocketFactory(wrongResponder)
        val failures = CountDownLatch(1)
        val client = PaseoRelayClient(
            config = RelayConfig("ws://127.0.0.1:4400", "atlas-test", maxReconnectAttempts = 0),
            credentialsProvider = InMemoryRelayCredentialStore(credentials),
            listener = object : PaseoRelayClient.Listener {
                override fun onProtocolFailure(error: Throwable) { failures.countDown() }
            },
            socketFactory = sockets,
        )
        try {
            client.start()
            assertTrue(failures.await(1, TimeUnit.SECONDS))
            assertEquals(4002, sockets.sockets.single().closedCode)
            assertFalse(client.state() == RelayConnectionState.RELAY_CONNECTED)
        } finally {
            client.stop()
            pinnedPair.privateKey.fill(0)
            wrongResponder.privateKey.fill(0)
        }
    }

    @Test
    fun debugDnsMapsTheTestRelayHostnameToLoopbackWithoutSystemDnsChanges() {
        val address = RelayDebugDns.lookup("relay.atlas.test").single()
        assertTrue(address.isLoopbackAddress)
        assertEquals("127.0.0.1", address.hostAddress)
    }

    @Test
    fun constructsExactPaseoV2ClientUrl() {
        val config = RelayConfig("ws://127.0.0.1:4400", "atlas-test")
        assertEquals("ws://127.0.0.1:4400/ws?serverId=atlas-test&role=client&v=2", config.webSocketUrl())
    }

    @Test
    fun retriesIdenticalE2eeHelloEverySecondUntilReady() {
        val (credentials, staticPair) = testCredentials()
        val store = InMemoryRelayCredentialStore(credentials)
        val sockets = FakeRelaySocketFactory(staticPair, handshakeMode = TestHandshakeMode.HOLD_READY)
        val client = PaseoRelayClient(
            config = RelayConfig("ws://127.0.0.1:4400", "atlas-test", maxReconnectAttempts = 0),
            credentialsProvider = store,
            listener = object : PaseoRelayClient.Listener {},
            socketFactory = sockets,
        )
        try {
            client.start()
            val socket = sockets.sockets.single()
            assertTrue(socket.helloSends.await(2_500, TimeUnit.MILLISECONDS))
            val hellos = socket.sentText.filter { it.contains("e2ee_hello") }
            assertTrue("expected retry", hellos.size >= 2)
            assertTrue(hellos.all { it == hellos.first() })
        } finally {
            client.stop()
        }
    }

    @Test
    fun refusesLegacyReadyAndClosesTheSocket() {
        val (credentials, staticPair) = testCredentials()
        val socketFactory = FakeRelaySocketFactory(staticPair, handshakeMode = TestHandshakeMode.LEGACY_READY)
        val failures = CountDownLatch(1)
        val client = PaseoRelayClient(
            config = RelayConfig("ws://127.0.0.1:4400", "atlas-test", maxReconnectAttempts = 0),
            credentialsProvider = InMemoryRelayCredentialStore(credentials),
            listener = object : PaseoRelayClient.Listener {
                override fun onProtocolFailure(error: Throwable) { failures.countDown() }
            },
            socketFactory = socketFactory,
        )
        client.start()
        assertTrue(failures.await(1, TimeUnit.SECONDS))
        assertEquals(4002, socketFactory.sockets.single().closedCode)
        assertFalse(client.state() == RelayConnectionState.RELAY_CONNECTED)
        client.stop()
    }

    @Test
    fun reconnectsWithinTheConfiguredBoundAfterDisconnect() {
        val (credentials, staticPair) = testCredentials()
        val secondConnection = CountDownLatch(1)
        val sockets = FakeRelaySocketFactory(
            staticPair,
            handshakeMode = TestHandshakeMode.HOLD_READY,
            onConnected = { socket, index -> if (index == 1) socket.remoteClose() else secondConnection.countDown() },
        )
        val client = PaseoRelayClient(
            config = RelayConfig(
                "ws://127.0.0.1:4400", "atlas-test", reconnectBaseMillis = 10,
                reconnectMaxMillis = 10, maxReconnectAttempts = 1,
            ),
            credentialsProvider = InMemoryRelayCredentialStore(credentials),
            listener = object : PaseoRelayClient.Listener {},
            socketFactory = sockets,
            jitter = { 0 },
        )
        client.start()
        assertTrue(secondConnection.await(2, TimeUnit.SECONDS))
        assertEquals(2, sockets.connectCount.get())
        client.stop()
    }

    @Test
    fun reconnectBackoffUsesBoundedExponentialDelayAndJitter() {
        assertEquals(250L, RelayReconnectDelay.calculate(100, 1_000, 2) { 50 })
        assertEquals(1_000L, RelayReconnectDelay.calculate(100, 1_000, 10) { 1_000 })
        assertEquals(50L, RelayReconnectDelay.calculate(100, 1_000, 0) { 0 })
    }
}
