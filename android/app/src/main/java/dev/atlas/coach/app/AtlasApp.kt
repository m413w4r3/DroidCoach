package dev.atlas.coach.app

import android.Manifest
import android.content.Context
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import dev.atlas.coach.focus.FocusStatus
import dev.atlas.coach.location.MobilitySessionStatusStore
import dev.atlas.coach.habits.Habit
import dev.atlas.coach.app.ui.AtlasRoutes
import dev.atlas.coach.app.ui.AtlasScreenFrame
import kotlinx.coroutines.delay

@Composable
fun AtlasApp(
    controller: AtlasController,
    planController: PlanController,
    context: Context,
    requestHomeRole: () -> Unit,
    openSystemHome: () -> Unit,
) {
    val revision = controller.revision
    var showDataExport by remember { mutableStateOf(false) }
    var showHabitExport by remember { mutableStateOf(false) }
    var showLinkChoices by remember { mutableStateOf(false) }
    var selectedHabit by remember { mutableStateOf<Habit?>(null) }
    var dismissedLatestMessage by remember(controller) { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val mobilityStatus by MobilitySessionStatusStore.status.collectAsState()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        controller.onNotificationPermissionResult(granted)
    }
    val requestNotifications = {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            controller.reportMessage("Les notifications Android sont autorisées. Vérifiez aussi les canaux dans les réglages système.")
        }
    }
    var activityStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            activityStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val shouldTickFocus = activityStarted &&
        controller.navigationState.current == AtlasScreenId.FOCUS &&
        controller.capabilities[AtlasController.CAP_FOCUS] == true &&
        controller.focusSession?.status == FocusStatus.RUNNING
    LaunchedEffect(shouldTickFocus) {
        while (shouldTickFocus) {
            delay(1_000)
            controller.tickFocus()
        }
    }

    LaunchedEffect(mobilityStatus.lastCompletion) {
        controller.onMobilitySessionCompletion(mobilityStatus.lastCompletion)
    }

    LaunchedEffect(controller.navigationState.current) {
        if (controller.navigationState.current == AtlasScreenId.SPORT) {
            controller.refreshSportPlan()
        }
    }

    LaunchedEffect(activityStarted, controller.capabilities[AtlasController.CAP_APP_TRACKING]) {
        if (!activityStarted) return@LaunchedEffect
        controller.onHostResumed()
        while (activityStarted && controller.capabilities[AtlasController.CAP_APP_TRACKING] == true) {
            controller.pollVisibleUsageObservation()
            delay(dev.atlas.coach.usage.UsageRetention.VISIBLE_POLL_INTERVAL_MILLIS)
        }
    }

    LaunchedEffect(controller.capabilities[AtlasController.CAP_HABITS]) {
        if (controller.capabilities[AtlasController.CAP_HABITS] != true) {
            showLinkChoices = false
            selectedHabit = null
        }
    }

    BackHandler(enabled = controller.navigationState.current != AtlasScreenId.HOME && controller.navigationState.canGoBack) {
        controller.navigationState.back()
    }

    val activeDestination = when (controller.navigationState.current) {
        AtlasScreenId.HOME -> AtlasRoutes.HOME
        AtlasScreenId.PLAN -> AtlasRoutes.PLAN
        AtlasScreenId.COACH -> AtlasRoutes.COACH
        AtlasScreenId.FOCUS, AtlasScreenId.FOCUS_STATS -> AtlasRoutes.FOCUS
        AtlasScreenId.HABITS -> AtlasRoutes.MORE
        else -> AtlasRoutes.MORE
    }
    val screenTitle = when (controller.navigationState.current) {
        AtlasScreenId.HOME -> "Aujourd'hui"
        AtlasScreenId.PLAN -> "Planning"
        AtlasScreenId.COACH -> "Coach"
        AtlasScreenId.COACH_CONTEXT -> "Contexte du coach"
        AtlasScreenId.FOCUS -> "Focus"
        AtlasScreenId.HABITS -> "Habitudes"
        AtlasScreenId.MORE -> "Plus"
        AtlasScreenId.SPORT -> "Sport"
        AtlasScreenId.AGENT_SETTINGS -> "Mandat du Coach"
        AtlasScreenId.AGENT_SHARING -> "Données partagées"
        AtlasScreenId.AGENT_EFFECTS -> "Journal des effets"
        AtlasScreenId.DIAGNOSTICS -> "Diagnostic avancé"
        AtlasScreenId.SYNC_SETTINGS -> "Partage backend"
        AtlasScreenId.RELAY_SETTINGS -> "Connexion relayée"
        AtlasScreenId.MEMORY -> "Mémoire locale"
        AtlasScreenId.GLOBAL_USAGE -> "Usage global"
        AtlasScreenId.CALENDAR_DIAGNOSTIC -> "Diagnostic calendrier"
        else -> controller.navigationState.current
    }
    AtlasScreenFrame(
        title = screenTitle,
        activeDestination = activeDestination,
        canGoBack = controller.navigationState.current != AtlasScreenId.HOME && controller.navigationState.canGoBack,
        emergencyPaused = controller.isEmergencyPaused,
        onBack = { controller.navigationState.back() },
        onEmergencyPause = { if (controller.isEmergencyPaused) controller.endEmergencyPause() else controller.setEmergencyPause() },
        onNavigate = { route ->
            val destination = when (route) {
                AtlasRoutes.HOME -> AtlasScreenId.HOME
                AtlasRoutes.PLAN -> AtlasScreenId.PLAN
                AtlasRoutes.COACH -> AtlasScreenId.COACH
                AtlasRoutes.FOCUS -> AtlasScreenId.FOCUS
                AtlasRoutes.MORE -> AtlasScreenId.MORE
                else -> AtlasScreenId.MORE
            }
            controller.navigationState.selectTab(destination)
        },
    ) {
        when (controller.navigationState.current) {
            AtlasScreenId.HOME -> HomeScreen(controller, revision)
            AtlasScreenId.PLAN -> PlanScreen(planController, controller)
            AtlasScreenId.SPORT -> SportScreen(
                state = controller.sportPlanState,
                demoMode = controller.sportDemoMode,
                busy = controller.sportBusy,
                message = controller.sportMessage,
                onRefresh = controller::refreshSportPlan,
                onToggleDemoMode = { controller.selectSportDemoMode(!controller.sportDemoMode) },
            )
            AtlasScreenId.FOCUS -> FocusScreen(controller, revision, onChooseHabit = { showLinkChoices = true })
            AtlasScreenId.HABITS -> HabitsScreen(controller, revision, onExport = { showHabitExport = true })
            AtlasScreenId.LIBRARY -> LibraryScreen(controller, revision, context)
            AtlasScreenId.COACH -> CoachScreen(
                controller,
                revision,
                onOpenContext = { controller.navigationState.navigate(AtlasScreenId.COACH_CONTEXT) },
            )
            AtlasScreenId.COACH_CONTEXT -> CoachContextScreen(controller, revision)
            AtlasScreenId.AGENT_SETTINGS -> AgentSettingsScreen(controller)
            AtlasScreenId.AGENT_SHARING -> AgentSharingScreen(controller)
            AtlasScreenId.AGENT_EFFECTS -> AgentEffectsScreen(controller)
            AtlasScreenId.MORE -> MoreScreen(controller)
            AtlasScreenId.SETTINGS -> SettingsScreen(
                controller = controller,
                requestHomeRole = requestHomeRole,
                openSystemHome = openSystemHome,
                onExport = { showDataExport = true },
                requestNotifications = requestNotifications,
            )
            AtlasScreenId.SIMULATOR -> SimulatorScreen(controller, revision)
            AtlasScreenId.DIAGNOSTICS -> DiagnosticsScreen(controller)
            AtlasScreenId.SYNC_SETTINGS -> SyncSettingsScreen(controller, revision)
            AtlasScreenId.RELAY_SETTINGS -> RelaySettingsScreen(controller.relaySettings)
            AtlasScreenId.MEMORY -> MemoryScreen(controller.memoryController)
            AtlasScreenId.GLOBAL_USAGE -> GlobalUsageRoute(
                controller = controller.globalUsageController,
                openUsageAccessSettings = {
                    runCatching { context.startActivity(controller.usageAccessSettingsIntent()) }
                },
            )
            AtlasScreenId.CALENDAR_DIAGNOSTIC -> CalendarPermissionScreen()
            AtlasScreenId.OBSERVATION -> ObservationScreen(controller, context, requestNotifications)
            AtlasScreenId.FOCUS_STATS -> FocusStatsScreen(controller)
            AtlasScreenId.ROUTINES -> FocusRoutinesScreen(controller)
            AtlasScreenId.ABOUT -> AboutLicensesScreen(context)
            else -> MoreScreen(controller)
        }
        controller.latestMessage?.takeIf { it != dismissedLatestMessage }?.let { message ->
            NoticeCard(message, onDismiss = { dismissedLatestMessage = message })
        }
    }

    if (showLinkChoices && controller.capabilities[AtlasController.CAP_HABITS] == true) {
        AlertDialog(
            onDismissRequest = { showLinkChoices = false },
            title = { Text("Choisir une habitude") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Aucune habitude ne sera cochée sans votre confirmation.")
                    controller.activeHabits.forEach { habit ->
                        TextButton(onClick = { selectedHabit = habit; showLinkChoices = false }) { Text(habit.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLinkChoices = false }) { Text("Annuler") } },
        )
    }
    selectedHabit?.takeIf { controller.capabilities[AtlasController.CAP_HABITS] == true }?.let { habit ->
        AlertDialog(
            onDismissRequest = { selectedHabit = null },
            title = { Text("Confirmer le lien") },
            text = { Text("Cocher « ${habit.name} » aujourd'hui pour cette session terminée ?") },
            confirmButton = {
                TextButton(onClick = { controller.confirmHabitLink(habit.id); selectedHabit = null }) { Text("Confirmer") }
            },
            dismissButton = { TextButton(onClick = { selectedHabit = null }) { Text("Annuler") } },
        )
    }
    if (showDataExport) {
        ExportDialog(title = "Export local des données", text = controller.exportAllData(), onDismiss = { showDataExport = false })
    }
    if (showHabitExport) {
        ExportDialog(title = "Export des habitudes (CSV)", text = controller.exportHabits(), onDismiss = { showHabitExport = false })
    }
}
