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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.atlas.coach.focus.ReefPomodoroConfig

@Composable
internal fun ReefPomodoroSetup(onStart: (ReefPomodoroConfig) -> Unit) {
    var focus by remember { mutableIntStateOf(ReefPomodoroConfig.DEFAULT.focusMinutes) }
    var shortBreak by remember { mutableIntStateOf(ReefPomodoroConfig.DEFAULT.shortBreakMinutes) }
    var longBreak by remember { mutableIntStateOf(ReefPomodoroConfig.DEFAULT.longBreakMinutes) }
    var cycles by remember { mutableIntStateOf(ReefPomodoroConfig.DEFAULT.cycles) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Pomodoro personnalisé", style = MaterialTheme.typography.titleMedium)
        DurationStepper("Concentration", focus, 1, 180) { focus = it }
        DurationStepper("Pause courte", shortBreak, 1, 60) { shortBreak = it }
        DurationStepper("Pause longue", longBreak, 1, 120) { longBreak = it }
        DurationStepper("Cycles avant pause longue", cycles, 1, 12) { cycles = it }
        Button(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
            onClick = { onStart(ReefPomodoroConfig(focus, shortBreak, longBreak, cycles)) },
        ) { Text("Démarrer le Pomodoro") }
    }
}

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
