package dev.atlas.coach.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.test.BackendArgumentsAwareRunner
import dev.atlas.coach.test.RequiresBackendArguments
import dev.atlas.coach.focus.FocusStatus
import dev.atlas.coach.sync.OutboxStorage
import dev.atlas.coach.sync.agent.AgentActionType
import dev.atlas.coach.sync.agent.DirectiveProvenance
import dev.atlas.coach.sync.agent.AgentFocusCardState
import dev.atlas.coach.sync.agent.AgentFocusWindow
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.AgentMode
import dev.atlas.coach.sync.agent.AgentVerificationContext
import dev.atlas.coach.sync.agent.LocalMandateStore
import dev.atlas.coach.sync.agent.MandateVerifier
import dev.atlas.coach.sync.agent.ProposalLevel
import dev.atlas.coach.sync.agent.ProposalState
import dev.atlas.coach.sync.agent.ProposalV2
import dev.atlas.coach.sync.agent.coach.AgentStatus
import dev.atlas.coach.sync.agent.dispatch.ActionDispatcher
import dev.atlas.coach.sync.agent.dispatch.AgentEffectJournal
import dev.atlas.coach.sync.agent.dispatch.AgentEffectPhase
import dev.atlas.coach.sync.agent.dispatch.DispatchStatus
import dev.atlas.coach.sync.agent.dispatch.InMemoryAgentEffectJournal
import dev.atlas.coach.sync.agent.inbox.AgentInboxRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** Script-driven real OpenClaw -> FastAPI -> Coach/dispatcher -> persisted Android HOME flow. */
@RunWith(BackendArgumentsAwareRunner::class)
class V6RealAgentE2eInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @After
    fun clearRandomBackendCredentials() {
        val controller = (composeRule.activity.application as AtlasApplication).controller
        composeRule.runOnUiThread {
            controller.clearSyncToken()
            controller.saveSyncConfiguration("", null)
        }
    }

    @Test
    @RequiresBackendArguments(
        keys = ["backend_url", "backend_token"],
        reason = "real OpenClaw scenario requires scripts/v6-e2e-real.sh",
    )
    fun realCoachResponseCreatesDirectiveAndStartsFocus() {
        val args = InstrumentationRegistry.getArguments()
        val backendUrl = args.getString("backend_url") ?: error("backend_url argument is required")
        val atlasToken = args.getString("backend_token") ?: error("backend_token argument is required")
        require(atlasToken.isNotBlank()) { "backend_token argument is empty" }
        val controller = (composeRule.activity.application as AtlasApplication).controller

        composeRule.runOnUiThread {
            controller.agentHomeState.focusProposal
                ?.takeIf { card ->
                    card.state in setOf(
                        AgentFocusCardState.PLANNED,
                        AgentFocusCardState.RUNNING,
                        AgentFocusCardState.PAUSED,
                    )
                }
                ?.let { card -> controller.cancelAgentFocusProposal(card.proposalId) }
            controller.enableAgent()
            controller.setCapability(AtlasController.CAP_COACH, true)
            controller.setCapability(AtlasController.CAP_FOCUS, true)
            controller.setAgentMode(AgentMode.PROPOSALS_ONLY)
            val allowed = setOf("home.set_directive", "focus.propose", "focus.start")
            AgentActionType.entries.forEach { action ->
                controller.setAgentActionAllowed(action.wireValue, action.wireValue in allowed)
            }
            val mandateZone = ZoneId.of(controller.agentMandate.focusWindow.timezone)
            val mandateDate = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(mandateZone).toLocalDate()
            val executedToday = controller.agentEffectHistory.count { record ->
                record.phase == AgentEffectPhase.COMPLETE && record.state == ProposalState.EXECUTED &&
                    Instant.ofEpochMilli(record.atUtcMillis).atZone(mandateZone).toLocalDate() == mandateDate
            }
            controller.setAgentFocusWindow(
                days = (1..7).toSet(), start = "00:00", end = "23:59",
                maxMinutes = 30, maxSessionsPerDay = 4,
                // Keep the daily limit bounded while reserving room for this run on a persistent AVD.
                maxChangesPerDay = (executedToday + 5).coerceIn(20, 500),
            )
            AgentMandate.ALLOWED_SHARED_CATEGORIES.forEach { controller.setAgentSharedCategory(it, false) }
            controller.saveSyncConfiguration(backendUrl, atlasToken)
            controller.navigationState.selectTab(AtlasScreenId.COACH)
        }

        assertTrue("The local agent mandate is disabled: ${controller.agentMandate}", controller.isAgentEnabled)
        assertTrue("The random backend bearer was not configured", controller.syncTokenConfigured)
        assertEquals("The debug backend URL was not retained", backendUrl, controller.syncBaseUrl)
        val clientField = AtlasController::class.java.getDeclaredField("agentApiClient").apply { isAccessible = true }
        assertNotNull("Android did not construct its V2 HTTP client: ${controller.agentStatusMessage}", clientField.get(controller))
        waitForActionCooldown(controller, setOf("home.set_directive", "focus.propose", "focus.start"))
        val assistantCountBefore = controller.agentConversation.count { it.role.name == "ASSISTANT" }
        val proposalIdsBefore = controller.visibleAgentProposals.map { it.id }.toSet()
        composeRule.onNodeWithTag("agent_message_input").performScrollTo().performTextInput(
            "Prépare-moi une session de travail de exactement 25 minutes pour réviser. Que dois-je faire maintenant ?",
        )
        composeRule.onNodeWithText("Envoyer").assertIsEnabled().performScrollTo().performClick()
        assertTrue("The Compose send action did not append the user's message", controller.agentConversation.lastOrNull()?.role?.name == "USER")
        composeRule.waitUntil(timeoutMillis = 75_000) {
            controller.agentConversation.count { it.role.name == "ASSISTANT" } > assistantCountBefore
        }
        assertEquals(
            "Android did not report the real adapter as connected; status=${controller.agentStatusMessage}, reply=${controller.agentConversation.lastOrNull()?.text}",
            AgentStatus.CONNECTED,
            controller.agentStatus,
        )
        val response = controller.agentConversation.last { it.role.name == "ASSISTANT" }
        val proposals = controller.visibleAgentProposals.filter { it.id !in proposalIdsBefore }
        val directiveEntry = proposals.firstOrNull { it.proposal.actionType == "home.set_directive" }
            ?: error("The real coach response contained no home.set_directive proposal; types=${controller.agentInbox.map { it.proposal.actionType }}, reply=${response.text}")
        val focusEntry = proposals.firstOrNull { it.proposal.actionType == "focus.propose" }
            ?: error("The real coach response contained no focus.propose proposal; types=${controller.agentInbox.map { it.proposal.actionType }}, reply=${response.text}")
        assertEquals("connected", controller.agentStatus.wireValue)
        assertEquals(25, (focusEntry.proposal.parameters["focus_minutes"] as Number).toInt())

        val currentResponseProposalIds = proposals.mapTo(mutableSetOf()) { it.id }
        acceptPendingProposalThroughCompose(controller, directiveEntry.id, currentResponseProposalIds)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            controller.agentInbox.firstOrNull { it.id == directiveEntry.id }?.proposal?.state?.terminal == true
        }
        val appliedDirectiveEntry = controller.agentInbox.firstOrNull { it.id == directiveEntry.id }
            ?: error("The real HOME directive disappeared from the durable inbox")
        assertEquals(
            "The real HOME directive was not applied; effect=${appliedDirectiveEntry.effect}",
            ProposalState.EXECUTED,
            appliedDirectiveEntry.proposal.state,
        )
        composeRule.waitUntil(timeoutMillis = 5_000) {
            controller.agentHomeState.directive?.sourceProposalId == directiveEntry.id
        }
        val directive = requireNotNull(controller.agentHomeState.directive)
        assertTrue(directive.title.isNotBlank())
        assertTrue(directive.body.isNotBlank())

        composeRule.waitUntil(timeoutMillis = 5_000) {
            controller.agentHomeState.focusProposal?.proposalId == focusEntry.id ||
                controller.visibleAgentProposals.any { it.id == focusEntry.id }
        }
        if (controller.agentHomeState.focusProposal?.proposalId != focusEntry.id) {
            acceptPendingProposalThroughCompose(controller, focusEntry.id, currentResponseProposalIds)
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            controller.agentInbox.firstOrNull { it.id == focusEntry.id }?.proposal?.state?.terminal == true
        }
        val acceptedFocusEntry = controller.agentInbox.firstOrNull { it.id == focusEntry.id }
            ?: error("The real focus proposal disappeared from the durable inbox")
        assertEquals(
            "The real focus proposal was not applied; effect=${acceptedFocusEntry.effect}",
            ProposalState.EXECUTED,
            acceptedFocusEntry.proposal.state,
        )
        composeRule.waitUntil(timeoutMillis = 5_000) {
            controller.agentHomeState.focusProposal?.proposalId == focusEntry.id
        }
        composeRule.runOnUiThread { controller.navigationState.selectTab(AtlasScreenId.FOCUS) }
        composeRule.waitForIdle()
        if (controller.focusSession?.status == FocusStatus.FINISHED) {
            composeRule.onNodeWithText("Nouvelle session").performScrollTo().performClick()
            composeRule.waitForIdle()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("focus_pomodoro_start").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("focus_pomodoro_start").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            controller.focusSession?.status == FocusStatus.RUNNING
        }

        val session = requireNotNull(controller.focusSession)
        assertEquals(focusEntry.id, session.directiveId)
        assertTrue("Focus should use the local countdown", session.remainingSeconds in 1L..1_500L)
        val focusStartEffect = controller.agentEffectHistory.firstOrNull {
            it.actionType == "focus.start" && it.state == ProposalState.EXECUTED
        }
        assertNotNull("The Android effect journal should record focus.start", focusStartEffect)
        assertTrue("The assistant response should be visible in Coach", response.text.isNotBlank())
        android.util.Log.i(
            "ATLAS_V6_REAL",
            "agent=connected proposals=${proposals.size} home_title=${directive.title} focus_minutes=${session.workSeconds / 60} focus=RUNNING effect_journal=recorded",
        )

        // The credentials are only needed for this request. Do not leave the random test bearer on the AVD.
        controller.clearSyncToken()
        controller.saveSyncConfiguration("", null)
    }

    @Test
    @RequiresBackendArguments(
        keys = ["backend_url", "backend_token"],
        reason = "restored real Focus scenario requires scripts/v6-e2e-real.sh",
    )
    fun restoredFocusCanPauseResumeAndCancel() {
        val args = InstrumentationRegistry.getArguments()
        val controller = (composeRule.activity.application as AtlasApplication).controller
        composeRule.runOnUiThread { controller.navigationState.selectTab(AtlasScreenId.FOCUS) }
        composeRule.waitForIdle()

        val restored = requireNotNull(controller.focusSession) { "No Focus session was restored after process restart" }
        assertEquals(FocusStatus.RUNNING, restored.status)
        val directive = requireNotNull(controller.agentHomeState.directive) { "No real HOME directive was restored" }
        assertEquals(DirectiveProvenance.REMOTE_AI, directive.provenance)
        assertTrue("The restored HOME directive has no source proposal", !directive.sourceProposalId.isNullOrBlank())
        val directiveEntry = controller.agentInbox.firstOrNull { it.id == directive.sourceProposalId }
        assertEquals("The source HOME proposal was not restored", "home.set_directive", directiveEntry?.proposal?.actionType)
        assertEquals(ProposalState.EXECUTED, directiveEntry?.proposal?.state)
        val focusProposalId = requireNotNull(controller.agentHomeState.focusProposal?.proposalId)
        assertEquals(focusProposalId, restored.directiveId)
        assertTrue(controller.agentEffectHistory.any {
            it.actionType == "focus.start" && it.state == ProposalState.EXECUTED
        })
        val beforeTick = restored.remainingSeconds
        composeRule.waitUntil(timeoutMillis = 6_000) {
            (controller.focusSession?.remainingSeconds ?: beforeTick) < beforeTick
        }
        composeRule.onNodeWithTag("focus_time_remaining").assertIsDisplayed()

        composeRule.onNodeWithText("Pause").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) { controller.focusSession?.status == FocusStatus.PAUSED }
        composeRule.onNodeWithText("Reprendre").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) { controller.focusSession?.status == FocusStatus.RUNNING }
        composeRule.onNodeWithText("Annuler").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) {
            controller.agentHomeState.focusProposal?.state == AgentFocusCardState.CANCELLED &&
                controller.focusSession?.status == FocusStatus.CANCELLED
        }
        assertTrue(focusProposalId in controller.agentHomeState.cancelledFocusProposalIds)
        android.util.Log.i("ATLAS_V6_REAL", "restart=restored timer=local pause=passed resume=passed cancel=passed")
    }

    /** Separate deterministic Android dispatcher trial; the proposal is synthetic and not OpenClaw output. */
    @Test
    fun limitedMandatePreauthorizesFocusPlanOnAndroid() {
        val controller = (composeRule.activity.application as AtlasApplication).controller
        val context = composeRule.activity
        val originalRoutineCapability = controller.capabilities[AtlasController.CAP_FOCUS_ROUTINES] == true
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val mandate = AgentMandate(
            id = "v6-preauth-focus-plan",
            mode = AgentMode.PREAUTHORIZED,
            allowedActions = setOf("focus.plan"),
            focusWindow = AgentFocusWindow(
                days = (1..7).toSet(), start = "00:00", end = "23:59", timezone = zone.id,
                maxMinutesPerSession = 30, maxSessionsPerDay = 1,
            ),
            maxChangesPerDay = 1,
        )
        val startAt = ZonedDateTime.of(LocalDate.now(zone).plusDays(1), LocalTime.NOON, zone)
        val proposal = ProposalV2(
            schemaVersion = 2,
            proposalId = UUID.randomUUID().toString(),
            idempotencyKey = "v6-preauth-${UUID.randomUUID()}",
            conversationId = null,
            mandateId = mandate.id,
            mandateVersion = mandate.version,
            contextVersion = "instrumentation-synthetic",
            createdAtUtcMillis = now,
            expiresAtUtcMillis = now + 10 * 60_000L,
            actionType = "focus.plan",
            parameters = mapOf(
                "focus_minutes" to 25,
                "break_minutes" to 5,
                "cycles" to 1,
                "label" to "Essai mandat limité",
                "start_at" to Instant.ofEpochMilli(startAt.toInstant().toEpochMilli()).toString(),
                "timezone" to zone.id,
            ),
            reason = "Essai séparé d'une planification préautorisée dans les bornes.",
            requiredLevel = ProposalLevel.CONFIRM,
        )
        val inbox = AgentInboxRepository(MemoryStorage()) { now }
        assertNotNull(inbox.insertLocal(proposal))
        val journal: AgentEffectJournal = InMemoryAgentEffectJournal()
        val dispatcher = ActionDispatcher(
            mandates = LocalMandateStore(mandate),
            inbox = inbox,
            verifier = MandateVerifier(),
            context = {
                AgentVerificationContext(
                    nowUtcMillis = now,
                    capabilities = setOf("focus", "focusRoutines"),
                    focusSessionsToday = 0,
                    changesToday = 0,
                )
            },
            actions = controller,
            journal = journal,
            nowUtcMillis = { now },
        )

        composeRule.runOnUiThread {
            controller.setCapability(AtlasController.CAP_FOCUS_ROUTINES, true)
        }
        try {
            val result = dispatcher.dispatchPreauthorized(proposal.proposalId)
            assertEquals(DispatchStatus.EXECUTED, result.status)
            assertEquals(AgentFocusCardState.PLANNED, controller.agentHomeState.focusProposal?.state)
            assertTrue(journal.entries().any { it.actionType == "focus.plan" && it.state == ProposalState.EXECUTED })
            android.util.Log.i("ATLAS_V6_MOCK", "preauthorized=focus.plan mandate=limited android=applied")
        } finally {
            composeRule.runOnUiThread {
                controller.cancelAgentFocusProposal(proposal.proposalId)
                controller.setCapability(AtlasController.CAP_FOCUS_ROUTINES, originalRoutineCapability)
            }
        }
    }

    private fun acceptPendingProposalThroughCompose(
        controller: AtlasController,
        proposalId: String,
        currentResponseProposalIds: Set<String>,
    ) {
        require(controller.visibleAgentProposals.any { it.id == proposalId }) {
            "Expected proposal is not visible for user confirmation"
        }
        while (true) {
            val priority = controller.visibleAgentProposals.firstOrNull()
                ?: error("The proposal queue became empty before the requested proposal was shown")
            if (priority.id == proposalId) break
            if (priority.id in currentResponseProposalIds) {
                acceptPriorityCardThroughCompose(priority.id)
                composeRule.waitUntil(timeoutMillis = 5_000) {
                    controller.agentInbox.firstOrNull { it.id == priority.id }?.proposal?.state?.terminal == true
                }
            } else {
                // Preserve older local proposals without applying or rejecting them; the priority UI
                // exposes one card at a time, so snooze its older entry to reach this response.
                composeRule.runOnUiThread { controller.postponeAgentProposal(priority.id) }
                composeRule.waitForIdle()
                runCatching {
                    composeRule.waitUntil(timeoutMillis = 5_000) {
                        controller.visibleAgentProposals.firstOrNull()?.id != priority.id
                    }
                }.getOrElse {
                    val entry = controller.agentInbox.firstOrNull { it.id == priority.id }
                    val record = controller.agentEffectHistory.firstOrNull { it.proposalId == priority.id }
                    error(
                        "Snoozing the older priority proposal did not advance the queue; " +
                            "action=${priority.proposal.actionType}, state=${entry?.proposal?.state}, " +
                            "snoozed=${entry?.snoozedUntilUtcMillis != null}, latest=${controller.latestMessage}, " +
                            "journal=${record?.state}/${record?.phase}",
                    )
                }
            }
        }
        acceptPriorityCardThroughCompose(proposalId)
        runCatching {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                controller.agentInbox.firstOrNull { it.id == proposalId }?.proposal?.state?.terminal == true
            }
        }.getOrElse {
            val proposal = controller.agentInbox.firstOrNull { it.id == proposalId }
            val record = controller.agentEffectHistory.firstOrNull { it.proposalId == proposalId }
            error(
                "Compose did not dispatch the requested priority proposal; " +
                    "action=${proposal?.proposal?.actionType}, state=${proposal?.proposal?.state}, " +
                    "latest=${controller.latestMessage}, journal=${record?.state}/${record?.phase}",
            )
        }
    }

    private fun waitForActionCooldown(controller: AtlasController, actionTypes: Set<String>) {
        val lastSuccessfulActionAt = controller.agentEffectHistory
            .filter { it.state == ProposalState.EXECUTED && it.actionType in actionTypes }
            .maxOfOrNull { it.atUtcMillis } ?: return
        val availableAt = lastSuccessfulActionAt + MandateVerifier.ACTION_COOLDOWN_MILLIS
        val remaining = availableAt - System.currentTimeMillis()
        if (remaining > 0L) {
            composeRule.waitUntil(timeoutMillis = remaining + 1_000L) {
                System.currentTimeMillis() >= availableAt
            }
        }
    }

    private fun acceptPriorityCardThroughCompose(proposalId: String) {
        val cardTag = "agent_priority_proposal_$proposalId"
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching { composeRule.onNodeWithTag(cardTag).fetchSemanticsNode() }.isSuccess
        }
        composeRule.onNodeWithTag(cardTag).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("agent_proposal_accept").performScrollTo().performClick()
    }

    private class MemoryStorage : OutboxStorage {
        private var raw: String? = null
        override fun read(): String? = raw
        override fun write(value: String) { raw = value }
    }
}
