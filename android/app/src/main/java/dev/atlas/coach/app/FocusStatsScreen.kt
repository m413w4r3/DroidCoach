/*
 * Adapted from Reef@044358fb26bf11aa85dddadf919106ca4053d926:
 * Reef/src/main/java/dev/pranav/reef/data/FocusStats.kt and screens/FocusStatsScreen.kt, MIT.
 * Uses the ATLAS persisted FocusSession history and reports only completed local focus time.
 */
package dev.atlas.coach.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.atlas.coach.focus.FocusStatus
import dev.atlas.coach.focus.ReefFocusStats
import dev.atlas.coach.focus.displayLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun FocusStatsScreen(controller: AtlasController) {
    val sessions = controller.focusHistory
    val totalFocusMillis = sessions.sumOf { ReefFocusStats.from(it).focusTimeMillis }
    val totalCycles = sessions.sumOf { ReefFocusStats.from(it).completedCycles }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Bilan local de concentration", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("${sessions.size} sessions archivées · ${totalFocusMillis / 60_000L} minutes · $totalCycles cycles terminés")
        Text("Ces statistiques restent sur cet appareil et ne reposent sur aucune observation d'applications.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sessions.isEmpty()) Text("Terminez ou annulez une session pour alimenter l'historique.")
        sessions.forEach { session ->
            val stats = ReefFocusStats.from(session)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(session.status.displayLabel(), style = MaterialTheme.typography.titleMedium)
                    Text("${stats.focusTimeMillis / 60_000L} min · ${stats.completedCycles} cycles · ${controller.directiveTitle(session.directiveId)}")
                    val startedAt = Instant.ofEpochMilli(session.createdAtUtcMillis)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.FRENCH))
                    Text("${session.workSeconds / 60L} min de concentration par phase · $startedAt", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (session.status == FocusStatus.CANCELLED) Text("Session interrompue", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = { controller.navigationState.navigate(AtlasScreenId.FOCUS) }) {
            Text("Retour au focus")
        }
    }
}
