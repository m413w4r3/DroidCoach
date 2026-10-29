package dev.atlas.coach.plan

import dev.atlas.coach.plan.v4.PlanV4JsonCodec
import dev.atlas.coach.plan.v4.PlanV4StoreDocument
import dev.atlas.coach.plan.v5.PlanV5JsonCodec
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class A1BoundedRevisionTest {
    @Test
    fun explicitAllowedClassesMoveOnlyUnlockedPlannedActivityAndStatusStaysPlanned() {
        A1ActivityClass.values().filter { it != A1ActivityClass.PROTECTED }.forEachIndexed { index, category ->
            val current = dayPlan()
            val state = allowedState(current, category = category)
            val proposal = proposal(current, moved(current, 10 * 60), number = index + 1)

            val result = A1BoundedRevisionPolicy.validate(current, proposal, state, NOW)

            assertTrue("$category should be eligible", result is A1ValidationResult.Valid)
        }
        val current = dayPlan()
        val backend = MemoryPlanBackend()
        val repository = repository(backend)
        configure(repository, current)
        val applied = repository.applyA1(proposal(current, moved(current, 10 * 60)), userApproved = true)

        assertTrue(applied is A1ApplyResult.Applied)
        assertEquals(ActivityStatus.PLANNED, (applied as A1ApplyResult.Applied).plan.activities.single().status)
        assertEquals(2, applied.plan.version)
    }

    @Test
    fun movementCountAndPerActivityDistanceAreBounded() {
        val activities = listOf(
            activity(),
            activity(id = SECOND_ACTIVITY_ID, scheduled = schedule(10 * 60)),
            activity(id = THIRD_ACTIVITY_ID, scheduled = schedule(11 * 60)),
            activity(id = FOURTH_ACTIVITY_ID, scheduled = schedule(12 * 60)),
        )
        val current = dayPlan(activities = activities)
        val state = allowedState(current)

        val fourMoves = proposal(current, current.activities.map { it.copy(scheduledLocal = it.scheduledLocal.copy(startMinute = it.scheduledLocal.startMinute + 30)) })
        assertRejected(A1PolicyRejection.MOVE_LIMIT_EXCEEDED, current, fourMoves, state)

        val tooFar = proposal(current, moved(current, 9 * 60 + 121))
        assertRejected(A1PolicyRejection.MOVE_DISTANCE_EXCEEDED, current, tooFar, state)
        val atLimit = proposal(current, moved(current, 11 * 60))
        assertTrue(A1BoundedRevisionPolicy.validate(current, atLimit, state, NOW) is A1ValidationResult.Valid)

        val threeMoves = proposal(current, current.activities.map { activity ->
            if (activity.activityId == FOURTH_ACTIVITY_ID) activity
            else activity.copy(scheduledLocal = activity.scheduledLocal.copy(startMinute = activity.scheduledLocal.startMinute + 30))
        })
        assertTrue(A1BoundedRevisionPolicy.validate(current, threeMoves, state, NOW) is A1ValidationResult.Valid)
    }

    @Test
    fun validatorRejectsAddRemoveContentStatusDateTimezoneAndDurationChanges() {
        val current = dayPlan()
        val state = allowedState(current)
        assertRejected(A1PolicyRejection.ADD_OR_REMOVE_FORBIDDEN, current, proposal(current, emptyList()), state)
        assertRejected(
            A1PolicyRejection.ADD_OR_REMOVE_FORBIDDEN,
            current,
            proposal(current, listOf(current.activities.single(), activity(id = SECOND_ACTIVITY_ID))),
            state,
        )
        assertRejected(
            A1PolicyRejection.CONTENT_CHANGE_FORBIDDEN,
            current,
            proposal(current, listOf(current.activities.single().copy(title = "Titre différent"))),
            state,
        )
        assertRejected(
            A1PolicyRejection.STATUS_CHANGE_FORBIDDEN,
            current,
            proposal(current, listOf(current.activities.single().copy(status = ActivityStatus.DONE))),
            state,
        )
        assertRejected(
            A1PolicyRejection.INVALID_DATE_OR_TIMEZONE,
            current,
            proposal(current, listOf(current.activities.single().copy(scheduledLocal = schedule(10 * 60, date = "2026-10-27")))),
            state,
        )
        assertRejected(
            A1PolicyRejection.INVALID_DATE_OR_TIMEZONE,
            current,
            proposal(current, listOf(current.activities.single().copy(scheduledLocal = schedule(10 * 60, timezone = "UTC")))),
            state,
        )
        assertRejected(
            A1PolicyRejection.CONTENT_CHANGE_FORBIDDEN,
            current,
            proposal(current, listOf(current.activities.single().copy(scheduledLocal = schedule(10 * 60, durationMinutes = 61)))),
            state,
        )
    }

    @Test
    fun requiredStatusKindTrainingAndUnknownPoliciesFailClosed() {
        val cases = listOf(
            activity().copy(priority = Priority.REQUIRED) to A1PolicyRejection.REQUIRED_ACTIVITY,
            activity().copy(status = ActivityStatus.IN_PROGRESS) to A1PolicyRejection.ACTIVITY_NOT_PLANNED,
            activity().copy(kind = ActivityKind.REST) to A1PolicyRejection.PROTECTED_ACTIVITY_KIND,
            activity().copy(kind = ActivityKind.APPOINTMENT) to A1PolicyRejection.PROTECTED_ACTIVITY_KIND,
            activity().copy(kind = ActivityKind.TRAINING) to A1PolicyRejection.PROTECTED_ACTIVITY_KIND,
            activity().copy(trainingRef = trainingReference()) to A1PolicyRejection.TRAINING_LINK_FORBIDDEN,
        )
        cases.forEach { (activity, reason) ->
            val current = dayPlan(listOf(activity))
            assertRejected(reason, current, proposal(current, moved(current, 10 * 60)), allowedState(current))
        }

        val current = dayPlan()
        val missingPolicyState = A1PersistentState(consent = A1Consent(1, true, NOW))
        assertRejected(
            A1PolicyRejection.ACTIVITY_PROTECTED,
            current,
            proposal(current, moved(current, 10 * 60)),
            missingPolicyState,
        )
        val protected = allowedState(current).copy(
            activityPolicies = mapOf(ACTIVITY_ID to A1ActivityPolicy.legacyProtected(ACTIVITY_ID)),
        )
        assertRejected(A1PolicyRejection.ACTIVITY_PROTECTED, current, proposal(current, moved(current, 10 * 60)), protected)
        val locked = allowedState(current, unlocked = false)
        assertRejected(A1PolicyRejection.ACTIVITY_LOCKED, current, proposal(current, moved(current, 10 * 60)), locked)
        val unknownLink = allowedState(current, linkState = A1ExternalLinkState.UNKNOWN)
        assertRejected(A1PolicyRejection.EXTERNAL_LINK_UNKNOWN, current, proposal(current, moved(current, 10 * 60)), unknownLink)
        val linked = allowedState(current, linkState = A1ExternalLinkState.LINKED)
        assertRejected(A1PolicyRejection.EXTERNAL_LINK_FORBIDDEN, current, proposal(current, moved(current, 10 * 60)), linked)
    }

    @Test
    fun consentExpiryAndOptimisticVersionAreRevalidated() {
        val current = dayPlan()
        val proposal = proposal(current, moved(current, 10 * 60))
        assertRejected(
            A1PolicyRejection.CONSENT_DISABLED,
            current,
            proposal,
            A1PersistentState(activityPolicies = allowedState(current).activityPolicies),
        )
        assertRejected(
            A1PolicyRejection.CONSENT_VERSION_MISMATCH,
            current,
            proposal.copy(consentVersion = 2),
            allowedState(current),
        )
        assertRejected(
            A1PolicyRejection.STALE_VERSION,
            current.copy(version = 2),
            proposal,
            allowedState(current),
        )
        assertRejected(
            A1PolicyRejection.PROPOSAL_EXPIRED,
            current,
            proposal.copy(expiresAtUtcMillis = NOW),
            allowedState(current),
            NOW,
        )
        assertRejected(
            A1PolicyRejection.PROPOSAL_TTL_TOO_LONG,
            current,
            proposal.copy(expiresAtUtcMillis = proposal.createdAtUtcMillis + A1BoundedRevisionPolicy.MAX_PROPOSAL_TTL_MILLIS + 1),
            allowedState(current),
        )

        val backend = MemoryPlanBackend()
        val repository = repository(backend)
        configure(repository, current)
        assertTrue(A1BoundedRevisionPolicy.validate(
            current,
            proposal,
            PlanV5JsonCodec.decodeStore(backend.value!!).a1State,
            NOW,
        ) is A1ValidationResult.Valid)
        assertTrue(repository.setA1Consent(false) is A1SettingsResult.Updated)
        assertEquals(
            A1ApplyResult.Rejected(A1PolicyRejection.CONSENT_DISABLED),
            repository.applyA1(proposal, userApproved = true),
        )
        assertEquals(1, (repository.load() as PlanLoadResult.Loaded).plan.version)
    }

    @Test
    fun revocationObservedAtCommitBoundaryRejectsThePreparedProposal() {
        val delegate = MemoryPlanBackend()
        val setup = repository(delegate)
        val current = dayPlan()
        configure(setup, current)
        val pending = proposal(current, moved(current, 10 * 60))
        var reads = 0
        var revoked = false
        lateinit var raceRepository: DayPlanRepository
        val revokingBackend = object : PlanStateBackend {
            override fun read(): String? {
                reads++
                if (reads == 2 && !revoked) {
                    revoked = true
                    assertTrue(raceRepository.setA1Consent(false) is A1SettingsResult.Updated)
                }
                return delegate.read()
            }
            override fun write(value: String) = delegate.write(value)
            override fun quarantine(rawValue: String, reason: String, atUtcMillis: Long) =
                delegate.quarantine(rawValue, reason, atUtcMillis)
        }
        raceRepository = repository(revokingBackend)

        assertEquals(
            A1ApplyResult.Rejected(A1PolicyRejection.CONSENT_DISABLED),
            raceRepository.applyA1(pending, userApproved = true),
        )
        assertEquals(1, (setup.load() as PlanLoadResult.Loaded).plan.version)
        assertFalse(PlanV5JsonCodec.decodeStore(delegate.value!!).a1State.consent.enabled)
        assertEquals(2, PlanV5JsonCodec.decodeStore(delegate.value!!).a1State.consent.version)
    }

    @Test
    fun userRefusalCancelsWithoutWriteOrQuotaUse() {
        val backend = MemoryPlanBackend()
        val repository = repository(backend)
        val current = dayPlan()
        configure(repository, current)
        val before = backend.value
        val writes = backend.writes

        assertEquals(A1ApplyResult.Cancelled, repository.applyA1(proposal(current, moved(current, 10 * 60)), userApproved = false))

        assertEquals(before, backend.value)
        assertEquals(writes, backend.writes)
        assertTrue(PlanV5JsonCodec.decodeStore(backend.value!!).a1State.quotaByLocalDate.isEmpty())
    }

    @Test
    fun idempotentReplayReturnsReceiptButSameIdWithChangedPayloadIsRejected() {
        val backend = MemoryPlanBackend()
        val repository = repository(backend)
        val current = dayPlan()
        configure(repository, current)
        val original = proposal(current, moved(current, 10 * 60))
        val applied = repository.applyA1(original, userApproved = true) as A1ApplyResult.Applied

        assertEquals(applied.receipt, (repository.applyA1(original, userApproved = true) as A1ApplyResult.AlreadyApplied).receipt)
        assertEquals(
            A1ApplyResult.Rejected(A1PolicyRejection.IDEMPOTENCY_CONFLICT),
            repository.applyA1(original.copy(proposedActivities = moved(current, 11 * 60)), userApproved = true),
        )
        assertEquals(1, PlanV5JsonCodec.decodeStore(backend.value!!).a1State.receipts.size)
    }

    @Test
    fun localDateQuotaCapsAtTwoAndConcurrentReplayCannotOverdrawIt() {
        val backend = MemoryPlanBackend()
        val repository = repository(backend)
        var current = dayPlan()
        configure(repository, current)
        val first = proposal(current, moved(current, 10 * 60), number = 1)
        assertTrue(repository.applyA1(first, true) is A1ApplyResult.Applied)
        current = (repository.load() as PlanLoadResult.Loaded).plan
        val second = proposal(current, moved(current, 11 * 60), number = 2)
        assertTrue(repository.applyA1(second, true) is A1ApplyResult.Applied)
        current = (repository.load() as PlanLoadResult.Loaded).plan
        val third = proposal(current, moved(current, 12 * 60), number = 3)
        assertEquals(
            A1ApplyResult.Rejected(A1PolicyRejection.QUOTA_EXCEEDED),
            repository.applyA1(third, true),
        )
        val localDate = java.time.Instant.ofEpochMilli(NOW).atZone(java.time.ZoneId.of(current.timezone)).toLocalDate().toString()
        assertEquals(2, PlanV5JsonCodec.decodeStore(backend.value!!).a1State.quotaByLocalDate[localDate])

        val concurrentBackend = MemoryPlanBackend()
        val concurrentRepository = repository(concurrentBackend)
        val concurrentPlan = dayPlan()
        configure(concurrentRepository, concurrentPlan)
        val proposals = listOf(
            proposal(concurrentPlan, moved(concurrentPlan, 10 * 60), number = 11),
            proposal(concurrentPlan, moved(concurrentPlan, 11 * 60), number = 12),
        )
        val gate = CountDownLatch(1)
        val done = CountDownLatch(proposals.size)
        val results = Collections.synchronizedList(mutableListOf<A1ApplyResult>())
        proposals.forEach { item ->
            thread {
                gate.await()
                results += concurrentRepository.applyA1(item, true)
                done.countDown()
            }
        }
        gate.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, results.count { it is A1ApplyResult.Applied })
        assertEquals(1, PlanV5JsonCodec.decodeStore(concurrentBackend.value!!).a1State.receipts.size)
    }

    @Test
    fun separateRepositoriesSerializeA1CommitsForTheSameBackend() {
        val storedBackend = MemoryPlanBackend()
        val current = dayPlan()
        configure(repository(storedBackend), current)
        val concurrentWriters = CountDownLatch(2)
        val backend = object : PlanStateBackend {
            override fun read(): String? = storedBackend.read()

            override fun write(value: String) {
                concurrentWriters.countDown()
                concurrentWriters.await(1, TimeUnit.SECONDS)
                storedBackend.write(value)
            }

            override fun quarantine(rawValue: String, reason: String, atUtcMillis: Long) =
                storedBackend.quarantine(rawValue, reason, atUtcMillis)
        }
        val repositories = listOf(repository(backend), repository(backend))
        val proposals = listOf(
            proposal(current, moved(current, 10 * 60), number = 31),
            proposal(current, moved(current, 11 * 60), number = 32),
        )
        val start = CountDownLatch(1)
        val done = CountDownLatch(proposals.size)
        val results = Collections.synchronizedList(mutableListOf<A1ApplyResult>())
        proposals.forEachIndexed { index, item ->
            thread {
                start.await()
                results += repositories[index].applyA1(item, userApproved = true)
                done.countDown()
            }
        }
        val writesBeforeCommit = storedBackend.writes

        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))

        val applied = results.filterIsInstance<A1ApplyResult.Applied>().single()
        assertEquals(
            A1ApplyResult.Rejected(A1PolicyRejection.STALE_VERSION),
            results.single { it is A1ApplyResult.Rejected },
        )
        val persisted = PlanV5JsonCodec.decodeStore(storedBackend.value!!)
        assertEquals(applied.plan, persisted.store.dayPlan)
        assertEquals(listOf(applied.receipt), persisted.a1State.receipts)
        val quotaDate = java.time.Instant.ofEpochMilli(NOW)
            .atZone(java.time.ZoneId.of(current.timezone))
            .toLocalDate()
            .toString()
        assertEquals(1, persisted.a1State.quotaByLocalDate[quotaDate])
        assertEquals(writesBeforeCommit + 1, storedBackend.writes)
    }

    @Test
    fun failedStorageWriteLeavesPlanReceiptAndQuotaUntouched() {
        val backend = MemoryPlanBackend()
        val setup = repository(backend)
        val current = dayPlan()
        configure(setup, current)
        val originalBytes = backend.value
        val failing = object : PlanStateBackend {
            override fun read(): String? = backend.read()
            override fun write(value: String) { throw java.io.IOException("synthetic storage failure") }
            override fun quarantine(rawValue: String, reason: String, atUtcMillis: Long) =
                backend.quarantine(rawValue, reason, atUtcMillis)
        }

        assertEquals(
            A1ApplyResult.Rejected(A1PolicyRejection.STORAGE_FAILURE),
            repository(failing).applyA1(proposal(current, moved(current, 10 * 60)), true),
        )
        assertEquals(originalBytes, backend.value)
        assertEquals(1, (setup.load() as PlanLoadResult.Loaded).plan.version)
        assertTrue(PlanV5JsonCodec.decodeStore(backend.value!!).a1State.receipts.isEmpty())
        assertTrue(PlanV5JsonCodec.decodeStore(backend.value!!).a1State.quotaByLocalDate.isEmpty())
    }

    @Test
    fun undoCreatesAnotherVersionAndDoesNotRefundQuota() {
        val backend = MemoryPlanBackend()
        val repository = repository(backend)
        val current = dayPlan()
        configure(repository, current)
        val proposal = proposal(current, moved(current, 10 * 60))
        val applied = repository.applyA1(proposal, true) as A1ApplyResult.Applied

        val undone = repository.undoA1(proposal.proposalId) as A1UndoResult.Undone

        assertEquals(3, undone.plan.version)
        assertEquals(schedule(), undone.plan.activities.single().scheduledLocal)
        assertEquals(ActivityStatus.PLANNED, undone.plan.activities.single().status)
        assertNotEquals(applied.plan.currentRevisionId, undone.plan.currentRevisionId)
        assertEquals(1, PlanV5JsonCodec.decodeStore(backend.value!!).a1State.quotaByLocalDate.values.single())
        assertEquals(proposal.proposalId, undone.receipt.proposalId)
        assertEquals(undone.plan.version, undone.receipt.undoVersion)
    }

    @Test
    fun undoExpiresAndIsRefusedAfterAnyNewerVersion() {
        val backend = MemoryPlanBackend()
        val repository = repository(backend)
        val current = dayPlan()
        configure(repository, current)
        val proposal = proposal(current, moved(current, 10 * 60))
        assertTrue(repository.applyA1(proposal, true) is A1ApplyResult.Applied)
        val after = (repository.load() as PlanLoadResult.Loaded).plan
        val newer = after.copy(
            version = after.version + 1,
            currentRevisionId = "30000000-0000-4000-8000-000000000099",
            updatedAt = after.updatedAt + 1,
        )
        assertTrue(repository.save(newer) is PlanWriteResult.Saved)
        assertEquals(A1UndoResult.Rejected(A1UndoRejection.VERSION_CHANGED), repository.undoA1(proposal.proposalId))

        val expiredBackend = MemoryPlanBackend()
        val expiredSetup = repository(expiredBackend)
        val expiredPlan = dayPlan()
        configure(expiredSetup, expiredPlan)
        val expiringProposal = proposal(expiredPlan, moved(expiredPlan, 10 * 60), number = 2)
        val application = expiredSetup.applyA1(expiringProposal, true) as A1ApplyResult.Applied
        val afterDeadline = repository(expiredBackend, application.receipt.undoUntilUtcMillis)
        assertEquals(A1UndoResult.Rejected(A1UndoRejection.UNDO_EXPIRED), afterDeadline.undoA1(expiringProposal.proposalId))
    }

    @Test
    fun v3AndV4ReadAndLegacyActivitiesRemainProtectedWhenUpgradedToV5() {
        val plan = dayPlan()
        val rawV3 = PlanJsonCodec.encodeStore(PlanStoreDocument(plan))
        val rawV4 = PlanV4JsonCodec.encodeStore(PlanV4StoreDocument(PlanStoreDocument(plan)))
        listOf(rawV3, rawV4).forEach { raw ->
            val backend = MemoryPlanBackend(raw)
            val repository = repository(backend)
            assertEquals(PlanLoadResult.Loaded(plan), repository.load())
            assertEquals(raw, backend.value)
            assertTrue(repository.setA1Consent(true) is A1SettingsResult.Updated)
            val upgraded = PlanV5JsonCodec.decodeStore(backend.value!!)
            assertEquals(5, org.json.JSONObject(backend.value!!).getInt("schema_version"))
            assertEquals(A1ActivityClass.PROTECTED, upgraded.a1State.policyFor(ACTIVITY_ID).activityClass)
            assertFalse(upgraded.a1State.policyFor(ACTIVITY_ID).unlocked)
            assertEquals(plan, upgraded.store.dayPlan)
        }
    }

    @Test
    fun populatedV4HistorySurvivesUpgradeToV5() {
        val current = dayPlan(
            activities = listOf(
                activity(),
                activity(id = SECOND_ACTIVITY_ID, scheduled = schedule(13 * 60)),
            ),
        )
        val backend = MemoryPlanBackend()
        val repository = repository(backend)
        assertTrue(repository.create(current) is PlanWriteResult.Saved)
        val revision = PlanRevisionEngine().createRevision(
            current = current,
            proposedActivities = current.activities.map { activity ->
                if (activity.activityId == ACTIVITY_ID) {
                    activity.copy(scheduledLocal = activity.scheduledLocal.copy(startMinute = 10 * 60))
                } else {
                    activity
                }
            },
            revisionId = "30000000-0000-4000-8000-000000000021",
            origin = RevisionOrigin.USER,
            summary = "Déplacer un créneau local",
            createdAt = NOW - 1_000L,
        )
        val preview = repository.preview(revision) as PlanPreviewResult.Ready
        val confirmation = PlanConfirmation(
            confirmationId = "40000000-0000-4000-8000-000000000021",
            revisionId = revision.revisionId,
            effectIds = preview.preview.effectIds,
            scopeHash = preview.preview.scopeHash,
            approvedAt = NOW,
            expiresAt = NOW + 60_000L,
        )
        assertTrue(repository.apply(revision, confirmation) is PlanApplyResult.Applied)
        val afterRevision = (repository.load() as PlanLoadResult.Loaded).plan
        assertTrue(
            repository.reportActivity(
                ReportActivityRequest(
                    planId = current.planId,
                    activityId = SECOND_ACTIVITY_ID,
                    baseVersion = afterRevision.version,
                    targetLocalDate = current.localDate,
                    targetStartMinute = 16 * 60,
                    targetDurationMinutes = 45,
                    reason = "Imprévu synthétique pour vérifier la migration",
                    idempotencyKey = "80000000-0000-4000-8000-000000000021",
                ),
            ) is PlanReportResult.Reported,
        )

        val populatedV4 = PlanV4JsonCodec.decodeStore(backend.value!!)
        assertEquals(2, populatedV4.store.appliedRevisions.size)
        assertEquals(2, populatedV4.store.confirmations.size)
        assertEquals(1, populatedV4.statusEvents.size)
        assertEquals(1, populatedV4.reportReceipts.size)
        assertTrue(repository.setA1Consent(true) is A1SettingsResult.Updated)
        val upgradedV5 = PlanV5JsonCodec.decodeStore(backend.value!!)

        assertEquals(populatedV4.store.dayPlan, upgradedV5.store.dayPlan)
        assertEquals(populatedV4.store.appliedRevisions, upgradedV5.store.appliedRevisions)
        assertEquals(populatedV4.store.confirmations, upgradedV5.store.confirmations)
        assertEquals(populatedV4.statusEvents, upgradedV5.statusEvents)
        assertEquals(populatedV4.reportReceipts, upgradedV5.reportReceipts)
        assertEquals(populatedV4.schema3Original, upgradedV5.schema3Original)
    }

    @Test
    fun v5RejectsUnknownEnvelopeFields() {
        val backend = MemoryPlanBackend()
        val repository = repository(backend)
        configure(repository, dayPlan())
        backend.value = org.json.JSONObject(backend.value!!).put("unexpected", true).toString()

        assertTrue(repository.load() is PlanLoadResult.Corrupt)
    }

    private fun configure(repository: DayPlanRepository, current: DayPlan) {
        assertTrue(repository.create(current) is PlanWriteResult.Saved)
        assertTrue(repository.setA1Consent(true) is A1SettingsResult.Updated)
        current.activities.forEach { activity ->
            assertTrue(repository.setA1ActivityPolicy(A1ActivityPolicy(
                activityId = activity.activityId,
                activityClass = A1ActivityClass.READING,
                unlocked = true,
                externalLinkState = A1ExternalLinkState.NONE,
            )) is A1PolicyUpdateResult.Updated)
        }
    }

    private fun allowedState(
        current: DayPlan,
        category: A1ActivityClass = A1ActivityClass.READING,
        unlocked: Boolean = true,
        linkState: A1ExternalLinkState = A1ExternalLinkState.NONE,
    ) = A1PersistentState(
        consent = A1Consent(version = 1, enabled = true, updatedAtUtcMillis = NOW),
        activityPolicies = current.activities.associate { activity ->
            activity.activityId to A1ActivityPolicy(activity.activityId, category, unlocked, linkState)
        },
    )

    private fun proposal(
        current: DayPlan,
        proposed: List<PlanActivity>,
        number: Int = 1,
        createdAt: Long = NOW - 1_000,
        expiresAt: Long = NOW + A1BoundedRevisionPolicy.MAX_PROPOSAL_TTL_MILLIS - 1_000,
    ) = A1RevisionProposal(
        proposalId = uuid("60000000-0000-4000-8000-", number),
        revisionId = uuid("30000000-0000-4000-8000-", number),
        planId = current.planId,
        baseVersion = current.version,
        createdAtUtcMillis = createdAt,
        expiresAtUtcMillis = expiresAt,
        consentVersion = 1,
        proposedActivities = proposed,
    )

    private fun moved(current: DayPlan, newStartMinute: Int): List<PlanActivity> = current.activities.map { activity ->
        if (activity.activityId == ACTIVITY_ID) activity.copy(
            scheduledLocal = activity.scheduledLocal.copy(startMinute = newStartMinute),
        ) else activity
    }

    private fun assertRejected(
        reason: A1PolicyRejection,
        current: DayPlan,
        proposal: A1RevisionProposal,
        state: A1PersistentState,
        nowUtcMillis: Long = NOW,
    ) {
        assertEquals(A1ValidationResult.Rejected(reason), A1BoundedRevisionPolicy.validate(current, proposal, state, nowUtcMillis))
    }

    private fun repository(backend: PlanStateBackend, now: Long = NOW) = DayPlanRepository(backend, FixedPlanClock(now))

    private fun uuid(prefix: String, number: Int): String = prefix + number.toString().padStart(12, '0')

    private companion object {
        const val FOURTH_ACTIVITY_ID = "20000000-0000-4000-8000-000000000004"
    }
}
