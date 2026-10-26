package dev.atlas.coach.app

import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.ActivityStatus
import dev.atlas.coach.plan.DayPlan
import dev.atlas.coach.plan.LocalSchedule
import dev.atlas.coach.plan.PlanActivity
import dev.atlas.coach.plan.PlanOrigin
import dev.atlas.coach.plan.PlanState
import dev.atlas.coach.plan.Priority
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNextActionTest {
    private val today = LocalDate.of(2026, 10, 26)
    private val planId = "00000000-0000-0000-0000-000000000001"
    private val revisionId = "00000000-0000-0000-0000-000000000002"

    private fun activity(
        id: Int,
        title: String,
        startMinute: Int,
        status: ActivityStatus = ActivityStatus.PLANNED,
        durationMinutes: Int = 45,
        priority: Priority = Priority.IMPORTANT,
        goalId: String? = null,
        date: LocalDate = today,
    ) = PlanActivity(
        activityId = "00000000-0000-0000-0000-${id.toString().padStart(12, '0')}",
        planId = planId,
        kind = ActivityKind.FOCUS,
        title = title,
        status = status,
        scheduledLocal = LocalSchedule(
            localDate = date.toString(),
            startMinute = startMinute,
            durationMinutes = durationMinutes,
            timezone = "Europe/Paris",
        ),
        priority = priority,
        goalId = goalId,
        createdRevisionId = revisionId,
        lastRevisionId = revisionId,
    )

    private fun plan(
        activities: List<PlanActivity> = emptyList(),
        date: LocalDate = today,
        state: PlanState = PlanState.APPROVED,
    ) = DayPlan(
        planId = planId,
        localDate = date.toString(),
        timezone = "Europe/Paris",
        version = 1,
        currentRevisionId = revisionId,
        state = state,
        activities = activities,
        createdAt = 0L,
        updatedAt = 0L,
        approvedAt = if (state == PlanState.APPROVED) 0L else null,
        origin = PlanOrigin.LOCAL,
    )

    @Test
    fun selectsEarliestUpcomingPlannedActivityByStartMinute() {
        val later = activity(3, "Plus tard", startMinute = 12 * 60)
        val next = activity(4, "Prochaine", startMinute = 10 * 60)

        val result = selectHomeNextAction(plan(listOf(later, next)), today, currentMinute = 9 * 60)

        assertEquals(next, (result as HomeNextActionState.Selected).activity)
    }

    @Test
    fun plannedActivityWhoseSlotContainsNowIsSelectedBeforeFutureActivity() {
        val currentSlot = activity(41, "Créneau en cours", startMinute = 8 * 60 + 30, durationMinutes = 60)
        val future = activity(42, "Plus tard", startMinute = 10 * 60 + 30)

        val result = selectHomeNextAction(
            plan(listOf(future, currentSlot)),
            today,
            currentMinute = 9 * 60,
        )

        assertEquals(currentSlot, (result as HomeNextActionState.Selected).activity)
    }

    @Test
    fun plannedActivityWhoseSlotEndedIsExcluded() {
        val ended = activity(43, "Créneau terminé", startMinute = 8 * 60, durationMinutes = 60)
        val future = activity(44, "Prochaine", startMinute = 10 * 60 + 30)

        val result = selectHomeNextAction(
            plan(listOf(ended, future)),
            today,
            currentMinute = 9 * 60,
        )

        assertEquals(future, (result as HomeNextActionState.Selected).activity)
    }

    @Test
    fun inProgressActivityTakesPriorityOverPlannedActivityWhoseSlotContainsNow() {
        val currentSlot = activity(45, "Créneau planifié", startMinute = 8 * 60 + 30, durationMinutes = 60)
        val inProgress = activity(
            46,
            "En cours explicitement",
            startMinute = 7 * 60 + 30,
            status = ActivityStatus.IN_PROGRESS,
        )

        val result = selectHomeNextAction(
            plan(listOf(currentSlot, inProgress)),
            today,
            currentMinute = 9 * 60,
        )

        assertEquals(inProgress, (result as HomeNextActionState.Selected).activity)
    }

    @Test
    fun earliestOfTwoPlannedActivitiesWhoseSlotsContainNowIsSelected() {
        val laterCurrentSlot = activity(47, "Créneau plus tardif", startMinute = 8 * 60 + 45, durationMinutes = 60)
        val earliestCurrentSlot = activity(48, "Premier créneau", startMinute = 8 * 60 + 30, durationMinutes = 60)

        val result = selectHomeNextAction(
            plan(listOf(laterCurrentSlot, earliestCurrentSlot)),
            today,
            currentMinute = 9 * 60,
        )

        assertEquals(earliestCurrentSlot, (result as HomeNextActionState.Selected).activity)
    }

    @Test
    fun inProgressActivityTakesPriorityOverFuturePlannedActivity() {
        val future = activity(5, "À venir", startMinute = 10 * 60)
        val inProgress = activity(6, "En cours", startMinute = 8 * 60, status = ActivityStatus.IN_PROGRESS)

        val result = selectHomeNextAction(plan(listOf(future, inProgress)), today, currentMinute = 9 * 60)

        assertEquals(inProgress, (result as HomeNextActionState.Selected).activity)
    }

    @Test
    fun terminalAndUnsupportedStatusesAreExcluded() {
        val terminal = listOf(
            ActivityStatus.CANCELLED,
            ActivityStatus.MISSED,
            ActivityStatus.DONE,
            ActivityStatus.POSTPONED,
        ).mapIndexed { index, status -> activity(10 + index, status.name, 11 * 60, status) }

        val result = selectHomeNextAction(plan(terminal), today, currentMinute = 9 * 60)

        assertEquals(HomeNextActionState.NonePlanned, result)
    }

    @Test
    fun planForAnotherLocalDateIsNeutral() {
        val yesterday = today.minusDays(1)
        val result = selectHomeNextAction(
            plan(listOf(activity(20, "Hier", 10 * 60, date = yesterday)), date = yesterday),
            today,
            currentMinute = 9 * 60,
        )

        assertEquals(HomeNextActionState.Neutral, result)
    }

    @Test
    fun absentPlanIsNeutral() {
        assertEquals(HomeNextActionState.Neutral, selectHomeNextAction(null, today, currentMinute = 9 * 60))
    }

    @Test
    fun unapprovedPlanIsNeutral() {
        val draft = plan(state = PlanState.DRAFT)

        assertEquals(HomeNextActionState.Neutral, selectHomeNextAction(draft, today, currentMinute = 9 * 60))
    }

    @Test
    fun approvedPlanWithoutUpcomingActivitiesShowsExplicitEmptyState() {
        val result = selectHomeNextAction(plan(), today, currentMinute = 9 * 60)

        assertEquals(HomeNextActionState.NonePlanned, result)
    }

    @Test
    fun pastPlannedActivityRemainsPlannedAndIsNotShownAsUpcoming() {
        val past = activity(30, "Activité passée", startMinute = 8 * 60)
        val result = selectHomeNextAction(plan(listOf(past)), today, currentMinute = 9 * 60)

        assertEquals(HomeNextActionState.NonePlanned, result)
        assertEquals(ActivityStatus.PLANNED, past.status)
        assertFalse(past.status == ActivityStatus.DONE)
    }

    @Test
    fun accessibleLabelIncludesTitleTimeDurationPriorityAndGoal() {
        val activity = activity(
            40,
            "Préparer le dossier",
            startMinute = 9 * 60 + 30,
            durationMinutes = 45,
            priority = Priority.REQUIRED,
            goalId = "00000000-0000-0000-0000-000000000099",
        )

        val label = homeNextActionAccessibilityLabel(activity, "Dossier final")

        assertTrue(label.contains("Préparer le dossier"))
        assertTrue(label.contains("09:30 – 10:15"))
        assertTrue(label.contains("45 minutes"))
        assertTrue(label.contains("priorité requise"))
        assertTrue(label.contains("objectif Dossier final"))
    }
}
