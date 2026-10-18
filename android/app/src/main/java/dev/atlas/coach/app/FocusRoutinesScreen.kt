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
    var weekdaysOnly by remember { mutableStateOf(false) }
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
        Text("Une routine ne lance rien automatiquement. Elle propose un focus pendant une plage horaire; aucun usage d'application n'est lu.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nom de la routine") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text("Début · HH:mm") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = end, onValueChange = { end = it }, label = { Text("Fin · HH:mm") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Du lundi au vendredi")
            Switch(checked = weekdaysOnly, onCheckedChange = { weekdaysOnly = it })
        }
        Button(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
            onClick = { if (controller.addFocusRoutine(name, start, end, weekdaysOnly)) name = "" },
        ) { Text("Ajouter la routine") }
        if (controller.focusRoutines.isEmpty()) Text("Aucune routine enregistrée.")
        controller.focusRoutines.forEach { routine -> RoutineCard(routine, routine.id in activeIds, controller) }
    }
}

@Composable
private fun RoutineCard(routine: FocusRoutine, isActive: Boolean, controller: AtlasController) {
    val schedule = routine.schedule
    val timeRange = if (schedule.type == RoutineSchedule.ScheduleType.MANUAL) "Manuelle" else {
        "${schedule.time?.format(DateTimeFormatter.ofPattern("HH:mm"))}–${schedule.endTime?.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    }
    val days = if (schedule.type == RoutineSchedule.ScheduleType.WEEKLY) " · lundi–vendredi" else " · chaque jour"
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(routine.name, style = MaterialTheme.typography.titleMedium)
            Text("$timeRange$days · ${routine.focusMinutes} min")
            Text(if (isActive) "Plage active · action à confirmer" else "En dehors de la plage", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isActive) Button(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp), onClick = { controller.startFocusRoutine(routine.id) }) { Text("Commencer") }
                OutlinedButton(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp), onClick = { controller.deleteFocusRoutine(routine.id) }) { Text("Supprimer") }
            }
        }
    }
}
