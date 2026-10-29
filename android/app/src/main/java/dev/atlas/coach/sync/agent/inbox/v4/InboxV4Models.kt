package dev.atlas.coach.sync.agent.inbox.v4

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

enum class InboxV4ProposalState(val terminal: Boolean) {
    PENDING_ANDROID(false),
    RECEIVED(false),
    AWAITING_USER(false),
    APPLIED(true),
    REJECTED(true),
    STALE_VERSION(true),
    EXPIRED(true),
    VALIDATION_REJECTED(true),
    UNCERTAIN(false),
    RECONCILED(true),
}

enum class InboxV4ConfirmationMode {
    A0_USER_REVIEW,
    A1_ANDROID_ELIGIBLE_TO_CHECK,
    A2_USER_CONFIRMATION,
}

enum class InboxV4AckState {
    APPLIED,
    REJECTED,
    STALE_VERSION,
    EXPIRED,
    VALIDATION_REJECTED,
    UNCERTAIN,
    RECONCILED,
}

enum class InboxV4AckResultCode {
    APPLIED,
    USER_REJECTED,
    STALE_VERSION,
    PROPOSAL_EXPIRED,
    VALIDATION_FAILED,
    RESULT_UNCERTAIN,
    RECONCILED_APPLIED,
    RECONCILED_NOT_APPLIED,
}

data class InboxV4ApplicationProof(
    val planId: String,
    val appliedPlanVersion: Long,
    val revisionId: String,
    val scopeHash: String,
    val effectIds: List<String>,
) {
    init {
        require(isCanonicalUuid(planId)) { "plan_id must be a canonical UUID" }
        require(appliedPlanVersion > 0L) { "applied_plan_version must be positive" }
        require(isCanonicalUuid(revisionId)) { "revision_id must be a canonical UUID" }
        require(SHA256_PATTERN.matches(scopeHash)) { "scope_hash must be a SHA-256 digest" }
        require(effectIds.isNotEmpty() && effectIds.size <= MAX_EFFECT_IDS)
        require(effectIds.all { effectId ->
            EFFECT_ID_PATTERN.matches(effectId) && isCanonicalUuid(effectId.substringAfterLast(':'))
        })
    }
}

/** Strictly parsed Core proposal. changes and diff contain JSON-compatible scalar maps. */
data class InboxV4Proposal(
    val contractVersion: Int,
    val schemaVersion: Int,
    val proposalId: String,
    val capability: String,
    val idempotencyKey: String,
    val proposalHash: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val state: InboxV4ProposalState,
    val planId: String,
    val baseVersion: Long,
    val origin: String,
    val justification: String,
    val changes: List<Map<String, Any?>>,
    val diff: List<Map<String, Any?>>,
    val conflicts: List<String>,
    val confirmationMode: InboxV4ConfirmationMode,
    val autoApplication: String,
) {
    init {
        require(contractVersion == CONTRACT_VERSION && schemaVersion == SCHEMA_VERSION)
        require(isCanonicalUuid(proposalId) && isCanonicalUuid(idempotencyKey) && isCanonicalUuid(planId))
        require(capability == PROPOSAL_CAPABILITY)
        require(SHA256_PATTERN.matches(proposalHash))
        require(expiresAt.isAfter(createdAt))
        require(baseVersion > 0L)
        require(origin == PROPOSAL_ORIGIN)
        require(justification.isNotBlank() && justification.length <= 280)
        require(changes.isNotEmpty() && changes.size <= MAX_CHANGES)
        require(diff.size <= MAX_CHANGES)
        require(conflicts.size <= MAX_CONFLICTS && conflicts.all { it.isNotBlank() && it.length <= 120 })
        require(autoApplication == NO_CORE_AUTO_APPLICATION)
    }

    fun withState(next: InboxV4ProposalState) = copy(state = next)

    fun toJsonObject(): JSONObject = JSONObject()
        .put("contract_version", contractVersion)
        .put("schema_version", schemaVersion)
        .put("proposal_id", proposalId)
        .put("capability", capability)
        .put("idempotency_key", idempotencyKey)
        .put("proposal_hash", proposalHash)
        .put("created_at", createdAt.toString())
        .put("expires_at", expiresAt.toString())
        .put("state", state.name)
        .put("plan_id", planId)
        .put("base_version", baseVersion)
        .put("origin", origin)
        .put("justification", justification)
        .put("changes", changes.toJsonArray())
        .put("diff", diff.toJsonArray())
        .put("conflicts", JSONArray(conflicts))
        .put("confirmation_mode", confirmationMode.name)
        .put("auto_application", autoApplication)

    fun canonicalBody(): String = canonicalJson(toJsonObject())
}

/** ACK proof fields are nullable together; only successful application results carry a proof. */
data class InboxV4Ack(
    val contractVersion: Int,
    val schemaVersion: Int,
    val ackId: String,
    val capability: String,
    val proposalId: String,
    val sequence: Long,
    val state: InboxV4AckState,
    val resultCode: InboxV4AckResultCode,
    val occurredAt: Instant,
    val consentVersion: Long,
    val proof: InboxV4ApplicationProof? = null,
) {
    init {
        require(contractVersion == CONTRACT_VERSION && schemaVersion == SCHEMA_VERSION)
        require(isCanonicalUuid(ackId) && isCanonicalUuid(proposalId))
        require(capability == ACK_CAPABILITY)
        require(sequence > 0L && consentVersion > 0L)
        validateAckSemantics(state, resultCode, proof)
    }

    fun toJsonObject(): JSONObject = JSONObject()
        .put("contract_version", contractVersion)
        .put("schema_version", schemaVersion)
        .put("ack_id", ackId)
        .put("capability", capability)
        .put("proposal_id", proposalId)
        .put("sequence", sequence)
        .put("state", state.name)
        .put("result_code", resultCode.name)
        .put("occurred_at", occurredAt.toString())
        .also { json ->
            json.put("consent_version", consentVersion)
            proof?.let {
                json.put("plan_id", it.planId)
                    .put("applied_plan_version", it.appliedPlanVersion)
                    .put("revision_id", it.revisionId)
                    .put("scope_hash", it.scopeHash)
                    .put("effect_ids", JSONArray(it.effectIds))
            }
        }

    fun canonicalBody(): String = canonicalJson(toJsonObject())
}

data class InboxV4PullRequest(
    val cursor: String?,
    val pageSize: Int,
) {
    init {
        require(pageSize in 1..MAX_PAGE_SIZE) { "page_size must be between 1 and $MAX_PAGE_SIZE" }
        require(cursor == null || (cursor.isNotBlank() && cursor.length <= MAX_CURSOR_LENGTH))
    }
}

data class InboxV4PullPage(
    val proposals: List<InboxV4Proposal>,
    val nextCursor: String?,
    val truncated: Boolean,
) {
    init {
        require(nextCursor == null || (nextCursor.isNotBlank() && nextCursor.length <= MAX_CURSOR_LENGTH))
        require(!truncated || nextCursor != null) { "A truncated pull page must provide next_cursor" }
    }
}

data class InboxV4QuarantinedPayload(
    val raw: String,
    val reason: String,
    val quarantinedAt: Instant,
)

sealed interface InboxV4ProposalParseResult {
    data class Parsed(val proposal: InboxV4Proposal) : InboxV4ProposalParseResult
    data class Rejected(
        val raw: String,
        val reason: String,
        val quarantinable: Boolean = true,
    ) : InboxV4ProposalParseResult
    data class Quarantined(val raw: String, val reason: String) : InboxV4ProposalParseResult
}

data class InboxV4MergeResult(
    val added: Int,
    val duplicates: Int,
    val expired: Int,
    val conflicts: Int,
    val deferredForCapacity: Int,
    val cursor: String?,
    val truncated: Boolean,
)

data class InboxV4EntryView(
    val proposal: InboxV4Proposal,
    val applicationProof: InboxV4ApplicationProof?,
    val lastAckSequence: Long,
)

enum class InboxV4AckDeliveryState {
    PENDING,
    UNCERTAIN,
    DELIVERED,
}

data class InboxV4OutboxAck(
    val ack: InboxV4Ack,
    val deliveryState: InboxV4AckDeliveryState,
)

sealed interface InboxV4AppendResult {
    data object Appended : InboxV4AppendResult
    data object Duplicate : InboxV4AppendResult
}

sealed interface InboxV4DeliveryResult {
    data object Delivered : InboxV4DeliveryResult
    data object Uncertain : InboxV4DeliveryResult
    data object Offline : InboxV4DeliveryResult
}

data class InboxV4ReplayResult(
    val delivered: Int,
    val uncertain: Int,
    val stillPending: Int,
)

class InboxV4ConflictException(message: String) : IllegalStateException(message)

internal class InboxV4FormatException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal class InboxV4UnknownFieldException(message: String) : IllegalArgumentException(message)

internal const val CONTRACT_VERSION = 4
internal const val SCHEMA_VERSION = 4
internal const val PROPOSAL_CAPABILITY = "plan_revision.propose"
internal const val ACK_CAPABILITY = "proposal.ack"
internal const val PROPOSAL_ORIGIN = "AGENT"
internal const val NO_CORE_AUTO_APPLICATION = "NONE_AT_CORE"
internal const val MAX_PAGE_SIZE = 32
internal const val MAX_CURSOR_LENGTH = 256
internal const val MAX_CHANGES = 8
internal const val MAX_CONFLICTS = 16
internal const val MAX_EFFECT_IDS = 32
internal const val MAX_PROPOSAL_PAYLOAD_BYTES = 64 * 1024
internal val SHA256_PATTERN = Regex("^sha256:[0-9a-f]{64}$")
internal val EFFECT_ID_PATTERN = Regex(
    "^(PLAN_ACTIVITY_(CREATE|UPDATE|DELETE):[0-9a-f-]{36}|PLAN_APPLY:[0-9a-f-]{36})$",
)

internal fun isCanonicalUuid(value: String): Boolean =
    runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

internal fun validateAckSemantics(
    state: InboxV4AckState,
    resultCode: InboxV4AckResultCode,
    proof: InboxV4ApplicationProof?,
) {
    val expected = when (state) {
        InboxV4AckState.APPLIED -> InboxV4AckResultCode.APPLIED
        InboxV4AckState.REJECTED -> InboxV4AckResultCode.USER_REJECTED
        InboxV4AckState.STALE_VERSION -> InboxV4AckResultCode.STALE_VERSION
        InboxV4AckState.EXPIRED -> InboxV4AckResultCode.PROPOSAL_EXPIRED
        InboxV4AckState.VALIDATION_REJECTED -> InboxV4AckResultCode.VALIDATION_FAILED
        InboxV4AckState.UNCERTAIN -> InboxV4AckResultCode.RESULT_UNCERTAIN
        InboxV4AckState.RECONCILED -> null
    }
    if (expected != null) require(resultCode == expected) { "ACK result_code does not match state" }
    when (state) {
        InboxV4AckState.APPLIED -> require(proof != null) { "APPLIED requires application proof" }
        InboxV4AckState.RECONCILED -> {
            if (resultCode == InboxV4AckResultCode.RECONCILED_APPLIED) {
                require(proof != null) { "RECONCILED_APPLIED requires application proof" }
            } else {
                require(resultCode in setOf(
                    InboxV4AckResultCode.RECONCILED_NOT_APPLIED,
                    InboxV4AckResultCode.USER_REJECTED,
                ))
                require(proof == null) { "RECONCILED_NOT_APPLIED cannot carry application proof" }
            }
        }
        else -> require(proof == null) { "$state cannot carry application proof" }
    }
}

private fun List<Map<String, Any?>>.toJsonArray(): JSONArray = JSONArray().also { array ->
    forEach { array.put(it.toJsonObject()) }
}

private fun Map<String, Any?>.toJsonObject(): JSONObject = JSONObject().also { json ->
    forEach { (key, value) -> json.put(key, value.toJsonValue()) }
}

private fun Any?.toJsonValue(): Any = when (this) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().also { json ->
        entries.forEach { (key, value) -> json.put(key.toString(), value.toJsonValue()) }
    }
    is Iterable<*> -> JSONArray().also { array -> forEach { array.put(it.toJsonValue()) } }
    else -> this ?: JSONObject.NULL
}

internal fun canonicalJson(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
        "${JSONObject.quote(key)}:${canonicalJson(value.get(key))}"
    }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.get(it)) }
    is Map<*, *> -> value.entries.sortedBy { it.key.toString() }.joinToString(",", "{", "}") { (key, item) ->
        "${JSONObject.quote(key.toString())}:${canonicalJson(item)}"
    }
    is Iterable<*> -> value.joinToString(",", "[", "]") { canonicalJson(it) }
    is String -> JSONObject.quote(value)
    is Boolean -> value.toString()
    is Number -> value.toString()
    else -> JSONObject.quote(value.toString())
}
