package dev.atlas.coach.app

import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.ActivityStatus
import dev.atlas.coach.plan.A1ActivityClass
import dev.atlas.coach.plan.A1ActivityPolicy
import dev.atlas.coach.plan.A1ExternalLinkState
import dev.atlas.coach.plan.A1PolicyRejection
import dev.atlas.coach.plan.PlanRevisionActivityV3
import dev.atlas.coach.plan.PlanRevisionAgentResponseV3
import dev.atlas.coach.plan.PlanRevisionApiRequest
import dev.atlas.coach.plan.PlanRevisionApiResult
import dev.atlas.coach.plan.PlanRevisionParametersV3
import dev.atlas.coach.plan.PlanRevisionProposalClient
import dev.atlas.coach.plan.PlanRevisionProposalEnvelopeV3
import dev.atlas.coach.plan.Priority
import dev.atlas.coach.plan.RevisionOrigin
import dev.atlas.coach.plan.v5.PlanV5JsonCodec
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4AckState
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4ConfirmationMode
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4Fixtures
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4ProposalState
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4PullPage
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4Repository
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4Storage
import dev.atlas.coach.sync.agent.inbox.v4.InMemoryInboxV4Storage
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlanControllerAgentRevisionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val zone = ZoneId.of("Europe/Paris")
    private val day = LocalDate.of(2027, 1, 15)
    private val now = 1_800_000_000_000L

    @Test
    fun proposalUsesAgentOriginShowsDiffAndWaitsForGroupedConfirmation() {
        val controller = controller()
        val activityId = seedPlan(controller)
        val current = controller.plan!!
        var sent: PlanRevisionApiRequest? = null
        val proposal = proposal(
            controller = controller,
            activity = activityId,
            baseVersion = current.version,
        )
        val client = fakeClient(proposal) { sent = it }

        assertTrue(controller.proposeAgentRevision(
            client = client,
            conversationId = "conversation-1",
            message = "Déplace cette activité",
            mandate = AgentMandate(id = "active-without-focus-plan", allowedActions = emptySet()),
            planContextConsented = true,
            planRevisionCapabilityEnabled = true,
            requestId = REQUEST_ID,
        ))

        assertEquals(current, controller.plan)
        assertEquals("Déplace cette activité", sent!!.message)
        assertTrue(sent!!.planContextConsented)
        assertEquals(REQUEST_ID, sent!!.requestId)
        assertEquals(RevisionOrigin.AGENT, controller.preview!!.revision.origin)
        assertEquals("Titre agent", controller.preview!!.after.activities.single().title)
        assertEquals("Préparer la journée", controller.plan!!.activities.single().title)
        assertTrue(controller.confirmPreview())
        assertEquals("Titre agent", controller.plan!!.activities.single().title)
        assertEquals(3, controller.plan!!.version)
    }

    @Test
    fun replayReturnsAlreadyAppliedWithoutCreatingAnotherPreviewOrEffect() {
        val controller = controller()
        val activityId = seedPlan(controller)
        val proposal = proposal(controller, activityId, controller.plan!!.version)
        val client = fakeClient(proposal)

        assertTrue(controller.proposeAgentRevision(
            client, null, "Change le titre", AgentMandate.default(), true, true, REQUEST_ID,
        ))
        assertTrue(controller.confirmPreview())
        val applied = controller.plan
        assertTrue(controller.proposeAgentRevision(
            client, null, "Change le titre", AgentMandate.default(), true, true, REQUEST_ID,
        ))

        assertEquals(applied, controller.plan)
        assertNull(controller.preview)
        assertTrue(controller.message!!.contains("AlreadyApplied"))
    }

    @Test
    fun reportsMismatchedPlanStaleVersionAndExpiredProposalExplicitly() {
        val controller = controller()
        assertTrue(controller.createTodayPlan())
        val current = controller.plan!!

        assertFalse(controller.proposeAgentRevision(
            fakeClient(proposal(controller, baseVersion = current.version, planId = OTHER_PLAN_ID)),
            null, "Révise", AgentMandate.default(), true, true,
        ))
        assertTrue(controller.message!!.contains("PLAN_MISMATCH"))

        assertFalse(controller.proposeAgentRevision(
            fakeClient(proposal(controller, baseVersion = current.version + 1)),
            null, "Révise", AgentMandate.default(), true, true,
        ))
        assertTrue(controller.message!!.contains("STALE_AGENT_REVISION"))

        assertFalse(controller.proposeAgentRevision(
            fakeClient(proposal(controller, baseVersion = current.version, expiresAt = Instant.ofEpochMilli(now - 1))),
            null, "Révise", AgentMandate.default(), true, true,
        ))
        assertTrue(controller.message!!.contains("AGENT_REVISION_EXPIRED"))
    }

    @Test
    fun proposalNullAndNonOkAgentStatusAreExplicitAndConsentIsRequired() {
        val controller = controller()
        assertTrue(controller.createTodayPlan())
        val noProposalClient = PlanRevisionProposalClient { request ->
            assertTrue(request.planContextConsented)
            PlanRevisionApiResult.Success(response(status = "ok", proposal = null, reply = "Pas de changement."))
        }
        assertFalse(controller.proposeAgentRevision(
            noProposalClient, null, "Révise", AgentMandate.default(), true, true,
        ))
        assertEquals("Pas de changement.", controller.message)

        val unavailable = PlanRevisionProposalClient {
            PlanRevisionApiResult.Success(response(status = "unavailable", proposal = null, reply = "Service arrêté."))
        }
        assertFalse(controller.proposeAgentRevision(
            unavailable, null, "Révise", AgentMandate.default(), true, true,
        ))
        assertTrue(controller.message!!.contains("unavailable"))

        var calls = 0
        val mustNotCall = PlanRevisionProposalClient {
            calls++
            PlanRevisionApiResult.InvalidRequest
        }
        assertFalse(controller.proposeAgentRevision(
            mustNotCall, null, "Révise", AgentMandate.default(), false, true,
        ))
        assertFalse(controller.proposeAgentRevision(
            mustNotCall, null, "Révise", AgentMandate.default(), true, false,
        ))
        assertEquals(0, calls)
        assertNull(controller.preview)
    }

    @Test
    fun mapsHttpFailuresToMessagesWithoutPreviewing() {
        val controller = controller()
        assertTrue(controller.createTodayPlan())
        val outcomes = listOf(
            PlanRevisionApiResult.Unauthorized to "401",
            PlanRevisionApiResult.Forbidden to "403",
            PlanRevisionApiResult.Conflict to "409",
            PlanRevisionApiResult.Unprocessable to "422",
            PlanRevisionApiResult.ServerError(503) to "503",
            PlanRevisionApiResult.Timeout to "attente",
            PlanRevisionApiResult.InvalidResponse to "invalide",
            PlanRevisionApiResult.InvalidProposal("UNSUPPORTED_PROPOSAL_FIELD") to "UNSUPPORTED_PROPOSAL_FIELD",
        )
        outcomes.forEach { (result, expectedMessage) ->
            val client = PlanRevisionProposalClient { result }
            assertFalse(controller.proposeAgentRevision(client, null, "Révise", AgentMandate.default(), true, true))
            assertTrue(controller.message!!.contains(expectedMessage))
            assertNull(controller.preview)
        }
    }

    @Test
    fun suspendingProposalUsesProvidedIoDispatcherAndAppliesPreviewOnCallerThread() {
        val controller = controller()
        val activityId = seedPlan(controller)
        val proposal = proposal(controller, activityId, controller.plan!!.version)
        val callerThread = Thread.currentThread()
        var clientThread: Thread? = null
        var capturedRequest: PlanRevisionApiRequest? = null
        val ioDispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "plan-revision-test-io")
        }.asCoroutineDispatcher()
        val client = PlanRevisionProposalClient { request ->
            clientThread = Thread.currentThread()
            capturedRequest = request
            PlanRevisionApiResult.Success(response(proposal = proposal))
        }

        try {
            val accepted = runBlocking {
                controller.proposeAgentRevisionSuspending(
                    client = client,
                    conversationId = "conversation-async",
                    message = "Révise le créneau",
                    mandate = AgentMandate(id = "active-without-focus-plan", allowedActions = emptySet()),
                    planContextConsented = true,
                    planRevisionCapabilityEnabled = true,
                    requestId = REQUEST_ID,
                    ioDispatcher = ioDispatcher,
                )
            }

            assertTrue(accepted)
            assertEquals("plan-revision-test-io", clientThread!!.name)
            assertEquals(REQUEST_ID, capturedRequest!!.requestId)
            assertEquals(controller.plan!!.planId, capturedRequest!!.plan.planId)
            assertEquals(callerThread, Thread.currentThread())
            assertEquals(RevisionOrigin.AGENT, controller.preview!!.revision.origin)
            assertEquals("Titre agent", controller.preview!!.after.activities.single().title)
        } finally {
            ioDispatcher.close()
        }
    }

    @Test
    fun a1CapabilityDefaultsOffAndDoesNotDependOnPlanRevisionCapability() {
        assertFalse(AtlasController.readCapabilities(null)[AtlasController.CAP_A1_BOUNDED] == true)
        assertTrue(
            AtlasController.readCapabilities(mapOf(AtlasController.CAP_A1_BOUNDED to true))[AtlasController.CAP_A1_BOUNDED] == true,
        )

        val setup = a1Setup()
        val proposalId = queueA1Proposal(setup, number = 1)
        val versionBefore = setup.controller.plan!!.version
        assertEquals(540, setup.controller.plan!!.activities.single().scheduledLocal.startMinute)

        assertTrue(applyInbox(setup, proposalId, planRevisionEnabled = false))

        assertEquals(versionBefore + 1, setup.controller.plan!!.version)
        assertEquals(660, setup.controller.plan!!.activities.single().scheduledLocal.startMinute)
    }

    @Test
    fun a1CapabilityOffAndMissingOrRevokedConsentPreventWrites() {
        val setup = a1Setup()
        val proposalId = queueA1Proposal(setup, number = 2)
        val original = setup.controller.plan!!

        assertFalse(applyInbox(setup, proposalId, a1Enabled = false))
        assertEquals(original, setup.controller.plan)
        assertEquals(InboxV4ProposalState.RECEIVED, setup.inbox.get(proposalId)?.proposal?.state)
        assertTrue(setup.inbox.ackHistory(proposalId).isEmpty())

        val noConsentSetup = a1Setup(grantConsent = false)
        val noConsentProposal = queueA1Proposal(noConsentSetup, number = 12)
        val noConsentOriginal = noConsentSetup.controller.plan!!
        assertEquals(0, noConsentSetup.controller.a1Consent()?.version)
        assertFalse(applyInbox(noConsentSetup, noConsentProposal))
        assertEquals(noConsentOriginal, noConsentSetup.controller.plan)
        assertEquals(InboxV4ProposalState.RECEIVED, noConsentSetup.inbox.get(noConsentProposal)?.proposal?.state)
        assertTrue(noConsentSetup.inbox.ackHistory(noConsentProposal).isEmpty())

        assertTrue(setup.controller.setA1Consent(false, inboxConsentVersion = 3L))
        assertEquals(2, setup.controller.a1Consent()?.version)
        assertFalse(applyInbox(setup, proposalId))
        assertEquals(original, setup.controller.plan)
        assertEquals(InboxV4ProposalState.VALIDATION_REJECTED, setup.inbox.get(proposalId)?.proposal?.state)
        assertEquals(InboxV4AckState.VALIDATION_REJECTED, setup.inbox.ackHistory(proposalId).single().state)
    }

    @Test
    fun a1RevocationTerminalizesOldProposalAcrossReactivationAndAllowsFreshProposal() {
        val setup = a1Setup()
        val oldProposalId = queueA1Proposal(setup, number = 14)
        val original = setup.controller.plan!!
        val originalConsentVersion = setup.controller.a1Consent()!!.version
        assertEquals(1, originalConsentVersion)

        assertTrue(setup.controller.setA1Consent(false, inboxConsentVersion = 3L))
        val revokedConsentVersion = setup.controller.a1Consent()!!.version
        assertEquals(originalConsentVersion + 1, revokedConsentVersion)
        assertEquals(
            InboxV4ProposalState.VALIDATION_REJECTED,
            setup.inbox.get(oldProposalId)?.proposal?.state,
        )
        assertEquals(1, setup.inbox.ackHistory(oldProposalId).size)

        assertTrue(setup.controller.setA1Consent(true, inboxConsentVersion = 3L))
        assertEquals(revokedConsentVersion + 1, setup.controller.a1Consent()!!.version)
        assertFalse(applyInbox(setup, oldProposalId))
        assertEquals(original, setup.controller.plan)
        assertEquals(1, setup.inbox.ackHistory(oldProposalId).size)

        val freshProposalId = queueA1Proposal(setup, number = 15)
        assertTrue(applyInbox(setup, freshProposalId))
        assertEquals(original.version + 1, setup.controller.plan!!.version)
        assertEquals(660, setup.controller.plan!!.activities.single().scheduledLocal.startMinute)
        assertEquals(InboxV4ProposalState.APPLIED, setup.inbox.get(freshProposalId)?.proposal?.state)
    }

    @Test
    fun a1RevocationAcknowledgesDurableApplicationAndKeepsUnknownAckPending() {
        val storage = ToggleFailInboxV4Storage()
        val inbox = InboxV4Repository(
            storage = storage,
            now = { Instant.ofEpochMilli(A1_NOW) },
        )
        val setup = a1Setup(inboxRepository = inbox)
        val proposalId = queueA1Proposal(setup, number = 16)
        val originalVersion = setup.controller.plan!!.version

        assertFalse(applyInbox(setup, proposalId, onA1Committed = { _, _ -> storage.failWrites = true }))
        assertEquals(2, storage.failedWrites) // APPLIED and the fallback UNCERTAIN write both failed.
        assertEquals(originalVersion + 1, setup.controller.plan!!.version)
        assertEquals(660, setup.controller.plan!!.activities.single().scheduledLocal.startMinute)
        assertEquals(InboxV4ProposalState.RECEIVED, inbox.get(proposalId)?.proposal?.state)
        assertTrue(inbox.ackHistory(proposalId).isEmpty())

        val appliedStore = PlanV5JsonCodec.decodeStore(
            File(setup.directory, "day-plan-${setup.controller.today}.json").readText(),
        )
        val receipt = appliedStore.a1State.receipts.single { it.proposalId == proposalId }
        assertEquals(originalVersion + 1, receipt.versionAfter)

        assertTrue(setup.controller.setA1Consent(false, inboxConsentVersion = 3L))
        assertEquals(false, setup.controller.a1Consent()?.enabled)
        assertEquals(4, storage.failedWrites) // Revocation preserves the receipt and cannot persist either ACK.
        assertEquals(InboxV4ProposalState.RECEIVED, inbox.get(proposalId)?.proposal?.state)
        assertTrue(inbox.ackHistory(proposalId).isEmpty())
        assertEquals(receipt, PlanV5JsonCodec.decodeStore(
            File(setup.directory, "day-plan-${setup.controller.today}.json").readText(),
        ).a1State.receipts.single { it.proposalId == proposalId })

        assertFalse(setup.controller.setA1Consent(true, inboxConsentVersion = 3L))
        assertEquals(false, setup.controller.a1Consent()?.enabled)
        assertEquals(6, storage.failedWrites)

        storage.failWrites = false
        assertTrue(setup.controller.setA1Consent(true, inboxConsentVersion = 3L))
        assertEquals(true, setup.controller.a1Consent()?.enabled)
        val appliedAck = inbox.ackHistory(proposalId).single()
        assertEquals(InboxV4AckState.APPLIED, appliedAck.state)
        assertEquals(receipt.planId, appliedAck.proof?.planId)
        assertEquals(receipt.versionAfter.toLong(), appliedAck.proof?.appliedPlanVersion)
        assertEquals(receipt.revisionId, appliedAck.proof?.revisionId)
        assertEquals("sha256:${receipt.scopeHash}", appliedAck.proof?.scopeHash)
        assertEquals(receipt.effectIds, appliedAck.proof?.effectIds)
        assertEquals(InboxV4ProposalState.APPLIED, inbox.get(proposalId)?.proposal?.state)

        val unappliedSetup = a1Setup()
        val unappliedProposalId = queueA1Proposal(unappliedSetup, number = 17)
        assertTrue(unappliedSetup.controller.setA1Consent(false, inboxConsentVersion = 3L))
        assertEquals(
            InboxV4ProposalState.VALIDATION_REJECTED,
            unappliedSetup.inbox.get(unappliedProposalId)?.proposal?.state,
        )
        assertEquals(
            InboxV4AckState.VALIDATION_REJECTED,
            unappliedSetup.inbox.ackHistory(unappliedProposalId).single().state,
        )
        assertTrue(PlanV5JsonCodec.decodeStore(
            File(unappliedSetup.directory, "day-plan-${unappliedSetup.controller.today}.json").readText(),
        ).a1State.receipts.isEmpty())
    }

    @Test
    fun a1ReviewRequiresExplicitPerActivityUnlockAndDisplaysValidatedDiff() {
        val setup = a1Setup(unlocked = false)
        val proposalId = queueA1Proposal(setup, number = 3)

        val lockedReview = setup.controller.inspectA1InboxProposal(proposalId)!!
        assertEquals(A1PolicyRejection.ACTIVITY_LOCKED, lockedReview.rejection)
        assertTrue(lockedReview.diff.isEmpty())

        assertTrue(setup.controller.setA1ActivityPolicy(
            A1ActivityPolicy(
                activityId = setup.activityId,
                activityClass = A1ActivityClass.READING,
                unlocked = true,
                externalLinkState = A1ExternalLinkState.NONE,
            ),
        ))
        val unlockedReview = setup.controller.inspectA1InboxProposal(proposalId)!!
        assertTrue(unlockedReview.valid)
        assertEquals(1, unlockedReview.diff.size)
        assertEquals(540, unlockedReview.diff.single().from.startMinute)
        assertEquals(660, unlockedReview.diff.single().to.startMinute)
        assertEquals(0, unlockedReview.quotaUsed)
    }

    @Test
    fun confirmedA1CommitPublishesOnceAndUndoCreatesASecondVersionWithoutRefundingQuota() {
        val setup = a1Setup()
        val proposalId = queueA1Proposal(setup, number = 4)
        val callbacks = mutableListOf<Pair<Int, String>>()

        assertTrue(applyInbox(setup, proposalId, onA1Committed = { plan, receipt ->
            callbacks += plan.version to receipt.proposalId
        }))

        val committed = setup.controller.plan!!
        assertEquals(3, committed.version)
        assertEquals(ActivityStatus.PLANNED, committed.activities.single().status)
        assertEquals(660, committed.activities.single().scheduledLocal.startMinute)
        assertEquals(listOf(3 to proposalId), callbacks)
        assertNotNull(setup.controller.activeA1UndoReceipt())
        assertEquals(InboxV4ProposalState.APPLIED, setup.inbox.get(proposalId)?.proposal?.state)
        assertEquals(1, setup.inbox.ackHistory(proposalId).size)

        assertFalse(applyInbox(setup, proposalId, onA1Committed = { _, _ -> callbacks += -1 to "duplicate" }))
        assertEquals(3, setup.controller.plan!!.version)
        assertEquals(listOf(3 to proposalId), callbacks)

        assertTrue(setup.controller.undoA1(proposalId))
        assertEquals(4, setup.controller.plan!!.version)
        assertEquals(540, setup.controller.plan!!.activities.single().scheduledLocal.startMinute)
        assertFalse(setup.controller.undoA1(proposalId))
        assertNull(setup.controller.activeA1UndoReceipt())

        val stored = PlanV5JsonCodec.decodeStore(
            File(setup.directory, "day-plan-${setup.controller.today}.json").readText(),
        )
        assertEquals(1, stored.a1State.quotaByLocalDate[A1_DAY.toString()])
        assertEquals(1, stored.a1State.receipts.single().quotaUsed)
    }

    @Test
    fun a1StaleVersionAndDailyQuotaFailClosedWithoutAnotherPlanWrite() {
        val staleSetup = a1Setup()
        val staleId = queueA1Proposal(staleSetup, number = 5, baseVersion = staleSetup.controller.plan!!.version - 1L)
        val staleVersion = staleSetup.controller.plan!!.version
        assertTrue(applyInbox(staleSetup, staleId))
        assertEquals(staleVersion, staleSetup.controller.plan!!.version)
        assertEquals(InboxV4ProposalState.STALE_VERSION, staleSetup.inbox.get(staleId)?.proposal?.state)

        val quotaSetup = a1Setup()
        val first = queueA1Proposal(quotaSetup, number = 6, targetStart = 600)
        assertTrue(applyInbox(quotaSetup, first))
        val second = queueA1Proposal(quotaSetup, number = 7, targetStart = 660)
        assertTrue(applyInbox(quotaSetup, second))
        val third = queueA1Proposal(quotaSetup, number = 8, targetStart = 720)
        val review = quotaSetup.controller.inspectA1InboxProposal(third)!!
        assertEquals(A1PolicyRejection.QUOTA_EXCEEDED, review.rejection)
        val quotaVersion = quotaSetup.controller.plan!!.version
        assertTrue(applyInbox(quotaSetup, third))
        assertEquals(quotaVersion, quotaSetup.controller.plan!!.version)
        assertEquals(InboxV4ProposalState.VALIDATION_REJECTED, quotaSetup.inbox.get(third)?.proposal?.state)
    }

    @Test
    fun a1UndoIsRejectedAfterAnyNewerPlanVersion() {
        val setup = a1Setup()
        val proposalId = queueA1Proposal(setup, number = 14)
        assertTrue(applyInbox(setup, proposalId))

        assertTrue(setup.controller.moveActivity(setup.activityId, 600))
        assertTrue(setup.controller.confirmPreview())
        val newer = setup.controller.plan!!

        assertFalse(setup.controller.undoA1(proposalId))
        assertEquals(newer.version, setup.controller.plan!!.version)
        assertEquals(600, setup.controller.plan!!.activities.single().scheduledLocal.startMinute)
    }

    @Test
    fun a0AndA2KeepTheirExistingPlanRevisionGateAndSpecificConfirmationPath() {
        listOf(
            InboxV4ConfirmationMode.A0_USER_REVIEW,
            InboxV4ConfirmationMode.A2_USER_CONFIRMATION,
        ).forEachIndexed { index, mode ->
            val setup = a1Setup()
            val proposalId = queueA1Proposal(setup, number = 9 + index, mode = mode)
            val before = setup.controller.plan!!.version

            assertTrue(applyInbox(setup, proposalId, planRevisionEnabled = true, a1Enabled = false))

            assertEquals(before + 1, setup.controller.plan!!.version)
            assertEquals(660, setup.controller.plan!!.activities.single().scheduledLocal.startMinute)
        }
    }

    @Test
    fun refusingA1IsTerminalAndNeverWritesThePlan() {
        val setup = a1Setup()
        val proposalId = queueA1Proposal(setup, number = 11)
        val original = setup.controller.plan!!

        assertTrue(setup.controller.rejectInboxProposal(proposalId, consentVersion = 3L))

        assertEquals(original, setup.controller.plan)
        assertEquals(InboxV4ProposalState.REJECTED, setup.inbox.get(proposalId)?.proposal?.state)
        assertEquals(1, setup.inbox.ackHistory(proposalId).size)
        assertTrue(PlanV5JsonCodec.decodeStore(
            File(setup.directory, "day-plan-${setup.controller.today}.json").readText(),
        ).a1State.receipts.isEmpty())
    }

    @Test
    fun a1NotificationLockScreenContentIsNeutralAndContainsNoProposalDetails() {
        val content = A1PlanNotificationPublisher.lockScreenContent()

        assertEquals("Programme mis à jour", content.title)
        assertEquals("Une modification confirmée est enregistrée. Ouvrez ATLAS pour consulter le planning.", content.body)
        assertEquals("Mise à jour disponible", content.publicBody)
        assertFalse(content.title.contains("Préparer la journée"))
        assertFalse(content.body.contains("Préparer la journée"))
        assertFalse(content.body.contains("proposition"))
    }

    private fun controller(
        directory: File = temporaryFolder.root,
        now: Long = this.now,
        date: LocalDate = day,
        inboxRepository: InboxV4Repository? = null,
    ) = PlanController(
        directory = directory,
        clock = dev.atlas.coach.plan.PlanClock { now },
        zoneIdProvider = { zone },
        todayProvider = { date },
        inboxRepository = inboxRepository,
    )

    private data class A1TestSetup(
        val controller: PlanController,
        val inbox: InboxV4Repository,
        val directory: File,
        val activityId: String,
    )

    private fun a1Setup(
        unlocked: Boolean = true,
        grantConsent: Boolean = true,
        inboxRepository: InboxV4Repository? = null,
    ): A1TestSetup {
        val directory = temporaryFolder.newFolder()
        val inbox = inboxRepository ?: InboxV4Repository(
            storage = InMemoryInboxV4Storage(),
            now = { Instant.ofEpochMilli(A1_NOW) },
        )
        val controller = controller(directory, A1_NOW, A1_DAY, inbox)
        assertTrue(controller.createTodayPlan())
        assertTrue(controller.addActivity("Préparer la journée", ActivityKind.FOCUS, 540, 50, Priority.IMPORTANT))
        assertTrue(controller.confirmPreview())
        val activityId = controller.plan!!.activities.single().activityId
        if (grantConsent) assertTrue(controller.setA1Consent(true))
        assertTrue(controller.setA1ActivityPolicy(
            A1ActivityPolicy(
                activityId = activityId,
                activityClass = A1ActivityClass.READING,
                unlocked = unlocked,
                externalLinkState = if (unlocked) A1ExternalLinkState.NONE else A1ExternalLinkState.UNKNOWN,
            ),
        ))
        return A1TestSetup(controller, inbox, directory, activityId)
    }

    private fun queueA1Proposal(
        setup: A1TestSetup,
        number: Int,
        baseVersion: Long = setup.controller.plan!!.version.toLong(),
        targetStart: Int = 660,
        mode: InboxV4ConfirmationMode = InboxV4ConfirmationMode.A1_ANDROID_ELIGIBLE_TO_CHECK,
    ): String {
        val proposalId = testUuid(number)
        val proposal = InboxV4Fixtures.proposal(
            proposalId = proposalId,
            idempotencyKey = testUuid(number + 100),
            expiresAt = Instant.ofEpochMilli(A1_NOW + 15 * 60_000L),
        ).copy(
            createdAt = Instant.ofEpochMilli(A1_NOW),
            expiresAt = Instant.ofEpochMilli(A1_NOW + 15 * 60_000L),
            planId = setup.controller.plan!!.planId,
            baseVersion = baseVersion,
            justification = "Proposition synthétique de déplacement.",
            changes = listOf(mapOf(
                "op" to "reschedule",
                "activity_id" to setup.activityId,
                "target_start_minute" to targetStart,
                "duration_minutes" to 50,
            )),
            confirmationMode = mode,
        )
        assertEquals(1, setup.inbox.mergePulled(InboxV4PullPage(listOf(proposal), null, false), 8).added)
        return proposalId
    }

    private fun applyInbox(
        setup: A1TestSetup,
        proposalId: String,
        planRevisionEnabled: Boolean = false,
        a1Enabled: Boolean = true,
        onA1Committed: (dev.atlas.coach.plan.DayPlan, dev.atlas.coach.plan.A1RevisionReceipt) -> Unit = { _, _ -> },
    ): Boolean = setup.controller.applyInboxProposal(
        proposalId = proposalId,
        planRevisionCapabilityEnabled = planRevisionEnabled,
        dayPlanConsentGranted = true,
        dayPlanConsentVersion = 3L,
        a1CapabilityEnabled = { a1Enabled },
        currentDayPlanConsent = { true to 3L },
        onA1Committed = onA1Committed,
    )

    private fun testUuid(number: Int): String = "90000000-0000-4000-8000-${number.toString().padStart(12, '0')}"

    private fun seedPlan(controller: PlanController): String {
        assertTrue(controller.createTodayPlan())
        assertTrue(controller.addActivity("Préparer la journée", ActivityKind.FOCUS, 540, 50, Priority.IMPORTANT))
        assertTrue(controller.confirmPreview())
        assertEquals(ActivityStatus.PLANNED, controller.plan!!.activities.single().status)
        return controller.plan!!.activities.single().activityId
    }

    private fun fakeClient(
        proposal: PlanRevisionProposalEnvelopeV3,
        onRequest: (PlanRevisionApiRequest) -> Unit = {},
    ) = PlanRevisionProposalClient { request ->
        onRequest(request)
        PlanRevisionApiResult.Success(response(proposal = proposal))
    }

    private fun proposal(
        controller: PlanController,
        activity: String? = null,
        baseVersion: Int,
        planId: String = controller.plan!!.planId,
        expiresAt: Instant = Instant.ofEpochMilli(now + 60 * 60 * 1_000L),
    ) = PlanRevisionProposalEnvelopeV3(
        proposalId = PROPOSAL_ID,
        idempotencyKey = "idempotency-1",
        conversationId = null,
        mandateId = "mandate-local",
        mandateVersion = 2,
        createdAt = Instant.ofEpochMilli(now),
        expiresAt = expiresAt,
        parameters = PlanRevisionParametersV3(
            planId = planId,
            baseVersion = baseVersion,
            localDate = controller.plan!!.localDate,
            timezone = controller.plan!!.timezone,
            summary = "Réviser le jour",
            activities = if (activity == null) emptyList() else listOf(PlanRevisionActivityV3(
                clientRef = "ref-existing",
                activityId = activity,
                kind = ActivityKind.ADMIN,
                title = "Titre agent",
                localDate = controller.plan!!.localDate,
                startMinute = 600,
                durationMinutes = 45,
                timezone = controller.plan!!.timezone,
                priority = Priority.REQUIRED,
                goalId = null,
                subGoalId = null,
                notes = null,
            )),
        ),
    )

    private fun response(
        status: String = "ok",
        proposal: PlanRevisionProposalEnvelopeV3?,
        reply: String = "Proposition prête.",
    ) = PlanRevisionAgentResponseV3(3, reply, status, proposal, emptyList())

    companion object {
        private const val PROPOSAL_ID = "90000000-0000-4000-8000-000000000001"
        private const val REQUEST_ID = "90000000-0000-4000-8000-000000000002"
        private const val OTHER_PLAN_ID = "90000000-0000-4000-8000-000000000003"
        private val A1_NOW: Long = Instant.parse("2026-10-29T08:10:00Z").toEpochMilli()
        private val A1_DAY: LocalDate = LocalDate.of(2026, 10, 29)
    }
}

private class ToggleFailInboxV4Storage : InboxV4Storage {
    private val delegate = InMemoryInboxV4Storage()
    var failWrites = false
    var failedWrites = 0
        private set

    override fun read(): String? = delegate.read()

    override fun write(serializedState: String) {
        if (failWrites) {
            failedWrites++
            throw IllegalStateException("synthetic inbox write failure")
        }
        delegate.write(serializedState)
    }

    override fun quarantine(raw: String, reason: String): String? = delegate.quarantine(raw, reason)
}
