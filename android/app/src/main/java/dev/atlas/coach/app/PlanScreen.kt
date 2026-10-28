package dev.atlas.coach.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import dev.atlas.coach.plan.PlanActivity
import dev.atlas.coach.plan.Priority
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Local day-plan surface. Local mutations are previewed before application.
 * The explicit coach request is the only network path: it requires the
 * planRevision capability and consent, and it still produces only a preview.
 */
@Composable
internal fun PlanScreen(sourceController: PlanController, atlasController: AtlasController? = null) {
    var selectedTomorrow by rememberSaveable { mutableStateOf(false) }
    var optionsExpanded by rememberSaveable { mutableStateOf(false) }
    var currentDate by remember(sourceController) { mutableStateOf(sourceController.today) }
    LaunchedEffect(sourceController.revision) {
        currentDate = sourceController.today
    }
    val tomorrowController = remember(currentDate) {
        sourceController.forDate(LocalDate.parse(currentDate).plusDays(1))
    }
    val controller = if (selectedTomorrow) tomorrowController else sourceController
    val plan = controller.plan
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var showCoachRequest by rememberSaveable { mutableStateOf(false) }
    var agentMessage by rememberSaveable { mutableStateOf("Propose une organisation de ma journée.") }
    var configurationMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var coachBusy by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var movingId by rememberSaveable { mutableStateOf<String?>(null) }
    var changingStatusId by rememberSaveable { mutableStateOf<String?>(null) }
    var removingId by rememberSaveable { mutableStateOf<String?>(null) }
    var calendarPreview by remember { mutableStateOf<CalendarSyncPreview?>(null) }
    var calendarPlanAtDialogOpen by remember { mutableStateOf<dev.atlas.coach.plan.DayPlan?>(null) }
    var calendarSyncBusy by remember { mutableStateOf(false) }
    var calendarSyncMessage by remember { mutableStateOf<String?>(null) }
    var calendarDiverged by remember { mutableStateOf(false) }
    val appContext = LocalContext.current.applicationContext
    var calendarEffectController by remember { mutableStateOf<CalendarEffectController?>(null) }
    var currentMinute by remember(controller) {
        mutableIntStateOf(ZonedDateTime.now(ZoneId.of(controller.timezone)).let { it.hour * 60 + it.minute })
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(appContext) {
        calendarEffectController = withContext(Dispatchers.IO) {
            // A compose test host or an unavailable private storage must not crash the plan UI.
            runCatching { CalendarEffectController.fromContext(appContext) }.getOrNull()
        }
    }

    LaunchedEffect(calendarEffectController, controller, controller.revision, plan?.version) {
        val effectController = calendarEffectController ?: return@LaunchedEffect
        val appliedPlan = plan?.takeIf { it.version >= 2 } ?: return@LaunchedEffect
        val verification = withContext(Dispatchers.IO) { effectController.verifyPlan(appliedPlan) }
        calendarDiverged = verification.any {
            it.state in setOf(CalendarPlanVerificationState.DIVERGED, CalendarPlanVerificationState.ORPHANED)
        }
    }

    // Reload on foreground return and at the next local midnight so a long-lived
    // process never shows yesterday's plan under today's date.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(controller) {
        controller.refresh()
    }
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                controller.refresh()
                currentDate = sourceController.today
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(controller) {
        while (true) {
            val zone = ZoneId.of(controller.timezone)
            val now = ZonedDateTime.now(zone)
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
            delay(Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1_000L))
            controller.refresh()
            currentDate = sourceController.today
        }
    }
    LaunchedEffect(controller) {
        val zone = ZoneId.of(controller.timezone)
        while (true) {
            val now = ZonedDateTime.now(zone)
            currentMinute = now.hour * 60 + now.minute
            delay(30_000L)
        }
    }

    val coachReady = isCoachRequestReady(atlasController)
    val nextActivity = plan?.let { PlanUiLogic.nextActivity(it.activities, currentMinute) }
    val activitiesById = plan?.activities?.associateBy(PlanActivity::activityId).orEmpty()
    val editing = editingId?.let(activitiesById::get)
    val moving = movingId?.let(activitiesById::get)
    val changingStatus = changingStatusId?.let(activitiesById::get)
    val removing = removingId?.let(activitiesById::get)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AtlasSpacing.page),
        verticalArrangement = Arrangement.spacedBy(AtlasSpacing.medium),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !selectedTomorrow,
                onClick = { selectedTomorrow = false },
                label = { Text("Aujourd’hui") },
                modifier = Modifier.testTag("plan_day_today"),
            )
            FilterChip(
                selected = selectedTomorrow,
                onClick = { selectedTomorrow = true },
                label = { Text("Demain") },
                modifier = Modifier.testTag("plan_day_tomorrow"),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (selectedTomorrow) "Demain" else "Aujourd’hui", style = MaterialTheme.typography.headlineSmall)
            Text(PlanUiLogic.readableDate(controller.today), style = MaterialTheme.typography.titleSmall)
            Text(
                plan?.let { PlanUiLogic.progressLabel(it.activities) }
                    ?: PlanUiLogic.emptyStateLabel(hasDocument = false, activityCount = 0).orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        nextActivity?.let { PlanNextActivityCard(it) }

        controller.corruption?.let { reason ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(AtlasSpacing.medium), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AtlasStatus("Programme illisible", AtlasStatusKind.UNAVAILABLE)
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
                    AtlasSecondaryButton("Relire le stockage local", onClick = { controller.refresh() })
                }
            }
        }

        if (plan == null) {
            if (controller.corruption == null) Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(AtlasSpacing.medium), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (selectedTomorrow) "Préparer demain" else "Préparer la journée", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Le programme est stocké uniquement sur ce téléphone. Il reste lisible hors ligne et après redémarrage.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    AtlasPrimaryButton(
                        if (selectedTomorrow) "Créer le programme de demain" else "Créer le programme du jour",
                        onClick = { controller.createTodayPlan() },
                    )
                }
            }
        } else {
            if (coachReady) {
                AtlasPrimaryButton(
                    label = "Demander au coach",
                    onClick = {
                        configurationMessage = null
                        showCoachRequest = true
                    },
                    enabled = !coachBusy,
                )
            }
            AtlasSecondaryButton("Ajouter une activité", onClick = { showAdd = true })
            if (!coachReady) {
                AtlasSecondaryButton(
                    "Configurer le coach",
                    onClick = { optionsExpanded = true },
                )
            }
            AtlasSection("Activités") {
                if (PlanUiLogic.emptyStateLabel(hasDocument = true, activityCount = plan.activities.size) != null) {
                    Text(
                        "Aucune activité planifiée. Un jour libre est un programme valide.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    PlanActivityList(
                        activities = plan.activities,
                        nowMinute = currentMinute,
                        onMove = { movingId = it.activityId },
                        onEdit = { editingId = it.activityId },
                        onStatus = { changingStatusId = it.activityId },
                        onRemove = { removingId = it.activityId },
                    )
                }
            }
            TextButton(
                onClick = { optionsExpanded = !optionsExpanded },
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) { Text("Options du planning") }
            if (optionsExpanded) {
                PlanAgentSection(controller, atlasController, configurationMessage)
                if (plan.version >= 2) {
                    AtlasSection("Calendrier") {
                        val effectController = calendarEffectController
                        if (effectController == null) {
                            Text("Diagnostic calendrier en cours de chargement…", style = MaterialTheme.typography.bodySmall)
                        } else {
                            AtlasSecondaryButton(
                                label = if (calendarSyncBusy) "Synchronisation…" else "Synchroniser avec le calendrier",
                                enabled = !calendarSyncBusy,
                                onClick = {
                                    calendarSyncBusy = true
                                    scope.launch {
                                        val preview = withContext(Dispatchers.IO) {
                                            effectController.syncAppliedPlan(plan)
                                        }
                                        calendarSyncBusy = false
                                        if (!preview.ready) {
                                            calendarSyncMessage = preview.issue?.userLabel()
                                            calendarDiverged = preview.issue == CalendarEffectIssue.DIVERGENCE_REQUIRES_REVIEW
                                        } else if (preview.effects.isEmpty()) {
                                            calendarSyncMessage = "Le calendrier correspond déjà à la version ${plan.version}."
                                            calendarDiverged = false
                                        } else {
                                            calendarPlanAtDialogOpen = plan
                                            calendarPreview = preview
                                            calendarSyncMessage = null
                                        }
                                    }
                                },
                            )
                            calendarSyncMessage?.let { message ->
                                Text(message, style = MaterialTheme.typography.bodySmall)
                            }
                            if (calendarDiverged) {
                                AtlasStatus("Divergence calendrier à vérifier", AtlasStatusKind.UNAVAILABLE)
                                TextButton(
                                    onClick = { atlasController?.navigationState?.navigate(AtlasScreenId.CALENDAR_DIAGNOSTIC) },
                                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                                ) { Text("Ouvrir le diagnostic calendrier") }
                            }
                        }
                    }
                }
                TextButton(
                    onClick = { showDetails = true },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) { Text("Détails") }
                Text(
                    "Les modifications du programme restent locales. Le calendrier ne change qu’après une confirmation groupée.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

    if (showDetails && plan != null) {
        PlanDetailsDialog(
            date = plan.localDate,
            version = plan.version,
            timezone = plan.timezone,
            onDismiss = { showDetails = false },
        )
    }

    if (showCoachRequest && atlasController != null) {
        AlertDialog(
            onDismissRequest = { if (!coachBusy) showCoachRequest = false },
            title = { Text("Demander une révision au coach") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Décrivez la journée souhaitée. La proposition restera un aperçu jusqu'à votre confirmation.")
                    OutlinedTextField(
                        value = agentMessage,
                        onValueChange = { if (it.length <= 2_000) agentMessage = it },
                        label = { Text("Message") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !coachBusy && agentMessage.isNotBlank(),
                    onClick = {
                        val client = atlasController.planRevisionClient()
                        val mandate = atlasController.planRevisionMandate
                        if (client == null || mandate == null) {
                            configurationMessage = "Configurez le backend, le jeton et le mandat du Coach avant de demander une révision."
                            showCoachRequest = false
                            return@TextButton
                        }
                        showCoachRequest = false
                        coachBusy = true
                        scope.launch {
                            try {
                                controller.proposeAgentRevisionSuspending(
                                    client = client,
                                    conversationId = null,
                                    message = agentMessage,
                                    mandate = mandate,
                                    planContextConsented = true,
                                    planRevisionCapabilityEnabled = true,
                                )
                            } finally {
                                coachBusy = false
                            }
                        }
                    },
                ) { Text(if (coachBusy) "Envoi…" else "Envoyer") }
            },
            dismissButton = {
                TextButton(enabled = !coachBusy, onClick = { showCoachRequest = false }) { Text("Annuler") }
            },
        )
    }

    calendarPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = {
                calendarPreview = null
                calendarPlanAtDialogOpen = null
            },
            title = { Text("Confirmer la synchronisation calendrier") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Programme v${preview.planVersion} · ${preview.effects.size} effet(s)")
                    preview.effects.forEach { effect ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${effect.operationLabel()} : ${effect.title}", style = MaterialTheme.typography.titleSmall)
                            Text(effect.localTimeLabel(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(
                        "Annuler laisse le calendrier inchangé. Si le programme change, cette confirmation expirera.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !calendarSyncBusy,
                    onClick = {
                        val confirmedPreview = preview
                        val planAtOpen = calendarPlanAtDialogOpen
                        val currentPlan = controller.plan
                        calendarPreview = null
                        calendarPlanAtDialogOpen = null
                        val effectController = calendarEffectController
                        if (planAtOpen == null || currentPlan == null || effectController == null) {
                            calendarSyncMessage = "Le programme a changé. Rouvrez la confirmation."
                            return@TextButton
                        }
                        calendarSyncBusy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                effectController.syncAppliedPlan(planAtOpen, confirmedPreview, currentPlan)
                            }
                            val verification = withContext(Dispatchers.IO) {
                                effectController.verifyPlan(currentPlan)
                            }
                            calendarSyncBusy = false
                            calendarSyncMessage = result.issue?.userLabel() ?: result.status.userLabel()
                            calendarDiverged = result.status == CalendarSyncStatus.DIVERGED ||
                                verification.any { it.state in setOf(CalendarPlanVerificationState.DIVERGED, CalendarPlanVerificationState.ORPHANED) }
                        }
                    },
                ) { Text(if (calendarSyncBusy) "Traitement…" else "Confirmer") }
            },
            dismissButton = {
                TextButton(
                    enabled = !calendarSyncBusy,
                    onClick = {
                        calendarPreview = null
                        calendarPlanAtDialogOpen = null
                        calendarSyncMessage = "Synchronisation annulée. Aucun effet effectué."
                    },
                ) { Text("Annuler") }
            },
        )
    }

    editing?.let { activity ->
        ActivityEditorDialog(
            title = "Modifier l'activité",
            initial = activity,
            withTime = false,
            onDismiss = { editingId = null },
            onConfirm = { draft ->
                val edited = controller.editActivity(
                    activityId = activity.activityId,
                    title = draft.title,
                    kind = draft.kind,
                    priority = draft.priority,
                    notes = draft.notes,
                )
                if (edited) editingId = null
            },
        )
    }

    moving?.let { activity ->
        MoveActivityDialog(
            activity = activity,
            onDismiss = { movingId = null },
            onConfirm = { minute ->
                if (controller.moveActivity(activity.activityId, minute)) movingId = null
            },
        )
    }

    changingStatus?.let { activity ->
        StatusDialog(
            activity = activity,
            allowed = controller.allowedStatuses(activity),
            onDismiss = { changingStatusId = null },
            onSelect = { status ->
                if (controller.setStatus(activity.activityId, status)) changingStatusId = null
            },
        )
    }

    removing?.let { activity ->
        PlanRemoveConfirmationDialog(
            activity = activity,
            onDismiss = { removingId = null },
            onConfirm = {
                if (controller.removeActivity(activity.activityId)) removingId = null
            },
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
private fun PlanAgentSection(
    controller: PlanController,
    atlasController: AtlasController?,
    configurationMessage: String?,
) {
    if (atlasController == null) return
    var showConsent by rememberSaveable { mutableStateOf(false) }
    val enabled = atlasController.capabilities[AtlasController.CAP_PLAN_REVISION] == true
    val configured = atlasController.syncTokenConfigured && atlasController.syncBaseUrl.isNotBlank()
    val agentEnabled = atlasController.capabilities[AtlasController.CAP_AGENT] == true
    val hasMandate = atlasController.planRevisionMandate.let { mandate ->
        !mandate.revoked && !mandate.suspended && System.currentTimeMillis() < mandate.expiresAtUtcMillis
    }
    val hasClient = atlasController.planRevisionClient() != null

    AtlasSection("Coach") {
        if (!enabled) {
            Text(
                "Les révisions par le coach sont désactivées. Le programme reste entièrement local.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AtlasSecondaryButton("Activer les révisions par le coach", onClick = { showConsent = true })
        } else {
            Text(
                "Le coach peut proposer une révision du programme. Rien n'est appliqué sans votre confirmation groupée.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            configurationMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!configured) {
                Text(
                    "Configurez l'URL et le jeton du backend dans Plus > Partage backend.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (!agentEnabled || !hasMandate || !hasClient) {
                Text(
                    "Activez le Coach et vérifiez son mandat et sa connexion avant d'envoyer une demande.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            AtlasSecondaryButton(
                "Désactiver les révisions par le coach",
                onClick = {
                    atlasController.setCapability(AtlasController.CAP_PLAN_REVISION, false)
                    if (controller.preview != null) controller.cancelPreview()
                },
            )
        }
    }

    if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text("Envoyer le programme au coach local ?") },
            text = {
                Text(
                    "Le programme du jour (titre, horaires, durées, priorités, objectif) sera envoyé au backend local ATLAS puis au coach OpenClaw. Aucun calendrier, aucune note libre et aucun identifiant wger ne sont envoyés. Vous pouvez désactiver cette fonction à tout moment.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    atlasController.setCapability(AtlasController.CAP_PLAN_REVISION, true)
                    showConsent = false
                }) { Text("Activer") }
            },
            dismissButton = { TextButton(onClick = { showConsent = false }) { Text("Annuler") } },
        )
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
    var titleValue by rememberSaveable(initial?.activityId) { mutableStateOf(initial?.title.orEmpty()) }
    var kind by rememberSaveable(initial?.activityId) { mutableStateOf(initial?.kind ?: ActivityKind.FOCUS) }
    var priority by rememberSaveable(initial?.activityId) { mutableStateOf(initial?.priority ?: Priority.IMPORTANT) }
    var startText by rememberSaveable(initial?.activityId) {
        mutableStateOf(initial?.scheduledLocal?.startMinute?.let(PlanController::timeLabel) ?: "09:00")
    }
    var durationText by rememberSaveable(initial?.activityId) {
        mutableStateOf((initial?.scheduledLocal?.durationMinutes ?: 50).toString())
    }
    var notes by rememberSaveable(initial?.activityId) { mutableStateOf(initial?.notes.orEmpty()) }
    var error by rememberSaveable(initial?.activityId) { mutableStateOf<String?>(null) }

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
    var startText by rememberSaveable(activity.activityId) {
        mutableStateOf(PlanController.timeLabel(activity.scheduledLocal.startMinute))
    }
    var error by rememberSaveable(activity.activityId) { mutableStateOf<String?>(null) }
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

private fun isCoachRequestReady(controller: AtlasController?): Boolean =
    controller != null &&
        controller.capabilities[AtlasController.CAP_PLAN_REVISION] == true &&
        controller.capabilities[AtlasController.CAP_AGENT] == true &&
        controller.syncTokenConfigured &&
        controller.syncBaseUrl.isNotBlank() &&
        !controller.planRevisionMandate.revoked &&
        !controller.planRevisionMandate.suspended &&
        System.currentTimeMillis() < controller.planRevisionMandate.expiresAtUtcMillis &&
        controller.planRevisionClient() != null

private fun validWindow(startMinute: Int, durationMinutes: Int): Boolean =
    startMinute in 0..1439 && durationMinutes in 1..1440 &&
        startMinute + durationMinutes <= PlanController.MINUTES_PER_DAY
