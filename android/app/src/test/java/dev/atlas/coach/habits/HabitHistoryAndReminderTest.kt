package dev.atlas.coach.habits

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitHistoryAndReminderTest {
    @Test
    fun historyReportsDueAndNonDueDaysRatesAndCurrentAndBestStreaks() {
        val created = LocalDate.of(2026, 10, 19).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val repo = InMemoryHabitRepository(HabitClock { created }, zoneProvider = { ZoneId.of("UTC") })
        val habit = repo.add("Lecture", HabitSchedule.weekdays(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)))
        listOf(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 26))
            .forEach { repo.checkIn(habit.id, it) }

        val seven = repo.history(habit.id, LocalDate.of(2026, 10, 27), 7)
        val thirty = repo.history(habit.id, LocalDate.of(2026, 10, 27), 30)
        assertEquals(2, seven.dueDays)
        assertEquals(2, seven.completedDueDays)
        assertEquals(5, seven.notDueDays)
        assertEquals(1.0, seven.completionRate, 0.0)
        assertEquals(3, seven.currentStreak)
        assertEquals(3, seven.bestStreak)
        assertEquals(30, thirty.days.size)
        assertEquals(3, thirty.dueDays)
        assertEquals(3, thirty.completedDueDays)
        assertEquals(3, thirty.bestStreak)
    }

    @Test
    fun restoreAndRepeatedCheckInKeepSingleStoredRecordAndStableCoachEventIds() {
        val repo = InMemoryHabitRepository(HabitClock { 1L }, { "habit" }, { ZoneId.of("UTC") })
        val habit = repo.add("Méditer", HabitSchedule.daily())
        val date = LocalDate.of(1970, 1, 1)
        val original = repo.checkIn(habit.id, date)
        val maps = repo.exportMaps()
        val restored = InMemoryHabitRepository(HabitClock { 2L }, { "unused" }, { ZoneId.of("UTC") })
        restored.restoreMaps(maps)
        val repeated = restored.checkIn(habit.id, date)

        assertEquals(original, repeated)
        assertEquals(1, restored.checkIns(habit.id).size)
        assertEquals(HabitCoachEventConverter.checkInConfirmed(original), HabitCoachEventConverter.checkInConfirmed(repeated))
        assertEquals("habit.check_in.confirmed", HabitCoachEventConverter.checkInConfirmed(original).toMap()["type"])
        val revoked = HabitCoachEventConverter.checkInRevoked(original, 3L)
        assertEquals("habit.check_in.revoked", revoked.type)
        assertEquals("habit-checkin-revoked:${original.id}", revoked.eventId)
        assertTrue(restored.undoCheckIn(habit.id, date))
        assertFalse(restored.undoCheckIn(habit.id, date))
    }

    @Test
    fun reminderMapFakeSchedulerAndDisableAreRevocable() {
        val reminder = HabitReminder.weekdaysAt(8, 15, setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))
        assertEquals(reminder, HabitReminder.fromMap(reminder.toMap()))
        val repo = InMemoryHabitRepository(HabitClock { 1L }, { "habit" }, { ZoneId.of("UTC") })
        val habit = repo.add("Marche", HabitSchedule.daily())
        repo.setReminder(habit.id, reminder)
        val restored = InMemoryHabitRepository(HabitClock { 2L }, { "unused" }, { ZoneId.of("UTC") })
        restored.restoreMaps(repo.exportMaps())
        assertEquals(reminder, restored.list().single().reminder)

        val scheduler = FakeHabitReminderScheduler(notificationsAvailable = false)
        val result = scheduler.schedule(habit.id, habit.name, reminder, ZoneId.of("Europe/Paris"))
        assertTrue(result.alarmScheduled)
        assertFalse(result.notificationsAvailable)
        assertEquals(reminder, scheduler.scheduled().getValue(habit.id).second)
        assertTrue(repo.checkIns(habit.id).isEmpty())
        scheduler.cancel(habit.id)
        assertTrue(scheduler.scheduled().isEmpty())

        val disabled = HabitReminder(8, days = setOf(DayOfWeek.MONDAY), enabled = false)
        assertFalse(scheduler.schedule(habit.id, "Marche", disabled).alarmScheduled)
        assertNull(disabled.nextOccurrence(ZonedDateTime.now(ZoneId.of("UTC"))))
    }

    @Test
    fun reminderUsesChosenLocalTimeAcrossZoneChangesAndResolvesDstGaps() {
        val mondayAtNine = HabitReminder.weekdaysAt(9, days = setOf(DayOfWeek.MONDAY))
        val after = ZonedDateTime.ofInstant(Instant.parse("2026-10-19T07:30:00Z"), ZoneId.of("UTC"))
        val paris = mondayAtNine.nextOccurrence(after, ZoneId.of("Europe/Paris"))!!
        val losAngeles = mondayAtNine.nextOccurrence(after, ZoneId.of("America/Los_Angeles"))!!
        assertEquals(LocalDate.of(2026, 10, 26), paris.toLocalDate())
        assertEquals(LocalDate.of(2026, 10, 19), losAngeles.toLocalDate())

        val parisGap = HabitReminder.weekdaysAt(2, 30, setOf(DayOfWeek.SUNDAY))
            .nextOccurrence(
                ZonedDateTime.ofInstant(Instant.parse("2026-03-28T23:00:00Z"), ZoneId.of("UTC")),
                ZoneId.of("Europe/Paris"),
            )!!
        assertEquals(LocalDate.of(2026, 3, 29), parisGap.toLocalDate())
        assertEquals(3, parisGap.hour)
        assertEquals(30, parisGap.minute)

        val laGap = HabitReminder.weekdaysAt(2, 30, setOf(DayOfWeek.SUNDAY))
            .nextOccurrence(
                ZonedDateTime.ofInstant(Instant.parse("2026-03-07T20:00:00Z"), ZoneId.of("UTC")),
                ZoneId.of("America/Los_Angeles"),
            )!!
        assertEquals(LocalDate.of(2026, 3, 8), laGap.toLocalDate())
        assertEquals(3, laGap.hour)
        assertEquals(30, laGap.minute)
    }
}
