package dev.atlas.coach.habits

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitLinkPolicyTest {
    @Test
    fun completedSessionDoesNotCheckHabitWithoutConsentOrPreEngagement() {
        val repo = InMemoryHabitRepository(HabitClock { 5 })
        val habit = repo.add("Lire", HabitSchedule.daily())
        val date = LocalDate.of(2026, 10, 18)
        val policy = HabitLinkPolicy(repo)

        assertTrue(policy.onSessionCompleted("focus-1", date).isEmpty())
        assertTrue(repo.checkIns(habit.id).isEmpty())

        val confirmed = policy.confirmLink("focus-2", habit.id, date)
        assertEquals(habit.id, confirmed.habitId)
        assertEquals(1, repo.checkIns(habit.id).size)

        repo.undoCheckIn(habit.id, date)
        policy.setPreEngagement(habit.id, true)
        assertEquals(1, policy.onSessionCompleted("focus-3", date).size)
        assertEquals(setOf(habit.id), HabitLinkPolicy.fromMap(repo, policy.toMap()).preEngagedHabitIds())
    }
}
