package dev.atlas.coach.sync.v4

import dev.atlas.coach.app.PlanController
import dev.atlas.coach.plan.A1ActivityClass
import dev.atlas.coach.plan.A1ActivityPolicy
import dev.atlas.coach.plan.A1ExternalLinkState
import dev.atlas.coach.plan.PlanApplyResult
import dev.atlas.coach.plan.DayPlanRepository
import dev.atlas.coach.plan.FilePlanBackend
import dev.atlas.coach.plan.PlanClock
import dev.atlas.coach.plan.PlanConfirmation
import dev.atlas.coach.plan.PlanPreviewResult
import dev.atlas.coach.plan.PlanRevisionEngine
import dev.atlas.coach.plan.PlanRevisionMapper
import dev.atlas.coach.plan.RevisionOrigin
import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.Priority
import dev.atlas.coach.plan.v5.PlanV5JsonCodec
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4Ack
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4AckDeliveryState
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4AckState
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4DeliveryResult
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4Fixtures
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4HttpException
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4HttpFailureKind
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4HttpPoster
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4HttpTransport
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4Proposal
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4ProposalParser
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4ProposalParseResult
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4PullPage
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4Repository
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4Storage
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4Transport
import dev.atlas.coach.sync.agent.inbox.v4.InMemoryInboxV4Storage
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InboxV4SyncTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun androidPullUsesFixedRouteCarriesCursorAndQuarantinesUnknownProposalData() {
        val storage = InMemoryInboxV4Storage()
        val repository = InboxV4Repository(storage, { NOW })
        val first = rawProposal(confirmationMode = "A0_USER_REVIEW")
        val unsupported = JSONObject(first).put("schema_version", 5).toString()
        val pages = ArrayDeque(listOf(
            FakePage(listOf(first, first, unsupported), "cursor-next"),
            FakePage(emptyList(), null),
        ))
        val requests = mutableListOf<CoreV4HttpRequest>()
        val transport = InboxV4HttpTransport(
            baseUrl = "https://core.example.test",
            bearerToken = "synthetic-secret-token",
            consentVersion = 3,
            debuggable = false,
            poster = InboxV4HttpPoster { request ->
                requests += request
                val page = pages.removeFirst()
                CoreV4HttpResponse(
                    200,
                    pullBody(page.proposals, page.nextCursor, JSONObject(request.body).getString("request_id")),
                )
            },
        )

        val pageOne = transport.pullRaw(repository.cursor(), 8)
        val firstMerge = repository.ingestRawPage(pageOne.proposals, pageOne.nextCursor, pageOne.truncated, 8)
        val pageTwo = transport.pullRaw(repository.cursor(), 8)
        val secondMerge = repository.ingestRawPage(pageTwo.proposals, pageTwo.nextCursor, pageTwo.truncated, 8)

        assertEquals(InboxV4HttpTransport.PULL_ROUTE, requests[0].path)
        assertEquals("cursor-next", JSONObject(requests[1].body).getString("cursor"))
        assertEquals(3, JSONObject(requests[0].body).getInt("consent_version"))
        assertEquals(1, firstMerge.added)
        assertEquals(1, firstMerge.duplicates)
        assertEquals(1, repository.quarantinedPayloads().size)
        assertEquals("cursor-next", pageOne.nextCursor)
        assertTrue(pageOne.truncated)
        assertNull(pageTwo.nextCursor)
        assertNull(secondMerge.cursor)
        assertFalse(transport.toString().contains("synthetic-secret-token"))
    }

    @Test
    fun ackUsesFixedProposalRouteEchoValidationAndRedactedRequest() {
        val ack = InboxV4Fixtures.ack()
        val captured = mutableListOf<CoreV4HttpRequest>()
        val transport = InboxV4HttpTransport(
            baseUrl = "https://core.example.test/",
            bearerToken = "synthetic-secret-token",
            consentVersion = 3,
            debuggable = false,
            poster = InboxV4HttpPoster { request ->
                captured += request
                CoreV4HttpResponse(200, ack.canonicalBody())
            },
        )

        assertEquals(InboxV4DeliveryResult.Delivered, transport.ack(ack))
        assertEquals("/v4/agent/proposals/${ack.proposalId}/ack", captured.single().path)
        assertFalse(captured.single().toString().contains("synthetic-secret-token"))
    }

    @Test
    fun httpFailuresAreTypedAndRetryableOnlyForTransientStatuses() {
        val cases = listOf(
            Triple(401, InboxV4HttpFailureKind.AUTHENTICATION, false),
            Triple(403, InboxV4HttpFailureKind.CONSENT, false),
            Triple(409, InboxV4HttpFailureKind.CONFLICT, false),
            Triple(422, InboxV4HttpFailureKind.REJECTED, false),
            Triple(503, InboxV4HttpFailureKind.UNAVAILABLE, true),
        )
        cases.forEach { (status, expectedKind, expectedRetryable) ->
            val transport = InboxV4HttpTransport(
                baseUrl = "https://core.example.test",
                bearerToken = "synthetic-token",
                consentVersion = 2,
                debuggable = false,
                poster = InboxV4HttpPoster { CoreV4HttpResponse(status, "{\"error\":{\"code\":\"TEST_ERROR\"}}") },
            )
            val failure = runCatching { transport.pullRaw(null, 1) }.exceptionOrNull()
            assertTrue("status $status", failure is InboxV4HttpException)
            assertEquals(expectedKind, (failure as InboxV4HttpException).kind)
            assertEquals(expectedRetryable, failure.retryable)
            assertEquals(status, failure.statusCode)

            val ackTransport = InboxV4HttpTransport(
                baseUrl = "https://core.example.test",
                bearerToken = "synthetic-token",
                consentVersion = 2,
                debuggable = false,
                poster = InboxV4HttpPoster { CoreV4HttpResponse(status, "{\"error\":{\"code\":\"TEST_ERROR\"}}") },
            )
            val ackFailure = runCatching { ackTransport.ack(InboxV4Fixtures.ack()) }.exceptionOrNull()
            assertTrue("ACK status $status must remain typed", ackFailure is InboxV4HttpException)
            assertEquals(expectedKind, (ackFailure as InboxV4HttpException).kind)
            assertEquals(expectedRetryable, ackFailure.retryable)
            assertEquals(expectedKind, ackTransport.drainObservedFailures().single().kind)
        }
    }

    @Test
    fun noPlanMutationHappensOnPullAndA0NeedsExplicitConfirmationAndLocalProof() {
        val harness = harness("A0_USER_REVIEW")
        val before = harness.controller.plan!!

        assertEquals(before, harness.controller.plan)
        assertEquals(1, harness.controller.inboxEntries.size)
        assertEquals("RECEIVED", harness.controller.inboxEntries.single().proposal.state.name)

        assertTrue(harness.controller.applyInboxProposal(
            harness.proposal.proposalId,
            planRevisionCapabilityEnabled = true,
            dayPlanConsentGranted = true,
            dayPlanConsentVersion = 3,
        ))

        val applied = harness.controller.plan!!
        val ack = harness.inbox.ackHistory(harness.proposal.proposalId).single()
        assertEquals(before.version + 1, applied.version)
        assertEquals(600, applied.activities.single().scheduledLocal.startMinute)
        assertEquals(InboxV4AckState.APPLIED, ack.state)
        assertNotNull(ack.proof)
        assertEquals(applied.planId, ack.proof!!.planId)
        assertEquals(applied.version.toLong(), ack.proof!!.appliedPlanVersion)
        assertEquals(applied.currentRevisionId, ack.proof!!.revisionId)
        assertEquals(InboxV4AckDeliveryState.PENDING, harness.inbox.pendingAcks().single().deliveryState)

        assertFalse(harness.controller.applyInboxProposal(
            harness.proposal.proposalId,
            planRevisionCapabilityEnabled = true,
            dayPlanConsentGranted = true,
            dayPlanConsentVersion = 3,
        ))
        assertEquals(applied.version, harness.controller.plan!!.version)
        assertEquals(1, harness.inbox.ackHistory(harness.proposal.proposalId).size)
    }

    @Test
    fun a2RequiresAnExplicitApplyCallAndUserRefusalIsTerminal() {
        val accepted = harness("A2_USER_CONFIRMATION")
        val originalVersion = accepted.controller.plan!!.version
        assertTrue(accepted.controller.applyInboxProposal(
            accepted.proposal.proposalId,
            planRevisionCapabilityEnabled = true,
            dayPlanConsentGranted = true,
            dayPlanConsentVersion = 3,
        ))
        assertEquals(originalVersion + 1, accepted.controller.plan!!.version)

        val refused = harness("A0_USER_REVIEW")
        val version = refused.controller.plan!!.version
        assertTrue(refused.controller.rejectInboxProposal(refused.proposal.proposalId, consentVersion = 3))
        assertEquals(version, refused.controller.plan!!.version)
        assertEquals(InboxV4AckState.REJECTED, refused.inbox.ackHistory().single().state)
        assertNull(refused.inbox.ackHistory().single().proof)
        assertFalse(refused.controller.applyInboxProposal(
            refused.proposal.proposalId,
            planRevisionCapabilityEnabled = true,
            dayPlanConsentGranted = true,
            dayPlanConsentVersion = 3,
        ))
        assertEquals(version, refused.controller.plan!!.version)
    }

    @Test
    fun a1RequiresCapabilityAndVersionedConsentAndUsesAtomicA1Receipt() {
        val harness = harness("A1_ANDROID_ELIGIBLE_TO_CHECK", a1Ttl = true)
        val original = harness.controller.plan!!
        assertFalse(harness.controller.applyInboxProposal(
            harness.proposal.proposalId,
            planRevisionCapabilityEnabled = false,
            dayPlanConsentGranted = true,
            dayPlanConsentVersion = 3,
        ))
        assertFalse(harness.controller.applyInboxProposal(
            harness.proposal.proposalId,
            planRevisionCapabilityEnabled = true,
            dayPlanConsentGranted = true,
            dayPlanConsentVersion = 3,
        ))
        assertEquals(original, harness.controller.plan)
        assertTrue(harness.inbox.ackHistory().isEmpty())

        val planRepository = repositoryFor(harness)
        assertTrue(planRepository.setA1Consent(true).toString().contains("Updated"))
        val activity = original.activities.single()
        assertTrue(planRepository.setA1ActivityPolicy(A1ActivityPolicy(
            activityId = activity.activityId,
            activityClass = A1ActivityClass.READING,
            unlocked = true,
            externalLinkState = A1ExternalLinkState.NONE,
        )).toString().contains("Updated"))

        assertTrue(harness.controller.applyInboxProposal(
            harness.proposal.proposalId,
            planRevisionCapabilityEnabled = true,
            dayPlanConsentGranted = true,
            dayPlanConsentVersion = 3,
        ))
        val applied = harness.controller.plan!!
        val persisted = PlanV5JsonCodec.decodeStore(
            File(harness.directory, "day-plan-${harness.controller.today}.json").readText(),
        )
        assertEquals(original.version + 1, applied.version)
        assertEquals(harness.proposal.proposalId, persisted.a1State.receipts.single().proposalId)
        assertEquals(InboxV4AckState.APPLIED, harness.inbox.ackHistory().single().state)
        assertEquals(applied.currentRevisionId, harness.inbox.ackHistory().single().proof!!.revisionId)
    }

    @Test
    fun a1EngineRejectsUnclassifiedActivitiesWithoutChangingThePlan() {
        val harness = harness("A1_ANDROID_ELIGIBLE_TO_CHECK", a1Ttl = true)
        val before = harness.controller.plan!!
        assertTrue(repositoryFor(harness).setA1Consent(true).toString().contains("Updated"))

        assertTrue(harness.controller.applyInboxProposal(
            harness.proposal.proposalId,
            planRevisionCapabilityEnabled = true,
            dayPlanConsentGranted = true,
            dayPlanConsentVersion = 3,
        ))

        assertEquals(before, harness.controller.plan)
        val ack = harness.inbox.ackHistory().single()
        assertEquals(InboxV4AckState.VALIDATION_REJECTED, ack.state)
        assertNull(ack.proof)
    }

    @Test
    fun staleConsentAndExpiredProposalsNeverMutateThePlan() {
        val noConsent = harness("A0_USER_REVIEW")
        val version = noConsent.controller.plan!!.version
        assertFalse(noConsent.controller.applyInboxProposal(
            noConsent.proposal.proposalId,
            planRevisionCapabilityEnabled = true,
            dayPlanConsentGranted = false,
            dayPlanConsentVersion = 3,
        ))
        assertEquals(version, noConsent.controller.plan!!.version)
        assertTrue(noConsent.inbox.ackHistory().isEmpty())

        val stale = harness("A0_USER_REVIEW")
        val activity = stale.controller.plan!!.activities.single()
        assertTrue(stale.controller.moveActivity(activity.activityId, 570))
        assertTrue(stale.controller.confirmPreview())
        val staleVersion = stale.controller.plan!!.version
        assertTrue(stale.controller.applyInboxProposal(
            stale.proposal.proposalId,
            planRevisionCapabilityEnabled = true,
            dayPlanConsentGranted = true,
            dayPlanConsentVersion = 3,
        ))
        assertEquals(staleVersion, stale.controller.plan!!.version)
        assertEquals(InboxV4AckState.STALE_VERSION, stale.inbox.ackHistory().single().state)

        val expired = harness("A0_USER_REVIEW")
        expired.clockNow = expired.proposal.expiresAt.toEpochMilli()
        val before = expired.controller.plan!!.version
        assertTrue(expired.controller.applyInboxProposal(
            expired.proposal.proposalId,
            planRevisionCapabilityEnabled = true,
            dayPlanConsentGranted = true,
            dayPlanConsentVersion = 3,
        ))
        assertEquals(before, expired.controller.plan!!.version)
        assertEquals(InboxV4AckState.EXPIRED, expired.inbox.ackHistory().single().state)
    }

    @Test
    fun offlineAckReplayUsesTheSameIdAndCannotApplyThePlanTwice() {
        val harness = harness("A0_USER_REVIEW")
        assertTrue(harness.controller.applyInboxProposal(
            harness.proposal.proposalId,
            planRevisionCapabilityEnabled = true,
            dayPlanConsentGranted = true,
            dayPlanConsentVersion = 3,
        ))
        val version = harness.controller.plan!!.version
        val originalAckId = harness.inbox.pendingAcks().single().ack.ackId

        val offline = harness.inbox.replayPendingAcks(ReplayTransport(fail = true))
        val restarted = InboxV4Repository(harness.storage, { NOW })
        assertEquals(1, offline.uncertain)
        assertEquals(originalAckId, restarted.pendingAcks().single().ack.ackId)
        assertEquals(InboxV4AckDeliveryState.UNCERTAIN, restarted.pendingAcks().single().deliveryState)
        assertEquals(version, harness.controller.plan!!.version)

        val replay = restarted.replayPendingAcks(ReplayTransport())
        assertEquals(1, replay.delivered)
        assertEquals(originalAckId, restarted.ackHistory().single().ackId)
        assertEquals(version, harness.controller.plan!!.version)
    }

    @Test
    fun durableLocalRevisionProofRecoversAnAckAfterAProcessGapWithoutReapplying() {
        val harness = harness("A0_USER_REVIEW")
        val current = harness.controller.plan!!
        val engine = PlanRevisionEngine()
        val revisionId = PlanRevisionMapper.revisionId(harness.proposal.proposalId)
        val proposed = current.activities.map { activity ->
            activity.copy(scheduledLocal = activity.scheduledLocal.copy(startMinute = 600))
        }
        val revision = engine.createRevision(
            current = current,
            proposedActivities = proposed,
            revisionId = revisionId,
            origin = RevisionOrigin.AGENT,
            summary = harness.proposal.justification,
            createdAt = NOW.toEpochMilli(),
            ttlMillis = harness.proposal.expiresAt.toEpochMilli() - NOW.toEpochMilli(),
        )
        val repository = repositoryFor(harness)
        val preview = (repository.preview(revision) as PlanPreviewResult.Ready).preview
        val confirmation = PlanConfirmation(
            confirmationId = UUID.randomUUID().toString(),
            revisionId = revisionId,
            effectIds = preview.effectIds,
            scopeHash = preview.scopeHash,
            approvedAt = NOW.toEpochMilli(),
            expiresAt = NOW.toEpochMilli() + 5 * 60_000L,
        )
        val applied = repository.apply(revision, confirmation)
        assertTrue(applied is PlanApplyResult.Applied)
        val appliedVersion = (applied as PlanApplyResult.Applied).plan.version

        harness.controller.refresh()
        assertTrue(harness.controller.applyInboxProposal(
            harness.proposal.proposalId,
            planRevisionCapabilityEnabled = true,
            dayPlanConsentGranted = true,
            dayPlanConsentVersion = 3,
        ))

        val ack = harness.inbox.ackHistory().single()
        assertEquals(InboxV4AckState.APPLIED, ack.state)
        assertEquals(appliedVersion, harness.controller.plan!!.version)
        assertEquals(appliedVersion.toLong(), ack.proof!!.appliedPlanVersion)
    }

    private fun harness(confirmationMode: String, a1Ttl: Boolean = false): Harness {
        val directory = temporaryFolder.newFolder()
        var clockNow = NOW.toEpochMilli()
        val storage = InMemoryInboxV4Storage()
        val inbox = InboxV4Repository(storage, { Instant.ofEpochMilli(clockNow) })
        val controller = PlanController(
            directory = directory,
            clock = PlanClock { clockNow },
            zoneIdProvider = { ZoneId.of("Europe/Paris") },
            todayProvider = { LocalDate.of(2027, 1, 15) },
            inboxRepository = inbox,
        )
        assertTrue(controller.createTodayPlan())
        assertTrue(controller.addActivity("Activité synthétique", ActivityKind.FOCUS, 540, 50, Priority.IMPORTANT))
        assertTrue(controller.confirmPreview())
        val plan = controller.plan!!
        val activityId = plan.activities.single().activityId
        val proposal = InboxV4ProposalParser.parse(rawProposal(
            proposalId = UUID.randomUUID().toString(),
            planId = plan.planId,
            baseVersion = plan.version,
            activityId = activityId,
            confirmationMode = confirmationMode,
            now = clockNow,
            expiresAt = clockNow + if (a1Ttl) 5 * 60_000L else 60 * 60_000L,
        )) as InboxV4ProposalParseResult.Parsed
        inbox.mergePulled(InboxV4PullPage(listOf(proposal.proposal), null, false), 8)
        controller.refreshInbox()
        return Harness(directory, controller, storage, inbox, proposal.proposal, { clockNow }, { clockNow = it })
    }

    private fun repositoryFor(harness: Harness) = DayPlanRepository(
        FilePlanBackend(File(harness.directory, "day-plan-${harness.controller.today}.json")),
        PlanClock { harness.clockNow },
    )

    private fun rawProposal(
        proposalId: String = UUID.randomUUID().toString(),
        planId: String = InboxV4Fixtures.PLAN_ID,
        baseVersion: Int = 7,
        activityId: String = InboxV4Fixtures.ACTIVITY_ID,
        confirmationMode: String,
        now: Long = NOW.toEpochMilli(),
        expiresAt: Long = now + 60 * 60_000L,
    ): String {
        val created = Instant.ofEpochMilli(now - 1_000L)
        val expires = Instant.ofEpochMilli(expiresAt)
        val changes = JSONArray().put(JSONObject()
            .put("op", "reschedule")
            .put("activity_id", activityId)
            .put("target_start_minute", 600)
            .put("duration_minutes", 50))
        val diff = JSONArray().put(JSONObject()
            .put("op", "reschedule")
            .put("activity_id", activityId)
            .put("client_ref", JSONObject.NULL)
            .put("before", JSONObject().put("start_minute", 540).put("duration_minutes", 50))
            .put("after", JSONObject().put("start_minute", 600).put("duration_minutes", 50)))
        return JSONObject(InboxV4Fixtures.rawProposal())
            .put("proposal_id", proposalId)
            .put("idempotency_key", UUID.randomUUID().toString())
            .put("plan_id", planId)
            .put("base_version", baseVersion)
            .put("created_at", created.toString())
            .put("expires_at", expires.toString())
            .put("confirmation_mode", confirmationMode)
            .put("changes", changes)
            .put("diff", diff)
            .toString()
    }

    private fun pullBody(proposals: List<String>, nextCursor: String?, requestId: String): String {
        return JSONObject()
            .put("contract_version", 4)
            .put("schema_version", 4)
            .put("request_id", requestId)
            .put("capability", "proposal.ack")
            .put("proposals", JSONArray().apply { proposals.forEach { put(JSONObject(it)) } })
            .put("next_cursor", nextCursor ?: JSONObject.NULL)
            .toString()
    }

    private data class Harness(
        val directory: File,
        val controller: PlanController,
        val storage: InboxV4Storage,
        val inbox: InboxV4Repository,
        val proposal: InboxV4Proposal,
        private val readNow: () -> Long,
        private val setNow: (Long) -> Unit,
    ) {
        var clockNow: Long
            get() = readNow()
            set(value) = setNow(value)
    }

    private data class FakePage(val proposals: List<String>, val nextCursor: String?)

    private class ReplayTransport(private val fail: Boolean = false) : InboxV4Transport {
        override fun pull(cursor: String?, pageSize: Int): InboxV4PullPage = error("No pull expected")
        override fun ack(ack: InboxV4Ack): InboxV4DeliveryResult {
            if (fail) throw IOException("synthetic offline response")
            return InboxV4DeliveryResult.Delivered
        }
    }

    companion object {
        private val NOW = Instant.parse("2027-01-15T08:00:00Z")
    }
}
