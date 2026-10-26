package dev.atlas.coach.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.atlas.coach.habits.Habit
import dev.atlas.coach.habits.HabitHistory
import dev.atlas.coach.habits.HabitHistoryDay
import dev.atlas.coach.habits.HabitSchedule
import dev.atlas.coach.habits.HabitScheduleKind
import java.time.DayOfWeek
import java.time.LocalDate

internal enum class HabitDayUiState { DONE, MISSED, NOT_PLANNED, BEFORE_START, DUE_TODAY, SCHEDULED }

internal fun HabitHistoryDay.uiState(today: LocalDate, createdDate: LocalDate? = null): HabitDayUiState = when {
    isCheckedIn -> HabitDayUiState.DONE
    createdDate != null && date.isBefore(createdDate) -> HabitDayUiState.BEFORE_START
    !isDue -> HabitDayUiState.NOT_PLANNED
    date.isBefore(today) -> HabitDayUiState.MISSED
    date.isAfter(today) -> HabitDayUiState.SCHEDULED
    else -> HabitDayUiState.DUE_TODAY
}

internal data class HabitProgress(
    val completedDueDays: Int,
    val dueDays: Int,
    val completionRate: Double,
    val currentStreak: Int,
    val bestStreak: Int,
) {
    val completionPercent: Int get() = (completionRate * 100).toInt()

    companion object {
        fun from(history: HabitHistory) = HabitProgress(
            completedDueDays = history.completedDueDays,
            dueDays = history.dueDays,
            completionRate = history.completionRate,
            currentStreak = history.currentStreak,
            bestStreak = history.bestStreak,
        )
    }
}

internal fun sortHabitsForToday(
    habits: List<Habit>,
    histories: Map<String, HabitHistory>,
    today: LocalDate,
): List<Habit> = habits.sortedBy { habit ->
    val todayEntry = histories[habit.id]?.days?.firstOrNull { it.date == today }
    todayEntry?.isDue != true
}

internal enum class HabitListState { EMPTY, WITH_HABITS }

internal fun habitListState(habits: List<Habit>): HabitListState =
    if (habits.isEmpty()) HabitListState.EMPTY else HabitListState.WITH_HABITS

internal fun HabitSchedule.frequencyLabel(): String = when (kind) {
    HabitScheduleKind.DAILY -> "Chaque jour"
    HabitScheduleKind.WEEKDAYS -> {
        val days = DayOfWeek.entries.filter { weekdaysMask and (1 shl (it.value - 1)) != 0 }
        "Les ${days.joinToString(", ") { it.shortLabel() }}"
    }
    HabitScheduleKind.TIMES_PER_WEEK -> "$timesPerWeek fois par semaine"
    HabitScheduleKind.ALTERNATING_DAYS -> "Un jour sur deux · depuis ${anchorDateEpochDay?.let(LocalDate::ofEpochDay)}"
}

internal fun DayOfWeek.shortLabel(): String = when (this) {
    DayOfWeek.MONDAY -> "Lun"
    DayOfWeek.TUESDAY -> "Mar"
    DayOfWeek.WEDNESDAY -> "Mer"
    DayOfWeek.THURSDAY -> "Jeu"
    DayOfWeek.FRIDAY -> "Ven"
    DayOfWeek.SATURDAY -> "Sam"
    DayOfWeek.SUNDAY -> "Dim"
}

@Composable
internal fun HabitsScreen(
    controller: AtlasController,
    @Suppress("UNUSED_PARAMETER") revision: Int,
    onExport: () -> Unit,
) {
    var space by remember { mutableStateOf(HabitSpace.LIST) }
    var selectedHabitId by remember { mutableStateOf<String?>(null) }
    var editingHabit by remember { mutableStateOf<Habit?>(null) }
    var editorReturnsToDetail by remember { mutableStateOf(false) }
    var habitToArchive by remember { mutableStateOf<Habit?>(null) }
    val today = LocalDate.now()
    val habits = controller.activeHabits
    val checkedIds = controller.checkedHabitIds

    BackHandler(enabled = space != HabitSpace.LIST) {
        if (space == HabitSpace.EDITOR) {
            space = if (editorReturnsToDetail) HabitSpace.DETAIL else HabitSpace.LIST
            editingHabit = null
        } else {
            space = HabitSpace.LIST
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (controller.capabilities[AtlasController.CAP_HABITS] != true) {
            Text("Habitudes désactivées dans les paramètres.")
            return@Column
        }

        when (space) {
            HabitSpace.LIST -> {
                val histories = habits.associate { habit ->
                    habit.id to controller.habits.history(habit.id, today, 7)
                }
                val orderedHabits = sortHabitsForToday(habits, histories, today)
                val dueToday = histories.values.count { history -> history.days.firstOrNull { it.date == today }?.isDue == true }
                val completedToday = orderedHabits.count { habit ->
                    habit.id in checkedIds && histories[habit.id]?.days?.firstOrNull { it.date == today }?.isDue == true
                }

                Text("Mes habitudes", style = MaterialTheme.typography.headlineSmall)
                Text("$completedToday sur $dueToday validées aujourd'hui", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("habits_add"),
                        onClick = {
                            editingHabit = null
                            editorReturnsToDetail = false
                            space = HabitSpace.EDITOR
                        },
                    ) { Text("Ajouter une habitude") }
                    TextButton(
                        modifier = Modifier.sizeIn(minHeight = 48.dp),
                        onClick = onExport,
                    ) { Text("Exporter CSV") }
                }

                if (habitListState(orderedHabits) == HabitListState.EMPTY) {
                    Text("Aucune habitude pour le moment.")
                    Button(
                        modifier = Modifier.sizeIn(minHeight = 48.dp),
                        onClick = {
                            editingHabit = null
                            editorReturnsToDetail = false
                            space = HabitSpace.EDITOR
                        },
                    ) { Text("Ajouter une habitude") }
                } else {
                    orderedHabits.forEach { habit ->
                        val history = requireNotNull(histories[habit.id])
                        HabitRow(
                            habit = habit,
                            history = history,
                            checkedToday = habit.id in checkedIds,
                            strength = controller.habits.strength(habit.id, today),
                            today = today,
                            onToggle = { controller.toggleHabitToday(habit) },
                            onOpen = {
                                controller.changeHistoryDays(7)
                                selectedHabitId = habit.id
                                space = HabitSpace.DETAIL
                            },
                        )
                    }
                }
            }

            HabitSpace.DETAIL -> {
                val habit = habits.firstOrNull { it.id == selectedHabitId }
                if (habit == null) {
                    selectedHabitId = null
                    space = HabitSpace.LIST
                } else {
                    HabitDetailScreen(
                        habit = habit,
                        history = controller.habits.history(habit.id, today, controller.historyDays),
                        historyDays = controller.historyDays,
                        checkedToday = habit.id in checkedIds,
                        strength = controller.habits.strength(habit.id, today),
                        today = today,
                        onBack = { space = HabitSpace.LIST },
                        onToggle = { controller.toggleHabitToday(habit) },
                        onEdit = {
                            editingHabit = habit
                            editorReturnsToDetail = true
                            space = HabitSpace.EDITOR
                        },
                        onArchive = { habitToArchive = habit },
                        onHistoryDaysChanged = controller::changeHistoryDays,
                    )
                }
            }

            HabitSpace.EDITOR -> HabitEditor(
                habit = editingHabit,
                remindersEnabled = controller.capabilities[AtlasController.CAP_HABIT_REMINDERS] == true,
                onCancel = {
                    space = if (editorReturnsToDetail) HabitSpace.DETAIL else HabitSpace.LIST
                    editingHabit = null
                },
                onSave = { name, schedule, reminderChange ->
                    val current = editingHabit
                    if (current == null) {
                        controller.addHabit(name, schedule, reminderChange.reminder)
                    } else {
                        controller.updateHabit(current.id, name, schedule)
                        if (reminderChange.changed) controller.setHabitReminder(current.id, reminderChange.reminder)
                    }
                    space = if (editorReturnsToDetail) HabitSpace.DETAIL else HabitSpace.LIST
                    editingHabit = null
                },
            )
        }
    }

    habitToArchive?.let { habit ->
        AlertDialog(
            onDismissRequest = { habitToArchive = null },
            title = { Text("Archiver ${habit.name} ?") },
            text = { Text("Cette habitude quittera la liste active. Son historique local sera conservé.") },
            confirmButton = {
                TextButton(
                    modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("habit_archive_confirm"),
                    onClick = {
                        controller.archiveHabit(habit.id)
                        habitToArchive = null
                        selectedHabitId = null
                        space = HabitSpace.LIST
                    },
                ) { Text("Archiver") }
            },
            dismissButton = {
                TextButton(modifier = Modifier.sizeIn(minHeight = 48.dp), onClick = { habitToArchive = null }) {
                    Text("Annuler")
                }
            },
        )
    }
}

private enum class HabitSpace { LIST, DETAIL, EDITOR }
