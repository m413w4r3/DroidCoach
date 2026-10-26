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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.atlas.coach.app.ui.AtlasStatus
import dev.atlas.coach.app.ui.AtlasStatusKind
import dev.atlas.coach.plan.ActivityStatus
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
        this == ActivityStatus.DONE || this == ActivityStatus.MISSED || this == ActivityStatus.CANCELLED
}

@Composable
internal fun PlanActivityList(
    activities: List<PlanActivity>,
    nowMinute: Int,
    onMove: (PlanActivity) -> Unit,
    onEdit: (PlanActivity) -> Unit,
    onStatus: (PlanActivity) -> Unit,
    onRemove: (PlanActivity) -> Unit,
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
                onStatus = { onStatus(activity) },
                onRemove = { onRemove(activity) },
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
    onStatus: () -> Unit,
    onRemove: () -> Unit,
) {
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
        modifier = Modifier.fillMaxWidth().testTag("plan_activity_row_${activity.activityId}"),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(
            width = if (isNext) 1.5.dp else 0.5.dp,
            color = if (isNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
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
                    label = PlanController.statusLabel(activity.status),
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
                onStatus = onStatus,
                onRemove = onRemove,
            )
        }
    }
}

@Composable
internal fun PlanActivityOverflowMenu(
    activity: PlanActivity,
    onMove: () -> Unit,
    onEdit: () -> Unit,
    onStatus: () -> Unit,
    onRemove: () -> Unit,
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
            DropdownMenuItem(
                text = { Text("Déplacer") },
                onClick = { expanded = false; onMove() },
            )
            DropdownMenuItem(
                text = { Text("Modifier") },
                onClick = { expanded = false; onEdit() },
            )
            DropdownMenuItem(
                text = { Text("Changer le statut") },
                onClick = { expanded = false; onStatus() },
            )
            DropdownMenuItem(
                text = { Text("Supprimer l’activité") },
                onClick = { expanded = false; onRemove() },
            )
        }
    }
}

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
