package dev.atlas.coach.goals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalRepositoryTest {
    @Test
    fun absentSubdocumentMigratesToVersionedEmptyValueThroughInjectedCallbacks() {
        var saved: Map<String, Any?>? = null
        val repository = GoalRepository(readSubdocument = { null }, persistSubdocument = { saved = it })

        assertTrue(repository.all().isEmpty())
        assertEquals(GoalRepository.GOALS_SCHEMA_VERSION, saved?.get("schema_version"))
        assertEquals(emptyList<Any>(), saved?.get("goals"))

        val restored = GoalRepository(readSubdocument = { saved }, persistSubdocument = { saved = it })
        assertTrue(restored.all().isEmpty())
    }

    @Test
    fun repositoryPersistsChangesAndRejectsDuplicateIdsOrImplicitManualCompletion() {
        var saved: Map<String, Any?>? = null
        var writes = 0
        val repository = GoalRepository({ null }) {
            saved = it
            writes++
        }
        val manual = goal(metric = GoalMetric.MANUAL)
        repository.add(manual)

        assertThrows(IllegalArgumentException::class.java) { repository.add(manual) }
        assertThrows(IllegalArgumentException::class.java) {
            repository.update(manual.copy(status = GoalStatus.COMPLETED, updatedAt = 101L))
        }
        val completed = repository.confirmManualCompletion(manual.id, 101L)
        assertEquals(GoalStatus.COMPLETED, completed.status)
        assertEquals(101L, completed.updatedAt)
        assertEquals(3, writes) // empty migration, add, then explicit confirmation
        assertEquals(GoalStatus.COMPLETED.wireValue, (saved?.get("goals") as List<*>).single().goalMap()["status"])

        assertTrue(repository.remove(manual.id))
        assertFalse(repository.remove(manual.id))
        assertEquals(4, writes)
        assertNull(repository.get(manual.id))
    }

    @Test
    fun completedMetricsCanOnlyBeClosedWithMatchingCalculatedProgress() {
        var saved: Map<String, Any?>? = null
        val repository = GoalRepository({ null }) { saved = it }
        val focus = goal(metric = GoalMetric.FOCUS_MINUTES, targetValue = 25L, links = setOf("finished"))
        repository.add(focus)
        val incomplete = GoalProgressCalculator.calculate(focus)
        assertThrows(IllegalArgumentException::class.java) {
            repository.completeFromProgress(focus.id, incomplete, 102L)
        }

        val progress = GoalProgress(focus.id, GoalMetric.FOCUS_MINUTES, currentValue = 25L, targetValue = 25L)
        assertEquals(GoalStatus.COMPLETED, repository.completeFromProgress(focus.id, progress, 102L).status)
        val wrongGoal = progress.copy(goalId = "other-goal")
        assertThrows(IllegalArgumentException::class.java) {
            repository.completeFromProgress(focus.id, wrongGoal, 103L)
        }
        assertEquals(0, repository.activeGoalsForCoach().size)
        assertEquals("completed", (saved?.get("goals") as List<*>).single().goalMap()["status"])
    }

    @Test
    fun unsupportedVersionsAndDuplicatePersistedIdsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            GoalRepository(
                readSubdocument = { mapOf("schema_version" to 99, "goals" to emptyList<Any>()) },
                persistSubdocument = {},
            )
        }
        val duplicate = goal().toMap()
        assertThrows(IllegalArgumentException::class.java) {
            GoalRepository(
                readSubdocument = {
                    mapOf("schema_version" to 1, "goals" to listOf(duplicate, duplicate))
                },
                persistSubdocument = {},
            )
        }
    }

    @Test
    fun coachProjectionOffersAtMostThreeActiveGoals() {
        val repository = GoalRepository({ null }) {}
        repeat(5) { index ->
            repository.add(goal(id = "goal-$index", createdAt = index.toLong() + 1L, updatedAt = index.toLong() + 1L))
        }

        assertEquals(3, repository.activeGoalsForCoach().size)
        assertEquals(listOf("goal-0", "goal-1", "goal-2"), repository.activeGoalsForCoach().map(GoalRecord::id))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun preparedFocusLinkIsAnImmutableSnapshotAndPublicationDoesNotPersist() {
        var saved: Map<String, Any?>? = null
        var writes = 0
        val repository = GoalRepository({ null }) {
            saved = it
            writes++
        }
        val focusGoal = goal(id = "focus-goal", metric = GoalMetric.FOCUS_MINUTES, targetValue = 120L)
        repository.add(focusGoal)
        val savedBeforePreparation = saved
        val writesBeforePreparation = writes

        val prepared = repository.prepareFocusLink("focus-goal", "new-session", linkedAtUtcMillis = 250L)

        assertEquals(listOf(focusGoal), prepared.expectedRecords)
        assertEquals(setOf("new-session"), prepared.linkedGoal.linkedFocusSessionIds)
        assertEquals(1, prepared.goalsSubdocument["schema_version"])
        assertEquals(focusGoal, repository.get("focus-goal"))
        assertEquals(writesBeforePreparation, writes)
        assertEquals(savedBeforePreparation, saved)
        assertThrows(UnsupportedOperationException::class.java) {
            (prepared.goalsSubdocument["goals"] as MutableList<Any?>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (prepared.linkedGoal.linkedFocusSessionIds as MutableSet<String>).add("mutated")
        }

        val published = repository.publishCommittedFocusLink(prepared)

        assertEquals(prepared.linkedGoal, published)
        assertEquals(setOf("new-session"), repository.get("focus-goal")!!.linkedFocusSessionIds)
        assertEquals(writesBeforePreparation, writes)
        assertEquals(savedBeforePreparation, saved)
    }

    @Test
    fun focusLinkPreparationRejectsMissingInactiveWrongMetricAndConflictingGoals() {
        val repository = GoalRepository({ null }) {}
        repository.add(goal(id = "active", metric = GoalMetric.FOCUS_MINUTES, targetValue = 120L))
        repository.add(goal(id = "paused", metric = GoalMetric.FOCUS_MINUTES, targetValue = 120L).copy(status = GoalStatus.PAUSED))
        repository.add(goal(id = "manual"))
        repository.add(goal(id = "habit", metric = GoalMetric.HABIT_CHECKINS, targetValue = 7L))
        repository.add(
            goal(id = "owner", metric = GoalMetric.FOCUS_MINUTES, targetValue = 120L)
                .copy(linkedFocusSessionIds = setOf("claimed-session")),
        )

        assertThrows(IllegalArgumentException::class.java) {
            repository.prepareFocusLink("missing", "new-session", 200L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.prepareFocusLink("paused", "new-session", 200L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.prepareFocusLink("manual", "new-session", 200L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.prepareFocusLink("habit", "new-session", 200L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.prepareFocusLink("active", "claimed-session", 200L)
        }
    }

    @Test
    fun repeatedFocusLinkPreparationIsIdempotentAndExpectedRecordsAreASeparateSnapshot() {
        val repository = GoalRepository({ null }) {}
        val existing = goal(
            id = "active",
            metric = GoalMetric.FOCUS_MINUTES,
            targetValue = 120L,
            links = setOf("existing-session"),
        )
        repository.add(existing)

        val prepared = repository.prepareFocusLink("active", "existing-session", linkedAtUtcMillis = 500L)

        assertEquals(existing, prepared.linkedGoal)
        assertEquals(prepared.expectedRecords, prepared.candidateRecords)
        repository.add(goal(id = "later", metric = GoalMetric.FOCUS_MINUTES, targetValue = 120L))
        assertTrue(prepared.expectedRecords != repository.all())
    }

    private fun goal(
        id: String = "goal-1",
        metric: GoalMetric = GoalMetric.MANUAL,
        targetValue: Long = if (metric == GoalMetric.MANUAL) 1L else 25L,
        links: Set<String> = emptySet(),
        createdAt: Long = 100L,
        updatedAt: Long = createdAt,
    ) = GoalRecord(
        id = id,
        title = "Fixture $id",
        description = "",
        metric = metric,
        targetValue = targetValue,
        linkedFocusSessionIds = links,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun Any?.goalMap(): Map<*, *> = this as? Map<*, *> ?: error("Expected serialized goal map")
}
