package dev.atlas.coach.app

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
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
                    )
                    AtlasController.SCREEN_SIMULATOR -> SimulatorScreen(controller, revision)
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
        Text("Un compte à rebours local, sans notification ni service en arrière-plan.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = controller::navigateToFocusStats) { Text("Statistiques de focus") }
        val session = controller.focusSession
        if (session == null || session.status == FocusStatus.CANCELLED) {
            if (controller.capabilities[AtlasController.CAP_FOCUS] != true) {
                Text("Focus désactivé dans les paramètres.")
                return@Column
            }
            Text("Choisissez votre rythme", style = MaterialTheme.typography.titleMedium)
            Button(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp).testTagModifier("focus_25"), onClick = { controller.startFocus(FocusPreset.POMODORO_25_5) }) { Text("Commencer · 25 / 5") }
            OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTagModifier("focus_10"), onClick = { controller.startFocus(FocusPreset.VARIANT_10_2) }) { Text("Commencer · 10 / 2") }
            OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTagModifier("focus_demo"), onClick = controller::startDemoFocus) { Text("Mode démo · 20 secondes") }
            ReefPomodoroSetup(onStart = controller::startFocus)
        } else if (session.status == FocusStatus.FINISHED) {
            Text("Session terminée", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text("Session liée à : ${controller.directiveTitle(session.directiveId)}")
            if (controller.capabilities[AtlasController.CAP_HABITS] == true && controller.activeHabits.isNotEmpty()) {
                OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = onChooseHabit) { Text("Relier à une habitude…") }
            }
            if (controller.capabilities[AtlasController.CAP_FOCUS] == true) {
                Button(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = { controller.startFocus(FocusPreset.VARIANT_10_2) }) { Text("Nouvelle session · 10 / 2") }
            } else {
                Text("Focus désactivé dans les paramètres.")
            }
        } else {
            Text("${session.phase.focusPhaseLabel()} · ${session.status.focusStatusLabel()}", style = MaterialTheme.typography.titleMedium)
            val timerState = session.toReefTimerState()
            if (timerState.isPomodoroMode && session.status != FocusStatus.FINISHED) {
                Text("Cycle ${timerState.currentCycle} / ${timerState.totalCycles}", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (controller.capabilities[AtlasController.CAP_HABITS] != true) {
            Text("Habitudes désactivées dans les paramètres.")
            return@Column
        }
        Text("Petites pratiques, suivi local", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                modifier = Modifier.weight(1f).testTagModifier("habit_input"),
                value = name,
                onValueChange = { name = it },
                label = { Text("Nouvelle habitude") },
                singleLine = true,
            )
            Button(modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 52.dp).semantics { contentDescription = "Ajouter une habitude" }, onClick = { controller.addHabit(name); name = "" }) { Text("Ajouter") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterButton("7 jours", controller.historyDays == 7) { controller.changeHistoryDays(7) }
            FilterButton("30 jours", controller.historyDays == 30) { controller.changeHistoryDays(30) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onExport) { Text("Exporter") }
        }
        if (controller.activeHabits.isEmpty()) Text("Aucune habitude pour le moment.")
        controller.activeHabits.forEach { habit ->
            val checked = habit.id in controller.checkedHabitIds
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(habit.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text(controller.habitHistorySummary(habit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).semantics { contentDescription = if (checked) "Annuler la validation de ${habit.name}" else "Valider ${habit.name} aujourd'hui" }, onClick = { controller.toggleHabitToday(habit) }) {
                        Text(if (checked) "Annuler aujourd'hui" else "Valider aujourd'hui")
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(modifier = Modifier.sizeIn(minHeight = 48.dp), onClick = onClick) { Text(label) }
    else OutlinedButton(modifier = Modifier.sizeIn(minHeight = 48.dp), onClick = onClick) { Text(label) }
}

@Composable
private fun LibraryScreen(controller: AtlasController, @Suppress("UNUSED_PARAMETER") revision: Int, context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (controller.capabilities[AtlasController.CAP_CONTENT] != true) {
            Text("Bibliothèque désactivée dans les paramètres.")
            return@Column
        }
        Text("Trois liens de recherche génériques. Android ouvre un sélecteur si besoin.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        controller.contentItems.forEach { item ->
            Card(modifier = Modifier.fillMaxWidth().clickable { controller.openContent(item.id, context) }) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    TextButton(modifier = Modifier.sizeIn(minHeight = 48.dp).testTagModifier("content_${item.id}"), onClick = { controller.openContent(item.id, context) }) { Text("Ouvrir") }
                }
            }
        }
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
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Mandat", style = MaterialTheme.typography.titleLarge)
        Text("Vous choisissez les objectifs et les permissions. ATLAS propose une action; les règles locales seules peuvent présenter une friction. Rien ne bloque Android. Vous pouvez faire pause, désactiver le suivi et revenir à l'application d'accueil.", lineHeight = 23.sp)
        Text("Capacités indépendantes", style = MaterialTheme.typography.titleMedium)
        CapabilityRow("Focus", controller, AtlasController.CAP_FOCUS)
        CapabilityRow("Habitudes", controller, AtlasController.CAP_HABITS)
        CapabilityRow("Bibliothèque", controller, AtlasController.CAP_CONTENT)
        CapabilityRow("Coach local", controller, AtlasController.CAP_COACH)
        CapabilityRow("Simulateur de règles", controller, AtlasController.CAP_SIMULATED_RULES)
        CapabilityRow("Consentement observation d'apps", controller, AtlasController.CAP_APP_TRACKING)
        CapabilityRow("Accueil launcher facultatif ATLAS", controller, AtlasController.CAP_LAUNCHER_HOME)
        CapabilityRow("Routines de focus locales", controller, AtlasController.CAP_FOCUS_ROUTINES)
        Text("Aucune observation réelle n'est intégrée dans VM-1. Ce consentement ne lance aucune collecte.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = { controller.navigate(AtlasController.SCREEN_SIMULATOR) }) { Text("Simulateur de friction · Diagnostic") }
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
private fun CapabilityRow(label: String, controller: AtlasController, key: String) {
    Row(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = controller.capabilities[key] == true,
            onCheckedChange = { controller.setCapability(key, it) },
            modifier = Modifier.semantics { contentDescription = "Activer ou désactiver $label" },
        )
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
