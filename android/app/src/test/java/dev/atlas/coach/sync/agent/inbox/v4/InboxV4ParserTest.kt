package dev.atlas.coach.sync.agent.inbox.v4

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxV4ParserTest {
    @Test
    fun parsesCompleteProposalAndRetainsNestedChangesAndDiff() {
        val result = InboxV4ProposalParser.parse(InboxV4Fixtures.rawProposal())

        assertTrue(result is InboxV4ProposalParseResult.Parsed)
        val proposal = (result as InboxV4ProposalParseResult.Parsed).proposal
        assertEquals(InboxV4Fixtures.PROPOSAL_ID, proposal.proposalId)
        assertEquals(InboxV4ProposalState.PENDING_ANDROID, proposal.state)
        assertEquals(7L, proposal.baseVersion)
        assertEquals("reschedule", proposal.changes.single()["op"])
        assertEquals(660, proposal.diff.single()["after"].let { it as Map<*, *> }["start_minute"])
    }

    @Test
    fun malformedTypesUnknownEnumsInvalidUuidsAndTimestampsAreRejected() {
        val valid = JSONObject(InboxV4Fixtures.rawProposal())
        val wrongType = JSONObject(valid.toString()).put("base_version", "7")
        val unknownState = JSONObject(valid.toString()).put("state", "FUTURE_STATE")
        val badUuid = JSONObject(valid.toString()).put("proposal_id", "not-a-uuid")
        val badTimestamp = JSONObject(valid.toString()).put("created_at", "2026-10-29 08:10:00")

        listOf(wrongType, unknownState, badUuid, badTimestamp).forEach { json ->
            assertTrue(InboxV4ProposalParser.parse(json.toString()) is InboxV4ProposalParseResult.Rejected)
        }
    }

    @Test
    fun unknownFieldsAreQuarantinedWithOriginalPayloadUnchanged() {
        val raw = JSONObject(InboxV4Fixtures.rawProposal()).put("future_field", JSONObject().put("value", 1)).toString()

        val result = InboxV4ProposalParser.parse(raw)

        assertTrue(result is InboxV4ProposalParseResult.Quarantined)
        assertEquals(raw, (result as InboxV4ProposalParseResult.Quarantined).raw)
    }

    @Test
    fun unknownSchemaVersionIsQuarantinedWithOriginalPayloadUnchanged() {
        val raw = JSONObject(InboxV4Fixtures.rawProposal()).put("schema_version", 5).toString()

        val result = InboxV4ProposalParser.parse(raw)

        assertTrue(result is InboxV4ProposalParseResult.Quarantined)
        assertEquals(raw, (result as InboxV4ProposalParseResult.Quarantined).raw)
    }

    @Test
    fun oversizedPayloadIsRejectedBeforeItCanBeQuarantined() {
        val oversized = " ".repeat(MAX_PROPOSAL_PAYLOAD_BYTES + 1)

        val result = InboxV4ProposalParser.parse(oversized)

        assertTrue(result is InboxV4ProposalParseResult.Rejected)
        assertFalse((result as InboxV4ProposalParseResult.Rejected).quarantinable)
    }

    @Test
    fun unknownNestedFieldIsQuarantinedRatherThanDropped() {
        val raw = JSONObject(InboxV4Fixtures.rawProposal())
        raw.getJSONArray("changes").getJSONObject(0).put("future_change", "kept")
        val payload = raw.toString()

        val result = InboxV4ProposalParser.parse(payload)

        assertTrue(result is InboxV4ProposalParseResult.Quarantined)
        assertEquals(payload, (result as InboxV4ProposalParseResult.Quarantined).raw)
    }

    @Test
    fun ackParserRequiresCompleteProofOnlyForAppliedResults() {
        val applied = InboxV4Fixtures.ack().toJsonObject().toString()
        val rejected = InboxV4Fixtures.ack(
            ackId = "00000000-0000-4000-8000-000000000611",
            state = InboxV4AckState.REJECTED,
            resultCode = InboxV4AckResultCode.USER_REJECTED,
            proof = null,
        ).toJsonObject().toString()
        val prooflessObject = JSONObject(applied)
        listOf("plan_id", "applied_plan_version", "revision_id", "scope_hash", "effect_ids")
            .forEach { prooflessObject.remove(it) }
        val prooflessApplied = prooflessObject.toString()

        assertEquals(InboxV4AckState.APPLIED, InboxV4AckParser.parse(applied).state)
        assertEquals(InboxV4AckState.REJECTED, InboxV4AckParser.parse(rejected).state)
        assertFalse(runCatching { InboxV4AckParser.parse(prooflessApplied) }.isSuccess)
        assertFalse(runCatching { InboxV4Fixtures.proof(effectIds = listOf("not-an-effect")) }.isSuccess)
        assertFalse(runCatching {
            InboxV4Fixtures.proof(effectIds = listOf("PLAN_APPLY:${"0".repeat(36)}"))
        }.isSuccess)
    }
}
