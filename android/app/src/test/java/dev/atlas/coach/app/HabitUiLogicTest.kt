package dev.atlas.coach.app

import dev.atlas.coach.habits.Habit
import dev.atlas.coach.habits.HabitDayStatus
import dev.atlas.coach.habits.HabitHistory
import dev.atlas.coach.habits.HabitHistoryDay
import dev.atlas.coach.habits.HabitSchedule
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitUiLogicTest {
    private val today = LocalDate.of(2026, 10, 26)

    @Test
    fun dueTodayHabitsAreListedBeforeNonDueHabits() {
        val due = habit("due", HabitSchedule.daily())
        val notDue = habit("not-due", HabitSchedule.weekdays(setOf(DayOfWeek.TUESDAY)))
        val anotherDue = habit("another-due", HabitSchedule.daily())
        val result = sortHabitsForToday(
            habits = listOf(notDue, due, anotherDue),
            histories = mapOf(
                due.id to history(due.id, day(today, due = true)),
                notDue.id to history(notDue.id, day(today, due = false)),
                anotherDue.id to history(anotherDue.id, day(today, due = true)),
            ),
            today = today,
        )

        assertEquals(listOf(due, anotherDue, notDue), result)
    }

    @Test
    fun historicalDayStatusSeparatesDoneMissedAndNotPlanned() {
        val yesterday = today.minusDays(1)
        assertEquals(HabitDayUiState.DONE, day(yesterday, due = true, checked = true).uiState(today))
        assertEquals(HabitDayUiState.MISSED, day(yesterday, due = true).uiState(today))
        assertEquals(HabitDayUiState.NOT_PLANNED, day(yesterday, due = false).uiState(today))
        assertEquals(HabitDayUiState.DUE_TODAY, day(today, due = true).uiState(today))
    }

    @Test
    fun progressUsesCountsRateAndStreaksFromHabitHistory() {
        val history = HabitHistory(
            habitId = "walk",
            startDateEpochDay = today.minusDays(29).toEpochDay(),
            endDateEpochDay = today.toEpochDay(),
            days = emptyList(),
            dueDays = 10,
            completedDueDays = 4,
            completionRate = 0.4,
            currentStreak = 2,
            bestStreak = 5,
            notDueDays = 20,
        )

        assertEquals(HabitProgress(4, 10, 0.4, 2, 5), HabitProgress.from(history))
        assertEquals(40, HabitProgress.from(history).completionPercent)
    }

    @Test
    fun frequencyLabelCoversAllScheduleKinds() {
        assertEquals("Chaque jour", HabitSchedule.daily().frequencyLabel())
        assertEquals("Les Lun, Mer", HabitSchedule.weekdays(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)).frequencyLabel())
        assertEquals("3 fois par semaine", HabitSchedule.timesPerWeek(3).frequencyLabel())
        assertTrue(HabitSchedule.alternatingDays(today).frequencyLabel().contains("Un jour sur deux"))
    }

    @Test
    fun listStateDistinguishesEmptyAndPopulated() {
        assertEquals(HabitListState.EMPTY, habitListState(emptyList()))
        assertEquals(HabitListState.WITH_HABITS, habitListState(listOf(habit("one", HabitSchedule.daily()))))
    }

    private fun habit(id: String, schedule: HabitSchedule) = Habit(
        id = id,
        name = id,
        schedule = schedule,
        createdAtUtcMillis = 1L,
        createdOnEpochDay = today.minusDays(60).toEpochDay(),
    )

    private fun history(id: String, day: HabitHistoryDay) = HabitHistory(
        habitId = id,
        startDateEpochDay = day.dateEpochDay,
        endDateEpochDay = day.dateEpochDay,
        days = listOf(day),
        dueDays = if (day.isDue) 1 else 0,
        completedDueDays = if (day.isDue && day.isCheckedIn) 1 else 0,
        completionRate = if (day.isDue && day.isCheckedIn) 1.0 else 0.0,
        currentStreak = if (day.isCheckedIn) 1 else 0,
    )

    private fun day(date: LocalDate, due: Boolean, checked: Boolean = false) = HabitHistoryDay(
        dateEpochDay = date.toEpochDay(),
        status = when {
            checked -> HabitDayStatus.DONE
            due -> HabitDayStatus.DUE
            else -> HabitDayStatus.NOT_DUE
        },
        isDue = due,
        isCheckedIn = checked,
    )
}
