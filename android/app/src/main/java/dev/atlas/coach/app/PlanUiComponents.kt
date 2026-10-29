package dev.atlas.coach.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.atlas.coach.app.ui.AtlasPrimaryButton
import dev.atlas.coach.app.ui.AtlasSecondaryButton
import dev.atlas.coach.app.ui.AtlasStatus
import dev.atlas.coach.app.ui.AtlasStatusKind
import dev.atlas.coach.app.ui.AtlasSpacing
import dev.atlas.coach.plan.ActivityStatus
import dev.atlas.coach.plan.DayPlan
import dev.atlas.coach.plan.PlanActivity
import dev.atlas.coach.plan.PlanChange
import dev.atlas.coach.plan.PlanPreview
import dev.atlas.coach.plan.Priority
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class PlanActivityTiming(val label: String, val glyph: String) {
    PAST("Passée", "✓"),
    CURRENT("En cours", "●"),
    UPCOMING("À venir", "○"),
}

/** Pure presentation rules shared by the day-plan screen and its JVM tests. */
internal object PlanUiLogic {
    fun sortActivities(activities: List<PlanActivity>): List<PlanActivity> =
        activities.sortedBy { it.scheduledLocal.startMinute }

    fun timing(activity: PlanActivity, nowMinute: Int): PlanActivityTiming {
        val start = activity.scheduledLocal.startMinute
        val end = start + activity.scheduledLocal.durationMinutes
        return when {
            nowMinute >= end -> PlanActivityTiming.PAST
            nowMinute >= start -> PlanActivityTiming.CURRENT
            else -> PlanActivityTiming.UPCOMING
        }
    }

    fun nextActivity(activities: List<PlanActivity>, nowMinute: Int): PlanActivity? {
        val candidates = sortActivities(activities).filterNot { it.status.isTerminalForNextActivity() }
        return candidates.firstOrNull { timing(it, nowMinute) == PlanActivityTiming.CURRENT }
            ?: candidates.firstOrNull { timing(it, nowMinute) == PlanActivityTiming.UPCOMING }
    }

    fun statusLabel(status: ActivityStatus): String = PlanController.statusLabel(status)

    fun primaryActionLabel(status: ActivityStatus): String? = when (status) {
        ActivityStatus.PLANNED -> "Démarrer"
        ActivityStatus.IN_PROGRESS -> "Terminé"
        ActivityStatus.DONE, ActivityStatus.CANCELLED, ActivityStatus.MISSED, ActivityStatus.POSTPONED -> null
    }

    fun canMarkMissed(activity: PlanActivity, nowMinute: Int): Boolean =
        activity.status in setOf(ActivityStatus.PLANNED, ActivityStatus.IN_PROGRESS) &&
            nowMinute >= activity.scheduledLocal.startMinute + activity.scheduledLocal.durationMinutes

    fun reportTarget(
        planDate: String,
        activity: PlanActivity,
        choice: PlanReportChoice,
        customStartMinute: Int? = null,
    ): PlanReportTarget? {
        val date = LocalDate.parse(planDate)
        val start = activity.scheduledLocal.startMinute
        return when (choice) {
            PlanReportChoice.PLUS_15, PlanReportChoice.PLUS_30 -> {
                val offset = if (choice == PlanReportChoice.PLUS_15) 15 else 30
                val absoluteMinute = start + offset
                PlanReportTarget(
                    localDate = date.plusDays((absoluteMinute / MINUTES_PER_DAY).toLong()).toString(),
                    startMinute = absoluteMinute % MINUTES_PER_DAY,
                    durationMinutes = activity.scheduledLocal.durationMinutes,
                )
            }
            PlanReportChoice.TOMORROW -> PlanReportTarget(
                localDate = date.plusDays(1).toString(),
                startMinute = start,
                durationMinutes = activity.scheduledLocal.durationMinutes,
            )
            PlanReportChoice.CUSTOM -> customStartMinute
                ?.takeIf { it in 0..(MINUTES_PER_DAY - activity.scheduledLocal.durationMinutes) }
                ?.let { PlanReportTarget(date.toString(), it, activity.scheduledLocal.durationMinutes) }
        }
    }

    fun reportConflicts(plan: DayPlan, activity: PlanActivity, target: PlanReportTarget): List<PlanActivity> {
        if (target.localDate != plan.localDate) return emptyList()
        val targetEnd = target.startMinute + target.durationMinutes
        return sortActivities(plan.activities).filter { other ->
            other.activityId != activity.activityId &&
                other.status in setOf(ActivityStatus.PLANNED, ActivityStatus.IN_PROGRESS) &&
                other.scheduledLocal.localDate == target.localDate &&
                target.startMinute < other.scheduledLocal.startMinute + other.scheduledLocal.durationMinutes &&
                other.scheduledLocal.startMinute < targetEnd
        }
    }

    fun priorityLabel(priority: Priority): String = PlanController.priorityLabel(priority)

    fun changeLabel(change: PlanChange): String = PlanController.revisionChangeLabel(change)

    fun emptyStateLabel(hasDocument: Boolean, activityCount: Int): String? = when {
        !hasDocument -> "Aucun programme pour cette date"
        activityCount == 0 -> "Journée libre"
        else -> null
    }

    fun progressLabel(activities: List<PlanActivity>): String {
        if (activities.isEmpty()) return "Journée libre · aucune activité"
        val completed = activities.count { it.status == ActivityStatus.DONE }
        val ending = if (completed == 1) "terminée" else "terminées"
        return "$completed sur ${activities.size} $ending"
    }

    fun readableDate(isoDate: String): String = LocalDate.parse(isoDate)
        .format(DateTimeFormatter.ofPattern("EEEE d MMMM uuuu", Locale.FRENCH))
        .replaceFirstChar { first -> first.titlecase(Locale.FRENCH) }

    private fun ActivityStatus.isTerminalForNextActivity(): Boolean =
        this == ActivityStatus.DONE || this == ActivityStatus.MISSED ||
            this == ActivityStatus.CANCELLED || this == ActivityStatus.POSTPONED

    private const val MINUTES_PER_DAY = 24 * 60
}

internal enum class PlanReportChoice(val label: String) {
    PLUS_15("+15 min"),
    PLUS_30("+30 min"),
    TOMORROW("Demain"),
    CUSTOM("Choisir une heure"),
}

internal data class PlanReportTarget(
    val localDate: String,
    val startMinute: Int,
    val durationMinutes: Int,
)

@Composable
internal fun PlanNextActivityCard(activity: PlanActivity) {
    Card(modifier = Modifier.fillMaxWidth().testTag("plan_next_activity_card")) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AtlasSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AtlasStatus("Prochaine activité", AtlasStatusKind.ACTIVE)
            Text(activity.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "${PlanController.timeLabel(activity.scheduledLocal.startMinute)} – " +
                    "${PlanController.endTimeLabel(activity.scheduledLocal.startMinute, activity.scheduledLocal.durationMinutes)} · " +
                    "${activity.scheduledLocal.durationMinutes} min",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Priorité · ${PlanController.priorityLabel(activity.priority)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun PlanActivityList(
    activities: List<PlanActivity>,
    nowMinute: Int,
    onMove: (PlanActivity) -> Unit,
    onEdit: (PlanActivity) -> Unit,
    onRemove: (PlanActivity) -> Unit,
    onPrimary: (PlanActivity) -> Unit = {},
    onReport: (PlanActivity) -> Unit = {},
    onMarkMissed: (PlanActivity) -> Unit = {},
    onCancel: (PlanActivity) -> Unit = {},
    onAddNote: (PlanActivity) -> Unit = {},
) {
    val ordered = PlanUiLogic.sortActivities(activities)
    val nextActivityId = PlanUiLogic.nextActivity(ordered, nowMinute)?.activityId
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ordered.forEach { activity ->
            PlanActivityRow(
                activity = activity,
                timing = PlanUiLogic.timing(activity, nowMinute),
                isNext = activity.activityId == nextActivityId,
                onMove = { onMove(activity) },
                onEdit = { onEdit(activity) },
                onRemove = { onRemove(activity) },
                onPrimary = { onPrimary(activity) },
                onReport = { onReport(activity) },
                onMarkMissed = { onMarkMissed(activity) },
                onCancel = { onCancel(activity) },
                onAddNote = { onAddNote(activity) },
                canMarkMissed = PlanUiLogic.canMarkMissed(activity, nowMinute),
            )
        }
    }
}

@Composable
internal fun PlanActivityRow(
    activity: PlanActivity,
    timing: PlanActivityTiming,
    isNext: Boolean,
    onMove: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onPrimary: () -> Unit = {},
    onReport: () -> Unit = {},
    onMarkMissed: () -> Unit = {},
    onCancel: () -> Unit = {},
    onAddNote: () -> Unit = onEdit,
    canMarkMissed: Boolean = false,
) {
    val statusLabel = PlanController.statusLabel(activity.status)
    val timeRange = "${PlanController.timeLabel(activity.scheduledLocal.startMinute)} – " +
        PlanController.endTimeLabel(activity.scheduledLocal.startMinute, activity.scheduledLocal.durationMinutes)
    val primaryLabel = PlanUiLogic.primaryActionLabel(activity.status)
    val rowDescription = "${timeRange}. ${activity.title}. État : $statusLabel." +
        (primaryLabel?.let { " Actions : $it, Reporter." } ?: " Actions : Plus d’actions.")
    val containerColor = if (isNext) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isNext) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier.fillMaxWidth()
            .semantics { contentDescription = rowDescription }
            .testTag("plan_activity_row_${activity.activityId}"),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(
            width = if (isNext) 1.5.dp else 0.5.dp,
            color = if (isNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier.widthIn(min = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        timing.glyph,
                        modifier = Modifier.semantics {
                            contentDescription = "Repère temporel : ${timing.label}, ${timing.glyph}"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = when (timing) {
                            PlanActivityTiming.PAST -> MaterialTheme.colorScheme.onSurfaceVariant
                            PlanActivityTiming.CURRENT -> MaterialTheme.colorScheme.primary
                            PlanActivityTiming.UPCOMING -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (isNext) AtlasStatus("Prochaine", AtlasStatusKind.ACTIVE)
                    Text(activity.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${timing.label} · ${PlanController.timeLabel(activity.scheduledLocal.startMinute)} – " +
                            "${PlanController.endTimeLabel(activity.scheduledLocal.startMinute, activity.scheduledLocal.durationMinutes)} · " +
                            "${activity.scheduledLocal.durationMinutes} min",
                        modifier = Modifier.semantics {
                            contentDescription = "${timing.glyph}, ${timing.label}. " +
                                "${PlanController.timeLabel(activity.scheduledLocal.startMinute)} à " +
                                "${PlanController.endTimeLabel(activity.scheduledLocal.startMinute, activity.scheduledLocal.durationMinutes)}, " +
                                "${activity.scheduledLocal.durationMinutes} minutes"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "${PlanController.kindLabel(activity.kind)} · ${PlanController.priorityLabel(activity.priority)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AtlasStatus(
                        label = statusLabel,
                        kind = when (activity.status) {
                            ActivityStatus.DONE -> AtlasStatusKind.ACTIVE
                            ActivityStatus.IN_PROGRESS -> AtlasStatusKind.AUTHORIZED
                            ActivityStatus.MISSED, ActivityStatus.CANCELLED -> AtlasStatusKind.UNAVAILABLE
                            ActivityStatus.PLANNED, ActivityStatus.POSTPONED -> AtlasStatusKind.SIMULATED
                        },
                    )
                }
                PlanActivityOverflowMenu(
                    activity = activity,
                    onMove = onMove,
                    onEdit = onEdit,
                    onRemove = onRemove,
                    onMarkMissed = onMarkMissed,
                    onCancel = onCancel,
                    onAddNote = onAddNote,
                    canMarkMissed = canMarkMissed,
                )
            }
            if (primaryLabel != null) {
                PlanActivityActionButtons(activity, onPrimary, onReport)
            }
        }
    }
}

@Composable
internal fun PlanActivityActionButtons(
    activity: PlanActivity,
    onPrimary: () -> Unit,
    onReport: () -> Unit,
    enabled: Boolean = true,
) {
    val primaryLabel = PlanUiLogic.primaryActionLabel(activity.status) ?: return
    val statusLabel = PlanController.statusLabel(activity.status)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onPrimary,
            enabled = enabled,
            modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp)
                .semantics {
                    contentDescription = "$primaryLabel ${activity.title}, prévu à " +
                        PlanController.timeLabel(activity.scheduledLocal.startMinute) + ", état $statusLabel"
                }
                .testTag("plan_activity_primary_${activity.activityId}"),
        ) { Text(primaryLabel, maxLines = 2) }
        OutlinedButton(
            onClick = onReport,
            enabled = enabled,
            modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp)
                .semantics {
                    contentDescription = "Reporter ${activity.title}, prévu à " +
                        PlanController.timeLabel(activity.scheduledLocal.startMinute) + ", état $statusLabel"
                }
                .testTag("plan_activity_report_${activity.activityId}"),
        ) { Text("Reporter", maxLines = 2) }
    }
}

internal fun performPlanActivityPrimaryAction(
    activity: PlanActivity,
    planController: PlanController,
    atlasController: AtlasController?,
): Boolean {
    val targetStatus = when (activity.status) {
        ActivityStatus.PLANNED -> ActivityStatus.IN_PROGRESS
        ActivityStatus.IN_PROGRESS -> ActivityStatus.DONE
        ActivityStatus.DONE, ActivityStatus.CANCELLED, ActivityStatus.MISSED, ActivityStatus.POSTPONED -> return false
    }
    val applied = planController.setStatusAndConfirm(activity.activityId, targetStatus)
    if (
        applied && targetStatus == ActivityStatus.IN_PROGRESS &&
        activity.kind == dev.atlas.coach.plan.ActivityKind.FOCUS
    ) {
        atlasController?.let { focusController ->
            if (!focusController.startFocusForPlanActivity(
                    activity.planId,
                    activity.activityId,
                    activity.scheduledLocal.localDate,
                    activity.title,
                )
            ) {
                focusController.latestMessage?.let(planController::showTransientMessage)
            }
        }
    }
    return applied
}

@Composable
internal fun PlanActivityOverflowMenu(
    activity: PlanActivity,
    onMove: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onMarkMissed: () -> Unit = {},
    onCancel: () -> Unit = {},
    onAddNote: () -> Unit = onEdit,
    canMarkMissed: Boolean = false,
) {
    var expanded by rememberSaveable(activity.activityId) { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics { contentDescription = "Plus d’actions pour ${activity.title}" },
        ) {
            Text("Plus")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Ajouter une note") }, onClick = { expanded = false; onAddNote() })
            if (activity.status == ActivityStatus.PLANNED) {
                DropdownMenuItem(text = { Text("Modifier") }, onClick = { expanded = false; onEdit() })
                DropdownMenuItem(text = { Text("Déplacer") }, onClick = { expanded = false; onMove() })
            }
            if (canMarkMissed) {
                DropdownMenuItem(text = { Text("Pas fait") }, onClick = { expanded = false; onMarkMissed() })
            }
            if (activity.status == ActivityStatus.PLANNED || activity.status == ActivityStatus.IN_PROGRESS) {
                DropdownMenuItem(text = { Text("Annuler") }, onClick = { expanded = false; onCancel() })
            }
            if (activity.status == ActivityStatus.PLANNED) {
                DropdownMenuItem(text = { Text("Supprimer l’activité") }, onClick = { expanded = false; onRemove() })
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PlanReportBottomSheet(
    plan: DayPlan,
    activity: PlanActivity,
    onDismiss: () -> Unit,
    onConfirm: (PlanReportTarget) -> Boolean,
    failureMessage: () -> String? = { null },
) {
    var choice by rememberSaveable(activity.activityId) { mutableStateOf(PlanReportChoice.PLUS_15) }
    var customTime by rememberSaveable(activity.activityId) {
        mutableStateOf(PlanController.timeLabel((activity.scheduledLocal.startMinute + 15) % (24 * 60)))
    }
    var localError by rememberSaveable(activity.activityId) { mutableStateOf<String?>(null) }
    var busy by rememberSaveable(activity.activityId) { mutableStateOf(false) }
    val customMinute = remember(customTime) {
        Regex("^([01]?[0-9]|2[0-3]):([0-5][0-9])$").matchEntire(customTime)?.let {
            it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt()
        }
    }
    val target = PlanUiLogic.reportTarget(plan.localDate, activity, choice, customMinute)
    val conflicts = target?.let { PlanUiLogic.reportConflicts(plan, activity, it) }.orEmpty()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(AtlasSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Reporter l’activité", style = MaterialTheme.typography.titleLarge)
            Text("Créneau actuel · ${PlanUiLogic.readableDate(activity.scheduledLocal.localDate)} · ${timeRangeLabel(activity)}")
            PlanReportChoice.entries.chunked(2).forEach { rowChoices ->
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowChoices.forEach { option ->
                        FilterChip(
                            selected = choice == option,
                            onClick = { choice = option; localError = null },
                            label = { Text(option.label) },
                            modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp)
                                .testTag("plan_report_choice_${option.name.lowercase()}"),
                        )
                    }
                    if (rowChoices.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
            }
            if (choice == PlanReportChoice.CUSTOM) {
                OutlinedTextField(
                    value = customTime,
                    onValueChange = { customTime = it.take(5); localError = null },
                    label = { Text("Nouvelle heure (HH:mm)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("plan_report_custom_time"),
                )
            }
            if (target == null) {
                Text("Saisissez une heure valide comprise entre 00:00 et 23:59.", color = MaterialTheme.colorScheme.error)
            } else {
                val targetSchedule = PlanController.timeLabel(target.startMinute) + " – " +
                    PlanController.endTimeLabel(target.startMinute, target.durationMinutes)
                Text(
                    "Nouveau créneau proposé · ${PlanUiLogic.readableDate(target.localDate)} · $targetSchedule · " +
                        "${target.durationMinutes} min · ${plan.timezone} · priorité ${PlanController.priorityLabel(activity.priority)}",
                    modifier = Modifier.testTag("plan_report_proposed_slot"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (target.localDate != plan.localDate) {
                    Text("Les conflits de cette autre date ne sont pas chargés.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (conflicts.isNotEmpty()) {
                    Text("Conflit avec :", color = MaterialTheme.colorScheme.error)
                    conflicts.forEach { conflict ->
                        Text("${conflict.title} · ${PlanController.timeLabel(conflict.scheduledLocal.startMinute)} – " +
                            PlanController.endTimeLabel(conflict.scheduledLocal.startMinute, conflict.scheduledLocal.durationMinutes))
                    }
                } else {
                    Text("Aucun chevauchement détecté.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            (localError ?: failureMessage())?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("plan_report_error")) }
            AtlasPrimaryButton(
                label = if (busy) "Report…" else "Confirmer le report",
                enabled = !busy && target != null && conflicts.isEmpty(),
                modifier = Modifier.testTag("plan_report_confirm"),
                onClick = {
                    val selectedTarget = target ?: return@AtlasPrimaryButton
                    if (!busy) {
                        busy = true
                        val applied = runCatching { onConfirm(selectedTarget) }.getOrDefault(false)
                        busy = false
                        if (applied) onDismiss() else localError = failureMessage() ?: "Le report n’a pas été appliqué."
                    }
                },
            )
            AtlasSecondaryButton("Fermer", onClick = onDismiss, enabled = !busy)
        }
    }
}

@Composable
internal fun PlanNoteDialog(
    activity: PlanActivity,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Boolean,
    failureMessage: () -> String? = { null },
) {
    var note by rememberSaveable(activity.activityId) { mutableStateOf(activity.notes.orEmpty()) }
    var error by rememberSaveable(activity.activityId) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (activity.notes.isNullOrBlank()) "Ajouter une note" else "Modifier la note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(activity.title)
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(280); error = null },
                    label = { Text("Note personnelle") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().testTag("plan_activity_note_field"),
                )
                (error ?: failureMessage())?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (!onConfirm(note.trim().takeIf(String::isNotEmpty))) error = failureMessage() ?: "La note n’a pas été enregistrée." },
                modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("plan_activity_note_save"),
            ) { Text("Enregistrer la note") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Fermer") }
        },
    )
}

private fun timeRangeLabel(activity: PlanActivity): String =
    "${PlanController.timeLabel(activity.scheduledLocal.startMinute)} – " +
        PlanController.endTimeLabel(activity.scheduledLocal.startMinute, activity.scheduledLocal.durationMinutes)

@Composable
internal fun PlanRemoveConfirmationDialog(
    activity: PlanActivity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Supprimer cette activité ?") },
        text = { Text("« ${activity.title} » sera retirée du programme après confirmation. Une activité terminée ne peut pas être supprimée par ce chemin.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Supprimer l’activité") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
internal fun PlanDetailsDialog(
    date: String,
    version: Int,
    timezone: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Détails du programme") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Date : ${PlanUiLogic.readableDate(date)}")
                Text("Version : $version")
                Text("Fuseau : $timezone")
                Text("Stockage : privé, écriture atomique")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

@Composable
internal fun RevisionPreviewDialog(
    preview: PlanPreview,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Aperçu de la révision") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(preview.revision.summary, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Version ${preview.before.version} → ${preview.after.version} · ${preview.effectIds.size} effet(s) local(aux)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                preview.revision.changes.forEach { change ->
                    Text("• ${PlanUiLogic.changeLabel(change)}", style = MaterialTheme.typography.bodyMedium)
                }
                if (preview.overlapWarnings.isNotEmpty()) {
                    Text(
                        "${preview.overlapWarnings.size} chevauchement(s) signalé(s). Ils restent autorisés mais doivent être relus.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "Confirmer applique exactement cet ensemble d'effets locaux. Un effet supplémentaire demanderait une nouvelle autorisation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Confirmer") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Abandonner") } },
    )
}
