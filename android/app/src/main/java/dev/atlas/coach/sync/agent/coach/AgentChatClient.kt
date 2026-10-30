package dev.atlas.coach.sync.agent.coach

import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.AgentActionType
import dev.atlas.coach.sync.agent.ProposalLevel
import dev.atlas.coach.sync.agent.ProposalV2
import dev.atlas.coach.sync.agent.context.AgentContextSnapshot
import dev.atlas.coach.sync.relay.AtlasRemoteProtocol
import dev.atlas.coach.sync.relay.RelayBackendTransport
import dev.atlas.coach.sync.relay.RelayCallResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
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

    /** Strict parser for contracts/v4/chat-response.schema.json. */
    fun parseV4(result: JSONObject, requestId: String, sharing: AgentChatV4Sharing): AgentChatResponse {
        try {
            val expectedKeys = setOf(
                "contract_version", "schema_version", "request_id", "reply", "proposal_ids",
                "agent_status", "tool_state", "tool_call_count",
            )
            require(result.keys().asSequence().toSet() == expectedKeys) { "Unexpected V4 chat response fields" }
            require(result.requiredInt("contract_version") == 4)
            require(result.requiredInt("schema_version") == 4)
            require(result.requiredString("request_id") == requestId)
            val reply = result.requiredString("reply")
            require(reply.isNotBlank() && reply.length <= MAX_REPLY_LENGTH)

            val rawProposalIds = result.opt("proposal_ids") as? JSONArray
                ?: error("V4 chat response has no proposal_ids array")
            require(rawProposalIds.length() <= 1)
            val proposalIds = (0 until rawProposalIds.length()).map { index ->
                val id = rawProposalIds.get(index) as? String ?: error("Invalid V4 proposal id")
                require(isCanonicalUuid(id))
                id
            }
            require(proposalIds.distinct().size == proposalIds.size)

            val status = when (result.requiredString("agent_status")) {
                "ok" -> AgentStatus.CONNECTED
                "mock" -> AgentStatus.MOCK
                "unavailable" -> AgentStatus.UNAVAILABLE
                else -> error("Unknown V4 agent_status")
            }
            val toolState = result.requiredString("tool_state")
            require(toolState in V4_TOOL_STATES)
            val toolCallCount = result.requiredInt("tool_call_count")
            require(toolCallCount in 0..6)

            val proposalSummary = if (proposalIds.isEmpty()) {
                "Aucune proposition (0)."
            } else {
                "Proposition(s) (${proposalIds.size}) — identifiants : ${proposalIds.joinToString(", ")}. Contenu à synchroniser via la boîte de réception."
            }
            val metadata = "\n\nÉtat des outils : $toolState ($toolCallCount appel(s)). $proposalSummary"
            val replyLimit = (MAX_VISIBLE_REPLY_LENGTH - metadata.length).coerceAtLeast(0)
            val clipped = reply.length > replyLimit
            val contentLimit = if (clipped) (replyLimit - 1).coerceAtLeast(0) else replyLimit
            val visibleReply = reply.take(contentLimit).let { content ->
                (if (clipped) content.trimEnd() + "…" else content) + metadata
            }
            val sharedCategories = JSONObject().put("consented_categories", JSONArray(sharing.sharedCategories.sorted()))
            return AgentChatResponse(
                reply = visibleReply,
                proposals = emptyList(),
                status = status,
                sharedContextJson = sharedCategories.toString(),
            )
        } catch (error: Exception) {
            throw AgentChatException(
                AgentChatFailureKind.INVALID_RESPONSE,
                "La réponse V4 du Coach est invalide.",
                cause = error,
            )
        }
    }

    private const val MAX_REPLY_LENGTH = 4_000
    private const val MAX_VISIBLE_REPLY_LENGTH = 4_000
    private const val MAX_PROPOSALS = 5
    private val V4_TOOL_STATES = setOf("none", "completed", "partial", "uncertain")
}

private fun JSONObject.requiredString(key: String): String =
    opt(key) as? String ?: error("Missing or invalid $key")

private fun JSONObject.requiredInt(key: String): Int {
    val value = opt(key) as? Number ?: error("Missing or invalid $key")
    val integer = value.toLong()
    require(value.toDouble() == integer.toDouble() && integer in Int.MIN_VALUE..Int.MAX_VALUE.toLong())
    return integer.toInt()
}

private fun isCanonicalUuid(value: String): Boolean = runCatching {
    UUID.fromString(value).toString() == value.lowercase()
}.getOrDefault(false)

/** Only V4 sync categories with a current, acknowledged consent can be shared by V4 chat. */
data class AgentChatV4Sharing(
    val consentVersion: Int,
    val sharedCategories: Set<String>,
) {
    init {
        require(consentVersion >= 1)
        require(sharedCategories.all { it in V4_CHAT_SHARED_CATEGORIES })
    }

    companion object {
        val V4_CHAT_SHARED_CATEGORIES = setOf(
            "DAY_PLAN", "ACTIVITY_STATUS", "USAGE_SUMMARY", "GOAL_PROGRESS", "TRAINING_PLAN",
        )
        val EMPTY = AgentChatV4Sharing(consentVersion = 1, sharedCategories = emptySet())
    }
}

/** The V4 chat body deliberately contains consented category names, never the legacy context payload. */
object AgentChatV4RequestBody {
    fun build(
        conversationId: String?,
        message: String,
        requestId: String,
        sharing: AgentChatV4Sharing,
        sentAt: Instant = Instant.now(),
    ): JSONObject {
        require(isCanonicalUuid(requestId))
        require(!conversationId.isNullOrBlank() && conversationId.length <= 80)
        require(message.isNotBlank() && message.length <= 2_000)
        return JSONObject()
            .put("contract_version", 4)
            .put("schema_version", 4)
            .put("request_id", requestId)
            .put("capability", "coach.chat")
            .put("sent_at", sentAt.toString())
            .put("conversation_id", conversationId)
            .put("message", message)
            .put("consent_version", sharing.consentVersion)
            .put("shared_categories", JSONArray(sharing.sharedCategories.sorted()))
    }
}

enum class AgentChatFailureKind {
    OPERATION_UNAVAILABLE,
    OFFLINE,
    RESULT_UNCERTAIN,
    CANCELLED,
    POLICY_DENIED,
    INVALID_RESPONSE,
    AUTHENTICATION_REQUIRED,
    CONSENT_DENIED,
    CONFLICT,
    INVALID_REQUEST,
    SERVICE_UNAVAILABLE,
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
        operation: String,
        timeoutMillis: Long,
        idempotencyKey: String,
        payloadFactory: () -> JSONObject,
        beforeDispatch: () -> Boolean,
        requestId: String?,
    ): RelayCallResult
}

/** Bridges chat dispatch to the relay while preserving the V4 body/envelope request ID. */
internal class RelayBackendChatRequestExecutor(
    private val transport: RelayBackendTransport,
) : RelayChatRequestExecutor {
    override fun execute(
        operation: String,
        timeoutMillis: Long,
        idempotencyKey: String,
        payloadFactory: () -> JSONObject,
        beforeDispatch: () -> Boolean,
        requestId: String?,
    ): RelayCallResult {
        if (operation == "v4.coach.chat.request" && requestId == null) {
            return RelayCallResult.Failure(RelayCallResult.Failure.Reason.PROTOCOL)
        }
        return transport.executeDeferred(
            operation = operation,
            timeoutMillis = timeoutMillis,
            idempotencyKey = idempotencyKey,
            requestId = requestId ?: AtlasRemoteProtocol.newUuid(),
            payloadFactory = payloadFactory,
            beforeDispatch = beforeDispatch,
        )
    }
}

/** Builds a fresh, consent-filtered payload at relay dispatch time and never retries a sent request. */
class RelayAgentChatClient(
    private val executor: RelayChatRequestExecutor,
    private val freshRequestContext: (AgentContextSnapshot) -> AgentRequestContext?,
    private val onContextWritten: (AgentContextSnapshot) -> Boolean,
    private val requestAllowed: () -> Boolean = { true },
    private val snapshotStillAuthorized: (AgentContextSnapshot) -> Boolean = { true },
    private val v4ChatEnabled: Boolean = false,
    private val v4Sharing: () -> AgentChatV4Sharing = { AgentChatV4Sharing.EMPTY },
    private val v4SharingStillAuthorized: (AgentChatV4Sharing) -> Boolean = { true },
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
        val dispatchMandate: AgentMandate
        val dispatchSnapshot: AgentContextSnapshot
        val dispatchV4Sharing: AgentChatV4Sharing
        try {
            if (!runCatching(requestAllowed).getOrDefault(false)) {
                throw AgentChatException(AgentChatFailureKind.POLICY_DENIED, "L'accès au Coach n'est plus autorisé.")
            }
            val fresh = freshRequestContext(context)
                ?: throw AgentChatException(AgentChatFailureKind.POLICY_DENIED, "Le mandat du Coach n'est plus actif.")
            dispatchMandate = fresh.mandate
            if (dispatchMandate.revoked || dispatchMandate.suspended ||
                fresh.nowUtcMillis >= dispatchMandate.expiresAtUtcMillis
            ) {
                throw AgentChatException(AgentChatFailureKind.POLICY_DENIED, "Le mandat du Coach est révoqué, suspendu ou expiré.")
            }
            dispatchSnapshot = fresh.minimizedSnapshot()
            dispatchV4Sharing = if (v4ChatEnabled) v4Sharing() else AgentChatV4Sharing.EMPTY
            val auditedSnapshot = if (v4ChatEnabled) AgentContextSnapshot(emptyList(), emptyMap(), emptyMap()) else dispatchSnapshot
            if (!onContextWritten(auditedSnapshot)) {
                throw AgentChatException(
                    AgentChatFailureKind.POLICY_DENIED,
                    "L'audit local du contexte n'a pas pu être enregistré. Aucun message relayé n'a été envoyé.",
                )
            }
        } catch (error: AgentChatException) {
            throw error
        } catch (error: Exception) {
            throw AgentChatException(
                AgentChatFailureKind.POLICY_DENIED,
                "Impossible de préparer le mandat et le contexte avant l'envoi relayé.",
                cause = error,
            )
        }

        val operation = if (v4ChatEnabled) V4_CHAT_OPERATION else V2_CHAT_OPERATION
        val beforeDispatch = {
            runCatching(requestAllowed).getOrDefault(false) &&
                !dispatchMandate.revoked && !dispatchMandate.suspended &&
                System.currentTimeMillis() < dispatchMandate.expiresAtUtcMillis &&
                runCatching { snapshotStillAuthorized(dispatchSnapshot) }.getOrDefault(false) &&
                (!v4ChatEnabled || runCatching { v4SharingStillAuthorized(dispatchV4Sharing) }.getOrDefault(false))
        }
        val payloadFactory = {
            if (v4ChatEnabled) {
                AgentChatV4RequestBody.build(conversationId, message, idempotencyKey, dispatchV4Sharing)
            } else {
                AgentChatRequestBody.build(conversationId, message, dispatchMandate, dispatchSnapshot)
            }
        }
        val result = executor.execute(
            operation = operation,
            timeoutMillis = RELAY_CHAT_TIMEOUT_MILLIS,
            idempotencyKey = idempotencyKey,
            payloadFactory = payloadFactory,
            beforeDispatch = beforeDispatch,
            requestId = if (v4ChatEnabled) idempotencyKey else null,
        )

        when (result) {
            is RelayCallResult.Success -> {
                return if (v4ChatEnabled) {
                    AgentChatResponseParser.parseV4(result.result, idempotencyKey, dispatchV4Sharing)
                } else {
                    AgentChatResponseParser.parse(result.result, dispatchSnapshot)
                }
            }
            is RelayCallResult.RemoteError -> {
                val code = result.code.takeIf { it.matches(REMOTE_CODE_PATTERN) }
                val (kind, safeMessage) = remoteFailure(code)
                throw AgentChatException(kind, safeMessage, code)
            }
            is RelayCallResult.Failure -> {
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

    private fun remoteFailure(code: String?): Pair<AgentChatFailureKind, String> = when (code) {
        "OPERATION_UNAVAILABLE" -> AgentChatFailureKind.OPERATION_UNAVAILABLE to
            "La capacité de conversation V4 n'est pas disponible sur ce relais. Aucun repli V2 ne sera tenté."
        "HTTP_401", "AUTH_REQUIRED", "AUTH_FAILED" -> AgentChatFailureKind.AUTHENTICATION_REQUIRED to
            "L'authentification du Coach a échoué."
        "HTTP_403", "FORBIDDEN", "CONSENT_REQUIRED", "CONSENT_DENIED" -> AgentChatFailureKind.CONSENT_DENIED to
            "Le consentement ou l'autorisation du Coach ne permet pas cet envoi."
        "HTTP_408" -> AgentChatFailureKind.RESULT_UNCERTAIN to
            "Le résultat de la requête est incertain. ATLAS ne la renverra pas automatiquement."
        "HTTP_409", "CONFLICT", "IDEMPOTENCY_CONFLICT", "STALE_VERSION" -> AgentChatFailureKind.CONFLICT to
            "Le serveur a détecté un conflit. Aucun nouvel envoi automatique ne sera tenté."
        "HTTP_422", "BAD_REQUEST", "INVALID_REQUEST" -> AgentChatFailureKind.INVALID_REQUEST to
            "La requête V4 a été refusée car elle ne respecte pas les données attendues."
        "HTTP_503", "SERVICE_UNAVAILABLE", "BACKEND_UNAVAILABLE", "UPSTREAM_UNAVAILABLE" ->
            AgentChatFailureKind.SERVICE_UNAVAILABLE to "Le service du Coach est temporairement indisponible."
        else -> AgentChatFailureKind.REMOTE_ERROR to
            "Le serveur a refusé la requête relayée. Aucun envoi direct ni renvoi automatique ne sera tenté."
    }

    companion object {
        /** Leaves headroom for the relay's strict 60-second request TTL. */
        const val RELAY_CHAT_TIMEOUT_MILLIS = 55_000L
        const val V2_CHAT_OPERATION = "coach.chat.request"
        const val V4_CHAT_OPERATION = "v4.coach.chat.request"
        private val REMOTE_CODE_PATTERN = Regex("[A-Z0-9_]{1,64}")
    }
}
