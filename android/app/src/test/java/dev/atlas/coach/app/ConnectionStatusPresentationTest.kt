package dev.atlas.coach.app

import dev.atlas.coach.sync.relay.RelayConnectionState
import dev.atlas.coach.sync.relay.RelayTransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStatusPresentationTest {
    @Test
    fun offlineModeNeverPretendsToBeConnected() {
        val state = relayHumanConnectionState(RelayTransportMode.OFFLINE, RelayConnectionState.RELAY_CONNECTED)

        assertEquals("Mode local", state.label)
        assertEquals(ConnectionTone.LOCAL, state.tone)
    }

    @Test
    fun everyRelayStateHasOneHumanLabel() {
        val labels = RelayConnectionState.entries.map { state ->
            relayHumanConnectionState(RelayTransportMode.RELAY, state).label
        }

        assertEquals(RelayConnectionState.entries.size, labels.distinct().size)
        assertTrue(labels.none { it.contains("WSS", ignoreCase = true) })
        assertTrue(labels.none { it.contains("X25519", ignoreCase = true) })
        assertTrue(labels.none { it.contains("serverId", ignoreCase = true) })
    }

    @Test
    fun revocationAndPairingStayActionable() {
        val revoked = relayHumanConnectionState(
            RelayTransportMode.RELAY,
            RelayConnectionState.REVOKED,
            lastError = "appareil révoqué",
        )
        val pairing = relayHumanConnectionState(
            RelayTransportMode.RELAY,
            RelayConnectionState.PAIRING_REQUIRED,
        )

        assertEquals("Accès révoqué", revoked.label)
        assertEquals(ConnectionTone.ERROR, revoked.tone)
        assertEquals("appareil révoqué", revoked.technicalDetail)
        assertEquals("Appairage nécessaire", pairing.label)
        assertEquals(ConnectionTone.ACTION_REQUIRED, pairing.tone)
    }

    @Test
    fun transportErrorTextIsKeptForAdvancedDetailsOnly() {
        val state = relayHumanConnectionState(
            RelayTransportMode.RELAY,
            RelayConnectionState.ERROR,
            lastError = "TLS handshake failed",
        )

        assertEquals("Erreur de connexion", state.label)
        assertEquals("TLS handshake failed", state.detail)
        assertEquals("TLS handshake failed", state.technicalDetail)
    }

    @Test
    fun coachLocalModeIsExplicitAndHasNoTechnicalDetail() {
        val state = coachHumanConnectionState(
            RelayTransportMode.OFFLINE,
            RelayConnectionState.OFFLINE,
            agentStatusMessage = "unused",
        )

        assertEquals("Mode local", state.label)
        assertEquals(ConnectionTone.LOCAL, state.tone)
        assertNull(state.technicalDetail)
    }

    @Test
    fun coachRelayUsesTheSameHumanLabelsAsSettings() {
        RelayConnectionState.entries.forEach { connection ->
            assertEquals(
                relayHumanConnectionState(RelayTransportMode.RELAY, connection).label,
                coachHumanConnectionState(RelayTransportMode.RELAY, connection, "").label,
            )
        }
    }
}
