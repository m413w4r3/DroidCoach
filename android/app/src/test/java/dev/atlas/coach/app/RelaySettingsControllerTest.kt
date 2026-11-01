package dev.atlas.coach.app

import dev.atlas.coach.sync.relay.RelayConnectionState
import dev.atlas.coach.sync.relay.RelayFailure
import dev.atlas.coach.sync.relay.RelayFailureKind
import dev.atlas.coach.sync.relay.RelayTransportMode
import org.junit.Assert.assertEquals
import org.junit.Test

class RelaySettingsControllerTest {
    @Test
    fun revokedStateSurvivesOfflineErrorAndAuthenticationRequiredCallbacks() {
        listOf(
            RelayConnectionState.OFFLINE,
            RelayConnectionState.ERROR,
            RelayConnectionState.AUTH_REQUIRED,
        ).forEach { incoming ->
            assertEquals(
                RelayConnectionState.REVOKED,
                RelaySettingsPresentationPolicy.afterStateChange(
                    currentState = RelayConnectionState.REVOKED,
                    incomingState = incoming,
                ),
            )
        }
    }

    @Test
    fun revokedStateClearsToConnectedOnlyForExplicitRecovery() {
        assertEquals(
            RelayConnectionState.REVOKED,
            RelaySettingsPresentationPolicy.afterStateChange(
                currentState = RelayConnectionState.REVOKED,
                incomingState = RelayConnectionState.RELAY_CONNECTED,
            ),
        )
        assertEquals(
            RelayConnectionState.RELAY_CONNECTED,
            RelaySettingsPresentationPolicy.afterStateChange(
                currentState = RelayConnectionState.REVOKED,
                incomingState = RelayConnectionState.RELAY_CONNECTED,
                explicitRecovery = true,
            ),
        )
    }

    @Test
    fun nonRevokedTransitionsRemainUnchangedAndNetworkFailureDoesNotCreateRevocation() {
        assertEquals(
            RelayConnectionState.OFFLINE,
            RelaySettingsPresentationPolicy.afterStateChange(
                currentState = RelayConnectionState.RELAY_CONNECTING,
                incomingState = RelayConnectionState.OFFLINE,
            ),
        )
        assertEquals(
            RelayConnectionState.ERROR,
            RelaySettingsPresentationPolicy.afterStateChange(
                currentState = RelayConnectionState.OFFLINE,
                incomingState = RelayConnectionState.ERROR,
            ),
        )
        assertEquals(
            RelayFailureKind.NETWORK_UNREACHABLE,
            RelaySettingsPresentationPolicy.afterFailure(
                currentState = RelayConnectionState.OFFLINE,
                currentKind = null,
                failure = RelayFailure.of(RelayFailureKind.NETWORK_UNREACHABLE),
            ),
        )
    }

    @Test
    fun revokedFailureKindSurvivesLaterNetworkFailureWhileRevoked() {
        assertEquals(
            RelayFailureKind.REVOKED,
            RelaySettingsPresentationPolicy.afterFailure(
                currentState = RelayConnectionState.REVOKED,
                currentKind = RelayFailureKind.REVOKED,
                failure = RelayFailure.of(RelayFailureKind.NETWORK_UNREACHABLE),
            ),
        )
    }

    @Test
    fun revokedMessageReplacesEarlierErrorAndSurvivesLaterNetworkFailure() {
        val revokedMessage = RelayFailure.of(RelayFailureKind.REVOKED).message
        var lastError = RelaySettingsErrorPolicy.afterStateChange(
            state = RelayConnectionState.REVOKED,
            current = RelayFailure.of(RelayFailureKind.NETWORK_UNREACHABLE).message,
        )

        lastError = RelaySettingsErrorPolicy.afterFailure(
            currentState = RelayConnectionState.REVOKED,
            failure = RelayFailure.of(RelayFailureKind.NETWORK_UNREACHABLE),
            current = lastError,
        )

        assertEquals("Cet appareil a été révoqué sur le PC. Réappairez-le pour vous reconnecter.", revokedMessage)
        assertEquals(revokedMessage, lastError)
    }

    @Test
    fun revokedFailureRemainsAcceptedAfterStateChange() {
        val revoked = RelayFailure.of(RelayFailureKind.REVOKED)

        val lastError = RelaySettingsErrorPolicy.afterFailure(
            currentState = RelayConnectionState.REVOKED,
            failure = revoked,
            current = null,
        )

        assertEquals(revoked.message, lastError)
    }

    @Test
    fun connectDisabledReasonsCoverMissingPairingModeAndConnectionState() {
        assertEquals(
            listOf(
                "Appairage absent : créez l'appareil sur le PC, puis saisissez ici les informations fournies.",
                "Sélectionnez le mode « Relais » pour connecter le téléphone au PC.",
            ),
            relayConnectDisabledReasons(
                mode = RelayTransportMode.OFFLINE,
                credentialsConfigured = false,
                state = RelayConnectionState.OFFLINE,
            ),
        )
        assertEquals(
            listOf("Le téléphone est déjà connecté au PC."),
            relayConnectDisabledReasons(
                mode = RelayTransportMode.RELAY,
                credentialsConfigured = true,
                state = RelayConnectionState.RELAY_CONNECTED,
            ),
        )
        assertEquals(
            listOf("Connexion au PC en cours."),
            relayConnectDisabledReasons(
                mode = RelayTransportMode.RELAY,
                credentialsConfigured = true,
                state = RelayConnectionState.RELAY_CONNECTING,
            ),
        )
        assertEquals(
            emptyList<String>(),
            relayConnectDisabledReasons(
                mode = RelayTransportMode.RELAY,
                credentialsConfigured = true,
                state = RelayConnectionState.OFFLINE,
            ),
        )
    }
}
