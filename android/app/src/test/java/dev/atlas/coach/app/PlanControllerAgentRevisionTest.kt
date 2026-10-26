package dev.atlas.coach.app

import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.ActivityStatus
import dev.atlas.coach.plan.PlanRevisionActivityV3
import dev.atlas.coach.plan.PlanRevisionAgentResponseV3
import dev.atlas.coach.plan.PlanRevisionApiRequest
import dev.atlas.coach.plan.PlanRevisionApiResult
import dev.atlas.coach.plan.PlanRevisionParametersV3
import dev.atlas.coach.plan.PlanRevisionProposalClient
import dev.atlas.coach.plan.PlanRevisionProposalEnvelopeV3
import dev.atlas.coach.plan.Priority
import dev.atlas.coach.plan.RevisionOrigin
import dev.atlas.coach.sync.agent.AgentMandate
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun controller(directory: File = temporaryFolder.root) = PlanController(
        directory = directory,
        clock = dev.atlas.coach.plan.PlanClock { now },
        zoneIdProvider = { zone },
        todayProvider = { day },
    )

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
    }
}
