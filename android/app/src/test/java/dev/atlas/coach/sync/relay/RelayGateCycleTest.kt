package dev.atlas.coach.sync.relay

import dev.atlas.coach.data.AgentConsentGate
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RelayGateCycleTest {
    @Test(timeout = 8_000)
    fun callbackWaitingForConsentGateCannotBlockTheNextSocketWrite() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(
            staticPair,
            dropRequestResponses = true,
            serverCapabilities = setOf("coach.chat", "plan.revision"),
        )
        val gate = AgentConsentGate()
        val transport = RelayBackendTransport(
            config = RelayConfig(
                "ws://127.0.0.1:4400",
                "atlas-test",
                maxReconnectAttempts = 0,
                requestedCapabilities = setOf("plan.revision", "coach.chat"),
            ),
            credentialStore = InMemoryRelayCredentialStore(credentials),
            consentGate = gate,
            socketFactory = sockets,
        )
        val gateHeld = CountDownLatch(1)
        val readerWaitingForGate = CountDownLatch(1)
        val continueDispatch = CountDownLatch(1)
        val dispatchStarted = CountDownLatch(1)
        val secondPayloadReady = CountDownLatch(1)
        val firstRequestDone = CountDownLatch(1)
        val secondDispatchDone = CountDownLatch(1)
        val workerFailures = AtomicReference<Throwable?>()
        gate.beforeLockAttemptForTest = {
            if (Thread.currentThread().name == "relay-cycle-reader") readerWaitingForGate.countDown()
        }

        transport.start()
        val firstRequestId = "11111111-1111-4111-8111-111111111111"
        val firstWorker = Thread({
            try {
                transport.executeDeferred(
                    operation = "coach.chat.request",
                    timeoutMillis = 10_000,
                    idempotencyKey = firstRequestId,
                    requestId = firstRequestId,
                    payloadFactory = { JSONObject().put("message", "first synthetic message") },
                )
            } catch (error: Throwable) {
                workerFailures.compareAndSet(null, error)
            } finally {
                firstRequestDone.countDown()
            }
        }, "relay-first-request").apply { isDaemon = true; start() }

        assertTrue("first request was not captured", sockets.requestCaptured.await(2, TimeUnit.SECONDS))
        val secondRequestId = "22222222-2222-4222-8222-222222222222"
        val gateHolder = Thread({
            try {
                gate.withLock {
                    gateHeld.countDown()
                    check(continueDispatch.await(4, TimeUnit.SECONDS)) { "test did not release the dispatch barrier" }
                    dispatchStarted.countDown()
                    transport.executeDeferred(
                        operation = "coach.chat.request",
                        timeoutMillis = 2_000,
                        idempotencyKey = secondRequestId,
                        requestId = secondRequestId,
                        beforeDispatch = { true },
                        payloadFactory = {
                            secondPayloadReady.countDown()
                            JSONObject().put("message", "second synthetic message")
                        },
                    )
                }
            } catch (error: Throwable) {
                workerFailures.compareAndSet(null, error)
            } finally {
                secondDispatchDone.countDown()
            }
        }, "relay-gate-holder").apply { isDaemon = true; start() }

        assertTrue("scenario thread did not acquire the consent gate", gateHeld.await(2, TimeUnit.SECONDS))
        val reader = Thread({
            try {
                sockets.sockets.single().sendResponseForRequest(firstRequestId)
            } catch (error: Throwable) {
                workerFailures.compareAndSet(null, error)
            }
        }, "relay-cycle-reader").apply { isDaemon = true; start() }

        assertTrue(
            "reader callback did not reach the consent gate; worker states/stacks:\n${threadDump(firstWorker, gateHolder, reader)}",
            readerWaitingForGate.await(2, TimeUnit.SECONDS),
        )
        continueDispatch.countDown()
        assertTrue("scenario thread did not begin its second dispatch", dispatchStarted.await(1, TimeUnit.SECONDS))
        assertTrue("second request payload was not prepared", secondPayloadReady.await(1, TimeUnit.SECONDS))

        assertTrue(
            "second request was not captured while its dispatch deadline was active; " +
                "likely G↔C deadlock. Worker states/stacks:\n${threadDump(firstWorker, gateHolder, reader)}",
            sockets.secondRequestCaptured.await(2, TimeUnit.SECONDS),
        )
        assertTrue("scenario dispatch did not finish", secondDispatchDone.await(4, TimeUnit.SECONDS))
        assertTrue("first request worker did not finish", firstRequestDone.await(2, TimeUnit.SECONDS))
        workerFailures.get()?.let { throw AssertionError("relay concurrency worker failed", it) }
        transport.close()
    }

    private fun threadDump(vararg threads: Thread): String {
        val bean = ManagementFactory.getThreadMXBean()
        val snapshots = bean.getThreadInfo(threads.map { it.threadId() }.toLongArray(), true, true, 50)
            .filterNotNull()
            .associateBy { it.threadId }
        return threads.joinToString("\n") { thread ->
            snapshots[thread.threadId()]?.toString() ?: "${thread.name} state=${thread.state} (no snapshot)"
        }
    }
}
