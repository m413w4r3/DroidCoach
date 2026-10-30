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
    val connectDisabledReasons = relayConnectDisabledReasons(
        mode = relay.mode,
        credentialsConfigured = relay.credentialsConfigured,
        state = relay.state,
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connexion relayée", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Cette connexion relie le téléphone au PC. Le partage backend règle séparément les données Sync envoyées.",
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
                    RelayTransportMode.RELAY -> "Relais chiffré de bout en bout ; FastAPI reste sur le PC, sans VPN."
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
                    "1. Sur le PC, créez un appareil et copiez ses informations d'appairage.\n" +
                        "2. Collez-les ci-dessous, puis enregistrez.\n" +
                        "3. Choisissez « Relais » pour connecter le téléphone au PC.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RelayPairingFields(
                    relayUrl = relayUrl,
                    onRelayUrlChange = { relayUrl = it },
                    serverId = serverId,
                    onServerIdChange = { serverId = it },
                    serverPublicKey = serverPublicKey,
                    onServerPublicKeyChange = { serverPublicKey = it },
                    deviceId = deviceId,
                    onDeviceIdChange = { deviceId = it },
                    token = token,
                    onTokenChange = { token = it },
                    paired = false,
                    editing = true,
                    onSave = {
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
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AtlasSecondaryButton(
                label = "Connecter",
                onClick = { relay.connect() },
                enabled = connectDisabledReasons.isEmpty(),
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
        if (connectDisabledReasons.isNotEmpty()) {
            Text(
                connectDisabledReasons.joinToString(" "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
            onClick = { advancedExpanded = !advancedExpanded },
        ) { Text(if (advancedExpanded) "Masquer Avancé" else "Avancé") }

        if (advancedExpanded) {
            AtlasSection("Avancé") {
                Text(
                    "Diagnostics et détails de la liaison.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (relay.credentialsConfigured) {
                    Text("Identifiants techniques de l'appareil appairé.", style = MaterialTheme.typography.bodySmall)
                    RelayPairingFields(
                        relayUrl = relayUrl,
                        onRelayUrlChange = { relayUrl = it },
                        serverId = serverId,
                        onServerIdChange = { serverId = it },
                        serverPublicKey = serverPublicKey,
                        onServerPublicKeyChange = { serverPublicKey = it },
                        deviceId = deviceId,
                        onDeviceIdChange = { deviceId = it },
                        token = token,
                        onTokenChange = { token = it },
                        paired = true,
                        editing = editingIdentity,
                        onSave = {
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
                    )
                } else {
                    Text(
                        "Les informations d'appairage se saisissent dans la section visible ci-dessus.",
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

@Composable
private fun RelayPairingFields(
    relayUrl: String,
    onRelayUrlChange: (String) -> Unit,
    serverId: String,
    onServerIdChange: (String) -> Unit,
    serverPublicKey: String,
    onServerPublicKeyChange: (String) -> Unit,
    deviceId: String,
    onDeviceIdChange: (String) -> Unit,
    token: String,
    onTokenChange: (String) -> Unit,
    paired: Boolean,
    editing: Boolean,
    onSave: () -> Unit,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = relayUrl,
        onValueChange = onRelayUrlChange,
        label = { Text("URL du relais") },
        placeholder = { Text(RelaySettings.DEFAULT_RELAY_URL) },
        singleLine = true,
        enabled = editing,
    )
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = serverId,
        onValueChange = onServerIdChange,
        label = { Text("Identifiant serveur (serverId)") },
        singleLine = true,
        enabled = editing,
    )
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = serverPublicKey,
        onValueChange = onServerPublicKeyChange,
        label = { Text("Clé publique X25519 du PC") },
        singleLine = true,
        enabled = editing,
    )
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = deviceId,
        onValueChange = onDeviceIdChange,
        label = { Text("Identifiant de l'appareil (UUID)") },
        singleLine = true,
        enabled = editing,
    )
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = token,
        onValueChange = onTokenChange,
        label = { Text("Jeton d'appareil") },
        placeholder = {
            Text(if (paired) "Laisser vide pour conserver le jeton" else "Jeton fourni lors de la création sur le PC")
        },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        enabled = editing,
    )
    if (paired) {
        if (editing) {
            AtlasPrimaryButton(
                label = "Enregistrer et appliquer",
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
            )
        }
    } else {
        Text(
            "Le jeton est protégé par Android Keystore et n'est jamais réaffiché.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AtlasPrimaryButton(
            label = "Enregistrer l'appairage",
            onClick = onSave,
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
        )
    }
}

internal fun relayConnectDisabledReasons(
    mode: RelayTransportMode,
    credentialsConfigured: Boolean,
    state: RelayConnectionState,
): List<String> = buildList {
    if (!credentialsConfigured) {
        add("Appairage absent : créez l'appareil sur le PC, puis saisissez ici les informations fournies.")
    }
    if (mode != RelayTransportMode.RELAY) {
        add("Sélectionnez le mode « Relais » pour connecter le téléphone au PC.")
    }
    when (state) {
        RelayConnectionState.RELAY_CONNECTED -> add("Le téléphone est déjà connecté au PC.")
        RelayConnectionState.RELAY_CONNECTING -> add("Connexion au PC en cours.")
        else -> Unit
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
