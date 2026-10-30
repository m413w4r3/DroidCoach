package dev.atlas.coach.sync.v4

import dev.atlas.coach.app.relayChatCapabilities
import dev.atlas.coach.app.relaySessionCapabilities
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4HttpTransport
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4HttpException
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4HttpFailureKind
import dev.atlas.coach.sync.relay.AtlasRemoteProtocol
import dev.atlas.coach.sync.relay.RelayCallResult
import dev.atlas.coach.sync.relay.RelayBackendTransport
import dev.atlas.coach.sync.relay.RelayConnectionState
import dev.atlas.coach.sync.relay.RelayConfig
import dev.atlas.coach.sync.relay.RelayCredentials
import dev.atlas.coach.sync.relay.InMemoryRelayCredentialStore
import dev.atlas.coach.sync.relay.RelayTransportMode
import dev.atlas.coach.sync.relay.FakeRelaySocketFactory
import dev.atlas.coach.sync.relay.testCredentials
import dev.atlas.coach.sync.agent.coach.RelayBackendChatRequestExecutor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.time.Instant
import java.util.Base64

class RelayCoreV4HttpTransportTest {
    @Test
    fun v2NegotiatesFixedV4SyncCapabilitiesWithinTheConnectorCeiling() {
        val sessionCapabilities = relaySessionCapabilities(v4ChatEnabled = false)
        assertEquals(setOf("plan.revision", "coach.chat"), relayChatCapabilities(v4ChatEnabled = false))
        assertEquals(9, sessionCapabilities.size)
        assertTrue(sessionCapabilities.all { it in AtlasRemoteProtocol.HELLO_CAPABILITIES })

        val operationCapabilities = mapOf(
            "v4.plan.snapshot.upsert" to "v4.day_plan.snapshot.write",
            "v4.activity-status.events.append" to "v4.activity_status.write",
            "v4.usage.summary.upsert" to "v4.usage_summary.write",
            "v4.goal.snapshot.upsert" to "v4.goal_progress.write",
            "v4.training.snapshot.upsert" to "v4.training_plan.write",
            "v4.proposals.pull" to "v4.proposal.ack",
            "v4.proposals.ack" to "v4.proposal.ack",
            "v4.consent.state.upsert" to "v4.consent.state.write",
        )
        operationCapabilities.forEach { (operation, capability) ->
            assertTrue(AtlasRemoteProtocol.supportsOperation(AtlasRemoteProtocol.PROTOCOL_V2, operation))
            assertEquals(capability, AtlasRemoteProtocol.requiredCapability(operation))
            assertFalse(AtlasRemoteProtocol.supportsOperation(AtlasRemoteProtocol.PROTOCOL, operation))
        }

        val credentials = RelayCredentials(
            deviceId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            deviceToken = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)),
            remoteStaticPublicKey = ByteArray(32),
        )
        val hello = AtlasRemoteProtocol.hello(
            credentials = credentials,
            clientVersion = "0.1.0-v7",
            requestedCapabilities = AtlasRemoteProtocol.HELLO_CAPABILITIES,
            now = Instant.parse("2026-10-30T12:00:00Z"),
            protocol = AtlasRemoteProtocol.PROTOCOL_V2,
        )
        assertEquals(AtlasRemoteProtocol.MAX_V2_HELLO_CAPABILITIES, hello.getJSONArray("capabilities").length())
        assertEquals(AtlasRemoteProtocol.PROTOCOL_V2, hello.getString("protocol"))

        val helloOk = JSONObject()
            .put("protocol", AtlasRemoteProtocol.PROTOCOL_V2)
            .put("kind", "hello_ok")
            .put("session_id", "cccccccc-cccc-4ccc-8ccc-cccccccccccc")
            .put("server_time", "2026-10-30T12:00:00Z")
            .put("expires_in_seconds", 3600)
            .put("capabilities", JSONArray(AtlasRemoteProtocol.HELLO_CAPABILITIES.sorted()))
            .put("max_request_bytes", AtlasRemoteProtocol.MAX_REQUEST_BYTES)
            .put("max_response_bytes", AtlasRemoteProtocol.MAX_RESPONSE_BYTES)
        AtlasRemoteProtocol.validate(helloOk, AtlasRemoteProtocol.PROTOCOL_V2)
    }

    @Test
    fun mapsExactlyTheEightFixedCoreRoutesAndDoesNotForwardCoreBearer() {
        val session = FakeSession()
        val transport = RelayCoreV4HttpTransport({ session })
        val proposalId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val cases = listOf(
            "/v4/sync/day-plan-snapshots" to "v4.plan.snapshot.upsert",
            "/v4/sync/activity-status-events" to "v4.activity-status.events.append",
            "/v4/sync/usage-summaries" to "v4.usage.summary.upsert",
            "/v4/sync/goal-progress-snapshots" to "v4.goal.snapshot.upsert",
            "/v4/sync/training-snapshots" to "v4.training.snapshot.upsert",
            "/v4/sync/consent-state" to "v4.consent.state.upsert",
            "/v4/agent/proposals/pull" to "v4.proposals.pull",
            "/v4/agent/proposals/$proposalId/ack" to "v4.proposals.ack",
        )

        cases.forEachIndexed { index, (path, operation) ->
            val body = JSONObject()
                .put("request_id", uuid(index + 1))
                .put("idempotency_key", uuid(index + 11))
            if (operation == "v4.proposals.ack") {
                body.put("ack_id", uuid(index + 21)).put("proposal_id", proposalId)
            }
            transport.post(CoreV4HttpRequest(path, "android-core-bearer-marker", body.toString()))
            val call = session.calls.last()
            assertEquals(operation, call.operation)
            assertEquals(body.getString("request_id"), call.requestId)
            assertEquals(body.toString(), call.payload.toString())
            assertFalse(call.payload.has("bearer"))
            assertFalse(call.payload.has("bearer_token"))
            assertFalse(call.payload.toString().contains("android-core-bearer-marker"))
        }

        assertEquals(cases.size, session.calls.size)
        assertEquals("v4.proposals.ack", session.calls.last().operation)
        assertEquals(proposalId, session.calls.last().payload.getString("proposal_id"))
        assertEquals(uuid(28), session.calls.last().idempotencyKey)
        assertFalse(transport.requiresBearerToken)
    }

    @Test
    fun v4RoutesAndChatSatisfyThePcRelayRulesAndAckReplayIsIdempotent() {
        val (credentials, staticPair) = testCredentials()
        val capabilities = setOf(
            "v4.day_plan.snapshot.write",
            "v4.proposal.ack",
            "v4.coach.chat",
        )
        val sockets = FakeRelaySocketFactory(staticPair, serverCapabilities = capabilities)
        val backend = RelayBackendTransport(
            config = RelayConfig(
                relayUrl = "ws://127.0.0.1:4400",
                serverId = "atlas-test",
                maxReconnectAttempts = 0,
                requestedCapabilities = capabilities,
            ),
            credentialStore = InMemoryRelayCredentialStore(credentials),
            socketFactory = sockets,
        )
        val transport = RelayCoreV4HttpTransport({ RelayBackendCoreV4Session(backend) })
        val snapshotRequestId = uuid(101)
        val pullRequestId = uuid(102)
        val chatRequestId = uuid(103)
        val ackId = uuid(104)
        val proposalId = uuid(105)
        val ackPayload = JSONObject().put("ack_id", ackId).put("proposal_id", proposalId)

        try {
            backend.start()
            transport.post(
                CoreV4HttpRequest(
                    "/v4/sync/day-plan-snapshots",
                    "android-core-bearer-marker",
                    JSONObject().put("request_id", snapshotRequestId)
                        .put("idempotency_key", uuid(111)).put("snapshot", JSONObject()).toString(),
                ),
            )
            transport.post(
                CoreV4HttpRequest(
                    "/v4/agent/proposals/pull",
                    "android-core-bearer-marker",
                    JSONObject().put("request_id", pullRequestId).put("cursor", JSONObject.NULL).toString(),
                ),
            )
            val chatExecutor = RelayBackendChatRequestExecutor(backend)
            val chatResult = chatExecutor.execute(
                operation = "v4.coach.chat.request",
                timeoutMillis = 8_000,
                idempotencyKey = chatRequestId,
                payloadFactory = {
                    JSONObject().put("request_id", chatRequestId)
                        .put("idempotency_key", chatRequestId).put("message", "synthetic")
                },
                beforeDispatch = { true },
                requestId = chatRequestId,
            )
            assertTrue(chatResult is RelayCallResult.Success)
            transport.post(
                CoreV4HttpRequest(
                    "/v4/agent/proposals/$proposalId/ack",
                    "android-core-bearer-marker",
                    ackPayload.toString(),
                ),
            )

            val requests = sockets.requests.toList()
            assertEquals(4, requests.size)
            assertPcRequest(requests[0], snapshotRequestId, uuid(111))
            assertPcRequest(requests[1], pullRequestId, pullRequestId)
            assertPcRequest(requests[2], chatRequestId, chatRequestId)
            assertPcRequest(requests[3], null, ackId)
            assertTrue(AtlasRemoteProtocol.isUuid(requests[3].getString("request_id")))
            assertTrue(requests.all { !it.getJSONObject("payload").toString().contains("android-core-bearer-marker") })

            val replay = backend.executeDeferred(
                operation = "v4.proposals.ack",
                idempotencyKey = ackId,
                requestId = uuid(106),
                payloadFactory = { JSONObject(ackPayload.toString()) },
            )
            assertTrue(replay is RelayCallResult.Success && replay.replayed)
            assertEquals(uuid(106), sockets.requests.last().getString("request_id"))
            assertEquals(ackId, sockets.requests.last().getString("idempotency_key"))
        } finally {
            transport.cancel()
            backend.close()
        }
    }

    @Test
    fun refusesUnknownAndMalformedAckPathsBeforeRelayCall() {
        val session = FakeSession()
        val transport = RelayCoreV4HttpTransport({ session })

        val unknown = assertThrows(CoreV4SyncException::class.java) {
            transport.post(CoreV4HttpRequest("https://core.example/v4/sync/day-plan-snapshots", "", "{}"))
        }
        assertEquals(CoreV4FailureKind.CONFIGURATION, unknown.kind)

        val mismatchedAck = assertThrows(CoreV4SyncException::class.java) {
            transport.post(
                CoreV4HttpRequest(
                    "/v4/agent/proposals/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/ack",
                    "",
                    JSONObject().put("proposal_id", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
                        .put("ack_id", uuid(5)).toString(),
                ),
            )
        }
        assertEquals(CoreV4FailureKind.PROTOCOL, mismatchedAck.kind)

        val missingRequestId = assertThrows(CoreV4SyncException::class.java) {
            transport.post(
                CoreV4HttpRequest(
                    "/v4/sync/day-plan-snapshots",
                    "",
                    JSONObject().put("idempotency_key", uuid(6)).toString(),
                ),
            )
        }
        assertEquals("REQUEST_ID_REQUIRED", missingRequestId.errorCode)

        val invalidRequestId = assertThrows(CoreV4SyncException::class.java) {
            transport.post(
                CoreV4HttpRequest(
                    "/v4/sync/day-plan-snapshots",
                    "",
                    JSONObject().put("request_id", "not-a-uuid").put("idempotency_key", uuid(7)).toString(),
                ),
            )
        }
        assertEquals("REQUEST_ID_INVALID", invalidRequestId.errorCode)

        val blankIdempotencyKey = assertThrows(CoreV4SyncException::class.java) {
            transport.post(
                CoreV4HttpRequest(
                    "/v4/sync/day-plan-snapshots",
                    "",
                    JSONObject().put("request_id", uuid(8)).put("idempotency_key", "").toString(),
                ),
            )
        }
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", blankIdempotencyKey.errorCode)
        assertTrue(session.calls.isEmpty())
    }

    @Test
    fun requiresConnectedSessionAndNegotiatedOperationCapability() {
        val path = "/v4/sync/day-plan-snapshots"
        val body = JSONObject().put("request_id", uuid(1)).put("idempotency_key", uuid(2)).toString()

        val disconnected = FakeSession(state = RelayConnectionState.OFFLINE)
        val offline = assertThrows(CoreV4SyncException::class.java) {
            RelayCoreV4HttpTransport({ disconnected }).post(CoreV4HttpRequest(path, "", body))
        }
        assertEquals(CoreV4FailureKind.OFFLINE, offline.kind)
        assertTrue(disconnected.calls.isEmpty())

        val noCapability = FakeSession(capabilities = emptySet())
        val unavailable = assertThrows(CoreV4SyncException::class.java) {
            RelayCoreV4HttpTransport({ noCapability }).post(CoreV4HttpRequest(path, "", body))
        }
        assertEquals(CoreV4FailureKind.UNAVAILABLE, unavailable.kind)
        assertEquals("CAPABILITY_UNAVAILABLE", unavailable.errorCode)
        assertTrue(noCapability.calls.isEmpty())

        val unpaired = FakeSession(state = RelayConnectionState.PAIRING_REQUIRED)
        val pairingRequired = assertThrows(CoreV4SyncException::class.java) {
            RelayCoreV4HttpTransport({ unpaired }).post(CoreV4HttpRequest(path, "", body))
        }
        assertEquals(CoreV4FailureKind.AUTHENTICATION, pairingRequired.kind)
        assertTrue(unpaired.calls.isEmpty())

        val noSession = RelayCoreV4HttpTransport({ null })
        val missingSession = assertThrows(CoreV4SyncException::class.java) {
            noSession.post(CoreV4HttpRequest(path, "", body))
        }
        assertEquals(CoreV4FailureKind.OFFLINE, missingSession.kind)
    }

    @Test
    fun relaySuccessBodiesAreParsedByTheSyncClientAndInbox() {
        val session = FakeSession { operation, payload ->
            when (operation) {
                "v4.consent.state.upsert" -> JSONObject()
                    .put("contract_version", 4)
                    .put("schema_version", 4)
                    .put("request_id", payload.getString("request_id"))
                    .put("capability", "consent.state.write")
                    .put("category", "DAY_PLAN")
                    .put("consent_version", 1)
                    .put("state", "GRANTED")
                    .put("updated_at", "2026-10-30T10:00:00Z")
                    .put("source", "ANDROID_SYNC")
                    .put("replayed", false)
                    .put("result_code", "ACCEPTED")
                "v4.proposals.pull" -> JSONObject()
                    .put("contract_version", 4)
                    .put("schema_version", 4)
                    .put("request_id", payload.getString("request_id"))
                    .put("capability", "proposal.ack")
                    .put("proposals", JSONArray())
                    .put("next_cursor", JSONObject.NULL)
                else -> error("Unexpected test operation: $operation")
            }
        }
        val transport = RelayCoreV4HttpTransport({ session })
        val client = CoreV4SyncClient(transport)
        val requestId = uuid(40)
        val consentBody = client.consentBody(
            category = CoreV4Category.DAY_PLAN,
            version = 1,
            state = CoreV4ConsentState.GRANTED,
            requestId = requestId,
            idempotencyKey = uuid(41),
            sentAt = "2026-10-30T10:00:00Z",
        )
        val receipt = client.sendConsent(consentBody, bearerToken = "")
        assertEquals(CoreV4ConsentState.GRANTED, receipt.state)

        val inbox = InboxV4HttpTransport(
            baseUrl = null,
            bearerToken = "",
            consentVersion = 1,
            debuggable = false,
            poster = transport,
            sessionAuthenticated = true,
        )
        val page = inbox.pullRaw(cursor = null, pageSize = InboxV4HttpTransport.PAGE_SIZE)
        assertTrue(page.proposals.isEmpty())
        assertEquals(2, session.calls.size)
    }

    @Test
    fun inboxRetainsRelayFailureKindAndRemoteCodeWithoutDirectFallback() {
        val session = FakeSession(result = RelayCallResult.RemoteError("HTTP_403", "denied", false))
        val relay = RelayCoreV4HttpTransport({ session })
        val direct = CountingDirectTransport()
        val inbox = InboxV4HttpTransport(
            baseUrl = null,
            bearerToken = "",
            consentVersion = 1,
            debuggable = false,
            poster = relay,
            sessionAuthenticated = true,
        )

        val error = assertThrows(InboxV4HttpException::class.java) {
            inbox.pullRaw(cursor = null, pageSize = InboxV4HttpTransport.PAGE_SIZE)
        }
        assertEquals(InboxV4HttpFailureKind.CONSENT, error.kind)
        assertEquals(403, error.statusCode)
        assertEquals("HTTP_403", error.errorCode)
        assertEquals(1, session.calls.size)
        assertEquals(0, direct.calls)
    }

    @Test
    fun cancellationInterruptsTheSingleInFlightRelayRequest() {
        val enteredRelay = CountDownLatch(1)
        val releaseRelay = CountDownLatch(1)
        val failure = AtomicReference<CoreV4SyncException?>()
        val session = object : RelayCoreV4Session {
            override val state = RelayConnectionState.RELAY_CONNECTED
            override fun hasCapability(capability: String) = true
            override fun executeDeferred(
                operation: String,
                timeoutMillis: Long,
                idempotencyKey: String,
                requestId: String,
                payloadFactory: () -> JSONObject,
                beforeDispatch: () -> Boolean,
            ): RelayCallResult {
                enteredRelay.countDown()
                return try {
                    releaseRelay.await()
                    RelayCallResult.Success(JSONObject(), replayed = false)
                } catch (_: InterruptedException) {
                    RelayCallResult.Failure(RelayCallResult.Failure.Reason.CANCELLED)
                }
            }
        }
        val transport = RelayCoreV4HttpTransport({ session })
        val body = JSONObject().put("request_id", uuid(80)).put("idempotency_key", uuid(81)).toString()
        val thread = Thread {
            runCatching {
                transport.post(CoreV4HttpRequest("/v4/sync/day-plan-snapshots", "", body))
            }.onFailure { failure.set(it as? CoreV4SyncException) }
        }
        thread.start()
        assertTrue(enteredRelay.await(2, TimeUnit.SECONDS))
        transport.cancel()
        thread.join(2_000)
        assertFalse(thread.isAlive)
        assertEquals(CoreV4FailureKind.OFFLINE, failure.get()?.kind)
    }

    @Test
    fun mapsRelayErrorsToTypedFailuresAndNeverFallsBackToDirectTransport() {
        val session = FakeSession(result = RelayCallResult.RemoteError("HTTP_409", "conflict", false))
        val relay = RelayCoreV4HttpTransport({ session })
        val direct = CountingDirectTransport()
        val selected = selectCoreV4HttpTransport(RelayTransportMode.RELAY, direct, relay)
        assertSame(relay, selected)

        val error = assertThrows(CoreV4SyncException::class.java) {
            val client = CoreV4SyncClient(selected)
            val body = client.consentBody(
                category = CoreV4Category.DAY_PLAN,
                version = 1,
                state = CoreV4ConsentState.GRANTED,
                requestId = uuid(70),
                idempotencyKey = uuid(71),
                sentAt = "2026-10-30T10:00:00Z",
            )
            client.sendConsent(body, bearerToken = "android-core-token")
        }
        assertEquals(CoreV4FailureKind.CONFLICT, error.kind)
        assertEquals(409, error.httpStatus)
        assertEquals("HTTP_409", error.errorCode)
        assertFalse(error.retryable)
        assertEquals(0, direct.calls)
        assertEquals(1, session.calls.size)

        val idempotencyConflict = RelayCallResult.RemoteError("IDEMPOTENCY_CONFLICT", "", false)
        val mapped = assertThrows(CoreV4SyncException::class.java) { idempotencyConflict.raiseForTest() }
        assertEquals(CoreV4FailureKind.IDEMPOTENCY_CONFLICT, mapped.kind)
    }

    @Test
    fun v2HttpMetadataAndRemoteCodeKeepTheCoreFailureClassification() {
        val session = FakeSession(
            result = RelayCallResult.RemoteError(
                code = "CORE_UNAVAILABLE",
                message = "Relay is unavailable.",
                retryable = true,
                httpStatus = 503,
                remoteCode = "CORE_UNAVAILABLE",
            ),
        )
        val error = assertThrows(CoreV4SyncException::class.java) {
            RelayCoreV4HttpTransport({ session }).post(
                CoreV4HttpRequest(
                    "/v4/sync/consent-state",
                    "",
                    JSONObject().put("request_id", uuid(90)).put("idempotency_key", uuid(91)).toString(),
                ),
            )
        }
        assertEquals(CoreV4FailureKind.UNAVAILABLE, error.kind)
        assertEquals(503, error.httpStatus)
        assertEquals("CORE_UNAVAILABLE", error.errorCode)
        assertTrue(error.retryable)
        assertEquals(1, session.calls.size)

        val rejectedSession = FakeSession(
            result = RelayCallResult.RemoteError(
                code = "VALIDATION_ERROR",
                message = "Relay rejected the request.",
                retryable = false,
                httpStatus = 422,
                remoteCode = "VALIDATION_ERROR",
            ),
        )
        val rejected = assertThrows(CoreV4SyncException::class.java) {
            RelayCoreV4HttpTransport({ rejectedSession }).post(
                CoreV4HttpRequest(
                    "/v4/sync/consent-state",
                    "",
                    JSONObject().put("request_id", uuid(92)).put("idempotency_key", uuid(93)).toString(),
                ),
            )
        }
        assertEquals(CoreV4FailureKind.REJECTED, rejected.kind)
        assertEquals(422, rejected.httpStatus)
        assertEquals("VALIDATION_ERROR", rejected.errorCode)
    }

    @Test
    fun selectsDirectOnlyForDirectModeAndOfflineNeverUsesDirect() {
        val direct = CountingDirectTransport()
        val offlineSession = FakeSession(state = RelayConnectionState.OFFLINE)
        val relay = RelayCoreV4HttpTransport({ offlineSession })
        assertSame(direct, selectCoreV4HttpTransport(RelayTransportMode.DIRECT, direct, relay))
        val offline = selectCoreV4HttpTransport(RelayTransportMode.OFFLINE, direct, relay)
        assertSame(CoreV4OfflineHttpTransport, offline)
        assertThrows(CoreV4SyncException::class.java) {
            offline.post(
                CoreV4HttpRequest(
                    "/v4/sync/consent-state",
                    "",
                    JSONObject().put("request_id", uuid(94)).put("idempotency_key", uuid(95)).toString(),
                ),
            )
        }
        assertTrue(offlineSession.calls.isEmpty())
        assertEquals(0, direct.calls)
    }

    private data class CapturedCall(
        val operation: String,
        val idempotencyKey: String,
        val requestId: String,
        val payload: JSONObject,
    )

    private class FakeSession(
        override var state: RelayConnectionState = RelayConnectionState.RELAY_CONNECTED,
        private val capabilities: Set<String> = AtlasRemoteProtocol.HELLO_CAPABILITIES.filter { it.startsWith("v4.") }.toSet(),
        private val result: RelayCallResult = RelayCallResult.Success(JSONObject(), replayed = false),
        private val resultFactory: ((String, JSONObject) -> JSONObject)? = null,
    ) : RelayCoreV4Session {
        val calls = mutableListOf<CapturedCall>()

        constructor(resultFactory: (String, JSONObject) -> JSONObject) : this(
            state = RelayConnectionState.RELAY_CONNECTED,
            capabilities = AtlasRemoteProtocol.HELLO_CAPABILITIES.filter { it.startsWith("v4.") }.toSet(),
            result = RelayCallResult.Success(JSONObject(), replayed = false),
            resultFactory = resultFactory,
        )

        override fun hasCapability(capability: String): Boolean = capability in capabilities

        override fun executeDeferred(
            operation: String,
            timeoutMillis: Long,
            idempotencyKey: String,
            requestId: String,
            payloadFactory: () -> JSONObject,
            beforeDispatch: () -> Boolean,
        ): RelayCallResult {
            assertEquals(8_000L, timeoutMillis)
            if (!beforeDispatch()) return RelayCallResult.Failure(RelayCallResult.Failure.Reason.POLICY_DENIED)
            val payload = payloadFactory()
            calls += CapturedCall(operation, idempotencyKey, requestId, payload)
            return resultFactory?.invoke(operation, payload)?.let { RelayCallResult.Success(it, false) } ?: result
        }
    }

    private class CountingDirectTransport : CoreV4HttpTransport {
        var calls = 0
        override fun post(request: CoreV4HttpRequest): CoreV4HttpResponse {
            calls += 1
            return CoreV4HttpResponse(200, "{}")
        }
    }

    private fun uuid(number: Int): String =
        "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"

    private fun assertPcRequest(envelope: JSONObject, payloadRequestId: String?, expectedIdempotencyKey: String) {
        assertEquals(expectedIdempotencyKey, envelope.getString("idempotency_key"))
        if (payloadRequestId != null) assertEquals(payloadRequestId, envelope.getString("request_id"))
    }

    private fun RelayCallResult.RemoteError.raiseForTest(): Nothing {
        val session = FakeSession(result = this)
        RelayCoreV4HttpTransport({ session }).post(
            CoreV4HttpRequest(
                "/v4/sync/day-plan-snapshots",
                "",
                JSONObject().put("request_id", uuid(60)).put("idempotency_key", uuid(61)).toString(),
            ),
        )
        error("Expected relay error")
    }
}
