package dev.atlas.coach.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.atlas.coach.sync.agent.AgentMode
import dev.atlas.coach.coach.proactive.ProactiveEngagementMode
import dev.atlas.coach.sync.agent.ProposalState
import dev.atlas.coach.sync.agent.coach.AgentMessageRole
import dev.atlas.coach.sync.agent.inbox.AgentInboxEntry
import dev.atlas.coach.sync.agent.dispatch.AgentEffectRecord
import java.util.Locale
import dev.atlas.coach.sync.agent.context.AgentContextCategory
import dev.atlas.coach.location.MobilitySessionStatusStore
import dev.atlas.coach.app.ui.AtlasRule
import kotlinx.coroutines.launch

private val actionLabels = linkedMapOf(
    "home.set_directive" to "Modifier la directive HOME",
    "home.reorder_cards" to "Réordonner les cartes HOME",
    "focus.propose" to "Proposer une session Focus",
    "focus.plan" to "Planifier une session Focus",
    "focus.start" to ProposalActionLabels.labelFor("focus.start"),
    "focus.pause" to "Mettre Focus en pause",
    "open_coach" to ProposalActionLabels.labelFor("open_coach"),
    "habit.suggest" to "Suggérer une habitude",
    "app_policy.propose" to "Proposer une règle d'application",
    "app_policy.adjust" to "Ajuster une règle d'application",
    "content.recommend" to "Recommander un contenu",
)

private val dataCategoryLabels = linkedMapOf(
    "focus" to "Focus",
    "habit" to "Résumé des habitudes",
    "content" to "Bibliothèque de contenus",
    "coach_interaction" to "Échanges avec le Coach",
    "app_usage_summary" to "Résumé d'observation des applications",
    "goals" to "Objectifs",
    "location_summary" to "Résumé de zone approximative",
)

private fun readableProvenanceLabel(contextSources: List<String>, fallback: String): String {
    val sources = contextSources.map { it.lowercase(Locale.ROOT) }
    return when {
        sources.any { "local-demo" in it || "adaptateur local" in it || "démonstration locale" in it } -> "Local"
        sources.any { it.startsWith("version de contexte ") } -> "Coach connecté"
        else -> fallback
    }
}

private fun readableContextCategories(contextSources: List<String>): String? {
    val categoryKeys = contextSources.flatMap { source ->
        val marker = source.indexOf("Catégories consenties", ignoreCase = true)
        if (marker < 0) emptyList() else {
            source.substring(marker).substringAfter(':', "")
                .split(',', '·', '|')
                .map { it.trim().trimEnd('.').lowercase(Locale.ROOT) }
        }
    }
    val labels = categoryKeys.mapNotNull(dataCategoryLabels::get).distinct()
    return labels.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
internal fun AgentCoachScreen(
    controller: AtlasController,
    pullOnOpen: Boolean = true,
    revision: Int = controller.revision,
) {
    var message by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<AgentInboxEntry?>(null) }
    val pendingProposals = remember(controller, revision) { controller.visibleAgentProposals }
    val notificationProposal = remember(controller, revision) {
        controller.highlightedProactiveProposalId?.let { id -> controller.agentInbox.firstOrNull { it.id == id } }
    }
    val missingNotificationProposal = controller.proactiveNotificationProposalUnavailable ||
        (controller.highlightedProactiveProposalId != null && notificationProposal == null)
    val latestProvenance = remember(controller, revision) {
        HomeAgentState.localDefault(controller).directiveStatusLabel()
    }

    LaunchedEffect(pullOnOpen) {
        if (pullOnOpen) controller.pullAgentInbox()
    }

    DisposableEffect(controller) {
        onDispose(controller::clearUnavailableProactiveNotificationState)
    }

    Column(
        // AtlasScreenFrame owns the screen's scroll container; nesting one here
        // gives Compose an unbounded height and crashes the Coach destination.
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            latestProvenance,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag("agent_connection_status"),
        )
        if (controller.capabilities[AtlasController.CAP_COACH] != true) {
            Text("Coach local désactivé dans les paramètres.")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Conversation", style = MaterialTheme.typography.titleLarge)
                if (controller.agentConversation.isEmpty()) {
                    Text("Écrivez un message pour démarrer. Sans serveur configuré, la démonstration locale reste disponible.")
                }
                controller.agentConversation.forEach { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                            androidx.compose.foundation.layout.Box(
                                Modifier.padding(top = 3.dp).sizeIn(minWidth = 2.dp, minHeight = 18.dp)
                                    .background(if (entry.role == AgentMessageRole.ASSISTANT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(if (entry.role == AgentMessageRole.USER) "Vous" else "Coach", style = MaterialTheme.typography.labelLarge)
                                Text(entry.text, style = MaterialTheme.typography.bodyLarge)
                                if (entry.role == AgentMessageRole.ASSISTANT) {
                                    Text(
                                        "Provenance : ${readableProvenanceLabel(entry.contextSources, latestProvenance)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    readableContextCategories(entry.contextSources)?.let { categories ->
                                        Text("Catégories : $categories", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        AtlasRule()
                    }
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = { if (it.length <= AtlasController.MAX_AGENT_MESSAGE_LENGTH) message = it },
                    modifier = Modifier.fillMaxWidth().testTag("agent_message_input"),
                    label = { Text("Message au coach") },
                    supportingText = { Text("Au plus 2 000 caractères. Le message part seulement après votre action Envoyer.") },
                    minLines = 2,
                    maxLines = 5,
                )
                Button(
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("agent_send_message"),
                    onClick = { controller.sendAgentMessage(message); message = "" },
                    enabled = message.isNotBlank() && controller.isAgentEnabled,
                ) { Text("Envoyer") }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Proposition prioritaire", style = MaterialTheme.typography.titleLarge)
                val proposal = if (missingNotificationProposal) null else notificationProposal ?: pendingProposals.firstOrNull()
                if (missingNotificationProposal) {
                    Text(
                        AtlasController.PROACTIVE_PROPOSAL_UNAVAILABLE_MESSAGE,
                        modifier = Modifier.testTag("proactive_proposal_unavailable"),
                    )
                } else if (proposal == null) Text("Aucune proposition en attente.")
                else {
                    val sourceMetadata = listOf(controller.agentProposalSourceSummary(proposal.proposal))
                    AgentProposalCard(
                        entry = proposal,
                        provenanceLabel = readableProvenanceLabel(sourceMetadata, latestProvenance),
                        contextCategories = readableContextCategories(sourceMetadata),
                        highlighted = proposal.id == controller.highlightedProactiveProposalId,
                        showActions = controller.isAgentEnabled && proposal.proposal.state in setOf(
                            ProposalState.PROPOSED,
                            ProposalState.PENDING_CONFIRMATION,
                        ),
                        onAccept = { controller.acceptAgentProposal(proposal.id) },
                        onEdit = { editing = proposal },
                        onPostpone = { controller.postponeAgentProposal(proposal.id) },
                        onReject = { controller.rejectAgentProposal(proposal.id) },
                    )
                    if (pendingProposals.size > 1) {
                        Text("${pendingProposals.size - 1} autre(s) proposition(s) apparaîtront après celle-ci.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        AgentIntegrationPanels(controller, revision)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Réglages et historique", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("agent_refresh"),
                onClick = controller::pullAgentInbox,
            ) { Text("Actualiser la boîte de réception") }
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

    editing?.let { entry ->
        ProposalEditDialog(
            entry = entry,
            onDismiss = { editing = null },
            onSave = { parameters -> controller.editAgentProposal(entry.id, parameters); editing = null },
        )
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

@Composable
internal fun AgentSettingsScreen(controller: AtlasController) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Mandat du Coach", style = MaterialTheme.typography.headlineSmall)
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("agent_enabled_toggle"),
            onClick = { if (controller.isAgentEnabled) controller.disableAgent() else controller.enableAgent() },
        ) { Text(if (controller.isAgentEnabled) "Désactiver le Coach OpenClaw" else "Activer le Coach OpenClaw") }
        MandatePanel(controller)
    }
}

@Composable
internal fun AgentSharingScreen(controller: AtlasController) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Données partagées", style = MaterialTheme.typography.headlineSmall)
        SharedDataPanel(controller)
    }
}

@Composable
internal fun AgentEffectsScreen(controller: AtlasController) {
    Column(modifier = Modifier.fillMaxWidth().testTag("agent_effect_history_screen"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Journal des effets", style = MaterialTheme.typography.headlineSmall)
        Text("Historique local des décisions et effets réconciliés.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        EffectHistoryPanel(controller.agentEffectHistory)
    }
}

@Composable
private fun AgentProposalCard(
    entry: AgentInboxEntry,
    provenanceLabel: String,
    contextCategories: String?,
    highlighted: Boolean,
    showActions: Boolean,
    onAccept: () -> Unit,
    onEdit: () -> Unit,
    onPostpone: () -> Unit,
    onReject: () -> Unit,
) {
    val compactActions = LocalConfiguration.current.screenWidthDp <= 360 || LocalDensity.current.fontScale >= 1.4f
    Column(
        Modifier.fillMaxWidth()
            .border(
                BorderStroke(1.dp, if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)),
                MaterialTheme.shapes.medium,
            )
            .testTag("agent_priority_proposal_${entry.id}")
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
            Text("Proposition", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(actionLabels[entry.proposal.actionType] ?: entry.proposal.actionType, style = MaterialTheme.typography.titleMedium)
            Text("Motif : ${entry.proposal.reason}")
            if (highlighted) Text("Ouverte depuis une notification", style = MaterialTheme.typography.labelMedium)
            Text("Provenance : $provenanceLabel", style = MaterialTheme.typography.bodySmall)
            contextCategories?.let { Text("Catégories : $it", style = MaterialTheme.typography.bodySmall) }
            Text("État : ${proposalStateLabel(entry)}", style = MaterialTheme.typography.bodySmall)
            if (showActions) {
                Text("Action à confirmer", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (compactActions) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("agent_proposal_accept"), onClick = onAccept) { Text("Accepter") }
                        OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("agent_proposal_edit"), onClick = onEdit) { Text("Modifier") }
                        OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("agent_proposal_postpone"), onClick = onPostpone) { Text("Plus tard") }
                        TextButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("agent_proposal_reject"), onClick = onReject) { Text("Refuser") }
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp).testTag("agent_proposal_accept"), onClick = onAccept) { Text("Accepter") }
                        OutlinedButton(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp).testTag("agent_proposal_edit"), onClick = onEdit) { Text("Modifier") }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp).testTag("agent_proposal_postpone"), onClick = onPostpone) { Text("Plus tard") }
                        TextButton(modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp).testTag("agent_proposal_reject"), onClick = onReject) { Text("Refuser") }
                    }
                }
            }
    }
}

@Composable
internal fun ProposalEditDialog(
    entry: AgentInboxEntry,
    onDismiss: () -> Unit,
    onSave: (Map<String, Any?>) -> Unit,
) {
    val parameters = entry.proposal.parameters
    val values = remember(entry.id) { mutableStateMapOf<String, Any?>().apply { putAll(parameters) } }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifier la proposition") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(actionLabels[entry.proposal.actionType] ?: "Proposition du Coach")
                parameters.forEach { (key, original) ->
                    val label = proposalParameterLabel(key)
                    when (original) {
                        is Boolean -> Row(
                            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(label, modifier = Modifier.weight(1f))
                            Switch(
                                checked = values[key] as? Boolean ?: original,
                                onCheckedChange = { values[key] = it },
                            )
                        }
                        is Map<*, *> -> Text("$label · ce réglage ne peut pas être modifié ici.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> OutlinedTextField(
                            value = parameterText(key, values[key]),
                            onValueChange = { values[key] = it },
                            modifier = Modifier.fillMaxWidth().testTag("agent_edit_field_$key"),
                            label = { Text(label) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = if (original is Number) KeyboardType.Number else KeyboardType.Text,
                            ),
                            singleLine = original !is List<*>,
                            minLines = if (original is List<*>) 2 else 1,
                            maxLines = if (original is List<*>) 3 else 1,
                        )
                    }
                }
                if (invalid) Text("Vérifiez les valeurs saisies. La proposition n'a pas été enregistrée.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("agent_edit_save"),
                onClick = {
                    runCatching {
                        parameters.mapValues { (key, original) -> parseEditedParameter(key, values[key], original) }
                    }.onSuccess(onSave).onFailure { invalid = true }
                },
            ) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(modifier = Modifier.sizeIn(minHeight = 48.dp), onClick = onDismiss) { Text("Annuler") } },
    )
}

private fun proposalParameterLabel(key: String): String = when (key) {
    "title" -> "Titre"
    "body" -> "Détail"
    "primary_action" -> "Action principale"
    "focus_minutes" -> "Concentration · minutes"
    "break_minutes" -> "Pause · minutes"
    "cycles" -> "Cycles avant la pause longue"
    "total_work_cycles" -> "Cycles de travail"
    "label" -> "Nom de la tâche"
    "start_at" -> "Début prévu · ISO 8601"
    "timezone" -> "Fuseau horaire"
    "name" -> "Nom"
    "schedule" -> "Rythme"
    "package_ref" -> "Application"
    "daily_limit_minutes" -> "Limite quotidienne · minutes"
    "url" -> "Lien"
    "focus_proposal_id" -> "Proposition Focus liée"
    "session_ref" -> "Session liée"
    "order" -> "Ordre · séparer par des virgules"
    else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun parameterText(key: String, value: Any?): String = when {
    key == "primary_action" -> ProposalActionLabels.labelFor(value as? String ?: "")
    value == null -> ""
    value is List<*> -> value.joinToString(", ")
    value is String || value is Number -> value.toString()
    else -> ""
}

private fun parseEditedParameter(key: String, value: Any?, original: Any?): Any? {
    if (key == "primary_action") {
        val originalId = original as? String ?: error("Invalid primary action")
        return ProposalActionLabels.contractIdFor(value as? String ?: "", originalId)
    }
    val text = value as? String ?: return value
    return when (original) {
        is Int -> text.toIntOrNull() ?: error("Invalid integer")
        is Long -> text.toLongOrNull() ?: error("Invalid integer")
        is Double -> text.toDoubleOrNull()?.takeIf(Double::isFinite) ?: error("Invalid number")
        is Float -> text.toFloatOrNull()?.takeIf(Float::isFinite) ?: error("Invalid number")
        is Number -> text.toIntOrNull() ?: error("Invalid number")
        is List<*> -> text.split(',').map(String::trim).filter(String::isNotEmpty)
        is String -> text
        null -> text
        else -> original
    }
}

/** Keeps the user-facing edit value separate from the unchanged action id sent in the proposal. */
internal object ProposalActionLabels {
    private val labelsById = linkedMapOf(
        "focus.start" to "Démarrer un focus",
        "open_coach" to "Ouvrir le Coach",
        "none" to "Aucune action",
    )

    fun labelFor(actionId: String): String = labelsById[actionId] ?: "Action non reconnue"

    fun contractIdFor(editedLabel: String, originalId: String): String {
        val candidate = editedLabel.trim()
        if (candidate == labelFor(originalId)) return originalId
        return labelsById.entries.firstOrNull { it.value.equals(candidate, ignoreCase = true) }?.key
            ?: candidate.takeIf(labelsById::containsKey)
            ?: throw IllegalArgumentException("Choisissez un libellé d'action connu")
    }
}

@Composable
private fun MandatePanel(controller: AtlasController) {
    val mandate = controller.agentMandate
    val fontScale = LocalDensity.current.fontScale
    val compactControls = fontScale >= 1.4f || LocalConfiguration.current.screenWidthDp <= 360
    val dayColumns = if (fontScale >= 1.4f) 3 else 4
    var start by remember(mandate.version) { mutableStateOf(mandate.focusWindow.start) }
    var end by remember(mandate.version) { mutableStateOf(mandate.focusWindow.end) }
    var maxMinutes by remember(mandate.version) { mutableStateOf(mandate.focusWindow.maxMinutesPerSession.toString()) }
    var maxSessions by remember(mandate.version) { mutableStateOf(mandate.focusWindow.maxSessionsPerDay.toString()) }
    var maxChanges by remember(mandate.version) { mutableStateOf(mandate.maxChangesPerDay.toString()) }
    var days by remember(mandate.version) { mutableStateOf(mandate.focusWindow.days) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Mandat et autonomie", style = MaterialTheme.typography.titleLarge)
        AtlasRule()
        Text("Mandat v2 · ${mandate.id} · version ${mandate.version}")
        if (mandate.revoked || mandate.suspended) {
            Text(if (mandate.revoked) "Mandat révoqué." else "Mandat suspendu.")
            OutlinedButton(modifier = Modifier.sizeIn(minHeight = 48.dp), onClick = controller::resumeAgentMandate) { Text("Réactiver le mandat") }
        } else {
            OutlinedButton(modifier = Modifier.sizeIn(minHeight = 48.dp), onClick = controller::suspendAgentMandate) { Text("Suspendre le mandat") }
        }
        OutlinedButton(modifier = Modifier.sizeIn(minHeight = 48.dp), onClick = controller::revokeAgentMandate) { Text("Révoquer tout") }

        Row(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Mode Actions préautorisées", modifier = Modifier.weight(1f))
            Switch(
                checked = mandate.mode == AgentMode.PREAUTHORIZED,
                onCheckedChange = { controller.setAgentMode(if (it) AgentMode.PREAUTHORIZED else AgentMode.PROPOSALS_ONLY) },
            )
        }
        Text(if (mandate.mode == AgentMode.PROPOSALS_ONLY) "Mode : Propositions seulement" else "Mode : Actions préautorisées dans les bornes locales")

        Text("Actions autorisées", style = MaterialTheme.typography.titleMedium)
        AtlasRule()
        actionLabels.forEach { (wire, label) ->
            Row(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = wire in mandate.allowedActions,
                    onCheckedChange = { controller.setAgentActionAllowed(wire, it) },
                )
                Text(label)
            }
        }

        Text("Fenêtre et quotas Focus", style = MaterialTheme.typography.titleMedium)
        AtlasRule()
        val mandateDays = listOf("L" to 1, "Ma" to 2, "Me" to 3, "J" to 4, "V" to 5, "S" to 6, "D" to 7)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            mandateDays.chunked(dayColumns).forEach { dayRow ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    dayRow.forEach { (label, day) ->
                        Row(
                            modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = day in days, onCheckedChange = { checked ->
                                days = if (checked) days + day else days - day
                            })
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        if (compactControls) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(start, { start = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Début HH:mm") }, singleLine = true)
                OutlinedTextField(end, { end = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Fin HH:mm") }, singleLine = true)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(start, { start = it }, modifier = Modifier.weight(1f), label = { Text("Début HH:mm") }, singleLine = true)
                OutlinedTextField(end, { end = it }, modifier = Modifier.weight(1f), label = { Text("Fin HH:mm") }, singleLine = true)
            }
        }
        if (compactControls) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    maxMinutes, { maxMinutes = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Minutes/session") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    maxSessions, { maxSessions = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Sessions/jour") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    maxMinutes, { maxMinutes = it }, modifier = Modifier.weight(1f), label = { Text("Minutes/session") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    maxSessions, { maxSessions = it }, modifier = Modifier.weight(1f), label = { Text("Sessions/jour") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }
        OutlinedTextField(
            maxChanges, { maxChanges = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Actions d'agent/jour") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Text("Fuseau : ${mandate.focusWindow.timezone}")
        OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = {
            controller.setAgentFocusWindow(
                days, start, end, maxMinutes.toIntOrNull() ?: -1,
                maxSessions.toIntOrNull() ?: -1, maxChanges.toIntOrNull() ?: -1,
            )
        }) { Text("Enregistrer la fenêtre et les quotas") }
    }
}

@Composable
private fun SharedDataPanel(controller: AtlasController) {
    val mandate = controller.agentMandate
    val authorizedCategories = controller.agentContextPreview.authorizedCategories
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Données partagées avec le coach", style = MaterialTheme.typography.titleLarge)
        AtlasRule()
        Text("Les catégories cochées autorisent les prochains envois. Les décocher arrête le partage de cette catégorie.")
        dataCategoryLabels.forEach { (category, label) ->
            Row(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = category in mandate.sharedDataCategories,
                    onCheckedChange = { controller.setAgentSharedCategory(category, it) },
                )
                Text(label)
            }
            if (category == AgentContextCategory.GOALS.wireValue &&
                category in authorizedCategories
            ) {
                Row(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = controller.includeExistingAgentGoals,
                        onCheckedChange = controller::setIncludeExistingAgentGoals,
                        modifier = Modifier.testTag("agent_include_existing_goals"),
                    )
                    Text("Inclure mes objectifs actifs existants")
                }
                Text(
                    if (controller.includeExistingAgentGoals) {
                        "Leur état actuel peut être inclus. Les événements Focus et check-in antérieurs au consentement restent exclus."
                    } else {
                        "Désactivé par défaut. Seuls les objectifs créés ou modifiés depuis ce consentement seront inclus."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (mandate.sharedDataCategories.isEmpty()) {
            Text("Aucune catégorie autorisée.", color = MaterialTheme.colorScheme.primary)
        } else {
            val labels = mandate.sharedDataCategories.mapNotNull(dataCategoryLabels::get).sorted()
            if (labels.isNotEmpty()) Text("Catégories autorisées : ${labels.joinToString(", ")}.")
        }
        Text("L'observation UsageStats exige son propre consentement Android et ATLAS. Les actions n'accordent aucune permission système.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun dev.atlas.coach.sync.agent.context.LastContextDisclosureStatus.displayLabel(): String = when (this) {
    dev.atlas.coach.sync.agent.context.LastContextDisclosureStatus.SUCCESS -> "écriture terminée côté ATLAS"
    dev.atlas.coach.sync.agent.context.LastContextDisclosureStatus.ATTEMPTED -> "tentative, réponse non confirmée"
}

@Composable
private fun EffectHistoryPanel(entries: List<AgentEffectRecord>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Journal des effets", style = MaterialTheme.typography.titleLarge)
        if (entries.isEmpty()) Text("Aucun effet d'agent enregistré.")
        entries.take(20).forEach { entry ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(actionLabels[entry.actionType] ?: entry.actionType, style = MaterialTheme.typography.titleSmall)
                Text("${entry.state.wireValue} · ${entry.displayTimestamp()}")
                Text(entry.effect, style = MaterialTheme.typography.bodySmall)
            }
            AtlasRule()
        }
    }
}

private fun proposalStateLabel(entry: AgentInboxEntry): String = when (entry.proposal.state) {
    ProposalState.PROPOSED -> "Proposée"
    ProposalState.PENDING_CONFIRMATION -> if (entry.snoozedUntilUtcMillis != null) "Reportée" else "À confirmer"
    ProposalState.ACCEPTED -> "Acceptée"
    ProposalState.EXECUTED -> "Exécutée"
    ProposalState.REJECTED -> "Refusée"
    ProposalState.EXPIRED -> "Expirée"
    ProposalState.CANCELLED -> "Annulée localement"
}

private fun AgentEffectRecord.displayTimestamp(): String = java.time.Instant.ofEpochMilli(atUtcMillis)
    .atZone(java.time.ZoneId.systemDefault())
    .format(java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm", java.util.Locale.FRANCE))
