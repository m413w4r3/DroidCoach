package dev.atlas.coach.sync

import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.AgentMode
import dev.atlas.coach.sync.agent.ProposalV2
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AgentV2ContractsTest {
    @Test
    fun proposalV2RoundTripsStrictly() {
        val proposal = ProposalV2.parse(validProposal().toString())
        assertEquals("focus.propose", proposal.actionType)
        assertEquals(25, proposal.parameters["focus_minutes"])
        assertEquals(proposal, ProposalV2.parse(proposal.toJson().toString()))
    }

    @Test
    fun unknownFieldsActionsAndFractionalNumbersAreRejected() {
        val extraRoot = validProposal().put("extra", true)
        assertThrows(IllegalArgumentException::class.java) { ProposalV2.parse(extraRoot.toString()) }

        val unknownAction = validProposal().put("action_type", "shell.exec")
        assertThrows(IllegalArgumentException::class.java) { ProposalV2.parse(unknownAction.toString()) }

        val extraParameter = validProposal().put("parameters", JSONObject()
            .put("focus_minutes", 25).put("break_minutes", 5).put("cycles", 2).put("shell", "whoami"))
        assertThrows(IllegalArgumentException::class.java) { ProposalV2.parse(extraParameter.toString()) }

        val fractional = validProposal().put("parameters", JSONObject()
            .put("focus_minutes", 25.5).put("break_minutes", 5).put("cycles", 2))
        assertThrows(IllegalArgumentException::class.java) { ProposalV2.parse(fractional.toString()) }
    }

    @Test
    fun legacyMandateLoadsAsProposalsOnlyAndV2RoundTrips() {
        val legacy = AgentMandate.fromJson(
            """{"id":"legacy","version":3,"revoked":false,"allowedActionTypes":["focus_session"]}""",
        )
        assertEquals(AgentMode.PROPOSALS_ONLY, legacy.mode)
        assertEquals(setOf("focus.propose"), legacy.allowedActions)
        assertNull(legacy.sharedDataCategories.takeIf { it.isNotEmpty() })

        val current = AgentMandate.default().copy(sharedDataCategories = setOf("focus"))
        assertEquals(current, AgentMandate.fromJson(current.toJson().toString()))
        assertTrue(current.sharedDataCategories.contains("focus"))
    }

    @Test
    fun mandateRejectsCoercedSchemaVersionAndDuplicateDays() {
        val current = AgentMandate.default().toJson()
        current.put("schema_version", "2")
        assertThrows(IllegalArgumentException::class.java) { AgentMandate.fromJson(current.toString()) }

        val duplicateDays = AgentMandate.default().toJson()
        duplicateDays.getJSONObject("focus_window").put("days", org.json.JSONArray(listOf(1, 1)))
        assertThrows(IllegalArgumentException::class.java) { AgentMandate.fromJson(duplicateDays.toString()) }
    }

    private fun validProposal() = JSONObject()
        .put("schema_version", 2)
        .put("proposal_id", UUID.randomUUID().toString())
        .put("idempotency_key", "proposal-01")
        .put("conversation_id", JSONObject.NULL)
        .put("mandate_id", "mandate-v2")
        .put("mandate_version", 1)
        .put("context_version", "ctx-hash")
        .put("created_at", "2026-10-19T12:00:00Z")
        .put("expires_at", "2026-10-19T12:10:00Z")
        .put("action_type", "focus.propose")
        .put("parameters", JSONObject().put("focus_minutes", 25).put("break_minutes", 5).put("cycles", 2))
        .put("reason", "Commencer par une session courte.")
        .put("required_level", "confirm")
        .put("state", "proposed")
}
