package dev.atlas.coach.coach.proactive

import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveCoachSchedulerTest {
    private val utc = ZoneId.of("UTC")
    private val localProposalId = "20000000-0000-4000-8000-000000000002"
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
            h.scheduler.runAuthorized(NOW, utc, requestPlan = { requested = true; ProactivePlanResponse() }, showNotification = {}).status,
        )
        assertFalse(requested)
    }

    @Test
    fun `events are grouped into one plan call and the reservation is durable before network`() {
        val h = Harness(allConsents)
        h.record(trigger("one", motif = "focus-review"))
        h.record(trigger("two", motif = "focus-review"))
        var calls = 0
        val result = h.scheduler.runAuthorized(
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
        val fail = h.scheduler.runAuthorized(
            NOW,
            utc,
            requestPlan = { calls++; throw IllegalStateException("offline") },
            showNotification = {},
        )
        assertEquals(ProactiveRunStatus.REQUEST_FAILED, fail.status)
        assertEquals(ProactiveRequestStatus.FAILED, h.state.requestReservations.single().status)

        assertEquals(CoachTriggerRecordStatus.DUPLICATE, h.scheduler.record(trigger("failure", motif = "retry-proof"), NOW))
        val replay = h.scheduler.runAuthorized(
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
                h.scheduler.runAuthorized(
                    NOW + index,
                    utc,
                    requestPlan = { throw IllegalStateException("offline") },
                    showNotification = {},
                ).status,
            )
        }
        h.record(trigger("quota-over", motif = "quota-over", occurredAt = NOW + 20L))
        var calls = 0
        val result = h.scheduler.runAuthorized(
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
            val result = h.scheduler.runAuthorized(
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
    fun `authorization invalidated after capture prevents network request`() {
        var generation = 7L
        var state = ProactiveCoachState()
        val scheduler = ProactiveCoachScheduler(
            readState = { state },
            writeState = { state = it },
            currentConsent = { allConsents },
            clockUtcMillis = { NOW },
        )
        assertEquals(CoachTriggerRecordStatus.RECORDED, scheduler.record(trigger("revoke-before-request"), NOW))
        var calls = 0
        val result = scheduler.runAuthorized(
            NOW,
            utc,
            requestPlan = { calls++; ProactivePlanResponse() },
            showNotification = {},
            publicationEpoch = ProactivePublicationEpoch(generation, 3L),
            isAuthorizationCurrent = { authorization ->
                generation++
                authorization.capabilityGeneration == generation
            },
        )
        assertEquals(ProactiveRunStatus.CONSENT_REVOKED, result.status)
        assertEquals(0, calls)
        assertTrue(state.requestReservations.isEmpty())
        assertEquals(listOf("revoke-before-request"), state.consumedEventTombstones.map { it.eventId })
    }

    @Test
    fun `consent revoked before the network callback prevents the request`() {
        var consent = allConsents
        var generation = 31L
        var state = ProactiveCoachState()
        val scheduler = ProactiveCoachScheduler(
            readState = { state },
            writeState = { next ->
                state = next
                if (next.requestReservations.lastOrNull()?.status == ProactiveRequestStatus.RESERVED) {
                    consent = ProactiveConsentSnapshot.EMPTY
                }
            },
            currentConsent = { consent },
            clockUtcMillis = { NOW },
        )
        assertEquals(CoachTriggerRecordStatus.RECORDED, scheduler.record(trigger("consent-revoke-before"), NOW))
        var calls = 0
        val result = scheduler.runAuthorized(
            NOW,
            utc,
            requestPlan = { calls++; ProactivePlanResponse() },
            showNotification = {},
            publicationEpoch = ProactivePublicationEpoch(generation, 32L),
            isAuthorizationCurrent = { true },
        )

        assertEquals(ProactiveRunStatus.CONSENT_REVOKED, result.status)
        assertEquals(0, calls)
        assertEquals("current consent alone rejects the request", 31L, generation)
        assertEquals(ProactiveRequestStatus.REVOKED_BEFORE_REQUEST, state.requestReservations.single().status)
    }

    @Test
    fun `authorization revoked while network response is in flight drops the late response`() {
        val generation = AtomicLong(11L)
        var state = ProactiveCoachState()
        val scheduler = ProactiveCoachScheduler(
            readState = { state },
            writeState = { state = it },
            currentConsent = { allConsents },
            clockUtcMillis = { NOW },
        )
        assertEquals(CoachTriggerRecordStatus.RECORDED, scheduler.record(trigger("revoke-in-flight"), NOW))
        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val resultReference = AtomicReference<ProactiveRunResult>()
        val notificationCount = AtomicLong(0L)
        val run = Thread {
            resultReference.set(scheduler.runAuthorized(
                NOW,
                utc,
                requestPlan = {
                    requestStarted.countDown()
                    check(releaseResponse.await(5, TimeUnit.SECONDS)) { "Response barrier timed out" }
                    responseWithLocalProposal()
                },
                showNotification = { notificationCount.incrementAndGet() },
                publicationEpoch = ProactivePublicationEpoch(generation.get(), 4L),
                isAuthorizationCurrent = { it.capabilityGeneration == generation.get() },
            ))
        }
        run.start()
        assertTrue("Plan request did not reach the controlled network barrier", requestStarted.await(5, TimeUnit.SECONDS))
        generation.incrementAndGet()
        releaseResponse.countDown()
        run.join(5_000L)
        assertFalse("Scheduler did not finish after response release", run.isAlive)

        val result = requireNotNull(resultReference.get())
        assertEquals(ProactiveRunStatus.CONSENT_REVOKED, result.status)
        assertEquals(0L, notificationCount.get())
        assertTrue(state.notificationReservations.isEmpty())
        assertEquals(ProactiveRequestStatus.FAILED, state.requestReservations.single().status)
        assertEquals(listOf("revoke-in-flight"), state.consumedEventTombstones.map { it.eventId })
    }

    @Test
    fun `consent revoked while the plan request is in flight suppresses its notification`() {
        var consent = allConsents
        var generation = 41L
        var state = ProactiveCoachState()
        val scheduler = ProactiveCoachScheduler(
            readState = { state },
            writeState = { state = it },
            currentConsent = { consent },
            clockUtcMillis = { NOW },
        )
        assertEquals(CoachTriggerRecordStatus.RECORDED, scheduler.record(trigger("consent-revoke-in-flight"), NOW))
        var notifications = 0
        val result = scheduler.runAuthorized(
            NOW,
            utc,
            requestPlan = {
                consent = ProactiveConsentSnapshot.EMPTY
                responseWithLocalProposal()
            },
            showNotification = { notifications++ },
            publicationEpoch = ProactivePublicationEpoch(generation, 42L),
            isAuthorizationCurrent = { true },
        )

        assertEquals(ProactiveRunStatus.CONSENT_REVOKED, result.status)
        assertEquals(0, notifications)
        assertTrue(state.notificationReservations.isEmpty())
        assertEquals(ProactiveRequestStatus.FAILED, state.requestReservations.single().status)
        assertEquals(41L, generation)
    }

    @Test
    fun `authorization revoked during response handling prevents notification reservation`() {
        var generation = 19L
        lateinit var state: ProactiveCoachState
        val scheduler = ProactiveCoachScheduler(
            readState = { state },
            writeState = {
                state = it
                if (it.requestReservations.any { reservation -> reservation.status == ProactiveRequestStatus.SUCCEEDED }) {
                    generation++
                }
            },
            currentConsent = { allConsents },
            clockUtcMillis = { NOW },
        )
        state = ProactiveCoachState()
        assertEquals(CoachTriggerRecordStatus.RECORDED, scheduler.record(trigger("revoke-processing"), NOW))
        var notifications = 0
        val result = scheduler.runAuthorized(
            NOW,
            utc,
            requestPlan = { responseWithLocalProposal() },
            showNotification = { notifications++ },
            publicationEpoch = ProactivePublicationEpoch(generation, 5L),
            isAuthorizationCurrent = { it.capabilityGeneration == generation },
        )

        assertEquals(ProactiveRunStatus.CONSENT_REVOKED, result.status)
        assertEquals(0, notifications)
        assertTrue(state.notificationReservations.isEmpty())
        assertEquals(ProactiveRequestStatus.SUCCEEDED, state.requestReservations.single().status)
    }

    @Test
    fun `revocation after notification reservation fails reserved slot without replay`() {
        var generation = 23L
        lateinit var state: ProactiveCoachState
        val scheduler = ProactiveCoachScheduler(
            readState = { state },
            writeState = {
                state = it
                if (it.notificationReservations.any { reservation -> reservation.status == ProactiveNotificationStatus.RESERVED }) {
                    generation++
                }
            },
            currentConsent = { allConsents },
            clockUtcMillis = { NOW },
        )
        state = ProactiveCoachState()
        assertEquals(CoachTriggerRecordStatus.RECORDED, scheduler.record(trigger("revoke-after-reserve"), NOW))
        var notifications = 0
        val result = scheduler.runAuthorized(
            NOW,
            utc,
            requestPlan = { responseWithLocalProposal() },
            showNotification = { notifications++ },
            publicationEpoch = ProactivePublicationEpoch(generation, 6L),
            isAuthorizationCurrent = { it.capabilityGeneration == generation },
        )

        assertEquals(ProactiveRunStatus.CONSENT_REVOKED, result.status)
        assertEquals(0, notifications)
        assertEquals(ProactiveNotificationStatus.FAILED, state.notificationReservations.single().status)
        assertEquals("proactive-1-notification", state.notificationReservations.single().notificationId)
        assertEquals(listOf("revoke-after-reserve"), state.consumedEventTombstones.map { it.eventId })
        assertEquals(1, state.requestReservations.size)
        assertEquals(ProactiveRunStatus.NO_ELIGIBLE_TRIGGERS, scheduler.runAuthorized(
            NOW + ProactiveTriggerGate.MIN_COOLDOWN_MS,
            utc,
            requestPlan = { ProactivePlanResponse() },
            showNotification = {},
        ).status)
    }

    @Test
    fun `notification is posted without a local proposal id`() {
        val h = Harness(allConsents)
        h.record(trigger("unlinked-notification"))
        val notifications = mutableListOf<ProactiveNotification>()

        val result = h.scheduler.runAuthorized(
            NOW,
            utc,
            requestPlan = { ProactivePlanResponse(ProactiveNotificationContent("Conseil", "Action proposée")) },
            showNotification = { notifications += it },
        )

        assertEquals(ProactiveRunStatus.REQUESTED, result.status)
        assertEquals(1, notifications.size)
        assertNull(notifications.single().localProposalId)
        assertEquals(1, h.state.notificationReservations.size)
    }

    @Test
    fun `notification is posted with the validated local proposal id when available`() {
        val h = Harness(allConsents)
        h.record(trigger("linked-notification"))
        val notifications = mutableListOf<ProactiveNotification>()

        val result = h.scheduler.runAuthorized(
            NOW,
            utc,
            requestPlan = { responseWithLocalProposal() },
            showNotification = { notifications += it },
        )

        assertEquals(ProactiveRunStatus.REQUESTED, result.status)
        assertEquals(localProposalId, notifications.single().localProposalId)
    }

    @Test
    fun `successfully delivered notification is not repeated after scheduler recreation`() {
        val h = Harness(allConsents)
        h.record(trigger("single-delivery-after-restart", motif = "single-delivery-after-restart"))
        val notifications = mutableListOf<ProactiveNotification>()
        var planRequests = 0

        val first = h.scheduler.runAuthorized(
            NOW,
            utc,
            requestPlan = { planRequests++; responseWithLocalProposal() },
            showNotification = { notifications += it },
        )

        assertEquals(ProactiveRunStatus.REQUESTED, first.status)
        assertEquals(1, notifications.size)
        assertEquals(ProactiveNotificationStatus.DELIVERED, h.state.notificationReservations.single().status)

        var persistedState = ProactiveCoachState.fromJsonString(h.state.toJsonString())
        val restartedScheduler = ProactiveCoachScheduler(
            readState = { persistedState },
            writeState = { persistedState = it },
            currentConsent = { allConsents },
            clockUtcMillis = { NOW + ProactiveTriggerGate.MIN_COOLDOWN_MS },
        )
        var replayedNotifications = 0
        val replay = restartedScheduler.runAuthorized(
            NOW + ProactiveTriggerGate.MIN_COOLDOWN_MS,
            utc,
            requestPlan = { planRequests++; responseWithLocalProposal() },
            showNotification = { replayedNotifications++ },
        )

        assertEquals(ProactiveRunStatus.NO_ELIGIBLE_TRIGGERS, replay.status)
        assertEquals(1, planRequests)
        assertEquals(0, replayedNotifications)
        assertEquals(ProactiveNotificationStatus.DELIVERED, persistedState.notificationReservations.single().status)
        assertEquals(listOf("single-delivery-after-restart"), persistedState.consumedEventTombstones.map { it.eventId })
    }

    @Test
    fun `failed notification callback consumes its reserved slot and is never repeated`() {
        val h = Harness(allConsents)
        h.record(trigger("notification-failure", motif = "notification-failure"))
        var requests = 0
        val first = h.scheduler.runAuthorized(
            NOW,
            utc,
            requestPlan = { requests++; responseWithLocalProposal() },
            showNotification = { throw IllegalStateException("notification unavailable") },
        )
        assertEquals(ProactiveRunStatus.NOTIFICATION_FAILED, first.status)
        assertEquals(ProactiveNotificationStatus.FAILED, h.state.notificationReservations.single().status)
        val replay = h.scheduler.runAuthorized(
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
            scheduler.runAuthorized(NOW, utc, requestPlan = { requests++; ProactivePlanResponse() }, showNotification = {}).status,
        )
        assertEquals(0, requests)
        assertEquals(0, writes)
        assertNull(scheduler.mode())
    }

    @Test
    fun `withdrawal and re opt in at reservation commit cannot authorize the reserved trigger`() {
        var consent = allConsents
        var generation = 1L
        var state = ProactiveCoachState()
        lateinit var scheduler: ProactiveCoachScheduler
        scheduler = ProactiveCoachScheduler(
            readState = { state },
            writeState = { next ->
                state = next
                if (next.pendingTriggers.isEmpty() &&
                    next.requestReservations.lastOrNull()?.status == ProactiveRequestStatus.RESERVED &&
                    generation == 1L
                ) {
                    // Inject withdrawal and re-opt-in exactly at durable reservation commit.
                    consent = ProactiveConsentSnapshot.EMPTY
                    generation++
                    assertEquals("a committed trigger is no longer purgeable", 0, scheduler.purgeRevoked())
                    consent = allConsents
                }
            },
            currentConsent = { consent },
            clockUtcMillis = { NOW },
        )
        assertEquals(CoachTriggerRecordStatus.RECORDED, scheduler.record(trigger("reopt-in-at-commit"), NOW))
        var requests = 0
        var notifications = 0

        val result = scheduler.runDue(
            nowUtcMillis = NOW,
            mandateZoneId = utc,
            publicationEpoch = ProactivePublicationEpoch(1L, 1L),
            isPublicationAuthorizationCurrent = { authorization ->
                authorization.capabilityGeneration == generation && authorization.triggers.all(consent::allows)
            },
            requestPlan = { _, _ -> requests++; ProactivePlanResponse() },
            showNotification = { _, _ -> notifications++ },
        )

        assertEquals(ProactiveRunStatus.CONSENT_REVOKED, result.status)
        assertEquals("withdrawal at commit must block transport", 0, requests)
        assertEquals("withdrawal at commit must block notification", 0, notifications)
        assertTrue(state.pendingTriggers.isEmpty())
        assertTrue(state.consumedEventTombstones.any { it.eventId == "reopt-in-at-commit" })
        assertEquals(ProactiveRequestStatus.REVOKED_BEFORE_REQUEST, state.requestReservations.single().status)

        // Durable reservation/quota/tombstone state prevents a recreated scheduler from replaying
        // the old event under the new opt-in epoch, even at the identical wall-clock timestamp.
        state = ProactiveCoachState.fromJsonString(state.toJsonString())
        var replayRequests = 0
        val restarted = ProactiveCoachScheduler(
            readState = { state },
            writeState = { state = it },
            currentConsent = { allConsents },
            clockUtcMillis = { NOW },
        )
        val replay = restarted.runDue(
            nowUtcMillis = NOW,
            mandateZoneId = utc,
            publicationEpoch = ProactivePublicationEpoch(generation, 1L),
            isPublicationAuthorizationCurrent = { true },
            requestPlan = { _, _ -> replayRequests++; ProactivePlanResponse() },
            showNotification = { _, _ -> error("restart must not publish") },
        )
        assertEquals(ProactiveRunStatus.NO_ELIGIBLE_TRIGGERS, replay.status)
        assertEquals(0, replayRequests)
        assertEquals(1, state.requestReservations.size)
        assertEquals(ProactiveRequestStatus.REVOKED_BEFORE_REQUEST, state.requestReservations.single().status)
    }

    private fun assertNotificationLimit(mode: ProactiveEngagementMode, expectedLimit: Int) {
        val h = Harness(allConsents)
        assertTrue(h.scheduler.setMode(mode))
        repeat(expectedLimit + 1) { index ->
            val event = trigger("notification-$index", motif = "notification-$index")
            h.record(event)
            val result = h.scheduler.runAuthorized(
                NOW + index,
                utc,
                requestPlan = { responseWithLocalProposal() },
                showNotification = {},
            )
            if (index < expectedLimit) assertEquals(ProactiveRunStatus.REQUESTED, result.status)
            else assertEquals(ProactiveRunStatus.NOTIFICATION_QUOTA_REACHED, result.status)
        }
        assertEquals(expectedLimit, h.state.notificationReservations.size)
    }

    private fun responseWithLocalProposal() = ProactivePlanResponse(
        notification = ProactiveNotificationContent("Conseil", "Action proposée"),
        localProposalId = localProposalId,
    )

    private fun ProactiveCoachScheduler.runAuthorized(
        nowUtcMillis: Long,
        mandateZoneId: ZoneId,
        requestPlan: (ProactivePlanRequest) -> ProactivePlanResponse?,
        showNotification: (ProactiveNotification) -> Unit,
        publicationEpoch: ProactivePublicationEpoch = ProactivePublicationEpoch(1L, 1L),
        isAuthorizationCurrent: (ProactivePublicationAuthorization) -> Boolean = { true },
    ) = runDue(
        nowUtcMillis = nowUtcMillis,
        mandateZoneId = mandateZoneId,
        publicationEpoch = publicationEpoch,
        isPublicationAuthorizationCurrent = isAuthorizationCurrent,
        requestPlan = { request, _ -> requestPlan(request) },
        showNotification = { notification, _ -> showNotification(notification) },
    )

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
            return scheduler.runAuthorized(
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
