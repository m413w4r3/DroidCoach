package dev.atlas.coach.sync.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentContractsInstrumentationTest {
    @Test
    fun proposalAndHomeStateRoundTripOnAndroidRuntime() {
        val proposal = ProposalV2(
            schemaVersion = 2,
            proposalId = UUID.randomUUID().toString(),
            idempotencyKey = UUID.randomUUID().toString(),
            conversationId = null,
            mandateId = "mandate-v2",
            mandateVersion = 1,
            contextVersion = "android-runtime",
            createdAtUtcMillis = 1_800_000_000_000L,
            expiresAtUtcMillis = 1_800_000_600_000L,
            actionType = "focus.propose",
            parameters = mapOf("focus_minutes" to 25, "break_minutes" to 5, "cycles" to 1),
            reason = "Commencer progressivement.",
            requiredLevel = ProposalLevel.CONFIRM,
        )
        assertEquals(proposal, ProposalV2.parse(proposal.toJson().toString()))

        val home = AgentHomeState(cancelledFocusProposalIds = setOf(proposal.proposalId))
        assertTrue(AgentHomeState.fromJson(home.toJson().toString()).cancelledFocusProposalIds.contains(proposal.proposalId))
    }
}
