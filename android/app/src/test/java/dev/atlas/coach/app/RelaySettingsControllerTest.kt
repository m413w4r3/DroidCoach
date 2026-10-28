package dev.atlas.coach.app

import dev.atlas.coach.sync.relay.RelayConnectionState
import dev.atlas.coach.sync.relay.RelayFailure
import dev.atlas.coach.sync.relay.RelayFailureKind
import org.junit.Assert.assertEquals
import org.junit.Test

class RelaySettingsControllerTest {
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
}
