package dev.atlas.coach.plan

import dev.atlas.coach.sync.BearerTokenProvider
import dev.atlas.coach.sync.agent.AgentMandate
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class PlanRevisionApiRequest(
    val requestId: String,
    val conversationId: String?,
    val message: String,
    val planContextConsented: Boolean,
    val mandate: AgentMandate,
    val plan: DayPlan,
)

fun interface PlanRevisionProposalClient {
    fun propose(request: PlanRevisionApiRequest): PlanRevisionApiResult
}

sealed interface PlanRevisionApiResult {
    data class Success(val response: PlanRevisionAgentResponseV3) : PlanRevisionApiResult
    data object Unauthorized : PlanRevisionApiResult
    data object Forbidden : PlanRevisionApiResult
    data object Conflict : PlanRevisionApiResult
    data object Unprocessable : PlanRevisionApiResult
    data class ServerError(val statusCode: Int) : PlanRevisionApiResult
    data class HttpRejected(val statusCode: Int) : PlanRevisionApiResult
    data object Timeout : PlanRevisionApiResult
    data object InvalidResponse : PlanRevisionApiResult
    data class InvalidProposal(val code: String) : PlanRevisionApiResult
    data object ResponseTooLarge : PlanRevisionApiResult
    data object Unavailable : PlanRevisionApiResult
    data object InvalidRequest : PlanRevisionApiResult
}

/** Proposal-only V3 endpoint client. It never retries and never logs the token or body. */
class PlanRevisionApiClient(
    baseUrl: String,
    private val tokenProvider: BearerTokenProvider,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val requestAllowed: () -> Boolean = { true },
) : PlanRevisionProposalClient {
    private val base = baseUrl.trimEnd('/')

    init {
        val uri = URI(base)
        require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank())
        require(uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0)
    }

    override fun propose(request: PlanRevisionApiRequest): PlanRevisionApiResult {
        if (!validRequest(request)) return PlanRevisionApiResult.InvalidRequest
        if (!isAllowed()) return PlanRevisionApiResult.Forbidden
        val token = try {
            tokenProvider.token()?.trim()?.takeIf(String::isNotEmpty)
        } catch (_: Exception) {
            null
        } ?: return PlanRevisionApiResult.Unauthorized

        val connection = try {
            URL(base + PATH).openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return PlanRevisionApiResult.Unavailable
        }
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true
            if (!isAllowed()) return PlanRevisionApiResult.Forbidden
            connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use {
                it.write(requestBody(request).toString())
            }

            val status = connection.responseCode
            if (status !in 200..299) return statusResult(status)
            val bytes = try {
                readBounded(connection)
            } catch (_: ResponseLimitException) {
                return PlanRevisionApiResult.ResponseTooLarge
            }
            val responseText = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: Exception) {
                return PlanRevisionApiResult.InvalidResponse
            }
            val response = try {
                PlanRevisionProposalV3.parse(responseText)
            } catch (error: PlanRevisionProposalParseException) {
                return if (error.code == PlanRevisionProposalV3.UNSUPPORTED_PROPOSAL_FIELD) {
                    PlanRevisionApiResult.InvalidProposal(error.code)
                } else {
                    PlanRevisionApiResult.InvalidResponse
                }
            } catch (_: Exception) {
                return PlanRevisionApiResult.InvalidResponse
            }
            return PlanRevisionApiResult.Success(response)
        } catch (_: SocketTimeoutException) {
            return PlanRevisionApiResult.Timeout
        } catch (_: IOException) {
            return PlanRevisionApiResult.Unavailable
        } catch (_: Exception) {
            return PlanRevisionApiResult.Unavailable
        } finally {
            connection.disconnect()
        }
    }

    private fun validRequest(request: PlanRevisionApiRequest): Boolean {
        if (!request.planContextConsented || request.message.isBlank() || request.message.length > 2_000) return false
        if (request.conversationId != null && request.conversationId.length > 80) return false
        if (!isCanonicalUuid(request.requestId)) return false
        val mandate = request.mandate
        if (mandate.id.isBlank() || mandate.id.length > 80 || mandate.version < 1L) return false
        if (mandate.allowedActions.size > 10) return false
        if (mandate.revoked || mandate.suspended || System.currentTimeMillis() >= mandate.expiresAtUtcMillis) return false
        if (request.plan.state !in setOf(PlanState.DRAFT, PlanState.APPROVED)) return false
        if (request.plan.activities.size > PlanRevisionProposalV3.MAX_ACTIVITIES) return false
        return true
    }

    private fun requestBody(request: PlanRevisionApiRequest): JSONObject = JSONObject()
        .put("schema_version", 3)
        .put("request_id", request.requestId)
        .put("conversation_id", request.conversationId ?: JSONObject.NULL)
        .put("message", request.message)
        .put("plan_context_consented", true)
        .put("mandate_summary", JSONObject()
            .put("id", request.mandate.id)
            .put("version", request.mandate.version)
            .put("allowed_actions", JSONArray(request.mandate.allowedActions.sorted()))
            .put("mode", request.mandate.mode.wireValue))
        .put("plan", JSONObject()
            .put("plan_id", request.plan.planId)
            .put("local_date", request.plan.localDate)
            .put("timezone", request.plan.timezone)
            .put("version", request.plan.version)
            .put("state", request.plan.state.name)
            .put("activities", JSONArray().also { array ->
                request.plan.activities.forEach { activity ->
                    array.put(JSONObject()
                        .put("activity_id", activity.activityId)
                        .put("kind", activity.kind.name)
                        .put("title", activity.title)
                        .put("start_minute", activity.scheduledLocal.startMinute)
                        .put("duration_minutes", activity.scheduledLocal.durationMinutes)
                        .put("priority", activity.priority.name)
                        .put("goal_id", activity.goalId ?: JSONObject.NULL))
                }
            }))

    private fun readBounded(connection: HttpURLConnection): ByteArray {
        val stream = connection.inputStream
        val output = ByteArrayOutputStream()
        stream.use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > PlanRevisionProposalV3.MAX_RESPONSE_BYTES) {
                    throw ResponseLimitException()
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun statusResult(status: Int): PlanRevisionApiResult = when (status) {
        401 -> PlanRevisionApiResult.Unauthorized
        403 -> PlanRevisionApiResult.Forbidden
        409 -> PlanRevisionApiResult.Conflict
        422 -> PlanRevisionApiResult.Unprocessable
        in 500..599 -> PlanRevisionApiResult.ServerError(status)
        else -> PlanRevisionApiResult.HttpRejected(status)
    }

    private fun isAllowed(): Boolean = try {
        requestAllowed()
    } catch (_: Exception) {
        false
    }

    private fun isCanonicalUuid(value: String): Boolean = try {
        java.util.UUID.fromString(value).toString() == value
    } catch (_: Exception) {
        false
    }

    private class ResponseLimitException : IOException()

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 4_000
        const val DEFAULT_READ_TIMEOUT_MILLIS = 65_000
        const val PATH = "/v3/agent/plan-revision"
    }
}
