package dev.atlas.coach.sync.agent.coach

import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.ProposalLevel
import dev.atlas.coach.sync.agent.ProposalV2
import dev.atlas.coach.sync.agent.context.AgentContextAssembler
import dev.atlas.coach.sync.agent.context.AgentContextDomainViews
import dev.atlas.coach.sync.agent.context.AgentContextSnapshot
import dev.atlas.coach.sync.agent.context.AgentContextDomainConsent
import dev.atlas.coach.sync.relay.RelayCallResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

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
        var keySeen: String? = null
        val client = RelayAgentChatClient(
            executor = RelayChatRequestExecutor { _, idempotencyKey, payloadFactory, beforeDispatch ->
                assertTrue(beforeDispatch())
                keySeen = idempotencyKey
                sentBody = payloadFactory()
                RelayCallResult.Success(chatResult(), replayed = false)
            },
            freshRequestContext = { requestContext },
            onContextWritten = { auditCount++; auditedSnapshot = it; true },
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
    }

    @Test
    fun capabilityUnavailableDoesNotBuildPayloadOrAuditContext() {
        var audits = 0
        val mandate = AgentMandate.default()
        val context = context(mandate, 1_800_000_000_000L, emptyMap())
        val client = RelayAgentChatClient(
            executor = RelayChatRequestExecutor { _, _, _, _ ->
                RelayCallResult.RemoteError("OPERATION_UNAVAILABLE", "coach.chat is unavailable", false)
            },
            freshRequestContext = { requestContext(mandate, context, 1_800_000_000_000L) },
            onContextWritten = { audits++; true },
        )

        val error = runCatching {
            client.chat(null, "synthetic", mandate, context, "chat-send-unsupported")
        }.exceptionOrNull()

        assertTrue(error is AgentChatException)
        assertEquals(AgentChatFailureKind.OPERATION_UNAVAILABLE, (error as AgentChatException).kind)
        assertEquals("OPERATION_UNAVAILABLE", error.remoteCode)
        assertEquals(0, audits)
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
            var payloadCalls = 0
            var audits = 0
            val client = RelayAgentChatClient(
                executor = RelayChatRequestExecutor { _, _, payloadFactory, beforeDispatch ->
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
            assertEquals(0, payloadCalls)
            assertEquals(0, audits)
        }
    }

    @Test
    fun timeoutIsAnUncertainResultAndMalformedResponseIsExplicit() {
        val now = 1_800_000_000_000L
        val mandate = AgentMandate.default()
        val context = context(mandate, now, emptyMap())
        var timeoutMillisSeen: Long? = null
        var timeoutCalls = 0
        val timeoutClient = RelayAgentChatClient(
            executor = RelayChatRequestExecutor { timeoutMillis, _, _, beforeDispatch ->
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
            executor = RelayChatRequestExecutor { _, _, _, beforeDispatch ->
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
    fun failedContextAuditBlocksDispatchAndUnknownAgentStatusIsRejected() {
        val now = 1_800_000_000_000L
        val mandate = AgentMandate.default()
        val context = context(mandate, now, emptyMap())
        var payloadCalls = 0
        var sent = 0
        val auditFailureClient = RelayAgentChatClient(
            executor = RelayChatRequestExecutor { _, _, payloadFactory, beforeDispatch ->
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
        assertEquals(0, sent)
        assertEquals(0, payloadCalls)

        val malformedStatusClient = RelayAgentChatClient(
            executor = RelayChatRequestExecutor { _, _, _, beforeDispatch ->
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
        val executor = RelayChatRequestExecutor { _, _, _, beforeDispatch ->
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
}
