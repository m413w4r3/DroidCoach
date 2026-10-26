package dev.atlas.coach.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.atlas.coach.habits.Habit
import dev.atlas.coach.habits.HabitReminder
import dev.atlas.coach.habits.HabitSchedule
import dev.atlas.coach.habits.HabitScheduleKind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

internal data class HabitReminderChange(val changed: Boolean, val reminder: HabitReminder?)

@Composable
internal fun HabitEditor(
    habit: Habit?,
    remindersEnabled: Boolean,
    onCancel: () -> Unit,
    onSave: (String, HabitSchedule, HabitReminderChange) -> Unit,
) {
    val defaultDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    var name by remember(habit?.id) { mutableStateOf(habit?.name.orEmpty()) }
    var scheduleKind by remember(habit?.id) { mutableStateOf(habit?.schedule?.kind ?: HabitScheduleKind.DAILY) }
    var selectedDays by remember(habit?.id) {
        mutableStateOf(
            habit?.schedule?.weekdaysMask?.let { mask ->
                DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.toSet()
            }?.takeIf { it.isNotEmpty() } ?: defaultDays,
        )
    }
    var weeklyQuota by remember(habit?.id) { mutableStateOf(habit?.schedule?.timesPerWeek?.coerceIn(1, 7) ?: 3) }
    var anchorDate by remember(habit?.id) {
        mutableStateOf(habit?.schedule?.anchorDateEpochDay?.let(LocalDate::ofEpochDay)?.toString() ?: LocalDate.now().toString())
    }
    var reminderEnabled by remember(habit?.id) { mutableStateOf(habit?.reminder?.enabled == true) }
    var reminderTime by remember(habit?.id) {
        mutableStateOf(habit?.reminder?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "09:00")
    }
    var reminderDays by remember(habit?.id) { mutableStateOf(habit?.reminder?.days ?: defaultDays) }
    var reminderEdited by remember(habit?.id) { mutableStateOf(false) }
    var errorMessage by remember(habit?.id) { mutableStateOf<String?>(null) }

    fun save() {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            errorMessage = "Le nom de l'habitude est obligatoire."
            return
        }
        val schedule = try {
            when (scheduleKind) {
                HabitScheduleKind.DAILY -> HabitSchedule.daily()
                HabitScheduleKind.WEEKDAYS -> HabitSchedule.weekdays(selectedDays)
                HabitScheduleKind.TIMES_PER_WEEK -> HabitSchedule.timesPerWeek(weeklyQuota)
                HabitScheduleKind.ALTERNATING_DAYS -> HabitSchedule.alternatingDays(LocalDate.parse(anchorDate.trim()))
            }
        } catch (_: Exception) {
            errorMessage = when (scheduleKind) {
                HabitScheduleKind.WEEKDAYS -> "Sélectionnez au moins un jour."
                HabitScheduleKind.ALTERNATING_DAYS -> "Saisissez une date valide au format AAAA-MM-JJ."
                else -> "Vérifiez la fréquence choisie."
            }
            return
        }
        val reminder = if (reminderEnabled) {
            val time = runCatching { LocalTime.parse(reminderTime.trim()) }.getOrElse {
                errorMessage = "Saisissez une heure valide au format HH:mm."
                return
            }
            if (reminderDays.isEmpty()) {
                errorMessage = "Sélectionnez au moins un jour de rappel."
                return
            }
            HabitReminder(time.hour, time.minute, reminderDays)
        } else {
            null
        }
        errorMessage = null
        onSave(cleanName, schedule, HabitReminderChange(changed = reminderEdited, reminder = reminder))
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(4.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(if (habit == null) "Ajouter une habitude" else "Modifier l'habitude", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().testTag("habit_name"),
                    value = name,
                    onValueChange = { name = it; errorMessage = null },
                    label = { Text("Nom") },
                    singleLine = true,
                )

                Text("Fréquence", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterButton("Chaque jour", scheduleKind == HabitScheduleKind.DAILY) { scheduleKind = HabitScheduleKind.DAILY }
                    FilterButton("Jours choisis", scheduleKind == HabitScheduleKind.WEEKDAYS) { scheduleKind = HabitScheduleKind.WEEKDAYS }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterButton("Quota hebdo", scheduleKind == HabitScheduleKind.TIMES_PER_WEEK) { scheduleKind = HabitScheduleKind.TIMES_PER_WEEK }
                    FilterButton("Alternance", scheduleKind == HabitScheduleKind.ALTERNATING_DAYS) { scheduleKind = HabitScheduleKind.ALTERNATING_DAYS }
                }

                if (scheduleKind == HabitScheduleKind.WEEKDAYS) {
                    Text("Jours prévus", style = MaterialTheme.typography.titleSmall)
                    DayCheckboxes(days = selectedDays, onToggle = { day ->
                        selectedDays = selectedDays.toggle(day)
                        errorMessage = null
                    })
                }

                if (scheduleKind == HabitScheduleKind.TIMES_PER_WEEK) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Objectif par semaine", modifier = Modifier.weight(1f))
                        OutlinedButton(modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp), onClick = { weeklyQuota = (weeklyQuota - 1).coerceAtLeast(1) }) { Text("−") }
                        Text(weeklyQuota.toString(), style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp), onClick = { weeklyQuota = (weeklyQuota + 1).coerceAtMost(7) }) { Text("+") }
                    }
                    Text("Semaine locale, du lundi au dimanche.", style = MaterialTheme.typography.bodySmall)
                }

                if (scheduleKind == HabitScheduleKind.ALTERNATING_DAYS) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth().testTag("habit_anchor_date"),
                        value = anchorDate,
                        onValueChange = { anchorDate = it; errorMessage = null },
                        label = { Text("Date d'ancrage · AAAA-MM-JJ") },
                        singleLine = true,
                    )
                    Text("Une échéance tous les deux jours à partir de cette date.", style = MaterialTheme.typography.bodySmall)
                }

                Row(
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = reminderEnabled,
                        onCheckedChange = {
                            reminderEnabled = it
                            reminderEdited = true
                            errorMessage = null
                        },
                    )
                    Text("Rappel facultatif", modifier = Modifier.weight(1f))
                }
                if (reminderEnabled) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth().testTag("habit_reminder_time"),
                        value = reminderTime,
                        onValueChange = { reminderTime = it; reminderEdited = true; errorMessage = null },
                        label = { Text("Heure locale · HH:mm") },
                        singleLine = true,
                    )
                    Text("Jours de rappel", style = MaterialTheme.typography.titleSmall)
                    DayCheckboxes(days = reminderDays, onToggle = { day ->
                        reminderDays = reminderDays.toggle(day)
                        reminderEdited = true
                        errorMessage = null
                    })
                    if (!remindersEnabled) {
                        Text(
                            "Le rappel sera enregistré, mais les notifications d'habitudes sont désactivées dans les paramètres.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("habit_form_error")) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp), onClick = onCancel) {
                        Text("Annuler")
                    }
                    Button(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp).testTag("habit_save"), onClick = ::save) {
                        Text("Enregistrer")
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCheckboxes(days: Set<DayOfWeek>, onToggle: (DayOfWeek) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        DayOfWeek.entries.forEach { day ->
            Row(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("habit_day_${day.name.lowercase()}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    modifier = Modifier.semantics { contentDescription = "${day.fullLabel()} sélectionné pour cette fréquence" },
                    checked = day in days,
                    onCheckedChange = { onToggle(day) },
                )
                Text(day.fullLabel(), modifier = Modifier.weight(1f))
                Text(day.shortLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun DayOfWeek.fullLabel(): String = when (this) {
    DayOfWeek.MONDAY -> "Lundi"
    DayOfWeek.TUESDAY -> "Mardi"
    DayOfWeek.WEDNESDAY -> "Mercredi"
    DayOfWeek.THURSDAY -> "Jeudi"
    DayOfWeek.FRIDAY -> "Vendredi"
    DayOfWeek.SATURDAY -> "Samedi"
    DayOfWeek.SUNDAY -> "Dimanche"
}

private fun Set<DayOfWeek>.toggle(day: DayOfWeek): Set<DayOfWeek> =
    if (day in this) this - day else this + day
