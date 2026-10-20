package dev.atlas.coach.goals

import dev.atlas.coach.focus.FocusPhase
import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.focus.FocusStatus
import dev.atlas.coach.habits.HabitCheckIn
import java.math.BigInteger

data class GoalProgress(
    val goalId: String,
    val metric: GoalMetric,
    val currentValue: Long,
    val targetValue: Long,
) {
    init {
        require(currentValue >= 0L && targetValue > 0L)
    }

    val isComplete: Boolean get() = currentValue >= targetValue
    val percentComplete: Int get() = if (isComplete) 100 else {
        BigInteger.valueOf(currentValue)
            .multiply(BigInteger.valueOf(100L))
            .divide(BigInteger.valueOf(targetValue))
            .toInt()
            .coerceIn(0, 100)
    }
}

/** Counts only completed, explicitly linked domain records. This calculator never mutates a goal. */
object GoalProgressCalculator {
    fun calculate(
        goal: GoalRecord,
        focusSessions: Iterable<FocusSession> = emptyList(),
        habitCheckIns: Iterable<HabitCheckIn> = emptyList(),
    ): GoalProgress {
        val value = when (goal.metric) {
            GoalMetric.FOCUS_MINUTES -> completedFocusMinutes(goal, focusSessions)
            GoalMetric.HABIT_CHECKINS -> completedHabitCheckIns(goal, habitCheckIns)
            GoalMetric.MANUAL -> if (goal.status == GoalStatus.COMPLETED) 1L else 0L
        }
        return GoalProgress(goal.id, goal.metric, value, goal.targetValue)
    }

    fun completedFocusMinutes(goal: GoalRecord, focusSessions: Iterable<FocusSession>): Long {
        if (goal.metric != GoalMetric.FOCUS_MINUTES || goal.linkedFocusSessionIds.isEmpty()) return 0L
        val linkedIds = goal.linkedFocusSessionIds
        val seconds = latestFinishedSessions(focusSessions).asSequence()
            .filter { it.id in linkedIds }
            .fold(0L) { total, session -> saturatingAdd(total, completedWorkSeconds(session)) }
        return seconds / SECONDS_PER_MINUTE
    }

    /** Resolves duplicate stored rows by timestamp; a same-time cancellation wins conservatively. */
    internal fun latestFinishedSessions(focusSessions: Iterable<FocusSession>): List<FocusSession> =
        focusSessions.groupBy(FocusSession::id).values.mapNotNull { versions ->
            versions.maxWithOrNull(
                compareBy<FocusSession> { it.updatedAtUtcMillis }
                    .thenBy { terminalStatusPriority(it.status) },
            )?.takeIf { it.status == FocusStatus.FINISHED }
        }

    private fun terminalStatusPriority(status: FocusStatus): Int = when (status) {
        FocusStatus.CANCELLED -> 4
        FocusStatus.FINISHED -> 3
        FocusStatus.PAUSED -> 2
        FocusStatus.RUNNING -> 1
        FocusStatus.IDLE -> 0
    }

    fun completedHabitCheckIns(goal: GoalRecord, habitCheckIns: Iterable<HabitCheckIn>): Long {
        if (goal.metric != GoalMetric.HABIT_CHECKINS || goal.linkedHabitIds.isEmpty()) return 0L
        return habitCheckIns.asSequence()
            .filter { it.habitId in goal.linkedHabitIds }
            // A persisted duplicate row for the same habit/day still represents one validation.
            .distinctBy { it.habitId to it.dateEpochDay }
            .count()
            .toLong()
    }

    /**
     * Reconstructs elapsed work from completed cycles and, when manually finished mid-work,
     * elapsed time in that work phase. Planned cycles and cancelled sessions contribute nothing.
     */
    fun completedWorkSeconds(session: FocusSession): Long {
        if (session.status != FocusStatus.FINISHED) return 0L
        val completeCycles = session.completedWorkCycles.coerceAtLeast(0).toLong()
        val completeSeconds = saturatingMultiply(completeCycles, session.workSeconds.coerceAtLeast(0L))
        if (session.phase != FocusPhase.WORK || session.remainingMillis == 0L) return completeSeconds

        val plannedPhaseMillis = saturatingMultiply(session.workSeconds.coerceAtLeast(0L), MILLIS_PER_SECOND)
        val remainingMillis = session.remainingMillis.coerceIn(0L, plannedPhaseMillis)
        val partialSeconds = (plannedPhaseMillis - remainingMillis) / MILLIS_PER_SECOND
        return saturatingAdd(completeSeconds, partialSeconds)
    }

    private fun saturatingMultiply(left: Long, right: Long): Long = when {
        left <= 0L || right <= 0L -> 0L
        left > Long.MAX_VALUE / right -> Long.MAX_VALUE
        else -> left * right
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private const val MILLIS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60L
}
