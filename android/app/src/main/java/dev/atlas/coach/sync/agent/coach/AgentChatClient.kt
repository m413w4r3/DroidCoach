package dev.atlas.coach.sync.agent.coach

import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.AgentActionType
import dev.atlas.coach.sync.agent.ProposalLevel
import dev.atlas.coach.sync.agent.ProposalV2
import dev.atlas.coach.sync.agent.context.AgentContextSnapshot
import dev.atlas.coach.sync.relay.RelayCallResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** Common boundary used by DIRECT HTTP and RELAY conversation sends. */
interface AgentChatClient {
    fun chat(
        conversationId: String?,
        message: String,
        mandate: AgentMandate,
        context: AgentContextSnapshot,
        idempotencyKey: String,
    ): AgentChatResponse
}

/** The one canonical JSON body builder for HTTP and relay chat requests. */
object AgentChatRequestBody {
    fun build(
        conversationId: String?,
        message: String,
        mandate: AgentMandate,
        context: AgentContextSnapshot,
    ): JSONObject {
        require(message.isNotBlank() && message.length <= MAX_MESSAGE_LENGTH)
        return JSONObject()
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
    }

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

    private const val MAX_MESSAGE_LENGTH = 2_000
}

/** Shared response validation for both transports. */
object AgentChatResponseParser {
    fun parse(result: JSONObject, sentSnapshot: AgentContextSnapshot): AgentChatResponse {
        try {
            val reply = result.opt("reply") as? String ?: error("Agent response has no reply string")
            require(reply.length <= MAX_REPLY_LENGTH) { "Agent reply is too long" }
            val rawProposals = result.opt("proposals") as? JSONArray ?: error("Agent response has no proposals array")
            require(rawProposals.length() <= MAX_PROPOSALS) { "Agent response has too many proposals" }
            val proposals = (0 until rawProposals.length()).map { index ->
                ProposalV2.parse(rawProposals.getJSONObject(index).toString())
            }
            val rawStatus = result.opt("agent_status") as? String ?: error("Agent response has no agent_status string")
            val status = AgentStatus.parse(rawStatus) ?: error("Unknown agent_status")
            return AgentChatResponse(reply, proposals, status, sentSnapshot.toJsonString())
        } catch (error: AgentChatException) {
            throw error
        } catch (error: Exception) {
            throw AgentChatException(AgentChatFailureKind.INVALID_RESPONSE, "La réponse de conversation est malformée.", cause = error)
        }
    }

    private const val MAX_REPLY_LENGTH = 4_000
    private const val MAX_PROPOSALS = 5
}

enum class AgentChatFailureKind {
    OPERATION_UNAVAILABLE,
    OFFLINE,
    RESULT_UNCERTAIN,
    CANCELLED,
    POLICY_DENIED,
    INVALID_RESPONSE,
    REMOTE_ERROR,
}

enum class AgentChatSendState { IDLE, SENDING, CANCELLED, OFFLINE, ERROR, RESULT_UNCERTAIN }

/** Deterministic no-network chat adapter retained for the OFFLINE demonstration mode. */
class LocalMockAgentChatClient(
    private val nowUtcMillis: () -> Long = System::currentTimeMillis,
) : AgentChatClient {
    override fun chat(
        conversationId: String?,
        message: String,
        mandate: AgentMandate,
        context: AgentContextSnapshot,
        idempotencyKey: String,
    ): AgentChatResponse {
        require(message.isNotBlank() && message.length <= 2_000)
        val now = nowUtcMillis().coerceAtLeast(1L)
        val proposal = ProposalV2(
            schemaVersion = 2,
            proposalId = UUID.nameUUIDFromBytes("atlas-local-agent-chat:$idempotencyKey".toByteArray()).toString(),
            idempotencyKey = "local-$idempotencyKey".take(160),
            conversationId = conversationId,
            mandateId = mandate.id,
            mandateVersion = mandate.version,
            contextVersion = "local-demo-v1",
            createdAtUtcMillis = now,
            expiresAtUtcMillis = now + ProposalV2.MAX_TTL_MILLIS,
            actionType = AgentActionType.FOCUS_PROPOSE.wireValue,
            parameters = mapOf("focus_minutes" to 10, "break_minutes" to 2, "cycles" to 1, "label" to "Prochaine étape"),
            reason = "Une courte session locale est une prochaine étape simple.",
            requiredLevel = ProposalLevel.CONFIRM,
        )
        return AgentChatResponse(
            reply = "Je peux vous proposer une session de focus de 10 minutes. Cette démonstration fonctionne localement et n'envoie aucune donnée.",
            proposals = listOf(proposal),
            status = AgentStatus.MOCK,
            sharedContextJson = context.toJsonString(),
        )
    }
}

class AgentChatException(
    val kind: AgentChatFailureKind,
    message: String,
    val remoteCode: String? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Injectable boundary keeps relay chat tests independent of real sockets. */
fun interface RelayChatRequestExecutor {
    fun execute(
        idempotencyKey: String,
        payloadFactory: () -> JSONObject,
        beforeDispatch: () -> Boolean,
    ): RelayCallResult
}

/** Builds a fresh, consent-filtered payload at relay dispatch time and never retries a sent request. */
class RelayAgentChatClient(
    private val executor: RelayChatRequestExecutor,
    private val freshRequestContext: (AgentContextSnapshot) -> AgentRequestContext?,
    private val onContextWritten: (AgentContextSnapshot) -> Boolean,
    private val requestAllowed: () -> Boolean = { true },
) : AgentChatClient {
    override fun chat(
        conversationId: String?,
        message: String,
        mandate: AgentMandate,
        context: AgentContextSnapshot,
        idempotencyKey: String,
    ): AgentChatResponse {
        require(message.isNotBlank() && message.length <= 2_000)
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160)
        var dispatchMandate: AgentMandate? = null
        var dispatchSnapshot: AgentContextSnapshot? = null
        var dispatchFailure: AgentChatException? = null

        val result = executor.execute(
            idempotencyKey = idempotencyKey,
            beforeDispatch = {
                try {
                    if (!runCatching(requestAllowed).getOrDefault(false)) {
                        throw AgentChatException(AgentChatFailureKind.POLICY_DENIED, "L'accès au Coach n'est plus autorisé.")
                    }
                    val fresh = freshRequestContext(context)
                        ?: throw AgentChatException(AgentChatFailureKind.POLICY_DENIED, "Le mandat du Coach n'est plus actif.")
                    val currentMandate = fresh.mandate
                    if (currentMandate.revoked || currentMandate.suspended ||
                        fresh.nowUtcMillis >= currentMandate.expiresAtUtcMillis
                    ) {
                        throw AgentChatException(AgentChatFailureKind.POLICY_DENIED, "Le mandat du Coach est révoqué, suspendu ou expiré.")
                    }
                    val snapshot = fresh.minimizedSnapshot()
                    if (!onContextWritten(snapshot)) {
                        throw AgentChatException(
                            AgentChatFailureKind.POLICY_DENIED,
                            "L'audit local du contexte n'a pas pu être enregistré. Aucun message relayé n'a été envoyé.",
                        )
                    }
                    dispatchMandate = currentMandate
                    dispatchSnapshot = snapshot
                    true
                } catch (error: AgentChatException) {
                    dispatchFailure = error
                    false
                } catch (error: Exception) {
                    dispatchFailure = AgentChatException(
                        AgentChatFailureKind.POLICY_DENIED,
                        "Impossible de vérifier le mandat avant l'envoi relayé.",
                        cause = error,
                    )
                    false
                }
            },
            payloadFactory = {
                val currentMandate = checkNotNull(dispatchMandate)
                val snapshot = checkNotNull(dispatchSnapshot)
                AgentChatRequestBody.build(conversationId, message, currentMandate, snapshot)
            },
        )

        when (result) {
            is RelayCallResult.Success -> {
                val snapshot = dispatchSnapshot ?: throw AgentChatException(
                    AgentChatFailureKind.POLICY_DENIED,
                    "Le contexte relayé n'a pas été préparé.",
                )
                return AgentChatResponseParser.parse(result.result, snapshot)
            }
            is RelayCallResult.RemoteError -> {
                val kind = if (result.code == "OPERATION_UNAVAILABLE") {
                    AgentChatFailureKind.OPERATION_UNAVAILABLE
                } else AgentChatFailureKind.REMOTE_ERROR
                throw AgentChatException(kind, result.message, result.code)
            }
            is RelayCallResult.Failure -> {
                dispatchFailure?.let { throw it }
                val (kind, message) = when (result.reason) {
                    RelayCallResult.Failure.Reason.TIMEOUT -> AgentChatFailureKind.RESULT_UNCERTAIN to
                        "Le résultat du message relayé est incertain. ATLAS ne le renverra pas automatiquement."
                    RelayCallResult.Failure.Reason.CANCELLED -> AgentChatFailureKind.CANCELLED to "Envoi relayé annulé."
                    RelayCallResult.Failure.Reason.OFFLINE -> AgentChatFailureKind.OFFLINE to
                        "Connexion relayée perdue. Le message reste dans l'historique et ne sera pas renvoyé automatiquement."
                    RelayCallResult.Failure.Reason.POLICY_DENIED -> AgentChatFailureKind.POLICY_DENIED to
                        "Le mandat du Coach ne permet plus cet envoi."
                    RelayCallResult.Failure.Reason.PROTOCOL -> AgentChatFailureKind.INVALID_RESPONSE to
                        "La réponse relayée est invalide ou incompatible."
                    RelayCallResult.Failure.Reason.NOT_STARTED,
                    RelayCallResult.Failure.Reason.QUEUE_FULL,
                    RelayCallResult.Failure.Reason.EXPIRED -> AgentChatFailureKind.OFFLINE to
                        "Le relais n'a pas pu envoyer le message. Aucun renvoi automatique ne sera tenté."
                }
                throw AgentChatException(kind, message)
            }
        }
    }
}
