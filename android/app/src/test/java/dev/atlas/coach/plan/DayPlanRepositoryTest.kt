package dev.atlas.coach.plan

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import dev.atlas.coach.plan.v4.PlanV4JsonCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayPlanRepositoryTest {
    @Test
    fun missingCreateAndLoadedStatesAreDistinct() {
        val backend = MemoryPlanBackend()
        val repository = repository(backend)

        assertEquals(PlanLoadResult.Missing, repository.load())
        assertEquals(PlanWriteResult.Saved(dayPlan()), repository.create(dayPlan()))
        assertEquals(PlanLoadResult.Loaded(dayPlan()), repository.load())
    }

    @Test
    fun createRefusesToReplaceAnExistingPlan() {
        val repository = repository(MemoryPlanBackend())
        repository.create(dayPlan())

        val result = repository.create(dayPlan(activities = emptyList()))

        assertEquals(PlanWriteResult.Rejected(PlanRejectionReason.PLAN_ALREADY_EXISTS), result)
    }

    @Test
    fun saveRequiresStablePlanIdAndMonotoneVersion() {
        val repository = repository(MemoryPlanBackend())
        val initial = dayPlan()
        repository.create(initial)

        val changedSameVersion = initial.copy(state = PlanState.ARCHIVED)
        val changedNextVersion = initial.copy(version = 2, updatedAt = NOW)
        val replacementPlanId = "10000000-0000-4000-8000-000000000099"
        val changedPlanId = initial.copy(
            planId = replacementPlanId,
            activities = initial.activities.map { it.copy(planId = replacementPlanId) },
        )

        assertEquals(
            PlanWriteResult.Rejected(PlanRejectionReason.NON_MONOTONE_VERSION),
            repository.save(changedSameVersion),
        )
        assertEquals(
            PlanWriteResult.Rejected(PlanRejectionReason.PLAN_ID_MISMATCH),
            repository.save(changedPlanId),
        )
        assertEquals(PlanWriteResult.Saved(changedNextVersion), repository.save(changedNextVersion))
        assertEquals(
            PlanWriteResult.Rejected(PlanRejectionReason.NON_MONOTONE_VERSION),
            repository.save(initial),
        )
    }

    @Test
    fun fileBackendSurvivesRepositoryRecreation() {
        val directory = Files.createTempDirectory("atlas-plan-restart").toFile()
        try {
            val backend = FilePlanBackend(File(directory, "day-plan.json"))
            assertTrue(repository(backend).create(dayPlan()) is PlanWriteResult.Saved)

            val restarted = DayPlanRepository(backend, FixedPlanClock(NOW))

            assertEquals(PlanLoadResult.Loaded(dayPlan()), restarted.load())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun corruptDocumentIsQuarantinedWithoutReplacingRawValue() {
        val raw = "{private raw corrupt bytes}"
        val backend = MemoryPlanBackend(raw)
        val result = repository(backend).load()

        assertTrue(result is PlanLoadResult.Corrupt)
        val corrupt = result as PlanLoadResult.Corrupt
        assertNotNull(corrupt.quarantine)
        assertEquals(raw, backend.value)
        assertEquals(raw.toByteArray(Charsets.UTF_8).size, corrupt.quarantine!!.valueLength)
        assertEquals(sha256(raw), corrupt.quarantine!!.sha256)
    }

    @Test
    fun staleRevisionIsRejectedAfterPlanVersionAdvances() {
        val backend = MemoryPlanBackend()
        val repository = repository(backend)
        val current = dayPlan()
        repository.create(current)
        val stale = revisionFor(current, listOf(activity(scheduled = schedule(10 * 60))))
        repository.save(current.copy(version = 2, updatedAt = NOW, currentRevisionId = REVISION_ID))

        val result = repository.apply(stale, confirmationFor(stale))

        assertEquals(PlanApplyResult.Rejected(PlanRejectionReason.STALE_REVISION), result)
    }

    @Test
    fun applyingSameRevisionTwiceIsIdempotentAcrossRepositoryInstances() {
        val backend = MemoryPlanBackend()
        val repository = repository(backend)
        val current = dayPlan()
        repository.create(current)
        val revision = revisionFor(current, listOf(activity(scheduled = schedule(10 * 60))))
        val confirmation = confirmationFor(revision)

        assertTrue(repository.apply(revision, confirmation) is PlanApplyResult.Applied)
        val restarted = DayPlanRepository(backend, FixedPlanClock(NOW))
        val second = restarted.apply(revision, confirmation)

        assertTrue(second is PlanApplyResult.AlreadyApplied)
        assertEquals(2, (restarted.load() as PlanLoadResult.Loaded).plan.version)
        assertEquals(2, backend.writes)
        val persisted = PlanV4JsonCodec.decodeStore(backend.value!!).store
        assertEquals(NOW, persisted.confirmations.single().consumedAt)
    }

    @Test
    fun partialConfirmationIsRejected() {
        val (repository, revision) = configuredMove()
        val effects = PlanRevisionEngine().effectIds(revision.changes, revision.revisionId)
        val partial = confirmationFor(revision, effects = effects.drop(1))

        assertEquals(
            PlanApplyResult.Rejected(PlanRejectionReason.EFFECT_SCOPE_MISMATCH),
            repository.apply(revision, partial),
        )
    }

    @Test
    fun extraConfirmationEffectIsRejected() {
        val (repository, revision) = configuredMove()
        val effects = PlanRevisionEngine().effectIds(revision.changes, revision.revisionId) +
            "PLAN_ACTIVITY_DELETE:20000000-0000-4000-8000-000000000099"
        val widened = confirmationFor(revision, effects = effects)

        assertEquals(
            PlanApplyResult.Rejected(PlanRejectionReason.EFFECT_SCOPE_MISMATCH),
            repository.apply(revision, widened),
        )
    }

    @Test
    fun alteredScopeHashIsRejectedEvenWhenEffectIdsMatch() {
        val (repository, revision) = configuredMove()
        val confirmation = confirmationFor(revision).copy(scopeHash = "0".repeat(64))

        assertEquals(
            PlanApplyResult.Rejected(PlanRejectionReason.EFFECT_SCOPE_MISMATCH),
            repository.apply(revision, confirmation),
        )
    }

    @Test
    fun expiredConfirmationIsRejected() {
        val (repository, revision) = configuredMove()
        val expired = confirmationFor(revision, approvedAt = NOW - 2L, expiresAt = NOW - 1L)

        assertEquals(
            PlanApplyResult.Rejected(PlanRejectionReason.CONFIRMATION_EXPIRED),
            repository.apply(revision, expired),
        )
    }

    @Test
    fun revisionPastItsExpiryIsRejected() {
        val backend = MemoryPlanBackend()
        val current = dayPlan()
        DayPlanRepository(backend, FixedPlanClock(NOW)).create(current)
        val revision = revisionFor(
            current,
            proposed = listOf(activity(scheduled = schedule(10 * 60))),
            createdAt = NOW - 200L,
            ttlMillis = 100L,
        )
        val repositoryAtExpiry = repository(backend, now = NOW)

        assertEquals(
            PlanPreviewResult.Rejected(PlanRejectionReason.REVISION_EXPIRED),
            repositoryAtExpiry.preview(revision),
        )
    }

    @Test
    fun previewReturnsDeterministicEffectsAndOverlapWarnings() {
        val currentActivity = activity()
        val current = dayPlan(listOf(currentActivity))
        val backend = MemoryPlanBackend()
        val repository = repository(backend)
        repository.create(current)
        val moved = currentActivity.copy(scheduledLocal = schedule(9 * 60 + 30))
        val added = activity(
            id = SECOND_ACTIVITY_ID,
            scheduled = schedule(10 * 60),
        )
        val revision = revisionFor(current, listOf(moved, added))

        val result = repository.preview(revision) as PlanPreviewResult.Ready

        assertEquals(
            listOf(
                "PLAN_ACTIVITY_CREATE:$SECOND_ACTIVITY_ID",
                "PLAN_ACTIVITY_UPDATE:$ACTIVITY_ID",
                "PLAN_APPLY:$REVISION_ID",
            ),
            result.preview.effectIds,
        )
        assertEquals(1, result.preview.overlapWarnings.size)
        assertEquals(2, result.preview.after.version)
        assertEquals(PLAN_ID, result.preview.after.planId)
    }

    @Test
    fun removingDoneActivityRequiresAnExplicitSupportedChangeAndIsRefusedHere() {
        val done = activity(status = ActivityStatus.DONE)
        val current = dayPlan(listOf(done))
        val repository = repository(MemoryPlanBackend())
        repository.create(current)
        val revision = revisionFor(current, emptyList())

        assertEquals(
            PlanPreviewResult.Rejected(PlanRejectionReason.DONE_ACTIVITY_REMOVAL),
            repository.preview(revision),
        )
    }

    @Test
    fun passingTimeNeverChangesPlannedActivityToDone() {
        val oldDate = "2020-01-01"
        val oldActivity = activity(scheduled = schedule(9 * 60, date = oldDate))
        val oldPlan = dayPlan(activities = listOf(oldActivity), date = oldDate)
        val backend = MemoryPlanBackend()
        DayPlanRepository(backend, FixedPlanClock(NOW)).create(oldPlan)

        val loaded = DayPlanRepository(backend, FixedPlanClock(NOW + 1_000_000L)).load() as PlanLoadResult.Loaded

        assertEquals(ActivityStatus.PLANNED, loaded.plan.activities.single().status)
    }

    @Test
    fun atomicFileQuarantineStoresMetadataButNeverRawValue() {
        val directory = Files.createTempDirectory("atlas-plan-quarantine").toFile()
        try {
            val file = File(directory, "plan.json")
            val raw = "raw-value-must-not-be-preserved"
            file.writeText(raw)
            val metadata = FilePlanBackend(file).quarantine(raw, "invalid test document", NOW)
            val sidecars = directory.listFiles()!!.filter { it.name.startsWith("plan.json.quarantine-") }
            val sidecarContent = sidecars.single().readText()

            assertEquals(raw.toByteArray(Charsets.UTF_8).size, metadata.valueLength)
            assertEquals(sha256(raw), metadata.sha256)
            assertFalse(sidecarContent.contains(raw))
            assertTrue(sidecarContent.contains(metadata.sha256))
            assertEquals(raw, file.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedDurableWriteNeverReturnsApplied() {
        val storedBackend = MemoryPlanBackend()
        val baseRepository = repository(storedBackend)
        val current = dayPlan()
        baseRepository.create(current)
        val revision = revisionFor(current, listOf(activity(scheduled = schedule(10 * 60))))
        val failingBackend = object : PlanStateBackend {
            override fun read(): String? = storedBackend.read()
            override fun write(value: String) { throw IOException("disk full") }
            override fun quarantine(rawValue: String, reason: String, atUtcMillis: Long) =
                storedBackend.quarantine(rawValue, reason, atUtcMillis)
        }

        val result = DayPlanRepository(failingBackend, FixedPlanClock(NOW))
            .apply(revision, confirmationFor(revision))

        assertEquals(PlanApplyResult.Rejected(PlanRejectionReason.STORAGE_FAILURE), result)
        assertEquals(current, (baseRepository.load() as PlanLoadResult.Loaded).plan)
    }

    @Test
    fun alteredRevisionContentWithSameEffectIdsIsRejected() {
        val backend = MemoryPlanBackend()
        val repository = repository(backend)
        val current = dayPlan()
        repository.create(current)
        val original = revisionFor(current, listOf(activity(scheduled = schedule(10 * 60))))
        val preview = repository.preview(original) as PlanPreviewResult.Ready
        val confirmation = confirmationFor(
            original,
            effects = preview.preview.effectIds,
            scopeHash = preview.preview.scopeHash,
        )
        val altered = revisionFor(current, listOf(activity(scheduled = schedule(11 * 60))))

        assertEquals(original.revisionId, altered.revisionId)
        assertEquals(
            PlanRevisionEngine().effectIds(original.changes, original.revisionId),
            PlanRevisionEngine().effectIds(altered.changes, altered.revisionId),
        )
        assertEquals(
            PlanApplyResult.Rejected(PlanRejectionReason.EFFECT_SCOPE_MISMATCH),
            repository.apply(altered, confirmation),
        )
    }

    @Test
    fun nullAndLiteralNullNotesCannotShareAConfirmation() {
        val backend = MemoryPlanBackend()
        val repository = repository(backend)
        val current = dayPlan(listOf(activity().copy(notes = null)))
        repository.create(current)
        val original = revisionFor(
            current,
            listOf(activity(scheduled = schedule(10 * 60)).copy(notes = null)),
        )
        val preview = repository.preview(original) as PlanPreviewResult.Ready
        val confirmation = confirmationFor(
            original,
            effects = preview.preview.effectIds,
            scopeHash = preview.preview.scopeHash,
        )
        val altered = revisionFor(
            current,
            listOf(activity(scheduled = schedule(10 * 60)).copy(notes = "<null>")),
        )

        assertEquals(original.revisionId, altered.revisionId)
        assertEquals(
            PlanRevisionEngine().effectIds(original.changes, original.revisionId),
            PlanRevisionEngine().effectIds(altered.changes, altered.revisionId),
        )
        assertNotEquals(preview.preview.scopeHash, repository.preview(altered).let {
            (it as PlanPreviewResult.Ready).preview.scopeHash
        })
        assertEquals(
            PlanApplyResult.Rejected(PlanRejectionReason.EFFECT_SCOPE_MISMATCH),
            repository.apply(altered, confirmation),
        )
    }

    @Test
    fun confirmationForAnotherRevisionIsRejected() {
        val (repository, revision) = configuredMove()
        val otherRevisionConfirmation = confirmationFor(revision).copy(
            revisionId = "30000000-0000-4000-8000-000000000099",
        )

        assertEquals(
            PlanApplyResult.Rejected(PlanRejectionReason.INVALID_CONFIRMATION),
            repository.apply(revision, otherRevisionConfirmation),
        )
    }

    @Test
    fun oneConfirmationIdCannotAuthorizeTwoDifferentRevisions() {
        val backend = MemoryPlanBackend()
        val repository = repository(backend)
        val current = dayPlan()
        repository.create(current)
        val firstRevision = revisionFor(current, listOf(activity(scheduled = schedule(10 * 60))))
        assertTrue(repository.apply(firstRevision, confirmationFor(firstRevision)) is PlanApplyResult.Applied)

        val updated = (repository.load() as PlanLoadResult.Loaded).plan
        val secondRevision = revisionFor(
            updated,
            listOf(activity(scheduled = schedule(11 * 60), revisionId = firstRevision.revisionId)),
            revisionId = "30000000-0000-4000-8000-000000000002",
            createdAt = NOW,
        )
        val reusedConfirmationId = confirmationFor(secondRevision).copy(confirmationId = CONFIRMATION_ID)

        assertEquals(
            PlanApplyResult.Rejected(PlanRejectionReason.INVALID_CONFIRMATION),
            repository.apply(secondRevision, reusedConfirmationId),
        )
    }

    private fun configuredMove(): Pair<DayPlanRepository, PlanRevision> {
        val backend = MemoryPlanBackend()
        val repository = repository(backend)
        val current = dayPlan()
        repository.create(current)
        return repository to revisionFor(current, listOf(activity(scheduled = schedule(10 * 60))))
    }

    private fun repository(backend: PlanStateBackend, now: Long = NOW) =
        DayPlanRepository(backend, FixedPlanClock(now))

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
