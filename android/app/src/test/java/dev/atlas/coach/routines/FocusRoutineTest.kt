package dev.atlas.coach.routines

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusRoutineTest {
    private val zone = ZoneId.of("Europe/Paris")

    @Test
    fun weeklyRoutineUsesStartDayForOvernightWindow() {
        val schedule = RoutineSchedule(
            type = RoutineSchedule.ScheduleType.WEEKLY,
            timeHour = 22,
            timeMinute = 0,
            endTimeHour = 2,
            endTimeMinute = 0,
            daysOfWeek = setOf(DayOfWeek.MONDAY),
        )
        val now = LocalDateTime.of(2026, 10, 20, 1, 0).atZone(zone)
        val window = RoutineTimeCalculator.getSessionWindow(schedule, now)
        assertEquals(LocalDateTime.of(2026, 10, 19, 22, 0), window?.start?.toLocalDateTime())
        assertEquals(LocalDateTime.of(2026, 10, 20, 2, 0), window?.end?.toLocalDateTime())
        assertEquals(1, RoutineEvaluator.active(listOf(FocusRoutine("r", "Focus", schedule)), now).size)
    }

    @Test
    fun manualDisabledAndEndBoundaryDoNotActivate() {
        val now = LocalDateTime.of(2026, 10, 19, 11, 0).atZone(zone)
        val daily = RoutineSchedule.daily(LocalTime.of(9, 0), LocalTime.of(11, 0))
        assertNull(RoutineTimeCalculator.getSessionWindow(daily, now))
        assertTrue(RoutineEvaluator.active(listOf(FocusRoutine("manual", "Local", RoutineSchedule.manual())), now).isEmpty())
        assertTrue(RoutineEvaluator.active(listOf(FocusRoutine("off", "Local", daily, isEnabled = false)), now).isEmpty())
    }

    @Test
    fun nextTriggerUsesInjectedZoneAndRespectsWeeklyDays() {
        val weekly = RoutineSchedule(
            type = RoutineSchedule.ScheduleType.WEEKLY,
            timeHour = 9,
            timeMinute = 0,
            endTimeHour = 10,
            endTimeMinute = 0,
            daysOfWeek = setOf(DayOfWeek.MONDAY),
        )
        val after = Instant.parse("2026-10-19T08:30:00Z").atZone(ZoneId.of("UTC"))
        val paris = RoutineTimeCalculator.nextTrigger(weekly, after, zone)
        assertEquals(LocalDateTime.of(2026, 10, 26, 9, 0), paris?.toLocalDateTime())
        assertEquals(zone, paris?.zone)

        val tokyo = ZoneId.of("Asia/Tokyo")
        val tokyoTrigger = RoutineTimeCalculator.nextTrigger(weekly, after, tokyo)
        assertEquals(LocalDateTime.of(2026, 10, 26, 9, 0), tokyoTrigger?.toLocalDateTime())
        assertEquals(tokyo, tokyoTrigger?.zone)
        assertTrue(paris!!.toInstant() != tokyoTrigger!!.toInstant())
    }

    @Test
    fun dstGapShiftsToFirstValidLocalTimeAndOverlapChoosesEarlierOffset() {
        val daily = RoutineSchedule.daily(LocalTime.of(2, 30), LocalTime.of(4, 0))
        val beforeSpringGap = LocalDateTime.of(2026, 3, 28, 23, 0).atZone(zone)
        val spring = RoutineTimeCalculator.nextTrigger(daily, beforeSpringGap, zone)!!
        assertEquals(LocalDateTime.of(2026, 3, 29, 3, 30), spring.toLocalDateTime())

        val beforeOverlap = LocalDateTime.of(2026, 10, 24, 23, 0).atZone(zone)
        val overlap = RoutineTimeCalculator.nextTrigger(daily, beforeOverlap, zone)!!
        assertEquals(LocalDateTime.of(2026, 10, 25, 2, 30), overlap.toLocalDateTime())
        assertEquals(java.time.ZoneOffset.ofHours(2), overlap.offset)
    }

    @Test
    fun simultaneousTriggersAreReturnedTogetherAndOrderedDeterministically() {
        val schedule = RoutineSchedule.daily(LocalTime.of(9, 0), LocalTime.of(10, 0))
        val routines = listOf(
            FocusRoutine("z", "Zulu", schedule),
            FocusRoutine("a", "Alpha", schedule),
            FocusRoutine("off", "Disabled", schedule, isEnabled = false),
        )
        val after = LocalDateTime.of(2026, 10, 19, 8, 0).atZone(zone)
        val triggers = RoutineEvaluator.nextTriggers(routines, after, zone)
        assertEquals(listOf("a", "z"), triggers.map { it.routine.id })
        assertEquals(1, triggers.map { it.at.toInstant() }.distinct().size)
    }

    @Test
    fun oneShotRoutineHasOnlyItsStoredDateAndManualHasNoTrigger() {
        val once = RoutineSchedule.daily(
            LocalTime.of(9, 0), LocalTime.of(10, 0),
            startDate = LocalDate.of(2026, 10, 20), isRecurring = false,
        )
        val routine = FocusRoutine("once", "Une fois", once)
        val before = LocalDateTime.of(2026, 10, 19, 20, 0).atZone(zone)
        assertEquals(LocalDateTime.of(2026, 10, 20, 9, 0), RoutineTimeCalculator.nextTrigger(once, before, zone)?.toLocalDateTime())
        val after = LocalDateTime.of(2026, 10, 20, 10, 0).atZone(zone)
        assertNull(RoutineTimeCalculator.nextTrigger(once, after, zone))
        assertNull(RoutineTimeCalculator.nextTrigger(RoutineSchedule.manual(), before, zone))
        assertEquals(once.startDate, FocusRoutine.fromMap(routine.toMap()).schedule.startDate)
        val legacyMap = once.toMap().toMutableMap().apply {
            remove("startDate")
            put("isRecurring", false)
        }
        assertTrue(RoutineSchedule.fromMap(legacyMap).isRecurring)
    }

    @Test
    fun repositorySupportsCrudImmediateDisableAndExplicitMandateProposals() {
        val repository = InMemoryFocusRoutineRepository()
        val schedule = RoutineSchedule.daily(LocalTime.of(9, 0), LocalTime.of(10, 0))
        val created = repository.create("Routine", schedule)
        assertEquals(created, repository.find(created.id))
        assertTrue(repository.update(created.copy(name = "Éditée", preauthorizedStart = true)))
        assertEquals("Éditée", repository.find(created.id)?.name)
        val now = LocalDateTime.of(2026, 10, 19, 9, 30).atZone(zone)
        val proposals = repository.proposals(now, zone)
        assertEquals(false, proposals.single().requiresConfirmation)
        val notifier = FakeRoutineProposalNotifier()
        assertTrue(notifier.notify(proposals.single()))
        assertEquals(1, notifier.proposals.size)
        val alarm = FakeRoutineProposalScheduler()
        alarm.schedule(created.copy(isEnabled = true), now, zone)
        assertEquals(1, alarm.scheduledTriggers.size)

        repository.setEnabled(created.id, false)
        alarm.cancel(created.id)
        assertTrue(repository.proposals(now, zone).isEmpty())
        assertTrue(alarm.scheduledTriggers.isEmpty())
        assertNull(repository.nextTrigger(created.id, now, zone))
        assertTrue(repository.delete(created.id))
        assertNull(repository.find(created.id))
    }
}
