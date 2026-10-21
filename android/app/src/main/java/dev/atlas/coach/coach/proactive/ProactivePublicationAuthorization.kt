package dev.atlas.coach.coach.proactive

import java.util.Collections

/**
 * Immutable process-local capability epoch captured before consent reads or scheduler preparation.
 * It is never persisted or included in an HTTP payload; a reservation binds it to its exact triggers.
 */
data class ProactivePublicationEpoch(
    val capabilityGeneration: Long,
    val agentRequestGeneration: Long,
) {
    fun authorize(triggers: List<CoachTrigger>): ProactivePublicationAuthorization =
        ProactivePublicationAuthorization(
            capabilityGeneration = capabilityGeneration,
            agentRequestGeneration = agentRequestGeneration,
            triggers = Collections.unmodifiableList(ArrayList(triggers)),
        )
}

/**
 * Process-local authority for one exact proactive plan reservation. This value is never persisted
 * and is never included in an HTTP payload.
 */
data class ProactivePublicationAuthorization(
    val capabilityGeneration: Long,
    val agentRequestGeneration: Long,
    val triggers: List<CoachTrigger>,
)

/** A final authorization gate rejected a notification after its durable slot was reserved. */
class ProactivePublicationRejectedException(
    message: String = "Proactive publication authorization was revoked",
) : IllegalStateException(message)
