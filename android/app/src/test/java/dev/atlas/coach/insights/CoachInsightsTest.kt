package dev.atlas.coach.insights

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachInsightsTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 4, 10)
    private val now = at(today, 12)

    @Test
    fun emptyDayWeekAndMonthReturnExplicitInsufficientResults() {
        listOf(InsightPeriod.DAY, InsightPeriod.WEEK, InsightPeriod.MONTH).forEach { period ->
            val report = CoachInsights.summarize(period, InsightFacts(now, zone))

            assertFalse("$period", report.enoughData)
            assertTrue(report.insufficientReasons.isNotEmpty())
            assertTrue(report.insufficientReasons.single().contains("donnée", ignoreCase = true))
            assertEquals(0L, report.metric("days_with_data").value)
            assertTrue(report.observations.isEmpty())
        }
    }

    @Test
    fun weekRequiresExactlyThreeDistinctLocalDaysWithData() {
        val twoDays = CoachInsights.summarize(
            InsightPeriod.WEEK,
            InsightFacts(now, zone, focusSessions = listOf(focus("a", today.minusDays(2)), focus("b", today.minusDays(1)))),
        )
        val threeDays = CoachInsights.summarize(
            InsightPeriod.WEEK,
            InsightFacts(
                now,
                zone,
                focusSessions = listOf(focus("a", today.minusDays(2)), focus("b", today.minusDays(1)), focus("c", today)),
            ),
        )

        assertFalse(twoDays.enoughData)
        assertTrue(twoDays.insufficientReasons.single().contains("2 jours renseignés"))
        assertTrue(threeDays.enoughData)
        assertTrue(threeDays.insufficientReasons.isEmpty())
    }

    @Test
    fun monthRequiresExactlySevenDistinctLocalDaysWithData() {
        val firstSixDates = (0..5).map { today.withDayOfMonth(1 + it * 5) }
        val sixDays = CoachInsights.summarize(
            InsightPeriod.MONTH,
            InsightFacts(nowUtcMillis = at(LocalDate.of(2026, 4, 30), 12), zoneId = zone, focusSessions = firstSixDates.mapIndexed { i, date -> focus("f$i", date) }),
        )
        val sevenDays = CoachInsights.summarize(
            InsightPeriod.MONTH,
            InsightFacts(
                nowUtcMillis = at(LocalDate.of(2026, 4, 30), 12),
                zoneId = zone,
                focusSessions = (firstSixDates + LocalDate.of(2026, 4, 30)).mapIndexed { i, date -> focus("f$i", date) },
            ),
        )

        assertFalse(sixDays.enoughData)
        assertEquals(6L, sixDays.metric("days_with_data").value)
        assertTrue(sevenDays.enoughData)
        assertEquals(7L, sevenDays.metric("days_with_data").value)
    }

    @Test
    fun sufficientDataCombinesLocalFactsIntoStructuredFrenchMetrics() {
        val dueDates = setOf(today.minusDays(2).toEpochDay(), today.minusDays(1).toEpochDay(), today.toEpochDay())
        val report = CoachInsights.summarize(
            InsightPeriod.WEEK,
            InsightFacts(
                nowUtcMillis = now,
                zoneId = zone,
                focusSessions = listOf(
                    focus("focus-a", today.minusDays(2), seconds = 1_500, cycles = 1),
                    focus("focus-b", today.minusDays(1), seconds = 1_800, cycles = 2),
                ),
                habitHistories = listOf(HabitFact("habit-a", "Lecture", dueDates, dueDates - today.minusDays(1).toEpochDay(), streak = 2)),
                planDays = listOf(
                    plan(today.minusDays(1), listOf(activity("a", InsightActivityStatus.DONE), activity("b", InsightActivityStatus.MISSED))),
                    plan(
                        today,
                        listOf(
                            activity("c", InsightActivityStatus.DONE),
                            activity("d", InsightActivityStatus.PLANNED),
                            activity("e", InsightActivityStatus.DONE),
                            activity("f", InsightActivityStatus.MISSED),
                        ),
                    ),
                ),
                goals = listOf(
                    GoalFact("goal-a", "Lire", "HABIT_CHECKINS", target = 10, current = 4, status = InsightGoalStatus.ACTIVE, dueAtUtcMillis = at(today.plusDays(3), 9)),
                    GoalFact("goal-b", "Ancien objectif", "MANUAL", target = 1, current = 1, status = InsightGoalStatus.COMPLETED),
                ),
                agentEffects = listOf(EffectFact("effect-a", "update_plan", InsightEffectState.EXECUTED, at(today, 10))),
            ),
        )

        assertTrue(report.enoughData)
        assertEquals(3L, report.metric("days_with_data").value)
        assertEquals(55L, report.metric("focus_minutes_completed").value)
        assertEquals(3L, report.metric("focus_cycles_completed").value)
        assertEquals(3L, report.metric("plan_done").value)
        assertEquals(2L, report.metric("plan_missed").value)
        assertEquals(1L, report.metric("plan_planned").value)
        assertEquals(1L, report.metric("active_goals").value)
        assertEquals(1L, report.metric("agent_effect_executed").value)
        val goalProgress = report.metrics.single { it.key == "goal_progress:goal-a" }
        assertEquals(4L, goalProgress.value)
        assertEquals(10L, goalProgress.targetValue)
        assertEquals("HABIT_CHECKINS", goalProgress.unit)
        assertEquals("Échéance : 2026-04-13", goalProgress.detailFr)
        assertTrue(report.observations.any { it.code == "plan_completion_rate" })
        assertTrue(report.observations.all { it.text.isNotBlank() })
    }

    @Test
    fun cancelledFocusSessionIsAnInterruptionAndNeverCompletedTime() {
        val report = CoachInsights.summarize(
            InsightPeriod.DAY,
            InsightFacts(
                now,
                zone,
                focusSessions = listOf(focus("cancelled", today, FocusTerminalStatus.CANCELLED, seconds = 3_600, cycles = 2)),
            ),
        )

        assertTrue(report.enoughData)
        assertEquals(0L, report.metric("focus_minutes_completed").value)
        assertEquals(2L, report.metric("focus_cycles_completed").value)
        assertEquals(1L, report.metric("focus_interruptions").value)
    }

    @Test
    fun nonUtcZoneUsesLocalDateAtDayBoundary() {
        val losAngeles = ZoneId.of("America/Los_Angeles")
        val localNow = Instant.parse("2025-01-01T01:00:00Z").toEpochMilli() // 2024-12-31 17:00 locally
        val report = CoachInsights.summarize(
            InsightPeriod.DAY,
            InsightFacts(
                nowUtcMillis = localNow,
                zoneId = losAngeles,
                focusSessions = listOf(
                    FocusFact("included", Instant.parse("2025-01-01T00:30:00Z").toEpochMilli(), FocusTerminalStatus.FINISHED, 1, 1_200),
                    FocusFact("next-day", Instant.parse("2025-01-01T08:30:00Z").toEpochMilli(), FocusTerminalStatus.FINISHED, 1, 9_000),
                ),
            ),
        )

        assertTrue(report.enoughData)
        assertEquals(1L, report.metric("days_with_data").value)
        assertEquals(20L, report.metric("focus_minutes_completed").value)
    }

    @Test
    fun daylightSavingTransitionGroupsFactsByLocalDate() {
        val newYork = ZoneId.of("America/New_York")
        val report = CoachInsights.summarize(
            InsightPeriod.WEEK,
            InsightFacts(
                nowUtcMillis = Instant.parse("2026-03-10T12:00:00Z").toEpochMilli(),
                zoneId = newYork,
                focusSessions = listOf(
                    FocusFact("before-gap", Instant.parse("2026-03-08T06:30:00Z").toEpochMilli(), FocusTerminalStatus.FINISHED, 1, 600),
                    FocusFact("after-gap", Instant.parse("2026-03-08T07:30:00Z").toEpochMilli(), FocusTerminalStatus.FINISHED, 1, 600),
                    // 03:59Z is still March 8 locally; 04:00Z is midnight March 9 after the DST change.
                    FocusFact("before-midnight", Instant.parse("2026-03-09T03:59:00Z").toEpochMilli(), FocusTerminalStatus.FINISHED, 1, 2_800),
                    FocusFact("after-midnight", Instant.parse("2026-03-09T04:00:00Z").toEpochMilli(), FocusTerminalStatus.FINISHED, 1, 1),
                ),
            ),
        )

        assertFalse(report.enoughData)
        assertEquals(2L, report.metric("days_with_data").value)
        assertEquals(66L, report.metric("focus_minutes_completed").value)
        val focusPattern = report.observation("focus_most_active_day")
        assertNotNull(focusPattern)
        assertTrue(focusPattern!!.text.startsWith("Jour le plus actif en Focus : dimanche"))
    }

    @Test
    fun patternsAppearAtExactTwoDayFiveActivityAndTwoHabitThresholds() {
        val focusDays = listOf(focus("focus-a", today.minusDays(1), seconds = 600), focus("focus-b", today, seconds = 1_200))
        val activities = listOf(
            activity("a", InsightActivityStatus.DONE),
            activity("b", InsightActivityStatus.DONE),
            activity("c", InsightActivityStatus.DONE),
            activity("d", InsightActivityStatus.MISSED),
            activity("e", InsightActivityStatus.MISSED),
        )
        val dueDays = setOf(today.minusDays(2).toEpochDay(), today.minusDays(1).toEpochDay(), today.toEpochDay())
        val habits = listOf(
            HabitFact("habit-a", "Lecture", dueDays, dueDays, streak = 3),
            HabitFact("habit-b", "Marche", dueDays, dueDays - today.toEpochDay(), streak = 2),
        )
        val report = CoachInsights.summarize(
            InsightPeriod.WEEK,
            InsightFacts(
                now,
                zone,
                focusSessions = focusDays,
                planDays = listOf(plan(today, activities)),
                habitHistories = habits,
            ),
        )

        assertNotNull(report.observation("focus_most_active_day"))
        assertNotNull(report.observation("plan_completion_rate"))
        assertNotNull(report.observation("habit_most_regular"))
        assertTrue(report.observation("habit_completion_rate")!!.text.contains("100 %"))
    }

    @Test
    fun noPatternIsEmittedBelowItsOwnThreshold() {
        val dueDays = setOf(today.minusDays(2).toEpochDay(), today.minusDays(1).toEpochDay(), today.toEpochDay())
        val report = CoachInsights.summarize(
            InsightPeriod.WEEK,
            InsightFacts(
                now,
                zone,
                focusSessions = listOf(focus("first", today), focus("second", today, seconds = 1_200)),
                planDays = listOf(plan(today, listOf(
                    activity("a", InsightActivityStatus.DONE),
                    activity("b", InsightActivityStatus.DONE),
                    activity("c", InsightActivityStatus.MISSED),
                    activity("d", InsightActivityStatus.MISSED),
                ))),
                habitHistories = listOf(
                    HabitFact("qualifies", "Lecture", dueDays, dueDays, streak = 3),
                    HabitFact(
                        "too-few-days",
                        "Marche",
                        setOf(today.minusDays(1).toEpochDay(), today.toEpochDay()),
                        dueDays,
                        streak = 2,
                    ),
                ),
            ),
        )

        assertNull(report.observation("focus_most_active_day")) // Two sessions, but only one session day.
        assertNull(report.observation("plan_completion_rate")) // Four decided activities.
        assertNull(report.observation("habit_most_regular")) // Only one habit has three due days.
    }

    @Test
    fun reportOrderingDoesNotDependOnInputListOrdering() {
        val dueDays = setOf(today.minusDays(2).toEpochDay(), today.minusDays(1).toEpochDay(), today.toEpochDay())
        val base = InsightFacts(
            now,
            zone,
            focusSessions = listOf(
                focus("focus-b", today, seconds = 1_800, cycles = 2),
                focus("focus-a", today.minusDays(1), seconds = 1_200, cycles = 1),
            ),
            planDays = listOf(
                plan(today, listOf(activity("b", InsightActivityStatus.MISSED), activity("a", InsightActivityStatus.DONE))),
                plan(today.minusDays(1), listOf(activity("c", InsightActivityStatus.DONE), activity("d", InsightActivityStatus.PLANNED), activity("e", InsightActivityStatus.DONE))),
            ),
            habitHistories = listOf(
                HabitFact("habit-b", "Marche", dueDays, dueDays - today.toEpochDay(), streak = 2),
                HabitFact("habit-a", "Lecture", dueDays, dueDays, streak = 3),
            ),
            goals = listOf(
                GoalFact("goal-b", "Écrire", "MANUAL", 1, 0, InsightGoalStatus.ACTIVE),
                GoalFact("goal-a", "Lire", "HABIT_CHECKINS", 12, 5, InsightGoalStatus.ACTIVE, at(today.plusDays(3), 9)),
            ),
            agentEffects = listOf(
                EffectFact("effect-b", "change_plan", InsightEffectState.REJECTED, at(today, 11)),
                EffectFact("effect-a", "update_plan", InsightEffectState.EXECUTED, at(today.minusDays(1), 10)),
            ),
        )
        val shuffled = base.copy(
            focusSessions = base.focusSessions.reversed(),
            planDays = base.planDays.reversed().map { it.copy(activities = it.activities.reversed()) },
            habitHistories = base.habitHistories.reversed(),
            goals = base.goals.reversed(),
            agentEffects = base.agentEffects.reversed(),
        )

        assertEquals(
            CoachInsights.summarize(InsightPeriod.WEEK, base),
            CoachInsights.summarize(InsightPeriod.WEEK, shuffled),
        )
    }

    private fun CoachInsightReport.metric(key: String): InsightMetric = metrics.single { it.key == key }

    private fun CoachInsightReport.observation(code: String): InsightObservation? = observations.singleOrNull { it.code == code }

    private fun focus(
        id: String,
        date: LocalDate,
        status: FocusTerminalStatus = FocusTerminalStatus.FINISHED,
        seconds: Long = 1_500,
        cycles: Int = 1,
    ) = FocusFact(id, at(date, 9), status, cycles, seconds, taskTitle = "Tâche")

    private fun plan(date: LocalDate, activities: List<PlanActivityFact>) = PlanDayFact(date, activities)

    private fun activity(id: String, status: InsightActivityStatus) = PlanActivityFact(
        id = id,
        title = "Activité $id",
        kind = "FOCUS",
        status = status,
        priority = InsightActivityPriority.REQUIRED,
    )

    private fun at(date: LocalDate, hour: Int): Long = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
}
