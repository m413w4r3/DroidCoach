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
import java.util.concurrent.atomic.AtomicBoolean
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
    fun duplicateInFlightRequestIdDoesNotReplaceFirstWaiter() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(
            staticPair,
            dropRequestResponses = true,
            serverCapabilities = setOf("coach.chat"),
        )
        val transport = transport(credentials, sockets, requestedCapabilities = setOf("coach.chat"))
        val executor = Executors.newFixedThreadPool(2)
        val requestId = uuid(71)
        val idempotencyKey = uuid(72)
        val bodyText = "same synthetic request"
        val timeoutMillis = 800L
        val releaseSecondDispatch = CountDownLatch(1)
        val secondDispatchEntered = AtomicBoolean(false)
        val secondProgress = CountDownLatch(1)
        try {
            transport.start()
            val startedAt = System.nanoTime()
            val first = executor.submit<RelayCallResult> {
                transport.executeDeferred(
                    operation = "coach.chat.request",
                    timeoutMillis = timeoutMillis,
                    idempotencyKey = idempotencyKey,
                    payloadFactory = { JSONObject().put("message", bodyText) },
                    requestId = requestId,
                )
            }
            assertTrue(sockets.requestCaptured.await(1, TimeUnit.SECONDS))

            val second = executor.submit<RelayCallResult> {
                try {
                    transport.executeDeferred(
                        operation = "coach.chat.request",
                        timeoutMillis = timeoutMillis,
                        idempotencyKey = idempotencyKey,
                        payloadFactory = { JSONObject().put("message", bodyText) },
                        beforeDispatch = {
                            secondDispatchEntered.set(true)
                            secondProgress.countDown()
                            releaseSecondDispatch.await(1, TimeUnit.SECONDS)
                            true
                        },
                        requestId = requestId,
                    )
                } finally {
                    secondProgress.countDown()
                }
            }
            assertTrue(secondProgress.await(1, TimeUnit.SECONDS))
            if (secondDispatchEntered.get()) {
                releaseSecondDispatch.countDown()
                assertTrue(sockets.secondRequestCaptured.await(1, TimeUnit.SECONDS))
            }

            sockets.sockets.single().sendResponseForRequest(requestId)
            val firstResult = first.get(1, TimeUnit.SECONDS)
            val secondResult = second.get(1, TimeUnit.SECONDS)

            assertTrue("the first waiter must receive the response", firstResult is RelayCallResult.Success)
            assertTrue("the duplicate caller must receive a typed failure", secondResult is RelayCallResult.Failure)
            assertEquals(1, sockets.requests.size)
            assertEquals(requestId, sockets.requests.single().getString("request_id"))
            assertEquals(idempotencyKey, sockets.requests.single().getString("idempotency_key"))
            assertEquals(bodyText, sockets.requests.single().getJSONObject("payload").getString("message"))
            assertTrue(
                "both callers must finish before the shared request timeout",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < timeoutMillis,
            )
        } finally {
            releaseSecondDispatch.countDown()
            transport.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun staleSessionResponseDoesNotAbortCurrentSession() {
        val (credentials, staticPair) = testCredentials()
        val oldSessionId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1"
        val currentSessionId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2"
        val sockets = FakeRelaySocketFactory(
            staticPair,
            serverCapabilities = setOf("plan.revision"),
            sessionIdForConnection = { index -> if (index == 1) oldSessionId else currentSessionId },
        )
        val transport = transport(
            credentials,
            sockets,
            requestedCapabilities = setOf("plan.revision"),
            maxReconnectAttempts = 1,
            reconnectBaseMillis = 1,
        )
        try {
            transport.start()
            assertEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
            sockets.sockets.first().remoteClose()
            assertTrue(sockets.secondSessionConnected.await(1, TimeUnit.SECONDS))
            assertEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())

            // PaseoRelayClient filters callbacks from obsolete socket generations. Inject the
            // stale application-session frame through S2 so this test reaches transport correlation.
            sockets.sockets.last().sendUnknownResponse(uuid(73), responseSessionId = oldSessionId)
            assertEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())

            val next = transport.execute(
                operation = "plan.revision.request",
                payload = JSONObject().put("request", "after stale frame"),
                timeoutMillis = 500,
                idempotencyKey = uuid(74),
                requestId = uuid(75),
            )
            assertTrue(next is RelayCallResult.Success)
            assertEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
            assertEquals(currentSessionId, sockets.requests.single().getString("session_id"))
        } finally { transport.close() }
    }

    @Test(timeout = 7_000)
    fun staleGenerationResponseCannotCompleteCurrentInflightRequest() {
        val (credentials, staticPair) = testCredentials()
        val oldSessionId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1"
        val currentSessionId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2"
        val sockets = FakeRelaySocketFactory(
            staticPair,
            dropRequestResponses = true,
            serverCapabilities = setOf("coach.chat", "plan.revision"),
            sessionIdForConnection = { index -> if (index == 1) oldSessionId else currentSessionId },
        )
        val transport = transport(
            credentials,
            sockets,
            requestedCapabilities = setOf("plan.revision", "coach.chat"),
            maxReconnectAttempts = 1,
            reconnectBaseMillis = 1,
        )
        val executor = Executors.newSingleThreadExecutor()
        val requestId = uuid(76)
        try {
            transport.start()
            val oldSocket = sockets.sockets.single()
            oldSocket.remoteClose()
            assertTrue("replacement session did not connect", sockets.secondSessionConnected.await(2, TimeUnit.SECONDS))

            val current = executor.submit<RelayCallResult> {
                transport.executeDeferred(
                    operation = "coach.chat.request",
                    timeoutMillis = 3_000,
                    idempotencyKey = requestId,
                    requestId = requestId,
                    payloadFactory = { JSONObject().put("message", "current synthetic message") },
                )
            }
            assertTrue("current request was not captured", sockets.requestCaptured.await(2, TimeUnit.SECONDS))

            // A callback from S1 must be rejected by the client's connection generation guard.
            oldSocket.sendUnknownResponse(requestId, responseSessionId = oldSessionId)
            assertFalse("stale S1 response completed a request owned by S2", current.isDone)
            assertEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())

            sockets.sockets.last().sendResponseForRequest(requestId, responseSessionId = currentSessionId)
            assertTrue(current.get(2, TimeUnit.SECONDS) is RelayCallResult.Success)
            assertEquals("the current request identity must be preserved", requestId,
                sockets.requests.single().getString("request_id"))
            assertEquals("a stale callback must not cause a retry", 1, sockets.requests.size)
        } finally {
            transport.close()
            executor.shutdownNow()
        }
    }

    @Test(timeout = 7_000)
    fun queuedNotSentRequestKeepsItsIdentityAcrossReconnect() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(
            staticPair,
            serverCapabilities = setOf("coach.chat", "plan.revision"),
        )
        val gate = AgentConsentGate()
        val transport = transport(
            credentials,
            sockets,
            requestedCapabilities = setOf("plan.revision", "coach.chat"),
            consentGate = gate,
            maxReconnectAttempts = 1,
            reconnectBaseMillis = 1,
        )
        val requestId = uuid(79)
        val workerReachedGate = CountDownLatch(1)
        val result = AtomicReference<RelayCallResult?>()
        val workerDone = CountDownLatch(1)
        gate.beforeLockAttemptForTest = {
            if (Thread.currentThread().name == "relay-unsent-request") workerReachedGate.countDown()
        }
        try {
            transport.start()
            val firstSocket = sockets.sockets.single()
            gate.withLock {
                Thread({
                    try {
                        result.set(transport.executeDeferred(
                            operation = "coach.chat.request",
                            timeoutMillis = 4_000,
                            idempotencyKey = requestId,
                            requestId = requestId,
                            payloadFactory = { JSONObject().put("message", "queued synthetic message") },
                        ))
                    } finally {
                        workerDone.countDown()
                    }
                }, "relay-unsent-request").apply { isDaemon = true; start() }

                assertTrue("request did not queue before its first gate wait", workerReachedGate.await(1, TimeUnit.SECONDS))
                assertTrue("request was unexpectedly sent before reconnect", sockets.requests.isEmpty())
                firstSocket.remoteClose()
                assertTrue("replacement session did not connect", sockets.secondSessionConnected.await(2, TimeUnit.SECONDS))
                assertTrue("request must still be queued while G is held", sockets.requests.isEmpty())
            }

            assertTrue("queued request did not dispatch after reconnect", sockets.requestCaptured.await(2, TimeUnit.SECONDS))
            assertTrue("queued request did not finish", workerDone.await(2, TimeUnit.SECONDS))
            assertTrue(result.get() is RelayCallResult.Success)
            assertEquals("the unsent request identity must survive reconnect", requestId,
                sockets.requests.single().getString("request_id"))
            assertEquals("the idempotency key must not be regenerated", requestId,
                sockets.requests.single().getString("idempotency_key"))
            assertEquals("one queued request has one socket write", 1, sockets.requests.size)
        } finally {
            transport.close()
        }
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

    @Test(timeout = 5_000)
    fun timeoutWaitingForConsentGateReturnsNotSentWithinTheRequestDeadline() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(
            staticPair,
            dropRequestResponses = true,
            serverCapabilities = setOf("coach.chat", "plan.revision"),
        )
        val gate = AgentConsentGate()
        val transport = transport(
            credentials,
            sockets,
            requestedCapabilities = setOf("plan.revision", "coach.chat"),
            consentGate = gate,
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            transport.start()
            val startedAt = System.nanoTime()
            val result = gate.withLock {
                executor.submit<RelayCallResult> {
                    transport.executeDeferred(
                        operation = "coach.chat.request",
                        timeoutMillis = 250,
                        idempotencyKey = uuid(77),
                        requestId = uuid(77),
                        payloadFactory = { JSONObject().put("message", "must not be sent") },
                    )
                }.get(1, TimeUnit.SECONDS)
            }
            assertEquals(
                RelayCallResult.Failure.Reason.TIMEOUT_NOT_SENT,
                (result as RelayCallResult.Failure).reason,
            )
            assertTrue(
                "pre-dispatch gate wait must respect the request deadline",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 900,
            )
            assertTrue("a request denied before the gate opened must never be written", sockets.requests.isEmpty())
        } finally {
            transport.close()
            executor.shutdownNow()
        }
    }

    @Test(timeout = 7_000)
    fun closeDuringBeforeDispatchNeverWritesTheRequest() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(
            staticPair,
            dropRequestResponses = true,
            serverCapabilities = setOf("coach.chat", "plan.revision"),
        )
        val transport = transport(credentials, sockets, requestedCapabilities = setOf("plan.revision", "coach.chat"))
        val beforeDispatchEntered = CountDownLatch(1)
        val releaseBeforeDispatch = CountDownLatch(1)
        val requestResult = AtomicReference<RelayCallResult?>()
        val requestDone = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val closeDone = CountDownLatch(1)
        try {
            transport.start()
            Thread({
                try {
                    requestResult.set(transport.executeDeferred(
                        operation = "coach.chat.request",
                        timeoutMillis = 3_000,
                        idempotencyKey = uuid(78),
                        requestId = uuid(78),
                        beforeDispatch = {
                            beforeDispatchEntered.countDown()
                            check(releaseBeforeDispatch.await(3, TimeUnit.SECONDS)) { "test release timed out" }
                            true
                        },
                        payloadFactory = { JSONObject().put("message", "must not be sent") },
                    ))
                } finally {
                    requestDone.countDown()
                }
            }, "relay-close-before-dispatch").apply { isDaemon = true; start() }
            assertTrue("request did not enter beforeDispatch", beforeDispatchEntered.await(2, TimeUnit.SECONDS))

            val closer = Thread({
                closeStarted.countDown()
                transport.close()
                closeDone.countDown()
            }, "relay-close-during-dispatch").apply { isDaemon = true; start() }
            assertTrue("close thread did not start", closeStarted.await(1, TimeUnit.SECONDS))
            val closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (System.nanoTime() < closeDeadline && closer.state != Thread.State.BLOCKED) Thread.yield()
            assertEquals("close must observe the in-progress transport critical section", Thread.State.BLOCKED, closer.state)

            releaseBeforeDispatch.countDown()
            assertTrue("request did not finish after close was requested", requestDone.await(2, TimeUnit.SECONDS))
            assertTrue("close did not finish after beforeDispatch exited", closeDone.await(2, TimeUnit.SECONDS))
            assertEquals(RelayCallResult.Failure(RelayCallResult.Failure.Reason.OFFLINE), requestResult.get())
            assertTrue("a request closed before socket write must never be sent", sockets.requests.isEmpty())
        } finally {
            releaseBeforeDispatch.countDown()
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
        maxReconnectAttempts: Int = 0,
        reconnectBaseMillis: Long = 500,
    ) = RelayBackendTransport(
        config = RelayConfig(
            "ws://127.0.0.1:4400",
            "atlas-test",
            maxReconnectAttempts = maxReconnectAttempts,
            reconnectBaseMillis = reconnectBaseMillis,
            reconnectMaxMillis = reconnectBaseMillis,
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
