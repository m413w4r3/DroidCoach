package dev.atlas.coach.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material3.Switch
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
import dev.atlas.coach.plan.A1ActivityClass
import dev.atlas.coach.plan.A1ActivityPolicy
import dev.atlas.coach.plan.A1BoundedRevisionPolicy
import dev.atlas.coach.plan.A1ExternalLinkState
import dev.atlas.coach.plan.A1MoveDiff
import dev.atlas.coach.plan.PlanActivity
import dev.atlas.coach.plan.PlanStatusEvent
import dev.atlas.coach.plan.Priority
import dev.atlas.coach.sync.relay.RelayTransportMode
import dev.atlas.coach.sync.AndroidSyncStorage
import dev.atlas.coach.sync.v4.CoreV4Category
import dev.atlas.coach.sync.v4.CoreV4ConsentState
import dev.atlas.coach.sync.v4.CoreV4SyncScheduler
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4ConfirmationMode
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4EntryView
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4ProposalState
import dev.atlas.coach.focus.android.AndroidFocusNotifications
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Local day-plan surface. Plan revisions received through WorkManager stay in
 * the inbox until a proposal-specific review and confirmation succeeds locally.
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
    val pendingNotificationAction = atlasController?.pendingPlanActivityNotificationAction
    LaunchedEffect(pendingNotificationAction?.target?.operationKey) {
        val target = pendingNotificationAction?.target ?: return@LaunchedEffect
        val today = runCatching { LocalDate.parse(sourceController.today) }.getOrNull() ?: return@LaunchedEffect
        selectedTomorrow = target.localDate == today.plusDays(1).toString()
    }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var emptyDetailsExpanded by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var showCoachRequest by rememberSaveable { mutableStateOf(false) }
    var reviewingInboxProposalId by rememberSaveable { mutableStateOf<String?>(null) }
    var agentMessage by rememberSaveable { mutableStateOf("Propose une organisation de ma journée.") }
    var configurationMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var coachBusy by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var movingId by rememberSaveable { mutableStateOf<String?>(null) }
    var removingId by rememberSaveable { mutableStateOf<String?>(null) }
    var reportingId by rememberSaveable { mutableStateOf<String?>(null) }
    var reportingPlanVersion by rememberSaveable { mutableIntStateOf(-1) }
    var contextualCoachActivityId by rememberSaveable { mutableStateOf<String?>(null) }
    var contextualCoachPlanVersion by rememberSaveable { mutableIntStateOf(0) }
    var contextualCoachActivityTitle by rememberSaveable { mutableStateOf("") }
    var addingNoteId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingTerminalId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingTerminalStatusName by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingTerminalPlanVersion by rememberSaveable { mutableIntStateOf(-1) }
    var notificationTargetActivityId by rememberSaveable { mutableStateOf<String?>(null) }
    var notificationActionMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var calendarPreview by remember { mutableStateOf<CalendarSyncPreview?>(null) }
    var calendarPlanAtDialogOpen by remember { mutableStateOf<dev.atlas.coach.plan.DayPlan?>(null) }
    var calendarSyncBusy by remember { mutableStateOf(false) }
    var calendarSyncMessage by remember { mutableStateOf<String?>(null) }
    var calendarDiverged by remember { mutableStateOf(false) }
    val appContext = LocalContext.current.applicationContext
    val uiContext = LocalContext.current
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
    LaunchedEffect(
        atlasController?.capabilities?.get(AtlasController.CAP_PLAN_ACTIVITY_NOTIFICATIONS),
        atlasController?.isEmergencyPaused,
        sourceController.revision,
        controller.revision,
        plan?.version,
    ) {
        val coachController = atlasController ?: return@LaunchedEffect
        val today = runCatching { LocalDate.parse(sourceController.today) }.getOrNull() ?: return@LaunchedEffect
        val plans = withContext(Dispatchers.IO) {
            listOf(today, today.plusDays(1)).mapNotNull(sourceController::planForDate)
        }
        val notifications = AndroidFocusNotifications(appContext)
        PlanActivityNotificationRuntime.refresh(
            context = appContext,
            plans = plans,
            eligible = coachController.capabilities[AtlasController.CAP_PLAN_ACTIVITY_NOTIFICATIONS] == true &&
                !coachController.isEmergencyPaused && notifications.canPostPlanActivityReminder(),
        )
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
    // Tomorrow is entirely ahead of the current clock: do not mark its morning
    // activities as "past" just because today's clock is late.
    val displayMinute = if (selectedTomorrow) 0 else currentMinute
    val nextActivity = plan?.let { PlanUiLogic.nextActivity(it.activities, displayMinute) }
    val activitiesById = plan?.activities?.associateBy(PlanActivity::activityId).orEmpty()
    val statusEvents = remember(controller, controller.revision, plan?.version) {
        runCatching { controller.statusEvents() }.getOrDefault(emptyList<PlanStatusEvent>())
    }
    val editing = editingId?.let(activitiesById::get)
    val moving = movingId?.let(activitiesById::get)
    val removing = removingId?.let(activitiesById::get)
    val reporting = reportingId?.let(activitiesById::get)
    val addingNote = addingNoteId?.let(activitiesById::get)
    val confirmingTerminal = confirmingTerminalId?.let(activitiesById::get)
    val confirmingTerminalStatus = confirmingTerminalStatusName?.let { runCatching { ActivityStatus.valueOf(it) }.getOrNull() }
    val notificationTargetActivity = notificationTargetActivityId?.let(activitiesById::get)
    val planNotificationsEnabled = atlasController?.capabilities?.get(AtlasController.CAP_PLAN_ACTIVITY_NOTIFICATIONS) == true
    val notificationPermissionGranted = Build.VERSION.SDK_INT < 33 ||
        appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val planNotificationsAvailable = AndroidFocusNotifications(appContext).canPostPlanActivityReminder()

    LaunchedEffect(pendingNotificationAction, controller) {
        val actionRequest = pendingNotificationAction ?: return@LaunchedEffect
        if (controller.today != actionRequest.target.localDate) return@LaunchedEffect
        controller.refresh()
        val freshPlan = controller.plan
        val freshActivity = freshPlan?.activities?.firstOrNull { it.activityId == actionRequest.target.activityId }
        val targetMatches = atlasController?.capabilities?.get(AtlasController.CAP_PLAN_ACTIVITY_NOTIFICATIONS) == true &&
            freshPlan != null &&
            freshPlan.planId == actionRequest.target.planId &&
            freshPlan.version == actionRequest.target.planVersion &&
            freshPlan.state == dev.atlas.coach.plan.PlanState.APPROVED &&
            freshActivity != null &&
            freshActivity.planId == freshPlan.planId &&
            freshActivity.status == ActivityStatus.PLANNED &&
            freshActivity.scheduledLocal.localDate == actionRequest.target.localDate
        if (!targetMatches) {
            atlasController?.consumePlanActivityNotificationAction(actionRequest.target.operationKey)
            notificationActionMessage = AtlasController.PLAN_ACTIVITY_NOTIFICATION_UNAVAILABLE_MESSAGE
            return@LaunchedEffect
        }
        val consumed = atlasController?.consumePlanActivityNotificationAction(actionRequest.target.operationKey)
            ?: return@LaunchedEffect
        when (consumed.action) {
            PlanActivityNotificationAction.OPEN -> {
                notificationTargetActivityId = consumed.target.activityId
                notificationActionMessage = "Activité ouverte depuis son rappel."
            }
            PlanActivityNotificationAction.START -> {
                if (controller.setStatusAndConfirm(consumed.target.activityId, ActivityStatus.IN_PROGRESS)) {
                    notificationTargetActivityId = consumed.target.activityId
                    notificationActionMessage = "L’activité est marquée en cours dans le programme."
                } else {
                    notificationActionMessage = controller.message ?: "L’activité n’a pas pu être démarrée."
                }
            }
            PlanActivityNotificationAction.DONE -> {
                confirmingTerminalId = consumed.target.activityId
                confirmingTerminalStatusName = ActivityStatus.DONE.name
                confirmingTerminalPlanVersion = consumed.target.planVersion
                notificationTargetActivityId = consumed.target.activityId
            }
            PlanActivityNotificationAction.REPORT -> {
                reportingId = consumed.target.activityId
                reportingPlanVersion = consumed.target.planVersion
                notificationTargetActivityId = consumed.target.activityId
            }
        }
    }

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

        if (!selectedTomorrow) {
            A1BoundedSection(
                controller = controller,
                atlasController = atlasController,
                onPendingA1ActionCancelled = { reviewingInboxProposalId = null },
            )
        }

        PlanInboxSection(
            controller = controller,
            enabled = !selectedTomorrow,
            capabilityEnabled = atlasController?.capabilities?.get(AtlasController.CAP_PLAN_REVISION) == true,
            a1CapabilityEnabled = atlasController?.capabilities?.get(AtlasController.CAP_A1_BOUNDED) == true,
            a1CapabilityEnabledProvider = { atlasController?.capabilities?.get(AtlasController.CAP_A1_BOUNDED) == true },
            pendingAckCount = controller.inboxPendingAckCount,
            selectedProposalId = reviewingInboxProposalId,
            onSelectProposal = { reviewingInboxProposalId = it },
            onDismissReview = { reviewingInboxProposalId = null },
        )

        nextActivity?.let { PlanNextActivityCard(it) }

        AtlasSection("Rappels du programme") {
            Text(
                "Rappel local à l’heure prévue, selon WorkManager. Android peut le retarder, notamment avec Doze.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Activer les rappels", modifier = Modifier.weight(1f))
                Switch(
                    checked = planNotificationsEnabled,
                    enabled = atlasController != null,
                    onCheckedChange = { enabled ->
                        atlasController?.setCapability(AtlasController.CAP_PLAN_ACTIVITY_NOTIFICATIONS, enabled)
                    },
                    modifier = Modifier.testTag("plan_activity_notifications_toggle"),
                )
            }
            if (planNotificationsEnabled && !notificationPermissionGranted) {
                Text("Autorisez les notifications Android pour recevoir ces rappels.", style = MaterialTheme.typography.bodySmall)
                AtlasSecondaryButton("Réglages des notifications", onClick = {
                    runCatching {
                        uiContext.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    Unit
                })
            } else if (planNotificationsEnabled && !planNotificationsAvailable) {
                Text("Les notifications globales ou le canal « Rappels du programme » sont désactivés dans Android.", style = MaterialTheme.typography.bodySmall)
                AtlasSecondaryButton("Réglages des notifications", onClick = {
                    runCatching {
                        uiContext.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    Unit
                })
            }
        }
        notificationTargetActivity?.let { activity ->
            Card(modifier = Modifier.fillMaxWidth().testTag("plan_notification_activity_target")) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(AtlasSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AtlasStatus("Activité du rappel", AtlasStatusKind.ACTIVE)
                    Text(activity.title, style = MaterialTheme.typography.titleMedium)
                    Text("Statut · ${PlanController.statusLabel(activity.status)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        notificationActionMessage?.let { message ->
            AtlasStatus(message, AtlasStatusKind.ACTIVE, modifier = Modifier.testTag("plan_notification_action_message"))
        }

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
                    AtlasPrimaryButton(
                        if (selectedTomorrow) "Créer le programme de demain" else "Créer le programme du jour",
                        onClick = { controller.createTodayPlan() },
                    )
                    TextButton(
                        onClick = { emptyDetailsExpanded = !emptyDetailsExpanded },
                        modifier = Modifier.sizeIn(minHeight = 48.dp),
                    ) { Text(if (emptyDetailsExpanded) "Masquer les détails" else "Détails") }
                    if (emptyDetailsExpanded) {
                        Text(
                            "Le programme est stocké uniquement sur ce téléphone. Il reste lisible hors ligne et après redémarrage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                        nowMinute = displayMinute,
                        statusEvents = statusEvents,
                        onMove = { movingId = it.activityId },
                        onEdit = { editingId = it.activityId },
                        onRemove = { removingId = it.activityId },
                        onPrimary = { activity -> performPlanActivityPrimaryAction(activity, controller, atlasController) },
                        onReport = { reportingId = it.activityId; reportingPlanVersion = plan.version },
                        onMarkMissed = {
                            confirmingTerminalId = it.activityId
                            confirmingTerminalStatusName = ActivityStatus.MISSED.name
                            confirmingTerminalPlanVersion = plan.version
                        },
                        onCancel = {
                            confirmingTerminalId = it.activityId
                            confirmingTerminalStatusName = ActivityStatus.CANCELLED.name
                            confirmingTerminalPlanVersion = plan.version
                        },
                        onAddNote = { addingNoteId = it.activityId },
                        onAskCoach = if (atlasController?.isAgentEnabled == true) {
                            { activity ->
                                contextualCoachActivityId = activity.activityId
                                contextualCoachPlanVersion = plan.version
                                contextualCoachActivityTitle = activity.title
                            }
                        } else null,
                    )
                }
            }
            TextButton(
                onClick = { optionsExpanded = !optionsExpanded },
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) { Text(if (optionsExpanded) "Masquer les options" else "Options du planning") }
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

    if (confirmingTerminal != null && confirmingTerminalStatus != null) {
        AlertDialog(
            onDismissRequest = {
                confirmingTerminalId = null
                confirmingTerminalStatusName = null
                confirmingTerminalPlanVersion = -1
            },
            title = {
                Text(
                    when (confirmingTerminalStatus) {
                        ActivityStatus.CANCELLED -> "Annuler cette activité ?"
                        ActivityStatus.DONE -> "Confirmer la fin de l’activité ?"
                        else -> "Marquer comme pas fait ?"
                    },
                )
            },
            text = {
                val consequence = when (confirmingTerminalStatus) {
                    ActivityStatus.CANCELLED -> "L’activité sera annulée volontairement."
                    ActivityStatus.DONE -> "Vous confirmez explicitement qu’elle est terminée."
                    else -> "L’activité sera déclarée manquée après la fin de son créneau."
                }
                Text("« ${confirmingTerminal.title} » · $consequence Cette décision sera enregistrée au programme.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.refresh()
                        if (controller.plan?.version != confirmingTerminalPlanVersion) {
                            notificationActionMessage = "Le programme a changé. Rouvrez l’activité avant de confirmer."
                            confirmingTerminalId = null
                            confirmingTerminalStatusName = null
                            confirmingTerminalPlanVersion = -1
                            return@TextButton
                        }
                        if (controller.setStatusAndConfirm(confirmingTerminal.activityId, confirmingTerminalStatus)) {
                            confirmingTerminalId = null
                            confirmingTerminalStatusName = null
                            confirmingTerminalPlanVersion = -1
                        }
                    },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) {
                    Text(
                        when (confirmingTerminalStatus) {
                            ActivityStatus.CANCELLED -> "Annuler l’activité"
                            ActivityStatus.DONE -> "Confirmer terminé"
                            else -> "Pas fait"
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmingTerminalId = null
                        confirmingTerminalStatusName = null
                        confirmingTerminalPlanVersion = -1
                    },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) { Text("Garder") }
            },
        )
    }

    reporting?.let { activity ->
        plan?.let { currentPlan ->
            PlanReportBottomSheet(
                plan = currentPlan,
                activity = activity,
                onDismiss = { reportingId = null; reportingPlanVersion = -1 },
                onConfirm = { target ->
                    controller.refresh()
                    if (controller.plan?.version != reportingPlanVersion) {
                        controller.showTransientMessage("Le programme a changé. Vérifiez l’activité avant de confirmer le report.")
                        false
                    } else {
                        controller.reportActivity(
                            activityId = activity.activityId,
                            targetLocalDate = target.localDate,
                            targetStartMinute = target.startMinute,
                            targetDurationMinutes = target.durationMinutes,
                            reason = "Report choisi par l’utilisateur",
                            baseVersion = reportingPlanVersion,
                        )
                    }
                },
                failureMessage = { controller.message },
            )
        }
    }

    contextualCoachActivityId?.let { activityId ->
        atlasController?.let { coachController ->
            PlanActivityCoachDialog(
                activityId = activityId,
                planVersion = contextualCoachPlanVersion,
                activityTitle = contextualCoachActivityTitle,
                offline = coachController.relaySettings.mode == RelayTransportMode.OFFLINE,
                onDismiss = { contextualCoachActivityId = null },
                onSend = { note ->
                    coachController.sendPlanActivityCoachMessage(
                        activityId = activityId,
                        planVersion = contextualCoachPlanVersion,
                        activityTitle = contextualCoachActivityTitle,
                        note = note,
                    )
                },
                failureMessage = { coachController.latestMessage },
            )
        }
    }

    addingNote?.let { activity ->
        PlanNoteDialog(
            activity = activity,
            onDismiss = { addingNoteId = null },
            onConfirm = { note ->
                controller.editActivityAndConfirm(
                    activityId = activity.activityId,
                    title = activity.title,
                    kind = activity.kind,
                    priority = activity.priority,
                    notes = note,
                ).also { if (it) addingNoteId = null }
            },
            failureMessage = { controller.message },
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
    val appContext = LocalContext.current.applicationContext
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
                    CoreV4SyncScheduler.schedule(appContext)
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
                    CoreV4SyncScheduler.schedule(appContext)
                }) { Text("Activer") }
            },
            dismissButton = { TextButton(onClick = { showConsent = false }) { Text("Annuler") } },
        )
    }

}

@Composable
private fun PlanInboxSection(
    controller: PlanController,
    enabled: Boolean,
    capabilityEnabled: Boolean,
    a1CapabilityEnabled: Boolean,
    a1CapabilityEnabledProvider: () -> Boolean,
    pendingAckCount: Int,
    selectedProposalId: String?,
    onSelectProposal: (String?) -> Unit,
    onDismissReview: () -> Unit,
) {
    if (!enabled) return
    val context = LocalContext.current.applicationContext
    val entries = controller.inboxEntries.filter { entry ->
        val state = entry.proposal.state
        (state in setOf(
            InboxV4ProposalState.PENDING_ANDROID,
            InboxV4ProposalState.RECEIVED,
            InboxV4ProposalState.AWAITING_USER,
            InboxV4ProposalState.UNCERTAIN,
        ))
    }
    if (entries.isEmpty() && pendingAckCount == 0) return

    val consent = remember(controller, controller.revision) {
        runCatching { AndroidSyncStorage.v4(context).consent(CoreV4Category.DAY_PLAN).snapshot }.getOrNull()
    }
    val a1Consent = remember(controller, controller.revision) { runCatching(controller::a1Consent).getOrNull() }
    val reviewable = entries.filter { it.proposal.state in setOf(
        InboxV4ProposalState.PENDING_ANDROID,
        InboxV4ProposalState.RECEIVED,
        InboxV4ProposalState.AWAITING_USER,
    ) }
    val selected = selectedProposalId?.let { id -> reviewable.firstOrNull { it.proposal.proposalId == id } }

    AtlasSection("Propositions reçues") {
        if (pendingAckCount > 0) {
            Text(
                "$pendingAckCount ACK en attente de livraison. Ils restent conservés hors ligne. Ceux de l'ancienne version DAY_PLAN restent bloqués jusqu'à réconciliation et ne sont pas rejoués automatiquement.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("inbox_ack_pending"),
            )
        }
        entries.forEach { entry ->
            Card(modifier = Modifier.fillMaxWidth().testTag("inbox_proposal_${entry.proposal.proposalId}")) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(AtlasSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Proposition ${entry.proposal.proposalId.take(8)} · ${inboxModeLabel(entry.proposal.confirmationMode)}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(entry.proposal.justification, style = MaterialTheme.typography.bodyMedium)
                    when (entry.proposal.state) {
                        InboxV4ProposalState.UNCERTAIN -> AtlasStatus(
                            "Résultat incertain. Aucun effet ne sera rejoué sans preuve locale.",
                            AtlasStatusKind.UNAVAILABLE,
                        )
                        else -> {
                            Text(
                                "Programme v${entry.proposal.baseVersion} · expire ${entry.proposal.expiresAt}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            AtlasSecondaryButton("Examiner les changements", onClick = {
                                onSelectProposal(entry.proposal.proposalId)
                            })
                        }
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        val proposal = entry.proposal
        val isA1 = proposal.confirmationMode == InboxV4ConfirmationMode.A1_ANDROID_ELIGIBLE_TO_CHECK
        val a1Review = remember(controller, controller.revision, proposal.proposalId) {
            if (isA1) controller.inspectA1InboxProposal(proposal.proposalId) else null
        }
        val inboxConsentProvider = {
            val latestConsent = runCatching {
                AndroidSyncStorage.v4(context).consent(CoreV4Category.DAY_PLAN).snapshot
            }.getOrNull()
            (latestConsent?.state == CoreV4ConsentState.GRANTED) to (latestConsent?.version?.toLong() ?: 0L)
        }
        val canApply = if (isA1) {
            a1CapabilityEnabled && a1Consent?.enabled == true && a1Review?.valid == true
        } else {
            capabilityEnabled
        }
        AlertDialog(
            onDismissRequest = onDismissReview,
            title = { Text("Revoir la proposition ${proposal.proposalId.take(8)}") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(proposal.justification, style = MaterialTheme.typography.bodyMedium)
                    Text("Mode · ${inboxModeLabel(proposal.confirmationMode)}", style = MaterialTheme.typography.labelLarge)
                    when (proposal.confirmationMode) {
                        InboxV4ConfirmationMode.A0_USER_REVIEW -> Text("Chaque changement ci-dessous sera revu avant son application locale.")
                        InboxV4ConfirmationMode.A1_ANDROID_ELIGIBLE_TO_CHECK -> Text(
                            "A1 utilise le consentement local et la capability A1. Chaque proposition reste soumise à votre confirmation et à la validation des bornes locales.",
                        )
                        InboxV4ConfirmationMode.A2_USER_CONFIRMATION -> Text(
                            "Cette proposition A2 requiert votre confirmation spécifique avant toute application.",
                        )
                    }
                    Text(
                        "Programme ${proposal.planId.take(8)} · version de base ${proposal.baseVersion}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (isA1) {
                        A1InboxReviewDetails(controller.plan, a1Review)
                    } else {
                        proposal.changes.forEachIndexed { index, change ->
                            Text("${index + 1}. ${renderInboxChange(change, controller.plan)}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (isA1 && !a1CapabilityEnabled) {
                        Text("Activez la capability A1 dans le parcours A1 pour appliquer cette proposition.", color = MaterialTheme.colorScheme.error)
                    } else if (isA1 && a1Consent?.enabled != true) {
                        Text("Le consentement local A1 est absent ou révoqué.", color = MaterialTheme.colorScheme.error)
                    } else if (!isA1 && !capabilityEnabled) {
                        Text("Activez les révisions par le coach pour appliquer cette proposition.", color = MaterialTheme.colorScheme.error)
                    }
                    if (consent?.state != CoreV4ConsentState.GRANTED) {
                        Text(
                            if (isA1) "L'autorisation DAY_PLAN de l'inbox doit rester active pour recevoir et acquitter cette proposition; elle est distincte du consentement local A1."
                            else "Le consentement DAY_PLAN doit être actif pour appliquer cette proposition.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canApply && consent?.state == CoreV4ConsentState.GRANTED,
                    onClick = {
                        if (controller.applyInboxProposal(
                                proposalId = proposal.proposalId,
                                planRevisionCapabilityEnabled = capabilityEnabled,
                                dayPlanConsentGranted = consent?.state == CoreV4ConsentState.GRANTED,
                                dayPlanConsentVersion = consent?.version?.toLong() ?: 0L,
                                a1CapabilityEnabled = a1CapabilityEnabledProvider,
                                currentDayPlanConsent = inboxConsentProvider,
                                onA1Committed = { plan, receipt ->
                                    A1PlanNotificationPublisher.publish(context, plan, receipt)
                                },
                            )
                        ) onDismissReview()
                    },
                ) {
                    Text(if (isA1) "Confirmer et appliquer A1" else "Confirmer et appliquer")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val latestConsent = runCatching {
                            AndroidSyncStorage.v4(context).consent(CoreV4Category.DAY_PLAN).snapshot
                        }.getOrNull()
                        if (controller.rejectInboxProposal(proposal.proposalId, latestConsent?.version?.toLong() ?: 0L)) {
                            onDismissReview()
                        }
                    }) { Text("Refuser définitivement") }
                    TextButton(onClick = onDismissReview) { Text("Plus tard") }
                }
            },
        )
    }
}

@Composable
private fun A1BoundedSection(
    controller: PlanController,
    atlasController: AtlasController?,
    onPendingA1ActionCancelled: () -> Unit,
) {
    if (atlasController == null) return
    val context = LocalContext.current.applicationContext
    val plan = controller.plan
    val consent = remember(controller, controller.revision) { runCatching(controller::a1Consent).getOrNull() }
    val capabilityEnabled = atlasController.capabilities[AtlasController.CAP_A1_BOUNDED] == true
    var consentDialogOpen by rememberSaveable { mutableStateOf(false) }
    var consentDialogGrant by rememberSaveable { mutableStateOf(true) }
    var editingActivityId by rememberSaveable { mutableStateOf<String?>(null) }
    var draftActivityClass by rememberSaveable { mutableStateOf(A1ActivityClass.PROTECTED.name) }
    var draftActivityUnlocked by rememberSaveable { mutableStateOf(false) }
    var showUndoConfirmation by rememberSaveable { mutableStateOf(false) }
    val undoReceipt = remember(controller, controller.revision, plan?.version) {
        controller.activeA1UndoReceipt()
    }
    val editingActivity = editingActivityId?.let { id -> plan?.activities?.firstOrNull { it.activityId == id } }

    AtlasSection("A1 · déplacements bornés") {
        Text(
            "A1 est une autorisation locale distincte des révisions du Coach. Les propositions viennent de l'inbox; aucune n'est appliquée sans votre confirmation.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Capability A1", modifier = Modifier.weight(1f))
            Switch(
                checked = capabilityEnabled,
                onCheckedChange = { enabled ->
                    atlasController.setCapability(AtlasController.CAP_A1_BOUNDED, enabled)
                    onPendingA1ActionCancelled()
                },
                modifier = Modifier.testTag("a1_capability_toggle"),
            )
        }
        Text(
            if (consent?.enabled == true) "Consentement local actif · version ${consent.version}."
            else "Aucun consentement A1 actif. Les activités non classées restent protégées.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("a1_consent_state"),
        )
        if (consent?.enabled == true) {
            AtlasSecondaryButton("Révoquer le consentement A1", onClick = {
                consentDialogGrant = false
                consentDialogOpen = true
            })
        } else {
            AtlasSecondaryButton(
                "Donner le consentement A1",
                enabled = plan != null,
                onClick = {
                    consentDialogGrant = true
                    consentDialogOpen = true
                },
            )
        }
        if (plan == null) {
            Text("Créez le programme du jour pour enregistrer un consentement A1 versionné.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                "Classez chaque activité explicitement. Aucun titre ni texte d'activité n'est analysé pour lui attribuer une classe.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            plan.activities.forEach { activity ->
                val activityPolicy = remember(controller, controller.revision, activity.activityId) {
                    controller.a1ActivityPolicy(activity.activityId) ?: A1ActivityPolicy.legacyProtected(activity.activityId)
                }
                Card(modifier = Modifier.fillMaxWidth().testTag("a1_activity_${activity.activityId}")) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(AtlasSpacing.medium),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(activity.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Classe · ${a1ClassLabel(activityPolicy.activityClass)} · ${if (activityPolicy.unlocked) "déverrouillée" else "protégée"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        AtlasSecondaryButton("Régler le classement A1", onClick = {
                            draftActivityClass = activityPolicy.activityClass.name
                            draftActivityUnlocked = activityPolicy.unlocked
                            editingActivityId = activity.activityId
                        })
                    }
                }
            }
            undoReceipt?.let { receipt ->
                Card(modifier = Modifier.fillMaxWidth().testTag("a1_undo_available")) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(AtlasSpacing.medium),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AtlasStatus("Révision A1 enregistrée", AtlasStatusKind.ACTIVE)
                        Text(
                            "Version ${receipt.versionAfter} · annulation disponible pendant 15 minutes si le programme reste exactement à cette version. Le quota restera consommé.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        AtlasSecondaryButton("Examiner l'annulation", onClick = { showUndoConfirmation = true })
                    }
                }
            }
        }
    }

    if (consentDialogOpen) {
        AlertDialog(
            onDismissRequest = { consentDialogOpen = false },
            title = { Text(if (consentDialogGrant) "Consentement local A1" else "Révoquer le consentement A1 ?") },
            text = {
                Text(if (consentDialogGrant) {
                    "A1 pourra déplacer, uniquement après une confirmation explicite, jusqu'à 3 activités classées et déverrouillées, de 120 minutes maximum chacune, avec 2 applications par jour local. Les activités liées, obligatoires et protégées restent intactes. Toute application crée un reçu; l'annulation est possible pendant 15 minutes si la version du programme n'a pas changé. Ce consentement local est distinct du partage DAY_PLAN et du Coach."
                } else {
                    "La révocation incrémente la version du consentement et bloque les applications A1. Les propositions A1 en attente sont invalidées par un ACK terminal. Si cet ACK ne peut pas être enregistré, elles restent visibles et A1 ne pourra pas être réactivé avant leur clôture. Une modification déjà enregistrée reste visible; son annulation disponible peut encore être demandée dans les 15 minutes."
                })
            },
            confirmButton = {
                TextButton(onClick = {
                    val inboxConsentVersion = runCatching {
                        AndroidSyncStorage.v4(context).consent(CoreV4Category.DAY_PLAN).snapshot.version.toLong()
                    }.getOrDefault(0L)
                    if (controller.setA1Consent(consentDialogGrant, inboxConsentVersion)) onPendingA1ActionCancelled()
                    consentDialogOpen = false
                }) { Text(if (consentDialogGrant) "Accorder le consentement" else "Révoquer") }
            },
            dismissButton = {
                TextButton(onClick = { consentDialogOpen = false }) { Text("Annuler") }
            },
        )
    }

    editingActivity?.let { activity ->
        val selectedClass = runCatching { A1ActivityClass.valueOf(draftActivityClass) }
            .getOrDefault(A1ActivityClass.PROTECTED)
        AlertDialog(
            onDismissRequest = { editingActivityId = null },
            title = { Text("Classement A1 · ${activity.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Choisissez explicitement une classe. PROTECTED est le défaut; le titre ne décide jamais de ce choix.")
                    A1ActivityClass.values().forEach { activityClass ->
                        TextButton(onClick = {
                            draftActivityClass = activityClass.name
                            if (activityClass == A1ActivityClass.PROTECTED) draftActivityUnlocked = false
                        }) {
                            Text(if (activityClass == selectedClass) "● ${a1ClassLabel(activityClass)}" else "○ ${a1ClassLabel(activityClass)}")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Déverrouiller · confirme que l'activité est locale et sans lien externe",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Switch(
                            checked = draftActivityUnlocked && selectedClass != A1ActivityClass.PROTECTED,
                            enabled = selectedClass != A1ActivityClass.PROTECTED,
                            onCheckedChange = { draftActivityUnlocked = it },
                            modifier = Modifier.testTag("a1_activity_unlock_toggle"),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val activityClass = runCatching { A1ActivityClass.valueOf(draftActivityClass) }
                        .getOrDefault(A1ActivityClass.PROTECTED)
                    val unlocked = draftActivityUnlocked && activityClass != A1ActivityClass.PROTECTED
                    controller.setA1ActivityPolicy(
                        A1ActivityPolicy(
                            activityId = activity.activityId,
                            activityClass = activityClass,
                            unlocked = unlocked,
                            externalLinkState = if (unlocked) A1ExternalLinkState.NONE else A1ExternalLinkState.UNKNOWN,
                        ),
                    )
                    editingActivityId = null
                    onPendingA1ActionCancelled()
                }) { Text("Enregistrer le classement") }
            },
            dismissButton = { TextButton(onClick = { editingActivityId = null }) { Text("Annuler") } },
        )
    }

    undoReceipt?.takeIf { showUndoConfirmation }?.let { receipt ->
        AlertDialog(
            onDismissRequest = { showUndoConfirmation = false },
            title = { Text("Annuler la révision A1 ?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Cette opération crée une nouvelle version du programme. Elle est possible uniquement avant l'échéance et si la version courante correspond toujours au reçu.")
                    receipt.diff.forEach { move ->
                        val activity = plan?.activities?.firstOrNull { it.activityId == move.activityId }
                        Text(
                            "${activity?.title ?: "Activité ${move.activityId.take(8)}"} · " +
                                "${PlanController.timeLabel(move.to.startMinute)} → ${PlanController.timeLabel(move.from.startMinute)}",
                        )
                    }
                    Text("Le quota de ${receipt.quotaUsed}/${A1BoundedRevisionPolicy.MAX_APPLICATIONS_PER_LOCAL_DATE} application(s) reste consommé.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    controller.undoA1(receipt.proposalId)
                    showUndoConfirmation = false
                }) { Text("Confirmer l'annulation") }
            },
            dismissButton = {
                TextButton(onClick = { showUndoConfirmation = false }) { Text("Conserver la révision") }
            },
        )
    }
}

@Composable
private fun A1InboxReviewDetails(
    currentPlan: dev.atlas.coach.plan.DayPlan?,
    review: A1InboxReview?,
) {
    if (review == null) {
        Text("La validation locale A1 n'a pas pu être chargée. Aucun changement ne sera appliqué.", color = MaterialTheme.colorScheme.error)
        return
    }
    Text(
        "${if (review.valid) "Diff validé" else "Proposition A1 · diff non validé"} · " +
            "programme v${review.proposalBaseVersion} → v${review.currentPlanVersion ?: "?"}",
        style = MaterialTheme.typography.labelLarge,
    )
    review.diff.forEachIndexed { index, move ->
        val activity = currentPlan?.activities?.firstOrNull { it.activityId == move.activityId }
        Text(
            "${index + 1}. ${activity?.title ?: "Activité ${move.activityId.take(8)}"} · " +
                "${PlanController.timeLabel(move.from.startMinute)} → ${PlanController.timeLabel(move.to.startMinute)} " +
                "(${moveDistanceLabel(move)}) · ${move.from.durationMinutes} min",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Text(
        "Bornes · max. ${A1BoundedRevisionPolicy.MAX_MOVED_ACTIVITIES} activités, " +
            "±${A1BoundedRevisionPolicy.MAX_MOVE_MINUTES} min chacune; même date, durée et statut. " +
            "Quota local ${review.quotaLocalDate} · ${review.quotaUsed}/${review.quotaLimit} déjà utilisé(s).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    review.rejection?.let { rejection ->
        Text("Validation A1 refusée · ${a1RejectionLabel(rejection)}. Aucun effet ne sera exécuté.", color = MaterialTheme.colorScheme.error)
    }
}

private fun moveDistanceLabel(move: A1MoveDiff): String {
    val delta = move.to.startMinute - move.from.startMinute
    return if (delta > 0) "+$delta min" else "$delta min"
}

private fun a1ClassLabel(activityClass: A1ActivityClass): String = when (activityClass) {
    A1ActivityClass.READING -> "Lecture"
    A1ActivityClass.CHINESE -> "Chinois"
    A1ActivityClass.LEISURE -> "Loisir"
    A1ActivityClass.PERSONAL -> "Personnel"
    A1ActivityClass.PROTECTED -> "Protégée"
}

private fun a1RejectionLabel(rejection: dev.atlas.coach.plan.A1PolicyRejection): String = when (rejection) {
    dev.atlas.coach.plan.A1PolicyRejection.PLAN_NOT_FOUND -> "programme introuvable"
    dev.atlas.coach.plan.A1PolicyRejection.PLAN_NOT_MODIFIABLE -> "programme non modifiable"
    dev.atlas.coach.plan.A1PolicyRejection.PLAN_ID_MISMATCH -> "programme différent"
    dev.atlas.coach.plan.A1PolicyRejection.STALE_VERSION -> "version de base périmée"
    dev.atlas.coach.plan.A1PolicyRejection.CONSENT_DISABLED -> "consentement absent ou révoqué"
    dev.atlas.coach.plan.A1PolicyRejection.CONSENT_VERSION_MISMATCH -> "version de consentement différente"
    dev.atlas.coach.plan.A1PolicyRejection.PROPOSAL_NOT_YET_VALID -> "proposition pas encore valide"
    dev.atlas.coach.plan.A1PolicyRejection.PROPOSAL_EXPIRED -> "proposition expirée"
    dev.atlas.coach.plan.A1PolicyRejection.PROPOSAL_TTL_TOO_LONG -> "durée de validité hors borne"
    dev.atlas.coach.plan.A1PolicyRejection.EMPTY_CHANGESET -> "aucun déplacement"
    dev.atlas.coach.plan.A1PolicyRejection.ADD_OR_REMOVE_FORBIDDEN -> "ajout ou retrait interdit"
    dev.atlas.coach.plan.A1PolicyRejection.CONTENT_CHANGE_FORBIDDEN -> "changement de contenu interdit"
    dev.atlas.coach.plan.A1PolicyRejection.STATUS_CHANGE_FORBIDDEN -> "changement de statut interdit"
    dev.atlas.coach.plan.A1PolicyRejection.INVALID_DATE_OR_TIMEZONE -> "date ou fuseau différent"
    dev.atlas.coach.plan.A1PolicyRejection.ACTIVITY_NOT_FOUND -> "activité introuvable"
    dev.atlas.coach.plan.A1PolicyRejection.ACTIVITY_NOT_PLANNED -> "activité non planifiée"
    dev.atlas.coach.plan.A1PolicyRejection.REQUIRED_ACTIVITY -> "activité obligatoire protégée"
    dev.atlas.coach.plan.A1PolicyRejection.PROTECTED_ACTIVITY_KIND -> "type d'activité protégé"
    dev.atlas.coach.plan.A1PolicyRejection.TRAINING_LINK_FORBIDDEN -> "activité liée à un entraînement"
    dev.atlas.coach.plan.A1PolicyRejection.POLICY_UNKNOWN -> "classement inconnu"
    dev.atlas.coach.plan.A1PolicyRejection.ACTIVITY_PROTECTED -> "activité protégée"
    dev.atlas.coach.plan.A1PolicyRejection.ACTIVITY_LOCKED -> "activité encore verrouillée"
    dev.atlas.coach.plan.A1PolicyRejection.EXTERNAL_LINK_UNKNOWN -> "lien externe non vérifié"
    dev.atlas.coach.plan.A1PolicyRejection.EXTERNAL_LINK_FORBIDDEN -> "activité liée à un service externe"
    dev.atlas.coach.plan.A1PolicyRejection.MOVE_LIMIT_EXCEEDED -> "trop d'activités déplacées"
    dev.atlas.coach.plan.A1PolicyRejection.MOVE_DISTANCE_EXCEEDED -> "déplacement hors borne"
    dev.atlas.coach.plan.A1PolicyRejection.QUOTA_EXCEEDED -> "quota journalier atteint"
    dev.atlas.coach.plan.A1PolicyRejection.IDEMPOTENCY_CONFLICT -> "identifiant de proposition réutilisé"
    dev.atlas.coach.plan.A1PolicyRejection.REVISION_ID_CONFLICT -> "identifiant de révision réutilisé"
    dev.atlas.coach.plan.A1PolicyRejection.STORAGE_FAILURE -> "écriture durable impossible"
    dev.atlas.coach.plan.A1PolicyRejection.STALE_STATE -> "état local modifié"
    dev.atlas.coach.plan.A1PolicyRejection.INVALID_PROPOSAL -> "proposition invalide"
}

private fun inboxModeLabel(mode: InboxV4ConfirmationMode): String = when (mode) {
    InboxV4ConfirmationMode.A0_USER_REVIEW -> "A0 · revue utilisateur"
    InboxV4ConfirmationMode.A1_ANDROID_ELIGIBLE_TO_CHECK -> "A1 · règle locale bornée"
    InboxV4ConfirmationMode.A2_USER_CONFIRMATION -> "A2 · confirmation spécifique"
}

private fun renderInboxChange(change: Map<String, Any?>, currentPlan: dev.atlas.coach.plan.DayPlan?): String {
    val op = change["op"] as? String ?: "changement"
    return when (op) {
        "reschedule" -> {
            val activityId = change["activity_id"] as? String
            val activity = currentPlan?.activities?.firstOrNull { it.activityId == activityId }
            val target = (change["target_start_minute"] as? Number)?.toInt()
            val start = activity?.scheduledLocal?.startMinute?.let { PlanController.timeLabel(it) } ?: "?"
            val destination = target?.let { PlanController.timeLabel(it) } ?: "?"
            val duration = (change["duration_minutes"] as? Number)?.toInt()
                ?: activity?.scheduledLocal?.durationMinutes
            "déplacer « ${activity?.title ?: activityId?.take(8) ?: "activité"} » de $start à $destination · ${duration ?: "?"} min"
        }
        "create_activity" -> {
            val start = (change["start_minute"] as? Number)?.toInt()?.let { PlanController.timeLabel(it) } ?: "?"
            "ajouter « ${change["title"] ?: "activité"} » · ${change["kind"] ?: "type inconnu"} · à $start (${change["duration_minutes"] ?: "?"} min)"
        }
        "edit_activity" -> {
            val activityId = change["activity_id"] as? String
            val title = currentPlan?.activities?.firstOrNull { it.activityId == activityId }?.title
            val fields = change.filterKeys { it != "op" && it != "activity_id" }
            "modifier « ${title ?: activityId?.take(8) ?: "activité"} » · $fields"
        }
        else -> "$op · $change"
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
