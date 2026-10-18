package dev.atlas.coach.habits

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitsTest {
    @Test
    fun addCheckInIsIdempotentUndoAndHistoryExports() {
        val createdAt = LocalDate.of(2026, 8, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val repo = InMemoryHabitRepository(HabitClock { createdAt })
        val habit = repo.add("  Lire  ", HabitSchedule.daily())
        val today = LocalDate.of(2026, 10, 18)
        assertEquals("Lire", habit.name)
        assertEquals(createdAt, habit.createdAtUtcMillis)

        val first = repo.checkIn(habit.id, today.minusDays(3))
        assertEquals(first, repo.checkIn(habit.id, today.minusDays(3)))
        repo.checkIn(habit.id, today.minusDays(2))
        repo.checkIn(habit.id, today.minusDays(1))
        assertEquals(3, repo.checkIns(habit.id).size)

        val sevenDays = repo.history(habit.id, today, 7)
        assertEquals(7, sevenDays.days.size)
        assertEquals(7, sevenDays.dueDays)
        assertEquals(3, sevenDays.completedDueDays)
        assertEquals(3.0 / 7.0, sevenDays.completionRate, 0.0001)
        assertEquals(3, sevenDays.currentStreak) // an unchecked current day does not end the streak yet
        assertEquals(HabitDayStatus.DUE, sevenDays.days.last().status)

        val thirtyDays = repo.history(habit.id, today, 30)
        assertEquals(30, thirtyDays.days.size)
        assertEquals(30, thirtyDays.dueDays)
        assertEquals(3, thirtyDays.completedDueDays)
        assertEquals(3, thirtyDays.currentStreak)

        assertTrue(repo.undoCheckIn(habit.id, today.minusDays(2)))
        assertFalse(repo.undoCheckIn(habit.id, today.minusDays(2)))
        assertEquals(1, repo.history(habit.id, today, 7).currentStreak)
        assertTrue(repo.exportCsv().contains("record_type,id,name,schedule"))
        assertTrue(repo.exportCsv().contains("checkin"))
        assertEquals(3, repo.exportMaps().size)
        assertEquals(habit, Habit.fromMap(habit.toMap()))
        assertEquals(first, HabitCheckIn.fromMap(first.toMap()))
    }

    @Test
    fun weeklySchedulesAndArchivingAreSupported() {
        val weekdays = HabitSchedule.weekdays(setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.FRIDAY))
        assertTrue(weekdays.isDueOn(LocalDate.of(2026, 10, 19)))
        assertFalse(weekdays.isDueOn(LocalDate.of(2026, 10, 20)))
        assertEquals(weekdays, HabitSchedule.fromMap(weekdays.toMap()))

        val twoPerWeek = HabitSchedule.timesPerWeek(2)
        val monday = LocalDate.of(2026, 10, 19)
        val checkIns = listOf(
            HabitCheckIn("a", "h", monday.toEpochDay(), 1),
            HabitCheckIn("b", "h", monday.plusDays(1).toEpochDay(), 2),
        )
        assertFalse(twoPerWeek.isDueOn(monday.plusDays(2), checkIns))
        assertTrue(twoPerWeek.isDueOn(monday.plusDays(1), checkIns.take(1)))

        val repo = InMemoryHabitRepository(HabitClock { 99 })
        val archived = repo.archive(repo.add("Marche", HabitSchedule.daily()).id)
        assertEquals(99L, archived.archivedAtUtcMillis)
        assertTrue(repo.list().isEmpty())
    }
}
