package dev.atlas.coach.sync.agent.coach

import dev.atlas.coach.data.LocalStateStore
import dev.atlas.coach.data.RevocationBarrier
import dev.atlas.coach.data.RevocationBarrierState
import dev.atlas.coach.data.RevocationBarrierStorage
import dev.atlas.coach.data.StateBackend
import dev.atlas.coach.focus.FocusPhase
import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.focus.FocusStatus
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
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    @Test
    fun revokingGoalsDuringAnInFlightCoachRequestRemovesThemFromTheNextRequest() {
        val consentAt = NOW - 60_000L
        val sessionId = "consented-focus-session"
        val stateStore = LocalStateStore(MemoryStateBackend())
        var mandate = mandateWithCategories(GOALS, FOCUS)
        stateStore.write(mapOf("agentMandate" to mandate.toJson().toString()))
        val barrierStorage = MemoryBarrierStorage()
        val barrier = RevocationBarrier(barrierStorage)
        val coordinator = AgentContextCoordinator(
            initialConsentAtUtcMillis = mapOf(GOALS to consentAt, FOCUS to consentAt),
            legacyConsentedCategories = emptySet(),
            mandateProvider = { mandate },
            domainsProvider = {
                AgentContextDomainViews(
                    focusSessions = listOf(finishedFocus(sessionId, consentAt + 1_000L)),
                    goals = listOf(
                        GoalRecord(
                            id = "consented-focus-goal",
                            title = "Fixture shared goal",
                            description = "Synthetic local goal",
                            metric = GoalMetric.FOCUS_MINUTES,
                            targetValue = 120,
                            linkedFocusSessionIds = setOf(sessionId),
                            createdAt = consentAt - 86_400_000L,
                            updatedAt = consentAt + 1_000L,
                        ),
                    ),
                )
            },
            nowUtcMillis = { NOW },
            persistConsentDates = {},
            agentAccessAllowed = { !barrier.isAgentBlocked() },
            showConsentedContext = { !barrier.isAgentBlocked() },
            blockedCategories = barrier::blockedCategories,
        )
        val initialContext = coordinator.assembleCurrent()
        val firstRequestReceived = CountDownLatch(1)
        val releaseFirstResponse = CountDownLatch(1)
        val requests = Collections.synchronizedList(mutableListOf<CapturedRequest>())
        val serverFailure = AtomicReference<Throwable?>()

        ServerSocket(0).use { server ->
            server.soTimeout = 5_000
            val serverThread = Thread {
                runCatching {
                    repeat(2) { index ->
                        server.accept().use { socket ->
                            socket.soTimeout = 5_000
                            requests += readRequest(socket)
                            if (index == 0) {
                                firstRequestReceived.countDown()
                                check(releaseFirstResponse.await(5, TimeUnit.SECONDS)) {
                                    "first response was not released after revocation"
                                }
                            }
                            writeChatResponse(socket)
                        }
                    }
                }.onFailure(serverFailure::set)
            }.apply { isDaemon = true; start() }

            val client = AgentApiClient(
                baseUrl = "http://127.0.0.1:${server.localPort}",
                tokenProvider = BearerTokenProvider { "unit-test-token" },
                freshRequestContext = coordinator::requestContext,
                requestAllowed = { !barrier.isAgentBlocked() },
            )
            val firstFailure = AtomicReference<Throwable?>()
            val firstCall = Thread {
                runCatching { client.chat("revocation-test", "Use my current goal", mandate, initialContext) }
                    .onFailure(firstFailure::set)
            }.apply { isDaemon = true; start() }

            assertTrue("initial request did not reach the local server", firstRequestReceived.await(5, TimeUnit.SECONDS))
            val sentBeforeRevocation = requests.single().body
            val firstSharedContext = JSONObject(sentBeforeRevocation).getJSONObject("shared_context")
            assertEquals(setOf(FOCUS, GOALS), jsonArrayStrings(firstSharedContext.getJSONArray("consented_categories")).toSet())
            assertTrue(sentBeforeRevocation.contains("25 min"))
            assertTrue(sentBeforeRevocation.contains("Fixture shared goal"))

            assertTrue(barrier.blockCategory(GOALS))
            mandate = mandateWithCategories(FOCUS)
            stateStore.write(mapOf("agentMandate" to mandate.toJson().toString()))
            releaseFirstResponse.countDown()
            firstCall.join(5_000)
            assertFalse("in-flight Coach call did not return", firstCall.isAlive)
            serverFailure.get()?.let { throw AssertionError("HTTP stub failed", it) }
            firstFailure.get()?.let { throw AssertionError("request sent before revocation failed", it) }

            // The already-sent request cannot be recalled. A fresh queued request must be
            // rebuilt against the durable mandate and revocation barrier before serialization.
            client.chat("revocation-test", "Continue with Focus only", mandateWithCategories(GOALS, FOCUS), initialContext)
            serverThread.join(5_000)
            assertFalse("HTTP stub did not finish", serverThread.isAlive)
            serverFailure.get()?.let { throw AssertionError("HTTP stub failed", it) }
        }

        assertEquals(2, requests.size)
        val nextBody = requests[1].body
        val nextContext = JSONObject(nextBody).getJSONObject("shared_context")
        assertEquals(listOf(FOCUS), jsonArrayStrings(nextContext.getJSONArray("consented_categories")))
        assertTrue(nextContext.getJSONObject("categories").has(FOCUS))
        assertFalse(nextContext.getJSONObject("categories").has(GOALS))
        assertFalse(nextBody.contains("Fixture shared goal"))
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

    private fun readRequest(socket: Socket): CapturedRequest {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
        val requestLine = reader.readLine().orEmpty()
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
        return CapturedRequest(requestLine, String(body, 0, read))
    }

    private fun writeChatResponse(socket: Socket) {
        val bytes = "{\"reply\":\"ok\",\"proposals\":[],\"agent_status\":\"connected\"}"
            .toByteArray(StandardCharsets.UTF_8)
        socket.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            write(bytes)
            flush()
        }
    }

    private fun jsonArrayStrings(array: JSONArray): List<String> =
        (0 until array.length()).map(array::getString)

    private fun finishedFocus(id: String, createdAt: Long) = FocusSession(
        id = id,
        directiveId = null,
        presetId = "synthetic-focus",
        phase = FocusPhase.WORK,
        status = FocusStatus.FINISHED,
        createdAtUtcMillis = createdAt,
        updatedAtUtcMillis = createdAt + 25L * 60_000L,
        remainingSeconds = 0L,
        completedWorkCycles = 1,
        workSeconds = 25L * 60L,
        shortBreakSeconds = 5L * 60L,
        longBreakSeconds = 15L * 60L,
        longBreakEveryCycles = 4,
        remainingMillis = 0L,
        totalWorkCycles = 1,
        continuous = false,
    )

    private data class CapturedRequest(val requestLine: String, val body: String)

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
