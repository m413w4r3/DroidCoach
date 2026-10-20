package dev.atlas.coach.app

import dev.atlas.coach.sync.agent.AgentBackendObservation
import dev.atlas.coach.sync.agent.AgentBackendState
import dev.atlas.coach.sync.agent.AgentFocusProposalCard
import dev.atlas.coach.sync.agent.AgentHomeDirective
import dev.atlas.coach.sync.agent.AgentHomeState
import dev.atlas.coach.sync.agent.DirectiveProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAgentStateTest {
    @Test
    fun titleAtSixtyCodePointsIsPreserved() {
        val title = "a".repeat(60)
        assertEquals(title, truncateHomeDirectiveTitle(title))
    }

    @Test
    fun longTitleIsTruncatedToSixtyCodePointsAndKeepsSurrogatePairsWhole() {
        val title = "🙂".repeat(61)
        val display = truncateHomeDirectiveTitle(title)
        assertEquals(60, display.codePointCount(0, display.length))
        assertTrue(display.endsWith("…"))
        assertFalse(display.dropLast(1).last().isHighSurrogate())
    }

    @Test
    fun aCustomLimitMustBePositive() {
        val error = runCatching { truncateHomeDirectiveTitle("titre", 0) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun remoteDirectiveProvenanceAndBackendContactSurviveSerialization() {
        val saved = AgentHomeState(
            directive = AgentHomeDirective("Reprendre", "Un pas à la fois", "open_coach", null, sourceProposalId = "proposal-1"),
        ).withBackendContactSucceeded(1_000L)

        val restored = AgentHomeState.fromJson(saved.toJson().toString())

        assertEquals(DirectiveProvenance.REMOTE_AI, restored.directive?.provenance)
        assertEquals("proposal-1", restored.directive?.sourceProposalId)
        assertEquals(AgentBackendState.REACHABLE, restored.backendStateAt(1_001L))
        assertEquals(1_000L, restored.backendObservation.lastSuccessfulContactAtUtcMillis)
        assertEquals(HomeAgentStatus.CONNECTED, homeAgentStatusFor(restored.directive!!.provenance, restored.backendStateAt(1_001L)))
    }

    @Test
    fun backendReachabilityExpiresAndOldAgentDirectiveRemainsRemote() {
        val stale = AgentHomeState().withBackendContactSucceeded(1_000L)
        val oldSchema = """{"schema_version":1,"directive":{"title":"Cap","body":"Maintenir","primary_action":"open_coach","focus_proposal_id":null},"focus_proposal":null,"content_recommendation":null,"card_order":["directive","focus","content"],"cancelled_focus_proposal_ids":[]}"""
        val restoredLegacy = AgentHomeState.fromJson(oldSchema)

        assertEquals(AgentBackendState.UNVERIFIED, stale.backendStateAt(1_000L + AgentBackendObservation.FRESHNESS_TTL_MILLIS + 1L))
        assertEquals(DirectiveProvenance.REMOTE_AI, restoredLegacy.directive?.provenance)
        assertEquals(AgentBackendState.UNVERIFIED, restoredLegacy.backendStateAt(2_000L))
        assertEquals(HomeAgentStatus.UNAVAILABLE, homeAgentStatusFor(restoredLegacy.directive!!.provenance, restoredLegacy.backendStateAt(2_000L)))
    }

    @Test
    fun localDemoAndManualDirectivesRemainDistinctFromBackendAvailability() {
        val demo = AgentHomeDirective("Démo", "Exemple", "open_coach", null, DirectiveProvenance.LOCAL_DEMO)
        val restored = AgentHomeState(directive = demo)
        val decoded = AgentHomeState.fromJson(restored.toJson().toString())

        assertEquals(DirectiveProvenance.LOCAL_DEMO, decoded.directive?.provenance)
        assertEquals(HomeAgentStatus.LOCAL_ONLY, homeAgentStatusFor(decoded.directive!!.provenance, AgentBackendState.REACHABLE))
        assertEquals(HomeAgentStatus.LOCAL_ONLY, homeAgentStatusFor(DirectiveProvenance.MANUAL_ENTRY, AgentBackendState.UNAVAILABLE))
    }

    @Test
    fun failedBackendObservationIsDistinctFromAnUnverifiedOne() {
        val observation = AgentHomeState().withBackendContactSucceeded(10_000L).withBackendContactFailed(11_000L)
        val clockRollback = AgentHomeState().withBackendContactSucceeded(10_000L).withBackendContactFailed(9_000L)

        assertEquals(AgentBackendState.UNAVAILABLE, observation.backendStateAt(11_001L))
        assertEquals(AgentBackendState.UNVERIFIED, observation.backendStateAt(11_000L + AgentBackendObservation.FRESHNESS_TTL_MILLIS + 1L))
        assertEquals(AgentBackendState.UNVERIFIED, clockRollback.backendStateAt(9_001L))
    }

    @Test
    fun remoteDirectiveUsesReadableConnectedOrPreservedProvenance() {
        val unavailable = HomeAgentState(
            directive = HomeDirective("Remote", "Persisted", HomePrimaryAction.NONE, DirectiveProvenance.REMOTE_AI),
            directiveProvenance = DirectiveProvenance.REMOTE_AI,
            backendState = AgentBackendState.UNAVAILABLE,
            lastSuccessfulContactAtUtcMillis = 10L * 60L * 60L * 1_000L + 7L * 60L * 1_000L,
        )
        val connected = unavailable.copy(backendState = AgentBackendState.REACHABLE)

        assertEquals("Dernière directive conservée", unavailable.directiveStatusLabel())
        assertEquals("Coach connecté", connected.directiveStatusLabel())
    }

    @Test
    fun localDirectiveUsesShortLocalProvenance() {
        val local = HomeAgentState(
            directive = HomeDirective("Cap", "", HomePrimaryAction.NONE, DirectiveProvenance.MANUAL_ENTRY),
            directiveProvenance = DirectiveProvenance.MANUAL_ENTRY,
            backendState = AgentBackendState.UNVERIFIED,
        )

        assertEquals("Local", local.directiveStatusLabel())
    }

    @Test
    fun editedFocusPlanFieldsSurviveHomeStateSerialization() {
        val proposal = AgentFocusProposalCard(
            proposalId = "proposal-1",
            label = "Titre de directive modifié",
            focusMinutes = 37,
            breakMinutes = 7,
            cycles = 6,
            longBreakMinutes = 22,
            longBreakEveryCycles = 3,
            continuous = true,
        )

        val restored = AgentHomeState.fromJson(AgentHomeState(focusProposal = proposal).toJson().toString())

        assertEquals(proposal, restored.focusProposal)
    }

    @Test
    fun corruptHomeAgentStateIsRetainedInAnInspectableQuarantineEnvelope() {
        val corrupt = "{broken-home-state"

        val quarantined = AgentHomeState.fromJson(corrupt)
        val restored = AgentHomeState.fromJson(quarantined.toJson().toString())

        assertEquals(corrupt, restored.quarantinedRaw)
        assertTrue(restored.quarantineReason?.contains("invalid HOME agent state") == true)
    }
}
