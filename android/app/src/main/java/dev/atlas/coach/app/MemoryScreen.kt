package dev.atlas.coach.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.atlas.coach.app.ui.AtlasRule
import dev.atlas.coach.memory.MemoryCategory
import dev.atlas.coach.memory.MemoryFact
import dev.atlas.coach.memory.MemorySensitivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Standalone memory screen; navigation and controller construction are owned by the parent. */
@Composable
fun MemoryScreen(controller: MemoryController, modifier: Modifier = Modifier) {
    var showAddPreference by remember { mutableStateOf(false) }
    var showRecoveryConfirmation by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Mémoire", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Relisez, corrigez ou effacez les informations locales ; chaque hypothèse attend votre confirmation.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        controller.quarantineState?.let { quarantine ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Mémoire en quarantaine", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Le document mémoire est illisible. Aucune donnée n'a été chargée et aucune " +
                            "écriture n'est autorisée avant une récupération explicite.",
                    )
                    Text(
                        if (quarantine.copyAvailable) {
                            "Copie conservée : ${quarantine.quarantineFileName}"
                        } else {
                            "La copie de sécurité n'a pas encore pu être créée. La récupération réessaiera de la conserver d'abord."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Détectée le ${formatMemoryInstant(quarantine.quarantinedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showRecoveryConfirmation = true },
                    ) { Text("Récupérer en repartant d'une mémoire vide") }
                }
            }
        } ?: run {
            controller.storageProblem?.let { problem ->
                Text(problem, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = controller.storageProblem == null,
                    onClick = { showAddPreference = true },
                ) { Text("Ajouter une préférence") }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = controller.storageProblem == null,
                    onClick = { controller.purgeRetention() },
                ) { Text("Purger les faits expirés") }
            }

            controller.message?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AtlasRule()
            if (controller.facts.isEmpty()) {
                Text("Aucune donnée en mémoire.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                controller.facts.forEach { fact ->
                    key(fact.factId) {
                        MemoryFactCard(fact = fact, controller = controller)
                    }
                }
            }
        }
    }

    if (showAddPreference) {
        AddPreferenceDialog(
            onDismiss = { showAddPreference = false },
            onSave = { statement, sensitivity, consent ->
                val saved = controller.addPreference(statement, sensitivity, consent)
                if (saved) showAddPreference = false
            },
        )
    }

    if (showRecoveryConfirmation) {
        AlertDialog(
            onDismissRequest = { showRecoveryConfirmation = false },
            title = { Text("Réinitialiser la mémoire locale ?") },
            text = {
                Text(
                    "Une mémoire vide remplacera le document illisible. La copie de quarantaine sera conservée. " +
                        "Les informations de cette copie ne seront pas réimportées automatiquement.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRecoveryConfirmation = false
                    controller.recoverByStartingEmpty()
                }) { Text("Confirmer la récupération") }
            },
            dismissButton = {
                TextButton(onClick = { showRecoveryConfirmation = false }) { Text("Annuler") }
            },
        )
    }
}

@Composable
private fun AddPreferenceDialog(
    onDismiss: () -> Unit,
    onSave: (String, MemorySensitivity, Boolean) -> Unit,
) {
    var statement by remember { mutableStateOf("") }
    var sensitivity by remember { mutableStateOf(MemorySensitivity.NORMAL) }
    var consent by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter une préférence") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = statement,
                    onValueChange = { statement = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Préférence") },
                    supportingText = { Text("1 à ${MemoryFact.MAX_STATEMENT_LENGTH} caractères") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    minLines = 2,
                    maxLines = 4,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = sensitivity == MemorySensitivity.SENSITIVE,
                        onCheckedChange = {
                            sensitivity = if (it) MemorySensitivity.SENSITIVE else MemorySensitivity.NORMAL
                        },
                    )
                    Text("Information sensible")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = consent, onCheckedChange = { consent = it })
                    Text("J'accepte d'enregistrer cette préférence dans la mémoire locale.")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = consent && statement.trim().isNotEmpty() &&
                    statement.trim().length <= MemoryFact.MAX_STATEMENT_LENGTH,
                onClick = { onSave(statement, sensitivity, consent) },
            ) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun MemoryFactCard(fact: MemoryFact, controller: MemoryController) {
    var editing by remember(fact.factId, fact.statement) { mutableStateOf(false) }
    var editedStatement by remember(fact.factId, fact.statement) { mutableStateOf(fact.statement) }
    var pendingAction by remember(fact.factId) { mutableStateOf<MemoryRowAction?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (fact.revoked) {
                Text("Révoquée", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
            }
            Text(fact.statement, style = MaterialTheme.typography.bodyLarge)
            AtlasRule()
            Text("Catégorie : ${fact.category.memoryLabel()}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Provenance : ${fact.provenance.source}" +
                    (fact.provenance.sourceId?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Observé le ${formatMemoryInstant(fact.provenance.observedAt)} · " +
                    "confiance ${(fact.provenance.confidence * 100).roundToInt()} % · " +
                    if (fact.provenance.userConfirmed) "confirmé par vous" else "non confirmé",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Sensibilité : ${if (fact.sensitivity == MemorySensitivity.SENSITIVE) "sensible" else "normale"} · " +
                    "expiration : ${fact.expiresAt?.let(::formatMemoryInstant) ?: "aucune"}",
                style = MaterialTheme.typography.bodySmall,
            )

            if (!fact.revoked && fact.category == MemoryCategory.HYPOTHESIS) {
                Text("Cette hypothèse n'est pas encore une préférence ou une habitude.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            controller.confirmHypothesis(fact.factId, MemoryCategory.PREFERENCE)
                        },
                    ) { Text("Confirmer comme préférence") }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            controller.confirmHypothesis(fact.factId, MemoryCategory.CONFIRMED_HABIT)
                        },
                    ) { Text("Confirmer comme habitude") }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = !fact.revoked,
                    onClick = {
                        editedStatement = fact.statement
                        editing = true
                    },
                ) { Text("Corriger") }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = !fact.revoked,
                    onClick = { pendingAction = MemoryRowAction.REVOKE },
                ) { Text("Révoquer") }
            }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { pendingAction = MemoryRowAction.DELETE },
            ) { Text("Supprimer") }
        }
    }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Corriger la mémoire") },
            text = {
                OutlinedTextField(
                    value = editedStatement,
                    onValueChange = { editedStatement = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Information corrigée") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    minLines = 2,
                    maxLines = 4,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = editedStatement.trim().isNotEmpty() &&
                        editedStatement.trim().length <= MemoryFact.MAX_STATEMENT_LENGTH,
                    onClick = {
                        if (controller.correct(fact.factId, editedStatement)) editing = false
                    },
                ) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Annuler") } },
        )
    }

    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(if (action == MemoryRowAction.DELETE) "Supprimer cette information ?" else "Révoquer cette information ?") },
            text = {
                Text(
                    if (action == MemoryRowAction.DELETE) {
                        "Cette information sera supprimée de la mémoire locale."
                    } else {
                        "Cette information ne sera plus utilisée. Elle restera visible jusqu'à sa suppression ou sa purge."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingAction = null
                    if (action == MemoryRowAction.DELETE) controller.delete(fact.factId)
                    else controller.revoke(fact.factId)
                }) { Text(if (action == MemoryRowAction.DELETE) "Supprimer" else "Révoquer") }
            },
            dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("Annuler") } },
        )
    }
}

private enum class MemoryRowAction { DELETE, REVOKE }

private fun MemoryCategory.memoryLabel(): String = when (this) {
    MemoryCategory.GOAL -> "objectif"
    MemoryCategory.PREFERENCE -> "préférence"
    MemoryCategory.CONFIRMED_HABIT -> "habitude confirmée"
    MemoryCategory.HYPOTHESIS -> "hypothèse"
    MemoryCategory.OBSERVATION -> "observation"
    MemoryCategory.AGGREGATE -> "agrégat"
}

private fun formatMemoryInstant(value: Instant): String =
    MEMORY_DATE_FORMATTER.format(value)

private val MEMORY_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter
    .ofPattern("dd/MM/yyyy HH:mm")
    .withZone(ZoneId.systemDefault())
