package dev.atlas.coach.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.atlas.coach.app.ui.AtlasPrimaryButton
import dev.atlas.coach.app.ui.AtlasSecondaryButton
import dev.atlas.coach.app.ui.AtlasSection
import dev.atlas.coach.app.ui.AtlasSpacing
import dev.atlas.coach.app.ui.AtlasStatus
import dev.atlas.coach.app.ui.AtlasStatusKind
import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.ActivityStatus
import dev.atlas.coach.plan.DayPlan
import dev.atlas.coach.plan.PlanActivity
import dev.atlas.coach.plan.PlanPreview
import dev.atlas.coach.plan.Priority
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.delay

/**
 * Local day-plan surface. Every mutation is previewed before it is applied; no
 * network, calendar provider or agent is contacted from this screen.
 */
@Composable
internal fun PlanScreen(controller: PlanController) {
    val plan = controller.plan
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PlanActivity?>(null) }
    var moving by remember { mutableStateOf<PlanActivity?>(null) }
    var changingStatus by remember { mutableStateOf<PlanActivity?>(null) }
    var removing by remember { mutableStateOf<PlanActivity?>(null) }

    // Reload on foreground return and at the next local midnight so a long-lived
    // process never shows yesterday's plan under today's date.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) controller.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            val zone = ZoneId.of(controller.timezone)
            val now = ZonedDateTime.now(zone)
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
            delay(Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1_000L))
            controller.refresh()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AtlasSpacing.page),
        verticalArrangement = Arrangement.spacedBy(AtlasSpacing.medium),
    ) {
        Text("Plan du jour", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "${controller.today} · ${controller.timezone}" +
                (plan?.let { " · version ${it.version}" } ?: " · aucun programme"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        controller.corruption?.let { reason ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(AtlasSpacing.medium), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Stockage du plan illisible", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Le programme local n'a pas pu être relu ($reason). ATLAS ne l'écrase pas automatiquement.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "Une copie de diagnostic expurgée a pu être conservée. Vérifiez le diagnostic ou repartez d'un nouveau programme en connaissance de cause.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (plan == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(AtlasSpacing.medium), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Construire la journée", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Le programme est stocké uniquement sur ce téléphone. Il reste lisible hors ligne et après redémarrage.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    AtlasPrimaryButton("Créer le programme du jour", onClick = { controller.createTodayPlan() })
                    AtlasSecondaryButton("Relire le stockage local", onClick = { controller.refresh() })
                }
            }
        } else {
            PlanHeaderCard(plan)
            AtlasPrimaryButton("Ajouter une activité", onClick = { showAdd = true })
            AtlasSection("Créneaux") {
                if (plan.activities.isEmpty()) {
                    Text(
                        "Aucune activité. Un jour de repos est un programme valide : ajoutez seulement ce qui est utile.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    plan.activities.sortedBy { it.scheduledLocal.startMinute }.forEach { activity ->
                        PlanActivityCard(
                            activity = activity,
                            onMove = { moving = activity },
                            onEdit = { editing = activity },
                            onStatus = { changingStatus = activity },
                            onRemove = { removing = activity },
                        )
                    }
                }
            }
            Text(
                "Les modifications sont locales. Aucun événement de calendrier n'est créé par cet écran tant que la permission calendrier n'a pas été approuvée.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        controller.message?.let { message ->
            NoticeCard(message)
            AtlasSecondaryButton("Masquer le message", onClick = controller::dismissMessage)
        }
    }

    if (showAdd) {
        ActivityEditorDialog(
            title = "Ajouter une activité",
            initial = null,
            withTime = true,
            onDismiss = { showAdd = false },
            onConfirm = { draft ->
                val added = if (draft.editing == null) {
                    controller.addActivity(
                        title = draft.title,
                        kind = draft.kind,
                        startMinute = draft.startMinute,
                        durationMinutes = draft.durationMinutes,
                        priority = draft.priority,
                        notes = draft.notes,
                    )
                } else {
                    false
                }
                if (added) showAdd = false
            },
        )
    }

    editing?.let { activity ->
        ActivityEditorDialog(
            title = "Modifier l'activité",
            initial = activity,
            withTime = false,
            onDismiss = { editing = null },
            onConfirm = { draft ->
                val edited = controller.editActivity(
                    activityId = activity.activityId,
                    title = draft.title,
                    kind = draft.kind,
                    priority = draft.priority,
                    notes = draft.notes,
                )
                if (edited) editing = null
            },
        )
    }

    moving?.let { activity ->
        MoveActivityDialog(
            activity = activity,
            onDismiss = { moving = null },
            onConfirm = { minute ->
                if (controller.moveActivity(activity.activityId, minute)) moving = null
            },
        )
    }

    changingStatus?.let { activity ->
        StatusDialog(
            activity = activity,
            allowed = controller.allowedStatuses(activity),
            onDismiss = { changingStatus = null },
            onSelect = { status ->
                if (controller.setStatus(activity.activityId, status)) changingStatus = null
            },
        )
    }

    removing?.let { activity ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Supprimer cette activité ?") },
            text = { Text("« ${activity.title} » sera retirée du programme après confirmation. Une activité terminée ne peut pas être supprimée par ce chemin.") },
            confirmButton = {
                TextButton(onClick = {
                    if (controller.removeActivity(activity.activityId)) removing = null
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Annuler") } },
        )
    }

    controller.preview?.let { preview ->
        RevisionPreviewDialog(
            preview = preview,
            onCancel = {
                controller.cancelPreview()
            },
            onConfirm = {
                controller.confirmPreview()
            },
        )
    }
}

@Composable
private fun PlanHeaderCard(plan: DayPlan) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AtlasSpacing.medium), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (plan.activities.isEmpty()) "Journée libre" else "${plan.activities.size} activité(s)",
                style = MaterialTheme.typography.titleMedium,
            )
            AtlasStatus(
                label = when (plan.state) {
                    dev.atlas.coach.plan.PlanState.APPROVED -> "Programme local approuvé"
                    dev.atlas.coach.plan.PlanState.DRAFT -> "Brouillon local"
                    dev.atlas.coach.plan.PlanState.SUPERSEDED -> "Programme remplacé"
                    dev.atlas.coach.plan.PlanState.ARCHIVED -> "Programme archivé"
                },
                kind = if (plan.state == dev.atlas.coach.plan.PlanState.APPROVED) AtlasStatusKind.ACTIVE else AtlasStatusKind.SIMULATED,
            )
            Text(
                "Version ${plan.version} · fuseau ${plan.timezone} · stockage privé atomique",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlanActivityCard(
    activity: PlanActivity,
    onMove: () -> Unit,
    onEdit: () -> Unit,
    onStatus: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AtlasSpacing.medium), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${PlanController.timeLabel(activity.scheduledLocal.startMinute)} – " +
                        PlanController.endTimeLabel(activity.scheduledLocal.startMinute, activity.scheduledLocal.durationMinutes),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
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
            Text(activity.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "${PlanController.kindLabel(activity.kind)} · ${PlanController.priorityLabel(activity.priority)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            activity.notes?.let { notes ->
                Text(notes, style = MaterialTheme.typography.bodySmall)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onMove,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Déplacer ${activity.title}" },
                ) { Text("Déplacer") }
                TextButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Modifier ${activity.title}" },
                ) { Text("Modifier") }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onStatus,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Changer le statut de ${activity.title}" },
                ) { Text("Statut") }
                TextButton(
                    onClick = onRemove,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Supprimer ${activity.title}" },
                ) { Text("Supprimer") }
            }
        }
    }
}

private data class ActivityDraft(
    val editing: PlanActivity?,
    val title: String,
    val kind: ActivityKind,
    val startMinute: Int,
    val durationMinutes: Int,
    val priority: Priority,
    val notes: String?,
)

@Composable
private fun ActivityEditorDialog(
    title: String,
    initial: PlanActivity?,
    withTime: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (ActivityDraft) -> Unit,
) {
    var titleValue by remember { mutableStateOf(initial?.title.orEmpty()) }
    var kind by remember { mutableStateOf(initial?.kind ?: ActivityKind.FOCUS) }
    var priority by remember { mutableStateOf(initial?.priority ?: Priority.IMPORTANT) }
    var startText by remember { mutableStateOf(initial?.scheduledLocal?.startMinute?.let(PlanController::timeLabel) ?: "09:00") }
    var durationText by remember { mutableStateOf((initial?.scheduledLocal?.durationMinutes ?: 50).toString()) }
    var notes by remember { mutableStateOf(initial?.notes.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = titleValue,
                    onValueChange = { titleValue = it.take(80) },
                    label = { Text("Titre") },
                    singleLine = true,
                )
                Text("Type", style = MaterialTheme.typography.labelLarge)
                listOf(
                    listOf(ActivityKind.FOCUS, ActivityKind.TRAINING),
                    listOf(ActivityKind.APPOINTMENT, ActivityKind.ADMIN),
                    listOf(ActivityKind.MEAL, ActivityKind.REST),
                    listOf(ActivityKind.BUFFER, ActivityKind.OTHER),
                ).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        line.forEach { option ->
                            FilterChip(
                                selected = kind == option,
                                onClick = { kind = option },
                                label = { Text(PlanController.kindLabel(option)) },
                            )
                        }
                    }
                }
                if (withTime) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = startText,
                            onValueChange = { startText = it.take(5) },
                            label = { Text("Début (HH:mm)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = durationText,
                            onValueChange = { durationText = it.filter(Char::isDigit).take(4) },
                            label = { Text("Durée (min)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text("Priorité", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Priority.entries.forEach { option ->
                        FilterChip(
                            selected = priority == option,
                            onClick = { priority = option },
                            label = { Text(PlanController.priorityLabel(option)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(280) },
                    label = { Text("Note locale (facultative)") },
                    minLines = 2,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val start = PlanController.parseTime(startText)
                val duration = durationText.toIntOrNull()
                if (titleValue.trim().isEmpty()) {
                    error = "Le titre est obligatoire."
                } else if (withTime && (start == null || duration == null || !validWindow(start, duration))) {
                    error = "Horaire invalide : utilisez HH:mm et une durée qui reste dans la journée."
                } else {
                    onConfirm(
                        ActivityDraft(
                            editing = initial,
                            title = titleValue.trim(),
                            kind = kind,
                            startMinute = start ?: initial?.scheduledLocal?.startMinute ?: 0,
                            durationMinutes = duration ?: initial?.scheduledLocal?.durationMinutes ?: 1,
                            priority = priority,
                            notes = notes.trim().takeIf(String::isNotEmpty),
                        ),
                    )
                }
            }) { Text("Prévisualiser") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun MoveActivityDialog(
    activity: PlanActivity,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var startText by remember { mutableStateOf(PlanController.timeLabel(activity.scheduledLocal.startMinute)) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Déplacer « ${activity.title} »") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it.take(5) },
                    label = { Text("Nouveau début (HH:mm)") },
                    singleLine = true,
                )
                Text(
                    "Durée conservée : ${activity.scheduledLocal.durationMinutes} min.",
                    style = MaterialTheme.typography.bodySmall,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val minute = PlanController.parseTime(startText)
                if (minute == null || !validWindow(minute, activity.scheduledLocal.durationMinutes)) {
                    error = "Horaire invalide."
                } else {
                    onConfirm(minute)
                }
            }) { Text("Prévisualiser") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun StatusDialog(
    activity: PlanActivity,
    allowed: List<ActivityStatus>,
    onDismiss: () -> Unit,
    onSelect: (ActivityStatus) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Statut de « ${activity.title} »") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Un statut est déclaré explicitement : l'heure qui passe ne rend jamais une activité terminée.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (allowed.isEmpty()) {
                    Text(
                        "Ce statut est terminal. Créez une nouvelle activité si une replanification est nécessaire.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    allowed.forEach { status ->
                        TextButton(onClick = { onSelect(status) }) {
                            Text("${PlanController.statusLabel(status)} (${status.name})")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

@Composable
private fun RevisionPreviewDialog(
    preview: PlanPreview,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Aperçu de la révision") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(preview.revision.summary, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Version ${preview.before.version} → ${preview.after.version} · ${preview.effectIds.size} effet(s) local(aux)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                preview.revision.changes.forEach { change ->
                    Text("• ${PlanController.revisionChangeLabel(change)}", style = MaterialTheme.typography.bodyMedium)
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

private fun validWindow(startMinute: Int, durationMinutes: Int): Boolean =
    startMinute in 0..1439 && durationMinutes in 1..1440 &&
        startMinute + durationMinutes <= PlanController.MINUTES_PER_DAY
