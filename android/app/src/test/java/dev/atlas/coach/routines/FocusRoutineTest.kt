package dev.atlas.coach.routines

import java.time.DayOfWeek
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
}
