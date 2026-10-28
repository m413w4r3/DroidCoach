package dev.atlas.coach.app

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.atlas.coach.app.ui.AtlasPrimaryButton
import dev.atlas.coach.app.ui.AtlasRule
import dev.atlas.coach.app.ui.AtlasSecondaryButton
import dev.atlas.coach.app.ui.AtlasSection
import dev.atlas.coach.app.ui.AtlasStatus
import dev.atlas.coach.app.ui.AtlasStatusKind
import dev.atlas.coach.app.ui.AtlasTertiaryButton
import dev.atlas.coach.sync.relay.RelayConnectionState
import dev.atlas.coach.sync.relay.RelaySettings
import dev.atlas.coach.sync.relay.RelayTransportMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Connection status and pairing actions stay readable before diagnostic values. */
@Composable
internal fun RelaySettingsScreen(relay: RelaySettingsController) {
    var showClear by remember { mutableStateOf(false) }
    var editingIdentity by rememberSaveable { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var showTechnicalDetail by rememberSaveable { mutableStateOf(false) }
    var relayUrl by remember(relay.relayUrl) { mutableStateOf(relay.relayUrl) }
    var serverId by remember(relay.serverId) { mutableStateOf(relay.serverId) }
    var serverPublicKey by remember(relay.serverPublicKey) { mutableStateOf(relay.serverPublicKey) }
    var deviceId by remember(relay.deviceId) { mutableStateOf(relay.deviceId) }
    var token by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    val connection = relayHumanConnectionState(relay.mode, relay.state, relay.lastError)
    val technicalError = relay.lastError?.takeIf(String::isNotBlank)
    val tlsError = technicalError?.takeIf(String::isTlsOrCertificateError)
    val humanDetail = when {
        tlsError != null -> "La connexion sécurisée au relais a échoué. Vérifiez le certificat et la configuration du relais."
        relay.state == RelayConnectionState.ERROR -> "La connexion au PC a échoué. Consultez le détail technique si le problème persiste."
        else -> connection.detail
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connexion relayée", style = MaterialTheme.typography.headlineSmall)
        Text(
            "État de la liaison avec le PC et gestion de l'appairage.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AtlasSection("État") {
            Column(
                modifier = Modifier.fillMaxWidth().border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                    MaterialTheme.shapes.medium,
                ).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                AtlasStatus(connection.label, relay.state.statusKind())
                Text(humanDetail, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Dernière connexion : ${relay.lastConnectedAtUtcMillis?.let(::formatInstant) ?: "jamais"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Identité d'appareil : ${if (relay.credentialsConfigured) "enregistrée" else "absente"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (technicalError != null) {
                    TextButton(onClick = { showTechnicalDetail = !showTechnicalDetail }) {
                        Text(if (showTechnicalDetail) "Masquer le détail technique" else "Détail technique")
                    }
                    if (showTechnicalDetail) {
                        Text(technicalError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        AtlasSection("Mode de connexion") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = relay.mode == RelayTransportMode.DIRECT,
                    onClick = { relay.selectMode(RelayTransportMode.DIRECT) },
                    label = { Text("Direct") },
                )
                FilterChip(
                    selected = relay.mode == RelayTransportMode.RELAY,
                    onClick = { relay.selectMode(RelayTransportMode.RELAY) },
                    label = { Text("Relais") },
                )
                FilterChip(
                    selected = relay.mode == RelayTransportMode.OFFLINE,
                    onClick = { relay.selectMode(RelayTransportMode.OFFLINE) },
                    label = { Text("Hors ligne") },
                )
            }
            Text(
                when (relay.mode) {
                    RelayTransportMode.DIRECT -> "Le PC est joint directement sur le réseau local."
                    RelayTransportMode.RELAY -> "Le PC est joint via un relais chiffré."
                    RelayTransportMode.OFFLINE -> "Le coach distant est désactivé; le plan local reste disponible."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Le chat relayé requiert coach.chat; réappairez l'appareil si cette capacité a changé.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (relay.credentialsConfigured) {
            AtlasSection("PC appairé") {
                Text("PC ATLAS", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (relay.mode) {
                        RelayTransportMode.RELAY -> "Relais actif"
                        RelayTransportMode.DIRECT -> "Connexion directe"
                        RelayTransportMode.OFFLINE -> "Mode hors ligne"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AtlasSecondaryButton(
                    "Modifier l'appairage",
                    onClick = {
                        editingIdentity = true
                        advancedExpanded = true
                    },
                )
            }
        } else {
            AtlasSection("Appairer ce téléphone") {
                Text(
                    "Créez un appareil sur le PC, puis saisissez le jeton remis une seule fois.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Jeton d'appareil") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                AtlasPrimaryButton(
                    label = "Enregistrer l'appairage",
                    onClick = {
                        val result = relay.saveConfiguration(
                            relayUrl,
                            serverId,
                            serverPublicKey,
                            deviceId,
                            token.ifBlank { null },
                        )
                        message = result.exceptionOrNull()?.message
                            ?: "Appairage enregistré. Le jeton n'est plus affiché."
                        if (result.isSuccess) token = ""
                    },
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AtlasSecondaryButton(
                label = "Connecter",
                onClick = { relay.connect() },
                enabled = relay.isRelayMode && relay.credentialsConfigured &&
                    relay.state !in setOf(RelayConnectionState.RELAY_CONNECTED, RelayConnectionState.RELAY_CONNECTING),
            )
            AtlasSecondaryButton(
                label = "Déconnecter",
                onClick = { relay.disconnect() },
                enabled = relay.state in setOf(
                    RelayConnectionState.RELAY_CONNECTED,
                    RelayConnectionState.RELAY_CONNECTING,
                    RelayConnectionState.OFFLINE,
                    RelayConnectionState.ERROR,
                ),
            )
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
            onClick = { advancedExpanded = !advancedExpanded },
        ) { Text(if (advancedExpanded) "Masquer Avancé" else "Avancé") }

        if (advancedExpanded) {
            AtlasSection("Avancé") {
                Text(
                    "Ces valeurs servent au diagnostic et à la configuration de la liaison.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = relayUrl,
                    onValueChange = { relayUrl = it },
                    label = { Text("URL du relais") },
                    placeholder = { Text(RelaySettings.DEFAULT_RELAY_URL) },
                    singleLine = true,
                    enabled = !relay.credentialsConfigured || editingIdentity,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = serverId,
                    onValueChange = { serverId = it },
                    label = { Text("Identifiant serveur (serverId)") },
                    singleLine = true,
                    enabled = !relay.credentialsConfigured || editingIdentity,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = serverPublicKey,
                    onValueChange = { serverPublicKey = it },
                    label = { Text("Clé publique X25519 du PC") },
                    singleLine = true,
                    enabled = !relay.credentialsConfigured || editingIdentity,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = deviceId,
                    onValueChange = { deviceId = it },
                    label = { Text("Identifiant de l'appareil (UUID)") },
                    singleLine = true,
                    enabled = !relay.credentialsConfigured || editingIdentity,
                )
                if (relay.credentialsConfigured) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Jeton d'appareil") },
                        placeholder = { Text("Laisser vide pour conserver le jeton") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = editingIdentity,
                    )
                    if (editingIdentity) {
                        AtlasPrimaryButton(
                            label = "Enregistrer et appliquer",
                            onClick = {
                                val result = relay.saveConfiguration(
                                    relayUrl,
                                    serverId,
                                    serverPublicKey,
                                    deviceId,
                                    token.ifBlank { null },
                                )
                                message = result.exceptionOrNull()?.message
                                    ?: "Configuration enregistrée. Le jeton n'est plus affiché."
                                if (result.isSuccess) {
                                    token = ""
                                    editingIdentity = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                        )
                    }
                } else {
                    Text(
                        "Le jeton d'appareil se saisit dans la carte d'appairage; sa valeur n'est jamais réaffichée.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                technicalError?.let {
                    Text("Dernier détail technique", style = MaterialTheme.typography.labelLarge)
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        AtlasRule()
        AtlasTertiaryButton(
            label = "Supprimer les identifiants de cet appareil",
            onClick = { showClear = true },
            enabled = relay.credentialsConfigured,
        )
        Text(
            "Pour révoquer l'accès, lancez la révocation sur le PC. Cette action efface les identifiants locaux.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("Supprimer l'appairage local ?") },
            text = { Text("Le jeton chiffré et l'identité seront supprimés de cet appareil. Le PC doit aussi révoquer l'appareil pour une révocation complète.") },
            confirmButton = {
                TextButton(onClick = {
                    relay.clearCredentials()
                    showClear = false
                    editingIdentity = false
                    message = "Identifiants locaux supprimés."
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { showClear = false }) { Text("Annuler") } },
        )
    }
}

private fun RelayConnectionState.statusKind(): AtlasStatusKind = when (this) {
    RelayConnectionState.RELAY_CONNECTED, RelayConnectionState.LOCAL -> AtlasStatusKind.ACTIVE
    RelayConnectionState.RELAY_CONNECTING -> AtlasStatusKind.SIMULATED
    RelayConnectionState.OFFLINE, RelayConnectionState.AUTH_REQUIRED,
    RelayConnectionState.PAIRING_REQUIRED, RelayConnectionState.REVOKED,
    RelayConnectionState.ERROR -> AtlasStatusKind.UNAVAILABLE
}

private fun String.isTlsOrCertificateError(): Boolean {
    val normalized = lowercase()
    return listOf("tls", "ssl", "certificat", "certificate", "x509", "pkix").any(normalized::contains)
}

private fun formatInstant(value: Long): String = DateTimeFormatter
    .ofPattern("dd/MM/yyyy HH:mm")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(value))
