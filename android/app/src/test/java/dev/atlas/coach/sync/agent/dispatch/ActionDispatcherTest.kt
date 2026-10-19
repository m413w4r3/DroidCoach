package dev.atlas.coach.sync.agent.dispatch

import dev.atlas.coach.sync.OutboxStorage
import dev.atlas.coach.sync.agent.AgentActionType
import dev.atlas.coach.sync.agent.AgentFocusWindow
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.AgentMode
import dev.atlas.coach.sync.agent.AgentVerificationContext
import dev.atlas.coach.sync.agent.LocalMandateStore
import dev.atlas.coach.sync.agent.MandateVerifier
import dev.atlas.coach.sync.agent.ProposalLevel
import dev.atlas.coach.sync.agent.ProposalState
import dev.atlas.coach.sync.agent.ProposalV2
import dev.atlas.coach.sync.agent.ProposedAction
import dev.atlas.coach.sync.agent.inbox.AgentInboxRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class ActionDispatcherTest {
    private val now = LocalDateTime.of(2026, 10, 19, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val verifier = MandateVerifier()
    private val baseMandate = AgentMandate.default().copy(
        focusWindow = AgentFocusWindow(start = "00:00", end = "23:59", timezone = "UTC"),
        appPolicyBounds = mapOf("com.example.reader" to dev.atlas.coach.sync.agent.AgentAppPolicyBounds(30, "soft_reminder", 7)),
    )

    @Test
    fun verifierRejectsRevokedExpiredReplayedUnknownAndOutOfBoundsProposals() {
        val valid = focusProposal()
        assertFalse(verifier.verify(valid, baseMandate.copy(revoked = true), context()).accepted)
        assertFalse(verifier.verify(
            valid.copy(createdAtUtcMillis = now - 60_000L, expiresAtUtcMillis = now - 1L),
            baseMandate, context(),
        ).accepted)
        assertFalse(verifier.verify(valid.copy(state = ProposalState.EXECUTED), baseMandate, context()).accepted)
        assertFalse(verifier.verify(ProposedAction("shell.exec", emptyMap(), "Action inconnue"), baseMandate).accepted)
        assertFalse(verifier.verify(
            valid.copy(parameters = mapOf("focus_minutes" to 181, "break_minutes" to 5, "cycles" to 1)),
            baseMandate, context(),
        ).accepted)
    }

    @Test
    fun verifierRejectsVitalPackagesAndPackagesWithoutObservationConsent() {
        val vital = appPolicyProposal("com.android.phone")
        assertFalse(verifier.verify(vital, baseMandate.copy(appPolicyBounds = mapOf(
            "com.android.phone" to dev.atlas.coach.sync.agent.AgentAppPolicyBounds(30, "soft_reminder", 7),
        )), context(observed = setOf("com.android.phone"), vital = setOf("com.android.phone"))).accepted)

        val notConsented = appPolicyProposal("com.example.reader", "app_policy.adjust")
        assertFalse(verifier.verify(notConsented, baseMandate, context()).accepted)
    }

    @Test
    fun cancelledFocusSessionCannotBeResurrectedByANewStartProposal() {
        val proposal = ProposalV2(
            schemaVersion = 2, proposalId = UUID.randomUUID().toString(), idempotencyKey = UUID.randomUUID().toString(),
            conversationId = null, mandateId = baseMandate.id, mandateVersion = baseMandate.version,
            contextVersion = "test", createdAtUtcMillis = now, expiresAtUtcMillis = now + 60_000,
            actionType = AgentActionType.FOCUS_START.wireValue,
            parameters = mapOf("session_ref" to "focus-proposal-1"), reason = "Démarrer la session",
            requiredLevel = ProposalLevel.CONFIRM,
        )
        val decision = verifier.verify(proposal, baseMandate, context(
            existingFocusIds = setOf("focus-proposal-1"), cancelledFocusIds = setOf("focus-proposal-1"),
        ))
        assertFalse(decision.accepted)
        assertTrue(decision.reason.contains("annulée"))
    }

    @Test
    fun localMandateModeOverridesIndicativeBackendLevel() {
        val proposal = ProposalV2(
            schemaVersion = 2, proposalId = UUID.randomUUID().toString(), idempotencyKey = UUID.randomUUID().toString(),
            conversationId = null, mandateId = baseMandate.id, mandateVersion = baseMandate.version,
            contextVersion = "test", createdAtUtcMillis = now, expiresAtUtcMillis = now + 60_000,
            actionType = "home.reorder_cards", parameters = mapOf("order" to listOf("focus")),
            reason = "Placer Focus en premier.", requiredLevel = ProposalLevel.CONFIRM,
        )
        val decision = verifier.verify(
            proposal,
            baseMandate.copy(mode = AgentMode.PREAUTHORIZED, allowedActions = setOf("home.reorder_cards")),
            context(),
        )
        assertTrue(decision.reason, decision.accepted)
        assertFalse(decision.requiresConfirmation)

        val editedOrPostponed = verifier.verify(
            proposal.copy(state = ProposalState.PENDING_CONFIRMATION),
            baseMandate.copy(mode = AgentMode.PREAUTHORIZED, allowedActions = setOf("home.reorder_cards")),
            context(),
        )
        assertTrue(editedOrPostponed.accepted)
        assertTrue(editedOrPostponed.requiresConfirmation)

        val emergency = verifier.verify(
            proposal,
            baseMandate.copy(mode = AgentMode.PREAUTHORIZED, allowedActions = setOf("home.reorder_cards")),
            context().copy(emergencyPaused = true),
        )
        assertFalse(emergency.accepted)
    }

    @Test
    fun focusPlanCanBePreauthorizedInsideTheMandateWindow() {
        val plan = ProposalV2(
            schemaVersion = 2, proposalId = UUID.randomUUID().toString(), idempotencyKey = UUID.randomUUID().toString(),
            conversationId = null, mandateId = baseMandate.id, mandateVersion = baseMandate.version,
            contextVersion = "test", createdAtUtcMillis = now, expiresAtUtcMillis = now + 60_000,
            actionType = AgentActionType.FOCUS_PLAN.wireValue,
            parameters = mapOf(
                "focus_minutes" to 25, "break_minutes" to 5, "cycles" to 1,
                "start_at" to "2026-10-19T13:00:00Z", "timezone" to "UTC",
            ),
            reason = "Planifier une session courte.", requiredLevel = ProposalLevel.CONFIRM,
        )
        val decision = verifier.verify(
            plan,
            baseMandate.copy(mode = AgentMode.PREAUTHORIZED, allowedActions = setOf("focus.plan")),
            context().copy(capabilities = setOf("focus", "focusRoutines")),
        )
        assertTrue(decision.reason, decision.accepted)
        assertFalse(decision.requiresConfirmation)

        val immediateRetry = verifier.verify(
            plan.copy(proposalId = UUID.randomUUID().toString(), idempotencyKey = UUID.randomUUID().toString()),
            baseMandate.copy(mode = AgentMode.PREAUTHORIZED, allowedActions = setOf("focus.plan")),
            context().copy(
                capabilities = setOf("focus", "focusRoutines"),
                lastActionAtUtcByType = mapOf("focus.plan" to now - 1_000L),
            ),
        )
        assertFalse(immediateRetry.accepted)
    }

    @Test
    fun repeatedPullIsIdempotentAndDispatcherRunsAcceptedEffectOnlyOnce() {
        val storage = MemoryStorage()
        val inbox = AgentInboxRepository(storage) { now }
        val proposal = focusProposal()
        assertEquals(1, inbox.mergePulled(listOf(proposal), "cursor-1").added)
        assertEquals(0, inbox.mergePulled(listOf(proposal), "cursor-1").added)
        val actions = FakeActions()
        val dispatcher = ActionDispatcher(
            LocalMandateStore(baseMandate), inbox, verifier,
            context = { context() }, actions = actions, journal = InMemoryAgentEffectJournal(), nowUtcMillis = { now },
        )
        assertEquals(DispatchStatus.EXECUTED, dispatcher.accept(proposal.proposalId).status)
        assertEquals(DispatchStatus.ALREADY_HANDLED, dispatcher.accept(proposal.proposalId).status)
        assertEquals(1, actions.focusProposals)
    }

    private fun focusProposal() = ProposalV2(
        schemaVersion = 2, proposalId = UUID.randomUUID().toString(), idempotencyKey = UUID.randomUUID().toString(),
        conversationId = null, mandateId = baseMandate.id, mandateVersion = baseMandate.version,
        contextVersion = "test", createdAtUtcMillis = now, expiresAtUtcMillis = now + 60_000,
        actionType = "focus.propose", parameters = mapOf("focus_minutes" to 25, "break_minutes" to 5, "cycles" to 1),
        reason = "Commencer par une session courte.", requiredLevel = ProposalLevel.CONFIRM,
    )

    private fun appPolicyProposal(packageRef: String, action: String = "app_policy.propose") = ProposalV2(
        schemaVersion = 2, proposalId = UUID.randomUUID().toString(), idempotencyKey = UUID.randomUUID().toString(),
        conversationId = null, mandateId = baseMandate.id, mandateVersion = baseMandate.version,
        contextVersion = "test", createdAtUtcMillis = now, expiresAtUtcMillis = now + 60_000,
        actionType = action,
        parameters = mapOf(
            "package_ref" to packageRef, "daily_limit_minutes" to 45,
            "mode" to "soft_reminder", "valid_until" to "2026-10-20T12:00:00Z",
        ),
        reason = "Proposer une limite douce.", requiredLevel = ProposalLevel.CONFIRM,
    )

    private fun context(
        observed: Set<String> = emptySet(),
        vital: Set<String> = emptySet(),
        existingFocusIds: Set<String> = emptySet(),
        cancelledFocusIds: Set<String> = emptySet(),
    ) = AgentVerificationContext(
        nowUtcMillis = now,
        observedPackages = observed,
        vitalPackages = vital,
        existingFocusProposalIds = existingFocusIds,
        cancelledFocusProposalIds = cancelledFocusIds,
    )

    private class MemoryStorage : OutboxStorage {
        private var raw: String? = null
        override fun read() = raw
        override fun write(value: String) { raw = value }
    }

    private class FakeActions : AgentActionPort {
        var focusProposals = 0
        private fun applied() = AgentActionOutcome(true, "Action appliquée")
        override fun setHomeDirective(proposal: ProposalV2) = applied()
        override fun reorderHomeCards(proposal: ProposalV2) = applied()
        override fun proposeFocus(proposal: ProposalV2): AgentActionOutcome { focusProposals++; return applied() }
        override fun planFocus(proposal: ProposalV2) = applied()
        override fun startFocus(proposal: ProposalV2) = applied()
        override fun pauseFocus(proposal: ProposalV2) = applied()
        override fun suggestHabit(proposal: ProposalV2) = applied()
        override fun proposeAppPolicy(proposal: ProposalV2) = applied()
        override fun adjustAppPolicy(proposal: ProposalV2) = applied()
        override fun recommendContent(proposal: ProposalV2) = applied()
    }
}
