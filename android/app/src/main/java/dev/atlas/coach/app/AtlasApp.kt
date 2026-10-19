package dev.atlas.coach.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.atlas.coach.coach.DirectiveChoice
import dev.atlas.coach.coach.displayLabel
import dev.atlas.coach.focus.FocusPreset
import dev.atlas.coach.focus.FocusPhase
import dev.atlas.coach.focus.FocusStatus
import dev.atlas.coach.focus.displayLabel as focusStatusLabel
import dev.atlas.coach.focus.displayLabel as focusPhaseLabel
import dev.atlas.coach.focus.displayLabel
import dev.atlas.coach.focus.toReefTimerState
import dev.atlas.coach.habits.Habit
import dev.atlas.coach.habits.HabitReminder
import dev.atlas.coach.habits.HabitSchedule
import dev.atlas.coach.habits.HabitScheduleKind
import dev.atlas.coach.content.ContentItem
import dev.atlas.coach.content.ContentKind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.delay

@Composable
fun AtlasApp(
    controller: AtlasController,
    context: Context,
    requestHomeRole: () -> Unit,
    openSystemHome: () -> Unit,
    homeLaunch: Boolean = false,
) {
    val revision = controller.revision
    var showDataExport by remember { mutableStateOf(false) }
    var showHabitExport by remember { mutableStateOf(false) }
    var showLinkChoices by remember { mutableStateOf(false) }
    var selectedHabit by remember { mutableStateOf<Habit?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
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
        controller.screen == AtlasController.SCREEN_FOCUS &&
        controller.capabilities[AtlasController.CAP_FOCUS] == true &&
        controller.focusSession?.status == FocusStatus.RUNNING
    LaunchedEffect(shouldTickFocus) {
        while (shouldTickFocus) {
            delay(1_000)
            controller.tickFocus()
        }
    }

    LaunchedEffect(activityStarted, controller.capabilities[AtlasController.CAP_APP_TRACKING]) {
        if (!activityStarted) return@LaunchedEffect
        controller.onHostResumed()
        while (activityStarted && controller.capabilities[AtlasController.CAP_APP_TRACKING] == true) {
            controller.pollUsageObservation()
            delay(dev.atlas.coach.usage.UsageRetention.VISIBLE_POLL_INTERVAL_MILLIS)
        }
    }

    LaunchedEffect(controller.capabilities[AtlasController.CAP_HABITS]) {
        if (controller.capabilities[AtlasController.CAP_HABITS] != true) {
            showLinkChoices = false
            selectedHabit = null
        }
    }

    LaunchedEffect(homeLaunch, controller.capabilities[AtlasController.CAP_LAUNCHER_HOME]) {
        if (homeLaunch && controller.capabilities[AtlasController.CAP_LAUNCHER_HOME] == true) {
            controller.navigate(AtlasController.SCREEN_LAUNCHER)
        } else if (controller.capabilities[AtlasController.CAP_LAUNCHER_HOME] != true && controller.screen == AtlasController.SCREEN_LAUNCHER) {
            controller.navigate(AtlasController.SCREEN_HOME)
        }
    }

    Surface(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 18.dp, end = 24.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(controller.screen.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
                if (controller.screen != AtlasController.SCREEN_HOME) {
                    TextButton(
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Retour à l'accueil ATLAS" },
                        onClick = { controller.navigate(AtlasController.SCREEN_HOME) },
                    ) { Text("Accueil") }
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp).padding(bottom = 84.dp),
            ) {
                when (controller.screen) {
                    AtlasController.SCREEN_HOME -> HomeScreen(controller, revision)
                    AtlasController.SCREEN_FOCUS -> FocusScreen(controller, revision, onChooseHabit = { showLinkChoices = true })
                    AtlasController.SCREEN_HABITS -> HabitsScreen(controller, revision, onExport = { showHabitExport = true })
                    AtlasController.SCREEN_LIBRARY -> LibraryScreen(controller, revision, context)
                    AtlasController.SCREEN_COACH -> CoachScreen(controller, revision)
                    AtlasController.SCREEN_SETTINGS -> SettingsScreen(
                        controller = controller,
                        revision = revision,
                        requestHomeRole = requestHomeRole,
                        openSystemHome = openSystemHome,
                        onExport = { showDataExport = true },
                        requestNotifications = requestNotifications,
                    )
                    AtlasController.SCREEN_SIMULATOR -> SimulatorScreen(controller, revision)
                    AtlasController.SCREEN_DIAGNOSTICS -> DiagnosticsScreen(controller)
                    AtlasController.SCREEN_OBSERVATION -> ObservationScreen(controller, context, requestNotifications)
                    AtlasController.SCREEN_LAUNCHER -> if (controller.capabilities[AtlasController.CAP_LAUNCHER_HOME] == true) {
                        LauncherHomeScreen(controller, context, openSystemHome)
                    } else {
                        HomeScreen(controller, revision)
                    }
                    AtlasController.SCREEN_FOCUS_STATS -> FocusStatsScreen(controller)
                    AtlasController.SCREEN_ROUTINES -> FocusRoutinesScreen(controller)
                    AtlasController.SCREEN_ABOUT -> AboutLicensesScreen(context)
                    else -> HomeScreen(controller, revision)
                }
                Spacer(Modifier.height(18.dp))
                controller.latestMessage?.let { NoticeCard(it) }
                Spacer(Modifier.height(20.dp))
            }
            Button(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).sizeIn(minHeight = 52.dp)
                    .semantics { contentDescription = if (controller.isEmergencyPaused) "Terminer la pause d'urgence" else "Pause d'urgence de 30 minutes" },
                onClick = { if (controller.isEmergencyPaused) controller.endEmergencyPause() else controller.setEmergencyPause() },
            ) {
                Text(if (controller.isEmergencyPaused) "Pause d'urgence active · terminer" else "Pause d'urgence 30 min")
            }
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

@Composable
private fun HomeScreen(controller: AtlasController, @Suppress("UNUSED_PARAMETER") revision: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(10.dp))
        Text("ATLAS", fontSize = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp, color = MaterialTheme.colorScheme.onBackground)
        Text("La prochaine étape", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Text(controller.directive.title, fontSize = 31.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Text(controller.directive.reason, style = MaterialTheme.typography.bodyLarge, lineHeight = 24.sp)
        Text("Proposition locale · disponible hors ligne", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Button(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp).semantics { contentDescription = "Commencer la directive avec un focus lié" },
            enabled = controller.capabilities[AtlasController.CAP_COACH] == true && controller.capabilities[AtlasController.CAP_FOCUS] == true && !controller.isCoachPaused,
            onClick = { controller.respond(DirectiveChoice.START) },
        ) { Text("Commencer") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp), enabled = controller.capabilities[AtlasController.CAP_COACH] == true && !controller.isCoachPaused, onClick = { controller.respond(DirectiveChoice.DISCUSS) }) { Text("Discuter") }
            OutlinedButton(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp), enabled = controller.capabilities[AtlasController.CAP_COACH] == true && !controller.isCoachPaused, onClick = { controller.respond(DirectiveChoice.ALTERNATIVE) }) { Text("Alternative") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp), enabled = controller.capabilities[AtlasController.CAP_COACH] == true && !controller.isCoachPaused, onClick = { controller.respond(DirectiveChoice.POSTPONE) }) { Text("Reporter") }
            OutlinedButton(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp), enabled = controller.capabilities[AtlasController.CAP_COACH] == true && !controller.isCoachPaused, onClick = { controller.respond(DirectiveChoice.PAUSE) }) { Text("Mettre le coach en pause") }
        }
        Spacer(Modifier.height(4.dp))
        Text("Accès rapide", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShortcutButton("Focus", Modifier.weight(1f)) { controller.navigate(AtlasController.SCREEN_FOCUS) }
            ShortcutButton("Habitudes", Modifier.weight(1f)) { controller.navigate(AtlasController.SCREEN_HABITS) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShortcutButton("Bibliothèque", Modifier.weight(1f)) { controller.navigate(AtlasController.SCREEN_LIBRARY) }
            ShortcutButton("Coach · journal", Modifier.weight(1f)) { controller.navigate(AtlasController.SCREEN_COACH) }
        }
        if (controller.capabilities[AtlasController.CAP_LAUNCHER_HOME] == true) {
            OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = { controller.navigate(AtlasController.SCREEN_LAUNCHER) }) { Text("Accueil launcher facultatif") }
        }
        if (controller.capabilities[AtlasController.CAP_FOCUS_ROUTINES] == true) {
            OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = { controller.navigate(AtlasController.SCREEN_ROUTINES) }) { Text("Routines de focus") }
        }
        OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = { controller.navigate(AtlasController.SCREEN_SETTINGS) }) { Text("Paramètres") }
    }
}

@Composable
private fun ShortcutButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(modifier = modifier.sizeIn(minHeight = 50.dp).semantics { contentDescription = "Ouvrir $label" }, onClick = onClick) { Text(label) }
}

@Composable
private fun FocusScreen(controller: AtlasController, @Suppress("UNUSED_PARAMETER") revision: Int, onChooseHabit: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Un pas à la fois", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            if (controller.capabilities[AtlasController.CAP_FOCUS_NOTIFICATIONS] == true) {
                "Compte à rebours local avec alarmes Android inexactes et notifications facultatives."
            } else {
                "Compte à rebours local. Les alertes hors écran sont désactivées dans les paramètres."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = controller::navigateToFocusStats) { Text("Statistiques de focus") }
        val session = controller.focusSession
        if (session == null || session.status == FocusStatus.CANCELLED) {
            if (controller.capabilities[AtlasController.CAP_FOCUS] != true) {
                Text("Focus désactivé dans les paramètres.")
                return@Column
            }
            Text("Choisissez votre rythme", style = MaterialTheme.typography.titleMedium)
            Button(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp).testTagModifier("focus_25"), onClick = { controller.startFocus(FocusPreset.POMODORO_25_5) }) { Text("Commencer · 25 min / pause 5 min") }
            OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTagModifier("focus_10"), onClick = { controller.startFocus(FocusPreset.VARIANT_10_2) }) { Text("Commencer · 10 min / pause 2 min") }
            OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTagModifier("focus_demo"), onClick = controller::startDemoFocus) { Text("Mode démo · 20 secondes") }
            ReefPomodoroSetup(onStart = controller::startFocus)
        } else if (session.status == FocusStatus.FINISHED) {
            Text("Session terminée", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(if (session.continuous) "Mode continu · ${session.completedWorkCycles} cycles terminés" else "Cycle ${session.completedWorkCycles} / ${session.totalWorkCycles}")
            Text("Session liée à : ${controller.directiveTitle(session.directiveId)}")
            if (controller.capabilities[AtlasController.CAP_HABITS] == true && controller.activeHabits.isNotEmpty() && controller.canLinkCompletedFocusToHabit) {
                OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = onChooseHabit) { Text("Relier à une habitude…") }
            } else if (controller.focusSession?.continuous == true || (controller.focusSession?.completedWorkCycles ?: 0) < (controller.focusSession?.totalWorkCycles ?: 0)) {
                Text("Le lien à une habitude est proposé après tous les cycles d'un plan fini.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (controller.capabilities[AtlasController.CAP_FOCUS] == true) {
                Button(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = { controller.startFocus(FocusPreset.VARIANT_10_2) }) { Text("Nouvelle session · 10 min / pause 2 min") }
            } else {
                Text("Focus désactivé dans les paramètres.")
            }
        } else {
            Text("${session.phase.focusPhaseLabel()} · ${session.status.focusStatusLabel()}", style = MaterialTheme.typography.titleMedium)
            val timerState = session.toReefTimerState()
            if (timerState.isPomodoroMode && session.status != FocusStatus.FINISHED) {
                Text(
                    if (session.continuous) "Cycle ${timerState.currentCycle} · mode continu"
                    else "Cycle ${session.completedWorkCycles + 1} / ${session.totalWorkCycles}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(formatSeconds(session.remainingSeconds), fontSize = 64.sp, fontWeight = FontWeight.Light, letterSpacing = 2.sp, modifier = Modifier.fillMaxWidth().testTagModifier("focus_timer"), textAlign = TextAlign.Center)
            Text("Session liée à : ${controller.directiveTitle(session.directiveId)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (controller.capabilities[AtlasController.CAP_FOCUS] == true) {
                Button(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp), onClick = controller::toggleFocusPause) {
                    Text(if (session.status == FocusStatus.RUNNING) "Mettre en pause" else "Reprendre")
                }
            } else {
                Text("Focus désactivé. La session est en pause.")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp), onClick = controller::cancelFocus) { Text("Annuler") }
                OutlinedButton(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp), onClick = controller::finishFocus) { Text("Terminer") }
            }
        }
    }
}

@Composable
private fun HabitsScreen(controller: AtlasController, @Suppress("UNUSED_PARAMETER") revision: Int, onExport: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var scheduleKind by remember { mutableStateOf(HabitScheduleKind.DAILY) }
    var selectedDays by remember { mutableStateOf(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) }
    var weeklyQuota by remember { mutableStateOf(3) }
    var anchorDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var reminderEnabled by remember { mutableStateOf(false) }
    var reminderTime by remember { mutableStateOf("09:00") }
    var editingHabitId by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (controller.capabilities[AtlasController.CAP_HABITS] != true) {
            Text("Habitudes désactivées dans les paramètres.")
            return@Column
        }
        Text("Planning, validations et rappels restent locaux. Une notification ne valide rien automatiquement.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().testTagModifier("habit_input"),
            value = name,
            onValueChange = { name = it },
            label = { Text(if (editingHabitId == null) "Nouvelle habitude" else "Modifier le nom") },
            singleLine = true,
        )
        Text("Planning", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterButton("Chaque jour", scheduleKind == HabitScheduleKind.DAILY) { scheduleKind = HabitScheduleKind.DAILY }
            FilterButton("Jours", scheduleKind == HabitScheduleKind.WEEKDAYS) { scheduleKind = HabitScheduleKind.WEEKDAYS }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterButton("Quota hebdo", scheduleKind == HabitScheduleKind.TIMES_PER_WEEK) { scheduleKind = HabitScheduleKind.TIMES_PER_WEEK }
            FilterButton("Alterné", scheduleKind == HabitScheduleKind.ALTERNATING_DAYS) { scheduleKind = HabitScheduleKind.ALTERNATING_DAYS }
        }
        if (scheduleKind == HabitScheduleKind.WEEKDAYS) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY).chunked(4).forEach { rowDays ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        rowDays.forEach { day ->
                            TextButton(onClick = { selectedDays = selectedDays.toggle(day) }) {
                                Text("${day.shortLabel()}${if (day in selectedDays) " ✓" else ""}")
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY).forEach { day ->
                        TextButton(onClick = { selectedDays = selectedDays.toggle(day) }) {
                            Text("${day.shortLabel()}${if (day in selectedDays) " ✓" else ""}")
                        }
                    }
                }
            }
        }
        if (scheduleKind == HabitScheduleKind.TIMES_PER_WEEK) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Objectif par semaine", modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { weeklyQuota = (weeklyQuota - 1).coerceAtLeast(1) }) { Text("−") }
                Text(weeklyQuota.toString())
                OutlinedButton(onClick = { weeklyQuota = (weeklyQuota + 1).coerceAtMost(7) }) { Text("+") }
            }
            Text("Semaine locale, du lundi au dimanche.", style = MaterialTheme.typography.bodySmall)
        }
        if (scheduleKind == HabitScheduleKind.ALTERNATING_DAYS) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = anchorDate,
                onValueChange = { anchorDate = it },
                label = { Text("Première échéance · AAAA-MM-JJ") },
                singleLine = true,
            )
            Text("Un jour sur deux à partir de cette date.", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(checked = reminderEnabled, onCheckedChange = { reminderEnabled = it })
            Text("Rappel facultatif · jamais de validation automatique", modifier = Modifier.weight(1f))
        }
        if (reminderEnabled) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = reminderTime,
                onValueChange = { reminderTime = it },
                label = { Text("Heure locale · HH:mm") },
                singleLine = true,
            )
            if (controller.capabilities[AtlasController.CAP_HABIT_REMINDERS] != true) {
                Text("Activez Rappels d'habitudes dans les paramètres pour planifier cette notification.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp).semantics { contentDescription = if (editingHabitId == null) "Ajouter une habitude" else "Enregistrer la modification de l'habitude" },
            onClick = {
                try {
                    val schedule = when (scheduleKind) {
                        HabitScheduleKind.DAILY -> HabitSchedule.daily()
                        HabitScheduleKind.WEEKDAYS -> HabitSchedule.weekdays(selectedDays)
                        HabitScheduleKind.TIMES_PER_WEEK -> HabitSchedule.timesPerWeek(weeklyQuota)
                        HabitScheduleKind.ALTERNATING_DAYS -> HabitSchedule.alternatingDays(LocalDate.parse(anchorDate))
                    }
                    val reminder = if (reminderEnabled) {
                        val time = LocalTime.parse(reminderTime)
                        val days = if (scheduleKind == HabitScheduleKind.WEEKDAYS) selectedDays else DayOfWeek.entries.toSet()
                        HabitReminder(time.hour, time.minute, days)
                    } else null
                    val currentId = editingHabitId
                    if (currentId == null) controller.addHabit(name, schedule, reminder)
                    else {
                        controller.updateHabit(currentId, name, schedule)
                        controller.setHabitReminder(currentId, reminder)
                    }
                    name = ""
                    editingHabitId = null
                } catch (error: IllegalArgumentException) {
                    controller.reportMessage(error.message ?: "Vérifiez le planning et l'heure du rappel.")
                }
            },
        ) { Text(if (editingHabitId == null) "Ajouter l'habitude" else "Enregistrer les changements") }
        if (editingHabitId != null) {
            TextButton(onClick = { editingHabitId = null; name = "" }) { Text("Annuler la modification") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterButton("7 jours", controller.historyDays == 7) { controller.changeHistoryDays(7) }
            FilterButton("30 jours", controller.historyDays == 30) { controller.changeHistoryDays(30) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onExport) { Text("Exporter CSV") }
        }
        if (controller.activeHabits.isEmpty()) Text("Aucune habitude pour le moment.")
        controller.activeHabits.forEach { habit ->
            val checked = habit.id in controller.checkedHabitIds
            val history = controller.habits.history(habit.id, LocalDate.now(), controller.historyDays)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(habit.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text(habit.schedule.summaryLabel(), style = MaterialTheme.typography.bodySmall)
                    Text(controller.habitHistorySummary(habit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Calendrier récent · " + history.days.takeLast(7).joinToString("  ") { day ->
                        val marker = when { day.isCheckedIn -> "✓"; day.isDue -> "·"; else -> "–" }
                        "${day.date.dayOfMonth}$marker"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp), onClick = {
                            name = habit.name
                            scheduleKind = habit.schedule.kind
                            selectedDays = habit.schedule.weekdaysMask.let { mask -> DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.toSet() }.ifEmpty { selectedDays }
                            weeklyQuota = habit.schedule.timesPerWeek.coerceIn(1, 7)
                            anchorDate = habit.schedule.anchorDateEpochDay?.let(LocalDate::ofEpochDay)?.toString() ?: LocalDate.now().toString()
                            reminderEnabled = habit.reminder?.enabled == true
                            reminderTime = habit.reminder?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "09:00"
                            editingHabitId = habit.id
                        }) { Text("Modifier") }
                        OutlinedButton(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp), onClick = { controller.archiveHabit(habit.id) }) { Text("Supprimer") }
                    }
                    OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).semantics { contentDescription = if (checked) "Annuler la validation de ${habit.name}" else "Valider ${habit.name} aujourd'hui" }, onClick = { controller.toggleHabitToday(habit) }) {
                        Text(if (checked) "Annuler aujourd'hui" else "Valider aujourd'hui")
                    }
                }
            }
        }
    }
}

private fun Set<DayOfWeek>.toggle(day: DayOfWeek): Set<DayOfWeek> = if (day in this) this - day else this + day

private fun DayOfWeek.shortLabel(): String = when (this) {
    DayOfWeek.MONDAY -> "Lun"
    DayOfWeek.TUESDAY -> "Mar"
    DayOfWeek.WEDNESDAY -> "Mer"
    DayOfWeek.THURSDAY -> "Jeu"
    DayOfWeek.FRIDAY -> "Ven"
    DayOfWeek.SATURDAY -> "Sam"
    DayOfWeek.SUNDAY -> "Dim"
}

private fun HabitSchedule.summaryLabel(): String = when (kind) {
    HabitScheduleKind.DAILY -> "Chaque jour"
    HabitScheduleKind.WEEKDAYS -> "Jours sélectionnés"
    HabitScheduleKind.TIMES_PER_WEEK -> "$timesPerWeek fois par semaine"
    HabitScheduleKind.ALTERNATING_DAYS -> "Un jour sur deux depuis ${anchorDateEpochDay?.let(LocalDate::ofEpochDay)}"
}

@Composable
private fun FilterButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(modifier = Modifier.sizeIn(minHeight = 48.dp), onClick = onClick) { Text(label) }
    else OutlinedButton(modifier = Modifier.sizeIn(minHeight = 48.dp), onClick = onClick) { Text(label) }
}

@Composable
private fun LibraryScreen(controller: AtlasController, @Suppress("UNUSED_PARAMETER") revision: Int, context: Context) {
    var showAddLink by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var failedItem by remember { mutableStateOf<ContentItem?>(null) }

    fun open(item: ContentItem) {
        val result = controller.openContent(item.id, context)
        if (result.status == dev.atlas.coach.content.ContentOpenStatus.NO_COMPATIBLE_APP) failedItem = item
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (controller.capabilities[AtlasController.CAP_CONTENT] != true) {
            Text("Bibliothèque désactivée dans les paramètres.")
            return@Column
        }
        Text("Les ouvertures passent par ACTION_VIEW et le sélecteur Android. Aucun historique de lecture n'est collecté.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = { showAddLink = true }) { Text("Ajouter un lien vidéo précis") }
        Text("Liens vidéo sélectionnés", style = MaterialTheme.typography.titleMedium)
        val links = controller.contentItems.filter { it.kind == ContentKind.VIDEO_LINK }
        if (links.isEmpty()) Text("Aucun lien vidéo ajouté.")
        links.forEach { item ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.title, style = MaterialTheme.typography.bodyLarge)
                    Text("Ouvertures locales : ${item.openCount}${if (item.pinned) " · épinglé" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp).testTagModifier("content_${item.id}"), onClick = { open(item) }) { Text("Ouvrir") }
                        TextButton(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp), onClick = { controller.pinContent(item.id, !item.pinned) }) { Text(if (item.pinned) "Désépingler" else "Épingler") }
                        TextButton(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp), onClick = { controller.removeContent(item.id) }) { Text("Supprimer") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Compter les ouvertures dans ATLAS", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Switch(checked = item.openCountingEnabled, onCheckedChange = { controller.setContentOpenCounting(item.id, it) })
                    }
                }
            }
        }
        Text("Recherches génériques", style = MaterialTheme.typography.titleMedium)
        val searches = controller.contentItems.filter { it.kind == ContentKind.SEARCH }
        if (searches.isEmpty()) Text("Aucune recherche enregistrée.")
        searches.forEach { item ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.bodyLarge)
                        Text("Recherche; aucun lien vidéo précis sélectionné", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(modifier = Modifier.sizeIn(minHeight = 48.dp), onClick = { open(item) }) { Text("Ouvrir") }
                }
            }
        }
    }

    if (showAddLink) {
        AlertDialog(
            onDismissRequest = { showAddLink = false },
            title = { Text("Ajouter un lien vidéo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Titre") }, singleLine = true)
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL YouTube HTTPS") }, singleLine = true)
                    Text("Seuls les liens vidéo spécifiques HTTPS sont acceptés; les recherches restent séparées.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (controller.addContentLink(title, url)) {
                        title = ""
                        url = ""
                        showAddLink = false
                    }
                }) { Text("Ajouter") }
            },
            dismissButton = { TextButton(onClick = { showAddLink = false }) { Text("Annuler") } },
        )
    }
    failedItem?.let { item ->
        AlertDialog(
            onDismissRequest = { failedItem = null },
            title = { Text("Aucune application compatible") },
            text = { Text("Android n'a trouvé aucun lecteur ni navigateur pour ce lien. Vous pouvez le copier manuellement.") },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Lien vidéo ATLAS", item.url))
                    controller.reportMessage("Lien copié dans le presse-papiers.")
                    failedItem = null
                }) { Text("Copier le lien") }
            },
            dismissButton = { TextButton(onClick = { failedItem = null }) { Text("Fermer") } },
        )
    }
}

@Composable
private fun CoachScreen(controller: AtlasController, @Suppress("UNUSED_PARAMETER") revision: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (controller.capabilities[AtlasController.CAP_COACH] != true) {
            Text("Coach local désactivé dans les paramètres.")
            return@Column
        }
        Text("Coach local · aucune IA ni serveur", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Directive : ${controller.directive.title}", style = MaterialTheme.typography.titleMedium)
        if (controller.isCoachPaused) OutlinedButton(onClick = controller::resumeCoach) { Text("Réactiver le coach") }
        if (controller.coachJournal.isEmpty()) Text("Le journal apparaîtra après votre première réponse.")
        controller.coachJournal.forEach { entry ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.choice.journalLabel(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(entry.message)
                    Text("Résultat : ${entry.effect.displayLabel()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    controller: AtlasController,
    @Suppress("UNUSED_PARAMETER") revision: Int,
    requestHomeRole: () -> Unit,
    openSystemHome: () -> Unit,
    onExport: () -> Unit,
    requestNotifications: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Mandat", style = MaterialTheme.typography.titleLarge)
        Text("Vous choisissez les objectifs et les permissions. ATLAS propose une action; les règles locales seules peuvent présenter une friction. Rien ne bloque Android. Vous pouvez faire pause, désactiver le suivi et revenir à l'application d'accueil.", lineHeight = 23.sp)
        Text("Capacités indépendantes", style = MaterialTheme.typography.titleMedium)
        CapabilityRow("Focus", controller, AtlasController.CAP_FOCUS)
        CapabilityRow("Habitudes", controller, AtlasController.CAP_HABITS)
        CapabilityRow("Bibliothèque", controller, AtlasController.CAP_CONTENT)
        CapabilityRow("Coach local", controller, AtlasController.CAP_COACH)
        CapabilityRow(
            "Notifications de phases Focus",
            controller,
            AtlasController.CAP_FOCUS_NOTIFICATIONS,
            onChanged = { enabled -> controller.setCapability(AtlasController.CAP_FOCUS_NOTIFICATIONS, enabled); if (enabled) requestNotifications() },
        )
        CapabilityRow(
            "Rappels d'habitudes",
            controller,
            AtlasController.CAP_HABIT_REMINDERS,
            onChanged = { enabled -> controller.setCapability(AtlasController.CAP_HABIT_REMINDERS, enabled); if (enabled) requestNotifications() },
        )
        CapabilityRow("Accueil launcher facultatif ATLAS", controller, AtlasController.CAP_LAUNCHER_HOME)
        CapabilityRow(
            "Routines de focus locales",
            controller,
            AtlasController.CAP_FOCUS_ROUTINES,
            onChanged = { enabled -> controller.setCapability(AtlasController.CAP_FOCUS_ROUTINES, enabled); if (enabled) requestNotifications() },
        )
        Text("Notifications Android · ${if (controller.notificationPermissionGranted()) "autorisées" else "non autorisées ou désactivées"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = requestNotifications) { Text("Autoriser les notifications…") }
        OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = { controller.navigate(AtlasController.SCREEN_OBSERVATION) }) {
            Text("Observation · ${controller.usagePermissionStatusText()}")
        }
        OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = { controller.navigate(AtlasController.SCREEN_ROUTINES) }) { Text("Gérer les routines de focus") }
        OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = { controller.navigate(AtlasController.SCREEN_DIAGNOSTICS) }) { Text("Diagnostic avancé / développeur") }
        OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = { if (controller.isEmergencyPaused) controller.endEmergencyPause() else controller.setEmergencyPause() }) { Text(if (controller.isEmergencyPaused) "Terminer la pause · 30 min max" else "Activer pause d'urgence · 30 min") }
        OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = onExport) { Text("Exporter toutes les données locales") }
        OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = openSystemHome) { Text("Choisir l'application d'accueil par défaut") }
        OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = requestHomeRole) { Text("Proposer ATLAS comme accueil…") }
        OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = { controller.navigate(AtlasController.SCREEN_ABOUT) }) { Text("À propos / Licences") }
        Text("Le rôle Accueil est demandé uniquement après ce choix explicite. Vous pouvez refuser.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AboutLicensesScreen(context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("À propos / Licences", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("ATLAS contient des adaptations de code libre pour un usage privé. L'APK ne doit pas être distribuée sans publier les sources sous licences compatibles.")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Reef · minuterie Pomodoro", style = MaterialTheme.typography.titleMedium)
                Text("Adaptation sous MIT. Attribution requise : Reef par Pranav Purwar, avec lien vers le dépôt d'origine.")
                TextButton(onClick = { openExternalLink(context, "https://github.com/aload0/Reef") }) { Text("github.com/aload0/Reef") }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Olauncher · modèle de liste d'applications", style = MaterialTheme.typography.titleMedium)
                Text("Adaptation sous GNU GPL version 3.0.")
                TextButton(onClick = { openExternalLink(context, "https://github.com/tanujnotes/Olauncher") }) { Text("Dépôt Olauncher") }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Loop Habit Tracker · force d'habitude et CSV", style = MaterialTheme.typography.titleMedium)
                Text("Adaptations de uhabits-core sous GNU GPL version 3.0 ou ultérieure, au choix du destinataire.")
                TextButton(onClick = { openExternalLink(context, "https://github.com/iSoron/uhabits") }) { Text("Dépôt Loop Habit Tracker") }
            }
        }
        Text("Textes de licence et notices complètes : THIRD_PARTY_NOTICES.md à la racine du projet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun openExternalLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
private fun CapabilityRow(
    label: String,
    controller: AtlasController,
    key: String,
    onChanged: ((Boolean) -> Unit)? = null,
) {
    Row(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = controller.capabilities[key] == true,
            onCheckedChange = { enabled ->
                if (onChanged != null) onChanged(enabled) else controller.setCapability(key, enabled)
            },
            modifier = Modifier.semantics { contentDescription = "Activer ou désactiver $label" },
        )
    }
}

@Composable
private fun DiagnosticsScreen(controller: AtlasController) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Diagnostic avancé / développeur", style = MaterialTheme.typography.headlineSmall)
        Text("Les événements ci-dessous sont synthétiques. Ils ne lisent pas les applications installées et ne représentent pas une observation réelle.")
        CapabilityRow("Simulateur de règles", controller, AtlasController.CAP_SIMULATED_RULES)
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
            enabled = controller.capabilities[AtlasController.CAP_SIMULATED_RULES] == true,
            onClick = { controller.navigate(AtlasController.SCREEN_SIMULATOR) },
        ) { Text("Ouvrir le simulateur de friction") }
    }
}

@Composable
private fun SimulatorScreen(controller: AtlasController, @Suppress("UNUSED_PARAMETER") revision: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Événements simulés, pas une observation réelle", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text("La règle propose une friction si 4 ouvertures de la même app surviennent en moins de 25 minutes. Elle ne bloque rien.")
        val enabled = controller.capabilities[AtlasController.CAP_SIMULATED_RULES] == true
        if (!enabled) Text("Simulateur désactivé dans les paramètres.")
        Button(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp).testTagModifier("simulate_four"), enabled = enabled, onClick = controller::simulateFourOpenings) { Text("Injecter 4 ouvertures simulées") }
        OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), enabled = enabled, onClick = controller::repeatLastEvent) { Text("Répéter le dernier événement (doublon)") }
        OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), enabled = enabled, onClick = controller::simulateCooldown) { Text("Injecter 4 autres · vérifier le délai d'attente") }
        controller.simulationMessage?.let { NoticeCard(it) }
        Text("Règle : ${if (controller.quietHoursEnabled) "heures calmes 22:00–07:00" else "heures calmes désactivées"} · délai d'attente 15 min · pause ${if (controller.isEmergencyPaused) "active" else "inactive"}.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = controller::toggleQuietHours) { Text(if (controller.quietHoursEnabled) "Désactiver les heures calmes" else "Activer les heures calmes 22:00–07:00") }
        Text("Propositions émises : ${controller.interventions.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTagModifier("intervention_count"))
        controller.interventions.forEach { intervention ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Pause proposée · ${intervention.packageName}", fontWeight = FontWeight.Medium)
                    Text("Événement ${intervention.eventId.take(8)} · délai d'attente de 15 min", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(message: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(message, modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ExportDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.height(350.dp).verticalScroll(rememberScrollState())) {
                Text(text.ifBlank { "Aucune donnée à exporter." }, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

private fun formatSeconds(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    return "%02d:%02d".format(safe / 60, safe % 60)
}

private fun DirectiveChoice.journalLabel(): String = when (this) {
    DirectiveChoice.START -> "Commencer"
    DirectiveChoice.DISCUSS -> "Discuter"
    DirectiveChoice.ALTERNATIVE -> "Alternative"
    DirectiveChoice.POSTPONE -> "Reporter"
    DirectiveChoice.PAUSE -> "Pause"
}

private fun Modifier.testTagModifier(tag: String): Modifier = this.testTag(tag)
