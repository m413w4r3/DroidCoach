package dev.atlas.coach.app

import dev.atlas.coach.sync.SyncStatus
import dev.atlas.coach.sync.agent.AgentBackendObservation
import dev.atlas.coach.sync.agent.AgentBackendState
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncStatusPresentationTest {
    private val now = 1_000_000L

    @Test
    fun disabledAndConsentStatesMatchTheControls() {
        assertEquals("Désactivée", present(enabled = false).label)
        assertEquals(
            "Activée · aucune catégorie consentie",
            present(hasConsent = false).label,
        )
    }

    @Test
    fun enabledSyncReportsMissingConfigurationAndFirstContactSeparately() {
        assertEquals(
            "Activée · configuration requise",
            present(backendUrlConfigured = false, tokenConfigured = false).label,
        )
        assertEquals(
            "Activée · configuration requise",
            present(tokenConfigured = false).label,
        )
        assertEquals("Activée · en attente de contact", present().label)
    }

    @Test
    fun recentBackendObservationReportsReachableAndExpiresAfterLotCTtl() {
        val recent = AgentBackendObservation(
            state = AgentBackendState.REACHABLE,
            checkedAtUtcMillis = now - 60_000L,
            lastSuccessfulContactAtUtcMillis = now - 60_000L,
        )
        assertEquals("Backend joignable", present(backendObservation = recent).label)

        val stale = recent.copy(
            checkedAtUtcMillis = now - AgentBackendObservation.FRESHNESS_TTL_MILLIS - 1L,
            lastSuccessfulContactAtUtcMillis = now - AgentBackendObservation.FRESHNESS_TTL_MILLIS - 1L,
        )
        assertEquals("Activée · contact à vérifier", present(backendObservation = stale).label)
    }

    @Test
    fun retryAndUnavailableObservationReportOffline() {
        val unavailable = AgentBackendObservation(
            state = AgentBackendState.UNAVAILABLE,
            checkedAtUtcMillis = now - 1_000L,
        )
        assertEquals("Hors ligne", present(backendObservation = unavailable).label)
        assertEquals("Hors ligne", present(status = SyncStatus.RETRY_SCHEDULED).label)
    }

    @Test
    fun activeRequestAndAuthenticationFailureKeepTheirOwnStates() {
        assertEquals("Contact en cours", present(inProgress = true).label)
        assertEquals("Authentification requise", present(status = SyncStatus.AUTH_REQUIRED).label)
    }

    private fun present(
        enabled: Boolean = true,
        hasConsent: Boolean = true,
        backendUrlConfigured: Boolean = true,
        tokenConfigured: Boolean = true,
        inProgress: Boolean = false,
        status: SyncStatus = SyncStatus.NOTHING_TO_SYNC,
        backendObservation: AgentBackendObservation = AgentBackendObservation(),
    ) = syncStatusPresentation(
        enabled = enabled,
        hasConsent = hasConsent,
        backendUrlConfigured = backendUrlConfigured,
        tokenConfigured = tokenConfigured,
        inProgress = inProgress,
        status = status,
        pendingCount = 0,
        statusMessage = "",
        backendObservation = backendObservation,
        nowUtcMillis = now,
    )
}
