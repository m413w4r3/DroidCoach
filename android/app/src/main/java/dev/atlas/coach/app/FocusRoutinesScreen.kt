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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.atlas.coach.routines.FocusRoutine
import dev.atlas.coach.routines.RoutineSchedule
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

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
        Text("Routines de focus", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
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
