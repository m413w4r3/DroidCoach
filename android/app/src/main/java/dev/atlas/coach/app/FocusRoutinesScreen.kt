package dev.atlas.coach.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atlas.coach.focus.FocusPhase
import dev.atlas.coach.focus.FocusPlan
import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.focus.FocusStatus
import dev.atlas.coach.focus.ReefPomodoroConfig
import dev.atlas.coach.routines.FocusRoutine
import dev.atlas.coach.routines.RoutineSchedule
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/** Agent input contract for a proposed, still user-confirmed local Focus session. */
data class ProposedFocusSession(
    val focusMinutes: Int,
    val breakMinutes: Int,
    val cycles: Int,
    val label: String? = null,
    val reason: String,
    val longBreakMinutes: Int = 15,
    val longBreakEveryCycles: Int = 4,
    val continuous: Boolean = false,
) {
    init {
        require(focusMinutes in 1..180)
        require(breakMinutes in 0..60)
        require(cycles in 1..8)
        require(label == null || label.length <= 60)
        require(reason.length <= 240)
        require(longBreakMinutes in 1..120)
        require(longBreakEveryCycles in 1..12)
    }

    /** Maps the v2 proposal to the local timer. Zero-minute breaks become a one-second transition. */
    fun toFocusPlan(): FocusPlan = FocusPlan(
        presetId = "agent-proposed-$focusMinutes-$breakMinutes-$cycles",
        workSeconds = focusMinutes * 60L,
        shortBreakSeconds = if (breakMinutes == 0) 1L else breakMinutes * 60L,
        longBreakSeconds = longBreakMinutes * 60L,
        longBreakEveryCycles = longBreakEveryCycles,
        totalWorkCycles = cycles,
        continuous = continuous,
    )

    fun toEditableConfig(): ReefPomodoroConfig = ReefPomodoroConfig(
        focusMinutes = focusMinutes,
        shortBreakMinutes = breakMinutes.coerceAtLeast(1),
        longBreakMinutes = longBreakMinutes,
        cycles = longBreakEveryCycles,
        totalWorkCycles = cycles,
        continuous = continuous,
    )

    fun estimatedDurationSeconds(): Long {
        val longBreakEvery = longBreakEveryCycles
        val breakSeconds = (1 until cycles).sumOf { completedCycles ->
            if (breakMinutes == 0) 1L
            else if (completedCycles % longBreakEvery == 0) longBreakMinutes * 60L
            else breakMinutes * 60L
        }
        return focusMinutes * 60L * cycles + breakSeconds
    }
}

/** Session-focused UI entry point for AtlasScreens integration. */
@Composable
fun FocusSessionScreen(
    controller: AtlasController,
    proposedSession: ProposedFocusSession? = null,
    onStartProposed: ((taskTitle: String, config: ReefPomodoroConfig) -> Unit)? = null,
    onChooseHabit: () -> Unit = {},
) {
    val session = controller.focusSession?.takeUnless { it.status == FocusStatus.CANCELLED }
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    var showManualSetup by remember { mutableStateOf(false) }

    LaunchedEffect(session?.status) {
        while (session?.status == FocusStatus.RUNNING) {
            delay(1_000)
            now = ZonedDateTime.now()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            session?.status == FocusStatus.RUNNING || session?.status == FocusStatus.PAUSED -> {
                val activeSession = session ?: return@Column
                Text("${activeSession.taskTitle ?: controller.directiveTitle(activeSession.directiveId)}", modifier = Modifier.testTag("focus_active_task"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("${activeSession.workSeconds / 60} min de concentration · " +
                    if (activeSession.continuous) "mode continu" else "${activeSession.totalWorkCycles} cycles",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    formatFocusCountdown(activeSession.remainingSeconds),
                    modifier = Modifier.fillMaxWidth().testTag("focus_time_remaining"),
                    textAlign = TextAlign.Center,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Light,
                )
                Text(activeSession.phase.focusLabel() + if (activeSession.status == FocusStatus.PAUSED) " · en pause" else " · en cours", modifier = Modifier.testTag("focus_phase"))
                if (activeSession.continuous) {
                    Text("Cycle ${activeSession.completedWorkCycles + 1} · mode continu", modifier = Modifier.testTag("focus_cycles"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Cycle ${activeSession.completedWorkCycles + 1} / ${activeSession.totalWorkCycles}", modifier = Modifier.testTag("focus_cycles"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val endMillis = approximateFocusEndMillis(activeSession, now.toInstant().toEpochMilli())
                Text(
                    if (activeSession.continuous) {
                        "Fin approximative de cette phase · ${formatFocusEnd(endMillis)}"
                    } else {
                        "Fin approximative de la session · ${formatFocusEnd(endMillis)}"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Notifications : ${focusNotificationStatus(controller)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (controller.capabilities[AtlasController.CAP_FOCUS] == true) {
                    Button(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp), onClick = controller::toggleFocusPause) {
                        Text(if (activeSession.status == FocusStatus.RUNNING) "Pause" else "Reprendre")
                    }
                } else {
                    Text("Focus est désactivé. La session reste en pause.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp), onClick = controller::cancelFocus) { Text("Annuler") }
                    OutlinedButton(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp), onClick = controller::finishFocus) { Text("Terminer") }
                }
            }
            session?.status == FocusStatus.FINISHED && showManualSetup &&
                proposedSession != null && onStartProposed != null -> {
                Text("Session proposée", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(proposedSession.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PomodoroSessionSetup(
                    initialTaskTitle = controller.directive.title,
                    initialConfig = proposedSession.toEditableConfig(),
                    onStart = { taskTitle, config -> onStartProposed(taskTitle, config) },
                )
            }
            session?.status == FocusStatus.FINISHED && showManualSetup -> {
                Text("Préparer une nouvelle session", style = MaterialTheme.typography.headlineSmall)
                PomodoroSessionSetup(
                    initialTaskTitle = controller.directive.title,
                    onStart = { taskTitle, config -> controller.startFocus(config, taskTitle) },
                )
            }
            session?.status == FocusStatus.FINISHED -> {
                Text("Session terminée", style = MaterialTheme.typography.headlineSmall)
                Text(session.taskTitle ?: controller.directiveTitle(session.directiveId))
                Text("${session.completedWorkCycles} cycle(s) de travail terminés.")
                if (controller.capabilities[AtlasController.CAP_HABITS] == true && controller.activeHabits.isNotEmpty() && controller.canLinkCompletedFocusToHabit) {
                    OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = onChooseHabit) {
                        Text("Relier à une habitude…")
                    }
                }
                Button(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = { showManualSetup = true }) {
                    Text("Nouvelle session")
                }
            }
            proposedSession != null -> {
                Text("Session proposée", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(proposedSession.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PomodoroSessionSetup(
                    initialTaskTitle = controller.directive.title,
                    initialConfig = proposedSession.toEditableConfig(),
                    onStart = { taskTitle, config ->
                        if (onStartProposed != null) onStartProposed(taskTitle, config)
                        else controller.startFocus(config, taskTitle)
                    },
                )
            }
            else -> {
                PomodoroSessionSetup(
                    initialTaskTitle = controller.directive.title,
                    onStart = { taskTitle, config -> controller.startFocus(config, taskTitle) },
                )
            }
        }
    }
}

private fun FocusPhase.focusLabel(): String = when (this) {
    FocusPhase.WORK -> "Concentration"
    FocusPhase.SHORT_BREAK -> "Pause courte"
    FocusPhase.LONG_BREAK -> "Pause longue"
}

private fun formatFocusCountdown(seconds: Long): String =
    "%02d:%02d".format(Locale.FRANCE, seconds.coerceAtLeast(0L) / 60L, seconds.coerceAtLeast(0L) % 60L)

private fun focusNotificationStatus(controller: AtlasController): String = when {
    controller.capabilities[AtlasController.CAP_FOCUS_NOTIFICATIONS] != true ->
        "désactivées; le compte à rebours local continue dans ATLAS"
    !controller.notificationPermissionGranted() ->
        "permission absente ou notifications coupées; aucune alerte n’est garantie"
    else -> "autorisées, mais Android peut les retarder; elles ne sont pas exactes"
}

private fun formatFocusEnd(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))

internal fun approximateFocusEndMillis(session: FocusSession, nowUtcMillis: Long): Long {
    var remaining = session.remainingSeconds.coerceAtLeast(0L)
    if (session.continuous || session.status == FocusStatus.FINISHED || session.status == FocusStatus.CANCELLED) {
        return nowUtcMillis + remaining * 1_000L
    }

    var completed = session.completedWorkCycles
    if (session.phase == FocusPhase.WORK) {
        val subsequentCycles = (session.totalWorkCycles - completed - 1).coerceAtLeast(0)
        repeat(subsequentCycles) {
            completed++
            remaining += if (completed % session.longBreakEveryCycles == 0) session.longBreakSeconds else session.shortBreakSeconds
            remaining += session.workSeconds
        }
    } else {
        val subsequentCycles = (session.totalWorkCycles - completed).coerceAtLeast(0)
        repeat(subsequentCycles) { index ->
            remaining += session.workSeconds
            completed++
            if (index < subsequentCycles - 1) {
                remaining += if (completed % session.longBreakEveryCycles == 0) session.longBreakSeconds else session.shortBreakSeconds
            }
        }
    }
    return nowUtcMillis + remaining * 1_000L
}

@Composable
internal fun FocusRoutinesScreen(controller: AtlasController) {
    var name by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("09:00") }
    var end by remember { mutableStateOf("11:00") }
    var minutes by remember { mutableStateOf("25") }
    var weekdaysOnly by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = ZonedDateTime.now()
        }
    }
    val activeIds = controller.activeFocusRoutines(now).mapTo(mutableSetOf()) { it.id }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Une routine envoie une proposition. Elle ne démarre jamais un focus automatiquement; aucun usage d'app n'est lu.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (controller.capabilities[AtlasController.CAP_FOCUS_ROUTINES] != true) {
            Text("La capacité Routines est désactivée. Activez-la dans Paramètres pour planifier des propositions.")
            return@Column
        }
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nom de la routine") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text("Début · HH:mm") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = end, onValueChange = { end = it }, label = { Text("Fin · HH:mm") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(value = minutes, onValueChange = { minutes = it }, label = { Text("Durée du focus · minutes") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Du lundi au vendredi")
            Switch(checked = weekdaysOnly, onCheckedChange = { weekdaysOnly = it })
        }
        Button(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
            onClick = {
                val focusMinutes = minutes.toIntOrNull()?.coerceIn(1, 180)
                if (focusMinutes == null) {
                    controller.reportMessage("La durée doit être comprise entre 1 et 180 minutes.")
                } else {
                    val saved = if (editingId == null) {
                        controller.addFocusRoutine(name, start, end, weekdaysOnly, focusMinutes)
                    } else {
                        controller.updateFocusRoutine(editingId!!, name, start, end, weekdaysOnly, focusMinutes)
                    }
                    if (saved) {
                        name = ""
                        editingId = null
                    }
                }
            },
        ) { Text(if (editingId == null) "Ajouter la routine" else "Enregistrer la routine") }
        if (editingId != null) TextButton(onClick = { editingId = null; name = "" }) { Text("Annuler la modification") }
        if (controller.focusRoutines.isEmpty()) Text("Aucune routine enregistrée.")
        controller.focusRoutines.forEach { routine ->
            val next = controller.nextFocusRoutineTrigger(routine.id, now)
            RoutineCard(
                routine = routine,
                isActive = routine.id in activeIds,
                nextTrigger = next?.format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm z")),
                controller = controller,
                onEdit = {
                    editingId = routine.id
                    name = routine.name
                    start = routine.schedule.time?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "09:00"
                    end = routine.schedule.endTime?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "11:00"
                    minutes = routine.focusMinutes.toString()
                    weekdaysOnly = routine.schedule.type == RoutineSchedule.ScheduleType.WEEKLY
                },
            )
        }
    }
}

@Composable
private fun RoutineCard(
    routine: FocusRoutine,
    isActive: Boolean,
    nextTrigger: String?,
    controller: AtlasController,
    onEdit: () -> Unit,
) {
    val schedule = routine.schedule
    val timeRange = if (schedule.type == RoutineSchedule.ScheduleType.MANUAL) "Manuelle" else {
        "${schedule.time?.format(DateTimeFormatter.ofPattern("HH:mm"))}–${schedule.endTime?.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    }
    val days = if (schedule.type == RoutineSchedule.ScheduleType.WEEKLY) " · lundi–vendredi" else " · chaque jour"
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(routine.name, style = MaterialTheme.typography.titleMedium)
            Text("$timeRange$days · ${routine.focusMinutes} min")
            Text("Prochain déclenchement : ${nextTrigger ?: "aucun (désactivée ou horaire manuel)"}", style = MaterialTheme.typography.bodySmall)
            Text(if (isActive) "Plage active · action à confirmer" else if (routine.isEnabled) "En attente de sa plage" else "Routine désactivée", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Routine active")
                Switch(checked = routine.isEnabled, onCheckedChange = { controller.setFocusRoutineEnabled(routine.id, it) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isActive && routine.isEnabled) Button(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp), onClick = { controller.startFocusRoutine(routine.id) }) { Text("Démarrer") }
                OutlinedButton(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp), onClick = onEdit) { Text("Modifier") }
                OutlinedButton(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp), onClick = { controller.deleteFocusRoutine(routine.id) }) { Text("Supprimer") }
            }
        }
    }
}
