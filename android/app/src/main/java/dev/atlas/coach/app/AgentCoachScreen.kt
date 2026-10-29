package dev.atlas.coach.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.atlas.coach.sync.agent.AgentMode
import dev.atlas.coach.sync.agent.ProposalState
import dev.atlas.coach.sync.agent.coach.AgentMessageRole
import dev.atlas.coach.sync.agent.coach.AgentChatSendState
import dev.atlas.coach.sync.agent.coach.AgentStatus
import dev.atlas.coach.sync.agent.inbox.AgentInboxEntry
import dev.atlas.coach.sync.agent.dispatch.AgentEffectRecord
import java.util.Locale
import dev.atlas.coach.sync.agent.context.AgentContextCategory
import dev.atlas.coach.app.ui.AtlasRule

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

internal val dataCategoryLabels = linkedMapOf(
    "focus" to "Focus",
    "habit" to "Résumé des habitudes",
    "content" to "Bibliothèque de contenus",
    "coach_interaction" to "Échanges avec le Coach",
    "app_usage_summary" to "Résumé d'observation des applications",
    "goals" to "Objectifs",
    "location_summary" to "Résumé de zone approximative",
)

internal fun readableProvenanceLabel(contextSources: List<String>, fallback: String): String {
    val sources = contextSources.map { it.lowercase(Locale.ROOT) }
    return when {
        "agent_status:mock" in sources || sources.any { "local-demo" in it || "adaptateur local" in it || "démonstration locale" in it } ->
            "Démonstration locale (mock)"
        "agent_status:unavailable" in sources -> "Agent indisponible"
        "agent_status:connected" in sources || sources.any { it.startsWith("version de contexte ") } -> "OpenClaw via PC"
        else -> fallback
    }
}

private fun readableMessageSourceLabel(contextSources: List<String>, fallback: String): String {
    val sources = contextSources.map { it.lowercase(Locale.ROOT) }
    val fromOpenClaw = sources.any {
        it == "agent_status:connected" || it.startsWith("version de contexte ") || it.contains("openclaw")
    } || fallback.contains("OpenClaw", ignoreCase = true)
    return if (fromOpenClaw) "OpenClaw" else "Local"
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
    onOpenContext: () -> Unit = {},
) {
    var message by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<AgentInboxEntry?>(null) }
    var showTechnicalConnectionDetail by rememberSaveable { mutableStateOf(false) }
    val pendingProposals = remember(controller, revision) { controller.visibleAgentProposals }
    val notificationProposal = remember(controller, revision) {
        controller.highlightedProactiveProposalId?.let { id -> controller.agentInbox.firstOrNull { it.id == id } }
    }
    val missingNotificationProposal = controller.proactiveNotificationProposalUnavailable ||
        (controller.highlightedProactiveProposalId != null && notificationProposal == null)
    val latestProvenance = remember(controller, revision) {
        HomeAgentState.localDefault(controller).directiveStatusLabel()
    }
    val humanConnection = coachHumanConnectionState(
        mode = controller.relaySettings.mode,
        state = controller.relaySettings.state,
        agentStatusMessage = controller.agentStatusMessage,
        lastError = controller.relaySettings.lastError,
    )
    val rawTechnicalDetail = humanConnection.technicalDetail ?: listOfNotNull(
        controller.agentStatusMessage,
        controller.agentChatStatusMessage,
    ).firstOrNull {
        it.contains("TLS", ignoreCase = true) ||
            it.contains("SSL", ignoreCase = true) ||
            it.contains("handshake", ignoreCase = true) ||
            it.contains("certificate", ignoreCase = true) ||
            it.contains("certificat", ignoreCase = true)
    }
    val consentDetail = controller.agentStatusMessage.takeIf {
        it.contains("consent", ignoreCase = true) || it.contains("autorisation requise", ignoreCase = true)
    }
    val tlsFailure = rawTechnicalDetail?.let {
        it.contains("TLS", ignoreCase = true) ||
            it.contains("SSL", ignoreCase = true) ||
            it.contains("certificat", ignoreCase = true)
    } == true
    val connectionLabel = when {
        tlsFailure -> "Erreur de connexion"
        humanConnection.label == "Connexion directe" -> when {
            controller.agentStatus == AgentStatus.CONNECTED -> "Connecté"
            controller.agentStatusMessage.contains("connexion", ignoreCase = true) -> "Connexion en cours…"
            else -> "Hors ligne"
        }
        humanConnection.label == "Relais inactif" -> "Hors ligne"
        else -> humanConnection.label
    }
    val humanConnectionDetail = when {
        consentDetail != null -> consentDetail
        tlsFailure -> "Erreur TLS · liaison sécurisée indisponible."
        humanConnection.label == "Accès révoqué" -> "Accès révoqué par le PC · réappairage requis."
        humanConnection.label == "Erreur de connexion" -> "Connexion au PC impossible."
        connectionLabel == "Hors ligne" -> "Données locales · aucun renvoi automatique."
        humanConnection.label == "Mode local" -> "Réponse locale, sans serveur distant."
        humanConnection.label == "Connexion en cours…" -> "Liaison en cours."
        humanConnection.label == "Connexion requise" -> "Jeton absent ou refusé."
        humanConnection.label == "Appairage nécessaire" -> "Appairage requis avant connexion."
        humanConnection.label == "Relais inactif" -> "Mode relayé inactif."
        humanConnection.label == "Connexion directe" -> "PC joignable en direct."
        rawTechnicalDetail != null && humanConnection.detail == rawTechnicalDetail -> "Un problème empêche la connexion au PC."
        else -> humanConnection.detail
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
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.small,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    connectionLabel,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag("agent_connection_status").semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(humanConnectionDetail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                rawTechnicalDetail?.let { detail ->
                    TextButton(
                        modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("agent_connection_technical_detail_toggle"),
                        onClick = { showTechnicalConnectionDetail = !showTechnicalConnectionDetail },
                    ) {
                        Text(if (showTechnicalConnectionDetail) "Masquer le détail technique" else "Détail technique")
                    }
                    if (showTechnicalConnectionDetail) {
                        Text(
                            detail,
                            modifier = Modifier.testTag("agent_connection_technical_detail"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        TextButton(
            modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("agent_refresh"),
            onClick = controller::pullAgentInbox,
        ) { Text("Actualiser") }
        if (controller.capabilities[AtlasController.CAP_COACH] != true) {
            Text("Coach local désactivé dans les paramètres.")
        } else {
            CoachQuickActions(controller)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Conversation", style = MaterialTheme.typography.titleLarge)
                if (controller.agentConversation.isEmpty()) {
                    Text("Écrivez un message pour démarrer. La démo locale fonctionne sans serveur.")
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
                                        readableMessageSourceLabel(entry.contextSources, latestProvenance),
                                        modifier = Modifier.testTag("agent_message_source"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
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
                    supportingText = { Text("2 000 caractères maximum · Envoyer transmet le message.") },
                    minLines = 2,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (message.isNotBlank() && controller.isAgentEnabled &&
                                controller.agentChatSendState != AgentChatSendState.SENDING &&
                                controller.sendAgentMessage(message)
                            ) message = ""
                        },
                    ),
                )
                Button(
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("agent_send_message"),
                    onClick = {
                        if (message.isNotBlank() && controller.isAgentEnabled &&
                            controller.agentChatSendState != AgentChatSendState.SENDING &&
                            controller.sendAgentMessage(message)
                        ) message = ""
                    },
                    enabled = message.isNotBlank() && controller.isAgentEnabled &&
                        controller.agentChatSendState != AgentChatSendState.SENDING,
                ) { Text("Envoyer") }
                val lastUserMessageIndex = controller.agentConversation.indexOfLast { it.role == AgentMessageRole.USER }
                val lastCoachMessageIndex = controller.agentConversation.indexOfLast { it.role == AgentMessageRole.ASSISTANT }
                val existingSendStatus = controller.agentChatStatusMessage?.let { status ->
                    when {
                        status == rawTechnicalDetail -> humanConnectionDetail
                        status.contains("requête relayée a échoué", ignoreCase = true) ->
                            "L'envoi a échoué. Aucun nouvel essai automatique ne sera tenté."
                        status.contains("requête directe a échoué", ignoreCase = true) ->
                            "L'envoi par la connexion directe a échoué."
                        status.contains("annulation HTTP directe", ignoreCase = true) ->
                            "L'annulation de cet envoi n'est pas garantie."
                        else -> status
                    }
                }
                val sendStatus = when {
                    controller.agentChatSendState == AgentChatSendState.SENDING -> "Envoi en cours…"
                    existingSendStatus != null -> existingSendStatus
                    lastCoachMessageIndex > lastUserMessageIndex && lastUserMessageIndex >= 0 -> "Réponse reçue"
                    else -> null
                }
                if (sendStatus != null) {
                    Text(
                        sendStatus,
                        modifier = Modifier.testTag("agent_chat_send_status").semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (controller.agentChatSendState == AgentChatSendState.SENDING) {
                    val relayMode = controller.relaySettings.mode == dev.atlas.coach.sync.relay.RelayTransportMode.RELAY
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("agent_cancel_send"),
                        enabled = relayMode,
                        onClick = controller::cancelAgentChat,
                    ) {
                        Text(if (relayMode) "Annuler l'envoi relayé" else "Annulation indisponible en mode direct")
                    }
                }
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
                        val otherProposalCount = pendingProposals.size - 1
                        val otherLabel = if (otherProposalCount == 1) "autre proposition" else "autres propositions"
                        Text("$otherProposalCount $otherLabel après celle-ci.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("agent_open_context"),
            onClick = onOpenContext,
        ) { Text("Contexte et confidentialité") }
    }

    editing?.let { entry ->
        ProposalEditDialog(
            entry = entry,
            onDismiss = { editing = null },
            onSave = { parameters -> controller.editAgentProposal(entry.id, parameters); editing = null },
        )
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
    Column(
        Modifier.fillMaxWidth()
            .border(
                BorderStroke(1.dp, if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)),
                RectangleShape,
            )
            .testTag("agent_priority_proposal_${entry.id}")
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
            Text("Proposition", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(actionLabels[entry.proposal.actionType] ?: entry.proposal.actionType, style = MaterialTheme.typography.titleMedium)
            Text("Motif : ${entry.proposal.reason}")
            if (highlighted) Text("Ouverte via notification", style = MaterialTheme.typography.labelMedium)
            Text("Provenance : $provenanceLabel", style = MaterialTheme.typography.bodySmall)
            contextCategories?.let { Text("Catégories : $it", style = MaterialTheme.typography.bodySmall) }
            Text("État : ${proposalStateLabel(entry)}", style = MaterialTheme.typography.bodySmall)
            if (showActions) {
                Text("Action à confirmer", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ProposalEditorialAction("Accepter", "agent_proposal_accept", emphasized = true, onClick = onAccept)
                    ProposalEditorialAction("Modifier", "agent_proposal_edit", onClick = onEdit)
                    ProposalEditorialAction("Plus tard", "agent_proposal_postpone", onClick = onPostpone)
                    ProposalEditorialAction("Refuser", "agent_proposal_reject", onClick = onReject)
                }
            }
    }
}

@Composable
private fun ProposalEditorialAction(label: String, tag: String, emphasized: Boolean = false, onClick: () -> Unit) {
    val ruleColor = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.62f)
    TextButton(
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag(tag),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (emphasized) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            )
            Spacer(Modifier.fillMaxWidth().height(if (emphasized) 1.5.dp else 1.dp).background(ruleColor))
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
