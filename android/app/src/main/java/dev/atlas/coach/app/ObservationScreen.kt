package dev.atlas.coach.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ObservationScreen(controller: AtlasController, context: Context, requestNotifications: () -> Unit) {
    var selected by remember { mutableStateOf(controller.selectedUsagePackages) }
    var showExport by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("État réel d'Accès à l'utilisation : ${controller.usagePermissionStatusText()}")
        Text("Notifications de friction : ${if (controller.notificationPermissionGranted()) "autorisées" else "non autorisées ou désactivées"}")
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
            onClick = requestNotifications,
        ) { Text("Autoriser les notifications de friction…") }
        Text(
            "Finalité : ${dev.atlas.coach.usage.ObservationPurpose.GENTLE_FRICTION.label}. " +
                "ATLAS lit seulement les transitions d'apps au premier plan pendant que cette Activity est visible. " +
                "Aucun texte d'écran, contenu de notification, URL consultée ou compte n'est lu.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Sélectionnez les applications à inclure dans l'allowlist :", style = MaterialTheme.typography.titleMedium)
        if (controller.selectableUsageApps.isEmpty()) {
            Text("Aucune application lançable admissible n'a été trouvée.")
        }
        controller.selectableUsageApps.forEach { app ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(
                    checked = app.packageName in selected,
                    onCheckedChange = { checked ->
                        selected = if (checked) selected + app.packageName else selected - app.packageName
                    },
                )
                Column(modifier = Modifier.weight(1f).padding(top = 10.dp)) {
                    Text(app.label)
                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("Apps système, HOME, téléphone, services de sécurité et ATLAS sont exclus par une denylist non modifiable.", style = MaterialTheme.typography.titleSmall)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                controller.protectedUsagePackages.sorted().forEach { packageName ->
                    Text("Exclue · $packageName", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("Allowlist active : ${if (controller.usageConsentActive) controller.selectedUsagePackages.sorted().joinToString() else "aucune"}")
        Text("Rétention : ${controller.usageRetentionDescription} · historique actuel : ${controller.usageHistoryCount} événement(s).", style = MaterialTheme.typography.bodySmall)
        Button(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp),
            enabled = selected.isNotEmpty(),
            onClick = {
                val activated = controller.activateUsageObservation(selected)
                if (!activated && !controller.usagePermissionGranted) {
                    runCatching { context.startActivity(controller.usageAccessSettingsIntent()) }
                        .onFailure { controller.reportMessage("Impossible d'ouvrir les réglages Accès à l'utilisation.") }
                }
            },
        ) { Text(if (controller.usageConsentActive) "Mettre à jour l'allowlist" else "Donner mon consentement et continuer") }
        if (!controller.usagePermissionGranted) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                onClick = {
                    runCatching { context.startActivity(controller.usageAccessSettingsIntent()) }
                        .onFailure { controller.reportMessage("Impossible d'ouvrir les réglages Accès à l'utilisation.") }
                },
            ) { Text("Ouvrir Accès à l'utilisation dans Android") }
        }
        if (controller.usageConsentActive) {
            OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = { showExport = true }) {
                Text("Exporter l'historique local (CSV)")
            }
            OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = controller::deleteUsageHistory) {
                Text("Supprimer l'historique")
            }
            Button(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp), onClick = { controller.revokeUsageObservation() }) {
                Text("Révoquer le consentement et tout purger")
            }
        } else {
            OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = controller::deleteUsageHistory) {
                Text("Supprimer les données d'observation")
            }
        }
        Text(
            "La détection est un meilleur effort avec un polling de 10 s; Android peut retarder ou omettre des événements. " +
                "Cette fonction ne bloque jamais l'application ouverte.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showExport) {
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("Export local des ouvertures observées") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(controller.exportUsageHistoryCsv(), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showExport = false }) { Text("Fermer") } },
        )
    }
}
