package dev.atlas.coach.app

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
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.atlas.coach.sync.agent.AgentMode
import dev.atlas.coach.sync.agent.ProposalState
import dev.atlas.coach.sync.agent.coach.AgentMessageRole
import dev.atlas.coach.sync.agent.inbox.AgentInboxEntry
import dev.atlas.coach.sync.agent.dispatch.AgentEffectRecord

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
    "focus" to "Résumé Focus",
    "habit" to "Résumé des habitudes",
    "content" to "Bibliothèque de contenus",
    "coach_interaction" to "Échanges avec le Coach",
    "app_usage_summary" to "Résumé d'observation des applications",
)

@Composable
internal fun AgentCoachScreen(
    controller: AtlasController,
    pullOnOpen: Boolean = true,
    revision: Int = controller.revision,
) {
    var message by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<AgentInboxEntry?>(null) }

    LaunchedEffect(pullOnOpen) {
        if (pullOnOpen) controller.pullAgentInbox()
    }

    Column(
        // AtlasScreenFrame owns the screen's scroll container; nesting one here
        // gives Compose an unbounded height and crashes the Coach destination.
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            controller.agentStatusMessage.ifBlank { "Agent indisponible — fonctions locales actives" },
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
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (entry.role == AgentMessageRole.USER) "Vous" else "Coach", fontWeight = FontWeight.SemiBold)
                            Text(entry.text)
                            if (entry.contextSources.isNotEmpty()) {
                                Text("Sources de contexte : ${entry.contextSources.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
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

            key(revision) {
                val pending = controller.visibleAgentProposals
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Proposition prioritaire", style = MaterialTheme.typography.titleLarge)
                    val proposal = pending.firstOrNull()
                    if (proposal == null) Text("Aucune proposition en attente.")
                    else {
                        AgentProposalCard(
                            entry = proposal,
                            sourceSummary = controller.agentProposalSourceSummary(proposal.proposal),
                            onAccept = { controller.acceptAgentProposal(proposal.id) },
                            onEdit = { editing = proposal },
                            onPostpone = { controller.postponeAgentProposal(proposal.id) },
                            onReject = { controller.rejectAgentProposal(proposal.id) },
                        )
                        if (pending.size > 1) {
                            Text("${pending.size - 1} autre(s) proposition(s) apparaîtront après celle-ci.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

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
    sourceSummary: String,
    onAccept: () -> Unit,
    onEdit: () -> Unit,
    onPostpone: () -> Unit,
    onReject: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("agent_priority_proposal_${entry.id}")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(actionLabels[entry.proposal.actionType] ?: entry.proposal.actionType, style = MaterialTheme.typography.titleMedium)
            Text("Motif : ${entry.proposal.reason}")
            Text("Sources de contexte : $sourceSummary", style = MaterialTheme.typography.bodySmall)
            Text("État : ${proposalStateLabel(entry)}", style = MaterialTheme.typography.bodySmall)
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
    var start by remember(mandate.version) { mutableStateOf(mandate.focusWindow.start) }
    var end by remember(mandate.version) { mutableStateOf(mandate.focusWindow.end) }
    var maxMinutes by remember(mandate.version) { mutableStateOf(mandate.focusWindow.maxMinutesPerSession.toString()) }
    var maxSessions by remember(mandate.version) { mutableStateOf(mandate.focusWindow.maxSessionsPerDay.toString()) }
    var maxChanges by remember(mandate.version) { mutableStateOf(mandate.maxChangesPerDay.toString()) }
    var days by remember(mandate.version) { mutableStateOf(mandate.focusWindow.days) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Mandat et autonomie", style = MaterialTheme.typography.titleLarge)
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
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("L" to 1, "Ma" to 2, "Me" to 3, "J" to 4, "V" to 5, "S" to 6, "D" to 7).forEach { (label, day) ->
                Row(modifier = Modifier.sizeIn(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = day in days, onCheckedChange = { checked ->
                        days = if (checked) days + day else days - day
                    })
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(start, { start = it }, modifier = Modifier.weight(1f), label = { Text("Début HH:mm") }, singleLine = true)
            OutlinedTextField(end, { end = it }, modifier = Modifier.weight(1f), label = { Text("Fin HH:mm") }, singleLine = true)
        }
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Données partagées avec le coach", style = MaterialTheme.typography.titleLarge)
        Text("Désactivées au départ. Seules les catégories cochées sont indiquées dans les requêtes v2; les messages ne partent qu'après Envoyer.")
        dataCategoryLabels.forEach { (category, label) ->
            Row(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = category in mandate.sharedDataCategories,
                    onCheckedChange = { controller.setAgentSharedCategory(category, it) },
                )
                Text(label)
            }
        }
        if (mandate.sharedDataCategories.isEmpty()) {
            Text("Aucune donnée locale partagée.", color = MaterialTheme.colorScheme.primary)
        } else {
            Text("Catégories consenties : ${mandate.sharedDataCategories.sorted().joinToString(", ")}.")
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
            Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(actionLabels[entry.actionType] ?: entry.actionType, fontWeight = FontWeight.Medium)
                    Text("${entry.state.wireValue} · ${entry.displayTimestamp()}")
                    Text(entry.effect, style = MaterialTheme.typography.bodySmall)
                }
            }
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
