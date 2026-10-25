package dev.atlas.coach.sync.agent.dispatch

import dev.atlas.coach.sync.OutboxStorage
import dev.atlas.coach.sync.agent.AgentActionType
import dev.atlas.coach.sync.agent.AgentFocusWindow
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.AgentMode
import dev.atlas.coach.sync.agent.AgentVerificationContext
import dev.atlas.coach.sync.agent.AgentHomeState
import dev.atlas.coach.sync.agent.DirectiveProvenance
import dev.atlas.coach.sync.agent.withRemoteDirective
import dev.atlas.coach.sync.agent.LocalMandateStore
import dev.atlas.coach.sync.agent.MandateStore
import dev.atlas.coach.sync.agent.MandateVerifier
import dev.atlas.coach.sync.agent.focusSessionDurationMinutes
import dev.atlas.coach.sync.agent.ProposalLevel
import dev.atlas.coach.sync.agent.ProposalState
import dev.atlas.coach.sync.agent.ProposalV2
import dev.atlas.coach.sync.agent.ProposedAction
import dev.atlas.coach.sync.agent.dispatch.AgentEffectPhase
import dev.atlas.coach.sync.agent.dispatch.AgentEffectReconciliation
import dev.atlas.coach.sync.agent.dispatch.JsonAgentEffectJournal
import dev.atlas.coach.sync.agent.inbox.AgentInboxRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun pulledRemoteDirectiveSurvivesPreparedRevalidationAndIsPersisted() {
        val proposal = ProposalV2(
            schemaVersion = 2,
            proposalId = "4ddfb73c-4eb7-4c57-9f10-d6e0bdb3c872",
            idempotencyKey = "8858d813-72f0-408d-908e-6601511e2c1d",
            conversationId = null,
            mandateId = baseMandate.id,
            mandateVersion = baseMandate.version,
            contextVersion = "openclaw-test",
            createdAtUtcMillis = now,
            expiresAtUtcMillis = now + 60_000L,
            actionType = "home.set_directive",
            parameters = mapOf(
                "title" to "Réviser les notes",
                "body" to "Commencer par la première section.",
                "primary_action" to "open_coach",
            ),
            reason = "Directive issue du Coach distant.",
            requiredLevel = ProposalLevel.CONFIRM,
        )
        val inbox = AgentInboxRepository(MemoryStorage()) { now }
        assertEquals(1, inbox.mergePulled(listOf(proposal), "remote-cursor").added)
        val journal = InMemoryAgentEffectJournal()
        val homeStorage = MemoryStorage()
        val actions = RemoteDirectiveActions(homeStorage)
        val mandate = baseMandate.copy(allowedActions = setOf("home.set_directive"))
        val dispatcher = ActionDispatcher(
            mandates = LocalMandateStore(mandate),
            inbox = inbox,
            verifier = verifier,
            context = {
                context().copy(
                    lastActionAtUtcByType = journal.cooldownHistoryByActionType(),
                )
            },
            actions = actions,
            journal = journal,
            nowUtcMillis = { now },
        )

        val result = dispatcher.accept(proposal.proposalId)

        assertEquals(DispatchStatus.EXECUTED, result.status)
        assertNotNull(actions.agentHomeState.directive)
        val restored = AgentHomeState.fromJson(homeStorage.read())
        assertEquals("Réviser les notes", restored.directive?.title)
        assertEquals(DirectiveProvenance.REMOTE_AI, restored.directive?.provenance)
        assertEquals(proposal.proposalId, restored.directive?.sourceProposalId)
        assertEquals(ProposalState.EXECUTED, inbox.get(proposal.proposalId)?.proposal?.state)
    }

    @Test
    fun onlySuccessfullyExecutedEffectsStartCooldown() {
        val journal = InMemoryAgentEffectJournal()
        val prepared = AgentEffectRecord(
            proposalId = "6d953dd3-9545-4f8e-b77e-e0cf6b827322",
            idempotencyKey = "e2f49d7a-22c1-4612-96af-d60632d9634a",
            actionType = "home.set_directive",
            state = ProposalState.ACCEPTED,
            effect = "Acceptée localement; effet à réconcilier",
            atUtcMillis = now,
            phase = AgentEffectPhase.PREPARED,
        )

        journal.append(prepared)
        assertTrue(journal.cooldownHistoryByActionType().isEmpty())

        journal.append(prepared.copy(
            proposalId = "9a7b8c55-c838-4847-9f6e-796ad33be1f4",
            idempotencyKey = "f201d46c-1234-4f02-9b04-396ebad7af8e",
            actionType = "home.reorder_cards",
            state = ProposalState.PENDING_CONFIRMATION,
            effect = "Reportée par l'utilisateur",
            phase = AgentEffectPhase.NONE,
        ))
        assertTrue(journal.cooldownHistoryByActionType().isEmpty())

        journal.append(prepared.copy(
            proposalId = "8e4af0dd-caf5-4f8f-af80-cd542946e676",
            idempotencyKey = "69532ab1-511f-4336-a8df-aac3aca29d8d",
            actionType = "home.reorder_cards",
            state = ProposalState.REJECTED,
            effect = "Action refusée par la politique.",
            phase = AgentEffectPhase.COMPLETE,
        ))
        assertTrue(journal.cooldownHistoryByActionType().isEmpty())

        journal.append(prepared.copy(
            state = ProposalState.EXECUTED,
            effect = "Directive HOME enregistrée localement.",
            phase = AgentEffectPhase.COMPLETE,
        ))
        assertEquals(mapOf("home.set_directive" to now), journal.cooldownHistoryByActionType())
    }

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
    fun corruptEffectJournalIsArchivedBeforeAUsableEmptyJournalIsCreated() {
        val storage = MemoryStorage("{broken")

        val journal = JsonAgentEffectJournal(storage)

        assertEquals("{broken", storage.quarantinedRaw)
        assertEquals("agent-effects.quarantine", journal.quarantineInfo()?.location)
        assertTrue(journal.entries().isEmpty())
        assertTrue(storage.read()?.contains("\"records\"") == true)
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
    fun focusProposeRejectsTotalDurationAboveMandateLimit() {
        val proposal = focusProposal().copy(
            parameters = mapOf("focus_minutes" to 25, "break_minutes" to 5, "cycles" to 4),
        )
        val mandate = baseMandate.copy(
            allowedActions = setOf("focus.propose"),
            focusWindow = baseMandate.focusWindow.copy(maxMinutesPerSession = 90),
        )

        val decision = verifier.verify(proposal, mandate, context())

        assertFalse(decision.accepted)
        assertEquals("Durée totale supérieure à la borne du mandat.", decision.reason)
    }

    @Test
    fun focusPlanRejectsTotalDurationAboveMandateLimit() {
        val plan = focusPlanProposal(
            startAt = "2026-10-20T09:00:00Z",
            focusMinutes = 25,
            breakMinutes = 5,
            cycles = 4,
        )
        val mandate = baseMandate.copy(
            allowedActions = setOf("focus.plan"),
            focusWindow = baseMandate.focusWindow.copy(maxMinutesPerSession = 90),
        )

        val decision = verifier.verify(
            plan,
            mandate,
            context().copy(capabilities = setOf("focus", "focusRoutines")),
        )

        assertFalse(decision.accepted)
        assertEquals("Durée totale supérieure à la borne du mandat.", decision.reason)
    }

    @Test
    fun focusPlanRejectsLongBreakDurationAboveMandateLimit() {
        val plan = focusPlanProposal(
            startAt = "2026-10-20T08:00:00Z",
            focusMinutes = 14,
            breakMinutes = 5,
            cycles = 5,
        )
        val mandate = baseMandate.copy(
            allowedActions = setOf("focus.plan"),
            focusWindow = AgentFocusWindow(
                start = "08:00", end = "20:00", timezone = "UTC", maxMinutesPerSession = 90,
            ),
        )

        val decision = verifier.verify(
            plan,
            mandate,
            context().copy(capabilities = setOf("focus", "focusRoutines")),
        )

        assertFalse(decision.accepted)
        assertEquals("Durée totale supérieure à la borne du mandat.", decision.reason)
    }

    @Test
    fun focusPlanRejectsZeroBreakCoercedDurationAboveLimit() {
        val plan = focusPlanProposal(
            startAt = "2026-10-20T08:00:00Z",
            focusMinutes = 20,
            breakMinutes = 0,
            cycles = 8,
        )
        val mandate = baseMandate.copy(
            allowedActions = setOf("focus.plan"),
            focusWindow = AgentFocusWindow(
                start = "08:00", end = "20:00", timezone = "UTC", maxMinutesPerSession = 180,
            ),
        )

        val decision = verifier.verify(
            plan,
            mandate,
            context().copy(capabilities = setOf("focus", "focusRoutines")),
        )

        assertFalse(decision.accepted)
        assertEquals("Durée totale supérieure à la borne du mandat.", decision.reason)
    }

    @Test
    fun focusPlanRejectsZeroBreakEndAfterMandateWindow() {
        val plan = focusPlanProposal(
            startAt = "2026-10-20T08:00:00Z",
            focusMinutes = 25,
            breakMinutes = 0,
            cycles = 2,
        )
        val mandate = baseMandate.copy(
            allowedActions = setOf("focus.plan"),
            focusWindow = AgentFocusWindow(
                start = "08:00", end = "08:50", timezone = "UTC", maxMinutesPerSession = 50,
            ),
        )

        val decision = verifier.verify(
            plan,
            mandate,
            context().copy(capabilities = setOf("focus", "focusRoutines")),
        )

        assertFalse(decision.accepted)
        assertEquals("Durée totale supérieure à la borne du mandat.", decision.reason)
    }

    @Test
    fun focusPlanRejectsZeroBreakEndAfterMandateWindowWhenDurationLimitAllowsIt() {
        val plan = focusPlanProposal(
            startAt = "2026-10-20T08:00:00Z",
            focusMinutes = 25,
            breakMinutes = 0,
            cycles = 2,
        )
        val mandate = baseMandate.copy(
            allowedActions = setOf("focus.plan"),
            focusWindow = AgentFocusWindow(
                start = "08:00", end = "08:50", timezone = "UTC", maxMinutesPerSession = 51,
            ),
        )

        val decision = verifier.verify(
            plan,
            mandate,
            context().copy(capabilities = setOf("focus", "focusRoutines")),
        )

        assertFalse(decision.accepted)
        assertEquals("Fin du plan hors fenêtre du mandat.", decision.reason)
    }

    @Test
    fun focusPlanAllowsZeroBreakEndExactlyAtMandateWindowBoundary() {
        val plan = focusPlanProposal(
            startAt = "2026-10-20T08:00:00Z",
            focusMinutes = 25,
            breakMinutes = 0,
            cycles = 2,
        )
        val mandate = baseMandate.copy(
            allowedActions = setOf("focus.plan"),
            focusWindow = AgentFocusWindow(
                start = "08:00", end = "08:51", timezone = "UTC", maxMinutesPerSession = 51,
            ),
        )

        val decision = verifier.verify(
            plan,
            mandate,
            context().copy(capabilities = setOf("focus", "focusRoutines")),
        )

        assertTrue(decision.reason, decision.accepted)
    }

    @Test
    fun focusSessionDurationMatchesFinitePersistedFocusPlanPhases() {
        assertEquals(
            100L,
            focusSessionDurationMinutes(
                focusMinutes = 14L,
                breakMinutes = 5L,
                cycles = 5L,
                longBreakMinutes = 15L,
                longBreakEveryCycles = 4L,
                continuous = false,
            ),
        )
        assertEquals(
            181L,
            focusSessionDurationMinutes(
                focusMinutes = 20L,
                breakMinutes = 0L,
                cycles = 8L,
                longBreakMinutes = 15L,
                longBreakEveryCycles = 4L,
                continuous = false,
            ),
        )
    }

    @Test
    fun focusSessionDurationMarksContinuousPlanUnbounded() {
        assertEquals(
            Long.MAX_VALUE,
            focusSessionDurationMinutes(
                focusMinutes = 20L,
                breakMinutes = 0L,
                cycles = 8L,
                longBreakMinutes = 15L,
                longBreakEveryCycles = 4L,
                continuous = true,
            ),
        )
    }

    @Test
    fun focusProposeAndPlanRejectContinuousParameter() {
        val capabilities = setOf("focus", "focusRoutines")
        val proposed = focusProposal()
        val planned = focusPlanProposal(startAt = "2026-10-20T08:00:00Z")
        val mandate = baseMandate.copy(allowedActions = setOf("focus.propose", "focus.plan"))

        val proposeDecision = verifier.verify(
            proposed.copy(parameters = proposed.parameters + ("continuous" to true)),
            mandate,
            context().copy(capabilities = capabilities),
        )
        val planDecision = verifier.verify(
            planned.copy(parameters = planned.parameters + ("continuous" to true)),
            mandate,
            context().copy(capabilities = capabilities),
        )

        assertFalse(proposeDecision.accepted)
        assertEquals("Paramètres invalides pour cette action.", proposeDecision.reason)
        assertFalse(planDecision.accepted)
        assertEquals("Paramètres invalides pour cette action.", planDecision.reason)
    }

    @Test
    fun focusPlanRejectsLongBreakEndAfterMandateWindow() {
        val plan = focusPlanProposal(
            startAt = "2026-10-20T08:00:00Z",
            focusMinutes = 14,
            breakMinutes = 5,
            cycles = 5,
        )
        val mandate = baseMandate.copy(
            allowedActions = setOf("focus.plan"),
            focusWindow = AgentFocusWindow(
                start = "08:00", end = "09:30", timezone = "UTC", maxMinutesPerSession = 120,
            ),
        )

        val decision = verifier.verify(
            plan,
            mandate,
            context().copy(capabilities = setOf("focus", "focusRoutines")),
        )

        assertFalse(decision.accepted)
        assertEquals("Fin du plan hors fenêtre du mandat.", decision.reason)
    }

    @Test
    fun focusPlanAllowsTotalDurationExactlyAtMandateLimit() {
        val plan = focusPlanProposal(
            startAt = "2026-10-20T08:00:00Z",
            focusMinutes = 25,
            breakMinutes = 5,
            cycles = 2,
        )
        val mandate = baseMandate.copy(
            allowedActions = setOf("focus.plan"),
            focusWindow = AgentFocusWindow(
                start = "08:00", end = "20:00", timezone = "UTC", maxMinutesPerSession = 55,
            ),
        )

        val decision = verifier.verify(
            plan,
            mandate,
            context().copy(capabilities = setOf("focus", "focusRoutines")),
        )

        assertTrue(decision.reason, decision.accepted)
    }

    @Test
    fun focusPlanRejectsEndAfterDailyMandateWindow() {
        val plan = focusPlanProposal(startAt = "2026-10-20T09:45:00Z")
        val mandate = baseMandate.copy(
            allowedActions = setOf("focus.plan"),
            focusWindow = AgentFocusWindow(start = "08:00", end = "10:00", timezone = "UTC"),
        )

        val decision = verifier.verify(
            plan,
            mandate,
            context().copy(capabilities = setOf("focus", "focusRoutines")),
        )

        assertFalse(decision.accepted)
        assertEquals("Fin du plan hors fenêtre du mandat.", decision.reason)
    }

    @Test
    fun focusPlanAllowsEndExactlyAtDailyMandateWindowBoundary() {
        val plan = focusPlanProposal(startAt = "2026-10-20T09:35:00Z")
        val mandate = baseMandate.copy(
            mode = AgentMode.PREAUTHORIZED,
            allowedActions = setOf("focus.plan"),
            focusWindow = AgentFocusWindow(start = "08:00", end = "10:00", timezone = "UTC"),
        )

        val decision = verifier.verify(
            plan,
            mandate,
            context().copy(capabilities = setOf("focus", "focusRoutines")),
        )

        assertTrue(decision.reason, decision.accepted)
        assertFalse(decision.requiresConfirmation)
    }

    @Test
    fun focusPlanUsesOvernightMandateWindowForItsEnd() {
        val mandate = baseMandate.copy(
            allowedActions = setOf("focus.plan"),
            focusWindow = AgentFocusWindow(start = "22:00", end = "02:00", timezone = "UTC"),
        )
        val capabilities = setOf("focus", "focusRoutines")
        val inside = verifier.verify(
            focusPlanProposal(startAt = "2026-10-20T23:35:00Z"),
            mandate,
            context().copy(capabilities = capabilities),
        )
        val outside = verifier.verify(
            focusPlanProposal(startAt = "2026-10-21T01:45:00Z"),
            mandate,
            context().copy(capabilities = capabilities),
        )

        assertTrue(inside.reason, inside.accepted)
        assertFalse(outside.accepted)
        assertEquals("Fin du plan hors fenêtre du mandat.", outside.reason)
    }

    @Test
    fun focusPlanRejectsEndOnNextDayForFullDayMandateWindow() {
        val mandate = baseMandate.copy(
            allowedActions = setOf("focus.plan"),
            focusWindow = AgentFocusWindow(start = "00:00", end = "23:59", timezone = "UTC"),
        )
        val plan = focusPlanProposal(
            startAt = "2026-10-20T23:50:00Z",
            breakMinutes = 0,
        )

        val decision = verifier.verify(
            plan,
            mandate,
            context().copy(capabilities = setOf("focus", "focusRoutines")),
        )

        assertFalse(decision.accepted)
        assertEquals("Fin du plan hors fenêtre du mandat.", decision.reason)
    }

    @Test
    fun focusPlanAllowsEndExactlyAtFullDayMandateWindowBoundary() {
        val mandate = baseMandate.copy(
            allowedActions = setOf("focus.plan"),
            focusWindow = AgentFocusWindow(start = "00:00", end = "23:59", timezone = "UTC"),
        )
        val plan = focusPlanProposal(
            startAt = "2026-10-20T23:34:00Z",
            breakMinutes = 0,
        )

        val decision = verifier.verify(
            plan,
            mandate,
            context().copy(capabilities = setOf("focus", "focusRoutines")),
        )

        assertTrue(decision.reason, decision.accepted)
    }

    @Test
    fun focusPlanRejectsEndAfterWindowDuringDstFallBack() {
        val mandate = baseMandate.copy(
            allowedActions = setOf("focus.plan"),
            focusWindow = AgentFocusWindow(
                start = "00:00", end = "02:30", timezone = "Europe/Paris",
            ),
        )
        val plan = focusPlanProposal(
            startAt = "2026-10-25T01:15:00Z",
            focusMinutes = 30,
            breakMinutes = 0,
            cycles = 1,
            timezone = "Europe/Paris",
        )

        val decision = verifier.verify(
            plan,
            mandate,
            context().copy(capabilities = setOf("focus", "focusRoutines")),
        )

        assertFalse(decision.accepted)
        assertEquals("Fin du plan hors fenêtre du mandat.", decision.reason)
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

    @Test
    fun localCommitFailureLeavesPreparedEffectAndDoesNotCreateTerminalAck() {
        val inbox = AgentInboxRepository(MemoryStorage()) { now }
        val proposal = focusProposal()
        inbox.mergePulled(listOf(proposal), "cursor")
        val journal = InMemoryAgentEffectJournal()
        var commitAttempts = 0
        val actions = object : AgentActionPort by FakeActions() {
            override fun proposeFocus(proposal: ProposalV2): AgentActionOutcome {
                commitAttempts++
                return AgentActionOutcome(
                    applied = false,
                    effect = "Impossible d'enregistrer l'état local; réconciliation requise.",
                    requiresReconciliation = true,
                )
            }

            override fun reconcile(proposal: ProposalV2, idempotencyKey: String) =
                AgentEffectReconciliation.NOT_APPLIED
        }
        val dispatcher = dispatcher(inbox, journal, actions)

        val first = dispatcher.accept(proposal.proposalId)
        assertEquals(DispatchStatus.NEEDS_RECONCILIATION, first.status)
        assertEquals(AgentEffectPhase.PREPARED, journal.findByProposalId(proposal.proposalId)?.phase)
        assertEquals(ProposalState.ACCEPTED, inbox.get(proposal.proposalId)?.proposal?.state)
        assertTrue(inbox.pendingAcks().isEmpty())

        assertEquals(DispatchStatus.NEEDS_RECONCILIATION, dispatcher.accept(proposal.proposalId).status)
        assertEquals(2, commitAttempts)
        assertEquals(AgentEffectPhase.PREPARED, journal.findByProposalId(proposal.proposalId)?.phase)
        assertTrue(inbox.pendingAcks().isEmpty())
    }

    @Test
    fun restartAfterLocalEffectBeforeFinalJournalReconcilesWithoutRepeatingEffect() {
        val inboxStorage = MemoryStorage()
        val journalStorage = MemoryStorage()
        val inbox = AgentInboxRepository(inboxStorage) { now }
        val proposal = focusProposal()
        inbox.mergePulled(listOf(proposal), "cursor")
        val durableLocalEffects = mutableSetOf<String>()
        val actions = FakeActions(durableLocalEffects).apply { throwAfterEffect = true }
        val dispatcher = dispatcher(inbox, JsonAgentEffectJournal(journalStorage), actions)

        assertEquals(DispatchStatus.NEEDS_RECONCILIATION, dispatcher.accept(proposal.proposalId).status)
        assertEquals(1, actions.focusProposals)
        assertEquals(AgentEffectPhase.PREPARED, JsonAgentEffectJournal(journalStorage).findByProposalId(proposal.proposalId)?.phase)

        val restoredInbox = AgentInboxRepository(inboxStorage) { now }
        val restoredJournal = JsonAgentEffectJournal(journalStorage)
        val restarted = dispatcher(restoredInbox, restoredJournal, FakeActions(durableLocalEffects))
        assertEquals(DispatchStatus.EXECUTED, restarted.accept(proposal.proposalId).status)
        assertEquals(1, actions.focusProposals)
        assertEquals(ProposalState.EXECUTED, restoredInbox.get(proposal.proposalId)?.proposal?.state)
        assertEquals(1, restoredInbox.pendingAcks().size)
    }

    @Test
    fun restartBeforeLocalEffectRetriesOnlyAfterPortProvesEffectWasNotApplied() {
        val inboxStorage = MemoryStorage()
        val journalStorage = MemoryStorage()
        val inbox = AgentInboxRepository(inboxStorage) { now }
        val proposal = focusProposal()
        inbox.mergePulled(listOf(proposal), "cursor")
        val applied = mutableSetOf<String>()
        val firstAttempt = FakeActions(applied).apply { throwBeforeEffect = true }

        assertEquals(DispatchStatus.NEEDS_RECONCILIATION, dispatcher(inbox, JsonAgentEffectJournal(journalStorage), firstAttempt).accept(proposal.proposalId).status)

        val restoredInbox = AgentInboxRepository(inboxStorage) { now }
        val restartedActions = FakeActions(applied)
        assertEquals(DispatchStatus.EXECUTED, dispatcher(
            restoredInbox,
            JsonAgentEffectJournal(journalStorage),
            restartedActions,
        ).accept(proposal.proposalId).status)
        assertEquals(1, restartedActions.focusProposals)
        assertEquals(1, applied.size)
    }

    @Test
    fun interruptedAcceptedProposalIsRevalidatedAfterTtlBeforeAnyRetry() {
        val inboxStorage = MemoryStorage()
        val journalStorage = MemoryStorage()
        val proposal = focusProposal()
        val inbox = AgentInboxRepository(inboxStorage) { now }
        inbox.mergePulled(listOf(proposal), "cursor")
        val prepared = AgentEffectRecord(
            proposalId = proposal.proposalId,
            idempotencyKey = proposal.idempotencyKey,
            actionType = proposal.actionType,
            state = ProposalState.ACCEPTED,
            effect = "Effet préparé",
            atUtcMillis = now,
            phase = AgentEffectPhase.PREPARED,
        )
        JsonAgentEffectJournal(journalStorage).append(prepared)
        inbox.update(proposal.proposalId, ProposalState.ACCEPTED, "Effet préparé")
        val afterExpiry = proposal.expiresAtUtcMillis + 1L
        val restoredInbox = AgentInboxRepository(inboxStorage) { afterExpiry }
        val actions = FakeActions()
        val dispatcher = ActionDispatcher(
            LocalMandateStore(baseMandate), restoredInbox, verifier,
            context = { context().copy(nowUtcMillis = afterExpiry) },
            actions = actions, journal = JsonAgentEffectJournal(journalStorage), nowUtcMillis = { afterExpiry },
        )

        assertEquals(DispatchStatus.REFUSED, dispatcher.reconcileInterrupted().single().status)
        assertEquals(0, actions.focusProposals)
        assertEquals(ProposalState.REJECTED, restoredInbox.get(proposal.proposalId)?.proposal?.state)
        assertEquals(1, restoredInbox.pendingAcks().size)
    }

    @Test
    fun committedJournalRepairsInboxAndRetainsAckUntilServerConfirmsIt() {
        val inboxStorage = MemoryStorage(failOnWrite = 3)
        val journalStorage = MemoryStorage()
        val inbox = AgentInboxRepository(inboxStorage) { now }
        val proposal = focusProposal()
        inbox.mergePulled(listOf(proposal), "cursor")
        val actions = FakeActions()

        assertEquals(DispatchStatus.EXECUTED, dispatcher(inbox, JsonAgentEffectJournal(journalStorage), actions).accept(proposal.proposalId).status)
        assertEquals(1, actions.focusProposals)

        val restoredInbox = AgentInboxRepository(inboxStorage) { now }
        val restoredJournal = JsonAgentEffectJournal(journalStorage)
        dispatcher(restoredInbox, restoredJournal, actions).reconcileInterrupted()
        assertEquals(ProposalState.EXECUTED, restoredInbox.get(proposal.proposalId)?.proposal?.state)
        assertEquals(1, restoredInbox.pendingAcks().size)

        val afterLostAckResponse = AgentInboxRepository(inboxStorage) { now }
        assertEquals(1, afterLostAckResponse.pendingAcks().size)
        afterLostAckResponse.markAcked(proposal.proposalId)
        assertTrue(AgentInboxRepository(inboxStorage) { now }.pendingAcks().isEmpty())
        assertEquals(1, actions.focusProposals)
    }

    @Test
    fun mandateIsRevalidatedAfterDurableAcceptanceAndBeforeLocalEffect() {
        val inbox = AgentInboxRepository(MemoryStorage()) { now }
        val proposal = focusProposal()
        inbox.mergePulled(listOf(proposal), "cursor")
        val mandateStore = LocalMandateStore(baseMandate)
        var checks = 0
        val actions = FakeActions()
        val dispatcher = ActionDispatcher(
            mandateStore,
            inbox,
            verifier,
            context = {
                checks++
                if (checks == 1) mandateStore.revoke()
                context()
            },
            actions = actions,
            journal = InMemoryAgentEffectJournal(),
            nowUtcMillis = { now },
        )

        assertEquals(DispatchStatus.REFUSED, dispatcher.accept(proposal.proposalId).status)
        assertEquals(0, actions.focusProposals)
    }

    @Test
    fun consentQuotaAndTtlChangesBetweenAcceptanceAndEffectAreRechecked() {
        val consentProposal = appPolicyProposal("com.example.reader")
        val consentInbox = AgentInboxRepository(MemoryStorage()) { now }
        consentInbox.mergePulled(listOf(consentProposal), "consent")
        var consentChecks = 0
        val consentActions = FakeActions()
        val consentDispatcher = ActionDispatcher(
            LocalMandateStore(baseMandate), consentInbox, verifier,
            context = {
                consentChecks++
                context(observed = if (consentChecks == 1) setOf("com.example.reader") else emptySet())
            },
            actions = consentActions, journal = InMemoryAgentEffectJournal(), nowUtcMillis = { now },
        )
        assertEquals(DispatchStatus.REFUSED, consentDispatcher.accept(consentProposal.proposalId).status)
        assertEquals(0, consentActions.appPolicyProposals)

        val focus = focusProposal()
        val ttlInbox = AgentInboxRepository(MemoryStorage()) { now }
        ttlInbox.mergePulled(listOf(focus), "ttl")
        var ttlChecks = 0
        var clockNow = now
        val ttlActions = FakeActions()
        val ttlDispatcher = ActionDispatcher(
            LocalMandateStore(baseMandate), ttlInbox, verifier,
            context = {
                ttlChecks++
                if (ttlChecks == 2) clockNow = focus.expiresAtUtcMillis
                context().copy(nowUtcMillis = clockNow)
            },
            actions = ttlActions, journal = InMemoryAgentEffectJournal(), nowUtcMillis = { clockNow },
        )
        assertEquals(DispatchStatus.REFUSED, ttlDispatcher.accept(focus.proposalId).status)
        assertEquals(0, ttlActions.focusProposals)

        val quotaInbox = AgentInboxRepository(MemoryStorage()) { now }
        val quotaProposal = focusProposal()
        quotaInbox.mergePulled(listOf(quotaProposal), "quota")
        var quotaChecks = 0
        val quotaActions = FakeActions()
        val quotaDispatcher = ActionDispatcher(
            LocalMandateStore(baseMandate), quotaInbox, verifier,
            context = {
                quotaChecks++
                context().copy(changesToday = if (quotaChecks == 1) 0 else baseMandate.maxChangesPerDay)
            },
            actions = quotaActions, journal = InMemoryAgentEffectJournal(), nowUtcMillis = { now },
        )
        assertEquals(DispatchStatus.REFUSED, quotaDispatcher.accept(quotaProposal.proposalId).status)
        assertEquals(0, quotaActions.focusProposals)
    }

    @Test
    fun aFocusReferenceCancelledAfterAcceptanceIsRefusedBeforeEffect() {
        val ref = "accepted-focus-ref"
        val proposal = ProposalV2(
            schemaVersion = 2, proposalId = UUID.randomUUID().toString(), idempotencyKey = UUID.randomUUID().toString(),
            conversationId = null, mandateId = baseMandate.id, mandateVersion = baseMandate.version,
            contextVersion = "test", createdAtUtcMillis = now, expiresAtUtcMillis = now + 60_000,
            actionType = AgentActionType.FOCUS_START.wireValue,
            parameters = mapOf("session_ref" to ref), reason = "Démarrer la session",
            requiredLevel = ProposalLevel.CONFIRM,
        )
        val inbox = AgentInboxRepository(MemoryStorage()) { now }
        inbox.mergePulled(listOf(proposal), "cancelled-reference")
        var checks = 0
        val actions = FakeActions()
        val dispatcher = ActionDispatcher(
            LocalMandateStore(baseMandate), inbox, verifier,
            context = {
                checks++
                context(
                    existingFocusIds = setOf(ref),
                    cancelledFocusIds = if (checks == 1) emptySet() else setOf(ref),
                )
            },
            actions = actions, journal = InMemoryAgentEffectJournal(), nowUtcMillis = { now },
        )

        assertEquals(DispatchStatus.REFUSED, dispatcher.accept(proposal.proposalId).status)
        assertEquals(0, actions.focusStarts)
    }

    private fun focusProposal() = ProposalV2(
        schemaVersion = 2, proposalId = UUID.randomUUID().toString(), idempotencyKey = UUID.randomUUID().toString(),
        conversationId = null, mandateId = baseMandate.id, mandateVersion = baseMandate.version,
        contextVersion = "test", createdAtUtcMillis = now, expiresAtUtcMillis = now + 60_000,
        actionType = "focus.propose", parameters = mapOf("focus_minutes" to 25, "break_minutes" to 5, "cycles" to 1),
        reason = "Commencer par une session courte.", requiredLevel = ProposalLevel.CONFIRM,
    )

    private fun focusPlanProposal(
        startAt: String,
        focusMinutes: Int = 25,
        breakMinutes: Int = 5,
        cycles: Int = 1,
        timezone: String = "UTC",
    ) = ProposalV2(
        schemaVersion = 2, proposalId = UUID.randomUUID().toString(), idempotencyKey = UUID.randomUUID().toString(),
        conversationId = null, mandateId = baseMandate.id, mandateVersion = baseMandate.version,
        contextVersion = "test", createdAtUtcMillis = now, expiresAtUtcMillis = now + 60_000,
        actionType = AgentActionType.FOCUS_PLAN.wireValue,
        parameters = mapOf(
            "focus_minutes" to focusMinutes, "break_minutes" to breakMinutes, "cycles" to cycles,
            "start_at" to startAt, "timezone" to timezone,
        ),
        reason = "Planifier une session Focus.", requiredLevel = ProposalLevel.CONFIRM,
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

    private fun dispatcher(
        inbox: AgentInboxRepository,
        journal: dev.atlas.coach.sync.agent.dispatch.AgentEffectJournal,
        actions: AgentActionPort,
    ) = ActionDispatcher(
        LocalMandateStore(baseMandate), inbox, verifier,
        context = { context() }, actions = actions, journal = journal, nowUtcMillis = { now },
    )

    private class MemoryStorage(
        private var raw: String? = null,
        private val failOnWrite: Int? = null,
    ) : OutboxStorage {
        private var writes = 0
        var quarantinedRaw: String? = null
        override fun read() = raw
        override fun write(value: String) {
            writes++
            if (writes == failOnWrite) throw IllegalStateException("simulated persistence interruption")
            raw = value
        }
        override fun quarantine(raw: String, category: String, reason: String): String? {
            quarantinedRaw = raw
            return "$category.quarantine"
        }
    }

    private class FakeActions(private val appliedKeys: MutableSet<String> = mutableSetOf()) : AgentActionPort {
        var focusProposals = 0
        var appPolicyProposals = 0
        var focusStarts = 0
        var throwBeforeEffect = false
        var throwAfterEffect = false
        private fun applied() = AgentActionOutcome(true, "Action appliquée")
        override fun setHomeDirective(proposal: ProposalV2) = applied()
        override fun reorderHomeCards(proposal: ProposalV2) = applied()
        override fun proposeFocus(proposal: ProposalV2): AgentActionOutcome {
            focusProposals++
            if (throwBeforeEffect) {
                throwBeforeEffect = false
                throw IllegalStateException("simulated interruption before effect")
            }
            appliedKeys += proposal.idempotencyKey
            if (throwAfterEffect) {
                throwAfterEffect = false
                throw IllegalStateException("simulated interruption after effect")
            }
            return applied()
        }
        override fun planFocus(proposal: ProposalV2) = applied()
        override fun startFocus(proposal: ProposalV2): AgentActionOutcome { focusStarts++; return applied() }
        override fun pauseFocus(proposal: ProposalV2) = applied()
        override fun suggestHabit(proposal: ProposalV2) = applied()
        override fun proposeAppPolicy(proposal: ProposalV2): AgentActionOutcome { appPolicyProposals++; return applied() }
        override fun adjustAppPolicy(proposal: ProposalV2) = applied()
        override fun recommendContent(proposal: ProposalV2) = applied()
        override fun reconcile(proposal: ProposalV2, idempotencyKey: String) =
            if (idempotencyKey in appliedKeys) AgentEffectReconciliation.APPLIED else AgentEffectReconciliation.NOT_APPLIED
    }

    private class RemoteDirectiveActions(private val homeStorage: OutboxStorage) : AgentActionPort by FakeActions() {
        var agentHomeState = AgentHomeState()
            private set

        override fun setHomeDirective(proposal: ProposalV2): AgentActionOutcome {
            agentHomeState = agentHomeState.withRemoteDirective(proposal)
            homeStorage.write(agentHomeState.toJson().toString())
            return AgentActionOutcome(true, "Directive HOME enregistrée localement.")
        }
    }
}
