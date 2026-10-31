package dev.atlas.coach.e2e

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.atlas.coach.app.AtlasApplication
import dev.atlas.coach.app.AtlasController
import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.PlanState
import dev.atlas.coach.sync.AndroidCoreV4SyncStore
import dev.atlas.coach.sync.AndroidSyncStorage
import dev.atlas.coach.sync.agent.AgentActionType
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.AgentMode
import dev.atlas.coach.sync.agent.coach.AgentMessageRole
import dev.atlas.coach.sync.agent.coach.AgentStatus
import dev.atlas.coach.sync.agent.context.AgentContextCategory
import dev.atlas.coach.sync.agent.inbox.v4.FileInboxV4Storage
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4AckState
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4ConfirmationMode
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4EntryView
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4LocalStore
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4ProposalState
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4Repository
import dev.atlas.coach.sync.relay.RelayConnectionState
import dev.atlas.coach.sync.relay.RelayTransportMode
import dev.atlas.coach.sync.v4.CoreV4Category
import dev.atlas.coach.sync.v4.CoreV4ConsentSnapshot
import dev.atlas.coach.sync.v4.CoreV4ConsentState
import dev.atlas.coach.sync.v4.CoreV4SyncScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class V7RemoteV4InstrumentedTest {
    @Test
    fun remoteV4ProposalCanBeAppliedOrRejectedAfterExplicitTestAction() {
        // Validate the external action before reading or mutating app state. There is no default action.
        val testAction = InstrumentationRegistry.getArguments()
            .getString("test_action")
            ?.trim()
            ?.lowercase(Locale.ROOT)
        require(testAction == "apply" || testAction == "reject") {
            "test_action is required and must be apply or reject"
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as AtlasApplication
        val context = app.applicationContext
        val controller = app.controller
        val syncStore = AndroidSyncStorage.v4(context)
        val initialState = captureInitialState(controller, syncStore)
        var scenarioFailure: Throwable? = null

        try {
            runRemoteV4Scenario(
                testAction = testAction,
                instrumentation = instrumentation,
                context = context,
                app = app,
                syncStore = syncStore,
            )
        } catch (failure: Throwable) {
            scenarioFailure = failure
            throw failure
        } finally {
            try {
                restoreInitialState(instrumentation, context, controller, syncStore, initialState)
            } catch (cleanupFailure: Throwable) {
                val originalFailure = scenarioFailure
                if (originalFailure == null) {
                    throw cleanupFailure
                }
                originalFailure.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun runRemoteV4Scenario(
        testAction: String,
        instrumentation: android.app.Instrumentation,
        context: Context,
        app: AtlasApplication,
        syncStore: AndroidCoreV4SyncStore,
    ) {
        val controller = app.controller
        val planController = app.planController
        instrumentation.runOnMainSync {
            planController.refresh()
            planController.refreshInbox()
        }
        val plan = planController.plan ?: error("V7-04 seeded plan is missing")
        assertEquals("seeded plan must be for today", planController.today, plan.localDate)
        assertEquals(PlanState.APPROVED, plan.state)
        assertTrue("seeded plan must be at least version 2", plan.version >= 2)
        assertEquals("only the synthetic fixture may be synced", 1, plan.activities.size)
        val activity = plan.activities.single()
        assertEquals(SYNTHETIC_ACTIVITY_TITLE, activity.title)
        assertEquals(ActivityKind.FOCUS, activity.kind)
        assertEquals(null, activity.notes)
        assertEquals(null, activity.goalId)
        assertEquals(null, activity.subGoalId)
        assertTrue("synthetic activity must have room to move by 10 minutes", activity.scheduledLocal.startMinute <= 1429)

        val idempotencyKey = stableIdempotencyKey(plan.planId, activity.activityId)
        // This snapshot is the inbox oracle for the whole chat/pull round-trip.
        val proposalIdsBefore = planController.inboxEntries.mapTo(mutableSetOf()) { it.proposal.proposalId }
        val oldProposal = planController.inboxEntries.firstOrNull {
            it.proposal.idempotencyKey == idempotencyKey
        }
        assertTrue(
            "V7-05 idempotency key already has proposal ${oldProposal?.proposal?.proposalId} " +
                "in state ${oldProposal?.proposal?.state}; refusing a repeat action",
            oldProposal == null,
        )

        val relaySettings = controller.relaySettings
        assertTrue("persisted relay pairing is required", relaySettings.credentialsConfigured)
        assertTrue("persisted relay device id is required", relaySettings.deviceId.isNotBlank())
        assertTrue(
            "relay server id must use the atlas-pc identity format",
            SERVER_ID_PATTERN.matches(relaySettings.serverId),
        )

        instrumentation.runOnMainSync {
            controller.enableAgent()
            controller.setCapability(AtlasController.CAP_AGENT, true)
            controller.setCapability(AtlasController.CAP_COACH, true)
            controller.setCapability(AtlasController.CAP_PLAN_REVISION, true)
            controller.setAgentMode(AgentMode.PROPOSALS_ONLY)
            AgentActionType.entries.forEach { action ->
                controller.setAgentActionAllowed(
                    action.wireValue,
                    action == AgentActionType.FOCUS_PROPOSE,
                )
            }
            controller.setAgentFocusWindow(
                days = (1..7).toSet(),
                start = "00:00",
                end = "23:59",
                maxMinutes = 60,
                maxSessionsPerDay = 8,
                maxChangesPerDay = 20,
            )
            relaySettings.setV4ChatEnabled(true)
            relaySettings.connect()
        }

        assertTrue(controller.capabilities[AtlasController.CAP_AGENT] == true)
        assertTrue(controller.capabilities[AtlasController.CAP_COACH] == true)
        assertTrue(controller.capabilities[AtlasController.CAP_PLAN_REVISION] == true)
        assertEquals(AgentMode.PROPOSALS_ONLY, controller.agentMandate.mode)
        assertEquals(setOf(AgentActionType.FOCUS_PROPOSE.wireValue), controller.agentMandate.allowedActions)
        assertEquals((1..7).toSet(), controller.agentMandate.focusWindow.days)
        assertEquals("00:00", controller.agentMandate.focusWindow.start)
        assertEquals("23:59", controller.agentMandate.focusWindow.end)
        assertTrue(relaySettings.v4ChatEnabled)

        waitUntil(
            description = "relay connection",
            timeoutMillis = RELAY_TIMEOUT_MILLIS,
            diagnostic = { "state=${relaySettings.state}; paired=${relaySettings.credentialsConfigured}" },
        ) { relaySettings.state == RelayConnectionState.RELAY_CONNECTED }

        syncStore.setConsent(CoreV4Category.DAY_PLAN, true)
        syncStore.setConsent(CoreV4Category.ACTIVITY_STATUS, true)
        CoreV4SyncScheduler.schedule(context)

        waitUntil(
            description = "initial V4 consent and day-plan receipt",
            timeoutMillis = V4_SYNC_TIMEOUT_MILLIS,
            diagnostic = { v4Diagnostic(syncStore, plan.planId, plan.version) },
        ) {
            val dayConsent = syncStore.consent(CoreV4Category.DAY_PLAN).snapshot
            val statusConsent = syncStore.consent(CoreV4Category.ACTIVITY_STATUS).snapshot
            val presentation = syncStore.presentation()
            dayConsent.state == CoreV4ConsentState.GRANTED &&
                dayConsent.syncedVersion == dayConsent.version &&
                statusConsent.state == CoreV4ConsentState.GRANTED &&
                statusConsent.syncedVersion == statusConsent.version &&
                presentation.code == V4_SYNCED_CODE &&
                syncStore.latestPlanReceiptVersion(plan.planId, dayConsent.version) == plan.version &&
                syncStore.freshness(CoreV4Category.DAY_PLAN).startsWith("FRESH")
        }

        val requestText = syntheticRequest(
            localDate = plan.localDate,
            planId = plan.planId,
            baseVersion = plan.version,
            activityId = activity.activityId,
            currentStartMinute = activity.scheduledLocal.startMinute,
            durationMinutes = activity.scheduledLocal.durationMinutes,
            idempotencyKey = idempotencyKey,
        )
        val assistantCountBefore = controller.agentConversation.count { it.role == AgentMessageRole.ASSISTANT }
        Log.i(LOG_TAG, "synthetic_message_chars=${requestText.length} idempotency_key=$idempotencyKey")
        var sent = false
        instrumentation.runOnMainSync { sent = controller.sendAgentMessage(requestText) }
        assertTrue(
            "synthetic V4 chat was refused; status=${controller.agentStatus}; relay=${relaySettings.state}",
            sent,
        )

        waitUntil(
            description = "connected assistant V4 response",
            timeoutMillis = CHAT_TIMEOUT_MILLIS,
            diagnostic = {
                "assistant_count=${controller.agentConversation.count { it.role == AgentMessageRole.ASSISTANT }}; " +
                    "agent_status=${controller.agentStatus}; relay=${relaySettings.state}"
            },
        ) {
            controller.agentConversation.count { it.role == AgentMessageRole.ASSISTANT } > assistantCountBefore &&
                controller.agentStatus == AgentStatus.CONNECTED
        }

        val assistantResponse = controller.agentConversation
            .filter { it.role == AgentMessageRole.ASSISTANT }
            .lastOrNull()
            ?: error("V4 assistant response is missing")
        val metadataStart = assistantResponse.text.lastIndexOf(TOOLS_PROVENANCE_LABEL)
        check(metadataStart >= 0) { "V4 tool metadata is missing from the assistant response" }
        val metadataSuffix = assistantResponse.text.substring(metadataStart).trimEnd()
        val toolMetadata = TOOL_METADATA_SUFFIX_PATTERN.matchEntire(metadataSuffix)
            ?: error("V4 tool metadata is not the generated suffix of the assistant response")
        val toolState = toolMetadata.groupValues[1]
        val toolCallCount = toolMetadata.groupValues[2].toInt()
        assertEquals("completed", toolState)
        assertTrue("V4 tool call count must include plan read and proposal", toolCallCount >= 2)
        assertEquals("V4 reply must identify exactly one proposal", 1, toolMetadata.groupValues[3].toInt())
        // V4 exposes only tool_state and tool_call_count here. Exact tool names are correlated
        // against the parent Core/OpenClaw evidence, never inferred from assistant text.
        val proposalIdFromReply = toolMetadata.groupValues[4]
        assertFalse(
            "reply proposal id was already present before the chat",
            proposalIdFromReply in proposalIdsBefore,
        )
        Log.i(
            LOG_TAG,
            "assistant_chars=${assistantResponse.text.length} tool_state=$toolState " +
                "tool_call_count=$toolCallCount reply_proposal_id=$proposalIdFromReply",
        )

        CoreV4SyncScheduler.schedule(context)
        var receivedEntry: InboxV4EntryView? = null
        waitUntil(
            description = "V4 proposal inbox pull",
            timeoutMillis = PROPOSAL_TIMEOUT_MILLIS,
            diagnostic = {
                val newEntries = planController.inboxEntries.filter {
                    it.proposal.proposalId !in proposalIdsBefore
                }
                "reply_proposal_id=$proposalIdFromReply; new_count=${newEntries.size}; " +
                    "new_ids=${newEntries.take(MAX_DIAGNOSTIC_IDS).joinToString(",") { it.proposal.proposalId }}; " +
                    "new_states=${newEntries.take(MAX_DIAGNOSTIC_IDS).joinToString(",") { it.proposal.state.name }}; " +
                    v4Diagnostic(syncStore, plan.planId, plan.version)
            },
        ) {
            instrumentation.runOnMainSync {
                planController.refreshInbox()
                receivedEntry = planController.inboxEntries.firstOrNull {
                    it.proposal.proposalId == proposalIdFromReply &&
                        it.proposal.proposalId !in proposalIdsBefore
                }
            }
            receivedEntry != null
        }

        val inboxEntry = requireNotNull(receivedEntry) {
            "reply proposal id was not received as a new V4 inbox entry"
        }
        val proposal = inboxEntry.proposal
        assertTrue("proposal must be new relative to the pre-chat inbox", proposal.proposalId !in proposalIdsBefore)
        assertEquals("reply and new inbox proposal IDs must match", proposalIdFromReply, proposal.proposalId)
        assertEquals("new proposal must retain the requested idempotency key", idempotencyKey, proposal.idempotencyKey)
        assertFalse(
            "V7-05 proposal ${proposal.proposalId} is already terminal (${proposal.state}); refusing to reapply",
            proposal.state.terminal,
        )
        assertTrue("pulled proposal is not in a reviewable state", proposal.state in REVIEWABLE_PROPOSAL_STATES)
        assertEquals(plan.planId, proposal.planId)
        assertEquals(plan.version.toLong(), proposal.baseVersion)
        assertEquals(InboxV4ConfirmationMode.A0_USER_REVIEW, proposal.confirmationMode)
        assertEquals("NONE_AT_CORE", proposal.autoApplication)
        assertTrue("proposal must have no unresolved conflicts", proposal.conflicts.isEmpty())
        assertEquals("proposal must contain exactly one change", 1, proposal.changes.size)
        val change = proposal.changes.single()
        assertEquals("reschedule", change["op"])
        assertEquals(activity.activityId, change["activity_id"])
        assertEquals(
            activity.scheduledLocal.startMinute + 10,
            (change["target_start_minute"] as? Number)?.toInt(),
        )
        Log.i(
            LOG_TAG,
            "proposal_id=${proposal.proposalId} state=${proposal.state} " +
                "base_version=${proposal.baseVersion} change_count=${proposal.changes.size}",
        )

        val currentDayPlanConsent = syncStore.consent(CoreV4Category.DAY_PLAN).snapshot
        assertEquals(CoreV4ConsentState.GRANTED, currentDayPlanConsent.state)
        assertEquals(currentDayPlanConsent.version, currentDayPlanConsent.syncedVersion)
        val versionBeforeAction = planController.plan?.version ?: error("local plan disappeared before action")
        assertEquals("proposal base version must still be current", proposal.baseVersion, versionBeforeAction.toLong())

        val expectedProposalState: InboxV4ProposalState
        val expectedAckState: InboxV4AckState
        when (testAction) {
            "apply" -> {
                var applied = false
                instrumentation.runOnMainSync {
                    applied = planController.applyInboxProposal(
                        proposalId = proposal.proposalId,
                        planRevisionCapabilityEnabled = true,
                        dayPlanConsentGranted = true,
                        dayPlanConsentVersion = currentDayPlanConsent.version.toLong(),
                    )
                    planController.refresh()
                    planController.refreshInbox()
                }
                assertTrue("explicit test apply should be accepted", applied)
                val planAfterAction = planController.plan ?: error("local plan missing after apply")
                assertEquals(versionBeforeAction + 1, planAfterAction.version)
                val movedActivity = planAfterAction.activities.single { it.activityId == activity.activityId }
                assertEquals(activity.scheduledLocal.startMinute + 10, movedActivity.scheduledLocal.startMinute)
                assertEquals("apply must preserve the synthetic activity title", activity.title, movedActivity.title)
                assertEquals(
                    "apply must preserve the synthetic activity duration",
                    activity.scheduledLocal.durationMinutes,
                    movedActivity.scheduledLocal.durationMinutes,
                )
                expectedProposalState = InboxV4ProposalState.APPLIED
                expectedAckState = InboxV4AckState.APPLIED
            }
            "reject" -> {
                var rejected = false
                instrumentation.runOnMainSync {
                    rejected = planController.rejectInboxProposal(
                        proposalId = proposal.proposalId,
                        consentVersion = currentDayPlanConsent.version.toLong(),
                    )
                    planController.refresh()
                    planController.refreshInbox()
                }
                assertTrue("explicit test reject should be accepted", rejected)
                assertEquals(versionBeforeAction, planController.plan?.version)
                expectedProposalState = InboxV4ProposalState.REJECTED
                expectedAckState = InboxV4AckState.REJECTED
            }
            else -> error("unreachable test_action=$testAction")
        }

        CoreV4SyncScheduler.schedule(context)
        var acknowledgedEntry: InboxV4EntryView? = null
        waitUntil(
            description = "V4 durable ACK delivery",
            timeoutMillis = ACK_TIMEOUT_MILLIS,
            diagnostic = {
                instrumentation.runOnMainSync { planController.refreshInbox() }
                val entry = planController.inboxEntries.firstOrNull { it.proposal.proposalId == proposal.proposalId }
                "proposal_id=${proposal.proposalId}; state=${entry?.proposal?.state}; " +
                    "ack_sequence=${entry?.lastAckSequence}; pending_acks=${planController.inboxPendingAckCount}; " +
                    v4Diagnostic(syncStore, plan.planId, plan.version)
            },
        ) {
            instrumentation.runOnMainSync {
                planController.refreshInbox()
                acknowledgedEntry = planController.inboxEntries.firstOrNull {
                    it.proposal.proposalId == proposal.proposalId
                }
            }
            planController.inboxPendingAckCount == 0 &&
                acknowledgedEntry?.proposal?.state == expectedProposalState &&
                acknowledgedEntry?.lastAckSequence?.let { it > 0L } == true
        }

        val durableRepository = InboxV4Repository(
            FileInboxV4Storage(File(File(context.filesDir, "plans"), InboxV4LocalStore.FILE_NAME)),
        )
        val durableEntry = durableRepository.get(proposal.proposalId)
        val durableAck = durableRepository.ackHistory(proposal.proposalId).singleOrNull()
        assertEquals(expectedProposalState, durableEntry?.proposal?.state)
        assertNotNull("proposal ACK must be durable", durableAck)
        assertEquals(expectedAckState, durableAck?.state)
        assertFalse(
            "delivered proposal ACK must no longer be pending",
            durableRepository.pendingAcks().any { it.ack.proposalId == proposal.proposalId },
        )
        assertEquals(0, planController.inboxPendingAckCount)
        Log.i(
            LOG_TAG,
            "ack_proposal_id=${proposal.proposalId} ack_state=${durableAck?.state} " +
                "ack_sequence=${acknowledgedEntry?.lastAckSequence} pending_acks=${planController.inboxPendingAckCount}",
        )
    }

    private fun captureInitialState(
        controller: AtlasController,
        syncStore: AndroidCoreV4SyncStore,
    ): InitialState {
        return InitialState(
            relayMode = controller.relaySettings.mode,
            v4ChatEnabled = controller.relaySettings.v4ChatEnabled,
            agentEnabled = controller.isAgentEnabled,
            capabilities = controller.capabilities.toMap(),
            mandate = controller.agentMandate,
            dayPlanConsent = syncStore.consent(CoreV4Category.DAY_PLAN).snapshot,
            activityStatusConsent = syncStore.consent(CoreV4Category.ACTIVITY_STATUS).snapshot,
        )
    }

    private fun restoreInitialState(
        instrumentation: android.app.Instrumentation,
        context: Context,
        controller: AtlasController,
        syncStore: AndroidCoreV4SyncStore,
        initial: InitialState,
    ) {
        val failures = mutableListOf<Throwable>()
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                failures += failure
            }
        }

        // Revoke and confirm any consent granted by this test while the relay can still deliver it.
        // If no grant occurred (for example, failure before setup) this does not schedule a sync.
        attempt {
            revokeTestConsentsAndConfirm(
                context = context,
                relaySettings = controller.relaySettings,
                store = syncStore,
                initial = initial,
            )
        }
        attempt {
            instrumentation.runOnMainSync {
                controller.relaySettings.disconnect()
                controller.relaySettings.setV4ChatEnabled(false)
                controller.relaySettings.selectMode(initial.relayMode)
                controller.relaySettings.setV4ChatEnabled(false)
                restoreControllerState(controller, initial)
            }
        }
        if (initial.v4ChatEnabled) {
            Log.i(LOG_TAG, "cleanup_limit=initial_v4_chat_forced_disabled")
        }
        // Some controller setters can enqueue work while restoring state, so cancel and verify again last.
        attempt { cancelCoreV4WorkAndAwaitIdle(context) }

        if (failures.isNotEmpty()) {
            val summary = failures.joinToString(",") { it.javaClass.simpleName }.take(MAX_CLEANUP_DIAGNOSTIC_CHARS)
            val cleanupFailure = AssertionError("V7 cleanup failed (${failures.size} steps): $summary")
            failures.forEach(cleanupFailure::addSuppressed)
            throw cleanupFailure
        }
    }

    private fun restoreControllerState(controller: AtlasController, initial: InitialState) {
        val initialMandate = initial.mandate
        val initialAgentCapability = initial.capabilities[AtlasController.CAP_AGENT] == true
        controller.enableAgent()

        AgentActionType.entries.forEach { action ->
            val wasAllowed = action.wireValue in initialMandate.allowedActions
            if ((action.wireValue in controller.agentMandate.allowedActions) != wasAllowed) {
                controller.setAgentActionAllowed(action.wireValue, wasAllowed)
            }
        }
        val categoriesToRestore = AgentContextCategory.wireValueSet +
            initialMandate.sharedDataCategories + controller.agentMandate.sharedDataCategories
        categoriesToRestore.forEach { category ->
            val wasAllowed = category in initialMandate.sharedDataCategories
            if ((category in controller.agentMandate.sharedDataCategories) != wasAllowed) {
                controller.setAgentSharedCategory(category, wasAllowed)
            }
        }
        if (controller.agentMandate.mode != initialMandate.mode) {
            controller.setAgentMode(initialMandate.mode)
        }
        val focusWindow = initialMandate.focusWindow
        if (controller.agentMandate.focusWindow != focusWindow ||
            controller.agentMandate.maxChangesPerDay != initialMandate.maxChangesPerDay
        ) {
            controller.setAgentFocusWindow(
                days = focusWindow.days,
                start = focusWindow.start,
                end = focusWindow.end,
                maxMinutes = focusWindow.maxMinutesPerSession,
                maxSessionsPerDay = focusWindow.maxSessionsPerDay,
                maxChangesPerDay = initialMandate.maxChangesPerDay,
            )
        }

        controller.setCapability(
            AtlasController.CAP_COACH,
            initial.capabilities[AtlasController.CAP_COACH] == true,
        )
        controller.setCapability(
            AtlasController.CAP_PLAN_REVISION,
            initial.capabilities[AtlasController.CAP_PLAN_REVISION] == true,
        )
        if (initialAgentCapability) {
            controller.enableAgent()
        } else {
            // The available disable API also revokes the mandate; no API restores an
            // unrevoked mandate while keeping CAP_AGENT disabled. This is restrictive.
            controller.disableAgent()
            if (!initialMandate.revoked) {
                Log.i(LOG_TAG, "cleanup_limit=disabled_agent_restores_as_revoked")
            }
        }
        if (initialMandate.revoked) controller.revokeAgentMandate()
        if (initialMandate.suspended) controller.suspendAgentMandate()

        assertEquals("relay mode must be restored", initial.relayMode, controller.relaySettings.mode)
        assertFalse("V4 chat opt-in must be disabled after cleanup", controller.relaySettings.v4ChatEnabled)
        assertEquals("effective agent state must be restored", initial.agentEnabled, controller.isAgentEnabled)
        assertEquals(
            "agent capability must be restored",
            initial.capabilities[AtlasController.CAP_AGENT] == true,
            controller.capabilities[AtlasController.CAP_AGENT] == true,
        )
        assertEquals(
            "coach capability must be restored",
            initial.capabilities[AtlasController.CAP_COACH] == true,
            controller.capabilities[AtlasController.CAP_COACH] == true,
        )
        assertEquals(
            "plan revision capability must be restored",
            initial.capabilities[AtlasController.CAP_PLAN_REVISION] == true,
            controller.capabilities[AtlasController.CAP_PLAN_REVISION] == true,
        )
        assertEquals(initialMandate.mode, controller.agentMandate.mode)
        assertEquals(initialMandate.allowedActions, controller.agentMandate.allowedActions)
        assertEquals(initialMandate.focusWindow, controller.agentMandate.focusWindow)
        assertEquals(initialMandate.maxChangesPerDay, controller.agentMandate.maxChangesPerDay)
        assertEquals(initialMandate.sharedDataCategories, controller.agentMandate.sharedDataCategories)
        // disableAgent() is the only public way to restore CAP_AGENT=false and it revokes
        // an otherwise unrevoked mandate. Report that documented restrictive fallback.
        val expectedRevoked = initialMandate.revoked || !initialAgentCapability
        assertEquals(expectedRevoked, controller.agentMandate.revoked)
        assertEquals(initialMandate.suspended, controller.agentMandate.suspended)
    }

    private fun revokeTestConsentsAndConfirm(
        context: Context,
        relaySettings: dev.atlas.coach.app.RelaySettingsController,
        store: AndroidCoreV4SyncStore,
        initial: InitialState,
    ) {
        val initiallyGranted = setOfNotNull(
            CoreV4Category.DAY_PLAN.takeIf { initial.dayPlanConsent.state == CoreV4ConsentState.GRANTED },
            CoreV4Category.ACTIVITY_STATUS.takeIf { initial.activityStatusConsent.state == CoreV4ConsentState.GRANTED },
        )
        val consentRevocations = mutableMapOf<CoreV4Category, CoreV4ConsentSnapshot>()
        listOf(CoreV4Category.DAY_PLAN, CoreV4Category.ACTIVITY_STATUS).forEach { category ->
            val beforeCleanup = store.consent(category).snapshot
            if (category in initiallyGranted) {
                if (beforeCleanup.state != CoreV4ConsentState.GRANTED) store.setConsent(category, true)
            } else if (beforeCleanup.state == CoreV4ConsentState.GRANTED) {
                // UNKNOWN -> REVOKED is the only available cleanup state; consent versions are monotonic.
                consentRevocations[category] = store.setConsent(category, false).snapshot
            }
        }
        if (consentRevocations.isEmpty()) return

        check(relaySettings.state == RelayConnectionState.RELAY_CONNECTED) {
            "V4 consent revocation not confirmed: relay is disconnected; categories=${consentRevocations.keys.joinToString(",") { it.name }}"
        }
        CoreV4SyncScheduler.schedule(context)
        waitUntil(
            description = "V4 consent revocation receipts",
            timeoutMillis = CONSENT_REVOCATION_TIMEOUT_MILLIS,
            diagnostic = {
                "relay=${relaySettings.state}; " + consentRevocations.keys.joinToString("; ") { category ->
                    val consent = store.consent(category).snapshot
                    "${category.name}=${consent.state}:${consent.syncedVersion}/${consent.version}"
                }
            },
        ) {
            consentRevocations.all { (category, revokedSnapshot) ->
                val consent = store.consent(category).snapshot
                consent.state == CoreV4ConsentState.REVOKED &&
                    consent.version == revokedSnapshot.version &&
                    consent.syncedVersion == consent.version
            }
        }
    }

    private fun cancelCoreV4WorkAndAwaitIdle(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val uniqueNames = listOf(CoreV4SyncScheduler.IMMEDIATE_WORK, CoreV4SyncScheduler.PERIODIC_WORK)
        uniqueNames.forEach { name ->
            workManager.cancelUniqueWork(name).result.get(WORK_OPERATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }
        val deadline = SystemClock.uptimeMillis() + WORK_IDLE_TIMEOUT_MILLIS
        while (true) {
            val workInfos = uniqueNames.flatMap { name ->
                workManager.getWorkInfosForUniqueWork(name)
                    .get(WORK_OPERATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            }
            val active = workInfos.filter {
                it.state == WorkInfo.State.ENQUEUED ||
                    it.state == WorkInfo.State.RUNNING ||
                    it.state == WorkInfo.State.BLOCKED
            }
            if (active.isEmpty()) return
            check(SystemClock.uptimeMillis() < deadline) {
                "Core V4 workers did not stop: active_count=${active.size}; states=${active.take(MAX_DIAGNOSTIC_IDS).joinToString(",") { it.state.name }}"
            }
            uniqueNames.forEach { name ->
                workManager.cancelUniqueWork(name).result.get(WORK_OPERATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
    }

    private fun waitUntil(
        description: String,
        timeoutMillis: Long,
        diagnostic: () -> String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        assertTrue("$description timed out: ${diagnostic().take(MAX_TIMEOUT_DIAGNOSTIC_CHARS)}", condition())
    }

    private fun v4Diagnostic(
        store: AndroidCoreV4SyncStore,
        planId: String,
        planVersion: Int,
    ): String {
        val day = store.consent(CoreV4Category.DAY_PLAN).snapshot
        val status = store.consent(CoreV4Category.ACTIVITY_STATUS).snapshot
        val freshness = store.freshness(CoreV4Category.DAY_PLAN).substringBefore(':').take(MAX_STATE_CHARS)
        return "v4_code=${store.presentation().code}; day_freshness=$freshness; " +
            "day_consent=${day.state}:${day.syncedVersion}/${day.version}; " +
            "status_consent=${status.state}:${status.syncedVersion}/${status.version}; " +
            "plan_receipt=${store.latestPlanReceiptVersion(planId, day.version)}; plan_version=$planVersion"
    }

    private fun stableIdempotencyKey(planId: String, activityId: String): String = UUID.nameUUIDFromBytes(
        "atlas-v7-05:$planId:$activityId".toByteArray(StandardCharsets.UTF_8),
    ).toString()

    private fun syntheticRequest(
        localDate: String,
        planId: String,
        baseVersion: Int,
        activityId: String,
        currentStartMinute: Int,
        durationMinutes: Int,
        idempotencyKey: String,
    ): String = "Test ATLAS V7-05 synthétique. Appelle d'abord atlas_get_day_plan " +
        "pour local_date=$localDate. Vérifie que le résultat contient plan_id=$planId " +
        "et version=$baseVersion, puis appelle atlas_propose_plan_revision exactement une fois " +
        "pour cette activité synthétique activity_id=$activityId : op=reschedule, " +
        "target_start_minute=${currentStartMinute + 10}, duration_minutes=$durationMinutes " +
        "(inchangée), justification='Décalage synthétique V7-05 de 10 minutes'. " +
        "Utilise idempotency_key=$idempotencyKey. Le serveur crée le proposal_id; " +
        "ne l'invente pas. N'appelle aucun autre outil et n'effectue aucune application."

    private data class InitialState(
        val relayMode: RelayTransportMode,
        val v4ChatEnabled: Boolean,
        val agentEnabled: Boolean,
        val capabilities: Map<String, Boolean>,
        val mandate: AgentMandate,
        val dayPlanConsent: CoreV4ConsentSnapshot,
        val activityStatusConsent: CoreV4ConsentSnapshot,
    )

    private companion object {
        const val LOG_TAG = "ATLAS_V7_V4_E2E"
        const val SYNTHETIC_ACTIVITY_TITLE = "ATLAS V7 synthetic focus"
        const val V4_SYNCED_CODE = "SYNCED"
        const val RELAY_TIMEOUT_MILLIS = 60_000L
        const val V4_SYNC_TIMEOUT_MILLIS = 60_000L
        const val CHAT_TIMEOUT_MILLIS = 90_000L
        const val PROPOSAL_TIMEOUT_MILLIS = 60_000L
        const val ACK_TIMEOUT_MILLIS = 60_000L
        const val CONSENT_REVOCATION_TIMEOUT_MILLIS = 60_000L
        const val WORK_IDLE_TIMEOUT_MILLIS = 30_000L
        const val WORK_OPERATION_TIMEOUT_MILLIS = 20_000L
        const val POLL_INTERVAL_MILLIS = 250L
        const val MAX_DIAGNOSTIC_IDS = 5
        const val MAX_STATE_CHARS = 24
        const val MAX_TIMEOUT_DIAGNOSTIC_CHARS = 1_200
        const val MAX_CLEANUP_DIAGNOSTIC_CHARS = 400
        val SERVER_ID_PATTERN = Regex("^atlas-pc-[A-Za-z0-9._-]+$")
        const val TOOLS_PROVENANCE_LABEL = "État des outils"
        val TOOL_METADATA_SUFFIX_PATTERN = Regex(
            "État des outils\\s*:\\s*([a-z_]+)\\s*\\((\\d+) appel\\(s\\)\\)\\.\\s*" +
                "Proposition\\(s\\)\\s*\\((\\d+)\\)\\s*—\\s*identifiants\\s*:\\s*" +
                "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.\\s*" +
                "Contenu à synchroniser via la boîte de réception\\.",
            RegexOption.IGNORE_CASE,
        )
        val REVIEWABLE_PROPOSAL_STATES = setOf(
            InboxV4ProposalState.PENDING_ANDROID,
            InboxV4ProposalState.RECEIVED,
            InboxV4ProposalState.AWAITING_USER,
        )
    }
}
