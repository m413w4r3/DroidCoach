package dev.atlas.coach.app

import dev.atlas.coach.plan.ActivityStatus
import dev.atlas.coach.plan.DayPlan
import dev.atlas.coach.plan.PlanActivity
import dev.atlas.coach.plan.PlanState
import dev.atlas.coach.plan.Priority
import java.time.LocalDate

/** Read-only projection of the approved plan onto HOME. */
internal sealed interface HomeNextActionState {
    data object Neutral : HomeNextActionState
    data object NonePlanned : HomeNextActionState
    data class Selected(val activity: PlanActivity) : HomeNextActionState
}

/** Selects explicit progress first, then the earliest active planned slot, then the future. */
internal fun selectHomeNextAction(
    plan: DayPlan?,
    today: LocalDate,
    currentMinute: Int,
): HomeNextActionState {
    require(currentMinute in 0..1439) { "currentMinute must be in 0..1439" }
    if (plan == null || plan.state != PlanState.APPROVED || plan.localDate != today.toString()) {
        return HomeNextActionState.Neutral
    }

    val inProgress = plan.activities
        .asSequence()
        .filter { it.status == ActivityStatus.IN_PROGRESS }
        .minByOrNull { it.scheduledLocal.startMinute }
    if (inProgress != null) return HomeNextActionState.Selected(inProgress)

    val plannedInCurrentSlot = plan.activities
        .asSequence()
        .filter {
            it.status == ActivityStatus.PLANNED &&
                it.scheduledLocal.startMinute <= currentMinute &&
                currentMinute < it.scheduledLocal.startMinute + it.scheduledLocal.durationMinutes
        }
        .minByOrNull { it.scheduledLocal.startMinute }
    if (plannedInCurrentSlot != null) return HomeNextActionState.Selected(plannedInCurrentSlot)

    val futurePlanned = plan.activities
        .asSequence()
        .filter {
            it.status == ActivityStatus.PLANNED &&
                it.scheduledLocal.startMinute > currentMinute
        }
        .minByOrNull { it.scheduledLocal.startMinute }
    return futurePlanned?.let(HomeNextActionState::Selected) ?: HomeNextActionState.NonePlanned
}

internal fun homeNextActionAccessibilityLabel(
    activity: PlanActivity,
    goalTitle: String?,
): String {
    val startMinute = activity.scheduledLocal.startMinute
    val timeRange = "${PlanController.timeLabel(startMinute)} – ${PlanController.endTimeLabel(startMinute, activity.scheduledLocal.durationMinutes)}"
    val status = if (activity.status == ActivityStatus.IN_PROGRESS) "En cours" else "Prévue"
    val goal = goalTitle?.takeIf(String::isNotBlank)?.let { ", objectif $it" }.orEmpty()
    return "Prochaine action : ${activity.title}. $status, $timeRange, " +
        "${activity.scheduledLocal.durationMinutes} minutes, priorité ${homePriorityLabel(activity.priority)}$goal."
}

internal fun homePriorityLabel(priority: Priority): String = when (priority) {
    Priority.REQUIRED -> "requise"
    Priority.IMPORTANT -> "importante"
    Priority.OPTIONAL -> "optionnelle"
}
