package dev.atlas.coach.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.atlas.coach.focus.FocusStatus
import dev.atlas.coach.focus.ReefFocusStats
import dev.atlas.coach.insights.CoachInsightReport
import dev.atlas.coach.insights.CoachInsights
import dev.atlas.coach.insights.EffectFact
import dev.atlas.coach.insights.FocusFact
import dev.atlas.coach.insights.FocusTerminalStatus
import dev.atlas.coach.insights.HabitFact
import dev.atlas.coach.insights.InsightActivityPriority
import dev.atlas.coach.insights.InsightActivityStatus
import dev.atlas.coach.insights.InsightEffectState
import dev.atlas.coach.insights.InsightFacts
import dev.atlas.coach.insights.InsightGoalStatus
import dev.atlas.coach.insights.InsightMetric
import dev.atlas.coach.insights.InsightPeriod
import dev.atlas.coach.insights.PlanActivityFact
import dev.atlas.coach.insights.PlanDayFact
import dev.atlas.coach.plan.DayPlan
import dev.atlas.coach.plan.Priority
import dev.atlas.coach.sync.agent.ProposalState
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private data class CoachInsightsSnapshot(
    val report: CoachInsightReport,
    val planDays: List<PlanDayFact>,
    val habitFacts: List<HabitFact>,
    val agentEffects: List<EffectFact>,
)

private data class SummaryGroup(val label: String, val metricKeys: List<String>)

private val summaryGroups = listOf(
    SummaryGroup("Focus", listOf("focus_minutes_completed", "focus_cycles_completed", "focus_interruptions")),
    SummaryGroup("Planning", listOf("plan_done", "plan_missed")),
    SummaryGroup("Habitudes", listOf("habits_checked", "habits_due")),
    SummaryGroup("Objectifs", listOf("active_goals")),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CoachInsightsScreen(
    controller: AtlasController,
    planController: PlanController,
    revision: Int = controller.revision,
) {
    var period by rememberSaveable { mutableStateOf(InsightPeriod.DAY) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val planRevision = planController.revision
    val snapshot = remember(controller, planController, revision, planRevision, period) {
        buildCoachInsightsSnapshot(controller, planController, period)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                InsightPeriod.DAY to ("Jour" to "coach_insights_period_day"),
                InsightPeriod.WEEK to ("Semaine" to "coach_insights_period_week"),
                InsightPeriod.MONTH to ("Mois" to "coach_insights_period_month"),
            ).forEach { (candidate, labelAndTag) ->
                CoachSegmentChoice(
                    label = labelAndTag.first,
                    selected = period == candidate,
                    testTag = labelAndTag.second,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        period = candidate
                        showDetails = false
                    },
                )
            }
        }

        Text(
            text = "Synthèses locales, sans envoi",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!snapshot.report.enoughData) {
            Column(
                modifier = Modifier.fillMaxWidth().testTag("coach_insights_insufficient"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Données insuffisantes", style = MaterialTheme.typography.titleMedium)
                snapshot.report.insufficientReasons.forEach { reason ->
                    Text(reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            val metricsByKey = snapshot.report.metrics.associateBy(InsightMetric::key)
            summaryGroups.forEach { group ->
                val metrics = group.metricKeys.mapNotNull(metricsByKey::get)
                if (metrics.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(group.label, style = MaterialTheme.typography.labelLarge)
                        metrics.forEach { metric ->
                            SummaryMetric(metric)
                        }
                    }
                }
            }
            snapshot.report.observations.firstOrNull()?.let { observation ->
                Text(
                    text = observation.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("coach_insights_observation_${observation.code}"),
                )
            }

            OutlinedButton(
                onClick = { showDetails = !showDetails },
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("coach_insights_details_toggle"),
            ) {
                Text(if (showDetails) "Masquer les détails" else "Détails")
            }

            if (showDetails) {
                CoachInsightsDetails(snapshot)
            }
        }
    }
}

@Composable
private fun SummaryMetric(metric: InsightMetric) {
    val value = formatMetricValue(metric.value)
    val compactValue = when (metric.key) {
        "focus_minutes_completed" -> "$value min"
        "focus_cycles_completed" -> "$value cycles"
        "focus_interruptions" -> "$value annulées"
        "plan_done" -> "$value réalisées"
        "plan_missed" -> "$value manquées"
        "habits_checked" -> "$value cochées"
        "habits_due" -> "$value dues"
        "active_goals" -> "$value actifs"
        else -> "${metric.label} $value ${metric.unit}"
    }
    Text(
        text = compactValue,
        modifier = Modifier
            .testTag("coach_insights_metric_${metric.key}")
            .semantics { contentDescription = "${metric.label} : $value ${metric.unit}" },
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun CoachInsightsDetails(snapshot: CoachInsightsSnapshot) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        snapshot.report.metrics.forEach { metric ->
            Text(
                text = "${metric.label} : ${formatMetricValue(metric.value)} ${metric.unit}" +
                    listOfNotNull(metric.targetValue?.let { " / ${formatMetricValue(it)}" }, metric.detailFr?.let { " · $it" }).joinToString(""),
                modifier = Modifier.testTag("coach_insights_metric_${metric.key}"),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        snapshot.report.observations.forEach { observation ->
            Text(
                text = observation.text,
                modifier = Modifier.testTag("coach_insights_observation_${observation.code}"),
            )
        }
        snapshot.planDays.forEach { day ->
            day.activities.forEach { activity ->
                Text("${day.localDate} · ${activityStatusLabel(activity.status)} · ${activity.title}")
            }
        }
        snapshot.habitFacts.forEach { habit ->
            Text("Habitude · ${habit.name}")
        }
        snapshot.agentEffects.forEach { effect ->
            Text("${effect.actionType} · ${effect.terminalState.name.lowercase(Locale.ROOT)}")
        }
    }
}

private fun buildCoachInsightsSnapshot(
    controller: AtlasController,
    planController: PlanController,
    period: InsightPeriod,
): CoachInsightsSnapshot {
    val nowUtcMillis = System.currentTimeMillis()
    val zoneId = ZoneId.of(planController.timezone)
    val today = Instant.ofEpochMilli(nowUtcMillis).atZone(zoneId).toLocalDate()
    val windowDays = when (period) {
        InsightPeriod.DAY -> 1
        InsightPeriod.WEEK -> 7
        InsightPeriod.MONTH -> 30
    }
    val startDate = today.minusDays((windowDays - 1).toLong())

    val focusFacts = controller.focusHistory.mapNotNull { session ->
        val terminalStatus = when (session.status) {
            FocusStatus.FINISHED -> FocusTerminalStatus.FINISHED
            FocusStatus.CANCELLED -> FocusTerminalStatus.CANCELLED
            else -> return@mapNotNull null
        }
        val stats = ReefFocusStats.from(session)
        FocusFact(
            id = session.id,
            startedAtUtcMillis = session.createdAtUtcMillis,
            terminalStatus = terminalStatus,
            completedWorkCycles = stats.completedCycles,
            workSeconds = stats.focusTimeMillis / 1_000L,
            taskTitle = session.taskTitle,
        )
    }

    val habitHistoryDays = if (period == InsightPeriod.MONTH) 30 else 7
    val habitFacts = controller.activeHabits.map { habit ->
        val history = controller.habits.history(habit.id, today, habitHistoryDays)
        HabitFact(
            id = habit.id,
            name = habit.name,
            dueDays = history.days.filter { it.isDue }.mapTo(sortedSetOf()) { it.dateEpochDay },
            completedDays = history.days.filter { it.isCheckedIn }.mapTo(sortedSetOf()) { it.dateEpochDay },
            streak = history.currentStreak,
        )
    }

    val planDays = generateSequence(startDate) { date ->
        date.plusDays(1).takeUnless { it.isAfter(today) }
    }.mapNotNull { date ->
        val plan = if (date == today) {
            planController.plan?.takeIf { it.localDate == today.toString() }
        } else {
            planController.planForDate(date)
        }
        plan?.toInsightPlanDay(date)
    }.toList()

    val goalFacts = controller.coachGoals.map { entry ->
        val goal = entry.record
        dev.atlas.coach.insights.GoalFact(
            id = goal.id,
            title = goal.title,
            metric = goal.metric.wireValue,
            target = goal.targetValue,
            current = entry.progress.currentValue,
            status = InsightGoalStatus.valueOf(goal.status.name),
            dueAtUtcMillis = goal.dueAt,
        )
    }

    val effectFacts = controller.agentEffectHistory.mapNotNull { record ->
        if (!record.state.terminal) return@mapNotNull null
        val terminalState = when (record.state) {
            ProposalState.EXECUTED -> InsightEffectState.EXECUTED
            ProposalState.REJECTED -> InsightEffectState.REJECTED
            ProposalState.EXPIRED -> InsightEffectState.EXPIRED
            ProposalState.CANCELLED -> InsightEffectState.CANCELLED
            ProposalState.PROPOSED, ProposalState.PENDING_CONFIRMATION, ProposalState.ACCEPTED -> return@mapNotNull null
        }
        EffectFact(
            id = record.proposalId,
            actionType = record.actionType,
            terminalState = terminalState,
            atUtcMillis = record.atUtcMillis,
        )
    }

    val report = CoachInsights.summarize(
        period,
        InsightFacts(
            nowUtcMillis = nowUtcMillis,
            zoneId = zoneId,
            focusSessions = focusFacts,
            habitHistories = habitFacts,
            planDays = planDays,
            goals = goalFacts,
            agentEffects = effectFacts,
        ),
    )
    val periodAgentEffects = effectFacts.filter { effect ->
        val effectDate = Instant.ofEpochMilli(effect.atUtcMillis).atZone(zoneId).toLocalDate()
        !effectDate.isBefore(startDate) && !effectDate.isAfter(today)
    }
    return CoachInsightsSnapshot(report, planDays, habitFacts, periodAgentEffects)
}

private fun DayPlan.toInsightPlanDay(date: LocalDate): PlanDayFact = PlanDayFact(
    localDate = date,
    activities = activities.map { activity ->
        PlanActivityFact(
            id = activity.activityId,
            title = activity.title,
            kind = activity.kind.name.lowercase(Locale.ROOT),
            status = InsightActivityStatus.valueOf(activity.status.name),
            priority = when (activity.priority) {
                Priority.REQUIRED -> InsightActivityPriority.REQUIRED
                Priority.IMPORTANT -> InsightActivityPriority.IMPORTANT
                Priority.OPTIONAL -> InsightActivityPriority.OPTIONAL
            },
        )
    },
)

private fun activityStatusLabel(status: InsightActivityStatus): String = when (status) {
    InsightActivityStatus.PLANNED -> "prévue"
    InsightActivityStatus.IN_PROGRESS -> "en cours"
    InsightActivityStatus.DONE -> "réalisée"
    InsightActivityStatus.MISSED -> "manquée"
    InsightActivityStatus.CANCELLED -> "annulée"
    InsightActivityStatus.POSTPONED -> "reportée"
}

private fun formatMetricValue(value: Long): String =
    NumberFormat.getIntegerInstance(Locale.FRANCE).format(value)
