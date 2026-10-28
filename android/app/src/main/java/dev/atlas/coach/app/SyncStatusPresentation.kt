package dev.atlas.coach.app

import dev.atlas.coach.sync.SyncStatus
import dev.atlas.coach.sync.agent.AgentBackendObservation
import dev.atlas.coach.sync.agent.AgentBackendState

internal data class SyncStatusPresentation(val label: String, val detail: String)

/** User-facing Sync state derived from consent, configuration, transport and the Lot C observation. */
internal fun syncStatusPresentation(
    enabled: Boolean,
    hasConsent: Boolean,
    backendUrlConfigured: Boolean,
    tokenConfigured: Boolean,
    inProgress: Boolean,
    status: SyncStatus,
    pendingCount: Int,
    statusMessage: String,
    backendObservation: AgentBackendObservation,
    nowUtcMillis: Long,
): SyncStatusPresentation {
    val observedState = backendObservation.stateAt(nowUtcMillis)
    val hasPriorObservation = backendObservation.checkedAtUtcMillis != null ||
        backendObservation.lastSuccessfulContactAtUtcMillis != null
    val readinessNotes = buildList {
        if (!hasConsent) add("Aucune catégorie n'est consentie.")
        if (!backendUrlConfigured) add("URL backend manquante.")
        if (!tokenConfigured) add("Jeton Bearer manquant.")
    }.joinToString(" ")
    fun withReadinessNotes(detail: String): String =
        if (readinessNotes.isBlank()) detail else "$detail $readinessNotes"

    // Keep a current transport/authentication failure visible even when a consent
    // or configuration change would otherwise select an earlier generic state.
    if (enabled && status == SyncStatus.AUTH_REQUIRED) {
        return SyncStatusPresentation(
            label = "Authentification requise",
            detail = withReadinessNotes(statusMessage.ifBlank { "Le jeton manque ou a été refusé; les données restent locales." }),
        )
    }
    if (enabled && status in setOf(SyncStatus.CONTRACT_ERROR, SyncStatus.PROTOCOL_ERROR, SyncStatus.REJECTED)) {
        return SyncStatusPresentation(
            label = "Erreur de synchronisation",
            detail = withReadinessNotes(statusMessage.ifBlank { "La dernière réponse n'a pas pu être validée; les données restent locales." }),
        )
    }
    if (enabled && (status == SyncStatus.RETRY_SCHEDULED || observedState == AgentBackendState.UNAVAILABLE)) {
        return SyncStatusPresentation(
            label = "Hors ligne",
            detail = withReadinessNotes(statusMessage.ifBlank {
                if (pendingCount == 0) "Le dernier contact backend a échoué." else "$pendingCount événement(s) restent en attente sur cet appareil."
            }),
        )
    }

    if (!enabled) {
        return SyncStatusPresentation(
            label = "Désactivée",
            detail = "La file reste sur cet appareil; aucune connexion n'est ouverte.",
        )
    }
    if (!hasConsent) {
        return SyncStatusPresentation(
            label = "Activée · aucune catégorie consentie",
            detail = "Choisissez une catégorie pour autoriser son envoi.",
        )
    }
    if (!backendUrlConfigured || !tokenConfigured) {
        return SyncStatusPresentation(
            label = "Activée · configuration requise",
            detail = if (!backendUrlConfigured) {
                "Saisissez l'URL du backend; la file reste locale jusque-là."
            } else {
                "Un jeton Bearer est requis; sa valeur reste masquée."
            },
        )
    }
    if (inProgress) {
        return SyncStatusPresentation("Contact en cours", "La requête est en cours; les événements restent en file locale jusqu'à confirmation.")
    }

    if (observedState == AgentBackendState.REACHABLE) {
        return SyncStatusPresentation(
            label = "Backend joignable",
            detail = if (pendingCount == 0) "Un contact backend a réussi récemment."
            else "$pendingCount événement(s) restent en attente de confirmation.",
        )
    }
    if (hasPriorObservation) {
        return SyncStatusPresentation(
            label = "Activée · contact à vérifier",
            detail = "L'observation a expiré; un nouveau contact confirmera l'état.",
        )
    }
    return SyncStatusPresentation(
        label = "Activée · en attente de contact",
        detail = if (pendingCount == 0) {
            "Aucun contact n'a encore été vérifié; les données restent locales."
        } else {
            "$pendingCount événement(s) attendent le premier contact backend."
        },
    )
}
