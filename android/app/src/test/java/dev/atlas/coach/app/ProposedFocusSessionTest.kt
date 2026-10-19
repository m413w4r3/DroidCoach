package dev.atlas.coach.app

import dev.atlas.coach.focus.FocusPhase
import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.focus.FocusStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ProposedFocusSessionTest {
    @Test
    fun contractProposalMapsToAUserStartedFiniteFocusPlan() {
        val proposal = ProposedFocusSession(25, 5, 3, "Lecture", "Lire un chapitre")
        val plan = proposal.toFocusPlan()

        assertEquals(1_500L, plan.workSeconds)
        assertEquals(300L, plan.shortBreakSeconds)
        assertEquals(3, plan.totalWorkCycles)
        assertEquals(false, plan.continuous)
    }

    @Test
    fun zeroBreakUsesDocumentedMinimalTransition() {
        assertEquals(1L, ProposedFocusSession(10, 0, 2, reason = "Courte tâche").toFocusPlan().shortBreakSeconds)
    }

    @Test
    fun proposedEndEstimateIncludesBreaksBetweenWorkCycles() {
        assertEquals(5_100L, ProposedFocusSession(25, 5, 3, reason = "Trois cycles").estimatedDurationSeconds())
    }

    @Test
    fun approximateEndIncludesRemainingCyclesAndBreaks() {
        val session = FocusSession(
            id = "test-session",
            directiveId = null,
            presetId = "test",
            phase = FocusPhase.WORK,
            status = FocusStatus.RUNNING,
            createdAtUtcMillis = 0L,
            updatedAtUtcMillis = 0L,
            remainingSeconds = 60L,
            remainingMillis = 60_000L,
            completedWorkCycles = 0,
            workSeconds = 1_500L,
            shortBreakSeconds = 300L,
            longBreakSeconds = 900L,
            longBreakEveryCycles = 4,
            totalWorkCycles = 2,
            continuous = false,
        )

        assertEquals(1_860_000L, approximateFocusEndMillis(session, 0L))
    }
}
