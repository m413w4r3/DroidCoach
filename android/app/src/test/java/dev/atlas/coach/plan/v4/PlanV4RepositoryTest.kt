package dev.atlas.coach.plan.v4

import dev.atlas.coach.plan.ActivityStatus
import dev.atlas.coach.plan.DayPlanRepository
import dev.atlas.coach.plan.MemoryPlanBackend
import dev.atlas.coach.plan.NOW
import dev.atlas.coach.plan.PLAN_ID
import dev.atlas.coach.plan.PlanApplyResult
import dev.atlas.coach.plan.PlanChange
import dev.atlas.coach.plan.PlanClock
import dev.atlas.coach.plan.PlanConfirmation
import dev.atlas.coach.plan.PlanJsonCodec
import dev.atlas.coach.plan.PlanLoadResult
import dev.atlas.coach.plan.PlanQuarantineMetadata
import dev.atlas.coach.plan.PlanReportResult
import dev.atlas.coach.plan.PlanRevision
import dev.atlas.coach.plan.PlanRevisionEngine
import dev.atlas.coach.plan.PlanStatusEvent
import dev.atlas.coach.plan.PlanStatusPolicy
import dev.atlas.coach.plan.PlanStateBackend
import dev.atlas.coach.plan.PlanStoreDocument
import dev.atlas.coach.plan.ReportActivityRequest
import dev.atlas.coach.plan.RevisionOrigin
import dev.atlas.coach.plan.StatusEventDraft
import dev.atlas.coach.plan.StatusProvenance
import dev.atlas.coach.plan.StatusSource
import dev.atlas.coach.plan.activity
import dev.atlas.coach.plan.confirmationFor
import dev.atlas.coach.plan.dayPlan
import dev.atlas.coach.plan.revisionFor
import dev.atlas.coach.plan.schedule
import java.time.Instant
import java.io.IOException
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanV4RepositoryTest {
    @Test
    fun schema3DocumentRemainsReadableUntilAWriteIsRequested() {
        val originalPlan = dayPlan()
        val rawV3 = PlanJsonCodec.encodeStore(PlanStoreDocument(originalPlan))
        val backend = MemoryPlanBackend(rawV3)
        val repository = DayPlanRepository(backend, PlanClock { NOW })

        assertEquals(PlanLoadResult.Loaded(originalPlan), repository.load())
        assertEquals(rawV3, backend.value)
        assertEquals(0, backend.writes)
    }

    @Test
    fun v4RoundTripIsStableAndKeepsASchema3RollbackProjection() {
        val continuity = "60000000-0000-4000-8000-000000000001"
        val plan = dayPlan(activities = listOf(activity().copy(continuityId = continuity)))
        val event = PlanStatusEvent(
            statusEventId = "70000000-0000-4000-8000-000000000001",
            planId = PLAN_ID,
            activityId = plan.activities.single().activityId,
            continuityId = continuity,
            fromStatus = ActivityStatus.PLANNED,
            toStatus = ActivityStatus.DONE,
            provenance = StatusProvenance.CONFIRMED,
            source = StatusSource.USER_UI,
            decidedAt = Instant.ofEpochMilli(NOW).toString(),
            observedAt = Instant.ofEpochMilli(NOW).toString(),
            planVersion = plan.version,
            consentVersion = 2,
        )
        val rawV3 = PlanJsonCodec.encodeStore(PlanStoreDocument(dayPlan()))
        val unknownEvent = event.copy(
            statusEventId = "70000000-0000-4000-8000-000000000002",
            provenance = StatusProvenance.UNKNOWN,
            source = StatusSource.CORE_IMPORT,
        )
        val document = PlanV4StoreDocument(
            store = PlanStoreDocument(plan),
            statusEvents = listOf(event, unknownEvent),
            schema3Original = rawV3,
        )

        val encoded = PlanV4JsonCodec.encodeStore(document)
        val decoded = PlanV4JsonCodec.decodeStore(encoded)

        assertEquals(plan, decoded.store.dayPlan)
        assertEquals(listOf(event, unknownEvent), decoded.statusEvents)
        assertEquals(rawV3, decoded.schema3Original)
        assertEquals(encoded, PlanV4JsonCodec.encodeStore(decoded))
        val rollback = PlanV4JsonCodec.schema3RollbackDocument(encoded)
        assertEquals(3, JSONObject(rollback).getInt("schema_version"))
        val rollbackPlan = plan.copy(activities = plan.activities.map { it.copy(continuityId = null) })
        assertEquals(rollbackPlan, PlanJsonCodec.decodeStore(rollback).dayPlan)
        assertEquals(PlanLoadResult.Loaded(rollbackPlan), DayPlanRepository(MemoryPlanBackend(rollback), PlanClock { NOW }).load())
    }

    @Test
    fun unknownSchemaIsQuarantinedWithoutReplacingItsOriginalBytes() {
        val future = "{\"schema_version\":99,\"future_payload\":{\"opaque\":true}}"
        val backend = MemoryPlanBackend(future)
        val repository = DayPlanRepository(backend, PlanClock { NOW })

        assertTrue(repository.load() is PlanLoadResult.Corrupt)
        assertEquals(future, backend.value)
        assertEquals(future, backend.quarantinedRaw)
        assertEquals(0, backend.writes)
        assertTrue(backend.quarantinedMetadata != null)
    }

    @Test
    fun userConfirmedDoneIsJournaledAndFocusMeasurementCannotApplyDone() {
        val current = dayPlan(activities = listOf(activity(status = ActivityStatus.IN_PROGRESS)), version = 2)
        val revision = revisionFor(current, listOf(activity(status = ActivityStatus.DONE)))
        val v3 = PlanJsonCodec.encodeStore(PlanStoreDocument(current))
        val timerBackend = MemoryPlanBackend(v3)
        val timerRepository = DayPlanRepository(timerBackend, PlanClock { NOW })
        val timerConfirmation = confirmationFor(revision)

        assertEquals(
            PlanApplyResult.Rejected(dev.atlas.coach.plan.PlanRejectionReason.INVALID_CHANGES),
            timerRepository.apply(
                revision,
                timerConfirmation,
                StatusEventDraft(StatusProvenance.MEASURED, StatusSource.FOCUS_TIMER),
            ),
        )
        assertEquals(0, timerBackend.writes)
        assertEquals(ActivityStatus.IN_PROGRESS, (timerRepository.load() as PlanLoadResult.Loaded).plan.activities.single().status)
        assertEquals(
            dev.atlas.coach.plan.StatusTransitionRejection.MEASURED_STATUS_CANNOT_TRANSITION,
            PlanStatusPolicy.provenanceRejection(
                ActivityStatus.IN_PROGRESS,
                ActivityStatus.DONE,
                StatusProvenance.MEASURED,
                StatusSource.FOCUS_TIMER,
            ),
        )
        assertEquals(
            null,
            PlanStatusPolicy.provenanceRejection(
                ActivityStatus.IN_PROGRESS,
                ActivityStatus.DONE,
                StatusProvenance.DECLARED,
                StatusSource.USER_UI,
            ),
        )

        val userBackend = MemoryPlanBackend(v3)
        val userRepository = DayPlanRepository(userBackend, PlanClock { NOW })
        val applied = userRepository.apply(
            revision,
            confirmationFor(revision),
            StatusEventDraft(StatusProvenance.CONFIRMED, StatusSource.USER_UI, consentVersion = 3),
        )
        assertTrue(applied is PlanApplyResult.Applied)
        val event = userRepository.statusEvents().single()
        assertEquals(StatusProvenance.CONFIRMED, event.provenance)
        assertEquals(StatusSource.USER_UI, event.source)
        assertEquals(ActivityStatus.IN_PROGRESS, event.fromStatus)
        assertEquals(ActivityStatus.DONE, event.toStatus)
        assertEquals(3, event.consentVersion)
        assertEquals(3, event.planVersion)
    }

    @Test
    fun missedCannotBeCommittedBeforeItsSlotEndsEvenWithAUserEventDraft() {
        val beforeSlotEnd = Instant.parse("2026-10-26T06:00:00Z").toEpochMilli()
        val current = dayPlan(activities = listOf(activity(scheduled = schedule(9 * 60, 60))))
        val revision = revisionFor(
            current,
            listOf(activity(status = ActivityStatus.MISSED, scheduled = schedule(9 * 60, 60))),
            createdAt = beforeSlotEnd - 1_000L,
        )
        val backend = MemoryPlanBackend(PlanJsonCodec.encodeStore(PlanStoreDocument(current)))
        val repository = DayPlanRepository(backend, PlanClock { beforeSlotEnd })

        assertTrue(
            repository.apply(
                revision,
                confirmationFor(revision, approvedAt = beforeSlotEnd, expiresAt = beforeSlotEnd + 60_000L),
                StatusEventDraft(StatusProvenance.DECLARED, StatusSource.USER_UI),
            ) is PlanApplyResult.Rejected,
        )
        assertEquals(0, backend.writes)
        assertEquals(ActivityStatus.PLANNED, (repository.load() as PlanLoadResult.Loaded).plan.activities.single().status)
    }

    @Test
    fun sameDayReportIsOneRevisionWithTwoEffectsAndAnIdempotentReplay() {
        val backend = MemoryPlanBackend(PlanJsonCodec.encodeStore(PlanStoreDocument(dayPlan())))
        val repository = DayPlanRepository(backend, PlanClock { NOW })
        val request = reportRequest()

        val result = repository.reportActivity(request) as PlanReportResult.Reported
        assertEquals(1, backend.writes)
        assertEquals(2, result.revision.changes.size)
        assertTrue(result.revision.changes[0] is PlanChange.StatusChanged)
        assertTrue(result.revision.changes[1] is PlanChange.SlotAdded)
        assertEquals(2, result.effectIds.size)
        val old = result.plan.activities.single { it.activityId == request.activityId }
        assertEquals(ActivityStatus.POSTPONED, old.status)
        assertEquals(result.replacement.activityId, result.plan.activities.last { it.activityId != old.activityId }.activityId)
        assertEquals(old.continuityId, result.replacement.continuityId)
        assertEquals(old.activityId, result.replacement.supersedesActivityId)
        assertEquals(ActivityStatus.PLANNED, result.replacement.status)
        assertEquals(StatusProvenance.CONFIRMED, result.statusEvent.provenance)
        assertEquals(2, result.statusEvent.planVersion)
        assertEquals(request.reason, PlanV4JsonCodec.decodeStore(backend.value!!).reportReceipts.single().reason)

        val replay = repository.reportActivity(request) as PlanReportResult.Replayed
        assertEquals(result.revision.revisionId, replay.revisionId)
        assertEquals(result.replacement.activityId, replay.replacementActivityId)
        assertEquals(result.scopeHash, replay.scopeHash)
        assertEquals(
            PlanReportResult.Rejected(dev.atlas.coach.plan.PlanReportRejection.IDEMPOTENCY_CONFLICT),
            repository.reportActivity(request.copy(reason = "Autre motif")),
        )
        assertEquals(1, backend.writes)
        val restarted = DayPlanRepository(backend, PlanClock { NOW })
        assertEquals(result.plan, (restarted.load() as PlanLoadResult.Loaded).plan)
        assertEquals(old.continuityId, restarted.load().let { (it as PlanLoadResult.Loaded).plan.activities.first().continuityId })
    }

    @Test
    fun failedReportWriteLeavesTheSchema3DocumentUntouched() {
        val originalPlan = dayPlan()
        val rawV3 = PlanJsonCodec.encodeStore(PlanStoreDocument(originalPlan))
        val backend = FailingWriteBackend(rawV3)
        val repository = DayPlanRepository(backend, PlanClock { NOW })

        assertEquals(
            PlanReportResult.Rejected(dev.atlas.coach.plan.PlanReportRejection.STORAGE_FAILURE),
            repository.reportActivity(reportRequest()),
        )
        assertEquals(1, backend.writeAttempts)
        assertEquals(rawV3, backend.value)
        assertEquals(PlanLoadResult.Loaded(originalPlan), repository.load())
    }

    @Test
    fun staleConflictTerminalAndCrossDateReportsHaveNoPartialWrite() {
        fun attempt(plan: dev.atlas.coach.plan.DayPlan, request: ReportActivityRequest): Pair<PlanReportResult, MemoryPlanBackend> {
            val backend = MemoryPlanBackend(PlanJsonCodec.encodeStore(PlanStoreDocument(plan)))
            return DayPlanRepository(backend, PlanClock { NOW }).reportActivity(request) to backend
        }

        val base = dayPlan()
        val (stale, staleBackend) = attempt(base, reportRequest(baseVersion = 2))
        assertEquals(PlanReportResult.Rejected(dev.atlas.coach.plan.PlanReportRejection.STALE_VERSION), stale)
        assertEquals(0, staleBackend.writes)

        val crowded = dayPlan(activities = listOf(
            activity(),
            activity(id = "20000000-0000-4000-8000-000000000002", scheduled = schedule(11 * 60, 60)),
        ))
        val (conflict, conflictBackend) = attempt(crowded, reportRequest(start = 11 * 60 + 30, duration = 30))
        assertEquals(PlanReportResult.Rejected(dev.atlas.coach.plan.PlanReportRejection.CONFLICT), conflict)
        assertEquals(0, conflictBackend.writes)

        val terminalPlan = dayPlan(activities = listOf(activity(status = ActivityStatus.CANCELLED)))
        val (terminal, terminalBackend) = attempt(terminalPlan, reportRequest())
        assertEquals(PlanReportResult.Rejected(dev.atlas.coach.plan.PlanReportRejection.TERMINAL_ACTIVITY), terminal)
        assertEquals(0, terminalBackend.writes)

        val (crossDate, crossDateBackend) = attempt(base, reportRequest(date = "2026-10-27"))
        assertEquals(PlanReportResult.Rejected(dev.atlas.coach.plan.PlanReportRejection.CROSS_DATE_UNSUPPORTED), crossDate)
        assertEquals(0, crossDateBackend.writes)
    }

    @Test
    fun postponedCannotBeAppliedAsAStandaloneStatusChange() {
        val backend = MemoryPlanBackend(PlanJsonCodec.encodeStore(PlanStoreDocument(dayPlan())))
        val repository = DayPlanRepository(backend, PlanClock { NOW })
        val revision = PlanRevision(
            revisionId = "30000000-0000-4000-8000-000000000002",
            planId = PLAN_ID,
            baseVersion = 1,
            newVersion = 2,
            origin = RevisionOrigin.USER,
            changes = listOf(PlanChange.StatusChanged("20000000-0000-4000-8000-000000000001", ActivityStatus.PLANNED, ActivityStatus.POSTPONED)),
            summary = "Report sans nouvelle occurrence",
            createdAt = NOW - 1_000,
            expiresAt = NOW + 60_000,
            status = dev.atlas.coach.plan.RevisionStatus.PROPOSED,
        )

        assertTrue(repository.preview(revision) is dev.atlas.coach.plan.PlanPreviewResult.Rejected)
        assertEquals(0, backend.writes)
    }

    private fun reportRequest(
        baseVersion: Int = 1,
        date: String = "2026-10-26",
        start: Int = 11 * 60,
        duration: Int = 45,
        key: String = "80000000-0000-4000-8000-000000000001",
    ) = ReportActivityRequest(
        planId = PLAN_ID,
        activityId = "20000000-0000-4000-8000-000000000001",
        baseVersion = baseVersion,
        targetLocalDate = date,
        targetStartMinute = start,
        targetDurationMinutes = duration,
        reason = "Un imprévu est arrivé",
        idempotencyKey = key,
    )

    private class FailingWriteBackend(var value: String?) : PlanStateBackend {
        var writeAttempts = 0
        override fun read(): String? = value
        override fun write(value: String) {
            writeAttempts++
            throw IOException("simulated atomic storage failure")
        }
        override fun quarantine(rawValue: String, reason: String, atUtcMillis: Long): PlanQuarantineMetadata {
            val bytes = rawValue.toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return PlanQuarantineMetadata(reason, atUtcMillis, bytes.size, digest)
        }
    }
}
