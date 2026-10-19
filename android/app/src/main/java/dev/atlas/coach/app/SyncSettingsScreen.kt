package dev.atlas.coach.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.atlas.coach.sync.SyncDataCategory
import dev.atlas.coach.sync.SyncStatus

@Composable
fun SyncSettingsScreen(controller: AtlasController, @Suppress("UNUSED_PARAMETER") revision: Int) {
    var baseUrl by remember(controller, controller.syncBaseUrl) { mutableStateOf(controller.syncBaseUrl) }
    var token by remember { mutableStateOf("") }
    var showClearQueue by remember { mutableStateOf(false) }
    var showDisableSync by remember { mutableStateOf(false) }
    val syncEnabled = controller.capabilities[AtlasController.CAP_SYNC] == true

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Partage backend facultatif", style = MaterialTheme.typography.headlineSmall)
        Text(
            "ATLAS reste local par défaut. Aucune connexion n'est ouverte avant l'activation de Sync et le choix d'au moins une catégorie.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(modifier = Modifier.fillMaxWidth().testTag("sync_status_card")) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(statusLabel(controller), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(controller.syncStatusMessage, style = MaterialTheme.typography.bodySmall)
                Text("File locale : ${controller.syncPendingCount} événement(s)", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Activer la sync", modifier = Modifier.weight(1f))
            Switch(
                checked = syncEnabled,
                onCheckedChange = controller::setSyncEnabled,
                modifier = Modifier.testTag("sync_enabled_switch"),
            )
        }
        Text(
            "Désactiver Sync révoque les catégories et supprime les événements en attente.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Catégories partagées", style = MaterialTheme.typography.titleMedium)
        SyncCategoryRow("Focus", "Démarrages, pauses et fins; sans nom de tâche.", SyncDataCategory.FOCUS, controller)
        SyncCategoryRow("Habitudes", "Validations seulement; sans nom d'habitude.", SyncDataCategory.HABIT, controller)
        SyncCategoryRow("Bibliothèque", "Ouverture seulement; sans titre ni URL.", SyncDataCategory.CONTENT, controller)
        SyncCategoryRow("Réponses du coach", "Choix explicite; la réponse locale reste disponible.", SyncDataCategory.COACH_INTERACTION, controller)
        SyncCategoryRow("Résumé global d'usage", "Réservé; aucun résumé n'est encore produit.", SyncDataCategory.APP_USAGE_SUMMARY, controller)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = false, onCheckedChange = {}, enabled = false)
            Column(modifier = Modifier.weight(1f)) {
                Text("Détails privés d'applications")
                Text("Désactivés et jamais envoyés.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("Backend", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().testTag("sync_backend_url"),
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("URL du backend") },
            placeholder = { Text("https://serveur.example") },
            singleLine = true,
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().testTag("sync_backend_token"),
            value = token,
            onValueChange = { token = it },
            label = { Text("Jeton Bearer") },
            placeholder = { Text(if (controller.syncTokenConfigured) "Jeton enregistré · laisser vide pour le garder" else "Saisir le jeton") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Button(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("sync_save_configuration"),
            onClick = {
                controller.saveSyncConfiguration(baseUrl, token.ifBlank { null })
                token = ""
            },
        ) { Text("Enregistrer la configuration") }
        if (controller.syncTokenConfigured) {
            Text("Jeton enregistré et chiffré; sa valeur n'est pas affichée.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = controller::clearSyncToken) {
                Text("Supprimer le jeton")
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp).testTag("sync_now"),
            enabled = !controller.syncInProgress,
            onClick = controller::syncNow,
        ) { Text(if (controller.syncInProgress) "Synchronisation…" else "Synchroniser maintenant") }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
            enabled = controller.syncPendingCount > 0,
            onClick = { showClearQueue = true },
        ) { Text("Vider la file") }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("sync_disable_purge"),
            enabled = syncEnabled || controller.syncPendingCount > 0,
            onClick = { showDisableSync = true },
        ) { Text("Désactiver et purger") }
        Text(
            "Le jeton est chiffré avec Android Keystore. HTTP n'est permis qu'en debug vers 10.0.2.2; les builds release exigent HTTPS.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showClearQueue) {
        AlertDialog(
            onDismissRequest = { showClearQueue = false },
            title = { Text("Vider la file locale ?") },
            text = { Text("Les événements en attente seront supprimés de cet appareil.") },
            confirmButton = {
                TextButton(onClick = { controller.clearSyncQueue(); showClearQueue = false }) { Text("Vider") }
            },
            dismissButton = { TextButton(onClick = { showClearQueue = false }) { Text("Annuler") } },
        )
    }
    if (showDisableSync) {
        AlertDialog(
            onDismissRequest = { showDisableSync = false },
            title = { Text("Désactiver et purger ?") },
            text = { Text("Le consentement sync sera révoqué et toute la file locale supprimée.") },
            confirmButton = {
                TextButton(onClick = { controller.disableAndPurgeSync(); showDisableSync = false }) { Text("Désactiver et purger") }
            },
            dismissButton = { TextButton(onClick = { showDisableSync = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun SyncCategoryRow(
    title: String,
    description: String,
    category: SyncDataCategory,
    controller: AtlasController,
) {
    val checked = category in controller.selectedSyncCategories
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { controller.setSyncCategory(category, it) })
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun statusLabel(controller: AtlasController): String {
    if (controller.capabilities[AtlasController.CAP_SYNC] != true) return "Désactivé · LocalMock"
    if (controller.selectedSyncCategories.isEmpty()) return "Aucune catégorie consentie"
    if (controller.syncBaseUrl.isBlank()) return "Configuration du backend requise"
    if (!controller.syncTokenConfigured) return "Authentification requise"
    if (controller.syncInProgress) return "Synchronisation en cours"
    if (controller.syncPendingCount > 0 && controller.syncStatus == SyncStatus.RETRY_SCHEDULED) {
        return "Hors ligne · en attente ${controller.syncPendingCount}"
    }
    if (controller.syncPendingCount > 0) return "En attente ${controller.syncPendingCount}"
    return when (controller.syncStatus) {
        SyncStatus.SUCCESS -> "Synchronisé"
        SyncStatus.AUTH_REQUIRED -> "Authentification requise"
        SyncStatus.RETRY_SCHEDULED -> "Hors ligne · aucun événement en attente"
        SyncStatus.CONTRACT_ERROR, SyncStatus.PROTOCOL_ERROR, SyncStatus.REJECTED -> "Erreur de synchronisation"
        SyncStatus.NOTHING_TO_SYNC -> "Aucun événement à synchroniser"
        SyncStatus.LOCAL_ONLY -> "Désactivé · LocalMock"
    }
}
