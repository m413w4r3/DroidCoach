package dev.atlas.coach.sync.agent.coach

import dev.atlas.coach.sync.BearerTokenProvider
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.ProposalV2
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
)

data class AgentInboxResponse(val proposals: List<ProposalV2>, val cursor: String?)

class AgentApiException(message: String, val statusCode: Int? = null, cause: Throwable? = null) : IOException(message, cause)

/** HTTP v2 client. It reuses the configured backend URL and Keystore-backed Bearer provider. */
class AgentApiClient(
    baseUrl: String,
    private val tokenProvider: BearerTokenProvider,
    private val connectTimeoutMillis: Int = 4_000,
    private val readTimeoutMillis: Int = 65_000,
) {
    private val base = baseUrl.trimEnd('/')

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
    ): AgentChatResponse {
        require(message.isNotBlank() && message.length <= 2_000)
        val body = JSONObject()
            .put("conversation_id", conversationId ?: JSONObject.NULL)
            .put("message", message)
            .put("shared_context", JSONObject()
                .put("consented_categories", JSONArray(mandate.sharedDataCategories.sorted()))
                .put("categories", JSONObject()))
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
        val result = request("POST", "/v2/agent/chat", body)
        val reply = result.optString("reply")
        require(reply.length <= MAX_REPLY_LENGTH) { "Agent reply is too long" }
        val rawProposals = result.optJSONArray("proposals") ?: error("Agent response has no proposals array")
        require(rawProposals.length() <= MAX_PROPOSALS)
        val proposals = (0 until rawProposals.length()).map { ProposalV2.parse(rawProposals.getJSONObject(it).toString()) }
        val status = AgentStatus.parse(result.optString("agent_status")) ?: error("Unknown agent_status")
        return AgentChatResponse(reply, proposals, status)
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

    fun acknowledge(proposalId: String, state: String, effect: String?, atUtcMillis: Long) {
        require(state in setOf("accepted", "executed", "rejected", "expired", "cancelled"))
        val pathId = URLEncoder.encode(proposalId, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val body = JSONObject()
            .put("state", state)
            .put("effect", effect ?: JSONObject.NULL)
            .put("at", Instant.ofEpochMilli(atUtcMillis).toString())
        request("POST", "/v2/agent/proposals/$pathId/ack", body)
    }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
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
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
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
        private const val MAX_REPLY_LENGTH = 4_000
        private const val MAX_PROPOSALS = 5
        private const val MAX_PROPOSALS_PER_PULL = 100

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
