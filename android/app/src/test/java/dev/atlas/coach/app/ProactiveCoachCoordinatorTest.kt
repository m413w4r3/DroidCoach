package dev.atlas.coach.app

import dev.atlas.coach.coach.proactive.CoachTrigger
import dev.atlas.coach.coach.proactive.CoachTriggerRecordStatus
import dev.atlas.coach.coach.proactive.CoachTriggerReliability
import dev.atlas.coach.coach.proactive.CoachTriggerSource
import dev.atlas.coach.coach.proactive.ProactiveCoachState
import dev.atlas.coach.coach.proactive.ProactiveConsentSnapshot
import dev.atlas.coach.coach.proactive.ProactiveEngagementMode
import dev.atlas.coach.coach.proactive.ProactiveNotificationContent
import dev.atlas.coach.coach.proactive.ProactivePlanResponse
import dev.atlas.coach.coach.proactive.ProactiveRunStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ProactiveCoachCoordinatorTest {
    private val localProposalId = "10000000-0000-4000-8000-000000000001"

    @Test
    fun reliableLocalTriggerUsesPlanCallbackAndPersistsVersionedState() {
        val now = Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()
        val grantAt = now - 60_000L
        var stateJson: String? = null
        val requests = mutableListOf<String>()
        val notifications = mutableListOf<String>()
        val coordinator = ProactiveCoachCoordinator(
            readStateJson = { stateJson },
            writeStateJson = { stateJson = it },
            currentConsent = { ProactiveConsentSnapshot(mapOf("focus" to grantAt)) },
            isEnabled = { true },
            nowUtcMillis = { now },
            mandateZoneId = { ZoneId.of("UTC") },
            requestPlan = { request ->
                requests += request.triggers.single().source.wireValue
                ProactivePlanResponse(
                    notification = ProactiveNotificationContent("Plan prêt", "Ouvrez ATLAS."),
                    localProposalId = localProposalId,
                )
            },
            showNotification = { notifications += "${it.title}:${it.localProposalId}" },
        )

        val recorded = coordinator.record(
            CoachTrigger(
                eventId = "focus:session-1",
                source = CoachTriggerSource.FOCUS_COMPLETED,
                occurredAtUtcMillis = now,
                reliability = CoachTriggerReliability.VERIFIED,
            ),
        )
        val result = coordinator.runDue()

        assertEquals(dev.atlas.coach.coach.proactive.CoachTriggerRecordStatus.RECORDED, recorded)
        assertEquals(ProactiveRunStatus.REQUESTED, result.status)
        assertEquals(listOf("focus_completed"), requests)
        assertEquals(listOf("Plan prêt:$localProposalId"), notifications)
        assertEquals(ProactiveCoachState.CURRENT_SCHEMA_VERSION, ProactiveCoachState.fromJsonString(requireNotNull(stateJson)).schemaVersion)
    }

    @Test
    fun dueTriggerRunsWithoutActivityAfterQuietHoursAndIsNotReplayedAfterRecreation() {
        var now = Instant.parse("2026-10-20T21:00:00Z").toEpochMilli() // 23:00 in the mandate zone.
        val grantAt = now - 60_000L
        var stateJson: String? = null
        var requests = 0
        var notifications = 0
        fun coordinator() = ProactiveCoachCoordinator(
            readStateJson = { stateJson },
            writeStateJson = { stateJson = it },
            currentConsent = { ProactiveConsentSnapshot(mapOf("focus" to grantAt)) },
            isEnabled = { true },
            nowUtcMillis = { now },
            mandateZoneId = { ZoneId.of("Europe/Paris") },
            requestPlan = {
                requests++
                ProactivePlanResponse(
                    notification = ProactiveNotificationContent("Plan", "Ouvrez le Coach."),
                    localProposalId = localProposalId,
                )
            },
            showNotification = { notifications++ },
        )

        val firstProcess = coordinator()
        val due = CoachTrigger(
            eventId = "daily-plan:2026-10-20",
            source = CoachTriggerSource.DAILY_PLAN_DUE,
            occurredAtUtcMillis = now,
            reliability = CoachTriggerReliability.VERIFIED,
        )
        assertEquals(CoachTriggerRecordStatus.RECORDED, firstProcess.record(due))
        assertEquals(ProactiveRunStatus.QUIET_HOURS, firstProcess.runDue().status)
        assertEquals(0, requests)

        // A recreated process sees the same durable due event and reevaluates the injected clock.
        now = Instant.parse("2026-10-21T06:00:00Z").toEpochMilli() // 08:00 in Europe/Paris.
        val resumedProcess = coordinator()
        assertEquals(CoachTriggerRecordStatus.DUPLICATE, resumedProcess.record(due))
        assertEquals(ProactiveRunStatus.REQUESTED, resumedProcess.runDue().status)
        assertEquals(1, requests)
        assertEquals(1, notifications)

        val recreatedAfterReservation = coordinator()
        assertEquals(ProactiveRunStatus.NO_ELIGIBLE_TRIGGERS, recreatedAfterReservation.runDue().status)
        assertEquals(1, requests)
        assertEquals(1, notifications)
    }

    @Test
    fun capabilityRevocationClearsPendingEventsAndBlocksNewPlanRuns() {
        val now = Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()
        var enabled = true
        var stateJson: String? = null
        var requests = 0
        val coordinator = ProactiveCoachCoordinator(
            readStateJson = { stateJson },
            writeStateJson = { stateJson = it },
            currentConsent = { ProactiveConsentSnapshot(mapOf("focus" to now - 1_000L)) },
            isEnabled = { enabled },
            nowUtcMillis = { now },
            mandateZoneId = { ZoneId.of("UTC") },
            requestPlan = { requests++; ProactivePlanResponse() },
            showNotification = {},
        )
        coordinator.record(
            CoachTrigger("focus:session-2", CoachTriggerSource.FOCUS_COMPLETED, now, reliability = CoachTriggerReliability.VERIFIED),
        )

        enabled = false
        coordinator.disableAndPurgePending()
        val state = ProactiveCoachState.fromJsonString(requireNotNull(stateJson))
        val result = coordinator.runDue()

        assertTrue(state.pendingTriggers.isEmpty())
        assertEquals(ProactiveRunStatus.CONSENT_REVOKED, result.status)
        assertEquals(0, requests)
        assertEquals(ProactiveEngagementMode.NORMAL, coordinator.mode())
    }

    @Test
    fun corruptPersistedStateFailsClosedWithoutResettingQuotaOrOverwritingIt() {
        val now = Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()
        val corruptJson = """{"schema_version":999,"request_reservations":[]}"""
        var stateJson: String? = corruptJson
        var writes = 0
        var requests = 0
        var notifications = 0
        val coordinator = ProactiveCoachCoordinator(
            readStateJson = { stateJson },
            writeStateJson = { writes++; stateJson = it },
            currentConsent = { ProactiveConsentSnapshot(mapOf("focus" to now - 1_000L)) },
            isEnabled = { true },
            nowUtcMillis = { now },
            mandateZoneId = { ZoneId.of("UTC") },
            requestPlan = {
                requests++
                ProactivePlanResponse(
                    notification = ProactiveNotificationContent("Plan", "Texte"),
                    localProposalId = localProposalId,
                )
            },
            showNotification = { notifications++ },
        )

        val recordStatus = coordinator.record(
            CoachTrigger("focus:corrupt-state", CoachTriggerSource.FOCUS_COMPLETED, now,
                reliability = CoachTriggerReliability.VERIFIED),
        )
        val runResult = coordinator.runDue()
        coordinator.disableAndPurgePending()

        assertEquals(dev.atlas.coach.coach.proactive.CoachTriggerRecordStatus.PERSISTENCE_FAILED, recordStatus)
        assertEquals(ProactiveRunStatus.PERSISTENCE_FAILED, runResult.status)
        assertEquals(ProactiveEngagementMode.NORMAL, coordinator.mode())
        assertFalse(coordinator.setMode(ProactiveEngagementMode.REINFORCED))
        assertEquals(0, requests)
        assertEquals(0, notifications)
        assertEquals(0, writes)
        assertEquals(corruptJson, stateJson)
    }

    @Test
    fun failedDurableReservationMakesNoAgentCallOrNotification() {
        val now = Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()
        var stateJson: String? = null
        var failWrites = false
        var requests = 0
        var notifications = 0
        val coordinator = ProactiveCoachCoordinator(
            readStateJson = { stateJson },
            writeStateJson = {
                if (failWrites) throw IllegalStateException("private disk detail")
                stateJson = it
            },
            currentConsent = { ProactiveConsentSnapshot(mapOf("focus" to now - 1_000L)) },
            isEnabled = { true },
            nowUtcMillis = { now },
            mandateZoneId = { ZoneId.of("UTC") },
            requestPlan = {
                requests++
                ProactivePlanResponse(
                    notification = ProactiveNotificationContent("Plan", "Action"),
                    localProposalId = localProposalId,
                )
            },
            showNotification = { notifications++ },
        )
        coordinator.record(
            CoachTrigger(
                eventId = "focus:failed-reservation",
                source = CoachTriggerSource.FOCUS_COMPLETED,
                occurredAtUtcMillis = now,
                reliability = CoachTriggerReliability.VERIFIED,
            ),
        )
        val beforeReservation = stateJson
        failWrites = true

        val result = coordinator.runDue()

        assertEquals(ProactiveRunStatus.PERSISTENCE_FAILED, result.status)
        assertEquals(0, requests)
        assertEquals(0, notifications)
        assertEquals(beforeReservation, stateJson)
        assertTrue(ProactiveCoachState.fromJsonString(requireNotNull(stateJson)).requestReservations.isEmpty())
    }
}
