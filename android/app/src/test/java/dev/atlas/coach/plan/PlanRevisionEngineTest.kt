package dev.atlas.coach.plan

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanRevisionEngineTest {
    private val engine = PlanRevisionEngine()

    @Test
    fun changeCalculationIsDeterministicAndUsesEachChangeType() {
        val existing = activity()
        val moved = activity(id = SECOND_ACTIVITY_ID, scheduled = schedule(11 * 60))
        val editedBefore = activity(id = THIRD_ACTIVITY_ID, title = "Ancien titre", scheduled = schedule(13 * 60))
        val editedAfter = editedBefore.copy(title = "Nouveau titre")
        val removed = activity(
            id = "20000000-0000-4000-8000-000000000004",
            scheduled = schedule(14 * 60),
        )
        val added = activity(
            id = "20000000-0000-4000-8000-000000000005",
            scheduled = schedule(15 * 60),
        )
        val current = dayPlan(listOf(existing, moved, editedBefore, removed))
        val proposed = listOf(existing, moved.copy(scheduledLocal = schedule(12 * 60)), editedAfter, added)

        val first = engine.calculateChanges(current, proposed)
        val second = engine.calculateChanges(current, proposed)

        assertEquals(first, second)
        assertEquals(
            listOf("SlotMoved", "SlotEdited", "SlotRemoved", "SlotAdded"),
            first.map { it::class.simpleName },
        )
    }

    @Test
    fun effectIdsAreExactSortedAndDeduplicateUpdatesForOneActivity() {
        val revisionId = REVISION_ID
        val changes = listOf(
            PlanChange.SlotAdded(activity(id = SECOND_ACTIVITY_ID)),
            PlanChange.SlotMoved(ACTIVITY_ID, schedule(), schedule(10 * 60)),
            PlanChange.SlotEdited(ACTIVITY_ID, activity(), activity(title = "Nouveau")),
            PlanChange.StatusChanged(ACTIVITY_ID, ActivityStatus.PLANNED, ActivityStatus.IN_PROGRESS),
            PlanChange.SlotRemoved(THIRD_ACTIVITY_ID, activity(id = THIRD_ACTIVITY_ID)),
        )

        assertEquals(
            listOf(
                "CALENDAR_CREATE:$SECOND_ACTIVITY_ID",
                "CALENDAR_DELETE:$THIRD_ACTIVITY_ID",
                "CALENDAR_UPDATE:$ACTIVITY_ID",
                "PLAN_APPLY:$revisionId",
            ).sorted(),
            engine.effectIds(changes, revisionId),
        )
    }

    @Test
    fun scopeHashIgnoresInputOrderAndUsesSha256Hex() {
        val ids = listOf("CALENDAR_UPDATE:$ACTIVITY_ID", "PLAN_APPLY:$REVISION_ID")

        assertEquals(engine.scopeHash(ids), engine.scopeHash(ids.reversed()))
        assertEquals(64, engine.scopeHash(ids).length)
        assertTrue(engine.scopeHash(ids).all { it in "0123456789abcdef" })
    }

    @Test
    fun revisionCreationIncrementsVersionAndKeepsStablePlanId() {
        val current = dayPlan()
        val revision = revisionFor(current, listOf(activity(scheduled = schedule(10 * 60))))

        assertEquals(PLAN_ID, revision.planId)
        assertEquals(1, revision.baseVersion)
        assertEquals(2, revision.newVersion)
    }

    @Test
    fun springDstGapUsesTheFirstValidLocalTime() {
        val missingLocalHour = LocalSchedule("2025-03-30", 2 * 60 + 15, 30, "Europe/Paris")

        assertEquals(Instant.parse("2025-03-30T01:00:00Z"), engine.localToInstant(missingLocalHour))
    }

    @Test
    fun autumnDstOverlapChoosesTheEarlierOffset() {
        val ambiguousLocalHour = LocalSchedule("2025-10-26", 2 * 60 + 30, 30, "Europe/Paris")

        assertEquals(Instant.parse("2025-10-26T00:30:00Z"), engine.localToInstant(ambiguousLocalHour))
    }

    @Test
    fun instantConvertsToLocalScheduleWithExplicitTimezone() {
        val schedule = engine.instantToSchedule(
            instant = Instant.parse("2025-10-26T00:30:00Z"),
            durationMinutes = 45,
            timezone = "Europe/Paris",
        )

        assertEquals("2025-10-26", schedule.localDate)
        assertEquals(150, schedule.startMinute)
        assertEquals(45, schedule.durationMinutes)
        assertEquals("Europe/Paris", schedule.timezone)
    }

    @Test
    fun overlapsAreReturnedAsWarningsWithExactIntersection() {
        val first = activity(scheduled = schedule(9 * 60, 60))
        val second = activity(id = SECOND_ACTIVITY_ID, scheduled = schedule(9 * 60 + 30, 45))

        val warnings = engine.overlapWarnings(listOf(second, first))

        assertEquals(1, warnings.size)
        assertEquals(ACTIVITY_ID, warnings.single().firstActivityId)
        assertEquals(SECOND_ACTIVITY_ID, warnings.single().secondActivityId)
        assertEquals(Instant.parse("2026-10-26T08:30:00Z"), warnings.single().overlapStart)
        assertEquals(Instant.parse("2026-10-26T09:00:00Z"), warnings.single().overlapEnd)
    }

    @Test
    fun terminalAndCompletedStatusesDoNotCreateOverlapWarnings() {
        val planned = activity()
        val done = activity(id = SECOND_ACTIVITY_ID, status = ActivityStatus.DONE)
        val cancelled = activity(id = THIRD_ACTIVITY_ID, status = ActivityStatus.CANCELLED)

        assertTrue(engine.overlapWarnings(listOf(planned, done, cancelled)).isEmpty())
    }
}
