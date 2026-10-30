package dev.atlas.coach.e2e

import dev.atlas.coach.app.A1PlanNotificationPublisher
import dev.atlas.coach.app.PlanController
import dev.atlas.coach.app.V4_CHAT_DEFAULT_ENABLED
import dev.atlas.coach.app.relayChatCapabilities
import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.ActivityStatus
import dev.atlas.coach.plan.A1ActivityClass
import dev.atlas.coach.plan.A1ActivityPolicy
import dev.atlas.coach.plan.A1ExternalLinkState
import dev.atlas.coach.plan.A1PolicyRejection
import dev.atlas.coach.plan.DayPlan
import dev.atlas.coach.plan.LocalSchedule
import dev.atlas.coach.plan.PlanActivity
import dev.atlas.coach.plan.PlanClock
import dev.atlas.coach.plan.PlanOrigin
import dev.atlas.coach.plan.PlanState
import dev.atlas.coach.plan.Priority
import dev.atlas.coach.plan.StatusProvenance
import dev.atlas.coach.plan.StatusSource
import dev.atlas.coach.plan.v5.PlanV5JsonCodec
import dev.atlas.coach.sync.AndroidCoreV4SyncStore
import dev.atlas.coach.sync.CoreV4SyncPersistence
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.context.AgentContextDomainConsent
import dev.atlas.coach.sync.agent.context.AgentContextSnapshot
import dev.atlas.coach.sync.agent.coach.AgentChatException
import dev.atlas.coach.sync.agent.coach.AgentChatFailureKind
import dev.atlas.coach.sync.agent.coach.AgentChatV4Sharing
import dev.atlas.coach.sync.agent.coach.AgentRequestContext
import dev.atlas.coach.sync.agent.coach.RelayAgentChatClient
import dev.atlas.coach.sync.agent.coach.RelayChatRequestExecutor
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4Ack
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4AckDeliveryState
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4AckState
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4ConfirmationMode
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4DeliveryResult
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4Fixtures
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4PullPage
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4Repository
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4Transport
import dev.atlas.coach.sync.agent.inbox.v4.InMemoryInboxV4Storage
import dev.atlas.coach.sync.relay.RelayCallResult
import dev.atlas.coach.sync.v4.CoreV4Category
import dev.atlas.coach.sync.v4.CoreV4ConsentState
import dev.atlas.coach.sync.v4.CoreV4FailureKind
import dev.atlas.coach.sync.v4.CoreV4HttpRequest
import dev.atlas.coach.sync.v4.CoreV4HttpResponse
import dev.atlas.coach.sync.v4.CoreV4HttpTransport
import dev.atlas.coach.sync.v4.CoreV4ProjectionRequest
import dev.atlas.coach.sync.v4.CoreV4SyncClient
import dev.atlas.coach.sync.v4.CoreV4SyncException
import dev.atlas.coach.sync.v4.pushApprovedCoreV4PlanAndStatuses
import dev.atlas.coach.sync.v4.syncPendingCoreV4ConsentsFirst
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class E2eV63Test {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun relayChatV4CarriesConsentAndProposalIdsAndNeverFallsBackAfterV4Error() {
        assertFalse(V4_CHAT_DEFAULT_ENABLED)
        assertEquals(setOf("plan.revision", "coach.chat"), relayChatCapabilities(v4ChatEnabled = false))
        assertEquals(setOf("plan.revision", "v4.coach.chat"), relayChatCapabilities(v4ChatEnabled = true))

        val now = System.currentTimeMillis()
        val mandate = AgentMandate.default()
        val snapshot = AgentContextSnapshot(emptyList(), emptyMap(), emptyMap())
        val requestContext = AgentRequestContext(
            mandate = mandate,
            snapshot = snapshot,
            consentAtUtcMillis = emptyMap(),
            domainConsent = AgentContextDomainConsent(),
            nowUtcMillis = now,
        )
        val sharing = AgentChatV4Sharing(
            consentVersion = 4,
            sharedCategories = setOf("DAY_PLAN", "GOAL_PROGRESS"),
        )
        val proposalId = "00000000-0000-4000-8000-000000000701"
        val operations = mutableListOf<String>()
        val payloads = mutableListOf<JSONObject>()
        val executor = RelayChatRequestExecutor { operation, _, _, payloadFactory, beforeDispatch ->
            operations += operation
            assertTrue(beforeDispatch())
            payloads += payloadFactory()
            if (operations.size == 1) {
                RelayCallResult.Success(JSONObject()
                    .put("contract_version", 4)
                    .put("schema_version", 4)
                    .put("request_id", "00000000-0000-4000-8000-000000000702")
                    .put("reply", "Proposition prête à revoir.")
                    .put("proposal_ids", JSONArray().put(proposalId))
                    .put("agent_status", "ok")
                    .put("tool_state", "completed")
                    .put("tool_call_count", 1), replayed = false)
            } else {
                RelayCallResult.RemoteError("OPERATION_UNAVAILABLE", "synthetic", retryable = false)
            }
        }
        val client = RelayAgentChatClient(
            executor = executor,
            freshRequestContext = { requestContext },
            onContextWritten = { true },
            v4ChatEnabled = true,
            v4Sharing = { sharing },
            v4SharingStillAuthorized = { it == sharing },
        )

        val response = client.chat("synthetic-conversation", "synthetic request", mandate, snapshot,
            "00000000-0000-4000-8000-000000000702")
        assertEquals("connected", response.status.wireValue)
        assertTrue(response.reply.contains("État des outils : completed (1 appel(s))"))
        assertTrue(response.reply.contains(proposalId))
        assertEquals("v4.coach.chat.request", operations.single())
        val requestBody = payloads.single()
        assertEquals(4, requestBody.getInt("contract_version"))
        assertEquals(4, requestBody.getInt("consent_version"))
        assertEquals("coach.chat", requestBody.getString("capability"))
        assertEquals(setOf("DAY_PLAN", "GOAL_PROGRESS"),
            (0 until requestBody.getJSONArray("shared_categories").length())
                .map { requestBody.getJSONArray("shared_categories").getString(it) }.toSet())
        assertFalse(requestBody.has("shared_context"))
        assertFalse(requestBody.has("mandate_summary"))

        val failure = assertThrows(AgentChatException::class.java) {
            client.chat("synthetic-conversation", "second synthetic request", mandate, snapshot,
                "00000000-0000-4000-8000-000000000703")
        }
        assertEquals(AgentChatFailureKind.OPERATION_UNAVAILABLE, failure.kind)
        assertEquals(listOf("v4.coach.chat.request", "v4.coach.chat.request"), operations)
        assertEquals(2, payloads.size)
    }

    @Test
    fun syncPushOrdersConsentPlanAndStatusAndReportsReplayAndTypedRejection() {
        val transport = FakeCoreV4Transport()
        val client = CoreV4SyncClient(transport)
        val store = AndroidCoreV4SyncStore(MemoryCoreV4Persistence())
        store.setConsent(CoreV4Category.DAY_PLAN, granted = true)
        store.setConsent(CoreV4Category.ACTIVITY_STATUS, granted = true)
        syncPendingCoreV4ConsentsFirst(store, client, TOKEN)

        val plan = syntheticPlan()
        val event = syntheticStatusEvent(plan)
        val planConsent = store.consent(CoreV4Category.DAY_PLAN).snapshot
        val statusConsent = store.consent(CoreV4Category.ACTIVITY_STATUS).snapshot
        assertTrue(pushApprovedCoreV4PlanAndStatuses(
            store = store,
            client = client,
            token = TOKEN,
            plan = plan,
            events = listOf(event),
            now = NOW,
            planConsent = planConsent,
            statusConsent = statusConsent,
        ))

        assertEquals(
            listOf(
                CoreV4SyncClient.CONSENT_ROUTE,
                CoreV4SyncClient.CONSENT_ROUTE,
                CoreV4Category.DAY_PLAN.route,
                CoreV4Category.ACTIVITY_STATUS.route,
            ),
            transport.requests.map { it.path },
        )
        val planRequest = transport.requests.first { it.path == CoreV4Category.DAY_PLAN.route }
        assertFalse(planRequest.body.contains("Synthetic private activity title"))
        val planEnvelope = JSONObject(planRequest.body)
        val replayRequest = CoreV4ProjectionRequest(
            category = CoreV4Category.DAY_PLAN,
            consentVersion = planEnvelope.getInt("consent_version"),
            requestId = planEnvelope.getString("request_id"),
            idempotencyKey = planEnvelope.getString("idempotency_key"),
            sentAt = planEnvelope.getString("sent_at"),
            data = planEnvelope.getJSONObject("data"),
        )
        val replay = client.sendProjection(
            replayRequest,
            planRequest.body,
            TOKEN,
            store.consent(CoreV4Category.DAY_PLAN).snapshot,
        )
        assertTrue(replay.replayed)
        assertEquals(planEnvelope.getString("idempotency_key"),
            JSONObject(transport.requests.last().body).getString("idempotency_key"))

        val alteredRequest = replayRequest.copy(data = JSONObject(replayRequest.data.toString())
            .put("observed_at", "2026-10-29T12:01:00Z"))
        val conflict = assertThrows(CoreV4SyncException::class.java) {
            client.sendProjection(alteredRequest, client.projectionBody(alteredRequest), TOKEN,
                store.consent(CoreV4Category.DAY_PLAN).snapshot)
        }
        assertEquals(CoreV4FailureKind.IDEMPOTENCY_CONFLICT, conflict.kind)
        val requestsBeforeRevocation = transport.requests.size
        store.setConsent(CoreV4Category.DAY_PLAN, granted = false)
        val consentFailure = assertThrows(CoreV4SyncException::class.java) {
            client.sendProjection(replayRequest, planRequest.body, TOKEN,
                store.consent(CoreV4Category.DAY_PLAN).snapshot)
        }
        assertEquals(CoreV4FailureKind.CONSENT, consentFailure.kind)
        assertEquals(requestsBeforeRevocation, transport.requests.size)
    }

    @Test
    fun inboxAndA1FlowKeepConsentReviewProofDedupUndoQuotaAndNeutralNotification() {
        val now = Instant.parse(NOW)
        val date = LocalDate.parse("2026-10-29")
        val inbox = InboxV4Repository(InMemoryInboxV4Storage(), { now })
        val directory = temporaryFolder.newFolder("atlas-v63-a1")
        val controller = PlanController(
            directory = directory,
            clock = PlanClock { now.toEpochMilli() },
            zoneIdProvider = { ZoneId.of("UTC") },
            todayProvider = { date },
            inboxRepository = inbox,
        )
        assertTrue(controller.createTodayPlan())
        assertTrue(controller.addActivity("Activité synthétique", ActivityKind.FOCUS, 540, 45, Priority.IMPORTANT))
        assertTrue(controller.confirmPreview())
        val initialPlan = requireNotNull(controller.plan)
        val activityId = initialPlan.activities.single().activityId
        assertTrue(controller.setA1Consent(true))
        assertTrue(controller.a1Consent()?.enabled == true)
        assertEquals(1, controller.a1Consent()?.version)
        assertTrue(controller.setA1ActivityPolicy(A1ActivityPolicy(
            activityId = activityId,
            activityClass = A1ActivityClass.READING,
            unlocked = false,
            externalLinkState = A1ExternalLinkState.NONE,
        )))

        val a0 = proposal(801, InboxV4ConfirmationMode.A0_USER_REVIEW, initialPlan, activityId)
        val a1 = proposal(802, InboxV4ConfirmationMode.A1_ANDROID_ELIGIBLE_TO_CHECK, initialPlan, activityId)
        val a2 = proposal(803, InboxV4ConfirmationMode.A2_USER_CONFIRMATION, initialPlan, activityId)
        val pullTransport = FakeInboxTransport(listOf(
            InboxV4PullPage(listOf(a0, a1, a2), "synthetic-cursor-2", true),
            InboxV4PullPage(listOf(a1), null, false),
        ))
        val firstPage = inbox.pullNextPage(pullTransport, 8)
        assertEquals(3, firstPage.added)
        assertEquals("synthetic-cursor-2", inbox.cursor())
        val secondPage = inbox.pullNextPage(pullTransport, 8)
        assertEquals(1, secondPage.duplicates)
        assertNull(inbox.cursor())
        assertEquals(listOf(null, "synthetic-cursor-2"), pullTransport.pullCursors)
        assertEquals(
            setOf(
                InboxV4ConfirmationMode.A0_USER_REVIEW,
                InboxV4ConfirmationMode.A1_ANDROID_ELIGIBLE_TO_CHECK,
                InboxV4ConfirmationMode.A2_USER_CONFIRMATION,
            ),
            inbox.all().map { it.proposal.confirmationMode }.toSet(),
        )
        assertEquals(initialPlan, controller.plan)

        val review = controller.inspectA1InboxProposal(a1.proposalId)
        assertEquals(A1PolicyRejection.ACTIVITY_LOCKED, review?.rejection)
        assertFalse(controller.applyInboxProposal(
            proposalId = a1.proposalId,
            planRevisionCapabilityEnabled = false,
            dayPlanConsentGranted = false,
            dayPlanConsentVersion = 0,
            a1CapabilityEnabled = { true },
            currentDayPlanConsent = { false to 0L },
        ))
        assertEquals(initialPlan, controller.plan)
        assertTrue(inbox.ackHistory(a1.proposalId).isEmpty())

        assertTrue(controller.setA1ActivityPolicy(A1ActivityPolicy(
            activityId = activityId,
            activityClass = A1ActivityClass.READING,
            unlocked = true,
            externalLinkState = A1ExternalLinkState.NONE,
        )))
        assertTrue(controller.applyInboxProposal(
            proposalId = a1.proposalId,
            planRevisionCapabilityEnabled = false,
            dayPlanConsentGranted = true,
            dayPlanConsentVersion = 3,
            a1CapabilityEnabled = { true },
            currentDayPlanConsent = { true to 3L },
        ))
        val firstApplied = requireNotNull(controller.plan)
        assertEquals(initialPlan.version + 1, firstApplied.version)
        assertEquals(660, firstApplied.activities.single().scheduledLocal.startMinute)
        val firstAck = inbox.ackHistory(a1.proposalId).single()
        assertEquals(InboxV4AckState.APPLIED, firstAck.state)
        assertNotNull(firstAck.proof)
        assertEquals(firstApplied.version.toLong(), firstAck.proof!!.appliedPlanVersion)
        assertEquals(firstApplied.currentRevisionId, firstAck.proof!!.revisionId)
        assertFalse(controller.applyInboxProposal(
            proposalId = a1.proposalId,
            planRevisionCapabilityEnabled = false,
            dayPlanConsentGranted = true,
            dayPlanConsentVersion = 3,
            a1CapabilityEnabled = { true },
            currentDayPlanConsent = { true to 3L },
        ))
        assertEquals(firstApplied.version, controller.plan!!.version)

        val staleProposal = proposal(806, InboxV4ConfirmationMode.A1_ANDROID_ELIGIBLE_TO_CHECK,
            initialPlan, activityId, targetStart = 720)
        inbox.mergePulled(InboxV4PullPage(listOf(staleProposal), null, false), 8)
        assertTrue("stale proposal rejection is durably acknowledged", controller.applyInboxProposal(
            proposalId = staleProposal.proposalId,
            planRevisionCapabilityEnabled = false,
            dayPlanConsentGranted = true,
            dayPlanConsentVersion = 3,
            a1CapabilityEnabled = { true },
            currentDayPlanConsent = { true to 3L },
        ))
        assertEquals(firstApplied, controller.plan)
        assertEquals(InboxV4AckState.STALE_VERSION, inbox.ackHistory(staleProposal.proposalId).single().state)

        assertTrue(controller.undoA1(a1.proposalId))
        assertEquals(initialPlan.version + 2, controller.plan!!.version)
        assertEquals(540, controller.plan!!.activities.single().scheduledLocal.startMinute)
        var persisted = PlanV5JsonCodec.decodeStore(
            File(directory, "day-plan-${date}.json").readText(),
        )
        assertEquals(1, persisted.a1State.quotaByLocalDate.values.single())

        assertTrue(controller.rejectInboxProposal(a0.proposalId, consentVersion = 3))
        assertTrue(controller.rejectInboxProposal(a2.proposalId, consentVersion = 3))
        assertEquals(InboxV4AckState.REJECTED, inbox.ackHistory(a0.proposalId).single().state)
        assertNull(inbox.ackHistory(a0.proposalId).single().proof)
        assertEquals(InboxV4AckState.REJECTED, inbox.ackHistory(a2.proposalId).single().state)
        assertEquals(initialPlan.version + 2, controller.plan!!.version)

        val secondProposal = proposal(804, InboxV4ConfirmationMode.A1_ANDROID_ELIGIBLE_TO_CHECK,
            requireNotNull(controller.plan), activityId, targetStart = 660)
        inbox.mergePulled(InboxV4PullPage(listOf(secondProposal), null, false), 8)
        assertTrue(controller.applyInboxProposal(
            proposalId = secondProposal.proposalId,
            planRevisionCapabilityEnabled = false,
            dayPlanConsentGranted = true,
            dayPlanConsentVersion = 3,
            a1CapabilityEnabled = { true },
            currentDayPlanConsent = { true to 3L },
        ))
        val secondApplied = requireNotNull(controller.plan)
        val thirdProposal = proposal(805, InboxV4ConfirmationMode.A1_ANDROID_ELIGIBLE_TO_CHECK,
            secondApplied, activityId, targetStart = 780)
        inbox.mergePulled(InboxV4PullPage(listOf(thirdProposal), null, false), 8)
        assertFalse(controller.applyInboxProposal(
            proposalId = thirdProposal.proposalId,
            planRevisionCapabilityEnabled = false,
            dayPlanConsentGranted = true,
            dayPlanConsentVersion = 3,
            a1CapabilityEnabled = { true },
            currentDayPlanConsent = { true to 3L },
        ))
        assertEquals(secondApplied, controller.plan)
        assertEquals(A1PolicyRejection.QUOTA_EXCEEDED,
            controller.inspectA1InboxProposal(thirdProposal.proposalId)?.rejection)
        persisted = PlanV5JsonCodec.decodeStore(File(directory, "day-plan-${date}.json").readText())
        assertEquals(2, persisted.a1State.quotaByLocalDate.values.single())
        assertEquals(InboxV4AckState.APPLIED, inbox.ackHistory(secondProposal.proposalId).single().state)
        assertEquals(InboxV4AckState.VALIDATION_REJECTED, inbox.ackHistory(thirdProposal.proposalId).single().state)

        val pendingIds = inbox.pendingAcks().map { it.ack.ackId }
        assertTrue(pendingIds.isNotEmpty())
        val ackTransport = AckReplayTransport(pendingIds.size)
        val offline = inbox.replayPendingAcks(ackTransport)
        assertEquals(pendingIds.size, offline.stillPending)
        assertTrue(inbox.pendingAcks().all { it.deliveryState == InboxV4AckDeliveryState.PENDING })
        val uncertain = inbox.replayPendingAcks(ackTransport)
        assertEquals(pendingIds.size, uncertain.uncertain)
        assertTrue(inbox.pendingAcks().all { it.deliveryState == InboxV4AckDeliveryState.UNCERTAIN })
        val delivered = inbox.replayPendingAcks(ackTransport)
        assertEquals(pendingIds.size, delivered.delivered)
        assertTrue(inbox.pendingAcks().isEmpty())
        assertEquals(pendingIds + pendingIds + pendingIds, ackTransport.ackIds)

        val lockScreen = A1PlanNotificationPublisher.lockScreenContent()
        assertEquals("Programme mis à jour", lockScreen.title)
        assertEquals("Mise à jour disponible", lockScreen.publicBody)
        assertFalse(lockScreen.title.contains("Activité synthétique"))
        assertFalse(lockScreen.body.contains(activityId))
        assertFalse(lockScreen.body.contains(a1.proposalId))
    }

    private fun proposal(
        number: Int,
        mode: InboxV4ConfirmationMode,
        plan: DayPlan,
        activityId: String,
        targetStart: Int = 660,
    ) = InboxV4Fixtures.proposal(
        proposalId = testUuid(number),
        idempotencyKey = testUuid(number + 100),
        expiresAt = Instant.parse(NOW).plusSeconds(29 * 60),
    ).copy(
        createdAt = Instant.parse(NOW),
        expiresAt = Instant.parse(NOW).plusSeconds(29 * 60),
        planId = plan.planId,
        baseVersion = plan.version.toLong(),
        justification = "Proposition synthétique à confirmer.",
        changes = listOf(mapOf(
            "op" to "reschedule",
            "activity_id" to activityId,
            "target_start_minute" to targetStart,
            "duration_minutes" to plan.activities.single().scheduledLocal.durationMinutes,
        )),
        confirmationMode = mode,
    )

    private fun testUuid(number: Int): String =
        "90000000-0000-4000-8000-${number.toString().padStart(12, '0')}"

    private class MemoryCoreV4Persistence : CoreV4SyncPersistence {
        private val raw = AtomicReference<String?>(null)
        override fun read(): String? = raw.get()
        override fun write(value: String) { raw.set(value) }
    }

    private class FakeCoreV4Transport : CoreV4HttpTransport {
        val requests = mutableListOf<CoreV4HttpRequest>()
        private val bodiesByKey = mutableMapOf<String, String>()

        override fun post(request: CoreV4HttpRequest): CoreV4HttpResponse {
            requests += request
            val envelope = JSONObject(request.body)
            val key = envelope.getString("idempotency_key")
            val previous = bodiesByKey.putIfAbsent(key, request.body)
            if (previous != null && previous != request.body) {
                return error(409, "IDEMPOTENCY_CONFLICT")
            }
            val duplicate = previous != null
            val capability = envelope.getString("capability")
            val result = JSONObject()
                .put("contract_version", 4)
                .put("schema_version", 4)
                .put("request_id", envelope.getString("request_id"))
                .put("capability", capability)
            if (request.path == CoreV4SyncClient.CONSENT_ROUTE) {
                return CoreV4HttpResponse(200, result
                    .put("category", envelope.getString("category"))
                    .put("consent_version", envelope.getInt("consent_version"))
                    .put("state", envelope.getString("state"))
                    .put("updated_at", NOW)
                    .put("source", "ANDROID_SYNC")
                    .put("replayed", duplicate)
                    .put("result_code", if (duplicate) "DUPLICATE" else "ACCEPTED")
                    .toString())
            }
            val data = envelope.getJSONObject("data")
            if (request.path == CoreV4Category.ACTIVITY_STATUS.route) {
                return CoreV4HttpResponse(200, result.put("items", JSONArray().put(JSONObject()
                    .put("event_id", data.getString("event_id"))
                    .put("result_code", if (duplicate) "DUPLICATE" else "ACCEPTED")
                    .put("last_synced_at", NOW))).toString())
            }
            val storedVersion = when (request.path) {
                CoreV4Category.DAY_PLAN.route -> data.getInt("version")
                CoreV4Category.GOAL_PROGRESS.route -> data.getInt("goals_version")
                else -> envelope.getInt("consent_version")
            }
            return CoreV4HttpResponse(200, result
                .put("accepted", true)
                .put("replayed", duplicate)
                .put("stored_version", storedVersion)
                .put("snapshot_hash", "sha256:" + "a".repeat(64))
                .put("last_synced_at", NOW)
                .put("result_code", if (duplicate) "DUPLICATE" else "ACCEPTED")
                .toString())
        }

        private fun error(status: Int, code: String) = CoreV4HttpResponse(status,
            JSONObject().put("error", JSONObject().put("code", code)).toString())
    }

    private class FakeInboxTransport(pages: List<InboxV4PullPage>) : InboxV4Transport {
        private val queuedPages = ArrayDeque(pages)
        val pullCursors = mutableListOf<String?>()
        override fun pull(cursor: String?, pageSize: Int): InboxV4PullPage {
            pullCursors += cursor
            return queuedPages.removeFirst()
        }
        override fun ack(ack: InboxV4Ack): InboxV4DeliveryResult = InboxV4DeliveryResult.Delivered
    }

    private class AckReplayTransport(private val pendingCount: Int) : InboxV4Transport {
        private var phase = 0
        private var inPhase = 0
        val ackIds = mutableListOf<String>()
        override fun pull(cursor: String?, pageSize: Int): InboxV4PullPage =
            error("Inbox replay must not pull proposals")

        override fun ack(ack: InboxV4Ack): InboxV4DeliveryResult {
            ackIds += ack.ackId
            val result = when (phase) {
                0 -> InboxV4DeliveryResult.Offline
                1 -> InboxV4DeliveryResult.Uncertain
                else -> InboxV4DeliveryResult.Delivered
            }
            inPhase++
            if (inPhase == pendingCount) {
                inPhase = 0
                phase++
            }
            return result
        }
    }

    private fun syntheticPlan(): DayPlan {
        val planId = "20000000-0000-4000-8000-000000000701"
        val revisionId = "30000000-0000-4000-8000-000000000701"
        val activityId = "40000000-0000-4000-8000-000000000701"
        return DayPlan(
            planId = planId,
            localDate = "2026-10-29",
            timezone = "UTC",
            version = 1,
            currentRevisionId = revisionId,
            state = PlanState.APPROVED,
            activities = listOf(PlanActivity(
                activityId = activityId,
                planId = planId,
                kind = ActivityKind.FOCUS,
                title = "Synthetic private activity title",
                status = ActivityStatus.IN_PROGRESS,
                scheduledLocal = LocalSchedule("2026-10-29", 600, 25, "UTC"),
                priority = Priority.IMPORTANT,
                createdRevisionId = revisionId,
                lastRevisionId = revisionId,
            )),
            createdAt = 1_798_000_000_000L,
            updatedAt = 1_798_000_000_100L,
            approvedAt = 1_798_000_000_100L,
            origin = PlanOrigin.LOCAL,
        )
    }

    private fun syntheticStatusEvent(plan: DayPlan) = dev.atlas.coach.plan.PlanStatusEvent(
        statusEventId = "50000000-0000-4000-8000-000000000701",
        planId = plan.planId,
        activityId = plan.activities.single().activityId,
        continuityId = null,
        fromStatus = ActivityStatus.PLANNED,
        toStatus = ActivityStatus.IN_PROGRESS,
        provenance = StatusProvenance.DECLARED,
        source = StatusSource.USER_UI,
        decidedAt = NOW,
        observedAt = NOW,
        planVersion = plan.version,
        consentVersion = 1,
    )

    companion object {
        private const val TOKEN = "synthetic-v63-sync-token"
        private const val NOW = "2026-10-29T08:10:00Z"
    }
}
