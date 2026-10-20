package dev.atlas.coach.app

import dev.atlas.coach.coach.proactive.CoachTrigger
import dev.atlas.coach.coach.proactive.CoachTriggerRecordStatus
import dev.atlas.coach.coach.proactive.ProactiveCoachScheduler
import dev.atlas.coach.coach.proactive.ProactiveCoachState
import dev.atlas.coach.coach.proactive.ProactiveConsentSnapshot
import dev.atlas.coach.coach.proactive.ProactiveEngagementMode
import dev.atlas.coach.coach.proactive.ProactiveNotification
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
    private val requestPlan: (ProactivePlanRequest) -> ProactivePlanResponse?,
    private val showNotification: (ProactiveNotification) -> Unit,
) {
    private val generation = AtomicLong(0L)
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
        if (!isEnabled()) {
            disableAndPurgePending()
            return ProactiveRunResult(ProactiveRunStatus.CONSENT_REVOKED)
        }
        scheduler.purgeRevoked()
        val validatedMandateZone = runCatching(mandateZoneId).getOrElse {
            return ProactiveRunResult(ProactiveRunStatus.PERSISTENCE_FAILED)
        }
        val runGeneration = generation.get()
        return scheduler.runDue(
            nowUtcMillis = nowUtcMillis(),
            mandateZoneId = validatedMandateZone,
            requestPlan = { request ->
                if (generation.get() != runGeneration || !isEnabled()) null else requestPlan(request)
            },
            showNotification = { notification ->
                if (generation.get() != runGeneration || !isEnabled()) {
                    throw IllegalStateException("Proactive capability was revoked")
                }
                showNotification(notification)
            },
        )
    }

    fun purgeRevoked(): Int = scheduler.purgeRevoked()

    /** Cancel queued effects immediately when the Coach, proactive capability, or emergency pause stops. */
    fun disableAndPurgePending() {
        generation.incrementAndGet()
        scheduler.cancelAllPending()
    }

    fun mode(): ProactiveEngagementMode = scheduler.mode() ?: ProactiveEngagementMode.NORMAL

    fun setMode(mode: ProactiveEngagementMode): Boolean = scheduler.setMode(mode)
}
