package dev.atlas.coach.calendar.write

import dev.atlas.coach.calendar.CalendarDescriptor
import dev.atlas.coach.calendar.CalendarPermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CalendarWriteServiceTest {
    @Test
    fun insertCreatesOnceAndConfirmsLink() {
        val harness = Harness()

        val result = harness.service.create(CALENDAR_ID, draft(), "create-1")

        assertTrue(result.successful)
        assertEquals(CalendarEventLinkState.CONFIRMED, result.link?.state)
        assertEquals(1, harness.provider.insertCalls)
        assertEquals(CalendarWriteOperationState.APPLIED, result.operation?.state)
        assertEquals("atlas://plan/$PLAN_ID/activity/$ACTIVITY_ID", result.link?.provenanceUri)
        assertEquals(draft().contentHash(CALENDAR_ID), result.link?.contentHash)

        val updated = harness.service.update(requireNotNull(result.link), draft(title = "Revised"), "update-once")
        assertTrue(updated.successful)
        assertEquals(CalendarEventLinkState.CONFIRMED, updated.link?.state)
        assertEquals(1, harness.provider.updateCalls)
        assertEquals("Revised", harness.provider.getEvent(requireNotNull(updated.link?.eventId))?.title)
    }

    @Test
    fun sameIdempotencyKeyDoesNotInsertTwice() {
        val harness = Harness()
        val first = harness.service.create(CALENDAR_ID, draft(), "same-key")

        val replay = harness.service.create(CALENDAR_ID, draft(), "same-key")

        assertEquals(1, harness.provider.insertCalls)
        assertEquals(first.link, replay.link)
        assertTrue(replay.successful)
    }

    @Test
    fun sameKeyDifferentPayloadIsRejected() {
        val harness = Harness()
        harness.service.create(CALENDAR_ID, draft(), "conflicting-key")

        val result = harness.service.create(CALENDAR_ID, draft(title = "Different"), "conflicting-key")

        assertEquals(CalendarWriteError.IDEMPOTENCY_CONFLICT, result.error)
        assertEquals(1, harness.provider.insertCalls)
    }

    @Test
    fun preexistingUriIsReusedWithoutSecondInsert() {
        val harness = Harness()
        harness.provider.seed(draft(), CALENDAR_ID)

        val result = harness.service.create(CALENDAR_ID, draft(), "reuse-key")

        assertTrue(result.successful)
        assertEquals(CalendarEventLinkState.CONFIRMED, result.link?.state)
        assertEquals(1, harness.provider.events.size)
        assertEquals(0, harness.provider.insertCalls)
        assertEquals(CalendarWriteOperationState.RECONCILED, result.operation?.state)
    }

    @Test
    fun duplicateUriReconciliationStopsWithoutChoosing() {
        val harness = Harness()
        val created = harness.service.create(CALENDAR_ID, draft(), "duplicate-key")
        harness.provider.seed(draft(), CALENDAR_ID, eventId = 2L)

        val result = harness.service.reconcile(requireNotNull(created.link))

        assertEquals(CalendarWriteError.DUPLICATE_URI, result.error)
        assertEquals(CalendarEventLinkState.ORPHANED, result.link?.state)
        assertNull(result.link?.eventId)
        assertEquals(1, harness.provider.insertCalls)
    }

    @Test
    fun crashBeforeProviderCallLeavesPreparedAndNoEffect() {
        val provider = FakeCalendarWriteProvider()
        val journal = InMemoryCalendarWriteJournal()
        val service = CalendarWriteService(
            provider = provider,
            journal = journal,
            nowMillis = { NOW },
            beforeProviderEffect = { throw SimulatedCrash() },
        )

        try {
            service.create(CALENDAR_ID, draft(), "before-call")
            throw AssertionError("expected simulated crash")
        } catch (_: SimulatedCrash) {
            // Process loss is represented by the durable PREPARED row and no provider request.
        }

        assertEquals(0, provider.insertCalls)
        assertEquals(0, provider.uriSearchCalls)
        assertEquals(CalendarWriteOperationState.PREPARED, journal.findByIdempotencyKey("before-call")?.state)
    }

    @Test
    fun crashAfterProviderCommitReconcilesByUri() {
        val harness = Harness()
        harness.provider.afterInsertCommit = { throw SimulatedCrash() }

        val unknown = harness.service.create(CALENDAR_ID, draft(), "after-commit")

        assertEquals(CalendarWriteError.UNKNOWN_RESULT, unknown.error)
        assertEquals(CalendarWriteOperationState.UNKNOWN, unknown.operation?.state)
        assertEquals(1, harness.provider.insertCalls)
        val reconciled = harness.service.reconcile(requireNotNull(unknown.link))
        assertEquals(CalendarEventLinkState.CONFIRMED, reconciled.link?.state)
        assertEquals(CalendarWriteOperationState.RECONCILED, reconciled.operation?.state)
        assertEquals(1, harness.provider.insertCalls)
    }

    @Test
    fun unknownResultNeverRetriesBlindly() {
        val harness = Harness()
        harness.provider.throwAfterInsertWithoutCommit = true

        val first = harness.service.create(CALENDAR_ID, draft(), "unknown-replay")
        val replay = harness.service.create(CALENDAR_ID, draft(), "unknown-replay")

        assertEquals(CalendarWriteError.UNKNOWN_RESULT, first.error)
        assertFalse(first.toString().contains("PRIVATE_PROVIDER_SENTINEL"))
        assertEquals(CalendarEventLinkState.MISSING, replay.link?.state)
        assertEquals(1, harness.provider.insertCalls)
        assertEquals(0, harness.provider.events.size)
    }

    @Test
    fun readBackMismatchBecomesDiverged() {
        val harness = Harness()
        harness.provider.readBackTransform = { it.copy(title = "Provider changed title") }

        val result = harness.service.create(CALENDAR_ID, draft(), "mismatch")

        assertEquals(CalendarWriteError.READ_BACK_MISMATCH, result.error)
        assertEquals(CalendarEventLinkState.DIVERGED, result.link?.state)
        assertEquals(CalendarWriteOperationState.APPLIED, result.operation?.state)
    }

    @Test
    fun foreignEventIsNeverUpdatedOrDeleted() {
        val harness = Harness()
        val created = harness.service.create(CALENDAR_ID, draft(), "foreign-source")
        harness.provider.mutate(requireNotNull(created.link?.eventId)) {
            it.copy(customAppPackage = "com.example.other", customAppUri = "content://foreign/1")
        }

        val update = harness.service.update(requireNotNull(created.link), draft(title = "Changed"), "foreign-update")
        val delete = harness.service.delete(requireNotNull(created.link), "foreign-delete")

        assertEquals(CalendarWriteError.FOREIGN_EVENT, update.error)
        assertEquals(CalendarWriteError.FOREIGN_EVENT, delete.error)
        assertEquals(0, harness.provider.updateCalls)
        assertEquals(0, harness.provider.deleteCalls)
    }

    @Test
    fun ambiguousMarkerIsReadOnly() {
        val harness = Harness()
        val created = harness.service.create(CALENDAR_ID, draft(), "ambiguous-source")
        harness.provider.mutate(requireNotNull(created.link?.eventId)) {
            it.copy(customAppPackage = ATLAS_WRITE_PACKAGE, customAppUri = null)
        }

        val update = harness.service.update(requireNotNull(created.link), draft(title = "Changed"), "ambiguous-update")
        val delete = harness.service.delete(requireNotNull(created.link), "ambiguous-delete")

        assertEquals(CalendarWriteError.PROVENANCE_AMBIGUOUS, update.error)
        assertEquals(CalendarWriteError.PROVENANCE_AMBIGUOUS, delete.error)
        assertEquals(0, harness.provider.updateCalls)
        assertEquals(0, harness.provider.deleteCalls)
    }

    @Test
    fun missingEventIsNotRecreated() {
        val harness = Harness()
        val created = harness.service.create(CALENDAR_ID, draft(), "missing-source")
        harness.provider.remove(requireNotNull(created.link?.eventId))

        val reconciled = harness.service.reconcile(requireNotNull(created.link))

        assertEquals(CalendarEventLinkState.MISSING, reconciled.link?.state)
        assertEquals(1, harness.provider.insertCalls)
        assertEquals(0, harness.provider.events.size)
    }

    @Test
    fun recurringEventIsRejected() {
        val harness = Harness()
        harness.provider.seed(draft(), CALENDAR_ID, rrule = "FREQ=DAILY")

        val result = harness.service.create(CALENDAR_ID, draft(), "recurring")

        assertEquals(CalendarWriteError.RECURRING_NOT_SUPPORTED, result.error)
        assertEquals(0, harness.provider.insertCalls)
        assertEquals(1, harness.provider.events.size)
    }

    @Test
    fun permissionRevokedBeforeEffectStops() {
        val provider = FakeCalendarWriteProvider().apply {
            permissionsForCall = { call ->
                if (call >= 2) deniedPermissions() else grantedPermissions()
            }
        }
        val journal = InMemoryCalendarWriteJournal()
        val service = CalendarWriteService(provider, journal, nowMillis = { NOW })

        val result = service.create(CALENDAR_ID, draft(), "revoked")

        assertEquals(CalendarWriteError.PERMISSION_DENIED, result.error)
        assertEquals(0, provider.insertCalls)
        assertEquals(CalendarWriteOperationState.NOT_APPLIED, result.operation?.state)
    }

    @Test
    fun nonLocalCalendarIsRejected() {
        val remote = FakeCalendarWriteProvider().apply {
            calendars = listOf(calendar(accountType = "com.google"))
        }
        val remoteResult = CalendarWriteService(remote, InMemoryCalendarWriteJournal(), nowMillis = { NOW })
            .create(CALENDAR_ID, draft(), "remote-calendar")
        assertEquals(CalendarWriteError.CALENDAR_NOT_LOCAL, remoteResult.error)
        assertEquals(0, remote.insertCalls)

        val invisible = FakeCalendarWriteProvider().apply {
            calendars = listOf(calendar(visible = false))
        }
        val invisibleResult = CalendarWriteService(invisible, InMemoryCalendarWriteJournal(), nowMillis = { NOW })
            .create(CALENDAR_ID, draft(), "invisible-calendar")
        assertEquals(CalendarWriteError.CALENDAR_NOT_LOCAL, invisibleResult.error)
        assertEquals(0, invisible.insertCalls)

        val insufficientAccess = FakeCalendarWriteProvider().apply {
            calendars = listOf(calendar(accessLevel = 499))
        }
        val accessResult = CalendarWriteService(insufficientAccess, InMemoryCalendarWriteJournal(), nowMillis = { NOW })
            .create(CALENDAR_ID, draft(), "access-calendar")
        assertEquals(CalendarWriteError.CALENDAR_NOT_LOCAL, accessResult.error)
        assertEquals(0, insufficientAccess.insertCalls)

        val noCalendar = FakeCalendarWriteProvider().apply { calendars = emptyList() }
        val noCalendarResult = CalendarWriteService(noCalendar, InMemoryCalendarWriteJournal(), nowMillis = { NOW })
            .create(null, draft(), "no-calendar")
        assertEquals(CalendarWriteError.CALENDAR_NOT_LOCAL, noCalendarResult.error)
        assertEquals(0, noCalendar.insertCalls)

        val noExplicitSelection = Harness()
        assertEquals(
            CalendarWriteError.CALENDAR_NOT_SELECTED,
            noExplicitSelection.service.create(null, draft(), "no-explicit-selection").error,
        )
        assertEquals(0, noExplicitSelection.provider.insertCalls)
    }

    @Test
    fun wrongCalendarIsRejected() {
        val harness = Harness()
        val created = harness.service.create(CALENDAR_ID, draft(), "wrong-calendar-source")
        harness.provider.mutate(requireNotNull(created.link?.eventId)) { it.copy(calendarId = OTHER_CALENDAR_ID) }

        val update = harness.service.update(requireNotNull(created.link), draft(title = "Changed"), "wrong-calendar-update")
        val delete = harness.service.delete(requireNotNull(created.link), "wrong-calendar-delete")

        assertEquals(CalendarWriteError.WRONG_CALENDAR, update.error)
        assertEquals(CalendarWriteError.WRONG_CALENDAR, delete.error)
        assertEquals(0, harness.provider.updateCalls)
        assertEquals(0, harness.provider.deleteCalls)
    }

    @Test
    fun dstSpringForwardAndFallBackProduceExpectedUtc() {
        val service = CalendarWriteService(FakeCalendarWriteProvider(), InMemoryCalendarWriteJournal())
        val spring = service.draftFromLocalTime(
            ACTIVITY_ID,
            PLAN_ID,
            "Spring",
            "2026-03-29",
            startMinute = 150,
            durationMinutes = 60,
            timezone = "Europe/Paris",
        )
        val fall = service.draftFromLocalTime(
            ACTIVITY_ID,
            PLAN_ID,
            "Fall",
            "2026-10-25",
            startMinute = 150,
            durationMinutes = 60,
            timezone = "Europe/Paris",
        )

        assertEquals(Instant.parse("2026-03-29T01:00:00Z").toEpochMilli(), spring.dtstartMillis)
        assertEquals(Instant.parse("2026-03-29T02:00:00Z").toEpochMilli(), spring.dtendMillis)
        assertEquals(Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(), fall.dtstartMillis)
        assertEquals(Instant.parse("2026-10-25T01:30:00Z").toEpochMilli(), fall.dtendMillis)
    }

    @Test
    fun deleteRequiresConfirmedMarkedLinkAndReadsBackAbsence() {
        val harness = Harness()
        val created = harness.service.create(CALENDAR_ID, draft(), "delete-source")

        val result = harness.service.delete(requireNotNull(created.link), "delete-key")

        assertNull(harness.provider.getEvent(requireNotNull(created.link?.eventId)))
        assertEquals(1, harness.provider.deleteCalls)
        assertEquals(CalendarEventLinkState.MISSING, result.link?.state)
        assertEquals(CalendarWriteOperationState.APPLIED, result.operation?.state)
        assertNull(result.error)
        assertTrue(result.successful)

        val notConfirmed = created.link!!.copy(state = CalendarEventLinkState.PENDING)
        val rejected = harness.service.delete(notConfirmed, "delete-pending")
        assertEquals(CalendarWriteError.INVALID_LINK, rejected.error)
        assertEquals(1, harness.provider.deleteCalls)
    }
}

private class Harness {
    val provider = FakeCalendarWriteProvider()
    val journal = InMemoryCalendarWriteJournal()
    val service = CalendarWriteService(provider, journal, nowMillis = { NOW })
}

private class FakeCalendarWriteProvider : CalendarWriteProvider {
    var calendars: List<CalendarDescriptor> = listOf(calendar())
    var permissionsForCall: (Int) -> CalendarWritePermissions = { grantedPermissions() }
    var permissionCalls = 0
    var insertCalls = 0
    var updateCalls = 0
    var deleteCalls = 0
    var uriSearchCalls = 0
    var afterInsertCommit: (() -> Unit)? = null
    var throwAfterInsertWithoutCommit = false
    var readBackTransform: (CalendarWriteEvent) -> CalendarWriteEvent = { it }
    val events = linkedMapOf<Long, CalendarWriteEvent>()
    private var nextId = 1L

    override fun permissions(): CalendarWritePermissions = permissionsForCall(++permissionCalls)

    override fun listCalendars(): List<CalendarDescriptor> = calendars

    override fun insert(calendarId: Long, draft: CalendarEventDraft, provenanceUri: String): Long {
        insertCalls++
        if (throwAfterInsertWithoutCommit) throw IllegalStateException("PRIVATE_PROVIDER_SENTINEL")
        val eventId = nextId++
        events[eventId] = event(calendarId, eventId, draft, provenanceUri)
        afterInsertCommit?.invoke()
        return eventId
    }

    override fun update(eventId: Long, calendarId: Long, draft: CalendarEventDraft, provenanceUri: String) {
        updateCalls++
        val current = requireNotNull(events[eventId])
        check(
            current.calendarId == calendarId && current.customAppPackage == ATLAS_WRITE_PACKAGE &&
                current.customAppUri == provenanceUri && current.rrule == null && !current.deleted,
        )
        events[eventId] = event(calendarId, eventId, draft, provenanceUri).copy(rrule = current.rrule)
    }

    override fun delete(eventId: Long, calendarId: Long, provenanceUri: String) {
        deleteCalls++
        val current = events[eventId] ?: return
        if (current.calendarId != calendarId || current.customAppPackage != ATLAS_WRITE_PACKAGE ||
            current.customAppUri != provenanceUri || current.rrule != null || current.deleted
        ) return
        events.remove(eventId)
    }

    override fun getEvent(eventId: Long): CalendarWriteEvent? = events[eventId]?.let(readBackTransform)

    override fun findByCustomAppUri(provenanceUri: String): List<CalendarWriteEvent> {
        uriSearchCalls++
        return events.values.filter { it.customAppUri == provenanceUri }
    }

    fun seed(
        draft: CalendarEventDraft,
        calendarId: Long,
        eventId: Long = nextId++,
        rrule: String? = null,
    ) {
        nextId = maxOf(nextId, eventId + 1)
        events[eventId] = event(calendarId, eventId, draft, draft.provenanceUri).copy(rrule = rrule)
    }

    fun mutate(eventId: Long, transform: (CalendarWriteEvent) -> CalendarWriteEvent) {
        events[eventId] = transform(requireNotNull(events[eventId]))
    }

    fun remove(eventId: Long) {
        events.remove(eventId)
    }
}

private fun event(
    calendarId: Long,
    eventId: Long,
    draft: CalendarEventDraft,
    provenanceUri: String,
): CalendarWriteEvent = CalendarWriteEvent(
    eventId = eventId,
    calendarId = calendarId,
    title = draft.title,
    dtstartMillis = draft.dtstartMillis,
    dtendMillis = draft.dtendMillis,
    timezone = draft.timezone,
    allDay = false,
    deleted = false,
    rrule = null,
    customAppPackage = ATLAS_WRITE_PACKAGE,
    customAppUri = provenanceUri,
)

private fun draft(title: String = "Focus"): CalendarEventDraft = CalendarEventDraft(
    activityId = ACTIVITY_ID,
    planId = PLAN_ID,
    title = title,
    dtstartMillis = 1_798_000_000_000,
    dtendMillis = 1_798_003_600_000,
    timezone = "Europe/Paris",
)

private fun calendar(
    id: Long = CALENDAR_ID,
    accountType: String? = "LOCAL",
    visible: Boolean = true,
    accessLevel: Int = 500,
): CalendarDescriptor = CalendarDescriptor(
    id = id,
    displayName = "Local",
    accountName = null,
    accountType = accountType,
    ownerAccount = null,
    accessLevel = accessLevel,
    visible = visible,
    syncEvents = false,
    primary = false,
    timezone = "Europe/Paris",
    color = null,
)

private fun grantedPermissions() = CalendarWritePermissions(
    read = CalendarPermissionState.GRANTED,
    write = CalendarPermissionState.GRANTED,
)

private fun deniedPermissions() = CalendarWritePermissions(
    read = CalendarPermissionState.DENIED,
    write = CalendarPermissionState.DENIED,
)

private class SimulatedCrash : RuntimeException()

private const val PLAN_ID = "00000000-0000-0000-0000-000000000001"
private const val ACTIVITY_ID = "00000000-0000-0000-0000-000000000002"
private const val CALENDAR_ID = 10L
private const val OTHER_CALENDAR_ID = 11L
private const val NOW = 1_800_000_000_000L
