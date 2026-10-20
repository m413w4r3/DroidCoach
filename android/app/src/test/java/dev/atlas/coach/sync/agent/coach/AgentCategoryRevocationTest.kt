package dev.atlas.coach.sync.agent.coach

import dev.atlas.coach.data.LocalStateStore
import dev.atlas.coach.data.RevocationBarrier
import dev.atlas.coach.data.RevocationBarrierState
import dev.atlas.coach.data.RevocationBarrierStorage
import dev.atlas.coach.data.StateBackend
import dev.atlas.coach.goals.GoalMetric
import dev.atlas.coach.goals.GoalRecord
import dev.atlas.coach.sync.BearerTokenProvider
import dev.atlas.coach.sync.agent.AgentMandate
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
import java.util.concurrent.atomic.AtomicReference

class AgentCategoryRevocationTest {
    @Test
    fun successfulCategoryWithdrawalKeepsAgentAvailableAndOmitsCategoryFromPayload() {
        val barrierStorage = MemoryBarrierStorage()
        val barrier = RevocationBarrier(barrierStorage)
        val stateBackend = MemoryStateBackend()
        val stateStore = LocalStateStore(stateBackend)
        val oldMandate = mandateWithCategories(GOALS, FOCUS)
        stateStore.write(mapOf("agentMandate" to oldMandate.toJson().toString()))
        assertTrue(barrier.blockCategory(GOALS))
        stateStore.write(mapOf("agentMandate" to mandateWithCategories(FOCUS).toJson().toString()))
        val mandate = AgentMandate.fromJson(stateStore.read().getValue("agentMandate") as String)
        val coordinator = coordinator(mandate, barrier)

        val request = sendChat(coordinator, barrier, mandate)
        val requestBody = request.substringAfter('\n')
        val payload = JSONObject(requestBody).getJSONObject("shared_context")

        assertEquals("POST /v2/agent/chat HTTP/1.1", requestLine(request))
        assertEquals(listOf(FOCUS), jsonArrayStrings(payload.getJSONArray("consented_categories")))
        assertFalse(payload.getJSONObject("categories").has(GOALS))
        assertFalse(requestBody.contains("Synthetic revoked goal"))
    }

    @Test
    fun failedRootWriteAndRecreationFilterCategoryStillInRestoredMandate() {
        val backend = MemoryStateBackend()
        val stateStore = LocalStateStore(backend)
        val persistedMandate = mandateWithCategories(GOALS, FOCUS)
        stateStore.write(mapOf("agentMandate" to persistedMandate.toJson().toString()))

        val barrierStorage = MemoryBarrierStorage()
        assertTrue(RevocationBarrier(barrierStorage).blockCategory(GOALS))
        backend.failWrites = true
        val attemptedMandate = persistedMandate.copy(sharedDataCategories = setOf(FOCUS))
        assertTrue(runCatching {
            stateStore.write(mapOf("agentMandate" to attemptedMandate.toJson().toString()))
        }.isFailure)

        val restoredMandate = AgentMandate.fromJson(
            LocalStateStore(backend).read().getValue("agentMandate") as String,
        )
        assertEquals(setOf(GOALS, FOCUS), restoredMandate.sharedDataCategories)

        val recreatedBarrier = RevocationBarrier(barrierStorage)
        assertFalse(recreatedBarrier.isAgentBlocked())
        assertEquals(setOf(GOALS), recreatedBarrier.blockedCategories())
        val coordinator = coordinator(restoredMandate, recreatedBarrier)
        val request = sendChat(coordinator, recreatedBarrier, restoredMandate)
        val requestBody = request.substringAfter('\n')
        val payload = JSONObject(requestBody).getJSONObject("shared_context")

        assertEquals(setOf(FOCUS), coordinator.effectiveMandate(restoredMandate).sharedDataCategories)
        assertEquals(listOf(FOCUS), jsonArrayStrings(payload.getJSONArray("consented_categories")))
        assertFalse(payload.getJSONObject("categories").has(GOALS))
        assertFalse(requestBody.contains("Synthetic revoked goal"))
    }

    private fun coordinator(mandate: AgentMandate, barrier: RevocationBarrier): AgentContextCoordinator {
        val consentAt = NOW - 60_000L
        return AgentContextCoordinator(
            initialConsentAtUtcMillis = mapOf(GOALS to consentAt, FOCUS to consentAt),
            legacyConsentedCategories = emptySet(),
            mandateProvider = { mandate },
            domainsProvider = {
                AgentContextDomainViews(goals = listOf(
                    GoalRecord(
                        id = "fixture-goal",
                        title = "Synthetic revoked goal",
                        description = "Local test fixture",
                        metric = GoalMetric.MANUAL,
                        targetValue = 1,
                        createdAt = consentAt,
                        updatedAt = consentAt,
                    ),
                ))
            },
            nowUtcMillis = { NOW },
            persistConsentDates = {},
            agentAccessAllowed = { !barrier.isAgentBlocked() },
            showConsentedContext = { !barrier.isAgentBlocked() },
            blockedCategories = barrier::blockedCategories,
        )
    }

    private fun sendChat(
        coordinator: AgentContextCoordinator,
        barrier: RevocationBarrier,
        mandate: AgentMandate,
    ): String {
        val requestBody = AtomicReference<String?>()
        val serverFailure = AtomicReference<Throwable?>()
        ServerSocket(0).use { server ->
            server.soTimeout = 5_000
            val worker = Thread {
                runCatching {
                    server.accept().use { socket ->
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
                        val line = reader.readLine()
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                            val delimiter = header.indexOf(':')
                            if (delimiter > 0) headers[header.substring(0, delimiter).lowercase()] = header.substring(delimiter + 1).trim()
                        }
                        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                        val body = CharArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val count = reader.read(body, read, contentLength - read)
                            if (count < 0) break
                            read += count
                        }
                        requestBody.set("$line\n${String(body, 0, read)}")
                        val response = "{\"reply\":\"ok\",\"proposals\":[],\"agent_status\":\"connected\"}"
                        val bytes = response.toByteArray(StandardCharsets.UTF_8)
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                            write(bytes)
                            flush()
                        }
                    }
                }.onFailure(serverFailure::set)
            }.apply { isDaemon = true; start() }

            val context = coordinator.assembleCurrent()
            val client = AgentApiClient(
                baseUrl = "http://127.0.0.1:${server.localPort}",
                tokenProvider = BearerTokenProvider { "unit-test-token" },
                freshRequestContext = coordinator::requestContext,
                requestAllowed = {
                    !barrier.isAgentBlocked() && coordinator.effectiveMandate(mandate).sharedDataCategories.all { category ->
                        coordinator.consentDates()[category]?.let { it in 1L..NOW } == true
                    }
                },
            )
            client.chat("category-test", "Check remaining context", mandate, context)
            worker.join(5_000)
            assertFalse("HTTP stub did not finish", worker.isAlive)
        }
        serverFailure.get()?.let { throw AssertionError("HTTP stub failed", it) }
        return requireNotNull(requestBody.get())
    }

    private fun requestLine(request: String): String = request.substringBefore('\n')

    private fun jsonArrayStrings(array: JSONArray): List<String> =
        (0 until array.length()).map(array::getString)

    private fun mandateWithCategories(vararg categories: String) = AgentMandate.default().copy(
        sharedDataCategories = categories.toSet(),
    )

    private class MemoryStateBackend : StateBackend {
        var value: String? = null
        var failWrites = false
        override fun read(): String? = value
        override fun write(value: String) {
            check(!failWrites)
            this.value = value
        }
    }

    private class MemoryBarrierStorage : RevocationBarrierStorage {
        var state = RevocationBarrierState()
        override fun readState(): RevocationBarrierState = state
        override fun writeState(state: RevocationBarrierState): Boolean {
            this.state = state
            return true
        }
    }

    private companion object {
        const val GOALS = "goals"
        const val FOCUS = "focus"
        const val NOW = 1_800_000_000_000L
    }
}
