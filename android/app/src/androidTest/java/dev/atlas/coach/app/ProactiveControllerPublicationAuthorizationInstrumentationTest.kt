package dev.atlas.coach.app

import android.content.Context
import android.content.ContextWrapper
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.coach.proactive.CoachTrigger
import dev.atlas.coach.coach.proactive.CoachTriggerRecordStatus
import dev.atlas.coach.coach.proactive.CoachTriggerReliability
import dev.atlas.coach.coach.proactive.CoachTriggerSource
import dev.atlas.coach.coach.proactive.ProactiveCoachState
import dev.atlas.coach.coach.proactive.ProactiveRequestStatus
import dev.atlas.coach.coach.proactive.ProactiveRunStatus
import dev.atlas.coach.data.PersistenceDiagnosticsStore
import dev.atlas.coach.data.AndroidRevocationBarrierStorage
import dev.atlas.coach.data.AndroidStateBackend
import dev.atlas.coach.data.RevocationBarrierState
import dev.atlas.coach.data.RevocationBarrierStorage
import dev.atlas.coach.data.StateBackend
import dev.atlas.coach.focus.FakeClock
import dev.atlas.coach.sync.BearerTokenProvider
import dev.atlas.coach.sync.agent.AgentActionType
import dev.atlas.coach.sync.agent.AgentMode
import dev.atlas.coach.sync.agent.ProposalLevel
import dev.atlas.coach.sync.agent.ProposalState
import dev.atlas.coach.sync.agent.ProposalV2
import dev.atlas.coach.sync.agent.coach.AgentApiClient
import dev.atlas.coach.sync.agent.coach.AgentChatResponse
import dev.atlas.coach.sync.agent.coach.AgentStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Controller-level race coverage: the real coordinator/scheduler run around a held transport seam. */
@RunWith(AndroidJUnit4::class)
class ProactiveControllerPublicationAuthorizationInstrumentationTest {
    @Test
    fun latePlanResponseAfterCategoryRevocationAndSameClockReoptInHasNoLocalEffects() {
        val fixture = newFixture("late-response")
        val enteredTransport = CountDownLatch(1)
        val releaseTransport = CountDownLatch(1)
        val allowSend = AtomicReference<(() -> Boolean)?>(null)
        val notificationAttempts = AtomicInteger()
        val ackAttempts = AtomicInteger()
        fixture.controller.proactiveNotificationAttemptObserverForTesting = { notificationAttempts.incrementAndGet() }
        fixture.controller.proactiveAckAttemptObserverForTesting = { ackAttempts.incrementAndGet() }
        fixture.controller.proactivePlanTransportForTesting = { _, stillAllowed ->
            assertTrue("initial allowSend gate must accept the captured grant", stillAllowed())
            allowSend.set(stillAllowed)
            enteredTransport.countDown()
            check(releaseTransport.await(10, TimeUnit.SECONDS)) { "transport was not released" }
            fixture.response
        }
        val trigger = fixture.trigger("held-before-response")
        assertEquals(CoachTriggerRecordStatus.RECORDED, fixture.record(trigger))
        val result = AtomicReference<ProactiveRunStatus?>(null)
        val runner = Thread({ result.set(fixture.controller.runProactiveDueForTesting().status) }, "v622-proactive-held-http")
        runner.start()
        try {
            assertTrue("plan transport was not entered", enteredTransport.await(5, TimeUnit.SECONDS))
            fixture.revokeAndReoptInAtSameClock()
            assertFalse("the old token must stay invalid after same-clock re-opt-in", requireNotNull(allowSend.get()).invoke())
        } finally {
            releaseTransport.countDown()
            runner.join(10_000)
        }
        assertFalse("controller run thread should finish", runner.isAlive)
        assertEquals(ProactiveRunStatus.CONSENT_REVOKED, result.get())
        fixture.assertNoStaleResponseEffects(notificationAttempts, ackAttempts)
    }

    @Test
    fun revocationAfterTransportReturnButBeforeResponseMergeHasNoLocalEffects() {
        val fixture = newFixture("post-response")
        val returnedFromTransport = CountDownLatch(1)
        val releaseResponseHandling = CountDownLatch(1)
        val allowSend = AtomicReference<(() -> Boolean)?>(null)
        val notificationAttempts = AtomicInteger()
        val ackAttempts = AtomicInteger()
        fixture.controller.proactiveNotificationAttemptObserverForTesting = { notificationAttempts.incrementAndGet() }
        fixture.controller.proactiveAckAttemptObserverForTesting = { ackAttempts.incrementAndGet() }
        fixture.controller.proactivePlanTransportForTesting = { _, stillAllowed ->
            assertTrue("initial allowSend gate must accept the captured grant", stillAllowed())
            allowSend.set(stillAllowed)
            fixture.response
        }
        fixture.controller.afterProactivePlanResponseForTesting = {
            returnedFromTransport.countDown()
            check(releaseResponseHandling.await(10, TimeUnit.SECONDS)) { "response handler was not released" }
        }
        val trigger = fixture.trigger("response-before-merge")
        assertEquals(CoachTriggerRecordStatus.RECORDED, fixture.record(trigger))
        val result = AtomicReference<ProactiveRunStatus?>(null)
        val runner = Thread({ result.set(fixture.controller.runProactiveDueForTesting().status) }, "v622-proactive-response-handler")
        runner.start()
        try {
            assertTrue("plan response was not returned", returnedFromTransport.await(5, TimeUnit.SECONDS))
            fixture.revokeAndReoptInAtSameClock()
            assertFalse("the captured allowSend closure must still represent the old grant", requireNotNull(allowSend.get()).invoke())
        } finally {
            releaseResponseHandling.countDown()
            runner.join(10_000)
            fixture.controller.afterProactivePlanResponseForTesting = null
        }
        assertFalse("controller run thread should finish", runner.isAlive)
        assertEquals(ProactiveRunStatus.CONSENT_REVOKED, result.get())
        fixture.assertNoStaleResponseEffects(notificationAttempts, ackAttempts)
    }

    @Test
    fun categoryWithdrawalAndSameClockReoptInAtDurableReservationCommitNeverEntersTransportOrReplays() {
        val context = isolatedContext("reservation-commit")
        val backend = ReservationCommitAndroidStateBackend(context)
        val fixture = newFixture(
            name = "reservation-commit",
            backendOverride = backend,
            useAndroidRevocationStorage = true,
            contextOverride = context,
        )
        val trigger = fixture.trigger("withdraw-during-reservation-commit")
        assertEquals(CoachTriggerRecordStatus.RECORDED, fixture.record(trigger))

        val reservationWriteEntered = CountDownLatch(1)
        val releaseReservationWrite = CountDownLatch(1)
        backend.blockNextReservedTriggerCommit(
            triggerEventId = trigger.eventId,
            entered = reservationWriteEntered,
            release = releaseReservationWrite,
        )
        val transportAttempts = AtomicInteger()
        val notificationAttempts = AtomicInteger()
        val ackAttempts = AtomicInteger()
        fixture.controller.proactiveNotificationAttemptObserverForTesting = { notificationAttempts.incrementAndGet() }
        fixture.controller.proactiveAckAttemptObserverForTesting = { ackAttempts.incrementAndGet() }
        fixture.controller.proactivePlanTransportForTesting = { _, _ ->
            transportAttempts.incrementAndGet()
            fixture.response
        }

        val runResult = AtomicReference<ProactiveRunStatus?>(null)
        val runThread = Thread({
            runResult.set(fixture.controller.runProactiveDueForTesting().status)
        }, "v622-proactive-reservation-commit")
        runThread.start()
        val revocationFailure = AtomicReference<Throwable?>(null)
        val revocationFinished = CountDownLatch(1)
        val revocationThread = Thread({
            try {
                // StateBackend.write is intentionally blocked on main at the durable reservation.
                // Run the real public revoke/re-opt-in path here; posting it to main would deadlock.
                fixture.revokeAndReoptInAtSameClockWithoutMainDispatch()
            } catch (error: Throwable) {
                revocationFailure.set(error)
            } finally {
                revocationFinished.countDown()
            }
        }, "v622-proactive-revoke-during-storage-commit")

        try {
            assertTrue(
                "scheduler never reached the durable write containing the reserved event",
                reservationWriteEntered.await(5, TimeUnit.SECONDS),
            )
            revocationThread.start()
            assertTrue(
                "revoke/re-opt-in deadlocked behind the main-thread storage commit",
                revocationFinished.await(5, TimeUnit.SECONDS),
            )
            revocationFailure.get()?.let { throw AssertionError("revoke/re-opt-in failed", it) }
            assertTrue("same-clock re-opt-in should be active after its durable write", fixture.controller.isAgentEnabled)
        } finally {
            releaseReservationWrite.countDown()
            runThread.join(10_000)
            if (revocationThread.state != Thread.State.NEW) revocationThread.join(10_000)
        }

        assertFalse("controller run did not finish after reservation commit release", runThread.isAlive)
        assertFalse("revocation path did not finish", revocationThread.isAlive)
        assertEquals(ProactiveRunStatus.CONSENT_REVOKED, runResult.get())
        assertEquals("reservation race must not enter the plan transport", 0, transportAttempts.get())
        fixture.assertNoStaleResponseEffects(notificationAttempts, ackAttempts)

        val committedRoot = JSONObject(requireNotNull(fixture.backend.read()))
        val state = ProactiveCoachState.fromJsonString(
            committedRoot.getString("proactiveCoach"),
            fixture.clock.nowMillis(),
        )
        val reservation = state.requestReservations.single { trigger.eventId in it.triggerEventIds }
        assertEquals(ProactiveRequestStatus.REVOKED_BEFORE_REQUEST, reservation.status)
        assertEquals("reservation still consumes the daily request quota", 1, state.requestReservations.size)
        assertEquals(2L, state.nextReservationSequence)
        assertTrue("reserved trigger must stay consumed", state.consumedEventTombstones.any { it.eventId == trigger.eventId })
        assertFalse("reserved trigger must not return to pending", state.pendingTriggers.any { it.eventId == trigger.eventId })
        assertTrue("no notification slot should be reserved", state.notificationReservations.isEmpty())

        val restarted = restartController(fixture)
        val replayTransportAttempts = AtomicInteger()
        restarted.proactivePlanTransportForTesting = { _, _ ->
            replayTransportAttempts.incrementAndGet()
            fixture.response
        }
        assertEquals(ProactiveRunStatus.NO_ELIGIBLE_TRIGGERS, restarted.runProactiveDueForTesting().status)
        assertEquals("restart must not replay the consumed trigger", 0, replayTransportAttempts.get())
        assertTrue("replayed response must not enter inbox after restart", restarted.agentInbox.isEmpty())
        assertTrue("replayed response must not dispatch after restart", restarted.agentEffectHistory.isEmpty())
    }

    @Test
    fun sameClockReoptInAfterProactiveDispatchPreventsRealAckAtSerializationGate() {
        ServerSocket(0).use { ackServer ->
            ackServer.soTimeout = 1_000
            val fixture = newFixture("ack-body-gate")
            val generalAuthorizationChecks = AtomicInteger()
            val genericGateAllowed = AtomicBoolean(false)
            val ackClient = AgentApiClient(
                baseUrl = "http://127.0.0.1:${ackServer.localPort}",
                tokenProvider = BearerTokenProvider { "synthetic-ack-gate-token" },
                requestAllowed = {
                    generalAuthorizationChecks.incrementAndGet()
                    val allowed = fixture.controller.isAgentEnabled
                    genericGateAllowed.set(allowed)
                    allowed
                },
            )
            fixture.client = ackClient
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                fixture.controller.setAgentApiClientForTesting(ackClient)
            }
            val serverRequests = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
            fun startServerWindow(): Pair<Thread, CountDownLatch> {
                val serverDone = CountDownLatch(1)
                val serverThread = Thread({
                    try {
                        while (true) {
                            val socket = try {
                                ackServer.accept()
                            } catch (_: SocketTimeoutException) {
                                break
                            }
                            socket.use {
                                val reader = BufferedReader(InputStreamReader(it.getInputStream(), StandardCharsets.ISO_8859_1))
                                val requestLine = reader.readLine().orEmpty()
                                val headers = mutableMapOf<String, String>()
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (line.isEmpty()) break
                                    val separator = line.indexOf(':')
                                    if (separator > 0) headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
                                }
                                val length = headers["content-length"]?.toIntOrNull() ?: 0
                                val bodyChars = CharArray(length)
                                var read = 0
                                while (read < length) {
                                    val count = reader.read(bodyChars, read, length - read)
                                    if (count < 0) break
                                    read += count
                                }
                                serverRequests += (requestLine to String(bodyChars, 0, read))
                                val body = "{\"proposals\":[],\"cursor\":null}".toByteArray(StandardCharsets.UTF_8)
                                val output = it.getOutputStream()
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                                output.write(body)
                                output.flush()
                            }
                        }
                    } finally {
                        serverDone.countDown()
                    }
                }, "v622-proactive-ack-loopback-server").apply { isDaemon = true; start() }
                return serverThread to serverDone
            }

            val (probeThread, probeDone) = startServerWindow()
            assertTrue("loopback AgentApiClient preflight inbox was not empty", ackClient.pullInbox(null).proposals.isEmpty())
            assertTrue("loopback server did not complete its preflight window", probeDone.await(5, TimeUnit.SECONDS))
            probeThread.join(1_000)
            assertFalse("loopback preflight server is still running", probeThread.isAlive)
            assertEquals(1, serverRequests.size)
            assertTrue("real AgentApiClient did not reach the local server", serverRequests.single().first.startsWith("GET /v2/agent/inbox "))
            val requestChecksBeforeProactiveRun = generalAuthorizationChecks.get()

            val ackHookEntered = CountDownLatch(1)
            val releaseAckHook = CountDownLatch(1)
            val controllerAckAttemptEntered = CountDownLatch(1)
            val releaseControllerAckAttempt = CountDownLatch(1)
            val ackBodyBoundaryCount = AtomicInteger()
            fixture.controller.proactiveAckAttemptObserverForTesting = {
                controllerAckAttemptEntered.countDown()
                check(releaseControllerAckAttempt.await(10, TimeUnit.SECONDS)) { "controller ACK hook was not released" }
            }
            ackClient.beforeAckBodySerializationForTesting = {
                ackBodyBoundaryCount.incrementAndGet()
                ackHookEntered.countDown()
                check(releaseAckHook.await(10, TimeUnit.SECONDS)) { "ACK body hook was not released" }
            }
            fixture.controller.proactivePlanTransportForTesting = { _, stillAllowed ->
                assertTrue("captured plan authorization should remain valid until the ACK race", stillAllowed())
                fixture.response
            }
            val trigger = fixture.trigger("withdraw-before-ack-body")
            assertEquals(CoachTriggerRecordStatus.RECORDED, fixture.record(trigger))
            val runResult = AtomicReference<ProactiveRunStatus?>(null)
            val runThread = Thread({
                runResult.set(fixture.controller.runProactiveDueForTesting().status)
            }, "v622-proactive-ack-body-gate")
            runThread.start()

            var serverThread: Thread? = null
            try {
                assertTrue("proactive response never reached the controller ACK boundary", controllerAckAttemptEntered.await(5, TimeUnit.SECONDS))
                val proposalId = fixture.response.proposals.single().proposalId
                lateinit var effectsBeforeRevocation: List<*>
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val entry = fixture.controller.agentInbox.single { it.id == proposalId }
                    assertEquals(ProposalState.EXECUTED, entry.proposal.state)
                    assertTrue("the local dispatch is committed before ACK", entry.ackPending)
                    effectsBeforeRevocation = fixture.controller.agentEffectHistory
                    assertEquals("fixture must dispatch the local effect once before ACK", 1, effectsBeforeRevocation.size)
                }

                fixture.revokeAndReoptInAtSameClock()
                assertTrue("general agent authorization should be active after re-opt-in", fixture.controller.isAgentEnabled)
                releaseControllerAckAttempt.countDown()

                assertTrue("real AgentApiClient never reached its ACK bodyFactory", ackHookEntered.await(5, TimeUnit.SECONDS))
                // requestAllowed ran after re-opt-in and passed; only the captured publication epoch is stale.
                assertTrue("the generic request gate must pass after re-opt-in", genericGateAllowed.get())
                assertTrue("AgentApiClient.requestAllowed was not evaluated after re-opt-in", generalAuthorizationChecks.get() > requestChecksBeforeProactiveRun)

                val (ackServerThread, ackServerDone) = startServerWindow()
                serverThread = ackServerThread
                releaseAckHook.countDown()
                assertTrue("local ACK server did not finish its bounded receive window", ackServerDone.await(5, TimeUnit.SECONDS))
                runThread.join(10_000)
                assertFalse("proactive run did not finish", runThread.isAlive)
                assertEquals("only the first accepted ACK body should reach its epoch gate", 1, ackBodyBoundaryCount.get())
                assertTrue("the general requestAllowed gate did not run", generalAuthorizationChecks.get() > requestChecksBeforeProactiveRun)
                val ackRequests = serverRequests.filter { "/ack " in it.first }
                assertTrue("the real HTTP server received a stale acknowledgement: $ackRequests", ackRequests.isEmpty())
                assertEquals("server should have received only the successful preflight inbox GET", 1, serverRequests.size)

                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val entry = fixture.controller.agentInbox.single { it.id == proposalId }
                    assertEquals(ProposalState.EXECUTED, entry.proposal.state)
                    assertTrue("unacknowledged proposal must remain pending", entry.ackPending)
                    // The pre-revocation local effect remains applied; revocation only blocks the old ACK.
                    assertEquals(effectsBeforeRevocation, fixture.controller.agentEffectHistory)
                }
            } finally {
                releaseAckHook.countDown()
                releaseControllerAckAttempt.countDown()
                runThread.join(10_000)
                serverThread?.join(2_000)
                fixture.controller.proactiveAckAttemptObserverForTesting = null
                ackClient.beforeAckBodySerializationForTesting = null
            }
        }
    }

    @Test
    fun proactiveDispatchSendsAcceptedThenExecutedThroughRealLoopbackClient() {
        val fixture = newFixture("ack-accepted-executed")
        val ackServer = ServerSocket(0)
        ackServer.soTimeout = 8_000
        val requests = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val responseStatuses = Collections.synchronizedList(mutableListOf<Int>())
        val serverFailure = AtomicReference<Throwable?>(null)
        val serverDone = CountDownLatch(1)
        val activeSocket = AtomicReference<Socket?>(null)
        var serverThread: Thread? = null
        var runThread: Thread? = null
        val runFailure = AtomicReference<Throwable?>(null)
        val runStatus = AtomicReference<ProactiveRunStatus?>(null)
        val publicationStillAllowed = AtomicReference<(() -> Boolean)?>(null)

        try {
            val localServerThread = Thread({
                try {
                    // Read and answer sequentially so the controller must complete accepted before executed.
                    repeat(2) {
                        val socket = ackServer.accept()
                        activeSocket.set(socket)
                        socket.use { acceptedSocket ->
                            acceptedSocket.soTimeout = 5_000
                            val reader = BufferedReader(InputStreamReader(acceptedSocket.getInputStream(), StandardCharsets.ISO_8859_1))
                            val requestLine = reader.readLine().orEmpty()
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                val separator = line.indexOf(':')
                                if (separator > 0) {
                                    headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
                                }
                            }
                            val length = headers["content-length"]?.toIntOrNull() ?: 0
                            val bodyChars = CharArray(length)
                            var read = 0
                            while (read < length) {
                                val count = reader.read(bodyChars, read, length - read)
                                if (count < 0) break
                                read += count
                            }
                            requests += requestLine to String(bodyChars, 0, read)

                            val responseStatus = 200
                            responseStatuses += responseStatus
                            val body = "{}".toByteArray(StandardCharsets.UTF_8)
                            val output = acceptedSocket.getOutputStream()
                            output.write(
                                "HTTP/1.1 $responseStatus OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                    .toByteArray(StandardCharsets.ISO_8859_1),
                            )
                            output.write(body)
                            output.flush()
                        }
                        activeSocket.compareAndSet(socket, null)
                    }
                } catch (error: Throwable) {
                    serverFailure.compareAndSet(null, error)
                } finally {
                    serverDone.countDown()
                }
            }, "v622-proactive-accepted-executed-server").apply {
                isDaemon = true
                start()
            }
            serverThread = localServerThread

            val ackClient = AgentApiClient(
                baseUrl = "http://127.0.0.1:${ackServer.localPort}",
                tokenProvider = BearerTokenProvider { "synthetic-accepted-executed-token" },
                connectTimeoutMillis = 2_000,
                readTimeoutMillis = 5_000,
                requestAllowed = { fixture.controller.isAgentEnabled },
            )
            fixture.client = ackClient
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                fixture.controller.setAgentApiClientForTesting(ackClient)
                assertTrue("fixture consent must stay active for the positive ACK path", fixture.controller.isAgentEnabled)
            }
            fixture.controller.proactivePlanTransportForTesting = { _, stillAllowed ->
                publicationStillAllowed.set(stillAllowed)
                assertTrue("captured publication epoch should remain valid", stillAllowed())
                fixture.response
            }

            val trigger = fixture.trigger("accepted-then-executed")
            assertEquals(CoachTriggerRecordStatus.RECORDED, fixture.record(trigger))
            val proactiveRunThread = Thread({
                try {
                    runStatus.set(fixture.controller.runProactiveDueForTesting().status)
                } catch (error: Throwable) {
                    runFailure.set(error)
                }
            }, "v622-proactive-accepted-executed-run")
            runThread = proactiveRunThread
            proactiveRunThread.start()

            assertTrue("local server did not receive both ordered ACK requests", serverDone.await(12, TimeUnit.SECONDS))
            localServerThread.join(1_000)
            assertFalse("local ACK server is still running", localServerThread.isAlive)
            serverFailure.get()?.let { throw AssertionError("local ACK server failed", it) }
            proactiveRunThread.join(10_000)
            assertFalse("proactive controller run did not finish", proactiveRunThread.isAlive)
            runFailure.get()?.let { throw AssertionError("proactive controller run failed", it) }
            assertEquals(ProactiveRunStatus.REQUESTED, runStatus.get())

            val observedRequests = synchronized(requests) { requests.toList() }
            assertEquals("controller must send exactly accepted then executed", 2, observedRequests.size)
            val proposalId = fixture.response.proposals.single().proposalId
            val expectedPath = "/v2/agent/proposals/$proposalId/ack"
            assertTrue(observedRequests[0].first.startsWith("POST $expectedPath "))
            assertTrue(observedRequests[1].first.startsWith("POST $expectedPath "))
            val acceptedBody = JSONObject(observedRequests[0].second)
            val executedBody = JSONObject(observedRequests[1].second)
            assertEquals("accepted", acceptedBody.getString("state"))
            assertTrue("accepted ACK must not include an effect", acceptedBody.isNull("effect"))
            assertEquals("executed", executedBody.getString("state"))
            assertFalse("executed ACK must carry the dispatched effect", executedBody.isNull("effect"))
            assertEquals(listOf(200, 200), synchronized(responseStatuses) { responseStatuses.toList() })
            assertTrue("the fixture consent must remain active after ACKs", fixture.controller.isAgentEnabled)
            assertTrue("the original publication epoch must remain valid", requireNotNull(publicationStillAllowed.get()).invoke())

            val ackPending = AtomicReference<Boolean?>(null)
            val ackDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (ackPending.get() != false && System.nanoTime() < ackDeadline) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    ackPending.set(fixture.controller.agentInbox.singleOrNull { it.id == proposalId }?.ackPending)
                }
                if (ackPending.get() != false) Thread.sleep(25)
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val entry = fixture.controller.agentInbox.single { it.id == proposalId }
                assertEquals(ProposalState.EXECUTED, entry.proposal.state)
                assertFalse("successful accepted/executed ACKs must clear ackPending", entry.ackPending)
                assertEquals("positive ACK path must dispatch exactly once", 1, fixture.controller.agentEffectHistory.size)
                assertTrue("the consent and publication epoch must still be valid", fixture.controller.isAgentEnabled)
            }
        } finally {
            fixture.controller.proactivePlanTransportForTesting = null
            activeSocket.getAndSet(null)?.let { runCatching { it.close() } }
            runCatching { ackServer.close() }
            serverThread?.join(2_000)
            runThread?.join(10_000)
            // The positive coordinator path may post its test notification; revoke this isolated fixture to remove it.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                fixture.controller.setAgentSharedCategory("focus", false)
            }
        }
    }

    private fun newFixture(
        name: String,
        backendOverride: StateBackend? = null,
        useAndroidRevocationStorage: Boolean = false,
        contextOverride: Context? = null,
    ): Fixture {
        val context = contextOverride ?: isolatedContext(name)
        val now = LocalDate.of(2027, 1, 15).atTime(LocalTime.of(6, 0))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val clock = FakeClock(now, 1_000L)
        val backend = backendOverride ?: MemoryStateBackend()
        lateinit var controller: AtlasController
        val client = AgentApiClient("https://example.invalid", BearerTokenProvider { "synthetic-controller-test-token" })
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller = AtlasController(
                context = context,
                clock = clock,
                stateBackendOverride = backend,
                revocationBarrierStorageOverride = if (useAndroidRevocationStorage) {
                    AndroidRevocationBarrierStorage(context)
                } else {
                    TestBarrierStorage()
                },
                persistenceDiagnosticsStoreOverride = TestDiagnosticsStore(),
            )
            controller.setAgentSharedCategory("focus", true)
            controller.setAgentMode(AgentMode.PREAUTHORIZED)
            controller.setAgentFocusWindow(
                days = (1..7).toSet(), start = "00:00", end = "23:59",
                maxMinutes = 30, maxSessionsPerDay = 4, maxChangesPerDay = 50,
            )
            controller.setCapability(AtlasController.CAP_AGENT_PROACTIVE, true)
            controller.setAgentApiClientForTesting(client)
        }
        // The public opt-in queues a best-effort drain. Let its empty pass finish before inserting a fixture trigger.
        Thread.sleep(300)
        val proposal = ProposalV2(
            schemaVersion = 2,
            proposalId = UUID.randomUUID().toString(),
            idempotencyKey = UUID.randomUUID().toString(),
            conversationId = null,
            mandateId = controller.agentMandate.id,
            mandateVersion = controller.agentMandate.version,
            contextVersion = "synthetic-v622-controller-race",
            createdAtUtcMillis = now,
            expiresAtUtcMillis = now + 120_000L,
            actionType = AgentActionType.HOME_SET_DIRECTIVE.wireValue,
            parameters = mapOf(
                "title" to "Directive synthétique",
                "body" to "Prochaine étape synthétique.",
                "primary_action" to "open_coach",
            ),
            reason = "Proposition synthétique pour vérifier le dispatch local.",
            requiredLevel = ProposalLevel.PREAUTHORIZED_OK,
        )
        val response = AgentChatResponse(
            reply = "Proposition de plan synthétique.",
            proposals = listOf(proposal),
            status = AgentStatus.CONNECTED,
        )
        return Fixture(context, clock, backend, controller, client, response)
    }

    private fun restartController(fixture: Fixture): AtlasController {
        lateinit var restarted: AtlasController
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            restarted = AtlasController(
                context = fixture.context,
                clock = fixture.clock,
                stateBackendOverride = AndroidStateBackend(fixture.context),
                revocationBarrierStorageOverride = AndroidRevocationBarrierStorage(fixture.context),
                persistenceDiagnosticsStoreOverride = TestDiagnosticsStore(),
            )
            restarted.setAgentApiClientForTesting(fixture.client)
        }
        return restarted
    }

    private fun isolatedContext(name: String): Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "$name-${UUID.randomUUID()}"
        val files = File(target.cacheDir, "v622-proactive-controller-$namespace").apply {
            check(mkdirs() || isDirectory)
        }
        return object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getSharedPreferences(prefName: String, mode: Int) =
                target.getSharedPreferences("v622_proactive_controller_${namespace}_$prefName", mode)
        }
    }

    private data class Fixture(
        val context: Context,
        val clock: FakeClock,
        val backend: StateBackend,
        val controller: AtlasController,
        var client: AgentApiClient,
        val response: AgentChatResponse,
    ) {
        fun trigger(id: String) = CoachTrigger(
            eventId = id,
            source = CoachTriggerSource.FOCUS_COMPLETED,
            occurredAtUtcMillis = clock.nowMillis(),
            reliability = CoachTriggerReliability.VERIFIED,
            manual = true,
        )

        fun record(trigger: CoachTrigger): CoachTriggerRecordStatus {
            val status = AtomicReference<CoachTriggerRecordStatus>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                status.set(controller.recordProactiveTriggerForTesting(trigger))
            }
            return requireNotNull(status.get())
        }

        fun revokeAndReoptInAtSameClock() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                controller.setAgentSharedCategory("focus", false)
                controller.setAgentSharedCategory("focus", true)
                // Reuse the same client identity so the old token is the only rejecting condition.
                controller.setAgentApiClientForTesting(client)
            }
        }

        fun revokeAndReoptInAtSameClockWithoutMainDispatch() {
            controller.setAgentSharedCategory("focus", false)
            controller.setAgentSharedCategory("focus", true)
            controller.setAgentApiClientForTesting(client)
        }

        fun assertNoStaleResponseEffects(notificationAttempts: AtomicInteger, ackAttempts: AtomicInteger) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertTrue("stale proposal entered the actual inbox: ${controller.agentInbox}", controller.agentInbox.isEmpty())
                assertTrue("stale proposal dispatched a local effect: ${controller.agentEffectHistory}", controller.agentEffectHistory.isEmpty())
                assertNull("stale proposal changed the local directive", controller.agentHomeState.directive)
                assertTrue("a proactive notification reached the publisher", notificationAttempts.get() == 0)
                assertTrue("an ACK reached the proactive ACK boundary", ackAttempts.get() == 0)
                val activeProactive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.getSystemService(NotificationManager::class.java)?.activeNotifications.orEmpty()
                        .filter { active ->
                            active.packageName == context.packageName &&
                                active.notification.channelId == ProactiveCoachNotificationPublisher.CHANNEL_ID
                        }
                } else emptyList()
                assertTrue("a proactive notification is active after revocation: $activeProactive", activeProactive.isEmpty())
            }
        }
    }

    private class TestBarrierStorage : RevocationBarrierStorage {
        override fun readState() = RevocationBarrierState()
        override fun writeState(state: RevocationBarrierState) = true
    }

    private class TestDiagnosticsStore : PersistenceDiagnosticsStore {
        override fun read(): String? = null
        override fun write(value: String) = true
    }

    private class ReservationCommitAndroidStateBackend(context: Context) : StateBackend {
        private data class ReservationWriteGate(
            val triggerEventId: String,
            val entered: CountDownLatch,
            val release: CountDownLatch,
            val used: AtomicBoolean = AtomicBoolean(false),
        )

        private val delegate = AndroidStateBackend(context)
        @Volatile private var reservationWriteGate: ReservationWriteGate? = null

        fun blockNextReservedTriggerCommit(
            triggerEventId: String,
            entered: CountDownLatch,
            release: CountDownLatch,
        ) {
            reservationWriteGate = ReservationWriteGate(triggerEventId, entered, release)
        }

        override fun read(): String? = delegate.read()

        override fun write(value: String) {
            val gate = reservationWriteGate
            if (gate != null && !gate.used.get() &&
                containsUnmarkedReservationFor(value, gate.triggerEventId) && gate.used.compareAndSet(false, true)
            ) {
                gate.entered.countDown()
                check(gate.release.await(10, TimeUnit.SECONDS)) { "Timed out waiting to release the reservation commit" }
            }
            delegate.write(value)
        }

        override fun writeCheckpoint(value: String) = delegate.writeCheckpoint(value)

        override fun quarantine(rawValue: String, reason: String) = delegate.quarantine(rawValue, reason)

        private fun containsUnmarkedReservationFor(rootJson: String, eventId: String): Boolean = runCatching {
            val stateJson = JSONObject(rootJson).opt("proactiveCoach") as? String ?: return false
            val reservations = JSONObject(stateJson).getJSONArray("request_reservations")
            (0 until reservations.length()).any { index ->
                val reservation = reservations.getJSONObject(index)
                if (reservation.optString("status") != "reserved") return@any false
                val triggerIds = reservation.getJSONArray("trigger_event_ids")
                (0 until triggerIds.length()).any { triggerIds.getString(it) == eventId }
            }
        }.getOrDefault(false)
    }

    private class MemoryStateBackend : StateBackend {
        @Volatile private var json: String? = null
        override fun read(): String? = json
        override fun write(value: String) { json = value }
        override fun quarantine(rawValue: String, reason: String) = true
    }
}
