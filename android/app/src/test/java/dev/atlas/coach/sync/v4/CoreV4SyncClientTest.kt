package dev.atlas.coach.sync.v4

import dev.atlas.coach.goals.GoalMetric
import dev.atlas.coach.goals.GoalProgress
import dev.atlas.coach.goals.GoalRecord
import dev.atlas.coach.goals.GoalStatus
import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.ActivityStatus
import dev.atlas.coach.plan.DayPlan
import dev.atlas.coach.plan.LocalSchedule
import dev.atlas.coach.plan.PlanActivity
import dev.atlas.coach.plan.PlanOrigin
import dev.atlas.coach.plan.PlanState
import dev.atlas.coach.plan.PlanStatusEvent
import dev.atlas.coach.plan.Priority
import dev.atlas.coach.plan.StatusProvenance
import dev.atlas.coach.plan.StatusSource
import dev.atlas.coach.plan.TrainingPlanReference
import dev.atlas.coach.plan.TrainingReferenceSyncState
import dev.atlas.coach.sync.AndroidCoreV4SyncStore
import dev.atlas.coach.sync.CoreV4SyncPersistence
import dev.atlas.coach.usage.GlobalUsageConsent
import dev.atlas.coach.usage.UsageAppSummary
import dev.atlas.coach.usage.UsageReliability
import dev.atlas.coach.usage.UsageSummary
import dev.atlas.coach.usage.UsageSummaryCoverage
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreV4SyncClientTest {
    @Test
    fun consentOffDoesNotReachTransport() {
        val transport = FakeTransport()
        val client = CoreV4SyncClient(transport)
        val (request, body) = request(client, CoreV4Category.DAY_PLAN, planData())

        listOf(CoreV4ConsentState.UNKNOWN, CoreV4ConsentState.REVOKED).forEach { state ->
            val failure = assertThrows(CoreV4SyncException::class.java) {
                client.sendProjection(
                    request,
                    body,
                    TOKEN,
                    CoreV4ConsentSnapshot(CoreV4Category.DAY_PLAN, 1, state),
                )
            }
            assertEquals(CoreV4FailureKind.CONSENT, failure.kind)
        }

        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun allFiveProjectionSchemasAreValidatedAndSentToTheirFixedRoutes() {
        val transport = FakeTransport()
        val client = CoreV4SyncClient(transport)
        val before = approvedPlan()
        val events = listOf(statusEvent())
        val titlelessPlan = CoreV4SyncClient.planData(before, events, observedAt = NOW)
        val status = CoreV4SyncClient.statusData(events.single(), before.version, sequence = 1)
        val usage = CoreV4SyncClient.usageData(
            usageSummary(),
            GlobalUsageConsent.grant(1_000L, "synthetic local test", consentVersion = 3),
            v4ConsentVersion = 1,
        )
        val goals = CoreV4SyncClient.goalData(
            listOf(goal()),
            mapOf(GOAL_ID to GoalProgress(GOAL_ID, GoalMetric.MANUAL, 0, 1)),
            version = 1,
            observedAt = NOW,
        )
        val training = CoreV4SyncClient.trainingData(listOf(trainingReference()), NOW)
        val payloads = listOf(
            CoreV4Category.DAY_PLAN to titlelessPlan,
            CoreV4Category.ACTIVITY_STATUS to status,
            CoreV4Category.USAGE_SUMMARY to usage,
            CoreV4Category.GOAL_PROGRESS to goals,
            CoreV4Category.TRAINING_PLAN to training,
        )

        payloads.forEach { (category, data) ->
            val (request, body) = request(client, category, data)
            val receipt = client.sendProjection(
                request,
                body,
                TOKEN,
                granted(category),
                dayPlanConsent = if (category == CoreV4Category.ACTIVITY_STATUS) {
                    granted(CoreV4Category.DAY_PLAN)
                } else null,
            )
            assertEquals(category, receipt.category)
        }

        assertEquals(CoreV4Category.entries.map { it.route }, transport.requests.map { it.path })
        assertEquals(before, approvedPlan())
        val allBodies = transport.requests.joinToString("\n") { it.body }
        assertFalse(allBodies.contains("synthetic private title"))
        assertFalse(allBodies.contains("synthetic private note"))
        assertFalse(allBodies.contains("private application label"))
        assertFalse(allBodies.contains(TOKEN))
        assertFalse(transport.requests.joinToString().contains(TOKEN))
        assertEquals("Focus", titlelessPlan.getJSONArray("activities").getJSONObject(0).getString("title"))
        assertEquals("Objectif 1", goals.getJSONArray("goals").getJSONObject(0).getString("title"))
        assertEquals(JSONObject.NULL, usage.getJSONArray("apps").getJSONObject(0).get("display_name"))
        assertEquals(7, training.getJSONArray("routines").getJSONObject(0).getInt("routine_id"))
    }

    @Test
    fun retryReplaysTheExactBodyAndStableIdempotencyKey() {
        val transport = FakeTransport().apply { duplicate = true }
        val client = CoreV4SyncClient(transport)
        val (request, body) = request(client, CoreV4Category.DAY_PLAN, planData())
        val consent = granted(CoreV4Category.DAY_PLAN)

        val first = client.sendProjection(request, body, TOKEN, consent)
        val second = client.sendProjection(request, body, TOKEN, consent)

        assertEquals("DUPLICATE", first.resultCode)
        assertTrue(second.replayed)
        assertEquals(transport.requests[0].body, transport.requests[1].body)
        assertEquals(request.idempotencyKey, JSONObject(transport.requests[1].body).getString("idempotency_key"))
    }

    @Test
    fun persistentStoreReplaysExactProjectionAfterRecreationAndConsentPrecedesData() {
        val persistence = MemoryV4Persistence()
        val store = AndroidCoreV4SyncStore(persistence)
        val grantedRecord = store.setConsent(CoreV4Category.DAY_PLAN, granted = true)
        val transport = FakeTransport()
        val client = CoreV4SyncClient(transport)

        syncPendingCoreV4ConsentsFirst(store, client, TOKEN)
        val consent = store.consent(CoreV4Category.DAY_PLAN).snapshot
        assertEquals(grantedRecord.snapshot.version, consent.syncedVersion)

        val data = planData()
        val fingerprint = "1".repeat(64)
        val firstStoreRequest = store.prepareProjection(
            sourceKey = "plan:$PLAN_ID:consent:${consent.version}",
            sourceVersion = 1,
            sourceFingerprint = fingerprint,
            category = CoreV4Category.DAY_PLAN,
            consentVersion = consent.version,
            data = data,
            client = client,
            nowUtc = NOW,
        ) ?: error("expected first persistent request")

        val recreatedStore = AndroidCoreV4SyncStore(persistence)
        val replay = recreatedStore.prepareProjection(
            sourceKey = "plan:$PLAN_ID:consent:${consent.version}",
            sourceVersion = 1,
            sourceFingerprint = fingerprint,
            category = CoreV4Category.DAY_PLAN,
            consentVersion = consent.version,
            data = data.put("observed_at", "2026-10-29T12:01:00Z"),
            client = client,
            nowUtc = "2026-10-29T12:01:00Z",
        ) ?: error("expected exact pending request replay")

        assertEquals(firstStoreRequest.body, replay.body)
        assertEquals(firstStoreRequest.request.idempotencyKey, replay.request.idempotencyKey)
        val currentConsent = recreatedStore.consent(CoreV4Category.DAY_PLAN).snapshot
        assertTrue(coreV4RequestStillAuthorized(
            recreatedStore,
            CoreV4HttpRequest(CoreV4Category.DAY_PLAN.route, TOKEN, replay.body),
        ))
        val receipt = client.sendProjection(replay.request, replay.body, TOKEN, currentConsent)
        recreatedStore.acknowledgeProjection(replay, receipt, NOW)
        assertEquals(
            listOf(CoreV4SyncClient.CONSENT_ROUTE, CoreV4Category.DAY_PLAN.route),
            transport.requests.map { it.path },
        )
        recreatedStore.setConsent(CoreV4Category.DAY_PLAN, granted = false)
        assertFalse(coreV4RequestStillAuthorized(
            recreatedStore,
            CoreV4HttpRequest(CoreV4Category.DAY_PLAN.route, TOKEN, replay.body),
        ))
    }

    @Test
    fun offlineStatusIsResumedBeforePlanRevisionAndSequenceStaysContiguous() {
        val persistence = MemoryV4Persistence()
        val store = AndroidCoreV4SyncStore(persistence)
        val transport = FakeTransport()
        val client = CoreV4SyncClient(transport)
        val dayConsent = granted(CoreV4Category.DAY_PLAN)
        val statusConsent = granted(CoreV4Category.ACTIVITY_STATUS)

        val initialPlan = store.prepareProjection(
            sourceKey = "plan:$PLAN_ID:consent:1",
            sourceVersion = 1,
            sourceFingerprint = "2".repeat(64),
            category = CoreV4Category.DAY_PLAN,
            consentVersion = 1,
            data = planData(),
            client = client,
            nowUtc = NOW,
        ) ?: error("expected initial plan")
        val planReceipt = client.sendProjection(initialPlan.request, initialPlan.body, TOKEN, dayConsent)
        store.acknowledgeProjection(initialPlan, planReceipt, NOW)

        val firstEvent = statusEvent()
        val firstSequence = store.sequenceForStatusEvent(firstEvent.statusEventId)
        val firstStatus = CoreV4SyncClient.statusData(firstEvent, planVersion = 1, sequence = firstSequence)
        val firstPending = store.prepareProjection(
            sourceKey = "status:${firstEvent.statusEventId}",
            sourceVersion = firstSequence.toInt(),
            sourceFingerprint = "3".repeat(64),
            category = CoreV4Category.ACTIVITY_STATUS,
            consentVersion = statusConsent.version,
            data = firstStatus,
            client = client,
            nowUtc = NOW,
        ) ?: error("expected first status")

        transport.offline = true
        val offline = assertThrows(CoreV4SyncException::class.java) {
            client.sendProjection(firstPending.request, firstPending.body, TOKEN, statusConsent, dayConsent)
        }
        assertEquals(CoreV4FailureKind.OFFLINE, offline.kind)

        transport.offline = false
        val revisedPlanVersion = 2
        assertTrue(flushSupersededStatusRequestsBeforePlan(
            store,
            client,
            TOKEN,
            PLAN_ID,
            revisedPlanVersion,
            statusConsent,
            dayConsent,
        ))
        assertTrue(store.pendingProjections(CoreV4Category.ACTIVITY_STATUS).isEmpty())

        val revisedPlan = approvedPlan().copy(version = revisedPlanVersion)
        val revisedData = CoreV4SyncClient.planData(revisedPlan, emptyList(), observedAt = "2026-10-29T12:02:00Z")
        val revisedPending = store.prepareProjection(
            sourceKey = "plan:$PLAN_ID:consent:1",
            sourceVersion = revisedPlanVersion,
            sourceFingerprint = "4".repeat(64),
            category = CoreV4Category.DAY_PLAN,
            consentVersion = dayConsent.version,
            data = revisedData,
            client = client,
            nowUtc = "2026-10-29T12:02:00Z",
        ) ?: error("expected revised plan")
        val revisedReceipt = client.sendProjection(revisedPending.request, revisedPending.body, TOKEN, dayConsent)
        store.acknowledgeProjection(revisedPending, revisedReceipt, "2026-10-29T12:02:00Z")

        val secondEvent = firstEvent.copy(
            statusEventId = EVENT_ID_2,
            fromStatus = ActivityStatus.IN_PROGRESS,
            toStatus = ActivityStatus.DONE,
            planVersion = revisedPlanVersion,
            decidedAt = "2026-10-29T12:03:00Z",
            observedAt = "2026-10-29T12:03:00Z",
        )
        val secondSequence = store.sequenceForStatusEvent(secondEvent.statusEventId)
        assertEquals(2L, secondSequence)
        val secondData = CoreV4SyncClient.statusData(secondEvent, revisedPlanVersion, secondSequence)
        val secondPending = store.prepareProjection(
            sourceKey = "status:${secondEvent.statusEventId}",
            sourceVersion = secondSequence.toInt(),
            sourceFingerprint = "5".repeat(64),
            category = CoreV4Category.ACTIVITY_STATUS,
            consentVersion = statusConsent.version,
            data = secondData,
            client = client,
            nowUtc = "2026-10-29T12:03:00Z",
        ) ?: error("expected second status")
        client.sendProjection(secondPending.request, secondPending.body, TOKEN, statusConsent, dayConsent)

        val resumedPaths = transport.requests.drop(2).map { it.path }
        assertEquals(
            listOf(
                CoreV4Category.ACTIVITY_STATUS.route,
                CoreV4Category.DAY_PLAN.route,
                CoreV4Category.ACTIVITY_STATUS.route,
            ),
            resumedPaths,
        )
        val acceptedSequences = transport.requests
            .filter { it.path == CoreV4Category.ACTIVITY_STATUS.route }
            .drop(1)
            .map { JSONObject(it.body).getJSONObject("data").getInt("sequence") }
        assertEquals(listOf(1, 2), acceptedSequences)
    }

    @Test
    fun transportCancellationWinsBeforeRegistrationAndCancelsRegisteredConnection() {
        val beforeRegistration = CoreV4TransportStartGate()
        val cancelledBefore = AtomicBoolean(false)
        beforeRegistration.cancel()
        val lateConnection = CoreV4CancelableConnection { cancelledBefore.set(true) }
        assertFalse(beforeRegistration.register(lateConnection))
        assertTrue(cancelledBefore.get())

        val afterRegistration = CoreV4TransportStartGate()
        val registered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val cancelledAfter = AtomicBoolean(false)
        val connection = CoreV4CancelableConnection { cancelledAfter.set(true) }
        val registeredSuccessfully = AtomicBoolean(false)
        val startAllowed = AtomicBoolean(true)
        val sender = Thread {
            registeredSuccessfully.set(afterRegistration.register(connection))
            registered.countDown()
            if (releaseStart.await(2, TimeUnit.SECONDS)) {
                startAllowed.set(afterRegistration.mayStart(connection))
            }
        }
        sender.start()
        assertTrue(registered.await(2, TimeUnit.SECONDS))
        afterRegistration.cancel()
        releaseStart.countDown()
        sender.join(2_000)

        assertFalse(sender.isAlive)
        assertTrue(registeredSuccessfully.get())
        assertTrue(cancelledAfter.get())
        assertFalse(startAllowed.get())
    }

    @Test
    fun transportStartBarrierRejectsRevocationBeforeAndAfterConnectionRegistration() {
        val store = AndroidCoreV4SyncStore(MemoryV4Persistence())
        val transport = FakeTransport()
        val client = CoreV4SyncClient(transport)
        store.setConsent(CoreV4Category.DAY_PLAN, granted = true)
        syncPendingCoreV4ConsentsFirst(store, client, TOKEN)
        val firstConsent = store.consent(CoreV4Category.DAY_PLAN).snapshot
        val firstPending = store.prepareProjection(
            sourceKey = "plan:$PLAN_ID:consent:${firstConsent.version}",
            sourceVersion = 1,
            sourceFingerprint = "6".repeat(64),
            category = CoreV4Category.DAY_PLAN,
            consentVersion = firstConsent.version,
            data = planData(),
            client = client,
            nowUtc = NOW,
        ) ?: error("expected first plan projection")
        val firstRequest = CoreV4HttpRequest(
            CoreV4Category.DAY_PLAN.route,
            TOKEN,
            firstPending.body,
        )

        // Model revocation after the pre-open consent read but before registration/start.
        assertTrue(coreV4RequestStillAuthorized(store, firstRequest))
        store.setConsent(CoreV4Category.DAY_PLAN, granted = false)
        val beforeRegistrationGate = CoreV4TransportStartGate()
        val beforeRegistrationConnection = CoreV4CancelableConnection { }
        assertTrue(beforeRegistrationGate.register(beforeRegistrationConnection))
        val startedBeforeRegistration = AtomicBoolean(false)
        assertTrue(beforeRegistrationGate.start(beforeRegistrationConnection) {
            startedBeforeRegistration.set(startCoreV4TransportIfAuthorized(store, firstRequest, { false }) {})
        })
        assertFalse(startedBeforeRegistration.get())

        // Re-grant, register the connection, then revoke before the start callback runs.
        store.setConsent(CoreV4Category.DAY_PLAN, granted = true)
        syncPendingCoreV4ConsentsFirst(store, client, TOKEN)
        val renewedConsent = store.consent(CoreV4Category.DAY_PLAN).snapshot
        val renewedPending = store.prepareProjection(
            sourceKey = "plan:$PLAN_ID:consent:${renewedConsent.version}",
            sourceVersion = 1,
            sourceFingerprint = "7".repeat(64),
            category = CoreV4Category.DAY_PLAN,
            consentVersion = renewedConsent.version,
            data = planData(),
            client = client,
            nowUtc = NOW,
        ) ?: error("expected renewed plan projection")
        val renewedRequest = CoreV4HttpRequest(
            CoreV4Category.DAY_PLAN.route,
            TOKEN,
            renewedPending.body,
        )
        val gate = CoreV4TransportStartGate()
        val connectionCancelled = AtomicBoolean(false)
        val connection = CoreV4CancelableConnection { connectionCancelled.set(true) }
        assertTrue(gate.register(connection))
        val registered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val startAttempted = AtomicBoolean(false)
        val requestStarted = AtomicBoolean(false)
        val sender = Thread {
            registered.countDown()
            if (releaseStart.await(2, TimeUnit.SECONDS)) {
                gate.start(connection) {
                    startAttempted.set(true)
                    requestStarted.set(startCoreV4TransportIfAuthorized(
                        store,
                        renewedRequest,
                        { false },
                    ) {})
                }
            }
        }
        sender.start()
        assertTrue(registered.await(2, TimeUnit.SECONDS))
        store.setConsent(CoreV4Category.DAY_PLAN, granted = false)
        releaseStart.countDown()
        sender.join(2_000)

        assertFalse(sender.isAlive)
        assertTrue(startAttempted.get())
        assertFalse(requestStarted.get())
        assertFalse(connectionCancelled.get())
    }

    @Test
    fun consentReceiptIsValidatedAndAnIdempotencyKeyCannotChangeItsBody() {
        val transport = FakeTransport()
        val client = CoreV4SyncClient(transport)
        val body = client.consentBody(
            CoreV4Category.DAY_PLAN,
            version = 2,
            state = CoreV4ConsentState.GRANTED,
            requestId = UUID_A,
            idempotencyKey = UUID_B,
            sentAt = NOW,
        )
        val receipt = client.sendConsent(body, TOKEN)
        assertEquals(CoreV4Category.DAY_PLAN, receipt.category)
        assertEquals(CoreV4ConsentState.GRANTED, receipt.state)
        assertEquals("ACCEPTED", receipt.resultCode)

        val (firstRequest, firstBody) = request(client, CoreV4Category.DAY_PLAN, planData())
        client.sendProjection(
            firstRequest,
            firstBody,
            TOKEN,
            granted(CoreV4Category.DAY_PLAN),
        )
        val changedData = planData().put("observed_at", "2026-10-29T12:01:00Z")
        val changedRequest = firstRequest.copy(data = changedData)
        val changedBody = client.projectionBody(changedRequest)
        val failure = assertThrows(CoreV4SyncException::class.java) {
            client.sendProjection(
                changedRequest,
                changedBody,
                TOKEN,
                granted(CoreV4Category.DAY_PLAN),
            )
        }
        assertEquals(CoreV4FailureKind.IDEMPOTENCY_CONFLICT, failure.kind)
    }

    @Test
    fun offlineIsTypedRetryableAndKeepsCredentialsOutOfErrors() {
        val transport = FakeTransport().apply { offline = true }
        val client = CoreV4SyncClient(transport)
        val (request, body) = request(client, CoreV4Category.DAY_PLAN, planData())
        val failure = assertThrows(CoreV4SyncException::class.java) {
            client.sendProjection(
                request, body, TOKEN,
                granted(CoreV4Category.DAY_PLAN),
            )
        }

        assertEquals(CoreV4FailureKind.OFFLINE, failure.kind)
        assertTrue(failure.retryable)
        assertFalse(failure.message.orEmpty().contains(TOKEN))
    }

    @Test
    fun httpErrorsMapToTypedRetryPolicy() {
        val expected = listOf(
            401 to (CoreV4FailureKind.AUTHENTICATION to false),
            403 to (CoreV4FailureKind.CONSENT to false),
            409 to (CoreV4FailureKind.CONFLICT to false),
            422 to (CoreV4FailureKind.REJECTED to false),
            503 to (CoreV4FailureKind.UNAVAILABLE to true),
        )
        expected.forEach { (status, mapping) ->
            val transport = FakeTransport().apply { failureStatus = status }
            val client = CoreV4SyncClient(transport)
            val (request, body) = request(client, CoreV4Category.DAY_PLAN, planData())

            val failure = assertThrows(CoreV4SyncException::class.java) {
                client.sendProjection(
                    request, body, TOKEN,
                    granted(CoreV4Category.DAY_PLAN),
                )
            }

            assertEquals(mapping.first, failure.kind)
            assertEquals(mapping.second, failure.retryable)
            assertFalse(failure.message.orEmpty().contains(TOKEN))
        }
    }

    @Test
    fun stalePlanVersionsMapToConflictAndNeverRewriteTheLocalPlan() {
        val transport = FakeTransport().apply { failureStatus = 409 }
        val client = CoreV4SyncClient(transport)
        val plan = approvedPlan()
        val original = plan.copy()
        val data = CoreV4SyncClient.planData(plan, emptyList(), observedAt = NOW)
        val (request, body) = request(client, CoreV4Category.DAY_PLAN, data)

        val failure = assertThrows(CoreV4SyncException::class.java) {
            client.sendProjection(
                request, body, TOKEN,
                granted(CoreV4Category.DAY_PLAN),
            )
        }

        assertEquals(CoreV4FailureKind.CONFLICT, failure.kind)
        assertEquals(original, plan)
    }

    @Test
    fun incompatibleGoalAndTrainingIdsAreRejectedInsteadOfReplaced() {
        val badGoal = goal().copy(id = "goal-local-1")
        val goalFailure = assertThrows(CoreV4SyncException::class.java) {
            CoreV4SyncClient.goalData(listOf(badGoal), emptyMap(), 1, NOW)
        }
        assertEquals(CoreV4FailureKind.PROTOCOL, goalFailure.kind)

        val badTraining = CoreV4SyncClient.trainingData(listOf(trainingReference()), NOW)
        badTraining.getJSONArray("routines").getJSONObject(0).put("routine_id", 0)
        val trainingFailure = assertThrows(CoreV4SyncException::class.java) {
            CoreV4SyncClient.validateProjectionData(CoreV4Category.TRAINING_PLAN, badTraining)
        }
        assertEquals(CoreV4FailureKind.PROTOCOL, trainingFailure.kind)

        val unavailableTraining = trainingReference().copy(syncState = TrainingReferenceSyncState.UNAVAILABLE)
        val unavailableFailure = assertThrows(CoreV4SyncException::class.java) {
            CoreV4SyncClient.trainingData(listOf(unavailableTraining), NOW)
        }
        assertEquals(CoreV4FailureKind.UNAVAILABLE, unavailableFailure.kind)
    }

    @Test
    fun backendOriginRejectsFreeCleartextHostsAndUrlsWithCredentials() {
        assertTrue(CoreV4SyncClient.validBackendUrl("https://core.example", debuggable = false))
        assertFalse(CoreV4SyncClient.validBackendUrl("http://core.example", debuggable = true))
        assertTrue(CoreV4SyncClient.validBackendUrl("http://10.0.2.2:8000", debuggable = true))
        assertFalse(CoreV4SyncClient.validBackendUrl("https://user:secret@core.example", debuggable = true))
    }

    private fun request(
        client: CoreV4SyncClient,
        category: CoreV4Category,
        data: JSONObject,
    ): Pair<CoreV4ProjectionRequest, String> {
        val request = CoreV4ProjectionRequest(
            category = category,
            consentVersion = 1,
            requestId = UUID_A,
            idempotencyKey = UUID_B.dropLast(1) + (category.ordinal + 2),
            sentAt = NOW,
            data = data,
        )
        return request to client.projectionBody(request)
    }

    private fun granted(category: CoreV4Category) =
        CoreV4ConsentSnapshot(category, 1, CoreV4ConsentState.GRANTED, syncedVersion = 1)

    private class FakeTransport : CoreV4HttpTransport {
        val requests = mutableListOf<CoreV4HttpRequest>()
        var failureStatus: Int? = null
        var duplicate: Boolean = false
        var offline: Boolean = false

        override fun post(request: CoreV4HttpRequest): CoreV4HttpResponse {
            requests += request
            if (offline) throw IOException("synthetic offline")
            failureStatus?.let { status ->
                return CoreV4HttpResponse(status, """{"error":{"code":"STALE_VERSION","message":"$TOKEN"}}""")
            }
            val envelope = JSONObject(request.body)
            val capability = envelope.getString("capability")
            val base = JSONObject()
                .put("contract_version", 4)
                .put("schema_version", 4)
                .put("request_id", envelope.getString("request_id"))
                .put("capability", capability)
            if (capability == CoreV4SyncClient.CONSENT_CAPABILITY) {
                val data = envelope
                return CoreV4HttpResponse(200, base
                    .put("category", data.getString("category"))
                    .put("consent_version", data.getInt("consent_version"))
                    .put("state", data.getString("state"))
                    .put("updated_at", NOW)
                    .put("source", "ANDROID_SYNC")
                    .put("replayed", duplicate)
                    .put("result_code", if (duplicate) "DUPLICATE" else "ACCEPTED")
                    .toString())
            }
            val data = envelope.getJSONObject("data")
            if (capability == CoreV4Category.ACTIVITY_STATUS.capability) {
                return CoreV4HttpResponse(200, base.put("items", org.json.JSONArray().put(JSONObject()
                    .put("event_id", data.getString("event_id"))
                    .put("result_code", if (duplicate) "DUPLICATE" else "ACCEPTED")
                    .put("last_synced_at", NOW))).toString())
            }
            val storedVersion = when (capability) {
                CoreV4Category.DAY_PLAN.capability -> data.getInt("version")
                CoreV4Category.GOAL_PROGRESS.capability -> data.getInt("goals_version")
                CoreV4Category.USAGE_SUMMARY.capability,
                CoreV4Category.TRAINING_PLAN.capability,
                -> envelope.getInt("consent_version")
                else -> error("unexpected fixed capability")
            }
            return CoreV4HttpResponse(200, base
                .put("accepted", true)
                .put("replayed", duplicate)
                .put("stored_version", storedVersion)
                .put("snapshot_hash", "sha256:" + "a".repeat(64))
                .put("last_synced_at", NOW)
                .put("result_code", if (duplicate) "DUPLICATE" else "ACCEPTED")
                .toString())
        }
    }

    companion object {
        private const val TOKEN = "synthetic-secret-token"
        private const val NOW = "2026-10-29T12:00:00Z"
        private const val UUID_A = "10000000-0000-4000-8000-000000000001"
        private const val UUID_B = "10000000-0000-4000-8000-000000000002"
        private const val PLAN_ID = "20000000-0000-4000-8000-000000000001"
        private const val REVISION_ID = "30000000-0000-4000-8000-000000000001"
        private const val ACTIVITY_ID = "40000000-0000-4000-8000-000000000001"
        private const val EVENT_ID = "50000000-0000-4000-8000-000000000001"
        private const val EVENT_ID_2 = "50000000-0000-4000-8000-000000000002"
        private const val GOAL_ID = "60000000-0000-4000-8000-000000000001"

        private fun approvedPlan(): DayPlan {
            val planId = PLAN_ID
            val activity = PlanActivity(
                activityId = ACTIVITY_ID,
                planId = planId,
                kind = ActivityKind.FOCUS,
                title = "synthetic private title",
                status = ActivityStatus.IN_PROGRESS,
                scheduledLocal = LocalSchedule("2026-10-29", 600, 25, "UTC"),
                priority = Priority.IMPORTANT,
                createdRevisionId = REVISION_ID,
                lastRevisionId = REVISION_ID,
                notes = "synthetic private note",
            )
            return DayPlan(
                planId = planId,
                localDate = "2026-10-29",
                timezone = "UTC",
                version = 1,
                currentRevisionId = REVISION_ID,
                state = PlanState.APPROVED,
                activities = listOf(activity),
                createdAt = 1_798_000_000_000L,
                updatedAt = 1_798_000_000_100L,
                approvedAt = 1_798_000_000_100L,
                origin = PlanOrigin.LOCAL,
            )
        }

        private fun statusEvent() = PlanStatusEvent(
            statusEventId = EVENT_ID,
            planId = PLAN_ID,
            activityId = ACTIVITY_ID,
            continuityId = null,
            fromStatus = ActivityStatus.PLANNED,
            toStatus = ActivityStatus.IN_PROGRESS,
            provenance = StatusProvenance.DECLARED,
            source = StatusSource.USER_UI,
            decidedAt = NOW,
            observedAt = NOW,
            planVersion = 1,
            consentVersion = 1,
        )

        private fun planData(): JSONObject =
            CoreV4SyncClient.planData(approvedPlan(), emptyList(), observedAt = NOW)

        private fun usageSummary() = UsageSummary(
            windowStart = 1_798_000_000_000L,
            windowEnd = 1_798_003_600_000L,
            timezone = "UTC",
            totalForegroundMs = 10_000L,
            apps = listOf(UsageAppSummary(
                appRef = "app:0123456789abcdef01234567",
                displayName = "private application label",
                foregroundMs = 10_000L,
                launchCount = 1,
                lastUsedAtUtc = 1_798_003_000_000L,
                reliability = UsageReliability.HIGH,
            )),
            coverage = UsageSummaryCoverage(10_000L, 0L, 2, false),
            generatedAt = 1_798_003_600_000L,
            consentVersion = 3,
        )

        private fun goal() = GoalRecord(
            id = GOAL_ID,
            title = "synthetic private title",
            description = "synthetic private description",
            metric = GoalMetric.MANUAL,
            targetValue = 1,
            status = GoalStatus.ACTIVE,
            createdAt = 1_798_000_000_000L,
            updatedAt = 1_798_000_000_000L,
        )

        private fun trainingReference() = TrainingPlanReference(
            baseUrl = "https://wger.example",
            routineId = 7,
            dayId = 21,
            slotId = 2,
            planId = PLAN_ID,
            activityId = ACTIVITY_ID,
            fetchedAt = NOW,
            sourceUpdatedAt = NOW,
            syncState = TrainingReferenceSyncState.FRESH,
        )
    }

    private class MemoryV4Persistence : CoreV4SyncPersistence {
        private var state: String? = null

        override fun read(): String? = state

        override fun write(value: String) {
            state = value
        }
    }
}
