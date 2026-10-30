package dev.atlas.coach.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.atlas.coach.focus.FocusPlan
import dev.atlas.coach.focus.ReefPomodoroConfig
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PomodoroSessionSetup(
    initialTaskTitle: String,
    initialConfig: ReefPomodoroConfig = ReefPomodoroConfig.DEFAULT,
    onStart: (taskTitle: String, config: ReefPomodoroConfig) -> Unit,
) {
    var taskTitle by remember(initialTaskTitle) { mutableStateOf(initialTaskTitle.take(FocusPlan.MAX_TASK_TITLE_LENGTH)) }
    var focus by remember(initialConfig) { mutableIntStateOf(initialConfig.focusMinutes) }
    var shortBreak by remember(initialConfig) { mutableIntStateOf(initialConfig.shortBreakMinutes) }
    var longBreak by remember(initialConfig) { mutableIntStateOf(initialConfig.longBreakMinutes) }
    var longBreakEvery by remember(initialConfig) { mutableIntStateOf(initialConfig.cycles) }
    var totalCycles by remember(initialConfig) { mutableIntStateOf(initialConfig.totalWorkCycles) }
    var continuous by remember(initialConfig) { mutableStateOf(initialConfig.continuous) }
    var showAdvanced by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Préparer le focus", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = taskTitle,
            onValueChange = { taskTitle = it.take(FocusPlan.MAX_TASK_TITLE_LENGTH) },
            modifier = Modifier.fillMaxWidth().testTag("focus_task_title"),
            label = { Text("Tâche") },
            singleLine = true,
        )
        PomodoroStepper(
            label = "Durée de concentration",
            value = focus,
            minimum = 1,
            maximum = 180,
            tagPrefix = "focus_duration",
            onChange = { focus = it },
        )
        Text(
            "$shortBreak min de pause · $totalCycles cycles${if (continuous) " · continu" else ""}",
            modifier = Modifier.testTag("focus_advanced_summary"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("focus_advanced_settings"),
            onClick = { showAdvanced = true },
        ) { Text("Réglages avancés") }
        Button(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp).testTag("focus_pomodoro_start"),
            enabled = taskTitle.isNotBlank(),
            onClick = { onStart(taskTitle.trim(), currentConfig(focus, shortBreak, longBreak, longBreakEvery, totalCycles, continuous)) },
        ) { Text("Démarrer") }
    }

    if (showAdvanced) {
        ModalBottomSheet(
            onDismissRequest = { showAdvanced = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)
                    .testTag("focus_advanced_sheet"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Réglages avancés", style = MaterialTheme.typography.titleLarge)
                PomodoroStepper("Pause courte", shortBreak, 1, 60, "focus_short_break") { shortBreak = it }
                PomodoroStepper("Pause longue", longBreak, 1, 120, "focus_long_break") { longBreak = it }
                PomodoroStepper("Cycles avant la pause longue", longBreakEvery, 1, 12, "focus_long_break_every") { longBreakEvery = it }
                PomodoroStepper("Cycles de travail", totalCycles, 1, 100, "focus_total_cycles") { totalCycles = it }
                Row(
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Mode continu")
                        Text("Sans arrêt après un nombre fixe de cycles.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        modifier = Modifier.testTag("focus_continuous_switch")
                            .semantics { contentDescription = "Activer le mode continu" },
                        checked = continuous,
                        onCheckedChange = { continuous = it },
                    )
                }
                TextButton(
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("focus_advanced_done"),
                    onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion { showAdvanced = false }
                    },
                ) { Text("Terminer les réglages") }
            }
        }
    }
}

@Composable
private fun PomodoroStepper(
    label: String,
    value: Int,
    minimum: Int,
    maximum: Int,
    tagPrefix: String,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("${tagPrefix}_minus"),
            enabled = value > minimum,
            onClick = { onChange(value - 1) },
        ) { Text("−") }
        Text(value.toString(), modifier = Modifier.sizeIn(minWidth = 32.dp).testTag("${tagPrefix}_value"), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("${tagPrefix}_plus"),
            enabled = value < maximum,
            onClick = { onChange(value + 1) },
        ) { Text("+") }
    }
}

private fun currentConfig(
    focus: Int,
    shortBreak: Int,
    longBreak: Int,
    longBreakEvery: Int,
    totalCycles: Int,
    continuous: Boolean,
) = ReefPomodoroConfig(focus, shortBreak, longBreak, longBreakEvery, totalCycles, continuous)
