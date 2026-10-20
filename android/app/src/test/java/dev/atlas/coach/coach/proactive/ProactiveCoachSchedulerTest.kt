package dev.atlas.coach.coach.proactive

import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveCoachSchedulerTest {
    private val utc = ZoneId.of("UTC")
    private val allConsents = ProactiveConsentSnapshot(
        mapOf(
            "focus" to 1L,
            "goals" to 1L,
            "habit" to 1L,
            "app_usage_summary" to 1L,
            "location_summary" to 1L,
        ),
    )

    @Test
    fun `only the six declared reliable sources can be recorded`() {
        val h = Harness(allConsents)
        val sources = listOf(
            CoachTriggerSource.FOCUS_COMPLETED,
            CoachTriggerSource.GOAL_REVIEW_DUE,
            CoachTriggerSource.HABIT_REMINDER_DUE,
            CoachTriggerSource.DISTRACTION_THRESHOLD_REACHED,
            CoachTriggerSource.MOBILITY_SESSION_COMPLETED,
            CoachTriggerSource.DAILY_PLAN_DUE,
        )
        sources.forEachIndexed { index, source ->
            assertEquals(
                CoachTriggerRecordStatus.RECORDED,
                h.scheduler.record(trigger("source-$index", source), NOW),
            )
        }
        assertEquals(6, h.state.pendingTriggers.size)
        assertEquals(
            CoachTriggerRecordStatus.UNRELIABLE,
            h.scheduler.record(trigger("uncertain", reliability = CoachTriggerReliability.INSUFFICIENT_EVIDENCE), NOW),
        )
        assertEquals(
            CoachTriggerReliability.INSUFFICIENT_EVIDENCE,
            CoachTrigger("default-unverified", CoachTriggerSource.FOCUS_COMPLETED, NOW).reliability,
        )
        assertTrue(runCatching {
            CoachTrigger("consent-bypass", CoachTriggerSource.FOCUS_COMPLETED, NOW, consentCategory = null)
        }.isFailure)
        assertEquals(6, h.state.pendingTriggers.size)
    }

    @Test
    fun `record de-duplicates across scheduler recreation and state round trips`() {
        val first = Harness(allConsents)
        val event = trigger("stable-event")
        assertEquals(CoachTriggerRecordStatus.RECORDED, first.scheduler.record(event, NOW))
        assertEquals(CoachTriggerRecordStatus.DUPLICATE, first.scheduler.record(event, NOW))

        val restored = ProactiveCoachState.fromJsonString(first.state.toJsonString())
        first.state = restored
        assertEquals(CoachTriggerRecordStatus.DUPLICATE, first.scheduler.record(event, NOW))
        assertEquals(listOf(event.eventId), first.state.gateMemory.recentEventIds)
    }

    @Test
    fun `current grant timestamp and revocation purge related pending triggers`() {
        val h = Harness(ProactiveConsentSnapshot(mapOf("focus" to NOW)))
        val beforeConsent = trigger("old-focus", occurredAt = NOW - 1L)
        assertEquals(CoachTriggerRecordStatus.CONSENT_REQUIRED, h.scheduler.record(beforeConsent, NOW))

        val currentEvent = trigger("current-focus", occurredAt = NOW)
        assertEquals(CoachTriggerRecordStatus.RECORDED, h.scheduler.record(currentEvent, NOW))
        h.consent = ProactiveConsentSnapshot.EMPTY
        assertEquals(1, h.scheduler.purgeRevoked())
        assertTrue(h.state.pendingTriggers.isEmpty())

        h.consent = ProactiveConsentSnapshot(mapOf("focus" to NOW))
        assertEquals(CoachTriggerRecordStatus.DUPLICATE, h.scheduler.record(currentEvent, NOW))
        var requested = false
        assertEquals(
            ProactiveRunStatus.NO_ELIGIBLE_TRIGGERS,
            h.scheduler.runDue(NOW, utc, requestPlan = { requested = true; ProactivePlanResponse() }, showNotification = {}).status,
        )
        assertFalse(requested)
    }

    @Test
    fun `events are grouped into one plan call and the reservation is durable before network`() {
        val h = Harness(allConsents)
        h.record(trigger("one", motif = "focus-review"))
        h.record(trigger("two", motif = "focus-review"))
        var calls = 0
        val result = h.scheduler.runDue(
            NOW,
            utc,
            requestPlan = { request ->
                calls++
                assertEquals(2, request.triggers.size)
                assertEquals(ProactiveRequestStatus.RESERVED, h.state.requestReservations.single().status)
                assertTrue(h.state.pendingTriggers.isEmpty())
                assertEquals(2, h.state.consumedEventTombstones.size)
                ProactivePlanResponse()
            },
            showNotification = {},
        )
        assertEquals(ProactiveRunStatus.REQUESTED, result.status)
        assertEquals(1, calls)
        assertEquals(ProactiveRequestStatus.SUCCEEDED, h.state.requestReservations.single().status)
    }

    @Test
    fun `failed network reservation consumes event and does not retry on replay`() {
        val h = Harness(allConsents)
        h.record(trigger("failure", motif = "retry-proof"))
        var calls = 0
        val fail = h.scheduler.runDue(
            NOW,
            utc,
            requestPlan = { calls++; throw IllegalStateException("offline") },
            showNotification = {},
        )
        assertEquals(ProactiveRunStatus.REQUEST_FAILED, fail.status)
        assertEquals(ProactiveRequestStatus.FAILED, h.state.requestReservations.single().status)

        assertEquals(CoachTriggerRecordStatus.DUPLICATE, h.scheduler.record(trigger("failure", motif = "retry-proof"), NOW))
        val replay = h.scheduler.runDue(
            NOW + ProactiveTriggerGate.MIN_COOLDOWN_MS,
            utc,
            requestPlan = { calls++; ProactivePlanResponse() },
            showNotification = {},
        )
        assertEquals(ProactiveRunStatus.NO_ELIGIBLE_TRIGGERS, replay.status)
        assertEquals(1, calls)
    }

    @Test
    fun `same motif waits a full hour and passes at the boundary`() {
        val h = Harness(allConsents)
        h.record(trigger("cooldown-1", motif = "same-motif"))
        assertEquals(ProactiveRunStatus.REQUESTED, h.run(NOW).status)
        h.record(trigger("cooldown-2", motif = "same-motif", occurredAt = NOW + 1L))
        assertEquals(ProactiveRunStatus.MOTIF_COOLDOWN, h.run(NOW + ProactiveTriggerGate.MIN_COOLDOWN_MS - 1L).status)
        assertEquals(
            ProactiveRunStatus.REQUESTED,
            h.run(NOW + ProactiveTriggerGate.MIN_COOLDOWN_MS).status,
        )
        assertEquals(2, h.state.requestReservations.size)
    }

    @Test
    fun `six requests per local calendar day are reserved even when they fail`() {
        val h = Harness(allConsents)
        repeat(ProactiveCoachScheduler.MAX_REQUESTS_PER_LOCAL_DAY) { index ->
            h.record(trigger("quota-$index", motif = "quota-$index"))
            assertEquals(
                ProactiveRunStatus.REQUEST_FAILED,
                h.scheduler.runDue(
                    NOW + index,
                    utc,
                    requestPlan = { throw IllegalStateException("offline") },
                    showNotification = {},
                ).status,
            )
        }
        h.record(trigger("quota-over", motif = "quota-over", occurredAt = NOW + 20L))
        var calls = 0
        val result = h.scheduler.runDue(
            NOW + 20L,
            utc,
            requestPlan = { calls++; ProactivePlanResponse() },
            showNotification = {},
        )
        assertEquals(ProactiveRunStatus.DAILY_REQUEST_QUOTA, result.status)
        assertEquals(0, calls)
        assertEquals(1, h.state.pendingTriggers.size)
    }

    @Test
    fun `calendar quotas follow the supplied local timezone across UTC midnight`() {
        val h = Harness(allConsents)
        val honolulu = ZoneId.of("Pacific/Honolulu")
        val beforeUtcMidnight = Instant.parse("2026-10-19T23:10:00Z").toEpochMilli()
        repeat(6) { index ->
            h.record(trigger("honolulu-$index", motif = "honolulu-$index", occurredAt = beforeUtcMidnight + index))
            assertEquals(ProactiveRunStatus.REQUESTED, h.run(beforeUtcMidnight + index, honolulu).status)
        }
        val afterUtcMidnightSameHonoluluDay = Instant.parse("2026-10-20T00:30:00Z").toEpochMilli()
        h.record(trigger("honolulu-over", motif = "honolulu-over", occurredAt = afterUtcMidnightSameHonoluluDay))
        val result = h.run(afterUtcMidnightSameHonoluluDay, honolulu)
        assertEquals(ProactiveRunStatus.DAILY_REQUEST_QUOTA, result.status)
        assertEquals(6, h.state.requestReservations.size)
    }

    @Test
    fun `mandate timezone owns quota date when it differs from system timezone`() {
        withSystemTimezone(ZoneId.of("UTC")) {
            val h = Harness(allConsents)
            val mandateZone = ZoneId.of("Pacific/Honolulu")
            val beforeUtcMidnight = Instant.parse("2026-10-19T23:10:00Z").toEpochMilli()
            repeat(ProactiveCoachScheduler.MAX_REQUESTS_PER_LOCAL_DAY) { index ->
                h.record(trigger("mandate-zone-$index", motif = "mandate-zone-$index", occurredAt = beforeUtcMidnight + index))
                assertEquals(ProactiveRunStatus.REQUESTED, h.run(beforeUtcMidnight + index, mandateZone).status)
            }

            val afterUtcMidnightSameMandateDate = Instant.parse("2026-10-20T00:30:00Z").toEpochMilli()
            h.record(trigger("mandate-zone-over", motif = "mandate-zone-over", occurredAt = afterUtcMidnightSameMandateDate))
            assertEquals(
                ProactiveRunStatus.DAILY_REQUEST_QUOTA,
                h.run(afterUtcMidnightSameMandateDate, mandateZone).status,
            )
            assertEquals(6, h.state.requestReservations.size)
        }
    }

    @Test
    fun `mandate timezone applies DST quiet hours even when system timezone is UTC`() {
        withSystemTimezone(ZoneId.of("UTC")) {
            val h = Harness(allConsents)
            val paris = ZoneId.of("Europe/Paris")
            // On the 2026 spring DST date this is 22:30 CEST in Paris, while UTC is 20:30.
            val atParis2230 = Instant.parse("2026-03-29T20:30:00Z").toEpochMilli()
            h.record(trigger("dst-quiet", occurredAt = atParis2230))
            var requests = 0
            val result = h.scheduler.runDue(
                atParis2230,
                paris,
                requestPlan = { requests++; ProactivePlanResponse() },
                showNotification = {},
            )
            assertEquals(ProactiveRunStatus.QUIET_HOURS, result.status)
            assertEquals(0, requests)
        }
    }

    @Test
    fun `timestamped tombstones survive revocation purge and persistence then prune after replay window`() {
        val h = Harness(allConsents)
        val cancelled = trigger("cancelled-recent")
        h.record(cancelled)
        h.consent = ProactiveConsentSnapshot.EMPTY
        assertEquals(1, h.scheduler.purgeRevoked())
        assertEquals(NOW, h.state.consumedEventTombstones.single().consumedAtUtcMillis)

        val persistedJson = h.state.toJson().apply {
            getJSONObject("gate_memory").put("recent_event_ids", org.json.JSONArray())
        }
        h.state = ProactiveCoachState.fromJsonString(persistedJson.toString())
        assertEquals(NOW, h.state.consumedEventTombstones.single().consumedAtUtcMillis)
        h.consent = allConsents
        h.clockNow = NOW + ProactiveCoachScheduler.MAX_TRIGGER_AGE_MS - 1L
        assertEquals(
            CoachTriggerRecordStatus.DUPLICATE,
            h.scheduler.record(cancelled, h.clockNow),
        )

        val pruneAt = NOW + ProactiveCoachScheduler.MAX_TRIGGER_AGE_MS + 1L
        assertEquals(ProactiveRunStatus.NO_ELIGIBLE_TRIGGERS, h.run(pruneAt).status)
        assertTrue(h.state.consumedEventTombstones.isEmpty())
        h.state = ProactiveCoachState.fromJsonString(h.state.toJsonString())
        assertTrue(h.state.consumedEventTombstones.isEmpty())
        assertEquals(
            CoachTriggerRecordStatus.OUTSIDE_RETENTION,
            h.scheduler.record(cancelled, pruneAt),
        )
    }

    @Test
    fun `legacy state migrates string tombstones with reservation timestamps`() {
        val h = Harness(allConsents)
        h.record(trigger("legacy-consumed"))
        h.run(NOW)
        val legacyJson = h.state.toJson().apply {
            put("schema_version", 1)
            put("consumed_event_ids", org.json.JSONArray(listOf("legacy-consumed", "legacy-cancelled")))
        }.toString()

        val migrated = ProactiveCoachState.fromJsonString(legacyJson, migrationNowUtcMillis = NOW + 99L)
        assertEquals(ProactiveCoachState.CURRENT_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals("legacy-consumed", migrated.consumedEventTombstones.first().eventId)
        assertEquals(2, migrated.consumedEventTombstones.size)
        assertEquals(NOW, migrated.consumedEventTombstones.first().consumedAtUtcMillis)
        assertEquals(NOW + 99L, migrated.consumedEventTombstones.last().consumedAtUtcMillis)
    }

    @Test
    fun `quiet hours use local time and only manual triggers bypass them`() {
        val h = Harness(allConsents)
        val paris = ZoneId.of("Europe/Paris")
        val at2159 = Instant.parse("2026-10-19T19:59:00Z").toEpochMilli() // 21:59 CEST
        h.record(trigger("before-quiet", occurredAt = at2159))
        assertEquals(ProactiveRunStatus.REQUESTED, h.run(at2159, paris).status)

        val at2200 = Instant.parse("2026-10-19T20:00:00Z").toEpochMilli()
        h.record(trigger("quiet", motif = "quiet", occurredAt = at2200))
        assertEquals(ProactiveRunStatus.QUIET_HOURS, h.run(at2200, paris).status)

        val manual = trigger("manual", motif = "manual", occurredAt = at2200, manual = true)
        h.record(manual)
        assertEquals(ProactiveRunStatus.REQUESTED, h.run(at2200, paris).status)

        val at759 = Instant.parse("2026-10-20T05:59:00Z").toEpochMilli() // 07:59 CEST
        h.record(trigger("quiet-morning", motif = "quiet-morning", occurredAt = at759))
        assertEquals(ProactiveRunStatus.QUIET_HOURS, h.run(at759, paris).status)
        val at800 = Instant.parse("2026-10-20T06:00:00Z").toEpochMilli()
        h.record(trigger("morning", motif = "morning", occurredAt = at800))
        assertEquals(ProactiveRunStatus.REQUESTED, h.run(at800, paris).status)
    }

    @Test
    fun `normal and reinforced modes reserve at most three and five notifications`() {
        assertNotificationLimit(ProactiveEngagementMode.NORMAL, 3)
        assertNotificationLimit(ProactiveEngagementMode.REINFORCED, 5)
    }

    @Test
    fun `consent revoked before the network callback prevents the request`() {
        var reads = 0
        var consent = allConsents
        var state = ProactiveCoachState()
        val scheduler = ProactiveCoachScheduler(
            readState = { state },
            writeState = { state = it },
            currentConsent = {
                reads++
                if (reads >= 3) ProactiveConsentSnapshot.EMPTY else consent
            },
            clockUtcMillis = { NOW },
        )
        assertEquals(CoachTriggerRecordStatus.RECORDED, scheduler.record(trigger("revoke-race"), NOW))
        var calls = 0
        val result = scheduler.runDue(
            NOW,
            utc,
            requestPlan = { calls++; ProactivePlanResponse() },
            showNotification = {},
        )
        assertEquals(ProactiveRunStatus.CONSENT_REVOKED, result.status)
        assertEquals(0, calls)
        assertEquals(ProactiveRequestStatus.REVOKED_BEFORE_REQUEST, state.requestReservations.single().status)
    }

    @Test
    fun `consent revoked while the plan request is in flight suppresses its notification`() {
        var reads = 0
        val consent = allConsents
        var state = ProactiveCoachState()
        val scheduler = ProactiveCoachScheduler(
            readState = { state },
            writeState = { state = it },
            currentConsent = {
                reads++
                if (reads >= 4) ProactiveConsentSnapshot.EMPTY else consent
            },
            clockUtcMillis = { NOW },
        )
        assertEquals(CoachTriggerRecordStatus.RECORDED, scheduler.record(trigger("revoke-in-flight"), NOW))
        var notifications = 0
        val result = scheduler.runDue(
            NOW,
            utc,
            requestPlan = { ProactivePlanResponse(ProactiveNotificationContent("Conseil", "Action proposée")) },
            showNotification = { notifications++ },
        )
        assertEquals(ProactiveRunStatus.CONSENT_REVOKED, result.status)
        assertEquals(0, notifications)
        assertTrue(state.notificationReservations.isEmpty())
        assertEquals(ProactiveRequestStatus.SUCCEEDED, state.requestReservations.single().status)
    }

    @Test
    fun `failed notification callback consumes its reserved slot and is never repeated`() {
        val h = Harness(allConsents)
        h.record(trigger("notification-failure", motif = "notification-failure"))
        var requests = 0
        val first = h.scheduler.runDue(
            NOW,
            utc,
            requestPlan = { requests++; ProactivePlanResponse(ProactiveNotificationContent("Conseil", "Action proposée")) },
            showNotification = { throw IllegalStateException("notification unavailable") },
        )
        assertEquals(ProactiveRunStatus.NOTIFICATION_FAILED, first.status)
        assertEquals(ProactiveNotificationStatus.FAILED, h.state.notificationReservations.single().status)
        val replay = h.scheduler.runDue(
            NOW + ProactiveTriggerGate.MIN_COOLDOWN_MS,
            utc,
            requestPlan = { requests++; ProactivePlanResponse() },
            showNotification = {},
        )
        assertEquals(ProactiveRunStatus.NO_ELIGIBLE_TRIGGERS, replay.status)
        assertEquals(1, requests)
    }

    @Test
    fun `scheduler does not reset quotas if state schema is unknown`() {
        var writes = 0
        val scheduler = ProactiveCoachScheduler(
            readState = { throw IllegalArgumentException("Unsupported state schema") },
            writeState = { writes++ },
            currentConsent = { allConsents },
            clockUtcMillis = { NOW },
        )
        var requests = 0
        assertEquals(
            ProactiveRunStatus.PERSISTENCE_FAILED,
            scheduler.runDue(NOW, utc, requestPlan = { requests++; ProactivePlanResponse() }, showNotification = {}).status,
        )
        assertEquals(0, requests)
        assertEquals(0, writes)
        assertNull(scheduler.mode())
    }

    private fun assertNotificationLimit(mode: ProactiveEngagementMode, expectedLimit: Int) {
        val h = Harness(allConsents)
        assertTrue(h.scheduler.setMode(mode))
        repeat(expectedLimit + 1) { index ->
            val event = trigger("notification-$index", motif = "notification-$index")
            h.record(event)
            val result = h.scheduler.runDue(
                NOW + index,
                utc,
                requestPlan = { ProactivePlanResponse(ProactiveNotificationContent("Conseil", "Action proposée")) },
                showNotification = {},
            )
            if (index < expectedLimit) assertEquals(ProactiveRunStatus.REQUESTED, result.status)
            else assertEquals(ProactiveRunStatus.NOTIFICATION_QUOTA_REACHED, result.status)
        }
        assertEquals(expectedLimit, h.state.notificationReservations.size)
    }

    private fun trigger(
        id: String,
        source: CoachTriggerSource = CoachTriggerSource.FOCUS_COMPLETED,
        motif: String = "${source.wireValue}-motif",
        occurredAt: Long = NOW,
        reliability: CoachTriggerReliability = CoachTriggerReliability.VERIFIED,
        manual: Boolean = false,
    ) = CoachTrigger(
        eventId = id,
        source = source,
        occurredAtUtcMillis = occurredAt,
        motifKey = motif,
        reliability = reliability,
        manual = manual,
    )

    private inner class Harness(var consent: ProactiveConsentSnapshot) {
        var state = ProactiveCoachState()
        var clockNow = NOW
        val scheduler = ProactiveCoachScheduler(
            readState = { state },
            writeState = { state = it },
            currentConsent = { consent },
            clockUtcMillis = { clockNow },
        )

        fun record(event: CoachTrigger) {
            clockNow = event.occurredAtUtcMillis
            assertEquals(CoachTriggerRecordStatus.RECORDED, scheduler.record(event, event.occurredAtUtcMillis))
        }

        fun run(now: Long, zone: ZoneId = utc): ProactiveRunResult {
            clockNow = now
            return scheduler.runDue(
                now,
                zone,
                requestPlan = { ProactivePlanResponse() },
                showNotification = {},
            )
        }
    }

    private fun withSystemTimezone(zoneId: ZoneId, block: () -> Unit) {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
            block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    companion object {
        private const val NOW = 1_800_000_000_000L
    }
}
