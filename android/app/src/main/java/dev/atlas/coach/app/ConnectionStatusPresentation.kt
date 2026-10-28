package dev.atlas.coach.app

import dev.atlas.coach.sync.relay.RelayConnectionState
import dev.atlas.coach.sync.relay.RelayTransportMode

/**
 * Shared, user-facing connection vocabulary. Technical identifiers and raw
 * transport errors stay in the advanced settings; this model only carries the
 * human state that the Coach and Connection screens must keep consistent.
 */
internal enum class ConnectionTone { CONNECTED, PROGRESS, LOCAL, ACTION_REQUIRED, ERROR }

internal data class HumanConnectionState(
    val label: String,
    val detail: String,
    val tone: ConnectionTone,
    val technicalDetail: String? = null,
)

internal fun relayHumanConnectionState(
    mode: RelayTransportMode,
    state: RelayConnectionState,
    lastError: String? = null,
): HumanConnectionState = when (mode) {
    RelayTransportMode.OFFLINE -> HumanConnectionState(
        label = "Mode local",
        detail = "Le coach distant est désactivé. Le plan et les données restent disponibles sur cet appareil.",
        tone = ConnectionTone.LOCAL,
    )
    RelayTransportMode.DIRECT -> when (state) {
        RelayConnectionState.ERROR -> HumanConnectionState(
            label = "Erreur de connexion",
            detail = lastError ?: "La connexion directe au PC a échoué.",
            tone = ConnectionTone.ERROR,
            technicalDetail = lastError,
        )
        else -> HumanConnectionState(
            label = "Connexion directe",
            detail = "Le PC est joint directement sur le réseau local autorisé.",
            tone = if (state == RelayConnectionState.RELAY_CONNECTED) ConnectionTone.CONNECTED else ConnectionTone.LOCAL,
        )
    }
    RelayTransportMode.RELAY -> when (state) {
        RelayConnectionState.RELAY_CONNECTED -> HumanConnectionState(
            label = "Connecté",
            detail = "Le PC répond via le relais chiffré.",
            tone = ConnectionTone.CONNECTED,
        )
        RelayConnectionState.RELAY_CONNECTING -> HumanConnectionState(
            label = "Connexion en cours…",
            detail = "La liaison avec le PC s'établit.",
            tone = ConnectionTone.PROGRESS,
        )
        RelayConnectionState.OFFLINE -> HumanConnectionState(
            label = "Hors ligne",
            detail = "Le PC n'est pas joignable. Les données restent locales et rien n'est renvoyé automatiquement.",
            tone = ConnectionTone.LOCAL,
        )
        RelayConnectionState.AUTH_REQUIRED -> HumanConnectionState(
            label = "Connexion requise",
            detail = "Le jeton de cet appareil est absent ou a été refusé.",
            tone = ConnectionTone.ACTION_REQUIRED,
            technicalDetail = lastError,
        )
        RelayConnectionState.PAIRING_REQUIRED -> HumanConnectionState(
            label = "Appairage nécessaire",
            detail = "Cet appareil doit être appairé au PC avant de se reconnecter.",
            tone = ConnectionTone.ACTION_REQUIRED,
            technicalDetail = lastError,
        )
        RelayConnectionState.REVOKED -> HumanConnectionState(
            label = "Accès révoqué",
            detail = "Le PC a révoqué cet appareil. Un réappairage est requis.",
            tone = ConnectionTone.ERROR,
            technicalDetail = lastError,
        )
        RelayConnectionState.ERROR -> HumanConnectionState(
            label = "Erreur de connexion",
            detail = lastError ?: "La connexion au relais a échoué.",
            tone = ConnectionTone.ERROR,
            technicalDetail = lastError,
        )
        RelayConnectionState.LOCAL -> HumanConnectionState(
            label = "Relais inactif",
            detail = "Le mode relayé n'est pas actif pour le moment.",
            tone = ConnectionTone.LOCAL,
        )
    }
}

/** Coach status derived from the same vocabulary; DIRECT keeps the agent message as detail. */
internal fun coachHumanConnectionState(
    mode: RelayTransportMode,
    state: RelayConnectionState,
    agentStatusMessage: String,
    lastError: String? = null,
): HumanConnectionState = when (mode) {
    RelayTransportMode.OFFLINE -> HumanConnectionState(
        label = "Mode local",
        detail = "Le coach répond localement, sans serveur distant.",
        tone = ConnectionTone.LOCAL,
    )
    RelayTransportMode.DIRECT -> if (state == RelayConnectionState.ERROR) {
        relayHumanConnectionState(mode, state, lastError)
    } else {
        HumanConnectionState(
            label = "Connexion directe",
            detail = agentStatusMessage.ifBlank { "Le PC est joint directement sur le réseau local autorisé." },
            tone = ConnectionTone.CONNECTED,
        )
    }
    RelayTransportMode.RELAY -> relayHumanConnectionState(mode, state, lastError)
}
