package dev.atlas.coach.sync.agent.inbox.v4

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal object InboxV4Fixtures {
    const val PROPOSAL_ID = "00000000-0000-4000-8000-000000000501"
    const val IDEMPOTENCY_KEY = "00000000-0000-4000-8000-000000000502"
    const val PLAN_ID = "00000000-0000-4000-8000-000000000101"
    const val ACTIVITY_ID = "00000000-0000-4000-8000-000000000103"
    const val REVISION_ID = "00000000-0000-4000-8000-000000000602"
    const val SCOPE_HASH = "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    val CREATED_AT: Instant = Instant.parse("2026-10-29T08:10:00Z")
    val EXPIRES_AT: Instant = Instant.parse("2026-10-30T08:10:00Z")

    fun rawProposal(
        proposalId: String = PROPOSAL_ID,
        idempotencyKey: String = IDEMPOTENCY_KEY,
        state: String = "PENDING_ANDROID",
        createdAt: String = CREATED_AT.toString(),
        expiresAt: String = EXPIRES_AT.toString(),
        baseVersion: Int = 7,
    ): String = JSONObject()
        .put("contract_version", 4)
        .put("schema_version", 4)
        .put("proposal_id", proposalId)
        .put("capability", "plan_revision.propose")
        .put("idempotency_key", idempotencyKey)
        .put("proposal_hash", "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        .put("created_at", createdAt)
        .put("expires_at", expiresAt)
        .put("state", state)
        .put("plan_id", PLAN_ID)
        .put("base_version", baseVersion)
        .put("origin", "AGENT")
        .put("justification", "Proposition synthétique de déplacement.")
        .put("changes", JSONArray().put(JSONObject()
            .put("op", "reschedule")
            .put("activity_id", ACTIVITY_ID)
            .put("target_start_minute", 660)
            .put("duration_minutes", 45)))
        .put("diff", JSONArray().put(JSONObject()
            .put("op", "reschedule")
            .put("activity_id", ACTIVITY_ID)
            .put("client_ref", JSONObject.NULL)
            .put("before", JSONObject().put("start_minute", 600).put("duration_minutes", 45))
            .put("after", JSONObject().put("start_minute", 660).put("duration_minutes", 45))))
        .put("conflicts", JSONArray())
        .put("confirmation_mode", "A0_USER_REVIEW")
        .put("auto_application", "NONE_AT_CORE")
        .toString()

    fun proposal(
        proposalId: String = PROPOSAL_ID,
        idempotencyKey: String = IDEMPOTENCY_KEY,
        state: InboxV4ProposalState = InboxV4ProposalState.PENDING_ANDROID,
        expiresAt: Instant = EXPIRES_AT,
    ): InboxV4Proposal = parsed(rawProposal(
        proposalId = proposalId,
        idempotencyKey = idempotencyKey,
        state = state.name,
        expiresAt = expiresAt.toString(),
    ))

    fun parsed(raw: String): InboxV4Proposal = when (val result = InboxV4ProposalParser.parse(raw)) {
        is InboxV4ProposalParseResult.Parsed -> result.proposal
        else -> error("Fixture proposal did not parse: $result")
    }

    fun proof(
        planId: String = PLAN_ID,
        appliedPlanVersion: Long = 8,
        revisionId: String = REVISION_ID,
        scopeHash: String = SCOPE_HASH,
        effectIds: List<String> = listOf("PLAN_ACTIVITY_UPDATE:$ACTIVITY_ID"),
    ) = InboxV4ApplicationProof(planId, appliedPlanVersion, revisionId, scopeHash, effectIds)

    fun ack(
        proposalId: String = PROPOSAL_ID,
        ackId: String = "00000000-0000-4000-8000-000000000601",
        sequence: Long = 1,
        state: InboxV4AckState = InboxV4AckState.APPLIED,
        resultCode: InboxV4AckResultCode = InboxV4AckResultCode.APPLIED,
        proof: InboxV4ApplicationProof? = proof(),
        occurredAt: Instant = Instant.parse("2026-10-29T08:12:00Z"),
    ) = InboxV4Ack(
        contractVersion = 4,
        schemaVersion = 4,
        ackId = ackId,
        capability = "proposal.ack",
        proposalId = proposalId,
        sequence = sequence,
        state = state,
        resultCode = resultCode,
        occurredAt = occurredAt,
        consentVersion = 3,
        proof = proof,
    )
}
