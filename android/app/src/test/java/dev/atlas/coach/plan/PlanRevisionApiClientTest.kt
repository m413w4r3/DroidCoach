package dev.atlas.coach.plan

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.atlas.coach.sync.BearerTokenProvider
import dev.atlas.coach.sync.agent.AgentMandate
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanRevisionApiClientTest {
    @Test
    fun postsTheMinimizedConsentedV3PlanWithBearerAndNoSecretLogs() {
        val seenBody = AtomicReference<String>()
        val seenPath = AtomicReference<String>()
        val seenAuthorization = AtomicReference<String>()
        val server = server { exchange ->
            seenPath.set(exchange.requestURI.path)
            seenAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
            seenBody.set(exchange.requestBody.bufferedReader().use { it.readText() })
            reply(exchange, 200, responseWithoutProposal())
        }
        val previousOut = System.out
        val previousErr = System.err
        val captured = ByteArrayOutputStream()
        val printStream = PrintStream(captured, true, Charsets.UTF_8)
        try {
            System.setOut(printStream)
            System.setErr(printStream)
            val result = client(server).propose(request())
            assertTrue(result is PlanRevisionApiResult.Success)
        } finally {
            System.setOut(previousOut)
            System.setErr(previousErr)
            printStream.flush()
            server.stop(0)
        }

        assertEquals(PlanRevisionApiClient.PATH, seenPath.get())
        assertEquals("Bearer $TOKEN", seenAuthorization.get())
        assertFalse(captured.toString(Charsets.UTF_8).contains(TOKEN))
        val body = JSONObject(seenBody.get())
        assertEquals(3, body.getInt("schema_version"))
        assertEquals(REQUEST_ID, body.getString("request_id"))
        assertTrue(body.getBoolean("plan_context_consented"))
        assertEquals(JSONObject.NULL, body.get("conversation_id"))
        assertEquals("Planifie la journée", body.getString("message"))
        val mandate = body.getJSONObject("mandate_summary")
        assertEquals("agent-local-v2", mandate.getString("id"))
        assertEquals("proposals_only", mandate.getString("mode"))
        val plan = body.getJSONObject("plan")
        assertEquals(PLAN_ID, plan.getString("plan_id"))
        assertEquals(PLAN_DATE, plan.getString("local_date"))
        assertEquals("Europe/Paris", plan.getString("timezone"))
        val activity = plan.getJSONArray("activities").getJSONObject(0)
        assertEquals(ACTIVITY_ID, activity.getString("activity_id"))
        assertEquals(GOAL_ID, activity.getString("goal_id"))
        assertFalse(activity.has("notes"))
        assertFalse(activity.has("sub_goal_id"))
    }

    @Test
    fun mapsRequiredHttpStatusesWithoutRetrying() {
        val expected = mapOf(
            401 to PlanRevisionApiResult.Unauthorized,
            403 to PlanRevisionApiResult.Forbidden,
            409 to PlanRevisionApiResult.Conflict,
            422 to PlanRevisionApiResult.Unprocessable,
            503 to PlanRevisionApiResult.ServerError(503),
        )
        expected.forEach { (status, resultExpected) ->
            val requests = AtomicInteger()
            val server = server { exchange ->
                requests.incrementAndGet()
                exchange.requestBody.use { it.readBytes() }
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            try {
                assertEquals(resultExpected, client(server).propose(request()))
                assertEquals(1, requests.get())
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun reportsInvalidOversizedAndTimedOutResponsesExplicitly() {
        val malformed = server { exchange ->
            exchange.requestBody.use { it.readBytes() }
            reply(exchange, 200, "{")
        }
        try {
            assertEquals(PlanRevisionApiResult.InvalidResponse, client(malformed).propose(request()))
        } finally {
            malformed.stop(0)
        }

        val oversized = server { exchange ->
            exchange.requestBody.use { it.readBytes() }
            reply(exchange, 200, "x".repeat(PlanRevisionProposalV3.MAX_RESPONSE_BYTES + 1))
        }
        try {
            assertEquals(PlanRevisionApiResult.ResponseTooLarge, client(oversized).propose(request()))
        } finally {
            oversized.stop(0)
        }

        val release = CountDownLatch(1)
        val timeout = server { exchange ->
            exchange.requestBody.use { it.readBytes() }
            release.await(2, TimeUnit.SECONDS)
            runCatching { reply(exchange, 200, responseWithoutProposal()) }
        }
        try {
            assertEquals(PlanRevisionApiResult.Timeout, client(timeout, readTimeout = 100).propose(request()))
        } finally {
            release.countDown()
            timeout.stop(0)
        }
    }

    @Test
    fun preservesUnsupportedProposalFieldCodeFromTheStrictParser() {
        val server = server { exchange ->
            exchange.requestBody.use { it.readBytes() }
            reply(exchange, 200, responseWithUnsupportedField())
        }
        try {
            assertEquals(
                PlanRevisionApiResult.InvalidProposal("UNSUPPORTED_PROPOSAL_FIELD"),
                client(server).propose(request()),
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun refusesMissingConsentAndInvalidRequestIdsBeforeNetwork() {
        val server = server { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        try {
            assertEquals(
                PlanRevisionApiResult.InvalidRequest,
                client(server).propose(request().copy(planContextConsented = false)),
            )
            assertEquals(
                PlanRevisionApiResult.InvalidRequest,
                client(server).propose(request().copy(requestId = "not-a-uuid")),
            )
        } finally {
            server.stop(0)
        }
    }

    private fun request() = PlanRevisionApiRequest(
        requestId = REQUEST_ID,
        conversationId = null,
        message = "Planifie la journée",
        planContextConsented = true,
        mandate = AgentMandate.default(),
        plan = dayPlan(),
    )

    private fun client(server: HttpServer, readTimeout: Int = 2_000) = PlanRevisionApiClient(
        baseUrl = "http://127.0.0.1:${server.address.port}",
        tokenProvider = BearerTokenProvider { TOKEN },
        connectTimeoutMillis = 500,
        readTimeoutMillis = readTimeout,
    )

    private fun server(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { server ->
            server.createContext("/") { exchange ->
                try {
                    handler(exchange)
                } catch (_: Exception) {
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

    private fun responseWithoutProposal() = JSONObject()
        .put("schema_version", 3)
        .put("reply", "Aucune proposition.")
        .put("agent_status", "ok")
        .put("proposal", JSONObject.NULL)
        .put("overlaps", org.json.JSONArray())
        .toString()

    private fun responseWithUnsupportedField() = JSONObject()
        .put("schema_version", 3)
        .put("reply", "Proposition")
        .put("agent_status", "ok")
        .put("proposal", JSONObject()
            .put("schema_version", 3)
            .put("proposal_id", PROPOSAL_ID)
            .put("idempotency_key", "idempotency-1")
            .put("conversation_id", JSONObject.NULL)
            .put("mandate_id", "mandate-local")
            .put("mandate_version", 1)
            .put("created_at", "2026-10-26T10:00:00Z")
            .put("expires_at", "2026-10-27T10:00:00.000000Z")
            .put("required_level", "confirm")
            .put("state", "proposed")
            .put("proposal", JSONObject()
                .put("action_type", "plan.revise_day")
                .put("reason", "Réviser le jour")
                .put("parameters", JSONObject()
                    .put("plan_id", PLAN_ID)
                    .put("base_version", 1)
                    .put("local_date", PLAN_DATE)
                    .put("timezone", "Europe/Paris")
                    .put("summary", "Révision")
                    .put("activities", org.json.JSONArray().put(JSONObject()
                        .put("client_ref", "ref-1")
                        .put("activity_id", ACTIVITY_ID)
                        .put("kind", "FOCUS")
                        .put("title", "Activité")
                        .put("local_date", PLAN_DATE)
                        .put("start_minute", 540)
                        .put("duration_minutes", 30)
                        .put("timezone", "Europe/Paris")
                        .put("priority", "IMPORTANT")
                        .put("goal_id", JSONObject.NULL)
                        .put("sub_goal_id", JSONObject.NULL)
                        .put("training_ref", JSONObject.NULL)
                        .put("focus", JSONObject().put("cycles", 1))
                        .put("notes", JSONObject.NULL)))))))
        .put("overlaps", org.json.JSONArray())
        .toString()

    companion object {
        private const val TOKEN = "secret-test-token-never-log"
        private const val REQUEST_ID = "80000000-0000-4000-8000-000000000001"
        private const val PROPOSAL_ID = "80000000-0000-4000-8000-000000000002"
    }
}
