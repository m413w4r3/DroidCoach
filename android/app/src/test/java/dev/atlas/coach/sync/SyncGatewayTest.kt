package dev.atlas.coach.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class SyncGatewayTest {
    @Test
    fun remoteCoachAndSyncRoundTripUseVersionedContractsAndServerAck() {
        var authorization: String? = null
        var syncBody: JSONObject? = null
        val server = server { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            when {
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/v1/directives/current" ->
                    reply(exchange, 200, directiveJson())
                exchange.requestMethod == "POST" && exchange.requestURI.path.endsWith("/respond") ->
                    reply(exchange, 200, """{"directive_id":"demo-focus-001","choice":"start","message":"Proposition reçue","action_executed":false}""")
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/v1/sync" -> {
                    syncBody = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                    reply(exchange, 200, acknowledgement(syncBody!!))
                }
                else -> reply(exchange, 404, "{}")
            }
        }
        try {
            val clock = MutableClock(1_792_320_000_000L)
            val outbox = DurableEventOutbox(MemoryStorage())
            val syncConsent = SyncConsent(setOf(SyncDataCategory.FOCUS, SyncDataCategory.COACH_INTERACTION))
            val gateway = gateway(server, outbox, clock, consent = syncConsent)
            val directive = gateway.currentDirective()
            assertEquals("demo-focus-001", directive.id)
            assertEquals("start_focus_session", directive.type)
            val response = gateway.respond(directive.id, "start")
            assertFalse(response.actionExecuted)
            assertEquals("Proposition reçue", response.message)

            val event = shareableEvent(clock.nowMillis())
            assertTrue(outbox.enqueue(event, syncConsent))
            val result = gateway.syncNow()
            assertEquals(SyncStatus.SUCCESS, result.status)
            assertEquals(1, result.acknowledged)
            assertEquals(0, result.pending)
            assertEquals(1, syncBody!!.getInt("contract_version"))
            assertEquals(event.clientEventId, syncBody!!.getJSONArray("events").getJSONObject(0).getString("event_id"))
            assertEquals("Bearer test-only-token", authorization)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun unauthorizedResponseKeepsEventForConfiguredCredentials() {
        val requests = AtomicInteger()
        val server = server { exchange ->
            requests.incrementAndGet()
            reply(exchange, 401, """{"error":{"code":"authentication_required"}}""")
        }
        try {
            val clock = MutableClock(1_000L)
            val consent = focusConsent()
            val outbox = DurableEventOutbox(MemoryStorage())
            outbox.enqueue(shareableEvent(clock.nowMillis()), consent)
            val result = gateway(server, outbox, clock, consent = consent).syncNow()
            assertEquals(SyncStatus.AUTH_REQUIRED, result.status)
            assertEquals(1, outbox.size())
            assertEquals(1_000L, result.retryAtUtcMillis!! - clock.nowMillis())
            assertEquals(1, requests.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun serverFailureUsesBoundedJitteredBackoffThenAcknowledgesSameUuid() {
        val attempts = AtomicInteger()
        val ids = mutableListOf<String>()
        val server = server { exchange ->
            val body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            ids += body.getJSONArray("events").getJSONObject(0).getString("event_id")
            if (attempts.incrementAndGet() == 1) reply(exchange, 503, "{}")
            else reply(exchange, 200, acknowledgement(body))
        }
        try {
            val clock = MutableClock(50_000L)
            val consent = focusConsent()
            val outbox = DurableEventOutbox(MemoryStorage())
            val event = shareableEvent(clock.nowMillis())
            outbox.enqueue(event, consent)
            val gateway = gateway(server, outbox, clock, consent = consent)

            val first = gateway.syncNow()
            assertEquals(SyncStatus.RETRY_SCHEDULED, first.status)
            assertEquals(51_000L, first.retryAtUtcMillis)
            assertEquals(SyncStatus.NOTHING_TO_SYNC, gateway.syncNow().status)
            clock.value = first.retryAtUtcMillis!!
            assertEquals(SyncStatus.SUCCESS, gateway.syncNow().status)
            assertEquals(listOf(event.clientEventId, event.clientEventId), ids)
            assertEquals(0, outbox.size())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun offlineEventSurvivesGatewayRecreationAndResumesAfterServerReturns() {
        val port = ServerSocket(0).use { it.localPort }
        val clock = MutableClock(70_000L)
        val consent = focusConsent()
        val directory = Files.createTempDirectory("atlas-outbox-test").toFile()
        val file = directory.resolve("outbox.json")
        try {
            val firstOutbox = DurableEventOutbox(FileOutboxStorage(file))
            val event = shareableEvent(clock.nowMillis())
            firstOutbox.enqueue(event, consent)
            val offline = gatewayAt(port, firstOutbox, clock, consent).syncNow()
            assertEquals(SyncStatus.RETRY_SCHEDULED, offline.status)
            assertEquals(1, firstOutbox.size())

            val restartedOutbox = DurableEventOutbox(FileOutboxStorage(file))
            assertEquals(event.clientEventId, restartedOutbox.all().single().event.clientEventId)
            val server = server(port) { exchange ->
                val body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                reply(exchange, 200, acknowledgement(body))
            }
            try {
                clock.value = offline.retryAtUtcMillis!!
                val resumed = gatewayAt(port, restartedOutbox, clock, consent).syncNow()
                assertEquals(SyncStatus.SUCCESS, resumed.status)
                assertEquals(0, restartedOutbox.size())
                assertEquals(SyncStatus.NOTHING_TO_SYNC, gatewayAt(port, restartedOutbox, clock, consent).syncNow().status)
            } finally {
                server.stop(0)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun disabledOrRevokedConsentSendsNothingAndDropsPreviouslyQueuedPrivateData() {
        val requests = AtomicInteger()
        val server = server { exchange ->
            requests.incrementAndGet()
            val body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            reply(exchange, 200, acknowledgement(body))
        }
        try {
            val clock = MutableClock(90_000L)
            val outbox = DurableEventOutbox(MemoryStorage())
            val event = shareableEvent(clock.nowMillis())
            assertFalse(outbox.enqueue(event, SyncConsent.DISABLED))
            assertEquals(0, outbox.size())
            val noConsent = gateway(server, outbox, clock, consent = SyncConsent.DISABLED).syncNow()
            assertEquals(SyncStatus.NOTHING_TO_SYNC, noConsent.status)

            assertTrue(outbox.enqueue(event, focusConsent()))
            val revoked = gateway(server, outbox, clock, consent = SyncConsent.DISABLED).syncNow()
            assertEquals(SyncStatus.NOTHING_TO_SYNC, revoked.status)
            assertEquals(0, outbox.size())
            assertEquals(0, requests.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun manualSyncCanMakeDeferredEventsDueAndClearTheQueue() {
        val outbox = DurableEventOutbox(MemoryStorage())
        val event = shareableEvent(100L)
        assertTrue(outbox.enqueue(event, focusConsent()))
        outbox.defer(event.clientEventId, 50_000L)
        assertTrue(outbox.pending(1_000L).isEmpty())
        assertEquals(1, outbox.makeDueNow())
        assertEquals(event.clientEventId, outbox.pending(1_000L).single().event.clientEventId)
        assertEquals(1, outbox.clear())
        assertEquals(0, outbox.size())
    }

    @Test
    fun privateAppDetailsRequireSeparateExplicitConsentAndStableUuidIsEnforced() {
        val detail = SyncEvent(
            eventType = SyncEventType.FOCUS_STARTED,
            occurredAtUtcMillis = 1L,
            category = SyncDataCategory.APP_USAGE_DETAIL,
            dataScope = DataScope.SHAREABLE,
            payload = mapOf("package_name" to "example.private.app"),
        )
        assertFalse(SyncConsent(setOf(SyncDataCategory.APP_USAGE_DETAIL)).allows(detail))
        assertTrue(SyncConsent(setOf(SyncDataCategory.APP_USAGE_DETAIL), sharePrivateAppDetails = true).allows(detail))
        assertThrows(IllegalArgumentException::class.java) {
            SyncEvent(clientEventId = "not-a-uuid", eventType = SyncEventType.FOCUS_STARTED,
                occurredAtUtcMillis = 1L, category = SyncDataCategory.FOCUS)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncEvent(clientEventId = UUID.randomUUID().toString().uppercase(), eventType = SyncEventType.FOCUS_STARTED,
                occurredAtUtcMillis = 1L, category = SyncDataCategory.FOCUS)
        }
    }

    @Test
    fun localMockRemainsDeterministicAndDoesNotContactTheBackend() {
        val mock = LocalMockCoachGateway(MutableClock(100L))
        val first = mock.currentDirective()
        val second = mock.currentDirective()
        assertEquals(first, second)
        assertEquals(mock.respond(first.id, "start"), mock.respond(first.id, "start"))
        assertFalse(mock.respond(first.id, "start").actionExecuted)
        assertEquals(SyncStatus.LOCAL_ONLY, LocalMockSyncGateway().syncNow().status)
    }

    @Test
    fun remoteCoachResponseRequiresExplicitCoachSharingConsent() {
        val requests = AtomicInteger()
        val server = server { exchange ->
            requests.incrementAndGet()
            reply(exchange, 200, "{}")
        }
        try {
            val gateway = gateway(server, DurableEventOutbox(MemoryStorage()), MutableClock(1L), consent = SyncConsent.DISABLED)
            assertThrows(SyncConsentRequiredException::class.java) {
                gateway.respond("demo-focus-001", "start")
            }
            assertEquals(0, requests.get())
        } finally {
            server.stop(0)
        }
    }

    private fun focusConsent() = SyncConsent(setOf(SyncDataCategory.FOCUS))

    private fun shareableEvent(now: Long) = SyncEvent(
        eventType = SyncEventType.FOCUS_STARTED,
        occurredAtUtcMillis = now,
        category = SyncDataCategory.FOCUS,
        dataScope = DataScope.SHAREABLE,
        payload = mapOf("duration_minutes" to 10),
    )

    private fun gateway(
        server: HttpServer,
        outbox: EventOutbox,
        clock: MutableClock,
        consent: SyncConsent,
    ) = RemoteBackendGateway(
        baseUrl = "http://127.0.0.1:${server.address.port}",
        tokenProvider = BearerTokenProvider { "test-only-token" },
        outbox = outbox,
        consentProvider = { consent },
        clock = clock,
        backoff = ExponentialBackoff(1_000L, 10_000L) { 0.5 },
    )

    private fun gatewayAt(port: Int, outbox: EventOutbox, clock: MutableClock, consent: SyncConsent) =
        RemoteBackendGateway(
            baseUrl = "http://127.0.0.1:$port",
            tokenProvider = BearerTokenProvider { "test-only-token" },
            outbox = outbox,
            consentProvider = { consent },
            clock = clock,
            backoff = ExponentialBackoff(1_000L, 10_000L) { 0.5 },
            connectTimeoutMillis = 200,
            readTimeoutMillis = 200,
        )

    private fun server(handler: (HttpExchange) -> Unit): HttpServer = server(0, handler)

    private fun server(port: Int, handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).also { server ->
            server.createContext("/") { exchange ->
                try { handler(exchange) } catch (_: Exception) {
                    runCatching { reply(exchange, 500, "{}") }
                }
            }
            server.start()
        }

    private fun reply(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun directiveJson() = """{
        "schema_version":1,"directive_id":"demo-focus-001","type":"start_focus_session",
        "title":"Avancer 10 minutes","reason":"Une courte session.","project_id":null,
        "estimated_minutes":10,"choices":["start","discuss","alternative","postpone","pause"],
        "created_at":"2026-10-18T12:00:00Z","expires_at":"2026-10-19T12:00:00Z",
        "requires_explicit_confirmation":false
    }""".trimIndent()

    private fun acknowledgement(request: JSONObject): String {
        val events = request.getJSONArray("events")
        val results = JSONArray()
        repeat(events.length()) { index ->
            results.put(JSONObject()
                .put("event_id", events.getJSONObject(index).getString("event_id"))
                .put("status", "accepted")
                .put("recorded_at", "2026-10-18T12:00:00Z"))
        }
        return JSONObject()
            .put("contract_version", 1)
            .put("status", "ok")
            .put("received", events.length())
            .put("inserted", events.length())
            .put("duplicates", 0)
            .put("results", results)
            .toString()
    }

    private class MemoryStorage : OutboxStorage {
        private var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
    }

    private class MutableClock(var value: Long) : UtcClock {
        override fun nowMillis(): Long = value
    }
}
