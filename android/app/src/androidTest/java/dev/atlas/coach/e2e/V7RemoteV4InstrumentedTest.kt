package dev.atlas.coach.e2e

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.AtlasApplication
import dev.atlas.coach.app.AtlasController
import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.PlanState
import dev.atlas.coach.sync.AndroidSyncStorage
import dev.atlas.coach.sync.agent.AgentActionType
import dev.atlas.coach.sync.agent.AgentMode
import dev.atlas.coach.sync.agent.coach.AgentMessageRole
import dev.atlas.coach.sync.agent.coach.AgentStatus
import dev.atlas.coach.sync.agent.inbox.v4.FileInboxV4Storage
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4AckState
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4ConfirmationMode
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4LocalStore
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4ProposalState
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4Repository
import dev.atlas.coach.sync.relay.RelayConnectionState
import dev.atlas.coach.sync.v4.CoreV4Category
import dev.atlas.coach.sync.v4.CoreV4ConsentState
import dev.atlas.coach.sync.v4.CoreV4SyncScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class V7RemoteV4InstrumentedTest {
    @After
    fun disconnectRelayAfterScenario() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as? AtlasApplication ?: return
        instrumentation.runOnMainSync {
            app.controller.relaySettings.disconnect()
            app.controller.relaySettings.setV4ChatEnabled(false)
        }
    }

    @Test
    fun remoteV4ProposalCanBeAppliedOrRejectedAfterExplicitTestAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as AtlasApplication
        val context = app.applicationContext
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
        val oldProposal = planController.inboxEntries.firstOrNull {
            it.proposal.idempotencyKey == idempotencyKey
        }
        assertFalse(
            "V7-05 proposal ${oldProposal?.proposal?.proposalId} is already terminal " +
                "(${oldProposal?.proposal?.state}); refusing to apply or reject it again",
            oldProposal?.proposal?.state?.terminal == true,
        )
        assertTrue(
            "existing V7-05 proposal is not in a reviewable state",
            oldProposal == null || oldProposal.proposal.state in REVIEWABLE_PROPOSAL_STATES,
        )

        val testAction = InstrumentationRegistry.getArguments().getString("test_action")?.trim()?.lowercase() ?: "apply"
        assertTrue("test_action must be apply or reject", testAction == "apply" || testAction == "reject")

        val relaySettings = controller.relaySettings
        assertTrue("persisted relay pairing is required", relaySettings.credentialsConfigured)
        assertEquals("V7-05 requires the persisted pixel-v7 pairing", "pixel-v7", relaySettings.serverId)

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

        val syncStore = AndroidSyncStorage.v4(context)
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
                dayConsent.version == statusConsent.version &&
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
        Log.i(LOG_TAG, "synthetic_message=$requestText idempotency_key=$idempotencyKey")
        var sent = false
        instrumentation.runOnMainSync { sent = controller.sendAgentMessage(requestText) }
        assertTrue(
            "synthetic V4 chat was refused; status=${controller.agentStatus} " +
                "relay=${relaySettings.state}",
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
            .last()
        assertTrue("assistant V4 response must be non-empty", assistantResponse.text.isNotBlank())
        assertTrue(
            "assistant response must include V4 tool provenance",
            assistantResponse.text.contains(TOOLS_PROVENANCE_LABEL),
        )
        assertTrue(
            "the requested day-plan read and proposal must both complete",
            assistantResponse.text.contains("État des outils : completed (2 appel(s))."),
        )
        val proposalIdFromReply = PROPOSAL_ID_PATTERN.find(assistantResponse.text)
            ?.groupValues
            ?.getOrNull(1)
        Log.i(
            LOG_TAG,
            "synthetic_assistant_reply=${assistantResponse.text.take(MAX_ASSISTANT_LOG_CHARS)} " +
                "reply_proposal_id=${proposalIdFromReply ?: "not-listed"}",
        )

        CoreV4SyncScheduler.schedule(context)
        var receivedEntry: dev.atlas.coach.sync.agent.inbox.v4.InboxV4EntryView? = null
        waitUntil(
            description = "V4 proposal inbox pull",
            timeoutMillis = PROPOSAL_TIMEOUT_MILLIS,
            diagnostic = {
                "assistant_response=${assistantResponse.text}; ${v4Diagnostic(syncStore, plan.planId, plan.version)}"
            },
        ) {
            instrumentation.runOnMainSync {
                planController.refreshInbox()
                receivedEntry = planController.inboxEntries.firstOrNull {
                    it.proposal.idempotencyKey == idempotencyKey
                }
            }
            receivedEntry != null
        }

        val inboxEntry = requireNotNull(receivedEntry) { "proposal was not received after V4 pull" }
        val proposal = inboxEntry.proposal
        assertTrue(
            "assistant reply and inbox proposal IDs must match",
            proposalIdFromReply == null || proposalIdFromReply == proposal.proposalId,
        )
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
        assertTrue(
            "proposal must shift only the synthetic focus activity by 10 minutes",
            proposal.changes.any { change ->
                change["op"] == "reschedule" &&
                    change["activity_id"] == activity.activityId &&
                    (change["target_start_minute"] as? Number)?.toInt() == activity.scheduledLocal.startMinute + 10
            },
        )
        Log.i(
            LOG_TAG,
            "proposal_id=${proposal.proposalId} state=${proposal.state} base_version=${proposal.baseVersion}",
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
        var acknowledgedEntry: dev.atlas.coach.sync.agent.inbox.v4.InboxV4EntryView? = null
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
        assertTrue("$description timed out: ${diagnostic()}", condition())
    }

    private fun v4Diagnostic(
        store: dev.atlas.coach.sync.AndroidCoreV4SyncStore,
        planId: String,
        planVersion: Int,
    ): String {
        val day = store.consent(CoreV4Category.DAY_PLAN).snapshot
        val status = store.consent(CoreV4Category.ACTIVITY_STATUS).snapshot
        return "v4_code=${store.presentation().code}; " +
            "day_freshness=${store.freshness(CoreV4Category.DAY_PLAN)}; " +
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

    private companion object {
        const val LOG_TAG = "ATLAS_V7_V4_E2E"
        const val SYNTHETIC_ACTIVITY_TITLE = "ATLAS V7 synthetic focus"
        const val V4_SYNCED_CODE = "SYNCED"
        const val TOOLS_PROVENANCE_LABEL = "État des outils"
        const val RELAY_TIMEOUT_MILLIS = 60_000L
        const val V4_SYNC_TIMEOUT_MILLIS = 60_000L
        const val CHAT_TIMEOUT_MILLIS = 90_000L
        const val PROPOSAL_TIMEOUT_MILLIS = 60_000L
        const val ACK_TIMEOUT_MILLIS = 60_000L
        const val POLL_INTERVAL_MILLIS = 250L
        const val MAX_ASSISTANT_LOG_CHARS = 1_500
        val REVIEWABLE_PROPOSAL_STATES = setOf(
            InboxV4ProposalState.PENDING_ANDROID,
            InboxV4ProposalState.RECEIVED,
            InboxV4ProposalState.AWAITING_USER,
        )
        val PROPOSAL_ID_PATTERN = Regex(
            "identifiants\\s*:\\s*([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})",
            RegexOption.IGNORE_CASE,
        )
    }
}
