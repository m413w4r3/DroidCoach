package dev.atlas.coach.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.atlas.coach.habits.Habit
import dev.atlas.coach.habits.HabitHistory
import java.time.LocalDate

@Composable
internal fun HabitDetailScreen(
    habit: Habit,
    history: HabitHistory,
    historyDays: Int,
    checkedToday: Boolean,
    strength: Double,
    today: LocalDate,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onHistoryDaysChanged: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(modifier = Modifier.sizeIn(minHeight = 48.dp), onClick = onBack) { Text("← Mes habitudes") }
        Text(habit.name, style = MaterialTheme.typography.headlineSmall)
        Text(habit.schedule.frequencyLabel(), style = MaterialTheme.typography.titleMedium)
        Text(
            when {
                checkedToday -> "Validée aujourd'hui"
                history.days.firstOrNull { it.date == today }?.isDue == true -> "À faire aujourd'hui"
                else -> "Pas prévue aujourd'hui"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("habit_detail_check"),
            onClick = onToggle,
        ) { Text(if (checkedToday) "Annuler la validation d'aujourd'hui" else "Valider aujourd'hui") }

        Text("Progression réelle", style = MaterialTheme.typography.titleMedium)
        val progress = HabitProgress.from(history)
        Text(
            if (progress.dueDays == 0) {
                "Aucun jour prévu sur cette période."
            } else {
                "${progress.completedDueDays} sur ${progress.dueDays} jours prévus validés · ${progress.completionPercent} %"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Text("Série actuelle · ${progress.currentStreak} échéances")
        Text("Meilleure série · ${progress.bestStreak} échéances")
        Text("Régularité · ${(strength.coerceIn(0.0, 1.0) * 100).toInt()} %", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            habit.reminder?.takeIf { it.enabled }?.let { reminder ->
                "Rappel actif · %02d:%02d".format(reminder.hour, reminder.minute)
            } ?: "Rappel désactivé",
            style = MaterialTheme.typography.bodyMedium,
        )

        Text("Historique", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterButton("7 jours", historyDays == 7) { onHistoryDaysChanged(7) }
            FilterButton("30 jours", historyDays == 30) { onHistoryDaysChanged(30) }
        }
        HabitHistoryGrid(history = history, today = today, createdDate = habit.createdDate())

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp).testTag("habit_edit"),
                onClick = onEdit,
            ) { Text("Modifier") }
            Button(
                modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp).testTag("habit_archive"),
                onClick = onArchive,
            ) { Text("Archiver") }
        }
        Text(
            "Archiver retire l'habitude de la liste active et conserve son historique local.",
            modifier = Modifier.padding(bottom = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
