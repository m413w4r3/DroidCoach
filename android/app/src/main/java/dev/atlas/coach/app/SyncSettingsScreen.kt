package dev.atlas.coach.app

import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.atlas.coach.app.ui.AtlasRule
import dev.atlas.coach.app.ui.AtlasSecondaryButton
import dev.atlas.coach.app.ui.AtlasSection
import dev.atlas.coach.sync.AndroidSyncStorage
import dev.atlas.coach.sync.SyncDataCategory
import dev.atlas.coach.sync.v4.CoreV4Category
import dev.atlas.coach.sync.v4.CoreV4ConsentState
import dev.atlas.coach.sync.v4.CoreV4SyncScheduler

@Composable
fun SyncSettingsScreen(controller: AtlasController, @Suppress("UNUSED_PARAMETER") revision: Int) {
    val context = LocalContext.current.applicationContext
    val v4Store = remember(context) { AndroidSyncStorage.v4(context) }
    val v4WorkManager = remember(context) { WorkManager.getInstance(context) }
    var v4Consents by remember(v4Store) { mutableStateOf(v4Store.consents()) }
    var v4ShareTitles by remember(v4Store) { mutableStateOf(v4Store.shareTitles()) }
    var v4Presentation by remember(v4Store) { mutableStateOf(v4Store.presentation()) }
    val refreshV4 = {
        v4Consents = v4Store.consents()
        v4ShareTitles = v4Store.shareTitles()
        v4Presentation = v4Store.presentation()
    }
    val immediateWork = remember(v4WorkManager) {
        v4WorkManager.getWorkInfosForUniqueWorkLiveData(CoreV4SyncScheduler.IMMEDIATE_WORK)
    }
    val periodicWork = remember(v4WorkManager) {
        v4WorkManager.getWorkInfosForUniqueWorkLiveData(CoreV4SyncScheduler.PERIODIC_WORK)
    }
    DisposableEffect(immediateWork, periodicWork) {
        val observer = Observer<List<WorkInfo>> { refreshV4() }
        immediateWork.observeForever(observer)
        periodicWork.observeForever(observer)
        onDispose {
            immediateWork.removeObserver(observer)
            periodicWork.removeObserver(observer)
        }
    }
    var baseUrl by remember(controller, controller.syncBaseUrl) { mutableStateOf(controller.syncBaseUrl) }
    var token by remember { mutableStateOf("") }
    var showClearQueue by remember { mutableStateOf(false) }
    var showDisableSync by remember { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    val syncEnabled = controller.capabilities[AtlasController.CAP_SYNC] == true

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Partage backend facultatif", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "ATLAS reste local par défaut ; le partage exige l'activation de Sync et le choix d'une catégorie.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            Modifier.fillMaxWidth().border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                MaterialTheme.shapes.medium,
            ).testTag("sync_status_card").padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            val status = syncStatusPresentation(
                enabled = syncEnabled,
                hasConsent = controller.selectedSyncCategories.isNotEmpty(),
                backendUrlConfigured = controller.syncBaseUrl.isNotBlank(),
                tokenConfigured = controller.syncTokenConfigured,
                inProgress = controller.syncInProgress,
                status = controller.syncStatus,
                pendingCount = controller.syncPendingCount,
                statusMessage = controller.syncStatusMessage,
                backendObservation = controller.agentHomeState.backendObservation,
                nowUtcMillis = System.currentTimeMillis(),
            )
            Text(status.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(status.detail, style = MaterialTheme.typography.bodySmall)
            Text("File locale : ${controller.syncPendingCount} événement(s)", style = MaterialTheme.typography.bodySmall)
        }

        Row(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Activer la sync", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
            Switch(
                checked = syncEnabled,
                onCheckedChange = controller::setSyncEnabled,
                modifier = Modifier.testTag("sync_enabled_switch"),
            )
        }
        Text(
            "Désactiver Sync V1 révoque ses catégories et purge sa file. Le Push Core V4 se règle séparément ci-dessous.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Catégories partagées", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        AtlasRule()
        SyncCategoryRow("Focus", "Démarrages, pauses et fins; sans nom de tâche.", SyncDataCategory.FOCUS, controller)
        SyncCategoryRow("Habitudes", "Validations seulement; sans nom d'habitude.", SyncDataCategory.HABIT, controller)
        SyncCategoryRow("Bibliothèque", "Ouverture seulement; sans titre ni URL.", SyncDataCategory.CONTENT, controller)
        SyncCategoryRow("Réponses du coach", "Choix explicite; la réponse locale reste disponible.", SyncDataCategory.COACH_INTERACTION, controller)
        SyncCategoryRow("Résumé global d'usage", "Réservé; aucun résumé n'est encore produit.", SyncDataCategory.APP_USAGE_SUMMARY, controller)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = false,
                onCheckedChange = {},
                enabled = false,
                modifier = Modifier.semantics { contentDescription = "Détails privés d'applications · indisponibles" },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Détails privés d'applications", color = MaterialTheme.colorScheme.onSurface)
                Text("Désactivés et jamais envoyés.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Text("Push Core V4 · Android vers Core", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "Les catégories V4 sont indépendantes et désactivées par défaut. Le worker transmet leur consentement avant les projections. Les envois relisent le plan approuvé sans le modifier; la révocation arrête les travaux V4 en attente.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AtlasRule()
        CoreV4ConsentRow(
            title = "Plan approuvé",
            description = "Version du plan et créneaux; notes exclues.",
            state = v4Consents.first { it.snapshot.category == CoreV4Category.DAY_PLAN }.snapshot.state,
            freshness = v4Store.freshness(CoreV4Category.DAY_PLAN),
        ) { enabled ->
            v4Store.setConsent(CoreV4Category.DAY_PLAN, enabled)
            CoreV4SyncScheduler.schedule(context)
            refreshV4()
        }
        CoreV4ConsentRow(
            title = "Journal des statuts",
            description = "Événements déjà décidés localement; aucune transition n'est créée.",
            state = v4Consents.first { it.snapshot.category == CoreV4Category.ACTIVITY_STATUS }.snapshot.state,
            freshness = v4Store.freshness(CoreV4Category.ACTIVITY_STATUS),
        ) { enabled ->
            v4Store.setConsent(CoreV4Category.ACTIVITY_STATUS, enabled)
            CoreV4SyncScheduler.schedule(context)
            refreshV4()
        }
        CoreV4ConsentRow(
            title = "Résumé d'usage",
            description = "Résumé local uniquement si le consentement de collecte est actif; aucun nom d'application.",
            state = v4Consents.first { it.snapshot.category == CoreV4Category.USAGE_SUMMARY }.snapshot.state,
            freshness = v4Store.freshness(CoreV4Category.USAGE_SUMMARY),
        ) { enabled ->
            v4Store.setConsent(CoreV4Category.USAGE_SUMMARY, enabled)
            CoreV4SyncScheduler.schedule(context)
            refreshV4()
        }
        CoreV4ConsentRow(
            title = "Progression des objectifs",
            description = "Identifiants UUID et mesure; détails et prochaine action exclus.",
            state = v4Consents.first { it.snapshot.category == CoreV4Category.GOAL_PROGRESS }.snapshot.state,
            freshness = v4Store.freshness(CoreV4Category.GOAL_PROGRESS),
        ) { enabled ->
            v4Store.setConsent(CoreV4Category.GOAL_PROGRESS, enabled)
            CoreV4SyncScheduler.schedule(context)
            refreshV4()
        }
        CoreV4ConsentRow(
            title = "Références d'entraînement",
            description = "Références wger déjà présentes localement; aucune lecture live ni séance inventée.",
            state = v4Consents.first { it.snapshot.category == CoreV4Category.TRAINING_PLAN }.snapshot.state,
            freshness = v4Store.freshness(CoreV4Category.TRAINING_PLAN),
        ) { enabled ->
            v4Store.setConsent(CoreV4Category.TRAINING_PLAN, enabled)
            CoreV4SyncScheduler.schedule(context)
            refreshV4()
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = v4ShareTitles,
                onCheckedChange = {
                    v4Store.setShareTitles(it)
                    v4ShareTitles = it
                    CoreV4SyncScheduler.schedule(context)
                    refreshV4()
                },
                modifier = Modifier.semantics { contentDescription = "Partager les titres V4" },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Partager les titres du plan et des objectifs", color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Désactivé par défaut. Notes, messages et détails privés restent exclus.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                MaterialTheme.shapes.medium,
            ).testTag("sync_v4_status").padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text("État Core V4 · ${v4Presentation.code}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(v4Presentation.message, style = MaterialTheme.typography.bodySmall)
            Text("Requêtes locales en attente : ${v4Presentation.pendingCount}", style = MaterialTheme.typography.bodySmall)
            v4Presentation.updatedAt?.let {
                Text("Dernier reçu ou état : $it", style = MaterialTheme.typography.bodySmall)
            }
            Button(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("sync_v4_now"),
                enabled = controller.syncBaseUrl.isNotBlank() && controller.syncTokenConfigured,
                onClick = {
                    CoreV4SyncScheduler.schedule(context)
                    refreshV4()
                },
            ) { Text("Pousser les projections V4") }
        }

        AtlasSecondaryButton(
            label = "Connexion relayée (PC)",
            onClick = { controller.navigationState.navigate(AtlasScreenId.RELAY_SETTINGS) },
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
            onClick = { advancedExpanded = !advancedExpanded },
        ) { Text("Configuration avancée") }

        if (advancedExpanded) {
            AtlasSection("Configuration avancée") {
                Text("URL du backend", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().testTag("sync_backend_url"),
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = null,
                    placeholder = { Text("https://serveur.example") },
                    singleLine = true,
                )
                Text("Jeton Bearer", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().testTag("sync_backend_token"),
                    value = token,
                    onValueChange = { token = it },
                    label = null,
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
                        CoreV4SyncScheduler.schedule(context)
                        refreshV4()
                    },
                ) { Text("Enregistrer la configuration") }
                if (controller.syncTokenConfigured) {
                    Text("Jeton enregistré et chiffré; sa valeur n'est pas affichée.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
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
                    "Le jeton est chiffré avec Android Keystore. HTTP n'est permis qu'en debug vers 10.0.2.2, 127.0.0.1 ou localhost; les builds release exigent HTTPS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Le relais chiffré joint le PC sans VPN ; le mode direct reste disponible.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
private fun CoreV4ConsentRow(
    title: String,
    description: String,
    state: CoreV4ConsentState,
    freshness: String,
    onChanged: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = state == CoreV4ConsentState.GRANTED,
            onCheckedChange = onChanged,
            modifier = Modifier.semantics { contentDescription = title },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface)
            Text(
                description + " · " + when (state) {
                    CoreV4ConsentState.GRANTED -> "Activé"
                    CoreV4ConsentState.REVOKED -> "Révoqué"
                    CoreV4ConsentState.UNKNOWN -> "Désactivé"
                } + " · " + freshness,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
        Checkbox(checked = checked, onCheckedChange = { controller.setSyncCategory(category, it) }, modifier = Modifier.semantics { contentDescription = title })
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
