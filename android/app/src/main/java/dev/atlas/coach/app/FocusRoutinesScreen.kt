package dev.atlas.coach.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atlas.coach.focus.FocusPhase
import dev.atlas.coach.focus.FocusPlan
import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.focus.FocusStatus
import dev.atlas.coach.focus.ReefPomodoroConfig
import dev.atlas.coach.goals.GoalMetric
import dev.atlas.coach.goals.GoalProgressCalculator
import dev.atlas.coach.goals.GoalStatus
import dev.atlas.coach.routines.FocusRoutine
import dev.atlas.coach.routines.RoutineSchedule
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import dev.atlas.coach.app.ui.AtlasRule

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
    val compatibleStart: ((String, ReefPomodoroConfig, String?) -> Unit)? = onStartProposed?.let { start ->
        { taskTitle, config, _ -> start(taskTitle, config) }
    }
    FocusSessionScreen(
        controller = controller,
        revision = controller.revision,
        proposedSession = proposedSession,
        onStartProposed = compatibleStart,
        onChooseHabit = onChooseHabit,
    )
}

/** Goal-aware overload; the original public FocusSessionScreen overload remains source/JVM compatible. */
@Composable
fun FocusSessionScreen(
    controller: AtlasController,
    revision: Int,
    proposedSession: ProposedFocusSession? = null,
    onStartProposed: ((taskTitle: String, config: ReefPomodoroConfig, goalId: String?) -> Unit)? = null,
    onChooseHabit: () -> Unit = {},
) {
    val session = controller.focusSession?.takeUnless { it.status == FocusStatus.CANCELLED }
    val eligibleGoals = remember(controller, revision) {
        controller.coachGoals.filter {
            it.record.status == GoalStatus.ACTIVE && it.record.metric == GoalMetric.FOCUS_MINUTES
        }
    }
    var selectedGoalId by remember { mutableStateOf<String?>(null) }
    var hasExplicitGoalSelection by remember { mutableStateOf(false) }
    val selectedFocusGoalId = if (hasExplicitGoalSelection) {
        selectedGoalId?.takeIf { id -> eligibleGoals.any { it.record.id == id } }
    } else {
        eligibleGoals.singleOrNull()?.record?.id
    }
    var showSessionDetails by remember(session?.id) { mutableStateOf(false) }
    val onFocusGoalSelected: (String?) -> Unit = { id ->
        selectedGoalId = id
        hasExplicitGoalSelection = true
    }
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
                Text(
                    activeSession.taskTitle ?: controller.directiveTitle(activeSession.directiveId),
                    modifier = Modifier.testTag("focus_active_task"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                FocusGoalSessionProjection(controller, activeSession)
                FocusTimeGauge(activeSession)
                Text(
                    activeSession.phase.focusLabel() +
                        if (activeSession.status == FocusStatus.PAUSED) " · en pause" else " · en cours",
                    modifier = Modifier.fillMaxWidth().testTag("focus_phase"),
                    textAlign = TextAlign.Center,
                )
                if (activeSession.continuous) {
                    Text("Cycle ${activeSession.completedWorkCycles + 1} · mode continu", modifier = Modifier.testTag("focus_cycles"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Cycle ${activeSession.completedWorkCycles + 1} / ${activeSession.totalWorkCycles}", modifier = Modifier.testTag("focus_cycles"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val notificationsNeedAttention =
                    controller.capabilities[AtlasController.CAP_FOCUS_NOTIFICATIONS] == true &&
                        !controller.notificationPermissionGranted()
                if (notificationsNeedAttention) {
                    Text(
                        "Notifications : ${focusNotificationStatus(controller)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (controller.capabilities[AtlasController.CAP_FOCUS] == true) {
                    Button(
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp).testTag("focus_pause_resume"),
                        onClick = controller::toggleFocusPause,
                    ) {
                        Text(if (activeSession.status == FocusStatus.RUNNING) "Pause" else "Reprendre")
                    }
                } else {
                    Text("Focus est désactivé. La session reste en pause.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp).testTag("focus_cancel"),
                        onClick = controller::cancelFocus,
                    ) { Text("Annuler") }
                    OutlinedButton(
                        modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp).testTag("focus_finish"),
                        onClick = controller::finishFocus,
                    ) { Text("Terminer") }
                }
                TextButton(
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("focus_details_toggle"),
                    onClick = { showSessionDetails = !showSessionDetails },
                ) {
                    Text(if (showSessionDetails) "Masquer les détails" else "Détails")
                }
                if (showSessionDetails) {
                    Text(
                        "${activeSession.workSeconds / 60} min de concentration · " +
                            if (activeSession.continuous) "mode continu" else "${activeSession.totalWorkCycles} cycles",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val endMillis = approximateFocusEndMillis(activeSession, now.toInstant().toEpochMilli())
                    Text(
                        if (activeSession.continuous) {
                            "Fin approximative de cette phase · ${formatFocusEnd(endMillis)}"
                        } else {
                            "Fin approximative de la session · ${formatFocusEnd(endMillis)}"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!notificationsNeedAttention) {
                        Text(
                            "Notifications : ${focusNotificationStatus(controller)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            session?.status == FocusStatus.FINISHED && showManualSetup &&
                proposedSession != null && onStartProposed != null -> {
                Text("Session proposée", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(proposedSession.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FocusGoalSelector(eligibleGoals, selectedFocusGoalId, onFocusGoalSelected)
                PomodoroSessionSetup(
                    initialTaskTitle = controller.directive.title,
                    initialConfig = proposedSession.toEditableConfig(),
                    onStart = { taskTitle, config -> onStartProposed(taskTitle, config, selectedFocusGoalId) },
                )
            }
            session?.status == FocusStatus.FINISHED && showManualSetup -> {
                Text("Préparer une nouvelle session", style = MaterialTheme.typography.headlineSmall)
                FocusGoalSelector(eligibleGoals, selectedFocusGoalId, onFocusGoalSelected)
                PomodoroSessionSetup(
                    initialTaskTitle = controller.directive.title,
                    onStart = { taskTitle, config -> controller.startFocus(config, taskTitle, selectedFocusGoalId) },
                )
            }
            session?.status == FocusStatus.FINISHED -> {
                Text("Session terminée", style = MaterialTheme.typography.headlineSmall)
                Text(session.taskTitle ?: controller.directiveTitle(session.directiveId))
                FocusGoalSessionProjection(controller, session)
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
                FocusGoalSelector(eligibleGoals, selectedFocusGoalId, onFocusGoalSelected)
                PomodoroSessionSetup(
                    initialTaskTitle = controller.directive.title,
                    initialConfig = proposedSession.toEditableConfig(),
                    onStart = { taskTitle, config ->
                        if (onStartProposed != null) onStartProposed(taskTitle, config, selectedFocusGoalId)
                        else controller.startFocus(config, taskTitle, selectedFocusGoalId)
                    },
                )
            }
            else -> {
                FocusGoalSelector(eligibleGoals, selectedFocusGoalId, onFocusGoalSelected)
                PomodoroSessionSetup(
                    initialTaskTitle = controller.directive.title,
                    onStart = { taskTitle, config -> controller.startFocus(config, taskTitle, selectedFocusGoalId) },
                )
            }
        }
    }
}

@Composable
private fun FocusTimeGauge(session: FocusSession) {
    val progress = focusGaugeProgress(session)
    val indicatorColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val gaugeSize = (maxWidth * 0.76f).coerceIn(196.dp, 256.dp)
        val countdownFontSize = if (gaugeSize < 224.dp) 26.sp else 30.sp
        Box(
            modifier = Modifier
                .size(gaugeSize)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                    contentDescription = focusGaugeContentDescription(session.remainingMillis)
                }
                .testTag("focus_time_gauge"),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val strokeWidth = 10.dp.toPx()
                val inset = strokeWidth / 2f
                val diameter = size.minDimension - strokeWidth
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(diameter, diameter),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                if (progress > 0f) {
                    drawArc(
                        color = indicatorColor,
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(diameter, diameter),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }
            }
            Text(
                text = formatFocusCountdown(session.remainingSeconds),
                modifier = Modifier.fillMaxWidth(0.86f).testTag("focus_time_remaining"),
                textAlign = TextAlign.Center,
                fontSize = countdownFontSize,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun FocusGoalSelector(
    eligibleGoals: List<GoalsPanelEntry>,
    selectedGoalId: String?,
    onSelected: (String?) -> Unit,
) {
    if (eligibleGoals.isEmpty()) return
    val selected = eligibleGoals.firstOrNull { it.record.id == selectedGoalId }
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            modifier = Modifier.fillMaxWidth()
                .semantics { contentDescription = "Objectif de la session Focus : ${selected?.record?.title ?: "Sans objectif"}" }
                .testTag("focus_goal_selector"),
            onClick = { expanded = true },
        ) {
            Text(selected?.record?.title ?: "Sans objectif")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Sans objectif") },
                modifier = Modifier.testTag("focus_goal_none"),
                onClick = { onSelected(null); expanded = false },
            )
            eligibleGoals.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.record.title) },
                    modifier = Modifier.testTag("focus_goal_option_${entry.record.id}"),
                    onClick = { onSelected(entry.record.id); expanded = false },
                )
            }
        }
    }
}

@Composable
internal fun FocusGoalSessionProjection(controller: AtlasController, session: FocusSession) {
    val linkedGoal = controller.coachGoals.firstOrNull { session.id in it.record.linkedFocusSessionIds }
    when (session.status) {
        FocusStatus.RUNNING, FocusStatus.PAUSED -> linkedGoal?.let {
            Text(
                "Objectif lié : ${it.record.title} · ${session.workSeconds / 60} min prévues; les minutes seront comptées à la fin.",
                modifier = Modifier.testTag("focus_goal_active_projection"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FocusStatus.FINISHED -> {
            val validatedMinutes = GoalProgressCalculator.completedWorkSeconds(session) / 60L
            Text(
                "Minutes validées pour cette session : $validatedMinutes min.",
                modifier = Modifier.testTag("focus_session_validated_minutes"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            linkedGoal?.let {
                Text(
                    "Objectif lié : ${it.record.title} · progression ${it.progress.currentValue.coerceAtMost(it.record.targetValue)} / ${it.record.targetValue} min.",
                    modifier = Modifier.testTag("focus_goal_finished_projection"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> Unit
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
    val compactInputs = LocalConfiguration.current.screenWidthDp <= 360 || LocalDensity.current.fontScale >= 1.4f

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Une routine envoie une proposition. Elle ne démarre jamais un focus automatiquement; aucun usage d'app n'est lu.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (controller.capabilities[AtlasController.CAP_FOCUS_ROUTINES] != true) {
            Text("La capacité Routines est désactivée. Activez-la dans Paramètres pour planifier des propositions.")
            return@Column
        }
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nom de la routine") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (compactInputs) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text("Début · HH:mm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = end, onValueChange = { end = it }, label = { Text("Fin · HH:mm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text("Début · HH:mm") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = end, onValueChange = { end = it }, label = { Text("Fin · HH:mm") }, singleLine = true, modifier = Modifier.weight(1f))
            }
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
            AtlasRule()
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
    val compactActions = LocalConfiguration.current.screenWidthDp <= 360 || LocalDensity.current.fontScale >= 1.4f
    val timeRange = if (schedule.type == RoutineSchedule.ScheduleType.MANUAL) "Manuelle" else {
        "${schedule.time?.format(DateTimeFormatter.ofPattern("HH:mm"))}–${schedule.endTime?.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    }
    val days = if (schedule.type == RoutineSchedule.ScheduleType.WEEKLY) " · lundi–vendredi" else " · chaque jour"
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(routine.name, style = MaterialTheme.typography.titleMedium)
        Text("$timeRange$days · ${routine.focusMinutes} min")
        Text("Prochain déclenchement : ${nextTrigger ?: "aucun (désactivée ou horaire manuel)"}", style = MaterialTheme.typography.bodySmall)
        Text(if (isActive) "Plage active · action à confirmer" else if (routine.isEnabled) "En attente de sa plage" else "Routine désactivée", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Routine active")
            Switch(checked = routine.isEnabled, onCheckedChange = { controller.setFocusRoutineEnabled(routine.id, it) })
        }
        if (compactActions) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isActive && routine.isEnabled) Button(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = { controller.startFocusRoutine(routine.id) }) { Text("Démarrer") }
                OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = onEdit) { Text("Modifier") }
                OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = { controller.deleteFocusRoutine(routine.id) }) { Text("Supprimer") }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isActive && routine.isEnabled) Button(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp), onClick = { controller.startFocusRoutine(routine.id) }) { Text("Démarrer") }
                OutlinedButton(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp), onClick = onEdit) { Text("Modifier") }
                OutlinedButton(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp), onClick = { controller.deleteFocusRoutine(routine.id) }) { Text("Supprimer") }
            }
        }
    }
}
