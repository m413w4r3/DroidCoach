package dev.atlas.coach.insights

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Calendar window used to build a local Coach summary. */
enum class InsightPeriod { DAY, WEEK, MONTH }

/** Terminal outcomes accepted for a Focus session snapshot. */
enum class FocusTerminalStatus { FINISHED, CANCELLED }

/** Local activity states consumed by the insights aggregator. */
enum class InsightActivityStatus { PLANNED, IN_PROGRESS, DONE, MISSED, CANCELLED, POSTPONED }

/** Priority is retained as an input fact for callers without coupling this API to Android models. */
enum class InsightActivityPriority { REQUIRED, IMPORTANT, OPTIONAL }

/** Terminal states of a locally recorded agent effect. */
enum class InsightEffectState { EXECUTED, REJECTED, EXPIRED, CANCELLED }

/** Current lifecycle state of a goal snapshot. */
enum class InsightGoalStatus { ACTIVE, COMPLETED, PAUSED, ABANDONED }

/** A terminal Focus session supplied by the app. Time values are seconds and cycles are counts. */
data class FocusFact(
    val id: String,
    val startedAtUtcMillis: Long,
    val terminalStatus: FocusTerminalStatus,
    val completedWorkCycles: Int,
    val workSeconds: Long,
    val taskTitle: String? = null,
) {
    init {
        require(id.isNotBlank())
        require(completedWorkCycles >= 0)
        require(workSeconds >= 0L)
    }
}

/** Habit history uses epoch-day values so it can be filtered without a device default timezone. */
data class HabitFact(
    val id: String,
    val name: String,
    val dueDays: Set<Long>,
    val completedDays: Set<Long>,
    val streak: Int,
) {
    init {
        require(id.isNotBlank())
        require(name.isNotBlank())
        require(streak >= 0)
    }
}

/** One planned local date and its activity snapshots. */
data class PlanDayFact(
    val localDate: LocalDate,
    val activities: List<PlanActivityFact>,
)

/** An activity fact independent of the Android plan implementation. */
data class PlanActivityFact(
    val id: String,
    val title: String,
    val kind: String,
    val status: InsightActivityStatus,
    val priority: InsightActivityPriority,
) {
    init {
        require(id.isNotBlank())
        require(title.isNotBlank())
        require(kind.isNotBlank())
    }
}

/** Current goal snapshot. [dueAtUtcMillis] is nullable when the goal has no deadline. */
data class GoalFact(
    val id: String,
    val title: String,
    val metric: String,
    val target: Long,
    val current: Long,
    val status: InsightGoalStatus,
    val dueAtUtcMillis: Long? = null,
) {
    init {
        require(id.isNotBlank())
        require(title.isNotBlank())
        require(metric.isNotBlank())
        require(target > 0L)
        require(current >= 0L)
    }
}

/** A terminal, local agent effect. */
data class EffectFact(
    val id: String,
    val actionType: String,
    val terminalState: InsightEffectState,
    val atUtcMillis: Long,
) {
    init {
        require(id.isNotBlank())
        require(actionType.isNotBlank())
    }
}

/** All inputs are supplied by the caller; this API has no clock, storage, or network dependency. */
data class InsightFacts(
    val nowUtcMillis: Long,
    val zoneId: ZoneId,
    val focusSessions: List<FocusFact> = emptyList(),
    val habitHistories: List<HabitFact> = emptyList(),
    val planDays: List<PlanDayFact> = emptyList(),
    val goals: List<GoalFact> = emptyList(),
    val agentEffects: List<EffectFact> = emptyList(),
)

/** Numeric measure. Optional goal fields preserve the supplied target and deadline facts. */
data class InsightMetric(
    val key: String,
    val label: String,
    val value: Long,
    val unit: String,
    val targetValue: Long? = null,
    val detailFr: String? = null,
)

/** A short, factual French sentence with a stable machine-readable code. */
data class InsightObservation(val code: String, val text: String)

/** Deterministic report for one local calendar period. */
data class CoachInsightReport(
    val period: InsightPeriod,
    val enoughData: Boolean,
    val metrics: List<InsightMetric>,
    val observations: List<InsightObservation>,
    val insufficientReasons: List<String>,
)

/**
 * Builds a deterministic summary from supplied local facts. Week and month windows are sliding
 * local-date windows including today; completed Focus time excludes cancelled sessions.
 */
object CoachInsights {
    private data class Window(val today: LocalDate, val start: LocalDate) {
        fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(today)
    }

    private data class DatedFocus(val date: LocalDate, val fact: FocusFact)
    private data class DatedActivity(val date: LocalDate, val fact: PlanActivityFact)
    private data class DatedEffect(val date: LocalDate, val fact: EffectFact)
    private data class HabitWindow(
        val fact: HabitFact,
        val dueDays: Set<Long>,
        val completedDays: Set<Long>,
    ) {
        val completedDueDays: Int get() = dueDays.intersect(completedDays).size
    }

    /** Summarizes [facts] for [period], using only their injected clock and zone. */
    fun summarize(period: InsightPeriod, facts: InsightFacts): CoachInsightReport {
        val today = Instant.ofEpochMilli(facts.nowUtcMillis).atZone(facts.zoneId).toLocalDate()
        val window = Window(
            today = today,
            start = when (period) {
                InsightPeriod.DAY -> today
                InsightPeriod.WEEK -> today.minusDays(6)
                InsightPeriod.MONTH -> today.minusDays(29)
            },
        )

        val focus = facts.focusSessions
            .mapNotNull { fact -> fact.startedAtUtcMillis.toLocalDate(facts.zoneId).takeIf(window::contains)?.let { DatedFocus(it, fact) } }
            .sortedWith(compareBy<DatedFocus>({ it.date }, { it.fact.id }, { it.fact.terminalStatus.name }, { it.fact.workSeconds }, { it.fact.completedWorkCycles }))

        val plans = facts.planDays
            .filter { window.contains(it.localDate) }
            .sortedBy(PlanDayFact::localDate)
        val activities = plans.flatMap { day ->
            day.activities.map { DatedActivity(day.localDate, it) }
        }.sortedWith(compareBy<DatedActivity>({ it.date }, { it.fact.id }, { it.fact.status.name }, { it.fact.title }, { it.fact.kind }, { it.fact.priority.name }))

        val habits = facts.habitHistories
            .map { fact ->
                HabitWindow(
                    fact = fact,
                    dueDays = fact.dueDays.filterTo(sortedSetOf()) { epochDay ->
                        window.contains(LocalDate.ofEpochDay(epochDay))
                    },
                    completedDays = fact.completedDays.filterTo(sortedSetOf()) { epochDay ->
                        window.contains(LocalDate.ofEpochDay(epochDay))
                    },
                )
            }
            .sortedWith(compareBy<HabitWindow>({ it.fact.id }, { it.fact.name }, { it.fact.streak }, { it.dueDays.joinToString(",") }, { it.completedDays.joinToString(",") }))

        val goals = facts.goals.sortedWith(
            compareBy<GoalFact>({ it.id }, { it.title }, { it.status.name }, { it.current }, { it.target }, { it.metric }, { it.dueAtUtcMillis }),
        )
        val effects = facts.agentEffects
            .mapNotNull { fact -> fact.atUtcMillis.toLocalDate(facts.zoneId).takeIf(window::contains)?.let { DatedEffect(it, fact) } }
            .sortedWith(compareBy<DatedEffect>({ it.date }, { it.fact.id }, { it.fact.actionType }, { it.fact.terminalState.name }))

        val daysWithData = sortedSetOf<LocalDate>().apply {
            focus.forEach { add(it.date) }
            plans.forEach { add(it.localDate) }
            habits.forEach { habit ->
                habit.dueDays.forEach { add(LocalDate.ofEpochDay(it)) }
                habit.completedDays.forEach { add(LocalDate.ofEpochDay(it)) }
            }
            // Histories, goals, and streaks are current snapshots supplied as of the injected now.
            if (facts.habitHistories.isNotEmpty() || goals.isNotEmpty()) add(today)
            effects.forEach { add(it.date) }
        }

        val completedFocus = focus.filter { it.fact.terminalStatus == FocusTerminalStatus.FINISHED }
        val completedFocusSeconds = completedFocus.sumOf { it.fact.workSeconds }
        val completedCycles = focus.sumOf { it.fact.completedWorkCycles.toLong() }
        val cancelledSessions = focus.count { it.fact.terminalStatus == FocusTerminalStatus.CANCELLED }
        val doneActivities = activities.count { it.fact.status == InsightActivityStatus.DONE }
        val missedActivities = activities.count { it.fact.status == InsightActivityStatus.MISSED }
        val plannedActivities = activities.count {
            it.fact.status == InsightActivityStatus.PLANNED || it.fact.status == InsightActivityStatus.IN_PROGRESS
        }
        val dueHabitDays = habits.sumOf { it.dueDays.size.toLong() }
        val checkedHabitDays = habits.sumOf { it.completedDays.size.toLong() }
        val activeGoals = goals.filter { it.status == InsightGoalStatus.ACTIVE }

        val metrics = buildList {
            add(InsightMetric("days_with_data", "Jours renseignés", daysWithData.size.toLong(), "jours"))
            add(InsightMetric("focus_minutes_completed", "Minutes Focus terminées", completedFocusSeconds / 60L, "minutes"))
            add(InsightMetric("focus_cycles_completed", "Cycles Focus terminés", completedCycles, "cycles"))
            add(InsightMetric("focus_interruptions", "Sessions Focus annulées", cancelledSessions.toLong(), "sessions"))
            add(InsightMetric("plan_done", "Activités réalisées", doneActivities.toLong(), "activités"))
            add(InsightMetric("plan_missed", "Activités manquées", missedActivities.toLong(), "activités"))
            add(InsightMetric("plan_planned", "Activités prévues", plannedActivities.toLong(), "activités"))
            add(InsightMetric("habits_checked", "Habitudes cochées", checkedHabitDays.toLong(), "jours-habitude"))
            add(InsightMetric("habits_due", "Jours d'habitude dus", dueHabitDays.toLong(), "jours-habitude"))
            add(InsightMetric("active_goals", "Objectifs actifs", activeGoals.size.toLong(), "objectifs"))
            InsightEffectState.entries.forEach { state ->
                val label = when (state) {
                    InsightEffectState.EXECUTED -> "Effets d'agent exécutés"
                    InsightEffectState.REJECTED -> "Effets d'agent refusés"
                    InsightEffectState.EXPIRED -> "Effets d'agent expirés"
                    InsightEffectState.CANCELLED -> "Effets d'agent annulés"
                }
                add(InsightMetric("agent_effect_${state.name.lowercase(Locale.ROOT)}", label, effects.count { it.fact.terminalState == state }.toLong(), "effets"))
            }
            activeGoals.forEach { goal ->
                val deadline = goal.dueAtUtcMillis?.let { dueAt ->
                    val date = Instant.ofEpochMilli(dueAt).atZone(facts.zoneId).toLocalDate()
                    "Échéance : ${DateTimeFormatter.ISO_LOCAL_DATE.format(date)}"
                }
                add(
                    InsightMetric(
                        key = "goal_progress:${goal.id}",
                        label = "Objectif : ${goal.title}",
                        value = goal.current,
                        unit = goal.metric,
                        targetValue = goal.target,
                        detailFr = deadline,
                    ),
                )
            }
        }

        val observations = buildList {
            val focusByDay = focus
                .filter { it.fact.terminalStatus == FocusTerminalStatus.FINISHED }
                .groupBy(DatedFocus::date)
            if (focusByDay.size >= 2) {
                val mostActiveDaySeconds = focusByDay.entries
                    .map { (date, sessions) ->
                        date to sessions.sumOf { it.fact.workSeconds }
                    }
                    .sortedWith(compareByDescending<Pair<LocalDate, Long>> { it.second }.thenBy { it.first })
                    .first()
                add(
                    InsightObservation(
                        "focus_most_active_day",
                        "Jour le plus actif en Focus : ${mostActiveDaySeconds.first.frenchWeekday()} (${mostActiveDaySeconds.second / 60L} min terminées).",
                    ),
                )
            }

            val decidedActivities = doneActivities + missedActivities
            if (decidedActivities >= 5) {
                val completionPercent = doneActivities.toLong() * 100 / decidedActivities
                add(
                    InsightObservation(
                        "plan_completion_rate",
                        "Activités réalisées parmi les terminées ou manquées : $completionPercent % ($doneActivities/$decidedActivities).",
                    ),
                )
            }

            val regularHabits = habits.filter { it.dueDays.size >= 3 }
            if (regularHabits.size >= 2) {
                val mostRegular = regularHabits.sortedWith(
                    compareByDescending<HabitWindow> { it.completedDueDays.toDouble() / it.dueDays.size }
                        .thenByDescending { it.dueDays.size }
                        .thenBy { it.fact.id }
                        .thenBy { it.fact.name },
                ).first()
                val completionPercent = mostRegular.completedDueDays * 100 / mostRegular.dueDays.size
                add(
                    InsightObservation(
                        "habit_most_regular",
                        "Habitude la plus régulière : ${mostRegular.fact.name} (${mostRegular.completedDueDays}/${mostRegular.dueDays.size} jours dus, série actuelle : ${mostRegular.fact.streak}).",
                    ),
                )
                // Keep the percentage as a separate structured fact rather than inferring a trend.
                add(InsightObservation("habit_completion_rate", "Taux de réalisation de cette habitude : $completionPercent %."))
            }
        }

        val minimumDays = when (period) {
            InsightPeriod.DAY -> 1
            InsightPeriod.WEEK -> 3
            InsightPeriod.MONTH -> 7
        }
        val enoughData = daysWithData.size >= minimumDays
        val insufficientReasons = if (enoughData) {
            emptyList()
        } else if (period == InsightPeriod.DAY) {
            listOf("Aucune donnée disponible pour le jour courant.")
        } else {
            val count = daysWithData.size
            val dayLabel = if (count == 1) "jour renseigné" else "jours renseignés"
            listOf("Données présentes sur $count $dayLabel seulement ; synthèse insuffisante.")
        }

        return CoachInsightReport(
            period = period,
            enoughData = enoughData,
            metrics = metrics,
            observations = observations,
            insufficientReasons = insufficientReasons,
        )
    }

    private fun Long.toLocalDate(zoneId: ZoneId): LocalDate = Instant.ofEpochMilli(this).atZone(zoneId).toLocalDate()

    private fun LocalDate.frenchWeekday(): String = dayOfWeek.getDisplayName(TextStyle.FULL, Locale.FRENCH)
}
