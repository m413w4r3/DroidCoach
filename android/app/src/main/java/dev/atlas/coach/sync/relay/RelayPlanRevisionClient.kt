package dev.atlas.coach.sync.relay

import dev.atlas.coach.plan.PlanRevisionApiRequest
import dev.atlas.coach.plan.PlanRevisionApiResult
import dev.atlas.coach.plan.PlanRevisionProposalClient
import dev.atlas.coach.plan.PlanRevisionProposalV3
import dev.atlas.coach.plan.PlanRevisionProposalParseException
import dev.atlas.coach.plan.PlanState
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Plan revision adapter over atlas.remote.v1; it preserves proposal-only local validation. */
class RelayPlanRevisionClient(
    private val transport: RelayBackendTransport,
    private val requestAllowed: () -> Boolean = { true },
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val requestTimeoutMillis: Long = RelayBackendTransport.DEFAULT_REQUEST_TIMEOUT_MILLIS,
) : PlanRevisionProposalClient {
    override fun propose(request: PlanRevisionApiRequest): PlanRevisionApiResult {
        if (!validRequest(request)) return PlanRevisionApiResult.InvalidRequest
        if (!isAllowed()) return PlanRevisionApiResult.Forbidden
        val payload = try { requestBody(request) } catch (_: Exception) {
            return PlanRevisionApiResult.InvalidRequest
        }
        if (payload.toString().toByteArray(Charsets.UTF_8).size > AtlasRemoteProtocol.MAX_REQUEST_BYTES) {
            return PlanRevisionApiResult.InvalidRequest
        }
        return when (val result = transport.execute(
            operation = "plan.revision.request",
            payload = payload,
            timeoutMillis = requestTimeoutMillis,
            idempotencyKey = request.requestId,
            beforeDispatch = { validRequest(request) && isAllowed() },
        )) {
            is RelayCallResult.Success -> parseProposal(result.result)
            is RelayCallResult.RemoteError -> mapRemoteError(result)
            is RelayCallResult.Failure -> when (result.reason) {
                RelayCallResult.Failure.Reason.TIMEOUT,
                RelayCallResult.Failure.Reason.EXPIRED -> PlanRevisionApiResult.Timeout
                RelayCallResult.Failure.Reason.PROTOCOL -> PlanRevisionApiResult.InvalidResponse
                RelayCallResult.Failure.Reason.POLICY_DENIED -> PlanRevisionApiResult.Forbidden
                else -> when (transport.state()) {
                    RelayConnectionState.AUTH_REQUIRED -> PlanRevisionApiResult.Unauthorized
                    RelayConnectionState.REVOKED -> PlanRevisionApiResult.Forbidden
                    else -> PlanRevisionApiResult.Unavailable
                }
            }
        }
    }

    private fun parseProposal(result: JSONObject): PlanRevisionApiResult = try {
        PlanRevisionApiResult.Success(PlanRevisionProposalV3.parse(result.toString()))
    } catch (error: PlanRevisionProposalParseException) {
        if (error.code == PlanRevisionProposalV3.UNSUPPORTED_PROPOSAL_FIELD) {
            PlanRevisionApiResult.InvalidProposal(error.code)
        } else PlanRevisionApiResult.InvalidResponse
    } catch (_: Exception) {
        PlanRevisionApiResult.InvalidResponse
    }

    private fun mapRemoteError(error: RelayCallResult.RemoteError): PlanRevisionApiResult = when (error.code) {
        "AUTH_REQUIRED", "AUTH_FAILED" -> PlanRevisionApiResult.Unauthorized
        "DEVICE_REVOKED" -> PlanRevisionApiResult.Forbidden
        "IDEMPOTENCY_CONFLICT" -> PlanRevisionApiResult.Conflict
        "EXPIRED", "CANCELLED" -> PlanRevisionApiResult.Timeout
        "INVALID_REQUEST", "PAYLOAD_TOO_LARGE" -> PlanRevisionApiResult.InvalidRequest
        "BACKEND_UNAVAILABLE" -> PlanRevisionApiResult.ServerError(503)
        "BACKEND_REJECTED" -> PlanRevisionApiResult.HttpRejected(502)
        "INTERNAL" -> PlanRevisionApiResult.ServerError(500)
        "UNKNOWN_OPERATION", "OPERATION_UNAVAILABLE" -> PlanRevisionApiResult.Unavailable
        else -> PlanRevisionApiResult.InvalidResponse
    }

    private fun validRequest(request: PlanRevisionApiRequest): Boolean {
        if (!request.planContextConsented || request.message.isBlank() || request.message.length > 2_000) return false
        if (request.conversationId != null && request.conversationId.length > 80) return false
        if (!AtlasRemoteProtocol.isUuid(request.requestId)) return false
        val mandate = request.mandate
        if (mandate.id.isBlank() || mandate.id.length > 80 || mandate.version < 1L) return false
        if (mandate.allowedActions.size > 10 || mandate.revoked || mandate.suspended || clockMillis() >= mandate.expiresAtUtcMillis) return false
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

    private fun isAllowed(): Boolean = try { requestAllowed() } catch (_: Exception) { false }
}
