package dev.atlas.coach.sync.relay

import dev.atlas.coach.data.AgentConsentGate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RelayConcurrencyGateTest {
    @Test
    fun revocationUnderTheGateDeniesAChatWaitingForDispatch() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(
            staticPair,
            serverCapabilities = setOf("coach.chat", "plan.revision"),
        )
        val gate = AgentConsentGate()
        val transport = transport(credentials, sockets, gate)
        val revoked = AtomicBoolean(false)
        val payloadCalls = AtomicInteger()
        val workerReady = CountDownLatch(1)
        val result = AtomicReference<RelayCallResult>()
        val completed = CountDownLatch(1)
        try {
            transport.start()
            gate.withLock {
                Thread {
                    workerReady.countDown()
                    result.set(
                        transport.executeDeferred(
                            operation = "coach.chat.request",
                            idempotencyKey = "race-a1-chat",
                            beforeDispatch = { !revoked.get() },
                            payloadFactory = {
                                payloadCalls.incrementAndGet()
                                JSONObject().put("message", "synthetic")
                            },
                        ),
                    )
                    completed.countDown()
                }.apply { isDaemon = true; start() }

                assertTrue("chat worker did not reach its dispatch call", workerReady.await(2, TimeUnit.SECONDS))
                revoked.set(true)
            }

            assertTrue("chat did not finish after the gate opened", completed.await(2, TimeUnit.SECONDS))
            assertEquals(
                RelayCallResult.Failure(RelayCallResult.Failure.Reason.POLICY_DENIED),
                result.get(),
            )
            assertTrue("payload must not be built after revocation", payloadCalls.get() == 0)
            assertTrue("no request may reach the socket after revocation", sockets.requests.isEmpty())
        } finally {
            transport.close()
        }
    }

    @Test
    fun consentMutationWaitsForAStartedSocketWriteAndBlocksTheNextChat() {
        val (credentials, staticPair) = testCredentials()
        val sendStarted = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val sockets = FakeRelaySocketFactory(
            staticPair,
            serverCapabilities = setOf("coach.chat", "plan.revision"),
            beforeSendText = { _, sendNumber, _ ->
                // The NK handshake is send 1, protected application hello is 2,
                // and this chat request is 3.
                if (sendNumber == 3) {
                    sendStarted.countDown()
                    check(releaseSend.await(5, TimeUnit.SECONDS)) { "request send release timed out" }
                }
            },
        )
        val gate = AgentConsentGate()
        val transport = transport(credentials, sockets, gate)
        val revoked = AtomicBoolean(false)
        val mutationStarted = CountDownLatch(1)
        val mutationDone = CountDownLatch(1)
        val chatResult = AtomicReference<RelayCallResult>()
        val chatDone = CountDownLatch(1)
        val payloadCalls = AtomicInteger()
        try {
            transport.start()
            Thread {
                chatResult.set(
                    transport.executeDeferred(
                        operation = "coach.chat.request",
                        idempotencyKey = "race-a2-chat",
                        beforeDispatch = { !revoked.get() },
                        payloadFactory = {
                            payloadCalls.incrementAndGet()
                            JSONObject().put("message", "synthetic")
                        },
                    ),
                )
                chatDone.countDown()
            }.apply { isDaemon = true; start() }

            assertTrue("chat request did not reach the controlled send point", sendStarted.await(3, TimeUnit.SECONDS))
            Thread {
                mutationStarted.countDown()
                gate.withLock {
                    revoked.set(true)
                    mutationDone.countDown()
                }
            }.apply { isDaemon = true; start() }
            assertTrue("revocation thread did not start", mutationStarted.await(2, TimeUnit.SECONDS))
            assertFalse(
                "consent mutation passed the gate while sendText was blocked",
                mutationDone.await(250, TimeUnit.MILLISECONDS),
            )

            releaseSend.countDown()
            assertTrue("request was not recorded after the socket write resumed", sockets.requestCaptured.await(2, TimeUnit.SECONDS))
            assertTrue("consent mutation did not run after the socket write", mutationDone.await(2, TimeUnit.SECONDS))
            assertTrue("first chat did not complete", chatDone.await(2, TimeUnit.SECONDS))
            assertTrue(chatResult.get() is RelayCallResult.Success)

            val denied = transport.executeDeferred(
                operation = "coach.chat.request",
                idempotencyKey = "race-a2-after-revoke",
                beforeDispatch = { !revoked.get() },
                payloadFactory = {
                    payloadCalls.incrementAndGet()
                    JSONObject().put("message", "must-not-send")
                },
            )
            assertEquals(RelayCallResult.Failure(RelayCallResult.Failure.Reason.POLICY_DENIED), denied)
            assertEquals("only the write already started before revocation may reach the peer", 1, sockets.requests.size)
            assertEquals("the post-revocation payload must stay unbuilt", 1, payloadCalls.get())
        } finally {
            releaseSend.countDown()
            transport.close()
        }
    }

    @Test
    fun deviceRevocationFailsInflightChatBeforeCloseAndIgnoresLateResponse() {
        val (credentials, staticPair) = testCredentials()
        val sockets = FakeRelaySocketFactory(
            staticPair,
            dropRequestResponses = true,
            serverCapabilities = setOf("coach.chat", "plan.revision"),
        )
        val transport = transport(credentials, sockets)
        val events = CopyOnWriteArrayList<String>()
        val revokedFailure = CountDownLatch(1)
        val authenticationFailure = CountDownLatch(1)
        val result = AtomicReference<RelayCallResult>()
        val chatDone = CountDownLatch(1)
        transport.addFailureListener { failure ->
            events += "failure:${failure.kind}"
            if (failure.kind == RelayFailureKind.REVOKED) revokedFailure.countDown()
            if (failure.kind == RelayFailureKind.AUTHENTICATION) authenticationFailure.countDown()
        }
        try {
            transport.start()
            assertEquals(RelayConnectionState.RELAY_CONNECTED, transport.state())
            Thread {
                result.set(
                    transport.executeDeferred(
                        operation = "coach.chat.request",
                        idempotencyKey = "race-a3-chat",
                        payloadFactory = { JSONObject().put("message", "synthetic") },
                    ),
                )
                chatDone.countDown()
            }.apply { isDaemon = true; start() }

            assertTrue("in-flight chat was not captured", sockets.requestCaptured.await(3, TimeUnit.SECONDS))
            val socket = sockets.sockets.single()
            val requestId = sockets.requests.single().getString("request_id")
            socket.sendErrorEnvelope("DEVICE_REVOKED", "device revoked")

            assertTrue("DEVICE_REVOKED failure was not reported", revokedFailure.await(2, TimeUnit.SECONDS))
            assertTrue("in-flight chat did not complete on revocation", chatDone.await(2, TimeUnit.SECONDS))
            assertEquals("DEVICE_REVOKED", (result.get() as RelayCallResult.RemoteError).code)
            assertEquals(RelayConnectionState.REVOKED, transport.state())

            events += "close:4001"
            socket.remoteClose(4001, "device revoked")
            assertEquals(4001, transport.lastCloseCode)
            assertEquals(RelayConnectionState.REVOKED, transport.state())
            assertEquals(listOf("failure:REVOKED", "close:4001"), events)
            assertFalse(
                "close 4001 must not report a second authentication failure",
                authenticationFailure.await(250, TimeUnit.MILLISECONDS),
            )

            socket.sendResponseForRequest(requestId)
            assertEquals("late response must not complete the already-revoked chat", "DEVICE_REVOKED", (result.get() as RelayCallResult.RemoteError).code)
            assertEquals("late response must not change the revoked connection state", RelayConnectionState.REVOKED, transport.state())
            assertEquals("late response must not create a second backend effect", 1, sockets.requests.size)
            assertEquals(listOf("failure:REVOKED", "close:4001"), events)
        } finally {
            transport.close()
        }
    }

    private fun transport(
        credentials: RelayCredentials,
        sockets: FakeRelaySocketFactory,
        gate: AgentConsentGate = AgentConsentGate(),
    ) = RelayBackendTransport(
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
}
