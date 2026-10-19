package dev.atlas.coach.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.atlas.coach.sync.agent.AgentActionType
import dev.atlas.coach.sync.agent.AgentFocusCardState
import dev.atlas.coach.sync.agent.AgentMode
import dev.atlas.coach.sync.agent.ProposalState
import dev.atlas.coach.sync.agent.coach.AgentMessageRole
import dev.atlas.coach.sync.agent.inbox.AgentInboxEntry
import dev.atlas.coach.sync.agent.dispatch.AgentEffectRecord
import org.json.JSONArray
import org.json.JSONObject

private val actionLabels = linkedMapOf(
    "home.set_directive" to "Modifier la directive HOME",
    "home.reorder_cards" to "Réordonner les cartes HOME",
    "focus.propose" to "Proposer une session Focus",
    "focus.plan" to "Planifier une session Focus",
    "focus.start" to "Démarrer Focus",
    "focus.pause" to "Mettre Focus en pause",
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
internal fun AgentCoachScreen(controller: AtlasController, pullOnOpen: Boolean = true) {
    var message by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<AgentInboxEntry?>(null) }
    val mandate = controller.agentMandate

    LaunchedEffect(pullOnOpen) {
        if (pullOnOpen) controller.pullAgentInbox()
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Coach", style = MaterialTheme.typography.headlineSmall)
        Text(
            controller.agentStatusMessage.ifBlank { "Agent indisponible — fonctions locales actives" },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = controller::pullAgentInbox) { Text("Actualiser la boîte de réception") }
            OutlinedButton(
                onClick = { if (controller.isAgentEnabled) controller.disableAgent() else controller.enableAgent() },
            ) {
                Text(if (controller.isAgentEnabled) "Désactiver agent" else "Activer agent")
            }
        }

        if (controller.capabilities[AtlasController.CAP_COACH] != true) {
            Text("Coach local désactivé dans les paramètres.")
        } else {
            Text("Directive locale : ${controller.directive.title}", style = MaterialTheme.typography.titleMedium)
            if (controller.isCoachPaused) OutlinedButton(onClick = controller::resumeCoach) { Text("Réactiver le coach local") }
        }

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
                onClick = { controller.sendAgentMessage(message); message = "" },
                enabled = message.isNotBlank() && controller.isAgentEnabled,
            ) { Text("Envoyer") }
        }

        val pending = controller.visibleAgentProposals
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Propositions", style = MaterialTheme.typography.titleLarge)
            if (pending.isEmpty()) Text("Aucune proposition en attente.")
            pending.forEach { proposal ->
                AgentProposalCard(
                    entry = proposal,
                    sourceSummary = controller.agentProposalSourceSummary(proposal.proposal),
                    onAccept = { controller.acceptAgentProposal(proposal.id) },
                    onEdit = { editing = proposal },
                    onPostpone = { controller.postponeAgentProposal(proposal.id) },
                    onReject = { controller.rejectAgentProposal(proposal.id) },
                )
            }
            val history = controller.agentInbox.filter { it.proposal.state.terminal }
            history.takeLast(10).asReversed().forEach { entry ->
                AgentProposalStatusCard(entry)
            }
        }

        controller.agentHomeState.focusProposal?.let { focus ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Session proposée", style = MaterialTheme.typography.titleMedium)
                    Text("${focus.label} · ${focus.focusMinutes} min · ${focus.cycles} cycle(s)")
                    Text("État : ${focusStateLabel(focus.state)}")
                    focus.startAtUtcMillis?.let { Text("Heure prévue : ${java.time.Instant.ofEpochMilli(it)}") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (focus.state in setOf(AgentFocusCardState.PROPOSED, AgentFocusCardState.PLANNED)) {
                            Button(onClick = { controller.startAgentProposedFocus(focus.proposalId) }) { Text("Démarrer sans quitter le Coach") }
                        }
                        if (focus.state != AgentFocusCardState.CANCELLED && focus.state != AgentFocusCardState.FINISHED) {
                            OutlinedButton(onClick = { controller.cancelAgentFocusProposal(focus.proposalId) }) { Text("Annuler localement") }
                        }
                    }
                }
            }
        }

        controller.agentHomeState.directive?.let { directive ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Directive HOME proposée", style = MaterialTheme.typography.titleMedium)
                    Text(directive.title)
                    Text(directive.body)
                }
            }
        }
        controller.agentHomeState.contentRecommendation?.let { content ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Contenu recommandé sur HOME", style = MaterialTheme.typography.titleMedium)
                    Text(content.title)
                    Text(content.contentId ?: content.url.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    Text("L'ouverture reste manuelle.")
                }
            }
        }

        if (controller.agentSuggestions.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Suggestions locales", style = MaterialTheme.typography.titleLarge)
                controller.agentSuggestions.takeLast(8).reversed().forEach { Text("• $it") }
            }
        }

        MandatePanel(controller)
        SharedDataPanel(controller)
        EffectHistoryPanel(controller.agentEffectHistory)
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
private fun AgentProposalCard(
    entry: AgentInboxEntry,
    sourceSummary: String,
    onAccept: () -> Unit,
    onEdit: () -> Unit,
    onPostpone: () -> Unit,
    onReject: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(actionLabels[entry.proposal.actionType] ?: entry.proposal.actionType, style = MaterialTheme.typography.titleMedium)
            Text("Motif : ${entry.proposal.reason}")
            Text("Sources de contexte : $sourceSummary", style = MaterialTheme.typography.bodySmall)
            Text("État : ${proposalStateLabel(entry)}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onAccept) { Text("Accepter") }
                OutlinedButton(onClick = onEdit) { Text("Modifier") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onPostpone) { Text("Plus tard") }
                TextButton(onClick = onReject) { Text("Refuser") }
            }
        }
    }
}

@Composable
private fun AgentProposalStatusCard(entry: AgentInboxEntry) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(actionLabels[entry.proposal.actionType] ?: entry.proposal.actionType, fontWeight = FontWeight.Medium)
            Text("Statut : ${proposalStateLabel(entry)}")
            entry.effect?.let { Text("Effet : $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ProposalEditDialog(
    entry: AgentInboxEntry,
    onDismiss: () -> Unit,
    onSave: (Map<String, Any?>) -> Unit,
) {
    var raw by remember(entry.id) { mutableStateOf(JSONObject(entry.proposal.parameters).toString(2)) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifier la proposition") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Paramètres de ${entry.proposal.actionType}")
                OutlinedTextField(value = raw, onValueChange = { raw = it }, minLines = 5, maxLines = 10)
                if (invalid) Text("JSON ou paramètres invalides. La proposition n'a pas été enregistrée.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { JSONObject(raw).toParameterMap() }
                    .onSuccess(onSave)
                    .onFailure { invalid = true }
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
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
            OutlinedButton(onClick = controller::resumeAgentMandate) { Text("Réactiver le mandat") }
        } else {
            OutlinedButton(onClick = controller::suspendAgentMandate) { Text("Suspendre le mandat") }
        }
        OutlinedButton(onClick = controller::revokeAgentMandate) { Text("Révoquer tout") }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mode Actions préautorisées", modifier = Modifier.weight(1f))
            Switch(
                checked = mandate.mode == AgentMode.PREAUTHORIZED,
                onCheckedChange = { controller.setAgentMode(if (it) AgentMode.PREAUTHORIZED else AgentMode.PROPOSALS_ONLY) },
            )
        }
        Text(if (mandate.mode == AgentMode.PROPOSALS_ONLY) "Mode : Propositions seulement" else "Mode : Actions préautorisées dans les bornes locales")

        Text("Actions autorisées", style = MaterialTheme.typography.titleMedium)
        actionLabels.forEach { (wire, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
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
        OutlinedButton(onClick = {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    Text("${entry.state.wireValue} · ${java.time.Instant.ofEpochMilli(entry.atUtcMillis)}")
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

private fun focusStateLabel(state: AgentFocusCardState): String = when (state) {
    AgentFocusCardState.PROPOSED -> "Session proposée"
    AgentFocusCardState.PLANNED -> "Action planifiée"
    AgentFocusCardState.RUNNING -> "En cours"
    AgentFocusCardState.PAUSED -> "En pause"
    AgentFocusCardState.CANCELLED -> "Annulée localement"
    AgentFocusCardState.FINISHED -> "Terminée"
}

private fun JSONObject.toParameterMap(): Map<String, Any?> = buildMap {
    val source = this@toParameterMap
    val names = source.keys()
    while (names.hasNext()) {
        val key = names.next()
        put(key, source.get(key).toParameterValue())
    }
}

private fun Any?.toParameterValue(): Any? = when (this) {
    JSONObject.NULL -> null
    is JSONObject -> toParameterMap()
    is JSONArray -> (0 until length()).map { get(it).toParameterValue() }
    else -> this
}
