package dev.atlas.coach.sync.relay

import dev.atlas.coach.data.AgentConsentGate
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
    fun requestsConfiguredCapabilitiesAndRejectsMissingCoachChatWithoutClosingSession() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(staticPair)
        val transport = RelayBackendTransport(
            config = RelayConfig(
                "ws://127.0.0.1:4400",
                "atlas-test",
                maxReconnectAttempts = 0,
                requestedCapabilities = setOf("plan.revision", "coach.chat"),
            ),
            credentialStore = InMemoryRelayCredentialStore(credentials),
            socketFactory = sockets,
        )
        try {
            transport.start()
            assertEquals(setOf("coach.chat", "plan.revision"),
                (0 until sockets.appHellos.single().getJSONArray("capabilities").length())
                    .map { sockets.appHellos.single().getJSONArray("capabilities").getString(it) }.toSet())
            assertEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
            val result = transport.execute("coach.chat.request", JSONObject().put("message", "synthetic"))
            assertEquals("OPERATION_UNAVAILABLE", (result as RelayCallResult.RemoteError).code)
            assertTrue(result.message.contains("Réappairez"))
            assertTrue("chat must not be dispatched without the negotiated capability", sockets.requests.isEmpty())
            assertTrue("a missing capability must not close the relay session", sockets.sockets.single().closedCode == null)
        } finally { transport.close() }
    }

    @Test
    fun deferredPayloadIsBuiltAtDispatchAndUsesStableIdempotencyKey() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(
            staticPair,
            result = JSONObject().put("accepted", true),
            serverCapabilities = setOf("coach.chat", "plan.revision"),
        )
        val transport = transport(credentials, sockets, requestedCapabilities = setOf("plan.revision", "coach.chat"))
        val order = mutableListOf<String>()
        try {
            transport.start()
            val result = transport.executeDeferred(
                operation = "coach.chat.request",
                idempotencyKey = "chat-send-001",
                beforeDispatch = { order += "beforeDispatch"; true },
                payloadFactory = { order += "payload"; JSONObject().put("message", "synthetic") },
            )
            assertTrue(result is RelayCallResult.Success)
            assertEquals(listOf("beforeDispatch", "payload"), order)
            assertEquals("chat-send-001", sockets.requests.single().getString("idempotency_key"))
            assertEquals("synthetic", sockets.requests.single().getJSONObject("payload").getString("message"))
        } finally { transport.close() }
    }

    @Test
    fun v4RequestsSatisfyThePcConnectorsEnvelopeAndIdempotencyRules() {
        val (credentials, staticPair) = testCredentials()
        val v4Capabilities = setOf(
            "v4.day_plan.snapshot.write",
            "v4.proposal.ack",
            "v4.coach.chat",
        )
        val sockets = FakeRelaySocketFactory(staticPair, serverCapabilities = v4Capabilities)
        val transport = RelayBackendTransport(
            config = RelayConfig(
                "ws://127.0.0.1:4400",
                "atlas-test",
                maxReconnectAttempts = 0,
                requestedCapabilities = v4Capabilities,
            ),
            credentialStore = InMemoryRelayCredentialStore(credentials),
            socketFactory = sockets,
        )
        val proposalId = "ffffffff-ffff-4fff-8fff-ffffffffffff"
        val cases = listOf(
            Triple(
                "v4.plan.snapshot.upsert",
                "00000000-0000-4000-8000-000000000012",
                JSONObject()
                    .put("request_id", "00000000-0000-4000-8000-000000000001")
                    .put("idempotency_key", "00000000-0000-4000-8000-000000000012"),
            ),
            Triple(
                "v4.proposals.pull",
                "00000000-0000-4000-8000-000000000002",
                JSONObject().put("request_id", "00000000-0000-4000-8000-000000000002"),
            ),
            Triple(
                "v4.coach.chat.request",
                "00000000-0000-4000-8000-000000000003",
                JSONObject()
                    .put("request_id", "00000000-0000-4000-8000-000000000003")
                    .put("idempotency_key", "00000000-0000-4000-8000-000000000003"),
            ),
            Triple(
                "v4.proposals.ack",
                "00000000-0000-4000-8000-000000000004",
                JSONObject()
                    .put("ack_id", "00000000-0000-4000-8000-000000000004")
                    .put("proposal_id", proposalId),
            ),
        )

        try {
            transport.start()
            cases.forEachIndexed { index, (operation, idempotencyKey, payload) ->
                val requestId = payload.optString("request_id").ifBlank { uuid(20 + index) }
                val result = if (operation == "v4.plan.snapshot.upsert") {
                    transport.execute(
                        operation = operation,
                        payload = payload,
                        idempotencyKey = idempotencyKey,
                        requestId = requestId,
                    )
                } else {
                    transport.executeDeferred(
                        operation = operation,
                        idempotencyKey = idempotencyKey,
                        requestId = requestId,
                        payloadFactory = { JSONObject(payload.toString()) },
                    )
                }
                assertTrue("PC connector rejected $operation: $result", result is RelayCallResult.Success)
                val envelope = sockets.requests.last()
                assertEquals(idempotencyKey, envelope.getString("idempotency_key"))
                assertEquals(requestId, envelope.getString("request_id"))
                if (payload.has("request_id")) {
                    assertEquals(payload.getString("request_id"), envelope.getString("request_id"))
                }
            }
        } finally {
            transport.close()
        }
    }

    @Test
    fun v2ErrorMetadataPreservesHttpAndRemoteCodesWithoutExposingUpstreamText() {
        val (credentials, staticPair) = testCredentials()
        val capabilities = setOf("v4.proposal.ack")
        val sockets = FakeRelaySocketFactory(
            staticPair,
            dropRequestResponses = true,
            serverCapabilities = capabilities,
        )
        val transport = transport(credentials, sockets, requestedCapabilities = capabilities)
        val requestId = uuid(81)
        val resultRef = AtomicReference<RelayCallResult?>()
        try {
            transport.start()
            val call = Thread {
                resultRef.set(
                    transport.executeDeferred(
                        operation = "v4.proposals.pull",
                        idempotencyKey = requestId,
                        requestId = requestId,
                        payloadFactory = { JSONObject().put("request_id", requestId) },
                    ),
                )
            }.apply { isDaemon = true; start() }
            assertTrue(sockets.requestCaptured.await(2, TimeUnit.SECONDS))
            sockets.sockets.single().sendErrorForRequest(
                requestId = requestId,
                code = "BACKEND_REJECTED",
                message = "UPSTREAM-DETAIL-".repeat(10),
                httpStatus = 503,
                remoteCode = "CORE_UNAVAILABLE",
                retryable = true,
            )
            call.join(2_000)
            assertFalse(call.isAlive)
            val error = resultRef.get() as RelayCallResult.RemoteError
            assertEquals("CORE_UNAVAILABLE", error.code)
            assertEquals("CORE_UNAVAILABLE", error.remoteCode)
            assertEquals(503, error.httpStatus)
            assertTrue(error.retryable)
            assertFalse(error.message.contains("UPSTREAM-DETAIL"))
            assertTrue(error.message.length <= 120)
        } finally {
            transport.close()
        }
    }

    @Test
    fun malformedResponseIsRejectedAsProtocolFailure() {
        val (credentials, staticPair) = testCredentials()
        val capabilities = setOf("v4.proposal.ack")
        val sockets = FakeRelaySocketFactory(
            staticPair,
            serverCapabilities = capabilities,
            malformedRequestResponse = true,
        )
        val transport = transport(credentials, sockets, requestedCapabilities = capabilities)
        val requestId = uuid(82)
        try {
            transport.start()
            val result = transport.executeDeferred(
                operation = "v4.proposals.pull",
                idempotencyKey = requestId,
                requestId = requestId,
                payloadFactory = { JSONObject().put("request_id", requestId) },
            )
            assertEquals(RelayCallResult.Failure(RelayCallResult.Failure.Reason.PROTOCOL), result)
            assertEquals(RelayConnectionState.ERROR, transport.state())
        } finally {
            transport.close()
        }
    }

    @Test
    fun usageConsentRevocationWaitsForTheFinalPayloadAndWebSocketWrite() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(
            staticPair,
            result = JSONObject().put("accepted", true),
            serverCapabilities = setOf("coach.chat", "plan.revision"),
        )
        val gate = AgentConsentGate()
        val transport = transport(
            credentials,
            sockets,
            requestedCapabilities = setOf("plan.revision", "coach.chat"),
            consentGate = gate,
        )
        val payloadStarted = CountDownLatch(1)
        val releasePayload = CountDownLatch(1)
        val mutationStarted = CountDownLatch(1)
        val mutationDone = CountDownLatch(1)
        try {
            transport.start()
            val call = Thread {
                transport.executeDeferred(
                    operation = "coach.chat.request",
                    idempotencyKey = "consent-gate-send",
                    beforeDispatch = { true },
                    payloadFactory = {
                        payloadStarted.countDown()
                        check(releasePayload.await(5, TimeUnit.SECONDS)) { "payload release timed out" }
                        JSONObject().put("message", "synthetic")
                    },
                )
            }.apply { isDaemon = true; start() }
            assertTrue("payload preparation did not start", payloadStarted.await(5, TimeUnit.SECONDS))

            // Models AtlasController.revokeUsageObservation(): the real revocation takes the
            // same shared gate before mutating the usage consent store.
            val mutation = Thread {
                mutationStarted.countDown()
                gate.withLock { mutationDone.countDown() }
            }.apply { isDaemon = true; start() }
            assertTrue("consent mutation did not start", mutationStarted.await(5, TimeUnit.SECONDS))
            assertFalse(
                "consent mutation entered while the payload/WebSocket write held the gate",
                mutationDone.await(300, TimeUnit.MILLISECONDS),
            )

            releasePayload.countDown()
            assertTrue("consent mutation did not run after the send", mutationDone.await(5, TimeUnit.SECONDS))
            call.join(5_000)
            assertFalse("relay send did not finish", call.isAlive)
            assertEquals(1, sockets.requests.size)
        } finally {
            releasePayload.countDown()
            transport.close()
        }
    }

    @Test
    fun relayChatTimeoutKeepsEnvelopeTtlWithinProtocolLimit() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(
            staticPair,
            serverCapabilities = setOf("coach.chat", "plan.revision"),
        )
        val transport = transport(credentials, sockets, requestedCapabilities = setOf("plan.revision", "coach.chat"))
        try {
            transport.start()
            val result = transport.executeDeferred(
                operation = "coach.chat.request",
                timeoutMillis = 55_000L,
                idempotencyKey = "chat-send-55s-ttl",
                payloadFactory = { JSONObject().put("message", "synthetic") },
            )

            assertTrue(result is RelayCallResult.Success)
            val request = sockets.requests.single()
            val ttlMillis = Instant.parse(request.getString("expires_at")).toEpochMilli() -
                Instant.parse(request.getString("created_at")).toEpochMilli()
            assertEquals(55_000L, ttlMillis)
            assertTrue(
                "request TTL must remain at or below the protocol's 60-second maximum",
                ttlMillis <= AtlasRemoteProtocol.MAX_REQUEST_TTL_SECONDS * 1_000L,
            )
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
    fun disconnectDuringChatDoesNotReplayAfterReconnect() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(
            staticPair,
            dropRequestResponses = true,
            serverCapabilities = setOf("coach.chat", "plan.revision"),
        )
        val transport = RelayBackendTransport(
            config = RelayConfig(
                "ws://127.0.0.1:4400",
                "atlas-test",
                maxReconnectAttempts = 2,
                requestedCapabilities = setOf("plan.revision", "coach.chat"),
            ),
            credentialStore = InMemoryRelayCredentialStore(credentials),
            socketFactory = sockets,
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            transport.start()
            val pending = executor.submit<RelayCallResult> {
                transport.execute("coach.chat.request", JSONObject().put("message", "synthetic"), timeoutMillis = 4_000,
                    idempotencyKey = "chat-send-stable-1")
            }
            assertTrue(sockets.requestCaptured.await(2, TimeUnit.SECONDS))
            sockets.sockets.first().remoteClose()
            assertEquals(RelayCallResult.Failure(RelayCallResult.Failure.Reason.OFFLINE), pending.get(2, TimeUnit.SECONDS))
            assertTrue("relay did not reconnect", sockets.secondConnection.await(3, TimeUnit.SECONDS))
            assertEquals("sent chat is not replayed after reconnect", 1, sockets.requests.size)
            assertEquals("chat-send-stable-1", sockets.requests.single().getString("idempotency_key"))
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
    fun interruptingCallerCancelsInFlightChatWaitAndSendsBestEffortCancel() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(
            staticPair,
            dropRequestResponses = true,
            serverCapabilities = setOf("coach.chat", "plan.revision"),
        )
        val transport = transport(credentials, sockets, requestedCapabilities = setOf("plan.revision", "coach.chat"))
        val executor = Executors.newSingleThreadExecutor()
        try {
            transport.start()
            val pending = executor.submit {
                transport.execute("coach.chat.request", JSONObject().put("message", "synthetic"), timeoutMillis = 5_000)
            }
            assertTrue(sockets.requestCaptured.await(2, TimeUnit.SECONDS))
            pending.cancel(true)
            assertTrue("interrupt should send a best-effort relay cancel", sockets.cancelCaptured.await(2, TimeUnit.SECONDS))
            assertEquals(1, sockets.requests.size)
            assertEquals(sockets.requests.single().getString("request_id"), sockets.cancels.single().getString("target_request_id"))
        } finally {
            transport.close()
            executor.shutdownNow()
        }
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
            consentGate = AgentConsentGate(),
            socketFactory = FakeRelaySocketFactory(staticPair),
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
        requestedCapabilities: Set<String> = setOf("plan.revision"),
        consentGate: AgentConsentGate = AgentConsentGate(),
    ) = RelayBackendTransport(
        config = RelayConfig(
            "ws://127.0.0.1:4400",
            "atlas-test",
            maxReconnectAttempts = 0,
            maxQueuedRequests = maxQueuedRequests,
            requestedCapabilities = requestedCapabilities,
        ),
        credentialStore = InMemoryRelayCredentialStore(credentials),
        consentGate = consentGate,
        socketFactory = sockets,
    )

    private fun uuid(number: Int): String =
        "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"
}
