package dev.atlas.coach.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.atlas.coach.goals.GoalMetric
import dev.atlas.coach.goals.GoalProgress
import dev.atlas.coach.goals.GoalRecord
import dev.atlas.coach.goals.GoalStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Display data supplied by the caller; the panel never reads or mutates a repository. */
data class GoalsPanelEntry(val record: GoalRecord, val progress: GoalProgress)

data class GoalPanelLinkOption(val id: String, val label: String)

data class GoalPanelDraft(
    val title: String,
    val metric: GoalMetric,
    val targetValue: Long,
    val description: String,
    val nextAction: String,
    val dueAt: Long? = null,
)

private enum class GoalLinkKind { FOCUS, HABIT }
private data class PendingGoalLink(val goalId: String, val kind: GoalLinkKind)

/**
 * S6 integration surface:
 * `goals` is a read-only projection, and callbacks perform explicit local repository operations.
 */
@Composable
fun GoalsPanel(
    goals: List<GoalsPanelEntry>,
    focusSessions: List<GoalPanelLinkOption>,
    habits: List<GoalPanelLinkOption>,
    onCreateGoal: (GoalPanelDraft) -> Unit,
    onUpdateGoal: (goalId: String, draft: GoalPanelDraft) -> Unit,
    onStatusChange: (goalId: String, status: GoalStatus) -> Unit,
    onCompleteFromProgress: (goalId: String) -> Unit,
    onLinkFocusSession: (goalId: String, focusSessionId: String) -> Unit,
    onUnlinkFocusSession: (goalId: String, focusSessionId: String) -> Unit,
    onLinkHabit: (goalId: String, habitId: String) -> Unit,
    onUnlinkHabit: (goalId: String, habitId: String) -> Unit,
    onConfirmManualCompletion: (goalId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var creating by remember { mutableStateOf(false) }
    var editingGoal by remember { mutableStateOf<GoalRecord?>(null) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var nextAction by remember { mutableStateOf("") }
    var dueDateText by remember { mutableStateOf("") }
    var targetText by remember { mutableStateOf("120") }
    var metric by remember { mutableStateOf(GoalMetric.FOCUS_MINUTES) }
    var metricMenuOpen by remember { mutableStateOf(false) }
    var pendingLink by remember { mutableStateOf<PendingGoalLink?>(null) }

    val activeCount = goals.count { it.record.status == GoalStatus.ACTIVE }
    val visibleGoals = goals
    val parsedTarget = targetText.toLongOrNull()
    val parsedDueAt = parseGoalDueDate(dueDateText)
    val dueDateValid = dueDateText.isBlank() || parsedDueAt != null

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Objectifs", style = MaterialTheme.typography.titleLarge)
        if (visibleGoals.isEmpty()) {
            Text("Aucun objectif enregistré.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        visibleGoals.forEach { entry ->
            val goal = entry.record
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(goal.title, style = MaterialTheme.typography.titleMedium)
                goal.dueAt?.let { dueAt -> Text("Échéance : ${formatGoalDueDate(dueAt)}", style = MaterialTheme.typography.bodySmall) }
                Text(
                    "${entry.progress.currentValue.coerceAtMost(goal.targetValue)} / ${goal.targetValue} ${goal.metric.unitLabel()} · ${entry.progress.percentComplete}% · ${goal.status.label()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (goal.description.isNotBlank()) Text(goal.description)
                goal.nextAction?.takeIf(String::isNotBlank)?.let { Text("Prochaine action : $it") }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = {
                            editingGoal = goal
                            title = goal.title
                            description = goal.description
                            nextAction = goal.nextAction.orEmpty()
                            dueDateText = goal.dueAt?.let(::formatGoalDueDate).orEmpty()
                            targetText = goal.targetValue.toString()
                            metric = goal.metric
                            creating = false
                        },
                    ) { Text("Modifier") }
                    GoalStatusActions(
                        goal = goal,
                        progress = entry.progress,
                        onStatusChange = { onStatusChange(goal.id, it) },
                        onCompleteFromProgress = { onCompleteFromProgress(goal.id) },
                    )
                }

                if (goal.status == GoalStatus.ACTIVE) {
                    when (goal.metric) {
                        GoalMetric.FOCUS_MINUTES -> {
                            Text("Les minutes comptées proviennent uniquement des sessions terminées et liées.", style = MaterialTheme.typography.bodySmall)
                            LinkPickerButton(
                                label = "Lier une session Focus (${goal.linkedFocusSessionIds.size})",
                                enabled = focusSessions.isNotEmpty(),
                                onClick = { pendingLink = PendingGoalLink(goal.id, GoalLinkKind.FOCUS) },
                            )
                        }
                        GoalMetric.HABIT_CHECKINS -> {
                            Text("La progression compte les validations réelles des habitudes liées.", style = MaterialTheme.typography.bodySmall)
                            LinkPickerButton(
                                label = "Lier une habitude (${goal.linkedHabitIds.size})",
                                enabled = habits.isNotEmpty(),
                                onClick = { pendingLink = PendingGoalLink(goal.id, GoalLinkKind.HABIT) },
                            )
                        }
                        GoalMetric.MANUAL -> Button(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            onClick = { onConfirmManualCompletion(goal.id) },
                        ) { Text("Confirmer manuellement l'accomplissement") }
                    }
                }
            }
        }

        if (activeCount >= 3) {
            Text("Trois objectifs actifs au maximum.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                onClick = { creating = !creating },
            ) { Text(if (creating) "Fermer le formulaire" else "Créer un objectif") }
        }

        if (creating && activeCount < 3 || editingGoal != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Titre") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Description") },
                    minLines = 2,
                    maxLines = 4,
                )
                Box {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    onClick = { if (editingGoal == null) metricMenuOpen = true },
                    enabled = editingGoal == null,
                ) { Text("Mesure : ${metric.label()}") }
                    DropdownMenu(expanded = metricMenuOpen && editingGoal == null, onDismissRequest = { metricMenuOpen = false }) {
                        GoalMetric.entries.forEach { option ->
                            DropdownMenuItem(
                                modifier = Modifier.heightIn(min = 48.dp),
                                text = { Text(option.label()) },
                                onClick = {
                                    metric = option
                                    if (option == GoalMetric.MANUAL) targetText = "1"
                                    else if (targetText == "1") targetText = if (option == GoalMetric.HABIT_CHECKINS) "12" else "120"
                                    metricMenuOpen = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = if (metric == GoalMetric.MANUAL) "1" else targetText,
                    onValueChange = { targetText = it.filter(Char::isDigit).take(9) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Cible (${metric.unitLabel()})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = metric != GoalMetric.MANUAL,
                )
                OutlinedTextField(
                    value = nextAction,
                    onValueChange = { nextAction = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Prochaine action") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = dueDateText,
                    onValueChange = { dueDateText = it.take(10) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Échéance facultative · AAAA-MM-JJ") },
                    supportingText = { if (!dueDateValid) Text("Saisissez une date valide au format AAAA-MM-JJ.") },
                    singleLine = true,
                )
                Button(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = title.isNotBlank() && dueDateValid &&
                        (metric == GoalMetric.MANUAL || (parsedTarget != null && parsedTarget > 0L)),
                    onClick = {
                        val draft = GoalPanelDraft(
                            title = title.trim(),
                            metric = metric,
                            targetValue = if (metric == GoalMetric.MANUAL) 1L else requireNotNull(parsedTarget),
                            description = description.trim(),
                            nextAction = nextAction.trim(),
                            dueAt = parsedDueAt,
                        )
                        editingGoal?.let { onUpdateGoal(it.id, draft) } ?: onCreateGoal(draft)
                        title = ""
                        description = ""
                        nextAction = ""
                        dueDateText = ""
                        targetText = if (metric == GoalMetric.HABIT_CHECKINS) "12" else "120"
                        creating = false
                        editingGoal = null
                    },
                ) { Text(if (editingGoal == null) "Enregistrer l'objectif" else "Enregistrer les modifications") }
                TextButton(onClick = {
                    creating = false
                    editingGoal = null
                }) { Text("Annuler") }
            }
        }
    }

    pendingLink?.let { request ->
        val isFocus = request.kind == GoalLinkKind.FOCUS
        val options = if (isFocus) focusSessions else habits
        val linkedIds = visibleGoals.firstOrNull { it.record.id == request.goalId }?.record?.let {
            if (isFocus) it.linkedFocusSessionIds else it.linkedHabitIds
        }.orEmpty()
        AlertDialog(
            onDismissRequest = { pendingLink = null },
            title = { Text(if (isFocus) "Sessions Focus liées" else "Habitudes liées") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (options.isEmpty()) Text("Aucun élément disponible.")
                    options.forEach { option ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            onClick = {
                                if (option.id in linkedIds) {
                                    if (isFocus) onUnlinkFocusSession(request.goalId, option.id)
                                    else onUnlinkHabit(request.goalId, option.id)
                                } else {
                                    if (isFocus) onLinkFocusSession(request.goalId, option.id)
                                    else onLinkHabit(request.goalId, option.id)
                                }
                                pendingLink = null
                            },
                        ) {
                            Text(
                                "${if (option.id in linkedIds) "Retirer" else "Lier"} · ${option.label}",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pendingLink = null }) { Text("Fermer") } },
        )
    }
}

private fun parseGoalDueDate(value: String): Long? = value.trim().takeIf(String::isNotEmpty)?.let { raw ->
    runCatching {
        // The selected date stays open through the local day; the review becomes due at its end.
        LocalDate.parse(raw).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1L
    }.getOrNull()
}

private fun formatGoalDueDate(timestampUtcMillis: Long): String =
    Instant.ofEpochMilli(timestampUtcMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

@Composable
private fun GoalStatusActions(
    goal: GoalRecord,
    progress: GoalProgress,
    onStatusChange: (GoalStatus) -> Unit,
    onCompleteFromProgress: () -> Unit,
) {
    var expanded by remember(goal.id) { mutableStateOf(false) }
    Box {
        TextButton(
            modifier = Modifier.heightIn(min = 48.dp),
            onClick = { expanded = true },
        ) { Text("Statut : ${goal.status.label()}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val options = when (goal.status) {
                GoalStatus.ACTIVE -> listOf(GoalStatus.PAUSED, GoalStatus.ABANDONED)
                GoalStatus.PAUSED, GoalStatus.ABANDONED, GoalStatus.COMPLETED -> listOf(GoalStatus.ACTIVE)
            }
            options.forEach { status ->
                DropdownMenuItem(
                    text = { Text(if (status == GoalStatus.ACTIVE) "Réactiver" else status.label()) },
                    onClick = { expanded = false; onStatusChange(status) },
                )
            }
        }
    }
    if (goal.metric != GoalMetric.MANUAL && goal.status == GoalStatus.ACTIVE && progress.isComplete) {
        TextButton(
            modifier = Modifier.heightIn(min = 48.dp),
            onClick = onCompleteFromProgress,
        ) { Text("Valider l'objectif atteint") }
    }
}

@Composable
private fun LinkPickerButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        enabled = enabled,
        onClick = onClick,
    ) { Text(label) }
}

private fun GoalMetric.label(): String = when (this) {
    GoalMetric.FOCUS_MINUTES -> "minutes Focus"
    GoalMetric.HABIT_CHECKINS -> "validations d'habitude"
    GoalMetric.MANUAL -> "confirmation manuelle"
}

private fun GoalMetric.unitLabel(): String = when (this) {
    GoalMetric.FOCUS_MINUTES -> "min"
    GoalMetric.HABIT_CHECKINS -> "validations"
    GoalMetric.MANUAL -> "confirmation"
}

private fun GoalStatus.label(): String = when (this) {
    GoalStatus.ACTIVE -> "En cours"
    GoalStatus.COMPLETED -> "Terminé"
    GoalStatus.PAUSED -> "En pause"
    GoalStatus.ABANDONED -> "Abandonné"
}
