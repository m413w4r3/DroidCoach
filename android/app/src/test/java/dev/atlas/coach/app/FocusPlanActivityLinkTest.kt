package dev.atlas.coach.app

import dev.atlas.coach.focus.FocusPhase
import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.focus.FocusStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusPlanActivityLinkTest {
    @Test
    fun planActivityLinkSurvivesFocusSessionPersistenceRoundTrip() {
        val session = FocusSession(
            id = "00000000-0000-0000-0000-000000000051",
            directiveId = null,
            presetId = "pomodoro-25-5",
            phase = FocusPhase.WORK,
            status = FocusStatus.RUNNING,
            createdAtUtcMillis = 10L,
            updatedAtUtcMillis = 10L,
            remainingSeconds = 1_500L,
            completedWorkCycles = 0,
            workSeconds = 1_500L,
            shortBreakSeconds = 300L,
            longBreakSeconds = 900L,
            longBreakEveryCycles = 4,
            totalWorkCycles = 4,
            continuous = false,
            taskTitle = "Préparer le dossier",
            planId = "00000000-0000-0000-0000-000000000001",
            activityId = "00000000-0000-0000-0000-000000000011",
            planLocalDate = "2026-10-26",
        )

        val restored = FocusSession.fromMap(session.toMap())

        assertEquals(session.planId, restored.planId)
        assertEquals(session.activityId, restored.activityId)
        assertEquals(session.planLocalDate, restored.planLocalDate)
        assertEquals(session.id, restored.id)
    }

    @Test
    fun legacyFocusSessionMapsRemainUnlinked() {
        val session = FocusSession(
            id = "00000000-0000-0000-0000-000000000052",
            directiveId = null,
            presetId = "pomodoro-25-5",
            phase = FocusPhase.WORK,
            status = FocusStatus.RUNNING,
            createdAtUtcMillis = 10L,
            updatedAtUtcMillis = 10L,
            remainingSeconds = 1_500L,
            completedWorkCycles = 0,
            workSeconds = 1_500L,
            shortBreakSeconds = 300L,
            longBreakSeconds = 900L,
            longBreakEveryCycles = 4,
        )
        val legacyMap = session.toMap() - setOf("planId", "activityId", "planLocalDate")

        val restored = FocusSession.fromMap(legacyMap)

        assertEquals(null, restored.planId)
        assertEquals(null, restored.activityId)
        assertEquals(null, restored.planLocalDate)
    }
}
