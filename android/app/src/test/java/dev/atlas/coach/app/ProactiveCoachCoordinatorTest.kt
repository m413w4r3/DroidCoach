package dev.atlas.coach.app

import dev.atlas.coach.coach.proactive.CoachTrigger
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
                ProactivePlanResponse(ProactiveNotificationContent("Plan prêt", "Ouvrez ATLAS."))
            },
            showNotification = { notifications += it.title },
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
        assertEquals(listOf("Plan prêt"), notifications)
        assertEquals(ProactiveCoachState.CURRENT_SCHEMA_VERSION, ProactiveCoachState.fromJsonString(requireNotNull(stateJson)).schemaVersion)
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
            requestPlan = { requests++; ProactivePlanResponse(ProactiveNotificationContent("Plan", "Texte")) },
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
}
