package dev.atlas.coach.sync.v4

import dev.atlas.coach.data.AgentConsentGate
import dev.atlas.coach.sync.AndroidCoreV4SyncStore
import dev.atlas.coach.sync.CoreV4ConsentAckResult
import dev.atlas.coach.sync.CoreV4SyncPersistence
import dev.atlas.coach.sync.relay.FakeRelaySocketFactory
import dev.atlas.coach.sync.relay.InMemoryRelayCredentialStore
import dev.atlas.coach.sync.relay.RelayBackendTransport
import dev.atlas.coach.sync.relay.RelayCallResult
import dev.atlas.coach.sync.relay.RelayConfig
import dev.atlas.coach.sync.relay.RelayConnectionState
import dev.atlas.coach.sync.relay.testCredentials
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CoreV4DiagnosticsTest {
    @Before
    fun resetTrace() {
        CoreV4Diagnostics.disable()
        CoreV4Diagnostics.clear()
    }

    @After
    fun disableTrace() {
        CoreV4Diagnostics.disable()
        CoreV4Diagnostics.clear()
    }

    @Test
    fun traceIsDisabledByDefaultBoundedAndContainsNoRequestData() {
        val sensitiveRequestId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        CoreV4Diagnostics.record(
            stage = CoreV4Diagnostics.Stage.CONSENT_DISPATCH,
            category = CoreV4Category.DAY_PLAN,
            consentVersion = 7,
            requestId = sensitiveRequestId,
        )
        assertFalse(CoreV4Diagnostics.enabled)
        assertTrue(CoreV4Diagnostics.snapshot().isEmpty())

        CoreV4Diagnostics.enableForDiagnostics()
        repeat(300) { index ->
            CoreV4Diagnostics.record(
                stage = CoreV4Diagnostics.Stage.CONSENT_DISPATCH,
                state = CoreV4Diagnostics.State.DISPATCHED,
                category = CoreV4Category.DAY_PLAN,
                consentVersion = index + 1,
                requestId = sensitiveRequestId,
            )
        }

        val events = CoreV4Diagnostics.snapshot()
        assertEquals(CoreV4Diagnostics.MAX_EVENTS, events.size)
        assertEquals(events.last().seq - CoreV4Diagnostics.MAX_EVENTS + 1L, events.first().seq)
        assertTrue(events.zipWithNext().all { (left, right) -> left.seq < right.seq })
        assertTrue(events.all { it.correlationTag?.matches(Regex("[0-9a-f]{8}")) == true })
        val rendered = events.toString()
        assertFalse(rendered.contains(sensitiveRequestId))
        assertFalse(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
            .containsMatchIn(rendered))
        assertFalse(rendered.contains("Bearer"))
        assertFalse(rendered.contains("synthetic payload"))
        assertNotNull(events.first().correlationTag)
        val immutable = CoreV4Diagnostics.snapshot()
        assertTrue(runCatching { (immutable as MutableList).clear() }.isFailure)

        val drained = CoreV4Diagnostics.drain()
        assertEquals(CoreV4Diagnostics.MAX_EVENTS, drained.size)
        assertTrue(CoreV4Diagnostics.snapshot().isEmpty())
    }

    @Test
    fun relayTraceShowsSocketAcceptanceCorrelationAndTimeoutWithFakePeer() {
        CoreV4Diagnostics.enableForDiagnostics()
        val (credentials, staticPair) = testCredentials()
        val capabilities = setOf("coach.chat")
        val successSockets = FakeRelaySocketFactory(
            staticPair = staticPair,
            serverCapabilities = capabilities,
        )
        val successTransport = relayTransport(credentials, successSockets, capabilities)
        val successRequestId = uuid(1)
        try {
            successTransport.start()
            assertEquals(RelayConnectionState.RELAY_CONNECTED, successTransport.state())
            val result = successTransport.executeDeferred(
                operation = "coach.chat.request",
                timeoutMillis = 1_000,
                idempotencyKey = uuid(2),
                requestId = successRequestId,
                payloadFactory = { JSONObject().put("message", "synthetic payload") },
            )
            assertTrue(result is RelayCallResult.Success)
            val successStages = CoreV4Diagnostics.snapshot()
                .filter { it.correlationTag == CoreV4Diagnostics.correlationTag(successRequestId) }
                .map { it.stage }
            assertInOrder(
                successStages,
                CoreV4Diagnostics.Stage.QUEUED,
                CoreV4Diagnostics.Stage.DISPATCHED,
                CoreV4Diagnostics.Stage.SOCKET_SEND_ACCEPTED,
                CoreV4Diagnostics.Stage.RESPONSE_RECEIVED,
                CoreV4Diagnostics.Stage.CORRELATED,
            )
            assertFalse(CoreV4Diagnostics.snapshot().toString().contains("synthetic payload"))
            assertTrue(CoreV4Diagnostics.snapshot().filter {
                it.correlationTag == CoreV4Diagnostics.correlationTag(successRequestId)
            }.all { it.sessionGeneration > 0L })
        } finally {
            successTransport.close()
        }

        CoreV4Diagnostics.clear()
        val (timeoutCredentials, timeoutStaticPair) = testCredentials()
        val timeoutSockets = FakeRelaySocketFactory(
            staticPair = timeoutStaticPair,
            dropRequestResponses = true,
            serverCapabilities = capabilities,
        )
        val timeoutTransport = relayTransport(timeoutCredentials, timeoutSockets, capabilities)
        val timeoutRequestId = uuid(3)
        try {
            timeoutTransport.start()
            val result = timeoutTransport.executeDeferred(
                operation = "coach.chat.request",
                timeoutMillis = 120,
                idempotencyKey = uuid(4),
                requestId = timeoutRequestId,
                payloadFactory = { JSONObject().put("message", "synthetic payload") },
            )
            assertEquals(RelayCallResult.Failure.Reason.TIMEOUT,
                (result as RelayCallResult.Failure).reason)
            val timeoutEvents = CoreV4Diagnostics.snapshot().filter {
                it.correlationTag == CoreV4Diagnostics.correlationTag(timeoutRequestId)
            }
            assertTrue(timeoutEvents.any { it.stage == CoreV4Diagnostics.Stage.QUEUED })
            assertTrue(timeoutEvents.any { it.stage == CoreV4Diagnostics.Stage.SOCKET_SEND_ACCEPTED })
            assertTrue(timeoutEvents.any { it.stage == CoreV4Diagnostics.Stage.TIMEOUT })
        } finally {
            timeoutTransport.close()
        }
    }

    @Test
    fun consentFirstTraceRecordsDayPlanAndActivityStatusAcknowledgements() {
        CoreV4Diagnostics.enableForDiagnostics()
        val persistence = MemoryPersistence()
        val store = AndroidCoreV4SyncStore(persistence)
        store.setConsent(CoreV4Category.DAY_PLAN, granted = true)
        store.setConsent(CoreV4Category.ACTIVITY_STATUS, granted = true)
        val client = CoreV4SyncClient(ConsentReceiptTransport())
        val context = CoreV4Diagnostics.beginExecution(CoreV4Diagnostics.WorkKind.IMMEDIATE, runAttempt = 1)

        try {
            syncPendingCoreV4ConsentsFirst(store, client, token = "synthetic test token")
        } finally {
            CoreV4Diagnostics.endExecution(context, CoreV4Diagnostics.Result.SUCCESS)
        }

        val events = CoreV4Diagnostics.snapshot()
        val ackResults = events.filter { it.stage == CoreV4Diagnostics.Stage.ACK_RESULT }
        assertEquals(setOf(CoreV4Category.DAY_PLAN, CoreV4Category.ACTIVITY_STATUS),
            ackResults.mapNotNull { it.category }.toSet())
        assertEquals(2, ackResults.size)
        assertTrue(ackResults.all {
            it.ackResult == CoreV4Diagnostics.AckResult.APPLIED &&
                it.result == CoreV4Diagnostics.Result.APPLIED
        })
        listOf(CoreV4Category.DAY_PLAN, CoreV4Category.ACTIVITY_STATUS).forEach { category ->
            val categoryStages = events.filter { it.category == category }.map { it.stage }
            assertInOrder(
                categoryStages,
                CoreV4Diagnostics.Stage.CONSENT_PREPARE,
                CoreV4Diagnostics.Stage.CONSENT_DISPATCH,
                CoreV4Diagnostics.Stage.CONSENT_RESPONSE,
                CoreV4Diagnostics.Stage.ACK_ATTEMPT,
                CoreV4Diagnostics.Stage.ACK_RESULT,
            )
        }
        assertTrue(events.filter { it.category == CoreV4Category.DAY_PLAN ||
            it.category == CoreV4Category.ACTIVITY_STATUS }.all {
            it.workerOrdinal == context.workerOrdinal &&
                it.executionGeneration == context.executionGeneration &&
                it.workKind == CoreV4Diagnostics.WorkKind.IMMEDIATE &&
                it.runAttempt == 1
        })
        assertEquals(2, store.consents().count { it.snapshot.syncedVersion == it.snapshot.version })
    }

    @Test
    fun consentAcknowledgementDistinguishesAppliedSupersededAndMissing() {
        val missingStore = AndroidCoreV4SyncStore(MemoryPersistence())
        val sampleReceipt = CoreV4ConsentReceipt(
            category = CoreV4Category.DAY_PLAN,
            version = 2,
            state = CoreV4ConsentState.GRANTED,
            resultCode = "ACCEPTED",
            updatedAt = "2026-10-31T12:00:00Z",
            replayed = false,
        )
        assertEquals(CoreV4ConsentAckResult.MISSING,
            missingStore.acknowledgeConsent(CoreV4Category.DAY_PLAN, sampleReceipt))

        val store = AndroidCoreV4SyncStore(MemoryPersistence())
        val granted = store.setConsent(CoreV4Category.DAY_PLAN, granted = true)
        val appliedReceipt = sampleReceipt.copy(version = granted.snapshot.version)
        assertEquals(CoreV4ConsentAckResult.APPLIED,
            store.acknowledgeConsent(CoreV4Category.DAY_PLAN, appliedReceipt))

        val stale = store.setConsent(CoreV4Category.DAY_PLAN, granted = false)
        assertEquals(CoreV4ConsentAckResult.SUPERSEDED,
            store.acknowledgeConsent(CoreV4Category.DAY_PLAN, appliedReceipt))
        assertEquals(granted.snapshot.version + 1, stale.snapshot.version)
    }

    private fun relayTransport(
        credentials: dev.atlas.coach.sync.relay.RelayCredentials,
        sockets: FakeRelaySocketFactory,
        capabilities: Set<String>,
    ) = RelayBackendTransport(
        config = RelayConfig(
            relayUrl = "ws://127.0.0.1:4400",
            serverId = "atlas-test",
            maxReconnectAttempts = 0,
            requestedCapabilities = capabilities,
        ),
        credentialStore = InMemoryRelayCredentialStore(credentials),
        consentGate = AgentConsentGate(),
        socketFactory = sockets,
    )

    private fun assertInOrder(actual: List<CoreV4Diagnostics.Stage>, vararg expected: CoreV4Diagnostics.Stage) {
        var next = 0
        actual.forEach { stage ->
            if (stage == expected[next]) next += 1
            if (next == expected.size) return
        }
        assertEquals("Missing ordered stages: ${expected.toList()} in $actual", expected.size, next)
    }

    private fun uuid(number: Int): String =
        "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"

    private class MemoryPersistence : CoreV4SyncPersistence {
        private val value = AtomicReference<String?>(null)
        override fun read(): String? = value.get()
        override fun write(value: String) { this.value.set(value) }
    }

    private class ConsentReceiptTransport : CoreV4HttpTransport {
        override val requiresBearerToken: Boolean get() = false

        override fun post(request: CoreV4HttpRequest): CoreV4HttpResponse {
            val envelope = JSONObject(request.body)
            return CoreV4HttpResponse(200, JSONObject()
                .put("contract_version", CoreV4SyncClient.CONTRACT_VERSION)
                .put("schema_version", CoreV4SyncClient.SCHEMA_VERSION)
                .put("request_id", envelope.getString("request_id"))
                .put("capability", CoreV4SyncClient.CONSENT_CAPABILITY)
                .put("category", envelope.getString("category"))
                .put("consent_version", envelope.getInt("consent_version"))
                .put("state", envelope.getString("state"))
                .put("updated_at", "2026-10-31T12:00:00Z")
                .put("source", "ANDROID_SYNC")
                .put("replayed", false)
                .put("result_code", "ACCEPTED")
                .toString())
        }
    }
}
