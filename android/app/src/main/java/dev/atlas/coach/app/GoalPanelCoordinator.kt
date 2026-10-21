package dev.atlas.coach.app

import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.focus.FocusStatus
import dev.atlas.coach.goals.GoalMetric
import dev.atlas.coach.goals.GoalProgressCalculator
import dev.atlas.coach.goals.GoalRecord
import dev.atlas.coach.goals.GoalRepository
import dev.atlas.coach.goals.GoalStatus
import dev.atlas.coach.goals.PreparedFocusLink
import dev.atlas.coach.habits.Habit
import dev.atlas.coach.habits.HabitCheckIn
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Adapts explicit Coach panel actions to the versioned local goal repository. */
class GoalPanelCoordinator(
    readSubdocument: () -> Map<String, Any?>?,
    persistSubdocument: (Map<String, Any?>) -> Unit,
    private val nowUtcMillis: () -> Long,
    private val focusSessions: () -> List<FocusSession>,
    private val habits: () -> List<Habit>,
    private val habitCheckIns: () -> List<HabitCheckIn>,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    val repository = GoalRepository(readSubdocument, persistSubdocument)

    fun prepareFocusLink(
        goalId: String,
        focusSessionId: String,
        linkedAtUtcMillis: Long,
    ): PreparedFocusLink = repository.prepareFocusLink(goalId, focusSessionId, linkedAtUtcMillis)

    fun publishCommittedFocusLink(prepared: PreparedFocusLink): GoalRecord =
        repository.publishCommittedFocusLink(prepared)

    fun entries(): List<GoalsPanelEntry> = repository.all()
        .sortedWith(
            compareBy<GoalRecord> { it.status != GoalStatus.ACTIVE }
                .thenBy { it.dueAt ?: Long.MAX_VALUE }
                .thenBy { it.createdAt },
        )
        .map { goal ->
            GoalsPanelEntry(
                goal,
                GoalProgressCalculator.calculate(goal, focusSessions(), habitCheckIns()),
            )
        }

    fun focusLinkOptions(): List<GoalPanelLinkOption> = GoalProgressCalculator.latestFinishedSessions(focusSessions())
        .filter { it.status == FocusStatus.FINISHED }
        .sortedByDescending(FocusSession::createdAtUtcMillis)
        .map { session ->
            val minutes = GoalProgressCalculator.completedWorkSeconds(session) / 60L
            val day = Instant.ofEpochMilli(session.createdAtUtcMillis)
                .atZone(ZoneId.systemDefault()).toLocalDate()
            GoalPanelLinkOption(session.id, "$day · $minutes min Focus terminé")
        }

    fun habitLinkOptions(): List<GoalPanelLinkOption> = habits()
        .filter { it.archivedAtUtcMillis == null }
        .sortedBy { it.name.lowercase() }
        .map { GoalPanelLinkOption(it.id, it.name) }

    fun create(draft: GoalPanelDraft): GoalRecord {
        require(repository.all().count { it.status == GoalStatus.ACTIVE } < GoalRepository.MAX_ACTIVE_GOALS_FOR_COACH) {
            "Trois objectifs actifs au maximum."
        }
        val now = nowUtcMillis().coerceAtLeast(1L)
        return repository.add(
            GoalRecord(
                id = newId(),
                title = draft.title.trim(),
                description = draft.description.trim(),
                metric = draft.metric,
                targetValue = draft.targetValue,
                dueAt = draft.dueAt,
                nextAction = draft.nextAction.trim().takeIf(String::isNotEmpty),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    fun update(goalId: String, draft: GoalPanelDraft): GoalRecord {
        val previous = repository.get(goalId) ?: throw IllegalArgumentException("Objectif introuvable.")
        require(previous.metric == draft.metric) { "La mesure d'un objectif existant ne peut pas changer." }
        return repository.update(
            previous.copy(
                title = draft.title.trim(),
                description = draft.description.trim(),
                targetValue = draft.targetValue,
                dueAt = draft.dueAt,
                nextAction = draft.nextAction.trim().takeIf(String::isNotEmpty),
                updatedAt = maxOf(previous.updatedAt, nowUtcMillis()),
            ),
        )
    }

    fun changeStatus(goalId: String, status: GoalStatus): GoalRecord {
        val goal = repository.get(goalId) ?: throw IllegalArgumentException("Objectif introuvable.")
        if (status == GoalStatus.COMPLETED) {
            val progress = GoalProgressCalculator.calculate(goal, focusSessions(), habitCheckIns())
            return if (goal.metric == GoalMetric.MANUAL) {
                repository.confirmManualCompletion(goalId, maxOf(goal.updatedAt, nowUtcMillis()))
            } else {
                repository.completeFromProgress(goalId, progress, maxOf(goal.updatedAt, nowUtcMillis()))
            }
        }
        if (status == GoalStatus.ACTIVE && goal.status != GoalStatus.ACTIVE) {
            require(repository.all().count { it.status == GoalStatus.ACTIVE } < GoalRepository.MAX_ACTIVE_GOALS_FOR_COACH) {
                "Trois objectifs actifs au maximum."
            }
        }
        return repository.update(goal.copy(status = status, updatedAt = maxOf(goal.updatedAt, nowUtcMillis())))
    }

    fun confirmManualCompletion(goalId: String): GoalRecord =
        repository.confirmManualCompletion(goalId, maxOf(repository.get(goalId)?.updatedAt ?: 0L, nowUtcMillis()))

    fun completeFromProgress(goalId: String): GoalRecord {
        val goal = repository.get(goalId) ?: throw IllegalArgumentException("Objectif introuvable.")
        val progress = GoalProgressCalculator.calculate(goal, focusSessions(), habitCheckIns())
        return repository.completeFromProgress(goalId, progress, maxOf(goal.updatedAt, nowUtcMillis()))
    }

    fun linkFocus(goalId: String, focusSessionId: String, linked: Boolean) = updateLinks(goalId) { goal ->
        require(goal.metric == GoalMetric.FOCUS_MINUTES) { "Cet objectif ne compte pas les sessions Focus." }
        val sessionExists = focusLinkOptions().any { it.id == focusSessionId }
        require(sessionExists) { "Seules les sessions Focus terminées peuvent être liées." }
        goal.copy(linkedFocusSessionIds = if (linked) goal.linkedFocusSessionIds + focusSessionId else goal.linkedFocusSessionIds - focusSessionId)
    }

    fun linkHabit(goalId: String, habitId: String, linked: Boolean) = updateLinks(goalId) { goal ->
        require(goal.metric == GoalMetric.HABIT_CHECKINS) { "Cet objectif ne compte pas les validations d'habitude." }
        require(habits().any { it.id == habitId && it.archivedAtUtcMillis == null }) { "Habitude indisponible." }
        goal.copy(linkedHabitIds = if (linked) goal.linkedHabitIds + habitId else goal.linkedHabitIds - habitId)
    }

    fun nextPriorityAction(): String? = repository.activeGoalsForCoach()
        .firstNotNullOfOrNull { goal -> goal.nextAction?.takeIf(String::isNotBlank) }

    private inline fun updateLinks(goalId: String, transform: (GoalRecord) -> GoalRecord): GoalRecord {
        val goal = repository.get(goalId) ?: throw IllegalArgumentException("Objectif introuvable.")
        require(goal.status == GoalStatus.ACTIVE) { "Seuls les objectifs actifs peuvent être liés." }
        return repository.update(transform(goal).copy(updatedAt = maxOf(goal.updatedAt, nowUtcMillis())))
    }
}
