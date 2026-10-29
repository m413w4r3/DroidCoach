package dev.atlas.coach.app

import dev.atlas.coach.focus.FocusPlan
import dev.atlas.coach.focus.FocusPreset
import dev.atlas.coach.goals.GoalMetric
import dev.atlas.coach.goals.GoalRecord
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachQuickActionsTest {
    @Test
    fun defaultValuesAreCompactAndUseJPlusSeven() {
        val today = LocalDate.of(2026, 10, 29)
        assertEquals(DayScheduleInput("09:00", "18:00", ""), dayScheduleDefaults())
        assertEquals("Objectif de la semaine", goalQuickActionDefaults(today).title)
        assertEquals("2026-11-05", goalQuickActionDefaults(today).dueDateText)
        assertEquals("Préparer le bilan", focusQuickActionDefaults(" Préparer le bilan ").taskTitle)
        assertEquals("Tâche de focus", focusQuickActionDefaults("  ").taskTitle)
    }

    @Test
    fun dayScheduleAcceptsBoundaryTimesAndComposesOptionalPriority() {
        val result = validateDaySchedule(DayScheduleInput("00:00", "23:59", "  Rapport  ")).validValue()
        assertEquals("00:00", result.startTime)
        assertEquals("23:59", result.endTime)
        assertEquals("Rapport", result.priority)
        assertEquals(
            "Organise ma journée de 00:00 à 23:59. Priorité : Rapport. Propose un planning réaliste avec des pauses.",
            result.message,
        )
        assertTrue(validateDaySchedule(DayScheduleInput("09:00", "18:00", "p".repeat(120))) is QuickActionValidation.Valid)
        assertError(validateDaySchedule(DayScheduleInput("09:00", "18:00", "p".repeat(121))))
    }

    @Test
    fun dayScheduleRejectsMalformedAndOutOfRangeHours() {
        listOf("9:00", "09:0", "24:00", "12:60", "xx:00", " 09:00").forEach { time ->
            assertError(validateDaySchedule(DayScheduleInput(startTime = time)))
            assertError(validateDaySchedule(DayScheduleInput(endTime = time)))
        }
    }

    @Test
    fun goalValidationCoversTargetTitleAndNextActionBoundaries() {
        val valid = validateGoalQuickAction(GoalQuickActionInput(
            title = "g".repeat(GoalRecord.MAX_TITLE_LENGTH),
            metric = GoalMetric.FOCUS_MINUTES,
            targetText = Long.MAX_VALUE.toString(),
            dueDateText = "2026-11-05",
            nextAction = "a".repeat(GoalRecord.MAX_NEXT_ACTION_LENGTH),
        ), ZoneOffset.UTC).validValue()
        assertEquals(GoalRecord.MAX_TITLE_LENGTH, valid.title.length)
        assertEquals(Long.MAX_VALUE, valid.targetValue)
        assertEquals(GoalRecord.MAX_NEXT_ACTION_LENGTH, valid.nextAction.length)
        assertEquals(
            LocalDate.parse("2026-11-06").atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() - 1L,
            valid.dueAt,
        )

        assertError(validateGoalQuickAction(goalInput(title = " ")))
        assertError(validateGoalQuickAction(goalInput(title = "g".repeat(GoalRecord.MAX_TITLE_LENGTH + 1))))
        assertError(validateGoalQuickAction(goalInput(targetText = "0")))
        assertError(validateGoalQuickAction(goalInput(targetText = "-1")))
        assertError(validateGoalQuickAction(goalInput(targetText = "9223372036854775808")))
        assertError(validateGoalQuickAction(goalInput(nextAction = "a".repeat(GoalRecord.MAX_NEXT_ACTION_LENGTH + 1))))
    }

    @Test
    fun goalValidationRequiresValidDueDateAndManualTargetOne() {
        assertError(validateGoalQuickAction(goalInput(dueDateText = "2026-02-30")))
        assertError(validateGoalQuickAction(goalInput(dueDateText = "05/11/2026")))
        assertError(validateGoalQuickAction(goalInput(dueDateText = "1969-12-31")))
        assertEquals(1L, validateGoalQuickAction(goalInput(metric = GoalMetric.MANUAL, targetText = "1")).validValue().targetValue)
        assertError(validateGoalQuickAction(goalInput(metric = GoalMetric.MANUAL, targetText = "2")))
    }

    @Test
    fun focusValidationAcceptsCycleBoundsAndPresetDurations() {
        val one = validateFocusQuickAction(FocusQuickActionInput(
            preset = FocusPreset.VARIANT_10_2,
            taskTitle = "Lecture",
            cyclesText = "1",
        )).validValue()
        assertEquals(10 * 60L, one.workSeconds)
        assertEquals(2 * 60L, one.shortBreakSeconds)
        assertEquals(1, one.totalWorkCycles)
        assertEquals("Lecture", one.taskTitle)

        val twelve = validateFocusQuickAction(FocusQuickActionInput(
            preset = FocusPreset.POMODORO_25_5,
            taskTitle = "Rédaction",
            cyclesText = "12",
        )).validValue()
        assertEquals(25 * 60L, twelve.workSeconds)
        assertEquals(5 * 60L, twelve.shortBreakSeconds)
        assertEquals(12, twelve.totalWorkCycles)
        assertEquals("t".repeat(FocusPlan.MAX_TASK_TITLE_LENGTH), validateFocusQuickAction(
            FocusQuickActionInput(taskTitle = "t".repeat(FocusPlan.MAX_TASK_TITLE_LENGTH), cyclesText = "1"),
        ).validValue().taskTitle)
    }

    @Test
    fun focusValidationRejectsCycleAndTitleBoundaries() {
        listOf("0", "13", "-1", "", "1.5").forEach { cycles ->
            assertError(validateFocusQuickAction(FocusQuickActionInput(taskTitle = "Tâche", cyclesText = cycles)))
        }
        assertError(validateFocusQuickAction(FocusQuickActionInput(taskTitle = "  ", cyclesText = "1")))
        assertError(validateFocusQuickAction(FocusQuickActionInput(
            taskTitle = "t".repeat(FocusPlan.MAX_TASK_TITLE_LENGTH + 1),
            cyclesText = "1",
        )))
    }

    private fun goalInput(
        title: String = "Objectif synthétique",
        metric: GoalMetric = GoalMetric.FOCUS_MINUTES,
        targetText: String = "120",
        dueDateText: String = "2026-11-05",
        nextAction: String = "Lire la première page",
    ) = GoalQuickActionInput(title, metric, targetText, dueDateText, nextAction)

    private fun <T> QuickActionValidation<T>.validValue(): T = when (this) {
        is QuickActionValidation.Valid -> value
        is QuickActionValidation.Invalid -> throw AssertionError("Expected valid input, got: $message")
    }

    private fun assertError(result: QuickActionValidation<*>) {
        assertFalse("Expected a one-line validation error", result is QuickActionValidation.Valid)
        val invalid = result as QuickActionValidation.Invalid
        assertTrue(invalid.message.isNotBlank())
        assertFalse(invalid.message.contains('\n'))
    }
}
