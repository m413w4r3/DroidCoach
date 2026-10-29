package dev.atlas.coach.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.atlas.coach.focus.FocusPlan
import dev.atlas.coach.focus.FocusPreset
import dev.atlas.coach.goals.GoalMetric
import dev.atlas.coach.goals.GoalRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class DayScheduleInput(
    val startTime: String = "09:00",
    val endTime: String = "18:00",
    val priority: String = "",
)

internal data class DayScheduleRequest(
    val startTime: String,
    val endTime: String,
    val priority: String,
    val message: String,
)

internal data class GoalQuickActionInput(
    val title: String = "Objectif de la semaine",
    val metric: GoalMetric = GoalMetric.FOCUS_MINUTES,
    val targetText: String = "120",
    val dueDateText: String,
    val nextAction: String = "",
)

internal data class FocusQuickActionInput(
    val preset: FocusPreset = FocusPreset.POMODORO_25_5,
    val taskTitle: String,
    val cyclesText: String = "4",
)

internal sealed interface QuickActionValidation<out T> {
    data class Valid<T>(val value: T) : QuickActionValidation<T>
    data class Invalid(val message: String) : QuickActionValidation<Nothing>
}

internal sealed interface PendingCoachQuickAction {
    data class SendMessage(val message: String) : PendingCoachQuickAction
    data class CreateGoal(val draft: GoalPanelDraft) : PendingCoachQuickAction
    data class StartFocus(val plan: FocusPlan) : PendingCoachQuickAction
}

internal fun dayScheduleDefaults() = DayScheduleInput()

internal fun goalQuickActionDefaults(today: LocalDate = LocalDate.now()) = GoalQuickActionInput(
    dueDateText = today.plusDays(7).toString(),
)

internal fun focusQuickActionDefaults(directiveTitle: String) = FocusQuickActionInput(
    taskTitle = directiveTitle.trim().take(FocusPlan.MAX_TASK_TITLE_LENGTH)
        .ifBlank { "Tâche de focus" },
)

internal fun validateDaySchedule(input: DayScheduleInput): QuickActionValidation<DayScheduleRequest> {
    if (!HH_MM.matches(input.startTime)) {
        return QuickActionValidation.Invalid("Saisissez une heure de début au format HH:mm.")
    }
    if (!HH_MM.matches(input.endTime)) {
        return QuickActionValidation.Invalid("Saisissez une heure de fin au format HH:mm.")
    }
    val priority = input.priority.trim()
    if (priority.length > MAX_PRIORITY_LENGTH) {
        return QuickActionValidation.Invalid("La priorité ne peut pas dépasser $MAX_PRIORITY_LENGTH caractères.")
    }
    val message = buildString {
        append("Organise ma journée de ${input.startTime} à ${input.endTime}.")
        if (priority.isNotEmpty()) append(" Priorité : $priority.")
        append(" Propose un planning réaliste avec des pauses.")
    }
    if (message.length > AtlasController.MAX_AGENT_MESSAGE_LENGTH) {
        return QuickActionValidation.Invalid("Le message dépasse ${AtlasController.MAX_AGENT_MESSAGE_LENGTH} caractères.")
    }
    return QuickActionValidation.Valid(
        DayScheduleRequest(input.startTime, input.endTime, priority, message),
    )
}

internal fun validateGoalQuickAction(
    input: GoalQuickActionInput,
    zoneId: ZoneId = ZoneId.systemDefault(),
): QuickActionValidation<GoalPanelDraft> {
    val title = input.title.trim()
    if (title.isEmpty()) return QuickActionValidation.Invalid("Saisissez un titre d’objectif.")
    if (title.length > GoalRecord.MAX_TITLE_LENGTH) {
        return QuickActionValidation.Invalid("Le titre ne peut pas dépasser ${GoalRecord.MAX_TITLE_LENGTH} caractères.")
    }
    val target = input.targetText.trim().toLongOrNull()
        ?: return QuickActionValidation.Invalid("Saisissez une cible entière supérieure à zéro.")
    if (target <= 0L) return QuickActionValidation.Invalid("La cible doit être supérieure à zéro.")
    if (input.metric == GoalMetric.MANUAL && target != 1L) {
        return QuickActionValidation.Invalid("Un objectif manuel utilise une cible de 1.")
    }
    val nextAction = input.nextAction.trim()
    if (nextAction.length > GoalRecord.MAX_NEXT_ACTION_LENGTH) {
        return QuickActionValidation.Invalid(
            "La prochaine action ne peut pas dépasser ${GoalRecord.MAX_NEXT_ACTION_LENGTH} caractères.",
        )
    }
    val dueAt = runCatching {
        LocalDate.parse(input.dueDateText.trim(), DateTimeFormatter.ISO_LOCAL_DATE)
            .plusDays(1)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli() - 1L
    }.getOrNull() ?: return QuickActionValidation.Invalid("Saisissez une échéance valide au format AAAA-MM-JJ.")
    if (dueAt < 0L) return QuickActionValidation.Invalid("L’échéance doit être à partir du 1er janvier 1970.")
    return QuickActionValidation.Valid(
        GoalPanelDraft(
            title = title,
            metric = input.metric,
            targetValue = target,
            description = "",
            nextAction = nextAction,
            dueAt = dueAt,
        ),
    )
}

internal fun validateFocusQuickAction(input: FocusQuickActionInput): QuickActionValidation<FocusPlan> {
    val title = input.taskTitle.trim()
    if (title.isEmpty()) return QuickActionValidation.Invalid("Saisissez un titre de tâche.")
    if (title.length > FocusPlan.MAX_TASK_TITLE_LENGTH) {
        return QuickActionValidation.Invalid("Le titre ne peut pas dépasser ${FocusPlan.MAX_TASK_TITLE_LENGTH} caractères.")
    }
    val cycles = input.cyclesText.trim().toIntOrNull()
        ?: return QuickActionValidation.Invalid("Saisissez un nombre de cycles de 1 à 12.")
    if (cycles !in 1..12) return QuickActionValidation.Invalid("Le nombre de cycles doit être compris entre 1 et 12.")
    return QuickActionValidation.Valid(
        FocusPlan.fromPreset(input.preset).copy(totalWorkCycles = cycles, taskTitle = title),
    )
}

private val HH_MM = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
private const val MAX_PRIORITY_LENGTH = 120

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CoachQuickActions(controller: AtlasController) {
    var activeForm by remember { mutableStateOf<QuickActionKind?>(null) }
    var dayInput by remember { mutableStateOf(dayScheduleDefaults()) }
    var goalInput by remember { mutableStateOf(goalQuickActionDefaults()) }
    var focusInput by remember { mutableStateOf(focusQuickActionDefaults(controller.directive.title)) }
    var pending by remember { mutableStateOf<PendingCoachQuickAction?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val fontScale = LocalDensity.current.fontScale

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = if (fontScale >= 2f) 2 else 3,
        ) {
            QuickActionButton("Organiser ma journée", "coach_quick_action_day", Modifier.weight(1f)) {
                dayInput = dayScheduleDefaults()
                statusMessage = null
                activeForm = QuickActionKind.DAY
            }
            QuickActionButton("Planifier un objectif", "coach_quick_action_goal", Modifier.weight(1f)) {
                goalInput = goalQuickActionDefaults()
                statusMessage = null
                activeForm = QuickActionKind.GOAL
            }
            QuickActionButton("Focus prêt", "coach_quick_action_focus", Modifier.weight(1f)) {
                statusMessage = null
                if (controller.capabilities[AtlasController.CAP_FOCUS] == true) {
                    focusInput = focusQuickActionDefaults(controller.directive.title)
                    activeForm = QuickActionKind.FOCUS
                } else {
                    statusMessage = "Focus désactivé dans les paramètres."
                }
            }
        }
        statusMessage?.let {
            Text(it, modifier = Modifier.testTag("coach_quick_action_status"), style = MaterialTheme.typography.bodySmall)
        }
    }

    when (activeForm) {
        QuickActionKind.DAY -> DayScheduleDialog(
            input = dayInput,
            onInputChange = { dayInput = it },
            onDismiss = { activeForm = null },
            onReview = { pending = PendingCoachQuickAction.SendMessage(it.message); activeForm = null },
        )
        QuickActionKind.GOAL -> GoalQuickActionDialog(
            input = goalInput,
            onInputChange = { goalInput = it },
            onDismiss = { activeForm = null },
            onReview = { pending = PendingCoachQuickAction.CreateGoal(it); activeForm = null },
        )
        QuickActionKind.FOCUS -> FocusQuickActionDialog(
            input = focusInput,
            onInputChange = { focusInput = it },
            onDismiss = { activeForm = null },
            onReview = { pending = PendingCoachQuickAction.StartFocus(it); activeForm = null },
        )
        null -> Unit
    }

    pending?.let { action ->
        QuickActionConfirmationDialog(
            action = action,
            onDismiss = { pending = null },
            onConfirm = {
                if (pending == action) {
                    pending = null
                    statusMessage = null
                    when (action) {
                        is PendingCoachQuickAction.SendMessage -> controller.sendAgentMessage(action.message)
                        is PendingCoachQuickAction.CreateGoal -> controller.createGoal(action.draft)
                        is PendingCoachQuickAction.StartFocus -> {
                            if (controller.capabilities[AtlasController.CAP_FOCUS] == true) {
                                controller.startFocus(action.plan, goalId = null)
                                controller.navigationState.selectTab(AtlasScreenId.FOCUS)
                            } else {
                                statusMessage = "Focus désactivé dans les paramètres."
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun QuickActionButton(label: String, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        modifier = modifier.heightIn(min = 48.dp).testTag(tag),
        onClick = onClick,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun DayScheduleDialog(
    input: DayScheduleInput,
    onInputChange: (DayScheduleInput) -> Unit,
    onDismiss: () -> Unit,
    onReview: (DayScheduleRequest) -> Unit,
) {
    val validation = validateDaySchedule(input)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Organiser ma journée") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = input.startTime,
                        onValueChange = { onInputChange(input.copy(startTime = it.take(5))) },
                        modifier = Modifier.weight(1f).testTag("coach_day_start"),
                        label = { Text("Début") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                    OutlinedTextField(
                        value = input.endTime,
                        onValueChange = { onInputChange(input.copy(endTime = it.take(5))) },
                        modifier = Modifier.weight(1f).testTag("coach_day_end"),
                        label = { Text("Fin") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                }
                OutlinedTextField(
                    value = input.priority,
                    onValueChange = { onInputChange(input.copy(priority = it.take(MAX_PRIORITY_LENGTH))) },
                    modifier = Modifier.fillMaxWidth().testTag("coach_day_priority"),
                    label = { Text("Priorité facultative") },
                    singleLine = true,
                )
                if (validation is QuickActionValidation.Invalid) {
                    Text(validation.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("coach_day_review"),
                enabled = validation is QuickActionValidation.Valid,
                onClick = { if (validation is QuickActionValidation.Valid) onReview(validation.value) },
            ) { Text("Vérifier") }
        },
        dismissButton = {
            TextButton(modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("coach_day_form_cancel"), onClick = onDismiss) {
                Text("Annuler")
            }
        },
    )
}

@Composable
private fun GoalQuickActionDialog(
    input: GoalQuickActionInput,
    onInputChange: (GoalQuickActionInput) -> Unit,
    onDismiss: () -> Unit,
    onReview: (GoalPanelDraft) -> Unit,
) {
    var step by remember { mutableStateOf(0) }
    var metricMenuOpen by remember { mutableStateOf(false) }
    val validation = validateGoalQuickAction(input)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Planifier un objectif") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step == 0) {
                    OutlinedTextField(
                        value = input.title,
                        onValueChange = { onInputChange(input.copy(title = it.take(GoalRecord.MAX_TITLE_LENGTH))) },
                        modifier = Modifier.fillMaxWidth().testTag("coach_goal_title"),
                        label = { Text("Titre") },
                        singleLine = true,
                    )
                    Box {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("coach_goal_metric"),
                            onClick = { metricMenuOpen = true },
                        ) { Text("Mesure : ${goalMetricLabel(input.metric)}") }
                        DropdownMenu(expanded = metricMenuOpen, onDismissRequest = { metricMenuOpen = false }) {
                            GoalMetric.entries.forEach { metric ->
                                DropdownMenuItem(
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    text = { Text(goalMetricLabel(metric)) },
                                    onClick = {
                                        onInputChange(input.copy(
                                            metric = metric,
                                            targetText = if (metric == GoalMetric.MANUAL) "1"
                                                else input.targetText.takeUnless { it == "1" } ?: "120",
                                        ))
                                        metricMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = input.targetText,
                        onValueChange = { onInputChange(input.copy(targetText = it.filter(Char::isDigit).take(19))) },
                        modifier = Modifier.fillMaxWidth().testTag("coach_goal_target"),
                        label = { Text("Cible") },
                        enabled = input.metric != GoalMetric.MANUAL,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                } else {
                    OutlinedTextField(
                        value = input.dueDateText,
                        onValueChange = { onInputChange(input.copy(dueDateText = it.take(10))) },
                        modifier = Modifier.fillMaxWidth().testTag("coach_goal_due_date"),
                        label = { Text("Échéance · AAAA-MM-JJ") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = input.nextAction,
                        onValueChange = { onInputChange(input.copy(nextAction = it.take(GoalRecord.MAX_NEXT_ACTION_LENGTH))) },
                        modifier = Modifier.fillMaxWidth().testTag("coach_goal_next_action"),
                        label = { Text("Prochaine action") },
                        singleLine = true,
                    )
                }
                if (validation is QuickActionValidation.Invalid) {
                    Text(validation.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.sizeIn(minHeight = 48.dp).testTag(if (step == 0) "coach_goal_next" else "coach_goal_review"),
                enabled = validation is QuickActionValidation.Valid,
                onClick = {
                    if (validation is QuickActionValidation.Valid) {
                        if (step == 0) step = 1 else onReview(validation.value)
                    }
                },
            ) { Text(if (step == 0) "Suite" else "Vérifier") }
        },
        dismissButton = {
            Row {
                if (step == 1) {
                    TextButton(modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("coach_goal_back"), onClick = { step = 0 }) {
                        Text("Retour")
                    }
                }
                TextButton(modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("coach_goal_form_cancel"), onClick = onDismiss) {
                    Text("Annuler")
                }
            }
        },
    )
}

@Composable
private fun FocusQuickActionDialog(
    input: FocusQuickActionInput,
    onInputChange: (FocusQuickActionInput) -> Unit,
    onDismiss: () -> Unit,
    onReview: (FocusPlan) -> Unit,
) {
    var presetMenuOpen by remember { mutableStateOf(false) }
    val validation = validateFocusQuickAction(input)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Focus prêt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("coach_focus_preset"),
                        onClick = { presetMenuOpen = true },
                    ) { Text("Preset : ${focusPresetLabel(input.preset)}") }
                    DropdownMenu(expanded = presetMenuOpen, onDismissRequest = { presetMenuOpen = false }) {
                        FocusPreset.entries.forEach { preset ->
                            DropdownMenuItem(
                                modifier = Modifier.heightIn(min = 48.dp),
                                text = { Text(focusPresetLabel(preset)) },
                                onClick = { onInputChange(input.copy(preset = preset)); presetMenuOpen = false },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = input.taskTitle,
                    onValueChange = { onInputChange(input.copy(taskTitle = it.take(FocusPlan.MAX_TASK_TITLE_LENGTH))) },
                    modifier = Modifier.fillMaxWidth().testTag("coach_focus_task"),
                    label = { Text("Tâche") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = input.cyclesText,
                    onValueChange = { onInputChange(input.copy(cyclesText = it.filter(Char::isDigit).take(2))) },
                    modifier = Modifier.fillMaxWidth().testTag("coach_focus_cycles"),
                    label = { Text("Cycles · 1 à 12") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (validation is QuickActionValidation.Invalid) {
                    Text(validation.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("coach_focus_review"),
                enabled = validation is QuickActionValidation.Valid,
                onClick = { if (validation is QuickActionValidation.Valid) onReview(validation.value) },
            ) { Text("Vérifier") }
        },
        dismissButton = {
            TextButton(modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("coach_focus_form_cancel"), onClick = onDismiss) {
                Text("Annuler")
            }
        },
    )
}

@Composable
private fun QuickActionConfirmationDialog(
    action: PendingCoachQuickAction,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = when (action) {
        is PendingCoachQuickAction.SendMessage -> "Confirmer l’envoi"
        is PendingCoachQuickAction.CreateGoal -> "Confirmer l’objectif"
        is PendingCoachQuickAction.StartFocus -> "Confirmer Focus"
    }
    val effect = when (action) {
        is PendingCoachQuickAction.SendMessage -> action.message
        is PendingCoachQuickAction.CreateGoal -> goalConfirmationText(action.draft)
        is PendingCoachQuickAction.StartFocus -> focusConfirmationText(action.plan)
    }
    val confirmLabel = when (action) {
        is PendingCoachQuickAction.SendMessage -> "Envoyer au coach"
        is PendingCoachQuickAction.CreateGoal -> "Créer l’objectif"
        is PendingCoachQuickAction.StartFocus -> "Démarrer Focus"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(effect, modifier = Modifier.testTag("coach_quick_confirmation_effect")) },
        confirmButton = {
            TextButton(modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("coach_quick_confirm"), onClick = onConfirm) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("coach_quick_cancel"), onClick = onDismiss) {
                Text("Annuler")
            }
        },
    )
}

private fun goalConfirmationText(draft: GoalPanelDraft): String = buildString {
    append("Créer «${draft.title}» avec une cible de ${draft.targetValue} ${goalMetricUnit(draft.metric)}.")
    draft.dueAt?.let { dueAt ->
        append(" Échéance : ${Instant.ofEpochMilli(dueAt).atZone(ZoneId.systemDefault()).toLocalDate()}.")
    }
    if (draft.nextAction.isNotBlank()) append(" Prochaine action : ${draft.nextAction}.")
}

private fun focusConfirmationText(plan: FocusPlan): String = buildString {
    append("Démarrer ${plan.totalWorkCycles} ${if (plan.totalWorkCycles == 1) "cycle" else "cycles"} : ")
    append("${plan.workSeconds / 60} min de concentration, pause courte de ${plan.shortBreakSeconds / 60} min")
    append(" et pause longue de ${plan.longBreakSeconds / 60} min toutes les ${plan.longBreakEveryCycles} cycles.")
    plan.taskTitle?.let { append(" Tâche : «$it».") }
}

private fun goalMetricLabel(metric: GoalMetric): String = when (metric) {
    GoalMetric.FOCUS_MINUTES -> "Focus minutes"
    GoalMetric.HABIT_CHECKINS -> "Habitudes"
    GoalMetric.MANUAL -> "Manuel"
}

private fun goalMetricUnit(metric: GoalMetric): String = when (metric) {
    GoalMetric.FOCUS_MINUTES -> "minutes Focus"
    GoalMetric.HABIT_CHECKINS -> "validations d’habitude"
    GoalMetric.MANUAL -> "confirmation manuelle"
}

private fun focusPresetLabel(preset: FocusPreset): String = when (preset) {
    FocusPreset.POMODORO_25_5 -> "25/5"
    FocusPreset.VARIANT_10_2 -> "10/2"
}

private enum class QuickActionKind { DAY, GOAL, FOCUS }
