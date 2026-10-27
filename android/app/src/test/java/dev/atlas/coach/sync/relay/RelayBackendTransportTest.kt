package dev.atlas.coach.sync.relay

import dev.atlas.coach.plan.PlanRevisionApiRequest
import dev.atlas.coach.plan.PlanRevisionApiResult
import dev.atlas.coach.plan.dayPlan
import dev.atlas.coach.sync.agent.AgentMandate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RelayBackendTransportTest {
    @Test
    fun authenticatesInsideTheProtectedHelloAndExposesSessionState() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(staticPair)
        val transport = transport(credentials, sockets)
        try {
            transport.start()
            assertEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
            assertEquals("ws://127.0.0.1:4400/ws?serverId=atlas-test&role=client&v=2", sockets.urls.single())
            val hello = sockets.appHellos.single()
            assertEquals(credentials.deviceId, hello.getString("device_id"))
            assertEquals(credentials.deviceToken, hello.getJSONObject("auth").getString("token"))
            assertFalse(sockets.urls.single().contains(credentials.deviceToken))
            assertEquals("device-token-v1", hello.getJSONObject("auth").getString("scheme"))
        } finally { transport.close() }
    }

    @Test
    fun correlatesResponsesAndKeepsIdempotencyKeyAcrossCallerRetries() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(staticPair, result = JSONObject().put("status", "ok"))
        val transport = transport(credentials, sockets)
        try {
            transport.start()
            val first = transport.execute("plan.revision.request", JSONObject(), idempotencyKey = "plan-revision-42")
            val second = transport.execute("plan.revision.request", JSONObject(), idempotencyKey = "plan-revision-42")
            assertTrue(first is RelayCallResult.Success)
            assertTrue(second is RelayCallResult.Success)
            assertEquals(2, sockets.requests.size)
            assertEquals("plan-revision-42", sockets.requests[0].getString("idempotency_key"))
            assertEquals("plan-revision-42", sockets.requests[1].getString("idempotency_key"))
            assertFalse(sockets.requests[0].getString("request_id") == sockets.requests[1].getString("request_id"))
            val created = Instant.parse(sockets.requests[0].getString("created_at"))
            val expires = Instant.parse(sockets.requests[0].getString("expires_at"))
            assertTrue(expires.isAfter(created))
            assertTrue(TimeUnit.SECONDS.convert(expires.toEpochMilli() - created.toEpochMilli(), TimeUnit.MILLISECONDS) <= 60)
        } finally { transport.close() }
    }

    @Test
    fun boundsOutstandingQueueAndNeverResendsAnAlreadySentRequest() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(staticPair, dropRequestResponses = true)
        val transport = transport(credentials, sockets, maxQueuedRequests = 1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            transport.start()
            val first = executor.submit<RelayCallResult> {
                transport.execute("plan.revision.request", JSONObject(), timeoutMillis = 2_000)
            }
            assertTrue(sockets.requestCaptured.await(1, TimeUnit.SECONDS))
            assertEquals(
                RelayCallResult.Failure(RelayCallResult.Failure.Reason.QUEUE_FULL),
                transport.execute("plan.revision.request", JSONObject(), timeoutMillis = 100),
            )
            sockets.sockets.single().remoteClose()
            assertEquals(RelayCallResult.Failure(RelayCallResult.Failure.Reason.OFFLINE), first.get(1, TimeUnit.SECONDS))
            assertEquals("one request reached the peer; reconnect did not replay it", 1, sockets.requests.size)
        } finally {
            transport.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun expiresTimedOutRequestAndSendsOnlyBestEffortCancel() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(staticPair, dropRequestResponses = true)
        val transport = transport(credentials, sockets)
        try {
            transport.start()
            val result = transport.execute("plan.revision.request", JSONObject(), timeoutMillis = 80)
            assertEquals(RelayCallResult.Failure(RelayCallResult.Failure.Reason.TIMEOUT), result)
            assertEquals(1, sockets.requests.size)
            assertEquals(1, sockets.cancels.size)
            assertEquals(sockets.requests.single().getString("request_id"), sockets.cancels.single().getString("target_request_id"))
        } finally { transport.close() }
    }

    @Test
    fun rejectsUncorrelatedResponseAndMapsDeviceAuthFailure() {
        val (credentials, staticPair) = testCredentials()
        val badCorrelation = FakeRelaySocketFactory(staticPair, mismatchResponseId = true)
        val transport = transport(credentials, badCorrelation)
        try {
            transport.start()
            val result = transport.execute("plan.revision.request", JSONObject(), timeoutMillis = 1_000)
            assertEquals(RelayCallResult.Failure(RelayCallResult.Failure.Reason.PROTOCOL), result)
            assertEquals(RelayConnectionState.ERROR, transport.state())
            assertEquals(4003, badCorrelation.sockets.single().closedCode)
        } finally { transport.close() }

        val (credentials2, staticPair2) = testCredentials()
        val authFailure = FakeRelaySocketFactory(staticPair2, authError = true)
        val authTransport = transport(credentials2, authFailure)
        try {
            authTransport.start()
            assertEquals(RelayConnectionState.AUTH_REQUIRED, authTransport.state())
            assertEquals(
                RelayCallResult.RemoteError(
                    "AUTH_REQUIRED",
                    RelayFailure.fromRemoteCode("AUTH_REQUIRED").message,
                    false,
                ),
                authTransport.execute("plan.revision.request", JSONObject()),
            )
        } finally { authTransport.close() }
    }

    @Test
    fun reportsAuthenticationAndRevocationAsDistinctSafeFailures() {
        val (authCredentials, authPair) = testCredentials()
        val authTransport = transport(
            authCredentials,
            FakeRelaySocketFactory(
                authPair,
                authError = true,
                remoteErrorMessage = "token=${authCredentials.deviceToken} wss://user:password@relay.example.org",
            ),
        )
        val authFailure = AtomicReference<RelayFailure?>()
        val authReported = CountDownLatch(1)
        authTransport.addFailureListener {
            authFailure.set(it)
            authReported.countDown()
        }
        try {
            authTransport.start()
            assertTrue(authReported.await(1, TimeUnit.SECONDS))
            assertEquals(RelayFailureKind.AUTHENTICATION, authFailure.get()?.kind)
            assertFalse(authFailure.get()?.message.orEmpty().contains(authCredentials.deviceToken))
            assertFalse(authFailure.get()?.message.orEmpty().contains("password"))
            assertEquals(RelayConnectionState.AUTH_REQUIRED, authTransport.state())
        } finally { authTransport.close() }

        val (revokedCredentials, revokedPair) = testCredentials()
        val revokedTransport = transport(revokedCredentials, FakeRelaySocketFactory(revokedPair, revokedError = true))
        val revokedFailure = AtomicReference<RelayFailure?>()
        val revocationReported = CountDownLatch(1)
        revokedTransport.addFailureListener {
            revokedFailure.set(it)
            revocationReported.countDown()
        }
        try {
            revokedTransport.start()
            assertTrue(revocationReported.await(1, TimeUnit.SECONDS))
            assertEquals(RelayFailureKind.REVOKED, revokedFailure.get()?.kind)
            assertEquals(RelayConnectionState.REVOKED, revokedTransport.state())
            assertFalse(revokedFailure.get()?.message.orEmpty().contains(revokedCredentials.deviceToken))
        } finally { revokedTransport.close() }
    }

    @Test
    fun missingCredentialRequiresPairingAndPlanClientPreservesApiResultTypes() {
        val (credentials, staticPair) = testCredentials()
        val missing = RelayBackendTransport(
            RelayConfig("ws://127.0.0.1:4400", "atlas-test", maxReconnectAttempts = 0),
            InMemoryRelayCredentialStore(),
            FakeRelaySocketFactory(staticPair),
        )
        try {
            missing.start()
            assertEquals(RelayConnectionState.PAIRING_REQUIRED, missing.state())
        } finally { missing.close() }

        val response = JSONObject()
            .put("schema_version", 3).put("reply", "Aucune proposition.")
            .put("agent_status", "ok").put("proposal", JSONObject.NULL).put("overlaps", org.json.JSONArray())
        val sockets = FakeRelaySocketFactory(staticPair, result = response)
        val transport = transport(credentials, sockets)
        try {
            transport.start()
            val client = RelayPlanRevisionClient(transport)
            val result = client.propose(
                PlanRevisionApiRequest(
                    requestId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
                    conversationId = null,
                    message = "Planifie la journée",
                    planContextConsented = true,
                    mandate = AgentMandate.default(),
                    plan = dayPlan(),
                ),
            )
            assertTrue(result is PlanRevisionApiResult.Success)
            val request = sockets.requests.single()
            assertEquals("plan.revision.request", request.getString("operation"))
            assertEquals("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", request.getString("idempotency_key"))
            assertEquals("Planifie la journée", request.getJSONObject("payload").getString("message"))
        } finally { transport.close() }
    }

    @Test
    fun revocationCloseDoesNotOverwriteRevokedFailureWithAuthentication() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(staticPair)
        val transport = transport(credentials, sockets)
        val revoked = CountDownLatch(1)
        val authenticationFailure = CountDownLatch(1)
        val lastFailure = AtomicReference<RelayFailure?>()
        transport.addFailureListener {
            lastFailure.set(it)
            if (it.kind == RelayFailureKind.REVOKED) revoked.countDown()
            if (it.kind == RelayFailureKind.AUTHENTICATION) authenticationFailure.countDown()
        }
        try {
            transport.start()
            assertEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
            val socket = sockets.sockets.single()
            socket.sendErrorEnvelope("DEVICE_REVOKED", "device revoked")
            assertTrue("DEVICE_REVOKED was not reported", revoked.await(1, TimeUnit.SECONDS))
            assertEquals(RelayConnectionState.REVOKED, transport.state())
            socket.remoteClose(4001)
            assertFalse(
                "an AUTH failure must not overwrite the REVOKED message on close 4001",
                authenticationFailure.await(300, TimeUnit.MILLISECONDS),
            )
            assertEquals(RelayConnectionState.REVOKED, transport.state())
            assertEquals(RelayFailureKind.REVOKED, lastFailure.get()?.kind)
        } finally {
            transport.close()
        }
    }

    @Test
    fun heartbeatReconnectsWhenThePeerStopsAnswering() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(staticPair)
        val transport = RelayBackendTransport(
            config = RelayConfig("ws://127.0.0.1:4400", "atlas-test", maxReconnectAttempts = 2),
            credentialStore = InMemoryRelayCredentialStore(credentials),
            socketFactory = sockets,
            heartbeatIntervalMillis = 80,
            heartbeatTimeoutMillis = 80,
        )
        try {
            transport.start()
            assertEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (System.nanoTime() < deadline && sockets.connectCount.get() < 2) {
                Thread.sleep(20)
            }
            assertTrue("heartbeat did not force a reconnect", sockets.connectCount.get() >= 2)
        } finally {
            transport.close()
        }
    }

    @Test
    fun heartbeatPongKeepsAHealthySessionConnected() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(staticPair, respondToPings = true)
        val transport = RelayBackendTransport(
            config = RelayConfig("ws://127.0.0.1:4400", "atlas-test", maxReconnectAttempts = 0),
            credentialStore = InMemoryRelayCredentialStore(credentials),
            socketFactory = sockets,
            heartbeatIntervalMillis = 50,
            heartbeatTimeoutMillis = 200,
        )
        try {
            transport.start()
            assertEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
            Thread.sleep(400)
            assertEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
            assertEquals("a pong must keep the session alive", 1, sockets.connectCount.get())
        } finally {
            transport.close()
        }
    }

    private fun transport(
        credentials: RelayCredentials,
        sockets: FakeRelaySocketFactory,
        maxQueuedRequests: Int = 16,
    ) = RelayBackendTransport(
        config = RelayConfig("ws://127.0.0.1:4400", "atlas-test", maxReconnectAttempts = 0, maxQueuedRequests = maxQueuedRequests),
        credentialStore = InMemoryRelayCredentialStore(credentials),
        socketFactory = sockets,
    )
}
