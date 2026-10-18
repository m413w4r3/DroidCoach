package dev.atlas.coach.habits

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitScheduleTest {
    @Test
    fun chosenWeekdaysAndWeeklyQuotaUseLocalCalendarAndExplicitWeekAnchor() {
        val weekdays = HabitSchedule.weekdays(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))
        assertTrue(weekdays.isDueOn(LocalDate.of(2026, 10, 19)))
        assertFalse(weekdays.isDueOn(LocalDate.of(2026, 10, 20)))

        val quota = HabitSchedule.timesPerWeek(2, weekStartsOn = DayOfWeek.SUNDAY)
        val sunday = LocalDate.of(2026, 10, 18)
        val checkIns = listOf(
            checkIn("sun", sunday),
            checkIn("mon", sunday.plusDays(1)),
        )
        assertFalse(quota.isDueOn(sunday.plusDays(2), checkIns))
        assertTrue(quota.isDueOn(sunday.plusWeeks(1), checkIns))
        assertEquals(quota, HabitSchedule.fromMap(quota.toMap()))
    }

    @Test
    fun alternatingDaysHonorInitialDateIncludingFutureDates() {
        val start = LocalDate.of(2026, 10, 21)
        val schedule = HabitSchedule.alternatingDays(start)
        assertFalse(schedule.isDueOn(start.minusDays(1)))
        assertTrue(schedule.isDueOn(start))
        assertFalse(schedule.isDueOn(start.plusDays(1)))
        assertTrue(schedule.isDueOn(start.plusDays(2)))
        assertEquals(schedule, HabitSchedule.fromMap(schedule.toMap()))
    }

    @Test
    fun oldPersistedHabitsMigrateToDailyAndNewLocalCreationDateStaysStableAfterZoneChange() {
        val old = Habit.fromMap(
            mapOf(
                "id" to "legacy",
                "name" to "Habitude historique",
                "createdAtUtcMillis" to Instant.parse("2026-10-19T00:30:00Z").toEpochMilli(),
            ),
        )
        assertEquals(HabitSchedule.daily(), old.schedule)
        assertTrue(old.schedule.isDueOn(LocalDate.of(2030, 1, 2)))

        var zone = ZoneId.of("Europe/Paris")
        val createdAt = Instant.parse("2026-10-19T00:30:00Z").toEpochMilli()
        val repo = InMemoryHabitRepository(HabitClock { createdAt }, { "stable-date" }) { zone }
        val habit = repo.add("Local", HabitSchedule.daily())
        assertEquals(LocalDate.of(2026, 10, 19), habit.createdDate())
        zone = ZoneId.of("America/Los_Angeles")
        assertEquals(LocalDate.of(2026, 10, 19), repo.list().single().createdDate())
        assertThrows(IllegalArgumentException::class.java) {
            repo.checkIn(habit.id, LocalDate.of(2026, 10, 18))
        }
        assertTrue(repo.checkIn(habit.id, LocalDate.of(2026, 10, 19)).date == LocalDate.of(2026, 10, 19))
    }

    @Test
    fun oldScheduleMapsKeepExistingWeekdayAndQuotaRules() {
        val weekdays = HabitSchedule.fromMap(mapOf("kind" to "WEEKDAYS", "weekdaysMask" to 2))
        assertTrue(weekdays.isDueOn(LocalDate.of(2026, 10, 20)))
        val quota = HabitSchedule.fromMap(mapOf("kind" to "TIMES_PER_WEEK", "timesPerWeek" to 1))
        assertEquals(DayOfWeek.MONDAY, quota.weekStartsOn)
        assertTrue(quota.isDueOn(LocalDate.of(2026, 10, 18)))
    }

    private fun checkIn(id: String, date: LocalDate) =
        HabitCheckIn(id, "habit", date.toEpochDay(), date.toEpochDay())
}
