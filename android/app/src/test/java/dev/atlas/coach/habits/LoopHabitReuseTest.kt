package dev.atlas.coach.habits

import dev.atlas.coach.habits.thirdparty.loop.HabitStrengthCalculator
import dev.atlas.coach.habits.thirdparty.loop.Score
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopHabitReuseTest {
    @Test
    fun strengthUsesLoopRollingWindowAndExponentialUpdate() {
        val start = LocalDate.of(2026, 10, 12)
        val repo = InMemoryHabitRepository(
            clock = HabitClock { start.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() },
            newId = { "habit" },
        )
        val habit = repo.add("Pratique", HabitSchedule.daily())
        val first = repo.checkIn(habit.id, start)
        val second = repo.checkIn(habit.id, start.plusDays(1))

        val afterFirst = Score.compute(1.0, 0.0, 1.0)
        val expected = Score.compute(1.0, afterFirst, 1.0)
        assertEquals(expected, repo.strength(habit.id, start.plusDays(1)), 1e-12)
        assertTrue(HabitStrengthCalculator.recompute(habit, listOf(first, second), start.plusDays(1)).size == 2)
    }

    @Test
    fun csvIncludesLoopScoresAndQuotesFieldsWithCommas() {
        val today = LocalDate.of(2026, 10, 18)
        val repo = InMemoryHabitRepository(
            clock = HabitClock { today.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() },
            newId = { "habit" },
        )
        val habit = repo.add("Lecture, calme", HabitSchedule.weekdays(setOf(java.time.DayOfWeek.MONDAY)))
        repo.checkIn(habit.id, today)

        val csv = repo.exportCsv()
        assertTrue(csv.startsWith("record_type,id,name,schedule"))
        assertTrue(csv.contains("\"Lecture, calme\""))
        assertTrue(csv.contains("score,habit,\"Lecture, calme\""))
        assertTrue(csv.contains("checkin"))
    }
}
