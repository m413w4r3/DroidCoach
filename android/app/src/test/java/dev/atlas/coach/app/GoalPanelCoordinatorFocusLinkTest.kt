package dev.atlas.coach.app

import dev.atlas.coach.focus.FakeClock
import dev.atlas.coach.focus.FocusEngine
import dev.atlas.coach.focus.FocusPhase
import dev.atlas.coach.focus.FocusPlan
import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.focus.FocusStatus
import dev.atlas.coach.goals.GoalMetric
import dev.atlas.coach.goals.GoalRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalPanelCoordinatorFocusLinkTest {
    @Test
    fun pausedPomodoroGetsNoCreditThenFinishedWorkSurvivesRecreationAndRepeatedRecalculation() {
        val harness = Harness(FakeClock(startUtcMillis = 100_000L))
        val coordinator = harness.coordinator()
        val goal = harness.createFocusGoal(coordinator, targetMinutes = 120L)
        val engine = FocusEngine(harness.clock, idFactory = { "twenty-five-minute-session" })
        val plan = FocusPlan(
            presetId = "pomodoro-25-single-cycle",
            workSeconds = 25 * 60L,
            shortBreakSeconds = 5 * 60L,
            totalWorkCycles = 1,
        )
        val start = engine.prepareStart(
            plan = plan,
            directiveId = null,
            sessionId = "twenty-five-minute-session",
            startedAtUtcMillis = harness.clock.nowMillis(),
            startedAtMonotonicMillis = harness.clock.monotonicMillis(),
        )
        val link = coordinator.prepareFocusLink(goal.id, start.session.id, harness.clock.nowMillis())

        assertEquals(0L, coordinator.entries().single().progress.currentValue)
        assertEquals(listOf(goal), coordinator.repository.all())
        assertTrue(link.expectedRecords == coordinator.repository.all())

        harness.subdocument = link.goalsSubdocument // stand-in for the owner committing its root candidate
        assertEquals(link.linkedGoal, coordinator.publishCommittedFocusLink(link))
        engine.publishPreparedStart(start)
        harness.focusHistory.add(engine.snapshot()!!)
        assertEquals(0L, coordinator.entries().single().progress.currentValue)

        harness.clock.advanceMillis(4 * 60_000L)
        harness.focusHistory[0] = engine.snapshot()!!
        assertEquals(0L, coordinator.entries().single().progress.currentValue)

        harness.clock.advanceMillis(6 * 60_000L)
        val paused = engine.pause(start.session.id)!!
        harness.focusHistory[0] = paused
        assertEquals(FocusStatus.PAUSED, paused.status)
        assertEquals(0L, coordinator.entries().single().progress.currentValue)

        harness.clock.advanceMillis(8 * 60 * 60_000L)
        engine.resume(start.session.id)
        assertEquals(0L, coordinator.entries().single().progress.currentValue)
        harness.clock.advanceMillis(15 * 60_000L)
        val finished = engine.snapshot()!!
        assertEquals(FocusStatus.FINISHED, finished.status)
        assertEquals(FocusPhase.WORK, finished.phase)
        harness.focusHistory[0] = finished

        assertEquals(25L, coordinator.entries().single().progress.currentValue)
        assertEquals(listOf(start.session.id), coordinator.focusLinkOptions().map(GoalPanelLinkOption::id))

        val recreated = harness.coordinator()
        assertEquals(25L, recreated.entries().single().progress.currentValue)
        repeat(3) { assertEquals(25L, recreated.entries().single().progress.currentValue) }
        assertEquals(setOf(start.session.id), recreated.repository.get(goal.id)!!.linkedFocusSessionIds)
        assertEquals(25L, recreated.entries().single().progress.currentValue)
    }

    @Test
    fun cancelledLinkedWorkStaysAtZeroAndFinishedHistoryCanStillBeLinkedWithoutDoubleCredit() {
        val harness = Harness(FakeClock(startUtcMillis = 500L))
        val coordinator = harness.coordinator()
        val goal = harness.createFocusGoal(coordinator, targetMinutes = 10L)
        val cancelledEngine = FocusEngine(harness.clock, idFactory = { "cancelled-session" })
        val cancelledStart = cancelledEngine.prepareStart(
            plan = FocusPlan("cancelled", workSeconds = 60, shortBreakSeconds = 30),
            directiveId = null,
            sessionId = "cancelled-session",
            startedAtUtcMillis = harness.clock.nowMillis(),
            startedAtMonotonicMillis = harness.clock.monotonicMillis(),
        )
        val cancelledLink = coordinator.prepareFocusLink(goal.id, cancelledStart.session.id, harness.clock.nowMillis())
        harness.subdocument = cancelledLink.goalsSubdocument
        coordinator.publishCommittedFocusLink(cancelledLink)
        cancelledEngine.publishPreparedStart(cancelledStart)
        harness.clock.advanceMillis(20_000L)
        val cancelled = cancelledEngine.cancel(cancelledStart.session.id)!!
        harness.focusHistory.add(cancelled)

        assertEquals(0L, coordinator.entries().single().progress.currentValue)
        assertTrue(coordinator.focusLinkOptions().isEmpty())

        val historicalEngine = FocusEngine(harness.clock, idFactory = { "historical-session" })
        historicalEngine.start(FocusPlan("historical", workSeconds = 60, shortBreakSeconds = 30, totalWorkCycles = 1))
        harness.clock.advanceMillis(60_000L)
        val historical = historicalEngine.snapshot()!!
        assertEquals(FocusStatus.FINISHED, historical.status)
        harness.focusHistory += historical
        harness.focusHistory += historical // duplicated stored row must remain one linked session

        assertEquals(listOf(historical.id), coordinator.focusLinkOptions().map(GoalPanelLinkOption::id))
        coordinator.linkFocus(goal.id, historical.id, linked = true)

        assertEquals(setOf(cancelled.id, historical.id), coordinator.repository.get(goal.id)!!.linkedFocusSessionIds)
        assertEquals(1L, coordinator.entries().single().progress.currentValue)
        assertFalse(coordinator.repository.get(goal.id)!!.linkedFocusSessionIds.contains("unselected-session"))
    }

    private class Harness(val clock: FakeClock) {
        val focusHistory = mutableListOf<FocusSession>()
        var subdocument: Map<String, Any?>? = null
        var persistenceWrites: Int = 0
            private set
        private var nextId = 0

        fun coordinator() = GoalPanelCoordinator(
            readSubdocument = { subdocument },
            persistSubdocument = {
                subdocument = it
                persistenceWrites++
            },
            nowUtcMillis = clock::nowMillis,
            focusSessions = { focusHistory.toList() },
            habits = { emptyList() },
            habitCheckIns = { emptyList() },
            newId = { "goal-${++nextId}" },
        )

        fun createFocusGoal(coordinator: GoalPanelCoordinator, targetMinutes: Long): GoalRecord =
            coordinator.create(
                GoalPanelDraft(
                    title = "Écrire une section",
                    metric = GoalMetric.FOCUS_MINUTES,
                    targetValue = targetMinutes,
                    description = "Fixture de test",
                    nextAction = "Ouvrir le document",
                ),
            )
    }
}
