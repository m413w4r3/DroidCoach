package dev.atlas.coach.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Coarse display projection only; raw coordinates never enter this UI contract. */
data class LocationPanelState(
    val coarseRequestConsent: Boolean = false,
    val coachSummaryConsent: Boolean = false,
    val mobilityConsent: Boolean = false,
    val mobilitySessionActive: Boolean = false,
    val coarseSummaryLabel: String? = null,
    val measuredAtLabel: String? = null,
    val statusMessage: String? = null,
)

/**
 * S6 integration surface. Consent, one-shot coarse capture, summary sharing, and temporary
 * mobility each have separate callbacks. The caller owns permission and service handling.
 */
@Composable
fun LocationPanel(
    state: LocationPanelState,
    onCoarseRequestConsentChanged: (Boolean) -> Unit,
    onCoachSummaryConsentChanged: (Boolean) -> Unit,
    onMobilityConsentChanged: (Boolean) -> Unit,
    onRequestCoarsePosition: () -> Unit,
    onStartMobility: () -> Unit,
    onStopMobility: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Localisation", style = MaterialTheme.typography.titleLarge)
        Text("Une position approximative n'est demandée qu'après votre action. Aucun point précis n'est affiché ici.")

        ConsentRow(
            checked = state.coarseRequestConsent,
            label = "Autoriser une demande ponctuelle de position approximative",
            onCheckedChange = onCoarseRequestConsentChanged,
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            enabled = state.coarseRequestConsent,
            onClick = onRequestCoarsePosition,
        ) { Text("Demander ma position approximative") }

        Text(state.coarseSummaryLabel ?: "Aucun résumé approximatif disponible.")
        state.measuredAtLabel?.let { Text("Mesure : $it", style = MaterialTheme.typography.bodySmall) }
        ConsentRow(
            checked = state.coachSummaryConsent,
            label = "Autoriser séparément le partage du résumé avec le Coach",
            onCheckedChange = onCoachSummaryConsentChanged,
        )

        Text("Suivi de déplacement temporaire", style = MaterialTheme.typography.titleMedium)
        Text("Le suivi démarre sur demande, reste visible et peut durer au plus une heure.")
        ConsentRow(
            checked = state.mobilityConsent,
            label = "Autoriser une session temporaire de déplacement",
            onCheckedChange = onMobilityConsentChanged,
        )
        if (state.mobilitySessionActive) {
            Button(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                onClick = onStopMobility,
            ) { Text("Arrêter le suivi") }
        } else {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = state.mobilityConsent,
                onClick = onStartMobility,
            ) { Text("Démarrer le suivi temporaire") }
        }
        state.statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ConsentRow(checked: Boolean, label: String, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, modifier = Modifier.weight(1f))
    }
}
