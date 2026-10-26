package dev.atlas.coach.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.atlas.coach.habits.Habit
import dev.atlas.coach.habits.HabitHistory
import java.time.LocalDate

@Composable
internal fun HabitRow(
    habit: Habit,
    history: HabitHistory,
    checkedToday: Boolean,
    strength: Double,
    today: LocalDate,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val todayState = history.days.firstOrNull { it.date == today }?.uiState(today)
    Card(modifier = Modifier.fillMaxWidth().testTag("habit_row_${habit.id}")) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(habit.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = when {
                        checkedToday -> "Validée aujourd'hui"
                        todayState == HabitDayUiState.NOT_PLANNED -> "Pas prévue aujourd'hui"
                        else -> "À faire aujourd'hui"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("habit_check_${habit.id}").semantics {
                        contentDescription = if (checkedToday) {
                            "Annuler la validation de ${habit.name} aujourd'hui"
                        } else {
                            "Valider ${habit.name} aujourd'hui"
                        }
                    },
                    onClick = onToggle,
                ) { Text(if (checkedToday) "Annuler" else "Valider") }
            }

            HabitHistoryGrid(history = history, today = today, compact = true, createdDate = habit.createdDate())

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Régularité · ${(strength.coerceIn(0.0, 1.0) * 100).toInt()} %",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("habit_open_${habit.id}"),
                    onClick = onOpen,
                ) { Text("Détail") }
            }
        }
    }
}
