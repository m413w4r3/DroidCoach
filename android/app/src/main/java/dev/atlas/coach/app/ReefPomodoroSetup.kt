/*
 * Adapted from Reef@044358fb26bf11aa85dddadf919106ca4053d926:
 * Reef/src/main/java/dev/pranav/reef/timer/TimerScreen.kt, MIT.
 * Keeps Reef's duration-and-cycle setup pattern in Compose; strict mode and any blocker hooks are omitted.
 */
package dev.atlas.coach.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.atlas.coach.focus.ReefPomodoroConfig

@Composable
internal fun ReefPomodoroSetup(
    onStart: (ReefPomodoroConfig) -> Unit,
    initialConfig: ReefPomodoroConfig = ReefPomodoroConfig.DEFAULT,
    expandedInitially: Boolean = false,
) {
    var focus by remember(initialConfig) { mutableIntStateOf(initialConfig.focusMinutes) }
    var shortBreak by remember(initialConfig) { mutableIntStateOf(initialConfig.shortBreakMinutes) }
    var longBreak by remember(initialConfig) { mutableIntStateOf(initialConfig.longBreakMinutes) }
    var longBreakEvery by remember(initialConfig) { mutableIntStateOf(initialConfig.cycles) }
    var totalCycles by remember(initialConfig) { mutableIntStateOf(initialConfig.totalWorkCycles) }
    var continuous by remember(initialConfig) { mutableStateOf(initialConfig.continuous) }
    var customize by remember(expandedInitially, initialConfig) { mutableStateOf(expandedInitially) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Session proposée", style = MaterialTheme.typography.titleMedium)
        Text("$focus min de concentration · pause $shortBreak min · $totalCycles cycles")
        Button(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp).testTag("focus_pomodoro_start"),
            onClick = { onStart(currentPomodoroConfig(focus, shortBreak, longBreak, longBreakEvery, totalCycles, continuous)) },
        ) { Text("Démarrer") }
        TextButton(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
            onClick = { customize = !customize },
        ) { Text(if (customize) "Masquer la personnalisation" else "Personnaliser") }

        if (customize) {
            Text("Réglages avancés", style = MaterialTheme.typography.titleSmall)
            DurationStepper("Concentration", focus, 1, 180) { focus = it }
            DurationStepper("Pause courte", shortBreak, 1, 60) { shortBreak = it }
            DurationStepper("Pause longue", longBreak, 1, 120) { longBreak = it }
            DurationStepper("Cycles avant la pause longue", longBreakEvery, 1, 12) { longBreakEvery = it }
            DurationStepper("Cycles de travail au total", totalCycles, 1, 100) { totalCycles = it }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Mode continu")
                    Text("Sans arrêt automatique après un nombre fixe de cycles.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = continuous, onCheckedChange = { continuous = it })
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                onClick = { onStart(currentPomodoroConfig(focus, shortBreak, longBreak, longBreakEvery, totalCycles, continuous)) },
            ) { Text("Démarrer cette session") }
        }
    }
}

private fun currentPomodoroConfig(
    focus: Int,
    shortBreak: Int,
    longBreak: Int,
    longBreakEvery: Int,
    totalCycles: Int,
    continuous: Boolean,
): ReefPomodoroConfig = ReefPomodoroConfig(focus, shortBreak, longBreak, longBreakEvery, totalCycles, continuous)

@Composable
private fun DurationStepper(label: String, value: Int, minimum: Int, maximum: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            enabled = value > minimum,
            onClick = { onChange(value - 1) },
        ) { Text("−") }
        Text(value.toString(), modifier = Modifier.sizeIn(minWidth = 32.dp), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            enabled = value < maximum,
            onClick = { onChange(value + 1) },
        ) { Text("+") }
    }
}
