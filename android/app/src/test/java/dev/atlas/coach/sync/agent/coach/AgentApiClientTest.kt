package dev.atlas.coach.sync.agent.coach

import dev.atlas.coach.sync.BearerTokenProvider
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.ProposalLevel
import dev.atlas.coach.sync.agent.ProposalV2
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID

class AgentApiClientTest {
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
        val inbox = JSONObject()
            .put("proposals", JSONArray().put(proposal.toJson()))
            .put("cursor", "cursor-2")
        val requests = Collections.synchronizedList(mutableListOf<StubRequest>())
        ServerSocket(0).use { server ->
            server.soTimeout = 8_000
            val worker = Thread {
                val responses = listOf(chat.toString(), inbox.toString(), "")
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
                        val status = if (index == 2) "204 No Content" else "200 OK"
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
            val pulled = client.pullInbox("cursor 1")
            client.acknowledge(proposal.proposalId, "executed", "Session proposée", 1_800_000_123_000L)
            worker.join(8_000)

            assertEquals(AgentStatus.CONNECTED, answer.status)
            assertEquals("Je propose une session courte.", answer.reply)
            assertEquals(proposal, answer.proposals.single())
            assertEquals("cursor-2", pulled.cursor)
            assertEquals(3, requests.size)
            assertTrue(requests[0].requestLine.startsWith("POST /v2/agent/chat "))
            assertEquals("Bearer test-token", requests[0].authorization)
            val sentChat = JSONObject(requests[0].body)
            assertEquals("Aide-moi a demarrer", sentChat.getString("message"))
            assertTrue(sentChat.getJSONObject("shared_context").getJSONArray("categories").length() == 0)
            assertTrue(requests[1].requestLine.contains("GET /v2/agent/inbox?after=cursor+1 "))
            assertTrue(requests[2].requestLine.startsWith("POST /v2/agent/proposals/"))
            assertEquals("executed", JSONObject(requests[2].body).getString("state"))
        }
    }

    private data class StubRequest(val requestLine: String, val authorization: String?, val body: String)
}
