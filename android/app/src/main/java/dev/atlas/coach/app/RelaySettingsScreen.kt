package dev.atlas.coach.app

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
import dev.atlas.coach.sync.relay.RelayTransportMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Sober connection settings: mode, paired PC, state, last success, reconnect,
 * local credential removal. The device token is never displayed after save.
 */
@Composable
internal fun RelaySettingsScreen(relay: RelaySettingsController) {
    var showClear by remember { mutableStateOf(false) }
    var editingIdentity by rememberSaveable { mutableStateOf(!relay.credentialsConfigured) }
    var relayUrl by remember(relay.relayUrl) { mutableStateOf(relay.relayUrl) }
    var serverId by remember(relay.serverId) { mutableStateOf(relay.serverId) }
    var serverPublicKey by remember(relay.serverPublicKey) { mutableStateOf(relay.serverPublicKey) }
    var deviceId by remember(relay.deviceId) { mutableStateOf(relay.deviceId) }
    var token by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connexion relayée", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Le téléphone et le PC se connectent chacun au relais. Le contenu utile reste chiffré " +
                "de bout en bout; FastAPI reste sur le PC et aucun VPN n'est nécessaire.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

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
                    RelayTransportMode.DIRECT -> "Le PC est joint directement sur le réseau local autorisé."
                    RelayTransportMode.RELAY -> "Le PC est joint via le relais chiffré."
                    RelayTransportMode.OFFLINE -> "Le coach distant est désactivé; le plan local reste disponible."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AtlasSection("État") {
            AtlasStatus(relay.state.label(), relay.state.statusKind())
            Text("Dernière connexion : ${relay.lastConnectedAtUtcMillis?.let(::formatInstant) ?: "jamais"}")
            Text("Identité d'appareil : ${if (relay.credentialsConfigured) "enregistrée" else "absente"}")
            relay.lastError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (!relay.credentialsConfigured) {
                Text(
                    "Aucun appareil n'est appairé. Créez un appareil sur le PC, puis saisissez le jeton une seule fois.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (relay.credentialsConfigured && !editingIdentity) {
            AtlasSection("PC appairé") {
                Text("Relais : ${relay.relayUrl}")
                Text("Serveur : ${relay.serverId}")
                Text("Clé serveur enregistrée (non réaffichée).")
                AtlasSecondaryButton("Modifier l'appairage", onClick = { editingIdentity = true })
            }
        } else {
            AtlasSection("Configuration") {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = relayUrl,
                    onValueChange = { relayUrl = it },
                    label = { Text("URL du relais") },
                    placeholder = { Text("ws://10.0.2.2:4400") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = serverId,
                    onValueChange = { serverId = it },
                    label = { Text("Identifiant serveur") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = serverPublicKey,
                    onValueChange = { serverPublicKey = it },
                    label = { Text("Clé publique X25519 du PC") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = deviceId,
                    onValueChange = { deviceId = it },
                    label = { Text("Identifiant de l'appareil (UUID)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Jeton d'appareil") },
                    placeholder = {
                        Text(
                            if (relay.credentialsConfigured) "Laisser vide pour conserver le jeton"
                            else "Jeton remis une seule fois par le PC",
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                AtlasPrimaryButton(
                    label = "Enregistrer et appliquer",
                    onClick = {
                        val result = relay.saveConfiguration(relayUrl, serverId, serverPublicKey, deviceId, token.ifBlank { null })
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

        AtlasRule()
        AtlasTertiaryButton(
            label = "Supprimer les identifiants de cet appareil",
            onClick = { showClear = true },
            enabled = relay.credentialsConfigured,
        )
        Text(
            "La révocation définitive se fait sur le PC avec la commande de révocation de l'appareil. " +
                "La suppression locale empêche cet appareil de se reconnecter et efface le jeton chiffré.",
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
                    editingIdentity = true
                    message = "Identifiants locaux supprimés."
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { showClear = false }) { Text("Annuler") } },
        )
    }
}

private fun RelayConnectionState.label(): String = when (this) {
    RelayConnectionState.LOCAL -> "Local / direct"
    RelayConnectionState.RELAY_CONNECTING -> "Connexion au relais…"
    RelayConnectionState.RELAY_CONNECTED -> "Connecté via relais"
    RelayConnectionState.OFFLINE -> "Hors ligne"
    RelayConnectionState.AUTH_REQUIRED -> "Authentification requise"
    RelayConnectionState.PAIRING_REQUIRED -> "Appairage requis"
    RelayConnectionState.REVOKED -> "Appareil révoqué"
    RelayConnectionState.ERROR -> "Erreur de connexion"
}

private fun RelayConnectionState.statusKind(): AtlasStatusKind = when (this) {
    RelayConnectionState.RELAY_CONNECTED, RelayConnectionState.LOCAL -> AtlasStatusKind.ACTIVE
    RelayConnectionState.RELAY_CONNECTING -> AtlasStatusKind.SIMULATED
    RelayConnectionState.OFFLINE, RelayConnectionState.AUTH_REQUIRED,
    RelayConnectionState.PAIRING_REQUIRED, RelayConnectionState.REVOKED,
    RelayConnectionState.ERROR -> AtlasStatusKind.UNAVAILABLE
}

private fun formatInstant(value: Long): String = DateTimeFormatter
    .ofPattern("dd/MM/yyyy HH:mm")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(value))
