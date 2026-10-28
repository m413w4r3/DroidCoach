package dev.atlas.coach.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.atlas.coach.coach.proactive.ProactiveEngagementMode
import dev.atlas.coach.location.MobilitySessionStatusStore
import dev.atlas.coach.sync.agent.context.AgentContextCategory
import kotlinx.coroutines.launch

@Composable
internal fun CoachContextScreen(
    controller: AtlasController,
    revision: Int = controller.revision,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Contexte du coach", style = MaterialTheme.typography.headlineSmall)
        Text("Objectifs, localisation, confidentialité et tâches en arrière-plan.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        AgentIntegrationPanels(controller, revision)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Mandat et historique", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("agent_settings_link"),
                onClick = { controller.navigationState.navigate(AtlasScreenId.AGENT_SETTINGS) },
            ) { Text("Mandat du Coach") }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("agent_sharing_link"),
                onClick = { controller.navigationState.navigate(AtlasScreenId.AGENT_SHARING) },
            ) { Text("Données partagées") }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("agent_effects_link"),
                onClick = { controller.navigationState.navigate(AtlasScreenId.AGENT_EFFECTS) },
            ) { Text("Journal des effets") }
        }
    }
}

private enum class LocationUiContinuation { CAPTURE, MOBILITY }

@Composable
private fun AgentIntegrationPanels(controller: AtlasController, revision: Int) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val mobilityStatus by MobilitySessionStatusStore.status.collectAsState()
    var pendingLocationAction by remember { mutableStateOf<LocationUiContinuation?>(null) }
    var locationPermissionResult by remember { mutableStateOf<Boolean?>(null) }
    var notificationPermissionResult by remember { mutableStateOf<Boolean?>(null) }
    var showContextPayload by remember { mutableStateOf(false) }
    var showLastSharedContextPayload by remember { mutableStateOf(false) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        controller.onLocationPermissionResult(granted)
        locationPermissionResult = granted
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationPermissionResult = granted }

    fun startMobilityIfVisible() {
        val visibleActivity = activity
        if (visibleActivity == null) {
            controller.reportMessage("Le suivi doit démarrer depuis une activité ATLAS visible.")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingLocationAction = LocationUiContinuation.MOBILITY
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            controller.startMobilitySession(visibleActivity)
        }
    }

    LaunchedEffect(locationPermissionResult) {
        val granted = locationPermissionResult ?: return@LaunchedEffect
        locationPermissionResult = null
        val action = pendingLocationAction
        pendingLocationAction = null
        if (!granted || action == null) return@LaunchedEffect
        when (action) {
            LocationUiContinuation.CAPTURE -> scope.launch { controller.captureCoarseLocation() }
            LocationUiContinuation.MOBILITY -> startMobilityIfVisible()
        }
    }
    LaunchedEffect(notificationPermissionResult) {
        val granted = notificationPermissionResult ?: return@LaunchedEffect
        notificationPermissionResult = null
        if (pendingLocationAction == LocationUiContinuation.MOBILITY) {
            pendingLocationAction = null
            if (granted) startMobilityIfVisible()
            else controller.reportMessage("Notifications refusées; la session de déplacement n'a pas démarré.")
        }
    }

    val contextPreview = controller.agentContextPreview
    LaunchedEffect(contextPreview.authorizedCategories.isEmpty()) {
        if (contextPreview.authorizedCategories.isEmpty()) {
            showContextPayload = false
            showLastSharedContextPayload = false
        }
    }
    val goals = remember(controller, revision) { controller.coachGoals }
    val focusLinkOptions = remember(controller, revision) { controller.goalFocusLinkOptions }
    val habitLinkOptions = remember(controller, revision) { controller.goalHabitLinkOptions }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GoalsPanel(
            goals = goals,
            focusSessions = focusLinkOptions,
            habits = habitLinkOptions,
            onCreateGoal = controller::createGoal,
            onUpdateGoal = controller::updateGoal,
            onStatusChange = controller::changeGoalStatus,
            onCompleteFromProgress = controller::completeGoalFromProgress,
            onLinkFocusSession = { goalId, sessionId -> controller.linkFocusToGoal(goalId, sessionId, true) },
            onUnlinkFocusSession = { goalId, sessionId -> controller.linkFocusToGoal(goalId, sessionId, false) },
            onLinkHabit = { goalId, habitId -> controller.linkHabitToGoal(goalId, habitId, true) },
            onUnlinkHabit = { goalId, habitId -> controller.linkHabitToGoal(goalId, habitId, false) },
            onConfirmManualCompletion = controller::confirmManualGoalCompletion,
        )

        LocationPanel(
            state = controller.locationPanelState.copy(mobilitySessionActive = mobilityStatus.active),
            onCoarseRequestConsentChanged = controller::setLocationRequestConsent,
            onCoachSummaryConsentChanged = controller::setLocationSummaryConsent,
            onMobilityConsentChanged = controller::setMobilityConsent,
            onRequestCoarsePosition = {
                if (controller.locationPermissionGranted()) scope.launch { controller.captureCoarseLocation() }
                else {
                    pendingLocationAction = LocationUiContinuation.CAPTURE
                    locationPermissionLauncher.launch(controller.initialLocationPermissionRequest().single())
                }
            },
            onStartMobility = {
                if (controller.locationPermissionGranted()) startMobilityIfVisible()
                else {
                    pendingLocationAction = LocationUiContinuation.MOBILITY
                    locationPermissionLauncher.launch(controller.initialLocationPermissionRequest().single())
                }
            },
            onStopMobility = { controller.stopMobilitySession() },
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Contexte du prochain envoi", style = MaterialTheme.typography.titleLarge)
            Text("Vérifiez les catégories autorisées et les données disponibles avant un envoi.")
            AgentContextCategory.wireValues.forEach { category ->
                val allowed = category in contextPreview.authorizedCategories
                val count = contextPreview.includedCategories[category]?.size ?: 0
                Text(
                    "${dataCategoryLabels[category] ?: category} · ${if (allowed) "autorisée" else "non autorisée"} · " +
                        if (count == 0) "aucune donnée disponible" else "$count élément(s) inclus",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (contextPreview.authorizedCategories.isNotEmpty()) {
                TextButton(
                    modifier = Modifier.testTag("agent_next_context_payload_toggle"),
                    onClick = { showContextPayload = !showContextPayload },
                ) {
                    Text(
                        if (showContextPayload) "Masquer le JSON exact du prochain envoi"
                        else "Afficher le JSON exact du prochain envoi",
                    )
                }
                if (showContextPayload) {
                    Surface(
                        modifier = Modifier.fillMaxWidth()
                            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)), MaterialTheme.shapes.small)
                            .testTag("agent_next_context_payload"),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(contextPreview.serializedPayload, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                Text("Le détail du prochain contexte s'affichera pendant un consentement actif.", style = MaterialTheme.typography.bodySmall)
            }
            Text("Seules les catégories autorisées et les données disponibles sont incluses avant l'envoi.", style = MaterialTheme.typography.bodySmall)
            val audit = controller.lastContextDisclosureAudit
            val sentAt = audit?.sentAtUtcMillis?.let { java.time.Instant.ofEpochMilli(it).toString() }
            Text(
                sentAt?.let { "Dernier contexte écrit · $it · ${audit.status.displayLabel()}" }
                    ?: "Aucun historique local de contexte.",
                style = MaterialTheme.typography.labelMedium,
            )
            audit?.categoryNames?.takeIf { it.isNotEmpty() }?.let { categories ->
                Text(
                    "Catégories de cet envoi : ${categories.mapNotNull(dataCategoryLabels::get).joinToString(" · ")}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (contextPreview.authorizedCategories.isNotEmpty()) {
                controller.lastSharedAgentContextJson?.let { payload ->
                    Text("Aperçu du dernier contexte conservé en mémoire pour cette session seulement.", style = MaterialTheme.typography.bodySmall)
                    TextButton(
                        modifier = Modifier.testTag("agent_last_shared_context_payload_toggle"),
                        onClick = { showLastSharedContextPayload = !showLastSharedContextPayload },
                    ) {
                        Text(
                            if (showLastSharedContextPayload) "Masquer l'aperçu du dernier contexte"
                            else "Afficher l'aperçu du dernier contexte",
                        )
                    }
                    if (showLastSharedContextPayload) {
                        Surface(
                            modifier = Modifier.fillMaxWidth()
                                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)), MaterialTheme.shapes.small)
                                .testTag("agent_last_shared_context_payload"),
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(payload, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text("Arrêter le partage bloque les prochains envois. Supprimer l'historique local efface l'audit enregistré; la copie déjà reçue par le backend n'est pas supprimée.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("agent_delete_context_history"),
                onClick = controller::deleteLastContextDisclosureHistory,
            ) { Text("Supprimer l'historique local") }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                onClick = controller::requestAgentPlan,
                enabled = controller.isAgentEnabled,
            ) { Text("Demander un plan sans ajouter de message au chat") }
            controller.latestAgentPlanReply?.let { Text("Plan · $it") }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Tâches en arrière-plan", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Observation UsageStats toutes les 30 minutes au mieux", Modifier.weight(1f))
                Switch(
                    checked = controller.capabilities[AtlasController.CAP_USAGE_BACKGROUND] == true,
                    enabled = controller.usageConsentActive && controller.usagePermissionGranted,
                    onCheckedChange = { controller.setCapability(AtlasController.CAP_USAGE_BACKGROUND, it) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Plans proactifs", Modifier.weight(1f))
                Switch(
                    checked = controller.capabilities[AtlasController.CAP_AGENT_PROACTIVE] == true,
                    enabled = controller.isAgentEnabled,
                    onCheckedChange = { controller.setCapability(AtlasController.CAP_AGENT_PROACTIVE, it) },
                )
            }
            Text("Au plus ${controller.proactiveEngagementMode.notificationLimitPerLocalDay} notifications par jour.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { controller.setProactiveEngagementMode(ProactiveEngagementMode.NORMAL) },
                    enabled = controller.capabilities[AtlasController.CAP_AGENT_PROACTIVE] == true,
                ) {
                    Text(if (controller.proactiveEngagementMode == ProactiveEngagementMode.NORMAL) "✓ Normal · 3/j" else "Normal · 3/j")
                }
                OutlinedButton(
                    onClick = { controller.setProactiveEngagementMode(ProactiveEngagementMode.REINFORCED) },
                    enabled = controller.capabilities[AtlasController.CAP_AGENT_PROACTIVE] == true,
                ) {
                    Text(if (controller.proactiveEngagementMode == ProactiveEngagementMode.REINFORCED) "✓ Renforcé · 5/j" else "Renforcé · 5/j")
                }
            }
        }
    }
}


private fun dev.atlas.coach.sync.agent.context.LastContextDisclosureStatus.displayLabel(): String = when (this) {
    dev.atlas.coach.sync.agent.context.LastContextDisclosureStatus.SUCCESS -> "écriture terminée côté ATLAS"
    dev.atlas.coach.sync.agent.context.LastContextDisclosureStatus.ATTEMPTED -> "tentative, réponse non confirmée"
}
