package dev.atlas.coach.sync.agent.coach

import dev.atlas.coach.sync.BearerTokenProvider
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.ProposalV2
import dev.atlas.coach.sync.agent.context.AgentContextSnapshot
import dev.atlas.coach.coach.proactive.ProactivePlanRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

enum class AgentStatus(val wireValue: String) {
    CONNECTED("connected"), UNAVAILABLE("unavailable"), MOCK("mock");

    companion object { fun parse(value: String) = entries.firstOrNull { it.wireValue == value } }
}

data class AgentChatResponse(
    val reply: String,
    val proposals: List<ProposalV2>,
    val status: AgentStatus,
    /** The exact minimized shared_context body written to this request. */
    val sharedContextJson: String? = null,
)

/** A fresh, read-only authorization and data snapshot assembled immediately before HTTP write. */
data class AgentRequestContext(
    val mandate: AgentMandate,
    val snapshot: AgentContextSnapshot,
    val consentAtUtcMillis: Map<String, Long>,
    val domainConsent: dev.atlas.coach.sync.agent.context.AgentContextDomainConsent,
    val nowUtcMillis: Long,
) {
    fun minimizedSnapshot(): AgentContextSnapshot {
        val consentBoundMandate = mandate.copy(
            sharedDataCategories = mandate.sharedDataCategories.filterTo(linkedSetOf()) { category ->
                consentAtUtcMillis[category]?.let { it in 1L..nowUtcMillis } == true
            },
        )
        return snapshot.minimizeFor(
            currentMandate = consentBoundMandate,
            currentConsentAtUtcMillis = consentAtUtcMillis,
            nowUtcMillis = nowUtcMillis,
            currentDomainConsent = domainConsent,
        )
    }
}

data class AgentInboxResponse(val proposals: List<ProposalV2>, val cursor: String?)

class AgentApiException(message: String, val statusCode: Int? = null, cause: Throwable? = null) : IOException(message, cause)

/** HTTP v2 client. It reuses the configured backend URL and Keystore-backed Bearer provider. */
class AgentApiClient(
    baseUrl: String,
    private val tokenProvider: BearerTokenProvider,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS.toInt(),
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS.toInt(),
    private val freshRequestContext: ((AgentContextSnapshot) -> AgentRequestContext?)? = null,
    private val onContextWritten: ((AgentContextSnapshot) -> Unit)? = null,
    private val requestAllowed: () -> Boolean = { true },
) {
    private val base = baseUrl.trimEnd('/')

    /** Test-only pause at the ACK body boundary, after requestAllowed and before its epoch check. */
    @Volatile internal var beforeAckBodySerializationForTesting: (() -> Unit)? = null

    init {
        val uri = URI(base)
        require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank())
        require(uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0)
    }

    fun chat(
        conversationId: String?,
        message: String,
        mandate: AgentMandate,
    ): AgentChatResponse = chat(
        conversationId = conversationId,
        message = message,
        mandate = mandate,
        context = emptyContext(mandate),
    )

    fun chat(
        conversationId: String?,
        message: String,
        mandate: AgentMandate,
        context: AgentContextSnapshot,
    ): AgentChatResponse = sendContextual(
        path = "/v2/agent/chat",
        conversationId = conversationId,
        message = message,
        mandate = mandate,
        context = context,
    )

    fun plan(
        conversationId: String,
        mandate: AgentMandate,
        context: AgentContextSnapshot,
    ): AgentChatResponse = sendContextual(
        path = "/v2/agent/plan",
        conversationId = conversationId,
        message = PLANNED_REQUEST,
        mandate = mandate,
        context = context,
    )

    /** Proactive callers re-check their independent capability at the last safe point before write. */
    fun plan(
        conversationId: String,
        mandate: AgentMandate,
        context: AgentContextSnapshot,
        allowSend: () -> Boolean,
    ): AgentChatResponse = sendContextual(
        path = "/v2/agent/plan",
        conversationId = conversationId,
        message = PLANNED_REQUEST,
        mandate = mandate,
        context = context,
        allowSend = allowSend,
    )

    /**
     * A proactive plan uses the existing plan route and a bounded structured request message.
     * Trigger identifiers, motifs, and domain content are deliberately omitted.
     */
    fun plan(
        conversationId: String,
        mandate: AgentMandate,
        context: AgentContextSnapshot,
        request: ProactivePlanRequest,
        allowSend: () -> Boolean,
    ): AgentChatResponse {
        require(request.triggers.isNotEmpty())
        require(request.triggers.all { it.reliability == dev.atlas.coach.coach.proactive.CoachTriggerReliability.VERIFIED })
        return sendContextual(
            path = "/v2/agent/plan",
            conversationId = conversationId,
            message = proactivePlanMessage(request),
            mandate = mandate,
            context = context,
            allowSend = allowSend,
        )
    }

    private fun sendContextual(
        path: String,
        conversationId: String?,
        message: String,
        mandate: AgentMandate,
        context: AgentContextSnapshot,
        allowSend: (() -> Boolean)? = null,
    ): AgentChatResponse {
        require(message.isNotBlank() && message.length <= 2_000)
        var sentSnapshot = context
        val result = request("POST", path, bodyFactory = {
            if (allowSend != null && !runCatching(allowSend).getOrDefault(false)) {
                throw AgentApiException("Proactive plan permission is no longer active", 403)
            }
            val requestContext = if (freshRequestContext != null) {
                freshRequestContext.invoke(context) ?: throw AgentApiException("Agent mandate is no longer active", 403)
            } else null
            val currentMandate = requestContext?.mandate ?: mandate
            if (currentMandate.revoked || currentMandate.suspended ||
                System.currentTimeMillis() >= currentMandate.expiresAtUtcMillis
            ) throw AgentApiException("Agent mandate is no longer active", 403)
            val snapshot = requestContext?.minimizedSnapshot() ?: context
            sentSnapshot = snapshot
            RequestBody(
                json = chatBody(conversationId, message, currentMandate, snapshot),
                afterWrite = { onContextWritten?.invoke(snapshot) },
            )
        })
        val reply = result.optString("reply")
        require(reply.length <= MAX_REPLY_LENGTH) { "Agent reply is too long" }
        val rawProposals = result.optJSONArray("proposals") ?: error("Agent response has no proposals array")
        require(rawProposals.length() <= MAX_PROPOSALS)
        val proposals = (0 until rawProposals.length()).map { ProposalV2.parse(rawProposals.getJSONObject(it).toString()) }
        val status = AgentStatus.parse(result.optString("agent_status")) ?: error("Unknown agent_status")
        return AgentChatResponse(reply, proposals, status, sentSnapshot.toJsonString())
    }

    fun pullInbox(after: String?): AgentInboxResponse {
        val suffix = after?.takeIf { it.isNotBlank() }?.let {
            "?after=" + URLEncoder.encode(it, StandardCharsets.UTF_8.name())
        }.orEmpty()
        val result = request("GET", "/v2/agent/inbox$suffix")
        val rawProposals = result.optJSONArray("proposals") ?: error("Inbox response has no proposals array")
        require(rawProposals.length() <= MAX_PROPOSALS_PER_PULL)
        val proposals = (0 until rawProposals.length()).map { ProposalV2.parse(rawProposals.getJSONObject(it).toString()) }
        val cursorValue = result.opt("cursor")
        val cursor = when (cursorValue) {
            null, JSONObject.NULL -> null
            is String -> cursorValue
            else -> error("Invalid inbox cursor")
        }
        return AgentInboxResponse(proposals, cursor)
    }

    @JvmOverloads
    fun acknowledge(
        proposalId: String,
        state: String,
        effect: String?,
        atUtcMillis: Long,
        authorizationAllowed: (() -> Boolean)? = null,
    ) {
        require(state in setOf("accepted", "executed", "rejected", "expired", "cancelled"))
        val pathId = URLEncoder.encode(proposalId, StandardCharsets.UTF_8.name()).replace("+", "%20")
        request("POST", "/v2/agent/proposals/$pathId/ack", bodyFactory = {
            beforeAckBodySerializationForTesting?.invoke()
            if (authorizationAllowed != null && !runCatching(authorizationAllowed).getOrDefault(false)) {
                throw AgentApiException("Proactive acknowledgement authorization is no longer active", 403)
            }
            RequestBody(JSONObject()
                .put("state", state)
                .put("effect", effect ?: JSONObject.NULL)
                .put("at", Instant.ofEpochMilli(atUtcMillis).toString()))
        })
    }

    /** The backend requires the accepted transition before an executed acknowledgement. */
    @JvmOverloads
    fun acknowledgeExecution(
        proposalId: String,
        effect: String?,
        atUtcMillis: Long,
        authorizationAllowed: (() -> Boolean)? = null,
    ) {
        try {
            acknowledge(proposalId, "accepted", null, atUtcMillis, authorizationAllowed)
        } catch (error: AgentApiException) {
            // A retry may find that a prior call already advanced the proposal to executed.
            // The terminal acknowledgement below is idempotent and confirms the resulting state.
            if (error.statusCode != 409) throw error
        }
        acknowledge(proposalId, "executed", effect, atUtcMillis, authorizationAllowed)
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        bodyFactory: (() -> RequestBody)? = null,
    ): JSONObject {
        if (!runCatching(requestAllowed).getOrDefault(false)) {
            throw AgentApiException("Agent access is no longer active", 403)
        }
        val token = tokenProvider.token()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw AgentApiException("Bearer token is not configured", 401)
        val connection = try { URL(base + path).openConnection() as HttpURLConnection }
        catch (error: Exception) { throw AgentApiException("Agent endpoint is unavailable", cause = error) }
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            val actualBody = bodyFactory?.invoke() ?: body?.let { RequestBody(it) }
            if (actualBody != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(actualBody.json.toString()) }
                actualBody.afterWrite?.invoke()
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw AgentApiException("Agent backend returned HTTP $status", status)
            if (response.isBlank()) return JSONObject()
            return try { JSONObject(response) } catch (error: Exception) {
                throw AgentApiException("Agent backend returned invalid JSON", status, error)
            }
        } catch (error: AgentApiException) {
            throw error
        } catch (error: Exception) {
            throw AgentApiException("Agent backend request failed", cause = error)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 4_000L
        const val DEFAULT_READ_TIMEOUT_MILLIS = 65_000L
        const val DEFAULT_HTTP_TRANSPORT_TIMEOUT_BUDGET_MILLIS =
            DEFAULT_CONNECT_TIMEOUT_MILLIS + DEFAULT_READ_TIMEOUT_MILLIS
        private const val MAX_REPLY_LENGTH = 4_000
        private const val MAX_PROPOSALS = 5
        private const val MAX_PROPOSALS_PER_PULL = 100
        const val PLANNED_REQUEST = "Que dois-je faire maintenant ?"

        private fun proactivePlanMessage(request: ProactivePlanRequest): String {
            val triggerSummaries = request.triggers
                .groupBy { it.source }
                .entries
                .sortedBy { it.key.wireValue }
                .map { (source, events) ->
                    JSONObject()
                        .put("source", source.wireValue)
                        .put("occurred_at_utc", Instant.ofEpochMilli(events.maxOf { it.occurredAtUtcMillis }).toString())
                }
            return JSONObject()
                .put("request", PLANNED_REQUEST)
                .put("proactive_plan", JSONObject()
                    .put("mode", request.mode.wireValue)
                    .put("triggers", JSONArray(triggerSummaries)))
                .toString()
                .also { require(it.length <= 2_000) { "Programmatic plan request is too long" } }
        }

        private fun emptyContext(mandate: AgentMandate): AgentContextSnapshot =
            dev.atlas.coach.sync.agent.context.AgentContextAssembler().assemble(
                currentMandate = mandate,
                consentAtUtcMillis = emptyMap(),
                domains = dev.atlas.coach.sync.agent.context.AgentContextDomainViews(),
                nowUtcMillis = System.currentTimeMillis().coerceAtLeast(1L),
            )

        private fun chatBody(
            conversationId: String?,
            message: String,
            mandate: AgentMandate,
            context: AgentContextSnapshot,
        ): JSONObject = JSONObject()
            .put("conversation_id", conversationId ?: JSONObject.NULL)
            .put("message", message)
            .put("shared_context", JSONObject(context.toJsonString()))
            .put("mandate_summary", JSONObject()
                .put("id", mandate.id)
                .put("version", mandate.version)
                .put("mode", mandate.mode.wireValue)
                .put("allowed_actions", JSONArray(mandate.allowedActions.sorted()))
                .put("focus_window", JSONObject()
                    .put("days", JSONArray(mandate.focusWindow.days.sorted().map(::weekdayName)))
                    .put("starts_at", mandate.focusWindow.start)
                    .put("ends_at", mandate.focusWindow.end)
                    .put("timezone", mandate.focusWindow.timezone)
                    .put("max_minutes_per_session", mandate.focusWindow.maxMinutesPerSession)
                    .put("max_sessions_per_day", mandate.focusWindow.maxSessionsPerDay))
                .put("app_policy_bounds", JSONObject().also { bounds ->
                    mandate.appPolicyBounds.toSortedMap().forEach { (packageRef, bound) ->
                        bounds.put(packageRef, JSONObject()
                            .put("min_limit_minutes", bound.minLimitMinutes)
                            .put("max_mode", bound.maxMode)
                            .put("max_valid_days", bound.maxValidDays))
                    }
                }))

        private data class RequestBody(
            val json: JSONObject,
            val afterWrite: (() -> Unit)? = null,
        )

        private fun weekdayName(day: Int): String = when (day) {
            1 -> "monday"
            2 -> "tuesday"
            3 -> "wednesday"
            4 -> "thursday"
            5 -> "friday"
            6 -> "saturday"
            7 -> "sunday"
            else -> error("Invalid weekday in local focus mandate")
        }
    }
}
