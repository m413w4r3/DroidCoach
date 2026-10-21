package dev.atlas.coach.sync.agent.coach

import dev.atlas.coach.sync.BearerTokenProvider
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.ProposalLevel
import dev.atlas.coach.sync.agent.ProposalV2
import dev.atlas.coach.coach.proactive.CoachTrigger
import dev.atlas.coach.coach.proactive.CoachTriggerReliability
import dev.atlas.coach.coach.proactive.CoachTriggerSource
import dev.atlas.coach.coach.proactive.ProactiveEngagementMode
import dev.atlas.coach.coach.proactive.ProactivePlanRequest
import dev.atlas.coach.sync.agent.context.AgentContextAssembler
import dev.atlas.coach.sync.agent.context.AgentContextDomainViews
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import java.net.SocketTimeoutException

class AgentApiClientTest {
    @Test
    fun revokedFreshContextBlocksPlanBeforeRequestBodyIsWritten() {
        var written = false
        ServerSocket(0).use { server ->
            server.soTimeout = 300
            val mandate = AgentMandate.default()
            val context = AgentContextAssembler().assemble(
                mandate,
                emptyMap(),
                AgentContextDomainViews(),
                1_800_000_000_000L,
            )
            val client = AgentApiClient(
                baseUrl = "http://127.0.0.1:${server.localPort}",
                tokenProvider = BearerTokenProvider { "test-token" },
                freshRequestContext = { null },
                onContextWritten = { written = true },
            )
            val failure = runCatching { client.plan("conversation-1", mandate, context) }.exceptionOrNull()

            assertTrue(failure is AgentApiException)
            assertEquals(403, (failure as AgentApiException).statusCode)
            assertFalse(written)
            assertTrue(runCatching { server.accept() }.exceptionOrNull() is SocketTimeoutException)
        }
    }

    @Test
    fun centralizedRequestGateBlocksInboxBeforeTokenLookupOrSocketCreation() {
        var tokenLookups = 0
        ServerSocket(0).use { server ->
            server.soTimeout = 300
            val client = AgentApiClient(
                baseUrl = "http://127.0.0.1:${server.localPort}",
                tokenProvider = BearerTokenProvider { tokenLookups++; "test-token" },
                requestAllowed = { false },
            )

            val failure = runCatching { client.pullInbox(null) }.exceptionOrNull()

            assertTrue(failure is AgentApiException)
            assertEquals(403, (failure as AgentApiException).statusCode)
            assertEquals(0, tokenLookups)
            assertTrue(runCatching { server.accept() }.exceptionOrNull() is SocketTimeoutException)
        }
    }

    @Test
    fun revokedProactivePlanAuthorizationBlocksRequestBeforeWrite() {
        ServerSocket(0).use { server ->
            server.soTimeout = 300
            val mandate = AgentMandate.default()
            val context = AgentContextAssembler().assemble(
                mandate,
                emptyMap(),
                AgentContextDomainViews(),
                1_800_000_000_000L,
            )
            val client = AgentApiClient(
                baseUrl = "http://127.0.0.1:${server.localPort}",
                tokenProvider = BearerTokenProvider { "test-token" },
            )
            val failure = runCatching {
                client.plan(
                    conversationId = "conversation-1",
                    mandate = mandate,
                    context = context,
                    request = proactivePlanRequest(),
                    allowSend = { false },
                )
            }.exceptionOrNull()

            assertTrue(failure is AgentApiException)
            assertEquals(403, (failure as AgentApiException).statusCode)
            assertTrue(runCatching { server.accept() }.exceptionOrNull() is SocketTimeoutException)
        }
    }

    @Test
    fun executedAckSendsAcceptedTransitionBeforeEffect() {
        val requests = withAckResponses(listOf(200, 204)) { client ->
            client.acknowledgeExecution("proposal-1", "Directive applied", 1_800_000_123_000L)
        }

        assertEquals(listOf("accepted", "executed"), requests.map { JSONObject(it.body).getString("state") })
        assertEquals(JSONObject.NULL, JSONObject(requests[0].body).get("effect"))
        assertEquals("Directive applied", JSONObject(requests[1].body).getString("effect"))
    }

    @Test
    fun executedAckResumesAfterAcceptanceWasAlreadyRecorded() {
        val requests = withAckResponses(listOf(409, 204)) { client ->
            client.acknowledgeExecution("proposal-1", "Directive applied", 1_800_000_123_000L)
        }

        assertEquals(listOf("accepted", "executed"), requests.map { JSONObject(it.body).getString("state") })
    }

    @Test
    fun proactiveAckAuthorizationRejectsBeforeHttpRequestOrBodySerialization() {
        var authorizationChecks = 0
        ServerSocket(0).use { server ->
            server.soTimeout = 300
            val client = AgentApiClient(
                baseUrl = "http://127.0.0.1:${server.localPort}",
                tokenProvider = BearerTokenProvider { "test-token" },
                requestAllowed = { true },
            )

            val failure = runCatching {
                client.acknowledge(
                    proposalId = "proposal-1",
                    state = "executed",
                    effect = "Directive applied",
                    atUtcMillis = 1_800_000_123_000L,
                    authorizationAllowed = { authorizationChecks++; false },
                )
            }.exceptionOrNull()

            assertTrue(failure is AgentApiException)
            assertEquals(403, (failure as AgentApiException).statusCode)
            assertEquals(1, authorizationChecks)
            assertTrue("stale acknowledgement opened an HTTP connection", runCatching { server.accept() }.exceptionOrNull() is SocketTimeoutException)
        }
    }

    @Test
    fun executedAckRechecksAuthorizationBetweenAcceptedAndExecuted() {
        var authorizationChecks = 0
        val requests = withAckResponses(listOf(204)) { client ->
            val failure = runCatching {
                client.acknowledgeExecution(
                    proposalId = "proposal-1",
                    effect = "Directive applied",
                    atUtcMillis = 1_800_000_123_000L,
                    authorizationAllowed = { ++authorizationChecks == 1 },
                )
            }.exceptionOrNull()

            assertTrue(failure is AgentApiException)
            assertEquals(403, (failure as AgentApiException).statusCode)
        }

        assertEquals(2, authorizationChecks)
        assertEquals("only accepted may cross the HTTP boundary", listOf("accepted"), requests.map { JSONObject(it.body).getString("state") })
    }

    @Test
    fun chatInboxAndAckUseExistingBearerAndV2ContractOverHttpStub() {
        val proposal = ProposalV2(
            schemaVersion = 2,
            proposalId = UUID.randomUUID().toString(),
            idempotencyKey = "stub-proposal-1",
            conversationId = "conversation-1",
            mandateId = "agent-local-v2",
            mandateVersion = 1,
            contextVersion = "ctx-1",
            createdAtUtcMillis = 1_800_000_000_000L,
            expiresAtUtcMillis = 1_800_000_600_000L,
            actionType = "focus.propose",
            parameters = mapOf("focus_minutes" to 25, "break_minutes" to 5, "cycles" to 1),
            reason = "Un démarrage court est adapté.",
            requiredLevel = ProposalLevel.CONFIRM,
        )
        val chat = JSONObject()
            .put("reply", "Je propose une session courte.")
            .put("proposals", JSONArray().put(proposal.toJson()))
            .put("agent_status", "connected")
        val plan = JSONObject()
            .put("reply", "Votre prochaine étape est prête.")
            .put("proposals", JSONArray())
            .put("agent_status", "connected")
        val inbox = JSONObject()
            .put("proposals", JSONArray().put(proposal.toJson()))
            .put("cursor", "cursor-2")
        val requests = Collections.synchronizedList(mutableListOf<StubRequest>())
        ServerSocket(0).use { server ->
            server.soTimeout = 8_000
            val worker = Thread {
                val responses = listOf(chat.toString(), plan.toString(), plan.toString(), inbox.toString(), "")
                responses.forEachIndexed { index, responseBody ->
                    server.accept().use { socket ->
                        socket.soTimeout = 8_000
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
                        val requestLine = reader.readLine().orEmpty()
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            val split = line.indexOf(':')
                            if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
                        }
                        val length = headers["content-length"]?.toIntOrNull() ?: 0
                        val bodyChars = CharArray(length)
                        var read = 0
                        while (read < length) {
                            val count = reader.read(bodyChars, read, length - read)
                            if (count < 0) break
                            read += count
                        }
                        requests += StubRequest(requestLine, headers["authorization"], String(bodyChars, 0, read))
                        val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
                        val status = if (index == 4) "204 No Content" else "200 OK"
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                        output.write(bytes)
                        output.flush()
                    }
                }
            }.apply { isDaemon = true; start() }

            val client = AgentApiClient(
                "http://127.0.0.1:${server.localPort}",
                BearerTokenProvider { "test-token" },
            )
            val mandate = AgentMandate.default()
            val answer = client.chat("conversation-1", "Aide-moi a demarrer", mandate)
            val context = AgentContextAssembler().assemble(
                mandate,
                emptyMap(),
                AgentContextDomainViews(),
                1_800_000_000_000L,
            )
            val planAnswer = client.plan(
                "conversation-1",
                mandate,
                context,
            )
            val proactivePlanAnswer = client.plan(
                conversationId = "conversation-1",
                mandate = mandate,
                context = context,
                request = proactivePlanRequest(),
                allowSend = { true },
            )
            val pulled = client.pullInbox("cursor 1")
            client.acknowledge(proposal.proposalId, "executed", "Session proposée", 1_800_000_123_000L)
            worker.join(8_000)

            assertEquals(AgentStatus.CONNECTED, answer.status)
            assertEquals("Je propose une session courte.", answer.reply)
            assertEquals("Votre prochaine étape est prête.", planAnswer.reply)
            assertEquals("Votre prochaine étape est prête.", proactivePlanAnswer.reply)
            assertEquals(proposal, answer.proposals.single())
            assertEquals("cursor-2", pulled.cursor)
            assertEquals(5, requests.size)
            assertTrue(requests[0].requestLine.startsWith("POST /v2/agent/chat "))
            assertEquals("Bearer test-token", requests[0].authorization)
            val sentChat = JSONObject(requests[0].body)
            assertEquals("Aide-moi a demarrer", sentChat.getString("message"))
            val sharedContext = sentChat.getJSONObject("shared_context")
            assertEquals(0, sharedContext.getJSONArray("consented_categories").length())
            assertEquals(0, sharedContext.getJSONObject("categories").length())
            val mandateSummary = sentChat.getJSONObject("mandate_summary")
            assertTrue(mandateSummary.has("focus_window"))
            assertTrue(mandateSummary.has("app_policy_bounds"))
            assertTrue(!mandateSummary.has("shared_data_categories"))
            assertTrue(requests[1].requestLine.startsWith("POST /v2/agent/plan "))
            val sentPlan = JSONObject(requests[1].body)
            assertEquals(AgentApiClient.PLANNED_REQUEST, sentPlan.getString("message"))
            assertEquals(0, sentPlan.getJSONObject("shared_context").getJSONObject("categories").length())
            assertTrue(requests[2].requestLine.startsWith("POST /v2/agent/plan "))
            val programmaticMessage = JSONObject(JSONObject(requests[2].body).getString("message"))
            assertEquals(AgentApiClient.PLANNED_REQUEST, programmaticMessage.getString("request"))
            val planContext = programmaticMessage.getJSONObject("proactive_plan")
            assertEquals("reinforced", planContext.getString("mode"))
            val triggerSummaries = planContext.getJSONArray("triggers")
            assertEquals(2, triggerSummaries.length())
            assertEquals("focus_completed", triggerSummaries.getJSONObject(0).getString("source"))
            assertTrue(triggerSummaries.getJSONObject(0).has("occurred_at_utc"))
            assertEquals("habit_reminder_due", triggerSummaries.getJSONObject(1).getString("source"))
            assertFalse(programmaticMessage.toString().contains("private-event-id"))
            assertFalse(programmaticMessage.toString().contains("private-motif"))
            assertFalse(programmaticMessage.toString().contains("goal title"))
            assertTrue(requests[3].requestLine.contains("GET /v2/agent/inbox?after=cursor+1 "))
            assertTrue(requests[4].requestLine.startsWith("POST /v2/agent/proposals/"))
            assertEquals("executed", JSONObject(requests[4].body).getString("state"))
        }
    }

    private fun proactivePlanRequest() = ProactivePlanRequest(
        reservationId = "reservation-private-id",
        requestedAtUtcMillis = 1_800_000_000_000L,
        mode = ProactiveEngagementMode.REINFORCED,
        triggers = listOf(
            CoachTrigger(
                eventId = "private-event-id",
                source = CoachTriggerSource.FOCUS_COMPLETED,
                occurredAtUtcMillis = 1_800_000_000_000L,
                motifKey = "private-motif",
                reliability = CoachTriggerReliability.VERIFIED,
            ),
            CoachTrigger(
                eventId = "another-private-event-id",
                source = CoachTriggerSource.FOCUS_COMPLETED,
                occurredAtUtcMillis = 1_800_000_060_000L,
                motifKey = "private-motif",
                reliability = CoachTriggerReliability.VERIFIED,
            ),
            CoachTrigger(
                eventId = "private-habit-event-id",
                source = CoachTriggerSource.HABIT_REMINDER_DUE,
                occurredAtUtcMillis = 1_800_000_030_000L,
                reliability = CoachTriggerReliability.VERIFIED,
            ),
        ),
    )

    private fun withAckResponses(statuses: List<Int>, action: (AgentApiClient) -> Unit): List<StubRequest> {
        val requests = Collections.synchronizedList(mutableListOf<StubRequest>())
        ServerSocket(0).use { server ->
            server.soTimeout = 8_000
            val worker = Thread {
                statuses.forEach { status ->
                    server.accept().use { socket ->
                        socket.soTimeout = 8_000
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
                        val requestLine = reader.readLine().orEmpty()
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            val split = line.indexOf(':')
                            if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
                        }
                        val length = headers["content-length"]?.toIntOrNull() ?: 0
                        val bodyChars = CharArray(length)
                        var read = 0
                        while (read < length) {
                            val count = reader.read(bodyChars, read, length - read)
                            if (count < 0) break
                            read += count
                        }
                        requests += StubRequest(requestLine, headers["authorization"], String(bodyChars, 0, read))
                        val reason = if (status in 200..299) "OK" else "Conflict"
                        val body = if (status in 200..299) "" else "{}"
                        val bytes = body.toByteArray(StandardCharsets.UTF_8)
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                        output.write(bytes)
                        output.flush()
                    }
                }
            }.apply { isDaemon = true; start() }

            action(AgentApiClient(
                "http://127.0.0.1:${server.localPort}",
                BearerTokenProvider { "test-token" },
            ))
            worker.join(8_000)
            assertTrue("Ack stub did not finish", !worker.isAlive)
        }
        return requests.toList()
    }

    private data class StubRequest(val requestLine: String, val authorization: String?, val body: String)
}
