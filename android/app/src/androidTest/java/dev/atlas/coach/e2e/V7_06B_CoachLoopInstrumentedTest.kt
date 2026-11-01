package dev.atlas.coach.e2e

import android.app.Instrumentation
import android.os.SystemClock
import android.system.Os
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.atlas.coach.app.AtlasApplication
import dev.atlas.coach.app.AtlasController
import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.DayPlan
import dev.atlas.coach.plan.PlanActivity
import dev.atlas.coach.plan.PlanState
import dev.atlas.coach.plan.Priority
import dev.atlas.coach.sync.AndroidCoreV4SyncStore
import dev.atlas.coach.sync.AndroidSyncStorage
import dev.atlas.coach.sync.agent.AgentActionType
import dev.atlas.coach.sync.agent.AgentMode
import dev.atlas.coach.sync.agent.coach.AgentMessageRole
import dev.atlas.coach.sync.agent.coach.AgentStatus
import dev.atlas.coach.sync.agent.inbox.v4.FileInboxV4Storage
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4AckState
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4ConfirmationMode
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4DeliveryResult
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4EntryView
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4HttpTransport
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4LocalStore
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4ProposalState
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4Repository
import dev.atlas.coach.sync.relay.RelayConnectionState
import dev.atlas.coach.sync.v4.CoreV4Category
import dev.atlas.coach.sync.v4.CoreV4ConsentSnapshot
import dev.atlas.coach.sync.v4.CoreV4ConsentState
import dev.atlas.coach.sync.v4.CoreV4Diagnostics
import dev.atlas.coach.sync.v4.CoreV4SyncScheduler
import dev.atlas.coach.sync.v4.RelayCoreV4HttpTransport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class V7_06B_CoachLoopInstrumentedTest {
    @Test
    fun coachLoopAppliesOrRejectsOneFreshProposal() {
        val arguments = InstrumentationRegistry.getArguments()
        val testAction = arguments.getString(ARG_TEST_ACTION)?.trim()?.lowercase(Locale.ROOT)
        require(testAction == ACTION_APPLY || testAction == ACTION_REJECT) {
            "test_action is required and must be apply or reject"
        }
        val relayUrl = requiredArgument(arguments, ARG_RELAY_URL)
        val serverId = requiredArgument(arguments, ARG_SERVER_ID)
        val serverPublicKey = requiredArgument(arguments, ARG_SERVER_PUBLIC_KEY)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as AtlasApplication
        val context = app.applicationContext
        val packageName = context.packageName
        assertTrue(
            "V7-06B requires a disposable suffixed QA package; refusing $packageName",
            QA_PACKAGE_PATTERN.matches(packageName) && packageName != PRODUCT_PACKAGE,
        )

        val controller = app.controller
        val planController = app.planController
        val relaySettings = controller.relaySettings
        val syncStore = AndroidSyncStorage.v4(context)
        val plansDirectory = File(context.filesDir, PLANS_DIRECTORY)
        val pairingFile = File(context.filesDir, PAIRING_FILE_NAME)
        var scenarioFailure: Throwable? = null

        try {
            assertTrue("relay_url is required", relayUrl.isNotBlank())
            assertTrue("server_id is required", serverId.isNotBlank())
            assertTrue("server_public_key is required", serverPublicKey.isNotBlank())

            assertTrue("qa package must not have existing app data", !relaySettings.credentialsConfigured)
            assertTrue("qa package must not contain previous agent conversation", controller.agentConversation.isEmpty())
            val existingPlans = listPlanFiles(plansDirectory)
            assertTrue(
                "qa package is not fresh: existing plan files=${existingPlans.map { it.name }}",
                existingPlans.isEmpty(),
            )
            assertFalse(
                "qa package is not fresh: persisted inbox file already exists",
                File(plansDirectory, InboxV4LocalStore.FILE_NAME).exists(),
            )
            assertTrue("private synthetic pairing file is required", pairingFile.isFile)
            assertEquals("pairing file must be private (0600)", PRIVATE_FILE_MODE, Os.stat(pairingFile.absolutePath).st_mode and FILE_MODE_MASK)

            val initialDayConsent = syncStore.consent(CoreV4Category.DAY_PLAN).snapshot
            val initialStatusConsent = syncStore.consent(CoreV4Category.ACTIVITY_STATUS).snapshot
            assertFreshUnknownConsent("DAY_PLAN", initialDayConsent)
            assertFreshUnknownConsent("ACTIVITY_STATUS", initialStatusConsent)

            val pairing = runCatching { JSONObject(pairingFile.readText(Charsets.UTF_8)) }
                .getOrElse { error("private synthetic pairing file is not valid JSON") }
            val deviceId = pairing.optString("device_id").trim()
            val deviceToken = pairing.optString("token").trim()
            require(deviceId.isNotEmpty() && deviceToken.isNotEmpty()) {
                "private synthetic pairing must contain device_id and token"
            }

            val saved = relaySettings.saveConfiguration(
                relayUrl = relayUrl,
                serverId = serverId,
                serverPublicKey = serverPublicKey,
                deviceId = deviceId,
                deviceToken = deviceToken,
            )
            assertTrue("synthetic relay pairing must persist", saved.isSuccess)
            assertTrue("persisted Keystore credential must be available", relaySettings.credentialsConfigured)
            assertTrue("temporary pairing file must be deleted after Keystore import", pairingFile.delete())
            Log.i(LOG_TAG, "step=01 result=PASS pairing_imported=true pairing_file_deleted=true token_logged=false")

            val plan = createFreshApprovedPlan(instrumentation, planController, plansDirectory)
            val activity = plan.activities.single()
            assertPlanFixture(plan, activity, planController.today)
            Log.i(
                LOG_TAG,
                "step=02 result=PASS plan_id=${plan.planId} plan_date=${plan.localDate} " +
                    "plan_version=${plan.version} activity_id=${activity.activityId} start_minute=${activity.scheduledLocal.startMinute}",
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
            assertTrue(relaySettings.v4ChatEnabled)
            waitUntil(
                description = "authenticated relay connection",
                timeoutMillis = RELAY_TIMEOUT_MILLIS,
                diagnostic = { "relay=${relaySettings.state}; configured=${relaySettings.credentialsConfigured}" },
            ) { relaySettings.state == RelayConnectionState.RELAY_CONNECTED }
            Log.i(LOG_TAG, "step=03 result=PASS relay_connected=true coach_capabilities=enabled mandate=PROPOSALS_ONLY")

            CoreV4Diagnostics.clear()
            CoreV4Diagnostics.enableForDiagnostics()
            val dayPlanGranted = syncStore.setConsent(CoreV4Category.DAY_PLAN, granted = true).snapshot
            val statusGranted = syncStore.setConsent(CoreV4Category.ACTIVITY_STATUS, granted = true).snapshot
            assertEquals(CoreV4ConsentState.GRANTED, dayPlanGranted.state)
            assertEquals(CoreV4ConsentState.GRANTED, statusGranted.state)
            assertEquals(initialDayConsent.version + 1, dayPlanGranted.version)
            assertEquals(initialStatusConsent.version + 1, statusGranted.version)
            CoreV4SyncScheduler.schedule(context)

            waitUntil(
                description = "grant acknowledgements and version 2 plan receipt",
                timeoutMillis = SYNC_TIMEOUT_MILLIS,
                diagnostic = { syncDiagnostic(syncStore, plan.planId) },
            ) {
                val day = syncStore.consent(CoreV4Category.DAY_PLAN).snapshot
                val status = syncStore.consent(CoreV4Category.ACTIVITY_STATUS).snapshot
                day.state == CoreV4ConsentState.GRANTED && day.syncedVersion == day.version &&
                    status.state == CoreV4ConsentState.GRANTED && status.syncedVersion == status.version &&
                    syncStore.latestPlanReceiptVersion(plan.planId, dayPlanGranted.version) == PLAN_VERSION_APPROVED &&
                    syncStore.freshness(CoreV4Category.DAY_PLAN).startsWith(FRESH_PREFIX) &&
                    syncStore.presentation().code == SYNCED_CODE
            }
            assertEquals(SYNCED_CODE, syncStore.presentation().code)
            Log.i(
                LOG_TAG,
                "step=04 result=PASS day_consent=${consentDiagnostic(syncStore.consent(CoreV4Category.DAY_PLAN).snapshot)} " +
                    "status_consent=${consentDiagnostic(syncStore.consent(CoreV4Category.ACTIVITY_STATUS).snapshot)} " +
                    "plan_receipt_version=${syncStore.latestPlanReceiptVersion(plan.planId, dayPlanGranted.version)} " +
                    "freshness=FRESH presentation=${syncStore.presentation().code}",
            )

            val runId = UUID.randomUUID().toString()
            val proposalIdsBefore = planController.inboxEntries.mapTo(mutableSetOf()) { it.proposal.proposalId }
            assertTrue("fresh qa inbox must have no previous proposal IDs", proposalIdsBefore.isEmpty())
            assertTrue("fresh qa package must have no previous chat messages", controller.agentConversation.isEmpty())
            val message = V7_06BChatTestSupport.syntheticCoachMessage(
                plan.localDate,
                plan.planId,
                PLAN_VERSION_APPROVED,
                activity.activityId,
                activity.scheduledLocal.startMinute + RESCHEDULE_MINUTES,
                activity.scheduledLocal.durationMinutes,
                runId,
            )
            assertTrue("controlled chat message must fit the V4 request limit", message.length <= MAX_CHAT_MESSAGE_LENGTH)
            val assistantCountBefore = controller.agentConversation.count { it.role == AgentMessageRole.ASSISTANT }
            var sent = false
            instrumentation.runOnMainSync { sent = controller.sendAgentMessage(message) }
            assertTrue(
                "V4 coach chat send was refused; status=${controller.agentStatus}; relay=${relaySettings.state}",
                sent,
            )
            waitUntil(
                description = "connected V4 assistant response",
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
            val toolMetadata = V7_06BChatTestSupport.parseToolMetadata(assistantResponse.text)
            assertEquals("completed", toolMetadata.toolState)
            assertTrue("V4 response must contain the plan read and proposal tool calls", toolMetadata.toolCallCount >= 2)
            assertEquals("V4 response must return exactly one proposal ID", 1, toolMetadata.proposalIds.size)
            val proposalIdFromReply = toolMetadata.proposalIds.single()
            assertFalse("assistant reply must not reuse a previous proposal ID", proposalIdFromReply in proposalIdsBefore)
            Log.i(
                LOG_TAG,
                "step=05 result=PASS run_id=$runId " +
                    "agent_status=${controller.agentStatus} tool_state=${toolMetadata.toolState} " +
                    "tool_call_count=${toolMetadata.toolCallCount} reply_proposal_id=$proposalIdFromReply",
            )

            CoreV4SyncScheduler.schedule(context)
            var receivedEntry: InboxV4EntryView? = null
            waitUntil(
                description = "V4 proposal pull into local review inbox",
                timeoutMillis = PROPOSAL_TIMEOUT_MILLIS,
                diagnostic = {
                    val currentEntries = planController.inboxEntries
                    "reply_proposal_id=$proposalIdFromReply; inbox_count=${currentEntries.size}; " +
                        "inbox_states=${currentEntries.take(MAX_DIAGNOSTIC_IDS).joinToString(",") { it.proposal.state.name }}"
                },
            ) {
                instrumentation.runOnMainSync {
                    planController.refreshInbox()
                    receivedEntry = planController.inboxEntries.firstOrNull {
                        it.proposal.proposalId == proposalIdFromReply
                    }
                }
                receivedEntry != null
            }
            val inboxEntry = requireNotNull(receivedEntry) { "assistant proposal ID was not pulled into the local inbox" }
            assertEquals("fresh inbox must contain exactly one proposal", 1, planController.inboxEntries.size)
            assertEquals(proposalIdFromReply, inboxEntry.proposal.proposalId)
            // Core owns the stored idempotency key and derives it from the chat request, device and canonical body.
            val coreIdempotencyKey = inboxEntry.proposal.idempotencyKey
            assertTrue("Core proposal idempotency key must not be empty", coreIdempotencyKey.isNotBlank())
            assertTrue("Core proposal idempotency key must be a canonical UUID", isCanonicalUuid(coreIdempotencyKey))
            // The adapter JVM test verifies backend key injection against the legacy sentinel.
            assertEquals(plan.planId, inboxEntry.proposal.planId)
            assertEquals(PLAN_VERSION_APPROVED.toLong(), inboxEntry.proposal.baseVersion)
            assertEquals(InboxV4ConfirmationMode.A0_USER_REVIEW, inboxEntry.proposal.confirmationMode)
            assertEquals(NONE_AT_CORE, inboxEntry.proposal.autoApplication)
            assertTrue("proposal must remain in a reviewable state", !inboxEntry.proposal.state.terminal)
            assertTrue("proposal must have no conflicts", inboxEntry.proposal.conflicts.isEmpty())
            assertEquals("proposal must contain one reschedule", 1, inboxEntry.proposal.changes.size)
            val change = inboxEntry.proposal.changes.single()
            assertEquals("reschedule", change[CHANGE_OP])
            assertEquals(activity.activityId, change[CHANGE_ACTIVITY_ID])
            assertEquals(
                activity.scheduledLocal.startMinute + RESCHEDULE_MINUTES,
                (change[CHANGE_TARGET_START] as? Number)?.toInt(),
            )
            if (change.containsKey(CHANGE_DURATION)) {
                assertEquals(
                    activity.scheduledLocal.durationMinutes,
                    (change[CHANGE_DURATION] as? Number)?.toInt(),
                )
            }
            assertEquals("proposal diff must describe the single reschedule", 1, inboxEntry.proposal.diff.size)
            val diff = inboxEntry.proposal.diff.single()
            assertEquals("reschedule", diff[CHANGE_OP])
            assertEquals(activity.activityId, diff[CHANGE_ACTIVITY_ID])
            val after = diff[CHANGE_AFTER] as? Map<*, *> ?: error("proposal diff after must be an object")
            assertEquals(
                "proposal diff must retain the original activity duration",
                activity.scheduledLocal.durationMinutes,
                (after[CHANGE_DURATION] as? Number)?.toInt(),
            )
            Log.i(
                LOG_TAG,
                "step=06 result=PASS proposal_id=${inboxEntry.proposal.proposalId} " +
                    "state=${inboxEntry.proposal.state} confirmation=A0_USER_REVIEW auto_application=$NONE_AT_CORE " +
                "base_version=${inboxEntry.proposal.baseVersion} changes=1 conflicts=0 " +
                    "core_idempotency_key=$coreIdempotencyKey",
            )

            val currentDayConsent = syncStore.consent(CoreV4Category.DAY_PLAN).snapshot
            assertEquals(CoreV4ConsentState.GRANTED, currentDayConsent.state)
            assertEquals(currentDayConsent.version, currentDayConsent.syncedVersion)
            assertEquals(PLAN_VERSION_APPROVED, planController.plan?.version)
            if (testAction == ACTION_APPLY) {
                var applied = false
                instrumentation.runOnMainSync {
                    applied = planController.applyInboxProposal(
                        proposalId = inboxEntry.proposal.proposalId,
                        planRevisionCapabilityEnabled = controller.capabilities[AtlasController.CAP_PLAN_REVISION] == true,
                        dayPlanConsentGranted = currentDayConsent.state == CoreV4ConsentState.GRANTED,
                        dayPlanConsentVersion = currentDayConsent.version.toLong(),
                        currentDayPlanConsent = {
                            val current = syncStore.consent(CoreV4Category.DAY_PLAN).snapshot
                            (current.state == CoreV4ConsentState.GRANTED) to current.version.toLong()
                        },
                    )
                    planController.refresh()
                    planController.refreshInbox()
                }
                assertTrue("PlanScreen apply action must be accepted", applied)
                val appliedPlan = requireNotNull(planController.plan) { "local plan missing after apply" }
                assertEquals(PLAN_VERSION_APPLIED, appliedPlan.version)
                assertSingleRescheduledActivity(appliedPlan, activity, RESCHEDULE_MINUTES)
                Log.i(
                    LOG_TAG,
                    "step=07 result=PASS action=APPLY plan_version=${appliedPlan.version} " +
                        "start_minute=${appliedPlan.activities.single().scheduledLocal.startMinute}",
                )
            } else {
                var rejected = false
                instrumentation.runOnMainSync {
                    rejected = planController.rejectInboxProposal(
                        proposalId = inboxEntry.proposal.proposalId,
                        consentVersion = currentDayConsent.version.toLong(),
                    )
                    planController.refresh()
                    planController.refreshInbox()
                }
                assertTrue("PlanScreen reject action must be accepted", rejected)
                assertEquals(PLAN_VERSION_APPROVED, planController.plan?.version)
                assertEquals(plan, planController.plan)
                Log.i(LOG_TAG, "step=07 result=PASS action=REJECT plan_version=${planController.plan?.version} applied=false")
            }

            val finalPlanVersion = if (testAction == ACTION_APPLY) PLAN_VERSION_APPLIED else PLAN_VERSION_APPROVED
            CoreV4SyncScheduler.schedule(context)
            waitUntil(
                description = "durable terminal proposal ACK and final plan receipt",
                timeoutMillis = ACK_TIMEOUT_MILLIS,
                diagnostic = {
                    val diskRepository = durableInboxRepository(context)
                    val durableEntry = diskRepository.get(proposalIdFromReply)
                    val ack = diskRepository.ackHistory(proposalIdFromReply).singleOrNull()
                    "proposal_state=${durableEntry?.proposal?.state}; ack_state=${ack?.state}; " +
                        "pending_acks=${diskRepository.pendingAcks().size}; " + syncDiagnostic(syncStore, plan.planId)
                },
            ) {
                instrumentation.runOnMainSync { planController.refreshInbox() }
                val diskRepository = durableInboxRepository(context)
                diskRepository.pendingAcks().isEmpty() &&
                    diskRepository.get(proposalIdFromReply)?.proposal?.state == expectedProposalState(testAction) &&
                    diskRepository.ackHistory(proposalIdFromReply).size == 1 &&
                    diskRepository.ackHistory().size == 1 &&
                    syncStore.latestPlanReceiptVersion(plan.planId, dayPlanGranted.version) == finalPlanVersion &&
                    syncStore.freshness(CoreV4Category.DAY_PLAN).startsWith(FRESH_PREFIX)
            }
            val durableRepository = durableInboxRepository(context)
            val durableEntry = requireNotNull(durableRepository.get(proposalIdFromReply))
            val durableAck = durableRepository.ackHistory(proposalIdFromReply).singleOrNull()
                ?: error("proposal must have exactly one durable ACK")
            assertEquals(expectedProposalState(testAction), durableEntry.proposal.state)
            assertTrue("terminal proposal state must be terminal", durableEntry.proposal.state.terminal)
            assertEquals(expectedAckState(testAction), durableAck.state)
            assertEquals("fresh QA inbox must contain exactly one durable ACK", 1, durableRepository.ackHistory().size)
            assertEquals("ACK sequence must match the inbox entry", durableEntry.lastAckSequence, durableAck.sequence)
            assertTrue("ACK sequence must be positive", durableAck.sequence > 0L)
            assertEquals(0, durableRepository.pendingAcks().size)
            assertEquals(0, planController.inboxPendingAckCount)
            if (testAction == ACTION_APPLY) {
                val proof = requireNotNull(durableAck.proof) { "APPLIED ACK must carry durable application proof" }
                assertEquals(plan.planId, proof.planId)
                assertEquals(PLAN_VERSION_APPLIED.toLong(), proof.appliedPlanVersion)
                assertTrue("revision_id must be a canonical UUID", isCanonicalUuid(proof.revisionId))
                assertTrue("scope_hash must be a SHA-256 digest", SHA256_PATTERN.matches(proof.scopeHash))
                assertTrue("effect_ids must be non-empty", proof.effectIds.isNotEmpty())
                assertEquals(proof, durableEntry.applicationProof)
            } else {
                assertNull("REJECTED ACK must not carry application proof", durableAck.proof)
                assertNull("rejected proposal must not expose application proof", durableEntry.applicationProof)
                assertEquals(PLAN_VERSION_APPROVED, planController.plan?.version)
            }
            Log.i(
                LOG_TAG,
                "step=08 result=PASS ack_id=${durableAck.ackId} ack_sequence=${durableAck.sequence} " +
                    "ack_state=${durableAck.state} proof=${durableAck.proof != null} pending_acks=0",
            )
            Log.i(
                LOG_TAG,
                "step=09 result=PASS final_plan_version=$finalPlanVersion " +
                    "plan_receipt_version=${syncStore.latestPlanReceiptVersion(plan.planId, dayPlanGranted.version)} freshness=FRESH",
            )

            val secondPullBaseline = CoreV4Diagnostics.snapshot().maxOfOrNull { it.workerOrdinal } ?: 0L
            val proposalIdsBeforeSecondPull = durableRepository.all().mapTo(mutableSetOf()) { it.proposal.proposalId }
            val ackHistoryBeforeSecondPull = durableRepository.ackHistory().toList()
            val planBeforeSecondPull = planController.plan ?: error("plan missing before second inbox pull")
            CoreV4SyncScheduler.schedule(context)
            waitForPassAfter(
                baselineOrdinal = secondPullBaseline,
                timeoutMillis = SECOND_PULL_TIMEOUT_MILLIS,
                context = "second idempotent pull",
                diagnostic = { syncDiagnostic(syncStore, plan.planId) },
            )
            instrumentation.runOnMainSync {
                planController.refresh()
                planController.refreshInbox()
            }
            val afterSecondPullPlan = planController.plan ?: error("plan missing after second inbox pull")
            val afterSecondPullRepository = durableInboxRepository(context)
            assertEquals("second pull must not add a proposal", proposalIdsBeforeSecondPull,
                afterSecondPullRepository.all().mapTo(mutableSetOf()) { it.proposal.proposalId })
            assertEquals("second pull must not add an ACK", ackHistoryBeforeSecondPull, afterSecondPullRepository.ackHistory())
            assertTrue("second pull must leave no pending ACK", afterSecondPullRepository.pendingAcks().isEmpty())
            assertEquals("second pull must not create a plan revision", planBeforeSecondPull, afterSecondPullPlan)
            assertEquals("proposal must remain terminal after second pull", expectedProposalState(testAction),
                afterSecondPullRepository.get(proposalIdFromReply)?.proposal?.state)
            assertEquals(finalPlanVersion, afterSecondPullPlan.version)
            Log.i(
                LOG_TAG,
                "step=10 result=PASS second_pull=true new_proposals=0 new_acks=0 pending_acks=0 " +
                    "proposal_state=${afterSecondPullRepository.get(proposalIdFromReply)?.proposal?.state} " +
                    "plan_version=${afterSecondPullPlan.version}",
            )

            val consentBeforeAckReplay = syncStore.consent(CoreV4Category.DAY_PLAN).snapshot
            assertEquals(CoreV4ConsentState.GRANTED, consentBeforeAckReplay.state)
            assertEquals(durableAck.consentVersion, consentBeforeAckReplay.version.toLong())
            val planBeforeAckReplay = planController.plan ?: error("plan missing before ACK replay")
            assertEquals(finalPlanVersion, planBeforeAckReplay.version)
            val ackReplayTransport = InboxV4HttpTransport(
                baseUrl = null,
                bearerToken = "",
                consentVersion = consentBeforeAckReplay.version.toLong(),
                debuggable = true,
                poster = RelayCoreV4HttpTransport(),
                sessionAuthenticated = true,
            )
            val ackReplayResult = runCatching { ackReplayTransport.ack(durableAck) }
                .getOrElse { failure -> throw AssertionError("exact durable ACK replay failed", failure) }
            assertEquals(
                "Core must deliver the exact durable ACK replay",
                InboxV4DeliveryResult.Delivered,
                ackReplayResult,
            )
            val afterAckReplayRepository = durableInboxRepository(context)
            val afterAckReplayEntry = requireNotNull(afterAckReplayRepository.get(proposalIdFromReply))
            val afterAckReplayPlan = planController.plan ?: error("plan missing after ACK replay")
            assertEquals("ACK replay must leave exactly one local ACK", 1, afterAckReplayRepository.ackHistory().size)
            assertEquals(
                "ACK replay must leave exactly one local ACK for the proposal",
                1,
                afterAckReplayRepository.ackHistory(proposalIdFromReply).size,
            )
            assertEquals("ACK replay must not change the durable ACK", durableAck,
                afterAckReplayRepository.ackHistory(proposalIdFromReply).single())
            assertTrue("ACK replay must leave no pending ACK", afterAckReplayRepository.pendingAcks().isEmpty())
            assertEquals(expectedProposalState(testAction), afterAckReplayEntry.proposal.state)
            assertEquals(durableEntry.proposal.state, afterAckReplayEntry.proposal.state)
            assertEquals("ACK replay must not change the local plan", planBeforeAckReplay, afterAckReplayPlan)
            assertEquals(finalPlanVersion, afterAckReplayPlan.version)
            Log.i(
                LOG_TAG,
                "step=11 result=PASS ack_replay=identical ack_id=${durableAck.ackId} " +
                    "ack_state=${durableAck.state} ack_events_local=1 pending_acks=0",
            )

            val dayPlanRevoked = syncStore.setConsent(CoreV4Category.DAY_PLAN, granted = false).snapshot
            val statusRevoked = syncStore.setConsent(CoreV4Category.ACTIVITY_STATUS, granted = false).snapshot
            assertEquals(CoreV4ConsentState.REVOKED, dayPlanRevoked.state)
            assertEquals(CoreV4ConsentState.REVOKED, statusRevoked.state)
            assertTrue(dayPlanRevoked.version > dayPlanGranted.version)
            assertTrue(statusRevoked.version > statusGranted.version)
            CoreV4SyncScheduler.schedule(context)
            waitUntil(
                description = "synchronized V4 consent revocation",
                timeoutMillis = REVOCATION_TIMEOUT_MILLIS,
                diagnostic = { "day=${consentDiagnostic(syncStore.consent(CoreV4Category.DAY_PLAN).snapshot)}; " +
                    "status=${consentDiagnostic(syncStore.consent(CoreV4Category.ACTIVITY_STATUS).snapshot)}" },
            ) {
                val day = syncStore.consent(CoreV4Category.DAY_PLAN).snapshot
                val status = syncStore.consent(CoreV4Category.ACTIVITY_STATUS).snapshot
                day.state == CoreV4ConsentState.REVOKED && day.syncedVersion == day.version &&
                    status.state == CoreV4ConsentState.REVOKED && status.syncedVersion == status.version
            }
            val finalDayConsent = syncStore.consent(CoreV4Category.DAY_PLAN).snapshot
            val finalStatusConsent = syncStore.consent(CoreV4Category.ACTIVITY_STATUS).snapshot
            Log.i(
                LOG_TAG,
                "step=12 result=PASS final_day_plan=${consentDiagnostic(finalDayConsent)} " +
                    "final_activity_status=${consentDiagnostic(finalStatusConsent)}",
            )
            Log.i(
                LOG_TAG,
                "v7_06b_result=PASS action=${testAction.uppercase(Locale.ROOT)} run_id=$runId " +
                    "plan_id=${plan.planId} plan_date=${plan.localDate} base_version=${PLAN_VERSION_APPROVED} " +
                    "final_plan_version=$finalPlanVersion proposal_id=$proposalIdFromReply " +
                    "ack_id=${durableAck.ackId} ack_sequence=${durableAck.sequence} token_logged=false",
            )
        } catch (failure: Throwable) {
            scenarioFailure = failure
            Log.e(LOG_TAG, "v7_06b_result=FAIL first_failure=${failure.javaClass.simpleName}")
            throw failure
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun attemptCleanup(block: () -> Unit) {
                try {
                    block()
                } catch (failure: Throwable) {
                    cleanupFailures += failure
                }
            }
            attemptCleanup { instrumentation.runOnMainSync { relaySettings.disconnect() } }
            attemptCleanup { cancelCoreV4WorkAndAwaitIdle(context) }
            attemptCleanup {
                if (pairingFile.exists()) check(pairingFile.delete()) { "temporary pairing file cleanup failed" }
            }
            CoreV4Diagnostics.disable()
            if (cleanupFailures.isNotEmpty()) {
                val cleanupError = AssertionError("V7-06B cleanup failed (${cleanupFailures.size} step(s))")
                cleanupFailures.forEach(cleanupError::addSuppressed)
                if (scenarioFailure == null) throw cleanupError
                scenarioFailure.addSuppressed(cleanupError)
            }
        }
    }

    private fun createFreshApprovedPlan(
        instrumentation: Instrumentation,
        planController: dev.atlas.coach.app.PlanController,
        plansDirectory: File,
    ): DayPlan {
        assertTrue("today's plan should be created", instrumentation.runOnMainSyncResult {
            planController.createTodayPlan()
        })
        val versionOne = requireNotNull(planController.plan) { "new plan missing after createTodayPlan" }
        assertEquals(1, versionOne.version)
        assertEquals(PlanState.APPROVED, versionOne.state)
        assertTrue("new plan must not contain activities", versionOne.activities.isEmpty())
        assertTrue(
            "synthetic focus activity should be staged",
            instrumentation.runOnMainSyncResult {
                planController.addActivity(
                    title = SYNTHETIC_ACTIVITY_TITLE,
                    kind = ActivityKind.FOCUS,
                    startMinute = SYNTHETIC_START_MINUTE,
                    durationMinutes = SYNTHETIC_DURATION_MINUTES,
                    priority = Priority.IMPORTANT,
                )
            },
        )
        assertNotNull("activity confirmation preview is required", planController.preview)
        assertTrue("synthetic focus preview should be approved", instrumentation.runOnMainSyncResult {
            planController.confirmPreview()
        })
        instrumentation.runOnMainSync {
            planController.refresh()
            planController.refreshInbox()
        }
        val approved = requireNotNull(planController.plan) { "approved plan missing after confirmation" }
        assertEquals(PLAN_VERSION_APPROVED, approved.version)
        assertEquals(PlanState.APPROVED, approved.state)
        assertEquals(1, approved.activities.size)
        val planFiles = listPlanFiles(plansDirectory)
        assertEquals("qa package must contain only its newly created day plan", 1, planFiles.size)
        assertEquals("day-plan-${approved.localDate}.json", planFiles.single().name)
        return approved
    }

    private fun assertPlanFixture(plan: DayPlan, activity: PlanActivity, today: String) {
        assertTrue("plan_id must be a canonical UUID", isCanonicalUuid(plan.planId))
        assertEquals(today, plan.localDate)
        assertEquals(PLAN_VERSION_APPROVED, plan.version)
        assertEquals(PlanState.APPROVED, plan.state)
        assertEquals(SYNTHETIC_ACTIVITY_TITLE, activity.title)
        assertEquals(ActivityKind.FOCUS, activity.kind)
        assertEquals(Priority.IMPORTANT, activity.priority)
        assertEquals(SYNTHETIC_START_MINUTE, activity.scheduledLocal.startMinute)
        assertEquals(SYNTHETIC_DURATION_MINUTES, activity.scheduledLocal.durationMinutes)
        assertTrue("activity_id must be a canonical UUID", isCanonicalUuid(activity.activityId))
        assertEquals(plan.planId, activity.planId)
        assertNull(activity.notes)
        assertNull(activity.goalId)
        assertNull(activity.subGoalId)
    }

    private fun assertSingleRescheduledActivity(plan: DayPlan, original: PlanActivity, offsetMinutes: Int) {
        assertEquals(1, plan.activities.size)
        val moved = plan.activities.single()
        assertEquals(original.activityId, moved.activityId)
        assertEquals(original.title, moved.title)
        assertEquals(original.kind, moved.kind)
        assertEquals(original.priority, moved.priority)
        assertEquals(original.scheduledLocal.durationMinutes, moved.scheduledLocal.durationMinutes)
        assertEquals(original.scheduledLocal.startMinute + offsetMinutes, moved.scheduledLocal.startMinute)
    }

    private fun assertFreshUnknownConsent(category: String, snapshot: CoreV4ConsentSnapshot) {
        assertEquals("fresh qa consent $category must be UNKNOWN", CoreV4ConsentState.UNKNOWN, snapshot.state)
        assertEquals("fresh qa consent $category must be version 1", 1, snapshot.version)
        assertEquals("fresh qa consent $category must not be synced", 0, snapshot.syncedVersion)
    }

    private fun requiredArgument(arguments: android.os.Bundle, name: String): String =
        arguments.getString(name)?.trim()?.takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("$name is required")

    private fun listPlanFiles(directory: File): List<File> {
        if (!directory.exists()) return emptyList()
        require(directory.isDirectory) { "plans path is not a directory" }
        return directory.listFiles()?.filter { it.isFile && it.name.startsWith(PLAN_FILE_PREFIX) && it.name.endsWith(JSON_SUFFIX) }
            ?: error("cannot inspect private plans directory")
    }

    private fun durableInboxRepository(context: android.content.Context): InboxV4Repository = InboxV4Repository(
        FileInboxV4Storage(
            File(File(context.filesDir, PLANS_DIRECTORY), InboxV4LocalStore.FILE_NAME),
        ),
    )

    private fun expectedProposalState(action: String): InboxV4ProposalState = when (action) {
        ACTION_APPLY -> InboxV4ProposalState.APPLIED
        ACTION_REJECT -> InboxV4ProposalState.REJECTED
        else -> error("unreachable test_action=$action")
    }

    private fun expectedAckState(action: String): InboxV4AckState = when (action) {
        ACTION_APPLY -> InboxV4AckState.APPLIED
        ACTION_REJECT -> InboxV4AckState.REJECTED
        else -> error("unreachable test_action=$action")
    }

    private fun syncDiagnostic(store: AndroidCoreV4SyncStore, planId: String): String =
        "presentation=${store.presentation().code}; " +
            "day=${consentDiagnostic(store.consent(CoreV4Category.DAY_PLAN).snapshot)}; " +
            "status=${consentDiagnostic(store.consent(CoreV4Category.ACTIVITY_STATUS).snapshot)}; " +
            "plan_receipt=${store.latestPlanReceiptVersion(planId, store.consent(CoreV4Category.DAY_PLAN).snapshot.version)}; " +
            "freshness=${store.freshness(CoreV4Category.DAY_PLAN).substringBefore(' ').take(MAX_DIAGNOSTIC_STATE_CHARS)}"

    private fun consentDiagnostic(snapshot: CoreV4ConsentSnapshot): String =
        "${snapshot.state}:${snapshot.syncedVersion}/${snapshot.version}"

    private fun waitForPassAfter(
        baselineOrdinal: Long,
        timeoutMillis: Long,
        context: String,
        diagnostic: () -> String,
    ) {
        waitUntil(
            description = "$context worker pass",
            timeoutMillis = timeoutMillis,
            diagnostic = {
                val latestOrdinal = CoreV4Diagnostics.snapshot().maxOfOrNull { it.workerOrdinal } ?: 0L
                "baseline_worker=$baselineOrdinal; latest_worker=$latestOrdinal; ${diagnostic()}"
            },
        ) {
            CoreV4Diagnostics.snapshot().any {
                it.workerOrdinal > baselineOrdinal && it.stage == CoreV4Diagnostics.Stage.PASS_END
            }
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

    private fun cancelCoreV4WorkAndAwaitIdle(context: android.content.Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        val uniqueNames = listOf(CoreV4SyncScheduler.IMMEDIATE_WORK, CoreV4SyncScheduler.PERIODIC_WORK)
        uniqueNames.forEach { name ->
            manager.cancelUniqueWork(name).result.get(WORK_OPERATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }
        val deadline = SystemClock.uptimeMillis() + CLEANUP_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            val active = uniqueNames.flatMap { name ->
                manager.getWorkInfosForUniqueWork(name).get(WORK_OPERATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            }.filter {
                it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED
            }
            if (active.isEmpty()) return
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        val remaining = uniqueNames.flatMap { name ->
            manager.getWorkInfosForUniqueWork(name).get(WORK_OPERATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }.count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
        assertEquals("Core V4 work should stop during cleanup", 0, remaining)
    }

    private companion object {
        const val LOG_TAG = "ATLAS_V7_06B"
        const val PRODUCT_PACKAGE = "dev.atlas.coach"
        const val ARG_TEST_ACTION = "test_action"
        const val ARG_RELAY_URL = "relay_url"
        const val ARG_SERVER_ID = "server_id"
        const val ARG_SERVER_PUBLIC_KEY = "server_public_key"
        const val ACTION_APPLY = "apply"
        const val ACTION_REJECT = "reject"
        const val PAIRING_FILE_NAME = "pairing.json"
        const val PLANS_DIRECTORY = "plans"
        const val PLAN_FILE_PREFIX = "day-plan-"
        const val JSON_SUFFIX = ".json"
        const val SYNTHETIC_ACTIVITY_TITLE = "ATLAS QA focus fixture"
        const val SYNTHETIC_START_MINUTE = 600
        const val SYNTHETIC_DURATION_MINUTES = 45
        const val RESCHEDULE_MINUTES = 10
        const val PLAN_VERSION_APPROVED = 2
        const val PLAN_VERSION_APPLIED = 3
        const val SYNCED_CODE = "SYNCED"
        const val FRESH_PREFIX = "FRESH"
        const val NONE_AT_CORE = "NONE_AT_CORE"
        const val CHANGE_OP = "op"
        const val CHANGE_ACTIVITY_ID = "activity_id"
        const val CHANGE_TARGET_START = "target_start_minute"
        const val CHANGE_DURATION = "duration_minutes"
        const val CHANGE_AFTER = "after"
        const val MAX_CHAT_MESSAGE_LENGTH = 2_000
        const val PRIVATE_FILE_MODE = 0x180
        const val FILE_MODE_MASK = 0x1FF
        const val RELAY_TIMEOUT_MILLIS = 60_000L
        const val SYNC_TIMEOUT_MILLIS = 60_000L
        const val CHAT_TIMEOUT_MILLIS = 90_000L
        const val PROPOSAL_TIMEOUT_MILLIS = 60_000L
        const val ACK_TIMEOUT_MILLIS = 60_000L
        const val SECOND_PULL_TIMEOUT_MILLIS = 60_000L
        const val REVOCATION_TIMEOUT_MILLIS = 60_000L
        const val CLEANUP_TIMEOUT_MILLIS = 30_000L
        const val WORK_OPERATION_TIMEOUT_MILLIS = 20_000L
        const val POLL_INTERVAL_MILLIS = 250L
        const val MAX_DIAGNOSTIC_IDS = 5
        const val MAX_DIAGNOSTIC_STATE_CHARS = 24
        const val MAX_TIMEOUT_DIAGNOSTIC_CHARS = 1_200
        val QA_PACKAGE_PATTERN = Regex("^dev\\.atlas\\.coach\\.qa\\.v706b\\.[A-Za-z0-9._-]+$")
        val SHA256_PATTERN = Regex("^sha256:[0-9a-f]{64}$")
    }
}

private fun Instrumentation.runOnMainSyncResult(block: () -> Boolean): Boolean {
    var result = false
    runOnMainSync { result = block() }
    return result
}

private fun isCanonicalUuid(value: String): Boolean = runCatching {
    UUID.fromString(value).toString() == value.lowercase(Locale.ROOT)
}.getOrDefault(false)
