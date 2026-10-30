package dev.atlas.coach.sync.agent.coach

import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.ProposalLevel
import dev.atlas.coach.sync.agent.ProposalV2
import dev.atlas.coach.sync.agent.context.AgentContextAssembler
import dev.atlas.coach.sync.agent.context.AgentContextDomainViews
import dev.atlas.coach.sync.agent.context.AgentContextSnapshot
import dev.atlas.coach.sync.agent.context.AgentContextDomainConsent
import dev.atlas.coach.sync.relay.AtlasRemoteProtocol
import dev.atlas.coach.sync.relay.RelayCallResult
import dev.atlas.coach.sync.relay.RelayCredentials
import dev.atlas.coach.sync.relay.RelayProtocolException
import dev.atlas.coach.sync.CoreV4ConsentRecord
import dev.atlas.coach.sync.v4.CoreV4Category
import dev.atlas.coach.sync.v4.CoreV4ConsentSnapshot
import dev.atlas.coach.sync.v4.CoreV4ConsentState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64
import java.util.UUID
import dev.atlas.coach.app.V4_CHAT_DEFAULT_ENABLED
import dev.atlas.coach.app.relayChatCapabilities
import dev.atlas.coach.app.v4ChatSharing

class RelayAgentChatClientTest {
    @Test
    fun relayPayloadUsesSharedBuilderAndFreshMinimizedConsentedContext() {
        val now = 1_800_000_000_000L
        val mandateAtClick = AgentMandate.default().copy(sharedDataCategories = setOf("focus", "goals"))
        val currentMandate = mandateAtClick.copy(sharedDataCategories = setOf("goals"))
        val clickSnapshot = context(mandateAtClick, now, mapOf("focus" to now - 100, "goals" to now - 100))
        val requestContext = AgentRequestContext(
            mandate = currentMandate,
            snapshot = clickSnapshot,
            consentAtUtcMillis = mapOf("goals" to now - 100),
            domainConsent = AgentContextDomainConsent(),
            nowUtcMillis = now,
        )
        var sentBody: JSONObject? = null
        var auditedSnapshot: AgentContextSnapshot? = null
        var auditCount = 0
        var freshContextCalls = 0
        var executorEntered = false
        var auditCompletedBeforeExecutor = false
        var keySeen: String? = null
        val client = RelayAgentChatClient(
            executor = RelayChatRequestExecutor { _, _, idempotencyKey, payloadFactory, beforeDispatch ->
                executorEntered = true
                assertTrue("audit must complete before relay executor invocation", auditCompletedBeforeExecutor)
                assertEquals("fresh context must be prepared before relay executor invocation", 1, freshContextCalls)
                assertEquals(1, auditCount)
                assertTrue(beforeDispatch())
                assertEquals("beforeDispatch must not repeat the audit", 1, auditCount)
                assertEquals("beforeDispatch must not rebuild fresh context", 1, freshContextCalls)
                keySeen = idempotencyKey
                sentBody = payloadFactory()
                RelayCallResult.Success(chatResult(), replayed = false)
            },
            freshRequestContext = { freshContextCalls++; requestContext },
            onContextWritten = {
                auditCount++
                auditCompletedBeforeExecutor = !executorEntered
                auditedSnapshot = it
                true
            },
            requestAllowed = { true },
        )

        val idempotencyKey = "chat-send-unique-1"
        val response = client.chat("conversation-1", "synthetic message", mandateAtClick, clickSnapshot, idempotencyKey)
        val minimized = requestContext.minimizedSnapshot()
        val expected = AgentChatRequestBody.build("conversation-1", "synthetic message", currentMandate, minimized)

        assertEquals(idempotencyKey, keySeen)
        assertEquals(expected.toString(), sentBody?.toString())
        assertEquals(listOf("goals"), (0 until requireNotNull(sentBody).getJSONObject("shared_context")
            .getJSONArray("consented_categories").length()).map { sentBody!!.getJSONObject("shared_context")
            .getJSONArray("consented_categories").getString(it) })
        assertEquals(minimized.toJsonString(), auditedSnapshot?.toJsonString())
        assertEquals(1, auditCount)
        assertEquals(minimized.toJsonString(), response.sharedContextJson)
        assertEquals(AgentStatus.CONNECTED, response.status)
        assertEquals(1, freshContextCalls)
    }

    @Test
    fun capabilityUnavailableIsReturnedAfterPreDispatchAudit() {
        var audits = 0
        var executorCalls = 0
        var operation: String? = null
        val mandate = AgentMandate.default()
        val context = context(mandate, 1_800_000_000_000L, emptyMap())
        val client = RelayAgentChatClient(
            executor = RelayChatRequestExecutor { requestedOperation, _, _, _, _ ->
                operation = requestedOperation
                executorCalls++
                RelayCallResult.RemoteError("OPERATION_UNAVAILABLE", "remote token must not be shown", false)
            },
            freshRequestContext = { requestContext(mandate, context, 1_800_000_000_000L) },
            onContextWritten = { audits++; true },
            v4ChatEnabled = true,
        )

        val error = runCatching {
            client.chat(null, "synthetic", mandate, context, "chat-send-unsupported")
        }.exceptionOrNull()

        val chatError = error as? AgentChatException ?: throw AssertionError("Expected an agent chat error")
        assertEquals(AgentChatFailureKind.OPERATION_UNAVAILABLE, chatError.kind)
        assertEquals("OPERATION_UNAVAILABLE", chatError.remoteCode)
        assertEquals("v4.coach.chat.request", operation)
        val errorMessage = requireNotNull(chatError.message)
        assertTrue(errorMessage.contains("aucun repli V2"))
        assertFalse(errorMessage.contains("token"))
        assertEquals(1, audits)
        assertEquals(1, executorCalls)
    }

    @Test
    fun revokedOrExpiredFreshMandateBlocksBeforePayloadAndAudit() {
        val now = 1_800_000_000_000L
        val mandate = AgentMandate.default()
        val context = context(mandate, now, emptyMap())
        listOf(
            mandate.copy(revoked = true),
            mandate.copy(suspended = true),
            mandate.copy(expiresAtUtcMillis = now),
        ).forEach { invalidMandate ->
            var executorCalls = 0
            var payloadCalls = 0
            var audits = 0
            val client = RelayAgentChatClient(
                executor = RelayChatRequestExecutor { _, _, _, payloadFactory, beforeDispatch ->
                    executorCalls++
                    if (!beforeDispatch()) RelayCallResult.Failure(RelayCallResult.Failure.Reason.POLICY_DENIED)
                    else {
                        payloadCalls++
                        payloadFactory()
                        RelayCallResult.Success(chatResult(), replayed = false)
                    }
                },
                freshRequestContext = { requestContext(invalidMandate, context, now) },
                onContextWritten = { audits++; true },
            )

            val error = runCatching { client.chat(null, "synthetic", mandate, context, UUID.randomUUID().toString()) }
                .exceptionOrNull()

            assertTrue(error is AgentChatException)
            assertEquals(AgentChatFailureKind.POLICY_DENIED, (error as AgentChatException).kind)
            assertEquals(0, executorCalls)
            assertEquals(0, payloadCalls)
            assertEquals(0, audits)
        }
    }

    @Test
    fun revokedCategoryAfterPreparationBlocksThePreparedPayloadAtDispatch() {
        val now = 1_800_000_000_000L
        val mandate = AgentMandate.default().copy(sharedDataCategories = setOf("focus", "goals"))
        val context = context(mandate, now, mapOf("focus" to now - 100, "goals" to now - 100))
        val prepared = requestContext(mandate, context, now)
        var consentChecks = 0
        var executorCalls = 0
        var payloadCalls = 0
        val client = RelayAgentChatClient(
            executor = RelayChatRequestExecutor { _, _, _, payloadFactory, beforeDispatch ->
                executorCalls++
                if (!beforeDispatch()) {
                    RelayCallResult.Failure(RelayCallResult.Failure.Reason.POLICY_DENIED)
                } else {
                    payloadCalls++
                    payloadFactory()
                    RelayCallResult.Success(chatResult(), replayed = false)
                }
            },
            freshRequestContext = { prepared },
            onContextWritten = { true },
            snapshotStillAuthorized = {
                consentChecks++
                false
            },
        )

        val error = runCatching {
            client.chat(null, "synthetic", mandate, context, "stale-consent-key")
        }.exceptionOrNull()

        assertTrue(error is AgentChatException)
        assertEquals(AgentChatFailureKind.POLICY_DENIED, (error as AgentChatException).kind)
        assertEquals(1, executorCalls)
        assertEquals(0, payloadCalls)
        assertEquals(1, consentChecks)
    }

    @Test
    fun timeoutIsAnUncertainResultAndMalformedResponseIsExplicit() {
        val now = 1_800_000_000_000L
        val mandate = AgentMandate.default()
        val context = context(mandate, now, emptyMap())
        var timeoutMillisSeen: Long? = null
        var timeoutCalls = 0
        val timeoutClient = RelayAgentChatClient(
            executor = RelayChatRequestExecutor { _, timeoutMillis, _, _, beforeDispatch ->
                timeoutMillisSeen = timeoutMillis
                timeoutCalls++
                assertTrue(beforeDispatch())
                RelayCallResult.Failure(RelayCallResult.Failure.Reason.TIMEOUT)
            },
            freshRequestContext = { requestContext(mandate, context, now) },
            onContextWritten = { true },
        )
        val timeout = runCatching { timeoutClient.chat(null, "synthetic", mandate, context, "stable-timeout-key") }.exceptionOrNull()
        assertEquals(AgentChatFailureKind.RESULT_UNCERTAIN, (timeout as AgentChatException).kind)
        assertEquals(RelayAgentChatClient.RELAY_CHAT_TIMEOUT_MILLIS, timeoutMillisSeen)
        assertEquals(55_000L, timeoutMillisSeen)
        assertTrue("relay chat timeout must be below the 60-second envelope TTL", requireNotNull(timeoutMillisSeen) < 60_000L)
        assertEquals("timeout remains uncertain and is never automatically replayed", 1, timeoutCalls)

        val malformedClient = RelayAgentChatClient(
            executor = RelayChatRequestExecutor { _, _, _, _, beforeDispatch ->
                assertTrue(beforeDispatch())
                RelayCallResult.Success(JSONObject().put("reply", "x".repeat(4_001)).put("proposals", JSONArray())
                    .put("agent_status", "connected"), replayed = false)
            },
            freshRequestContext = { requestContext(mandate, context, now) },
            onContextWritten = { true },
        )
        val malformed = runCatching { malformedClient.chat(null, "synthetic", mandate, context, "stable-malformed-key") }
            .exceptionOrNull()
        assertEquals(AgentChatFailureKind.INVALID_RESPONSE, (malformed as AgentChatException).kind)
    }

    @Test
    fun relayCancellationRemainsExplicitAfterSinglePreDispatchAudit() {
        val now = 1_800_000_000_000L
        val mandate = AgentMandate.default()
        val context = context(mandate, now, emptyMap())
        var auditCount = 0
        var executorCalls = 0
        var auditBeforeExecutor = false
        val client = RelayAgentChatClient(
            executor = RelayChatRequestExecutor { _, _, _, _, beforeDispatch ->
                executorCalls++
                assertTrue(auditBeforeExecutor)
                assertEquals(1, auditCount)
                assertTrue(beforeDispatch())
                assertEquals("beforeDispatch must not repeat the audit", 1, auditCount)
                RelayCallResult.Failure(RelayCallResult.Failure.Reason.CANCELLED)
            },
            freshRequestContext = { requestContext(mandate, context, now) },
            onContextWritten = {
                auditCount++
                auditBeforeExecutor = executorCalls == 0
                true
            },
        )

        val error = runCatching { client.chat(null, "synthetic", mandate, context, "stable-cancel-key") }.exceptionOrNull()

        assertTrue(error is AgentChatException)
        assertEquals(AgentChatFailureKind.CANCELLED, (error as AgentChatException).kind)
        assertEquals(1, auditCount)
        assertEquals(1, executorCalls)
    }

    @Test
    fun failedContextAuditBlocksDispatchAndUnknownAgentStatusIsRejected() {
        val now = 1_800_000_000_000L
        val mandate = AgentMandate.default()
        val context = context(mandate, now, emptyMap())
        var payloadCalls = 0
        var sent = 0
        var executorCalls = 0
        val auditFailureClient = RelayAgentChatClient(
            executor = RelayChatRequestExecutor { _, _, _, payloadFactory, beforeDispatch ->
                executorCalls++
                if (!beforeDispatch()) RelayCallResult.Failure(RelayCallResult.Failure.Reason.POLICY_DENIED)
                else {
                    sent++
                    payloadCalls++
                    payloadFactory()
                    RelayCallResult.Success(chatResult(), replayed = false)
                }
            },
            freshRequestContext = { requestContext(mandate, context, now) },
            onContextWritten = { false },
        )
        val auditError = runCatching {
            auditFailureClient.chat(null, "synthetic", mandate, context, "audit-failure-key")
        }.exceptionOrNull()
        assertTrue(auditError is AgentChatException)
        assertEquals(AgentChatFailureKind.POLICY_DENIED, (auditError as AgentChatException).kind)
        assertEquals(0, executorCalls)
        assertEquals(0, sent)
        assertEquals(0, payloadCalls)

        val malformedStatusClient = RelayAgentChatClient(
            executor = RelayChatRequestExecutor { _, _, _, _, beforeDispatch ->
                assertTrue(beforeDispatch())
                RelayCallResult.Success(chatResult().put("agent_status", "connected-but-unknown"), replayed = false)
            },
            freshRequestContext = { requestContext(mandate, context, now) },
            onContextWritten = { true },
        )
        val statusError = runCatching {
            malformedStatusClient.chat(null, "synthetic", mandate, context, "bad-status-key")
        }.exceptionOrNull()
        assertEquals(AgentChatFailureKind.INVALID_RESPONSE, (statusError as AgentChatException).kind)
    }

    @Test
    fun parsesRelayReplyProposalsAndAgentStatus() {
        val now = 1_800_000_000_000L
        val mandate = AgentMandate.default()
        val context = context(mandate, now, emptyMap())
        val proposal = ProposalV2(
            schemaVersion = 2,
            proposalId = UUID.randomUUID().toString(),
            idempotencyKey = "proposal-chat-test-1",
            conversationId = "conversation-1",
            mandateId = mandate.id,
            mandateVersion = mandate.version,
            contextVersion = "ctx-test",
            createdAtUtcMillis = now,
            expiresAtUtcMillis = now + ProposalV2.MAX_TTL_MILLIS,
            actionType = "focus.propose",
            parameters = mapOf("focus_minutes" to 25, "break_minutes" to 5, "cycles" to 1),
            reason = "Synthetic proposal",
            requiredLevel = ProposalLevel.CONFIRM,
        )
        val executor = RelayChatRequestExecutor { _, _, _, _, beforeDispatch ->
            assertTrue(beforeDispatch())
            RelayCallResult.Success(chatResult(proposal), replayed = false)
        }
        val client = RelayAgentChatClient(
            executor = executor,
            freshRequestContext = { requestContext(mandate, context, now) },
            onContextWritten = { true },
        )

        val response = client.chat("conversation-1", "synthetic", mandate, context, "chat-send-proposal")

        assertEquals("A next step.", response.reply)
        assertEquals(1, response.proposals.size)
        assertEquals(AgentStatus.CONNECTED, response.status)
    }

    @Test
    fun offlineMockConversationRemainsLocalAndProposalRequiresConfirmation() {
        val now = 1_800_000_000_000L
        val mandate = AgentMandate.default()
        val context = context(mandate, now, emptyMap())
        val response = LocalMockAgentChatClient { now }
            .chat("conversation-local", "synthetic prompt", mandate, context, "local-send-1")

        assertEquals(AgentStatus.MOCK, response.status)
        assertTrue(response.reply.contains("fonctionne localement"))
        assertEquals(1, response.proposals.size)
        assertEquals(ProposalLevel.CONFIRM, response.proposals.single().requiredLevel)
        assertTrue(response.proposals.single().idempotencyKey.startsWith("local-"))
    }

    @Test
    fun sharedBuilderBoundsMessagesAndOnlyIncludesConsentCategories() {
        val mandate = AgentMandate.default().copy(sharedDataCategories = setOf("goals"))
        val now = 1_800_000_000_000L
        val context = context(mandate, now, mapOf("goals" to now - 10))
        val error = runCatching {
            AgentChatRequestBody.build(null, "x".repeat(2_001), mandate, context)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)

        val body = AgentChatRequestBody.build(null, "synthetic", mandate, context)
        assertEquals(JSONObject.NULL, body.get("conversation_id"))
        assertEquals(listOf("goals"), (0 until body.getJSONObject("shared_context").getJSONArray("consented_categories").length())
            .map { body.getJSONObject("shared_context").getJSONArray("consented_categories").getString(it) })
        assertFalse(body.getJSONObject("shared_context").toString().contains("focus"))
    }

    @Test
    fun v4CapabilityIsOptInAndNegotiatesOnlyTheFixedChatOperation() {
        assertFalse(V4_CHAT_DEFAULT_ENABLED)
        assertEquals(setOf("plan.revision", "coach.chat"), relayChatCapabilities(false))
        assertEquals(setOf("plan.revision", "v4.coach.chat"), relayChatCapabilities(true))
        assertEquals(AtlasRemoteProtocol.PROTOCOL, AtlasRemoteProtocol.protocolForCapabilities(relayChatCapabilities(false)))
        assertEquals(AtlasRemoteProtocol.PROTOCOL_V2, AtlasRemoteProtocol.protocolForCapabilities(relayChatCapabilities(true)))
        assertEquals("v4.coach.chat", AtlasRemoteProtocol.requiredCapability("v4.coach.chat.request"))
        assertTrue(setOf("HTTP_401", "HTTP_403", "HTTP_408", "HTTP_409", "HTTP_422", "HTTP_503")
            .all { it in AtlasRemoteProtocol.ERROR_CODES })

        val credential = RelayCredentials(
            deviceId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            deviceToken = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)),
            remoteStaticPublicKey = ByteArray(32),
        )
        val now = Instant.parse("2026-10-30T12:00:00Z")
        val v4Protocol = AtlasRemoteProtocol.protocolForCapabilities(relayChatCapabilities(true))
        val hello = AtlasRemoteProtocol.hello(
            credential,
            "v4-test",
            relayChatCapabilities(true),
            now,
            protocol = v4Protocol,
        )
        val negotiatedRequest = AtlasRemoteProtocol.request(
            requestId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            sessionId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            operation = "v4.coach.chat.request",
            idempotencyKey = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
            createdAt = now,
            expiresAt = now.plusSeconds(30),
            payload = AgentChatV4RequestBody.build(
                conversationId = "conversation-v4",
                message = "synthetic request",
                requestId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                sharing = AgentChatV4Sharing.EMPTY,
                sentAt = now,
            ),
            protocol = v4Protocol,
        )
        assertEquals(AtlasRemoteProtocol.PROTOCOL_V2, hello.getString("protocol"))
        assertEquals(setOf("plan.revision", "v4.coach.chat"),
            (0 until hello.getJSONArray("capabilities").length()).map { hello.getJSONArray("capabilities").getString(it) }.toSet())
        assertEquals(AtlasRemoteProtocol.PROTOCOL_V2, negotiatedRequest.getString("protocol"))
        val parsedV2Request = AtlasRemoteProtocol.parse(AtlasRemoteProtocol.encode(negotiatedRequest), v4Protocol)
        assertEquals(AtlasRemoteProtocol.PROTOCOL_V2, parsedV2Request.getString("protocol"))
        assertEquals("v4.coach.chat.request", parsedV2Request.getString("operation"))
        assertEquals("v4.proposal.ack", AtlasRemoteProtocol.requiredCapability("v4.proposals.pull"))
        val v1HelloFailure = runCatching {
            AtlasRemoteProtocol.hello(credential, "v1-test", relayChatCapabilities(true), now)
        }.exceptionOrNull()
        assertTrue("V1 must reject V4 capabilities", v1HelloFailure is IllegalArgumentException)
        val arbitraryRouteFailure = runCatching {
            AtlasRemoteProtocol.request(
                requestId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                sessionId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                operation = "/v2/agent/chat",
                idempotencyKey = "fixed-route-test",
                createdAt = now,
                expiresAt = now.plusSeconds(30),
                payload = JSONObject(),
            )
        }.exceptionOrNull()
        assertTrue("The request builder must reject arbitrary routes", arbitraryRouteFailure is RelayProtocolException)
        val v4UnderV1Failure = runCatching {
            AtlasRemoteProtocol.request(
                requestId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                sessionId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                operation = "v4.coach.chat.request",
                idempotencyKey = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                createdAt = now,
                expiresAt = now.plusSeconds(30),
                payload = JSONObject(),
            )
        }.exceptionOrNull()
        assertTrue("V1 must reject V4 operations", v4UnderV1Failure is RelayProtocolException)
    }

    @Test
    fun v4ChatUsesStrictContractAndSurfacesBackendToolStateAndProposalIdsWithoutV2Fallback() {
        val now = 1_800_000_000_000L
        val mandate = AgentMandate.default()
        val context = context(mandate, now, emptyMap())
        val requestId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        val proposalId = "ffffffff-ffff-4fff-8fff-ffffffffffff"
        val sharing = AgentChatV4Sharing(3, setOf("DAY_PLAN", "GOAL_PROGRESS"))
        var operation: String? = null
        var calls = 0
        var auditedCategories: List<String>? = null
        var sentBody: JSONObject? = null
        val client = RelayAgentChatClient(
            executor = RelayChatRequestExecutor { requestedOperation, _, key, payloadFactory, beforeDispatch ->
                calls++
                operation = requestedOperation
                assertEquals("v4.coach.chat.request", requestedOperation)
                assertEquals(requestId, key)
                assertTrue(beforeDispatch())
                sentBody = payloadFactory()
                RelayCallResult.Success(v4ChatResult(requestId, proposalId, "partial", 6), replayed = false)
            },
            freshRequestContext = { requestContext(mandate, context, now) },
            onContextWritten = { auditedCategories = it.consentedCategories; true },
            v4ChatEnabled = true,
            v4Sharing = { sharing },
            v4SharingStillAuthorized = { it == sharing },
        )

        val response = client.chat("conversation-v4", "synthetic message", mandate, context, requestId)

        assertEquals(1, calls)
        assertEquals("v4.coach.chat.request", operation)
        assertEquals(
            setOf("contract_version", "schema_version", "request_id", "capability", "sent_at", "conversation_id", "message", "consent_version", "shared_categories"),
            requireNotNull(sentBody).keys().asSequence().toSet(),
        )
        assertEquals(4, sentBody?.getInt("contract_version"))
        assertEquals("coach.chat", sentBody?.getString("capability"))
        assertEquals(requestId, sentBody?.getString("request_id"))
        assertEquals(setOf("DAY_PLAN", "GOAL_PROGRESS"),
            (0 until requireNotNull(sentBody).getJSONArray("shared_categories").length())
                .map { sentBody!!.getJSONArray("shared_categories").getString(it) }.toSet())
        assertFalse(sentBody?.has("shared_context") == true)
        assertEquals(emptyList<String>(), auditedCategories)
        assertEquals(AgentStatus.CONNECTED, response.status)
        assertTrue(response.reply.contains("État des outils : partial (6 appel(s))"))
        assertTrue(response.reply.contains("$proposalId"))
        assertTrue(response.reply.contains("Contenu à synchroniser via la boîte de réception"))
        assertFalse(response.reply.contains("pour le détail"))
        assertTrue(response.proposals.isEmpty())
        assertTrue(response.reply.length <= 4_000)
    }

    @Test
    fun v4SharingUsesOnlyGrantedAndSyncedCategoriesAtOneConsentVersion() {
        val sharing = v4ChatSharing(listOf(
            consentRecord(CoreV4Category.DAY_PLAN, 2, CoreV4ConsentState.GRANTED, 2),
            consentRecord(CoreV4Category.GOAL_PROGRESS, 2, CoreV4ConsentState.GRANTED, 2),
            consentRecord(CoreV4Category.TRAINING_PLAN, 1, CoreV4ConsentState.GRANTED, 1),
            consentRecord(CoreV4Category.USAGE_SUMMARY, 3, CoreV4ConsentState.GRANTED, 2),
            consentRecord(CoreV4Category.ACTIVITY_STATUS, 3, CoreV4ConsentState.REVOKED, 3),
        ))

        assertEquals(2, sharing.consentVersion)
        assertEquals(setOf("DAY_PLAN", "GOAL_PROGRESS"), sharing.sharedCategories)
    }

    @Test
    fun v4ConsentRevocationBlocksDispatchAndRemoteErrorsAreTypedAndSanitized() {
        val now = 1_800_000_000_000L
        val mandate = AgentMandate.default()
        val context = context(mandate, now, emptyMap())
        val sharing = AgentChatV4Sharing(2, setOf("GOAL_PROGRESS"))
        var payloadCalls = 0
        val revokedAtDispatch = RelayAgentChatClient(
            executor = RelayChatRequestExecutor { _, _, _, payloadFactory, beforeDispatch ->
                if (!beforeDispatch()) {
                    RelayCallResult.Failure(RelayCallResult.Failure.Reason.POLICY_DENIED)
                } else {
                    payloadCalls++
                    payloadFactory()
                    RelayCallResult.Success(v4ChatResult(
                        "11111111-1111-4111-8111-111111111111",
                        "44444444-4444-4444-8444-444444444444",
                        "none",
                        0,
                    ), replayed = false)
                }
            },
            freshRequestContext = { requestContext(mandate, context, now) },
            onContextWritten = { true },
            v4ChatEnabled = true,
            v4Sharing = { sharing },
            v4SharingStillAuthorized = { false },
        )
        val consentError = runCatching {
            revokedAtDispatch.chat("conversation-v4", "synthetic", mandate, context,
                "11111111-1111-4111-8111-111111111111")
        }.exceptionOrNull()
        assertEquals(AgentChatFailureKind.POLICY_DENIED, (consentError as AgentChatException).kind)
        assertEquals(0, payloadCalls)

        val typedCases = listOf(
            "HTTP_401" to AgentChatFailureKind.AUTHENTICATION_REQUIRED,
            "HTTP_403" to AgentChatFailureKind.CONSENT_DENIED,
            "HTTP_408" to AgentChatFailureKind.RESULT_UNCERTAIN,
            "HTTP_409" to AgentChatFailureKind.CONFLICT,
            "HTTP_422" to AgentChatFailureKind.INVALID_REQUEST,
            "HTTP_503" to AgentChatFailureKind.SERVICE_UNAVAILABLE,
        )
        typedCases.forEach { (code, expectedKind) ->
            var operation: String? = null
            val client = RelayAgentChatClient(
                executor = RelayChatRequestExecutor { requestedOperation, _, _, _, _ ->
                    operation = requestedOperation
                    RelayCallResult.RemoteError(code, "Bearer secret-must-not-appear", false)
                },
                freshRequestContext = { requestContext(mandate, context, now) },
                onContextWritten = { true },
                v4ChatEnabled = true,
                v4Sharing = { sharing },
            )
            val error = runCatching {
                client.chat("conversation-v4", "synthetic", mandate, context,
                    "22222222-2222-4222-8222-222222222222")
            }.exceptionOrNull() as AgentChatException
            assertEquals(expectedKind, error.kind)
            assertFalse(error.message.orEmpty().contains("secret-must-not-appear"))
            assertEquals("v4.coach.chat.request", operation)
        }
    }

    @Test
    fun v4MalformedResponseIsRejectedWithoutExposingRemoteText() {
        val now = 1_800_000_000_000L
        val mandate = AgentMandate.default()
        val context = context(mandate, now, emptyMap())
        val client = RelayAgentChatClient(
            executor = RelayChatRequestExecutor { _, _, _, _, beforeDispatch ->
                assertTrue(beforeDispatch())
                RelayCallResult.Success(JSONObject().put("reply", "bad token=secret"), replayed = false)
            },
            freshRequestContext = { requestContext(mandate, context, now) },
            onContextWritten = { true },
            v4ChatEnabled = true,
        )
        val error = runCatching {
            client.chat("conversation-v4", "synthetic", mandate, context,
                "33333333-3333-4333-8333-333333333333")
        }.exceptionOrNull() as AgentChatException
        assertEquals(AgentChatFailureKind.INVALID_RESPONSE, error.kind)
        assertFalse(error.message.orEmpty().contains("secret"))
    }

    private fun context(
        mandate: AgentMandate,
        now: Long,
        consentDates: Map<String, Long>,
    ): AgentContextSnapshot = AgentContextAssembler().assemble(
        currentMandate = mandate,
        consentAtUtcMillis = consentDates,
        domains = AgentContextDomainViews(),
        nowUtcMillis = now,
    )

    private fun requestContext(mandate: AgentMandate, context: AgentContextSnapshot, now: Long) = AgentRequestContext(
        mandate = mandate,
        snapshot = context,
        consentAtUtcMillis = mandate.sharedDataCategories.associateWith { now - 10 },
        domainConsent = AgentContextDomainConsent(),
        nowUtcMillis = now,
    )

    private fun chatResult(proposal: ProposalV2? = null): JSONObject = JSONObject()
        .put("reply", "A next step.")
        .put("proposals", JSONArray().also { proposals -> proposal?.let { proposals.put(it.toJson()) } })
        .put("agent_status", "connected")

    private fun v4ChatResult(
        requestId: String,
        proposalId: String,
        toolState: String,
        toolCallCount: Int,
    ): JSONObject = JSONObject()
        .put("contract_version", 4)
        .put("schema_version", 4)
        .put("request_id", requestId)
        .put("reply", "A V4 next step.")
        .put("proposal_ids", JSONArray().put(proposalId))
        .put("agent_status", "ok")
        .put("tool_state", toolState)
        .put("tool_call_count", toolCallCount)

    private fun consentRecord(
        category: CoreV4Category,
        version: Int,
        state: CoreV4ConsentState,
        syncedVersion: Int,
    ) = CoreV4ConsentRecord(
        snapshot = CoreV4ConsentSnapshot(category, version, state, syncedVersion),
        requestId = UUID.randomUUID().toString(),
        idempotencyKey = UUID.randomUUID().toString(),
        sentAt = "2026-10-30T12:00:00Z",
    )
}
