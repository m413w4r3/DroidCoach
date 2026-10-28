package dev.atlas.coach.sync.relay

import dev.atlas.coach.data.AgentConsentGate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException

class RelayNetworkClassificationTest {
    @Test
    fun airplaneMode_unknownHostOrConnectException_goesOffline() {
        assertOfflineAfterConnectFailure(UnknownHostException("airplane mode"))
        assertOfflineAfterConnectFailure(ConnectException("network unreachable"))
    }

    @Test
    fun wifiDisabled_connectException_goesOffline() {
        assertOfflineAfterConnectFailure(ConnectException("Wi-Fi unavailable"))
    }

    @Test
    fun mobileDataDisabled_unknownHostException_goesOffline() {
        assertOfflineAfterConnectFailure(UnknownHostException("mobile network unavailable"))
    }

    @Test
    fun wifiConnectedButInternetLost_socketTimeout_goesOffline() {
        assertOfflineAfterConnectFailure(SocketTimeoutException("connect timed out"))
    }

    @Test
    fun relayUnreachableButInternetAvailable_publicIpConnectException_goesOffline() {
        assertOfflineAfterConnectFailure(
            ConnectException("relay refused connection"),
            relayUrl = "wss://203.0.113.7:4400",
        )
    }

    @Test
    fun fastApiStopped_backendUnavailableOperation_keepsRelayConnected() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(staticPair, dropRequestResponses = true)
        val transport = RelayBackendTransport(
            config = RelayConfig("ws://127.0.0.1:4400", "atlas-test", maxReconnectAttempts = 0),
            credentialStore = InMemoryRelayCredentialStore(credentials),
            consentGate = AgentConsentGate(),
            socketFactory = sockets,
        )
        val requestThread = Executors.newSingleThreadExecutor()
        val result = AtomicReference<RelayCallResult?>()
        val resultReady = CountDownLatch(1)

        try {
            transport.start()
            assertEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
            requestThread.execute {
                result.set(transport.execute("backend.health", JSONObject(), timeoutMillis = 5_000))
                resultReady.countDown()
            }
            assertTrue("request was not dispatched", sockets.requestCaptured.await(2, TimeUnit.SECONDS))
            val requestId = sockets.requests.single().getString("request_id")
            val remoteError = JSONObject()
                .put("protocol", AtlasRemoteProtocol.PROTOCOL)
                .put("kind", "error")
                .put("request_id", requestId)
                .put("session_id", FakeRelaySocket.SESSION_ID)
                .put("error", JSONObject()
                    .put("code", "BACKEND_UNAVAILABLE")
                    .put("message", "backend unavailable")
                    .put("retryable", true))
                .put("completed_at", java.time.Instant.now().toString())

            // Inject the already-decrypted application envelope at the protocol boundary.
            val dispatch = RelayBackendTransport::class.java
                .getDeclaredMethod("handleApplicationFrame", ByteArray::class.java)
                .apply { isAccessible = true }
            dispatch.invoke(transport, AtlasRemoteProtocol.encode(remoteError))

            assertTrue("operation did not complete", resultReady.await(2, TimeUnit.SECONDS))
            assertEquals("BACKEND_UNAVAILABLE", (result.get() as RelayCallResult.RemoteError).code)
            assertEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
        } finally {
            requestThread.shutdownNow()
            transport.close()
            staticPair.privateKey.fill(0)
        }
    }

    @Test
    fun invalidTlsCertificate_isTerminalErrorWithoutReconnectOrOffline() {
        val (credentials, staticPair) = testCredentials()
        val tlsError = SSLHandshakeException("certificate validation failed").apply {
            initCause(CertPathValidatorException("synthetic invalid certificate"))
        }
        val scheduler = ScheduledThreadPoolExecutor(1)
        val factory = FirstSocketWriteFailureFactory(tlsError)
        val states = CopyOnWriteArrayList<RelayConnectionState>()
        val failure = AtomicReference<RelayFailure?>()
        val client = PaseoRelayClient(
            config = RelayConfig(
                "ws://127.0.0.1:4400",
                "atlas-test",
                reconnectBaseMillis = 60_000,
                reconnectMaxMillis = 60_000,
                maxReconnectAttempts = 4,
            ),
            credentialsProvider = InMemoryRelayCredentialStore(credentials),
            listener = object : PaseoRelayClient.Listener {
                override fun onStateChanged(state: RelayConnectionState) { states += state }
                override fun onRelayFailure(value: RelayFailure) { failure.set(value) }
            },
            socketFactory = factory,
            scheduler = scheduler,
            jitter = { 0 },
        )

        try {
            client.start()
            assertEquals(RelayFailureKind.TLS_CERTIFICATE, failure.get()?.kind)
            assertEquals(RelayConnectionState.ERROR, client.state())
            assertEquals(listOf(RelayConnectionState.RELAY_CONNECTING, RelayConnectionState.ERROR), states.toList())
            assertFalse(states.contains(RelayConnectionState.OFFLINE))
            assertEquals("TLS failure must not schedule another attempt", 0, scheduler.queue.size)
            assertEquals("TLS failure must not reconnect", 1, factory.connectCount.get())
            assertEquals(4002, factory.failedSocket?.closedCode)
        } finally {
            client.stop()
            scheduler.shutdownNow()
            staticPair.privateKey.fill(0)
        }
    }

    @Test
    fun noiseIdentityMismatch_isTerminalErrorWithoutReconnectOrOffline() {
        val (credentials, pinnedPair) = testCredentials()
        val wrongPair = NoiseKeyPair.generate()
        val scheduler = ScheduledThreadPoolExecutor(1)
        val sockets = FakeRelaySocketFactory(wrongPair)
        val states = CopyOnWriteArrayList<RelayConnectionState>()
        val failure = AtomicReference<RelayFailure?>()
        val client = PaseoRelayClient(
            config = RelayConfig(
                "ws://127.0.0.1:4400",
                "atlas-test",
                reconnectBaseMillis = 60_000,
                reconnectMaxMillis = 60_000,
                maxReconnectAttempts = 4,
            ),
            credentialsProvider = InMemoryRelayCredentialStore(credentials),
            listener = object : PaseoRelayClient.Listener {
                override fun onStateChanged(state: RelayConnectionState) { states += state }
                override fun onRelayFailure(value: RelayFailure) { failure.set(value) }
            },
            socketFactory = sockets,
            scheduler = scheduler,
            jitter = { 0 },
        )

        try {
            client.start()
            assertEquals(RelayFailureKind.NOISE_IDENTITY_MISMATCH, failure.get()?.kind)
            assertEquals(RelayConnectionState.ERROR, client.state())
            assertEquals(listOf(RelayConnectionState.RELAY_CONNECTING, RelayConnectionState.ERROR), states.toList())
            assertFalse(states.contains(RelayConnectionState.OFFLINE))
            assertEquals(0, scheduler.queue.size)
            assertEquals(1, sockets.connectCount.get())
            assertEquals(4002, sockets.sockets.single().closedCode)
        } finally {
            client.stop()
            scheduler.shutdownNow()
            pinnedPair.privateKey.fill(0)
            wrongPair.privateKey.fill(0)
        }
    }

    @Test
    fun permissionDeniedAndUnknownFailures_areTerminalErrorsWithoutReconnect() {
        assertTerminalAfterConnectFailure(SecurityException("permission denied"), RelayFailureKind.PERMISSION_DENIED)
        assertTerminalAfterConnectFailure(IllegalStateException("unexpected relay failure"), RelayFailureKind.UNKNOWN)
    }

    @Test
    fun remoteProtectedProtocolClose_isTerminalErrorWithoutReconnect() {
        val (credentials, staticPair) = testCredentials()
        val scheduler = ScheduledThreadPoolExecutor(1)
        val sockets = FakeRelaySocketFactory(
            staticPair,
            onConnected = { socket, _ -> socket.remoteClose(4002, "protected handshake rejected") },
        )
        val states = CopyOnWriteArrayList<RelayConnectionState>()
        val failure = AtomicReference<RelayFailure?>()
        val client = PaseoRelayClient(
            config = RelayConfig(
                "ws://127.0.0.1:4400",
                "atlas-test",
                reconnectBaseMillis = 60_000,
                reconnectMaxMillis = 60_000,
                maxReconnectAttempts = 4,
            ),
            credentialsProvider = InMemoryRelayCredentialStore(credentials),
            listener = object : PaseoRelayClient.Listener {
                override fun onStateChanged(state: RelayConnectionState) { states += state }
                override fun onRelayFailure(value: RelayFailure) { failure.set(value) }
            },
            socketFactory = sockets,
            scheduler = scheduler,
            jitter = { 0 },
        )

        try {
            client.start()
            assertEquals(RelayFailureKind.UNKNOWN, failure.get()?.kind)
            assertEquals(RelayConnectionState.ERROR, client.state())
            assertEquals(RelayConnectionState.ERROR, states.last())
            assertFalse(states.contains(RelayConnectionState.OFFLINE))
            assertEquals(0, scheduler.queue.size)
            assertEquals(1, sockets.connectCount.get())
        } finally {
            client.stop()
            scheduler.shutdownNow()
            staticPair.privateKey.fill(0)
        }
    }

    @Test
    fun invalidToken_requiresAuthentication() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(staticPair, authError = true)
        val transport = transport(credentials, sockets)
        try {
            transport.start()
            assertEquals(RelayConnectionState.AUTH_REQUIRED, transport.state())
            assertEquals(1, sockets.connectCount.get())
        } finally {
            transport.close()
            staticPair.privateKey.fill(0)
        }
    }

    @Test
    fun revokedDevice_isRevoked() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(staticPair, revokedError = true)
        val transport = transport(credentials, sockets)
        try {
            transport.start()
            assertEquals(RelayConnectionState.REVOKED, transport.state())
            assertEquals(1, sockets.connectCount.get())
        } finally {
            transport.close()
            staticPair.privateKey.fill(0)
        }
    }

    @Test
    fun networkRecovery_firstConnectFails_thenConnectsOfflineBeforeRetrying() {
        val (credentials, staticPair) = testCredentials()
        val peer = FakeRelaySocketFactory(staticPair)
        val factory = FirstConnectFailureFactory(UnknownHostException("temporary DNS failure"), peer)
        val scheduler = ScheduledThreadPoolExecutor(1)
        val states = CopyOnWriteArrayList<RelayConnectionState>()
        val connected = CountDownLatch(1)
        val client = PaseoRelayClient(
            config = RelayConfig(
                "ws://127.0.0.1:4400",
                "atlas-test",
                reconnectBaseMillis = 1,
                reconnectMaxMillis = 1,
                maxReconnectAttempts = 1,
            ),
            credentialsProvider = InMemoryRelayCredentialStore(credentials),
            listener = object : PaseoRelayClient.Listener {
                override fun onStateChanged(state: RelayConnectionState) { states += state }
                override fun onSecureChannelReady() { connected.countDown() }
            },
            socketFactory = factory,
            scheduler = scheduler,
            jitter = { 0 },
        )

        try {
            client.start()
            assertTrue("relay did not reconnect", connected.await(2, TimeUnit.SECONDS))
            assertEquals(
                listOf(
                    RelayConnectionState.RELAY_CONNECTING,
                    RelayConnectionState.OFFLINE,
                    RelayConnectionState.RELAY_CONNECTING,
                    RelayConnectionState.RELAY_CONNECTED,
                ),
                states.toList(),
            )
            assertEquals(2, factory.connectCount.get())
        } finally {
            client.stop()
            scheduler.shutdownNow()
            staticPair.privateKey.fill(0)
        }
    }

    @Test
    fun socketIoFailureDuringHandshake_isOfflineAndCanReconnect() {
        val (credentials, staticPair) = testCredentials()
        val peer = FakeRelaySocketFactory(staticPair)
        val factory = FirstSocketWriteFailureFactory(SocketTimeoutException("handshake send timed out"), peer)
        val scheduler = ScheduledThreadPoolExecutor(1)
        val states = CopyOnWriteArrayList<RelayConnectionState>()
        val connected = CountDownLatch(1)
        val client = PaseoRelayClient(
            config = RelayConfig(
                "ws://127.0.0.1:4400",
                "atlas-test",
                reconnectBaseMillis = 1,
                reconnectMaxMillis = 1,
                maxReconnectAttempts = 1,
            ),
            credentialsProvider = InMemoryRelayCredentialStore(credentials),
            listener = object : PaseoRelayClient.Listener {
                override fun onStateChanged(state: RelayConnectionState) { states += state }
                override fun onSecureChannelReady() { connected.countDown() }
            },
            socketFactory = factory,
            scheduler = scheduler,
            jitter = { 0 },
        )

        try {
            client.start()
            assertTrue("relay did not reconnect after handshake I/O failure", connected.await(2, TimeUnit.SECONDS))
            assertEquals(
                listOf(
                    RelayConnectionState.RELAY_CONNECTING,
                    RelayConnectionState.OFFLINE,
                    RelayConnectionState.RELAY_CONNECTING,
                    RelayConnectionState.RELAY_CONNECTED,
                ),
                states.toList(),
            )
            assertEquals(2, factory.connectCount.get())
            assertEquals(4002, factory.failedSocket?.closedCode)
        } finally {
            client.stop()
            scheduler.shutdownNow()
            staticPair.privateKey.fill(0)
        }
    }

    private fun assertOfflineAfterConnectFailure(error: Throwable, relayUrl: String = "ws://127.0.0.1:4400") {
        val (credentials, staticPair) = testCredentials()
        val scheduler = ScheduledThreadPoolExecutor(1)
        val factory = FirstConnectFailureFactory(error)
        val states = CopyOnWriteArrayList<RelayConnectionState>()
        val failure = AtomicReference<RelayFailure?>()
        val protocolFailures = AtomicInteger()
        val client = PaseoRelayClient(
            config = RelayConfig(
                relayUrl,
                "atlas-test",
                maxReconnectAttempts = 0,
            ),
            credentialsProvider = InMemoryRelayCredentialStore(credentials),
            listener = object : PaseoRelayClient.Listener {
                override fun onStateChanged(state: RelayConnectionState) { states += state }
                override fun onProtocolFailure(error: Throwable) { protocolFailures.incrementAndGet() }
                override fun onRelayFailure(value: RelayFailure) { failure.set(value) }
            },
            socketFactory = factory,
            scheduler = scheduler,
        )

        try {
            client.start()
            assertEquals(RelayFailureKind.NETWORK_UNREACHABLE, failure.get()?.kind)
            assertEquals(RelayConnectionState.OFFLINE, client.state())
            assertEquals(listOf(RelayConnectionState.RELAY_CONNECTING, RelayConnectionState.OFFLINE), states.toList())
            assertEquals("network loss must not be reported as a protocol failure", 0, protocolFailures.get())
            assertEquals("retry budget is exhausted", 1, factory.connectCount.get())
        } finally {
            client.stop()
            scheduler.shutdownNow()
            staticPair.privateKey.fill(0)
        }
    }

    private fun assertTerminalAfterConnectFailure(error: Throwable, expectedKind: RelayFailureKind) {
        val (credentials, staticPair) = testCredentials()
        val scheduler = ScheduledThreadPoolExecutor(1)
        val factory = FirstConnectFailureFactory(error)
        val states = CopyOnWriteArrayList<RelayConnectionState>()
        val failure = AtomicReference<RelayFailure?>()
        val client = PaseoRelayClient(
            config = RelayConfig(
                "ws://127.0.0.1:4400",
                "atlas-test",
                reconnectBaseMillis = 60_000,
                reconnectMaxMillis = 60_000,
                maxReconnectAttempts = 4,
            ),
            credentialsProvider = InMemoryRelayCredentialStore(credentials),
            listener = object : PaseoRelayClient.Listener {
                override fun onStateChanged(state: RelayConnectionState) { states += state }
                override fun onRelayFailure(value: RelayFailure) { failure.set(value) }
            },
            socketFactory = factory,
            scheduler = scheduler,
            jitter = { 0 },
        )

        try {
            client.start()
            assertEquals(expectedKind, failure.get()?.kind)
            assertEquals(RelayConnectionState.ERROR, client.state())
            assertEquals(listOf(RelayConnectionState.RELAY_CONNECTING, RelayConnectionState.ERROR), states.toList())
            assertFalse(states.contains(RelayConnectionState.OFFLINE))
            assertEquals("terminal failures must not schedule retries", 0, scheduler.queue.size)
            assertEquals(1, factory.connectCount.get())
        } finally {
            client.stop()
            scheduler.shutdownNow()
            staticPair.privateKey.fill(0)
        }
    }

    private fun transport(credentials: RelayCredentials, sockets: FakeRelaySocketFactory) = RelayBackendTransport(
        config = RelayConfig("ws://127.0.0.1:4400", "atlas-test", maxReconnectAttempts = 0),
        credentialStore = InMemoryRelayCredentialStore(credentials),
        socketFactory = sockets,
    )

    private class FirstConnectFailureFactory(
        private val failure: Throwable,
        private val successfulFactory: RelaySocketFactory? = null,
    ) : RelaySocketFactory {
        val connectCount = AtomicInteger()

        override fun connect(url: String, callbacks: RelaySocketCallbacks) {
            when (connectCount.incrementAndGet()) {
                1 -> callbacks.onFailure(failure)
                else -> (successfulFactory ?: error("unexpected retry without a delegate")).connect(url, callbacks)
            }
        }
    }

    private class FirstSocketWriteFailureFactory(
        private val failure: Throwable,
        private val successfulFactory: RelaySocketFactory? = null,
    ) : RelaySocketFactory {
        val connectCount = AtomicInteger()
        @Volatile var failedSocket: ThrowingWriteSocket? = null
            private set

        override fun connect(url: String, callbacks: RelaySocketCallbacks) {
            when (connectCount.incrementAndGet()) {
                1 -> ThrowingWriteSocket(callbacks, failure).also {
                    failedSocket = it
                    callbacks.onOpen(it)
                }
                else -> (successfulFactory ?: error("unexpected retry without a delegate")).connect(url, callbacks)
            }
        }
    }

    private class ThrowingWriteSocket(
        private val callbacks: RelaySocketCallbacks,
        private val failure: Throwable,
    ) : RelaySocket {
        @Volatile var closedCode: Int? = null
            private set

        override fun sendText(text: String): Boolean = throw failure

        override fun close(code: Int, reason: String) {
            if (closedCode != null) return
            closedCode = code
            callbacks.onClosed(code, reason)
        }
    }
}
