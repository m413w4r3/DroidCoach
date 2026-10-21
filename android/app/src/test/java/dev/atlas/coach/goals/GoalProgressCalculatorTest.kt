package dev.atlas.coach.goals

import dev.atlas.coach.focus.FocusPhase
import dev.atlas.coach.focus.FocusEngine
import dev.atlas.coach.focus.FocusPlan
import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.focus.FocusStatus
import dev.atlas.coach.focus.FakeClock
import dev.atlas.coach.habits.HabitCheckIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalProgressCalculatorTest {
    @Test
    fun elapsedPomodoroWorkExcludesBreakAndCancelledWorkWithAControlledClock() {
        val clock = FakeClock(startUtcMillis = 10_000L)
        val engine = FocusEngine(clock, idFactory = { "pomodoro-session" })
        val started = engine.start(FocusPlan("pomodoro", workSeconds = 60, shortBreakSeconds = 30, totalWorkCycles = 2))

        clock.advanceMillis(60_000L)
        assertEquals(FocusPhase.SHORT_BREAK, engine.currentSession()!!.phase)
        val finishedDuringBreak = engine.finish(started.id)!!
        assertEquals(60L, GoalProgressCalculator.completedWorkSeconds(finishedDuringBreak))

        val cancelClock = FakeClock()
        val cancelEngine = FocusEngine(cancelClock, idFactory = { "cancelled-session" })
        val cancellable = cancelEngine.start(FocusPlan("cancel", workSeconds = 60, shortBreakSeconds = 30))
        cancelClock.advanceMillis(20_000L)
        val cancelled = cancelEngine.cancel(cancellable.id)!!
        assertEquals(FocusStatus.CANCELLED, cancelled.status)
        assertEquals(0L, GoalProgressCalculator.completedWorkSeconds(cancelled))
    }

    @Test
    fun focusProgressUsesOnlyLinkedFinishedWorkAndDeduplicatesSessions() {
        val goal = goal(
            metric = GoalMetric.FOCUS_MINUTES,
            target = 29,
            linkedFocus = setOf("finished", "partial", "cancelled", "running"),
        )
        val finished = session("finished", FocusStatus.FINISHED, cycles = 1, workSeconds = 25 * 60L, remainingMillis = 0L)
        val partial = session("partial", FocusStatus.FINISHED, cycles = 0, workSeconds = 25 * 60L, remainingMillis = 20 * 60_000L + 30_000L)
        val sessions = listOf(
            finished,
            finished.copy(workSeconds = 99 * 60L), // duplicate persisted row must not inflate progress
            partial,
            session("cancelled", FocusStatus.CANCELLED, cycles = 1, workSeconds = 25 * 60L, remainingMillis = 0L),
            finished.copy(id = "cancelled"), // a same-time cancellation wins over a conflicting row
            session("running", FocusStatus.RUNNING, cycles = 1, workSeconds = 25 * 60L, remainingMillis = 0L),
            session("unlinked", FocusStatus.FINISHED, cycles = 1, workSeconds = 25 * 60L, remainingMillis = 0L),
        )

        val progress = GoalProgressCalculator.calculate(goal, sessions)

        assertEquals(29L, progress.currentValue) // 25 min + 4 min 30 sec, floored after summing
        assertTrue(progress.isComplete)
        assertEquals(100, progress.percentComplete)
    }

    @Test
    fun habitProgressCountsOnlyLinkedConfirmedCheckInsAndDeduplicatesLogicalDays() {
        val goal = goal(metric = GoalMetric.HABIT_CHECKINS, target = 4, linkedHabits = setOf("walk", "read"))
        val checkIns = listOf(
            checkIn("walk-1", "walk", 10),
            checkIn("walk-1", "walk", 10),
            checkIn("duplicate-row", "walk", 10),
            checkIn("walk-2", "walk", 11),
            checkIn("read-1", "read", 10),
            checkIn("unlinked", "stretch", 10),
        )

        val progress = GoalProgressCalculator.calculate(goal, habitCheckIns = checkIns)

        assertEquals(3L, progress.currentValue)
        assertFalse(progress.isComplete)
        assertEquals(75, progress.percentComplete)
    }

    @Test
    fun manualProgressRequiresRepositoryConfirmation() {
        val goal = goal(metric = GoalMetric.MANUAL, target = 1)

        assertEquals(0L, GoalProgressCalculator.calculate(goal).currentValue)
        assertFalse(GoalProgressCalculator.calculate(goal).isComplete)
        assertEquals(1L, GoalProgressCalculator.calculate(goal.copy(status = GoalStatus.COMPLETED)).currentValue)
    }

    @Test
    fun mapRoundTripPreservesTheImposedModelAndOptionalFields() {
        val original = goal(
            metric = GoalMetric.FOCUS_MINUTES,
            target = 120,
            linkedFocus = setOf("session-b", "session-a"),
            linkedHabits = setOf("walk"),
        ).copy(dueAt = 500L, nextAction = "Commencer la prochaine étape")

        val restored = GoalRecord.fromMap(original.toMap())

        assertEquals(original, restored)
        assertEquals("focus_minutes", original.toMap()["metric"])
        assertEquals("active", original.toMap()["status"])
    }

    @Test
    fun percentCompleteDoesNotOverflowForLongTargets() {
        val almostComplete = GoalProgress(
            goalId = "large-goal",
            metric = GoalMetric.FOCUS_MINUTES,
            currentValue = Long.MAX_VALUE - 1L,
            targetValue = Long.MAX_VALUE,
        )
        val complete = almostComplete.copy(currentValue = Long.MAX_VALUE)

        assertEquals(99, almostComplete.percentComplete)
        assertEquals(100, complete.percentComplete)
    }

    private fun goal(
        metric: GoalMetric,
        target: Long,
        linkedFocus: Set<String> = emptySet(),
        linkedHabits: Set<String> = emptySet(),
    ) = GoalRecord(
        id = "goal-1",
        title = "Fixture goal",
        description = "",
        metric = metric,
        targetValue = target,
        linkedFocusSessionIds = linkedFocus,
        linkedHabitIds = linkedHabits,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private fun session(
        id: String,
        status: FocusStatus,
        cycles: Int,
        workSeconds: Long,
        remainingMillis: Long,
    ) = FocusSession(
        id = id,
        directiveId = null,
        presetId = "fixture",
        phase = FocusPhase.WORK,
        status = status,
        createdAtUtcMillis = 1_000L,
        updatedAtUtcMillis = 2_000L,
        remainingSeconds = remainingMillis / 1_000L,
        completedWorkCycles = cycles,
        workSeconds = workSeconds,
        shortBreakSeconds = 5 * 60L,
        longBreakSeconds = 15 * 60L,
        longBreakEveryCycles = 4,
        remainingMillis = remainingMillis,
        totalWorkCycles = 4,
        continuous = false,
    )

    private fun checkIn(id: String, habitId: String, day: Long) = HabitCheckIn(
        id = id,
        habitId = habitId,
        dateEpochDay = day,
        createdAtUtcMillis = day * 86_400_000L,
    )
}
