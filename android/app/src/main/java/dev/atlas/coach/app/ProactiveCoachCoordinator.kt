package dev.atlas.coach.app

import dev.atlas.coach.coach.proactive.CoachTrigger
import dev.atlas.coach.coach.proactive.CoachTriggerRecordStatus
import dev.atlas.coach.coach.proactive.ProactiveCoachScheduler
import dev.atlas.coach.coach.proactive.ProactiveCoachState
import dev.atlas.coach.coach.proactive.ProactiveConsentSnapshot
import dev.atlas.coach.coach.proactive.ProactiveEngagementMode
import dev.atlas.coach.coach.proactive.ProactiveNotification
import dev.atlas.coach.coach.proactive.ProactivePublicationAuthorization
import dev.atlas.coach.coach.proactive.ProactivePublicationEpoch
import dev.atlas.coach.coach.proactive.ProactivePublicationRejectedException
import dev.atlas.coach.coach.proactive.ProactivePlanRequest
import dev.atlas.coach.coach.proactive.ProactivePlanResponse
import dev.atlas.coach.coach.proactive.ProactiveRunResult
import dev.atlas.coach.coach.proactive.ProactiveRunStatus
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

/** App boundary for S5's versioned scheduler and its external plan/notification effects. */
class ProactiveCoachCoordinator(
    private val readStateJson: () -> String?,
    private val writeStateJson: (String) -> Unit,
    private val currentConsent: () -> ProactiveConsentSnapshot,
    private val isEnabled: () -> Boolean,
    private val nowUtcMillis: () -> Long,
    private val mandateZoneId: () -> ZoneId,
    private val capturePublicationEpoch: () -> ProactivePublicationEpoch,
    private val isPublicationAuthorizationCurrent: (ProactivePublicationAuthorization) -> Boolean,
    private val requestPlan: (ProactivePlanRequest, ProactivePublicationAuthorization) -> ProactivePlanResponse?,
    private val showNotification: (ProactiveNotification, ProactivePublicationAuthorization) -> Unit,
) {
    /**
     * Compatibility bridge for existing controller wiring. It snapshots the supplied generation
     * callback with an empty request before any run preparation; S5 should use the epoch callback.
     */
    constructor(
        readStateJson: () -> String?,
        writeStateJson: (String) -> Unit,
        currentConsent: () -> ProactiveConsentSnapshot,
        isEnabled: () -> Boolean,
        nowUtcMillis: () -> Long,
        mandateZoneId: () -> ZoneId,
        capturePublicationAuthorization: (ProactivePlanRequest) -> ProactivePublicationAuthorization,
        isPublicationAuthorizationCurrent: (ProactivePublicationAuthorization) -> Boolean,
        requestPlan: (ProactivePlanRequest, ProactivePublicationAuthorization) -> ProactivePlanResponse?,
        showNotification: (ProactiveNotification, ProactivePublicationAuthorization) -> Unit,
    ) : this(
        readStateJson = readStateJson,
        writeStateJson = writeStateJson,
        currentConsent = currentConsent,
        isEnabled = isEnabled,
        nowUtcMillis = nowUtcMillis,
        mandateZoneId = mandateZoneId,
        capturePublicationEpoch = {
            val captured = capturePublicationAuthorization(EPOCH_CAPTURE_REQUEST)
            require(captured.triggers.isEmpty()) { "Epoch capture must not bind triggers before reservation" }
            ProactivePublicationEpoch(captured.capabilityGeneration, captured.agentRequestGeneration)
        },
        isPublicationAuthorizationCurrent = isPublicationAuthorizationCurrent,
        requestPlan = requestPlan,
        showNotification = showNotification,
    )

    private val coordinatorGeneration = AtomicLong(0L)
    private val scheduler = ProactiveCoachScheduler(
        readState = {
            readStateJson()?.let { ProactiveCoachState.fromJsonString(it, nowUtcMillis()) } ?: ProactiveCoachState()
        },
        writeState = { writeStateJson(it.toJsonString()) },
        currentConsent = currentConsent,
        clockUtcMillis = nowUtcMillis,
    )

    fun record(event: CoachTrigger): CoachTriggerRecordStatus {
        if (!isEnabled()) return CoachTriggerRecordStatus.CONSENT_REQUIRED
        return scheduler.record(event)
    }

    fun recordAll(events: Iterable<CoachTrigger>): List<CoachTriggerRecordStatus> = events.map(::record)

    fun runDue(): ProactiveRunResult {
        // Capture once, before enablement, consent, persisted-state, clock, or timezone reads. The
        // scheduler later binds this exact epoch to the selected triggers before durable commit.
        val runGeneration = coordinatorGeneration.get()
        val publicationEpoch = runCatching(capturePublicationEpoch).getOrElse {
            return ProactiveRunResult(ProactiveRunStatus.CONSENT_REVOKED)
        }
        if (!isEnabled()) {
            disableAndPurgePending()
            return ProactiveRunResult(ProactiveRunStatus.CONSENT_REVOKED)
        }
        scheduler.purgeRevoked()
        val validatedMandateZone = runCatching(mandateZoneId).getOrElse {
            return ProactiveRunResult(ProactiveRunStatus.PERSISTENCE_FAILED)
        }
        fun authorizationIsCurrent(authorization: ProactivePublicationAuthorization): Boolean =
            coordinatorGeneration.get() == runGeneration &&
                authorization.capabilityGeneration == publicationEpoch.capabilityGeneration &&
                authorization.agentRequestGeneration == publicationEpoch.agentRequestGeneration && runCatching {
                isEnabled() && isPublicationAuthorizationCurrent(authorization)
            }.getOrDefault(false)

        return scheduler.runDue(
            nowUtcMillis = nowUtcMillis(),
            mandateZoneId = validatedMandateZone,
            publicationEpoch = publicationEpoch,
            isPublicationAuthorizationCurrent = ::authorizationIsCurrent,
            requestPlan = { request, authorization ->
                if (!authorizationIsCurrent(authorization) || authorization.triggers != request.triggers) {
                    null
                } else {
                    requestPlan(request, authorization)
                }
            },
            showNotification = { notification, authorization ->
                if (!authorizationIsCurrent(authorization) ||
                    authorization.triggers.map { it.source }.toSet() != notification.triggerSources
                ) {
                    throw ProactivePublicationRejectedException()
                }
                showNotification(notification, authorization)
            },
        )
    }

    fun purgeRevoked(): Int = scheduler.purgeRevoked()

    /** Cancel queued effects immediately when the Coach, proactive capability, or emergency pause stops. */
    fun disableAndPurgePending() {
        coordinatorGeneration.incrementAndGet()
        scheduler.cancelAllPending()
    }

    fun mode(): ProactiveEngagementMode = scheduler.mode() ?: ProactiveEngagementMode.NORMAL

    fun setMode(mode: ProactiveEngagementMode): Boolean = scheduler.setMode(mode)

    private companion object {
        val EPOCH_CAPTURE_REQUEST = ProactivePlanRequest(
            reservationId = "proactive-epoch-snapshot",
            requestedAtUtcMillis = 0L,
            mode = ProactiveEngagementMode.NORMAL,
            triggers = emptyList(),
        )
    }
}
