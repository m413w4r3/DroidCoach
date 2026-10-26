package dev.atlas.coach.app

import dev.atlas.coach.calendar.CalendarDescriptor
import dev.atlas.coach.calendar.CalendarPermissionState
import dev.atlas.coach.calendar.CalendarReadConsentStore
import dev.atlas.coach.calendar.write.CalendarEventDraft
import dev.atlas.coach.calendar.write.CalendarEventLinkState
import dev.atlas.coach.calendar.write.CalendarWriteOperation
import dev.atlas.coach.calendar.write.CalendarWriteOperationState
import dev.atlas.coach.calendar.write.CalendarWriteOperationType
import dev.atlas.coach.calendar.write.CalendarWritePermissions
import dev.atlas.coach.calendar.write.CalendarWriteProvider
import dev.atlas.coach.calendar.write.CalendarWriteService
import dev.atlas.coach.calendar.write.FileCalendarWriteJournal
import dev.atlas.coach.calendar.write.FileCalendarWriteLinkStore
import dev.atlas.coach.calendar.write.CalendarWriteEvent
import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.ActivityStatus
import dev.atlas.coach.plan.DayPlan
import dev.atlas.coach.plan.LocalSchedule
import dev.atlas.coach.plan.PlanActivity
import dev.atlas.coach.plan.PlanOrigin
import dev.atlas.coach.plan.PlanState
import dev.atlas.coach.plan.Priority
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarEffectControllerTest {
    @Test
    fun acceptsOnlyExplicitLocalCalendarsAndRejectsForeignCalendar() {
        val local = localCalendar()
        val foreign = local.copy(id = 2L, displayName = "Work", accountType = "com.google")
        val fixture = fixture(calendars = listOf(local, foreign))

        assertEquals(listOf(local), fixture.controller.localCalendars())
        assertNull(fixture.controller.selectCalendar(local.id))
        assertEquals(CalendarEffectIssue.CALENDAR_NOT_LOCAL, fixture.controller.selectCalendar(foreign.id))
        assertEquals(local.id, fixture.selectionStore.selectedCalendarId())
    }

    @Test
    fun groupedPreviewListsExactCreateUpdateDeleteAndDoesNotWriteUntilConfirmed() {
        val fixture = fixture()
        fixture.controller.selectCalendar(CALENDAR_ID)
        val first = plan(activities = listOf(activity("Focus"), activity("Lunch", startMinute = 12 * 60)))
        val initialPreview = fixture.controller.syncAppliedPlan(first)
        assertEquals(2, initialPreview.effects.size)
        assertEquals(0, fixture.provider.insertCount)
        assertTrue(fixture.controller.syncAppliedPlan(first, initialPreview, first).successful)
        assertEquals(2, fixture.provider.insertCount)

        val firstActivity = first.activities.first()
        val updated = firstActivity.copy(title = "Focus moved", scheduledLocal = firstActivity.scheduledLocal.copy(startMinute = 10 * 60))
        val added = activity("Walk", startMinute = 16 * 60).copy(planId = first.planId)
        val next = first.copy(
            version = first.version + 1,
            currentRevisionId = uuid(),
            updatedAt = first.updatedAt + 1,
            activities = listOf(updated, added),
        )
        val preview = fixture.controller.syncAppliedPlan(next)

        assertTrue(preview.ready)
        assertEquals(3, preview.planVersion)
        assertEquals(3, preview.effects.size)
        assertEquals(
            setOf(
                Triple(CalendarWriteOperationType.CREATE, "Walk", "16:00"),
                Triple(CalendarWriteOperationType.UPDATE, "Focus moved", "10:00"),
                Triple(CalendarWriteOperationType.DELETE, "Lunch", "12:00"),
            ),
            preview.effects.map {
                Triple(
                    it.type,
                    it.title,
                    Instant.ofEpochMilli(it.startMillis).atZone(ZoneId.of(it.timezone)).toLocalTime().toString(),
                )
            }.toSet(),
        )
        assertTrue(preview.effects.all { it.startMillis < it.endMillis && it.timezone == "Europe/Paris" })
        assertEquals(2, fixture.provider.insertCount)
        assertEquals(0, fixture.provider.updateCount)
        assertEquals(0, fixture.provider.deleteCount)
    }

    @Test
    fun changedPlanInvalidatesOpenConfirmationWithoutProviderEffects() {
        val fixture = fixture()
        fixture.controller.selectCalendar(CALENDAR_ID)
        val original = plan(activities = listOf(activity("Deep work")))
        val preview = fixture.controller.syncAppliedPlan(original)
        val changed = original.copy(
            version = original.version + 1,
            currentRevisionId = uuid(),
            updatedAt = original.updatedAt + 1,
            activities = original.activities.map { it.copy(title = "Changed work") },
        )

        val result = fixture.controller.syncAppliedPlan(original, preview, changed)

        assertEquals(CalendarSyncStatus.STALE_PLAN, result.status)
        assertEquals(0, fixture.provider.insertCount)
        assertEquals(0, fixture.provider.updateCount)
        assertEquals(0, fixture.provider.deleteCount)
    }

    @Test
    fun repeatedSyncForSamePlanHasOneProviderEffect() {
        val fixture = fixture()
        fixture.controller.selectCalendar(CALENDAR_ID)
        val plan = plan(activities = listOf(activity("Read")))
        val first = fixture.controller.syncAppliedPlan(plan)
        assertEquals(1, first.effects.size)
        assertTrue(fixture.controller.syncAppliedPlan(plan, first, plan).successful)

        val second = fixture.controller.syncAppliedPlan(plan)
        assertTrue(second.ready)
        assertTrue(second.effects.isEmpty())
        assertEquals(CalendarSyncStatus.NO_CHANGES,
            fixture.controller.syncAppliedPlan(plan, second, plan).status)
        assertEquals(1, fixture.provider.insertCount)
        assertEquals(1, fixture.provider.events.size)
    }

    @Test
    fun crashAfterProviderInsertIsRecoveredByReconciliationWithoutRetry() {
        val storage = createTempDirectory()
        val provider = FakeCalendarWriteProvider(listOf(localCalendar()))
        val readConsent = FakeReadConsent(true)
        val writeConsent = FakeWriteConsent(true)
        val selection = FakeSelection(CALENDAR_ID)
        val linkStore = FileCalendarWriteLinkStore(File(storage, "links"))
        val crashService = durableService(provider, storage, linkStore) { throw SimulatedProcessDeath() }
        val firstController = controller(provider, crashService, linkStore, storage, readConsent, writeConsent, selection)
        val plan = plan(activities = listOf(activity("Interrupted insert")))
        val preview = firstController.syncAppliedPlan(plan)

        try {
            firstController.syncAppliedPlan(plan, preview, plan)
            throw AssertionError("expected process death seam")
        } catch (_: SimulatedProcessDeath) {
            // The provider row exists while the durable operation remains PREPARED.
        }
        assertEquals(1, provider.insertCount)
        assertEquals(CalendarWriteOperationState.PREPARED, FileCalendarWriteJournal(File(storage, "operations"))
            .allOperations().single().state)

        val reloadedLinks = FileCalendarWriteLinkStore(File(storage, "links"))
        val recovered = controller(
            provider,
            durableService(provider, storage, reloadedLinks),
            reloadedLinks,
            storage,
            readConsent,
            writeConsent,
            selection,
        )

        assertTrue(recovered.recoveryResults.isNotEmpty())
        assertEquals(1, provider.insertCount)
        assertEquals(CalendarEventLinkState.CONFIRMED, reloadedLinks.findByActivity(plan.activities.single().activityId)?.state)
    }

    @Test
    fun consentRevocationBeforeConfirmationBlocksEveryEffect() {
        val fixture = fixture()
        fixture.controller.selectCalendar(CALENDAR_ID)
        val plan = plan(activities = listOf(activity("Private event")))
        val preview = fixture.controller.syncAppliedPlan(plan)
        fixture.writeConsentStore.granted = false

        val result = fixture.controller.syncAppliedPlan(plan, preview, plan)

        assertEquals(CalendarSyncStatus.BLOCKED, result.status)
        assertEquals(CalendarEffectIssue.WRITE_CONSENT_REQUIRED, result.issue)
        assertEquals(0, fixture.provider.insertCount)
        assertTrue(fixture.provider.events.isEmpty())
    }

    @Test
    fun externalEditIsReportedAsDivergedAndCalendarRemovalAsMissing() {
        val fixture = fixture()
        fixture.controller.selectCalendar(CALENDAR_ID)
        val plan = plan(activities = listOf(activity("Training")))
        val preview = fixture.controller.syncAppliedPlan(plan)
        assertTrue(fixture.controller.syncAppliedPlan(plan, preview, plan).successful)
        val activityId = plan.activities.single().activityId
        fixture.provider.mutateEvent(activityId) { it.copy(title = "Changed in calendar") }

        assertEquals(
            CalendarPlanVerificationState.DIVERGED,
            fixture.controller.verifyPlan(plan).single { it.activityId == activityId }.state,
        )

        fixture.provider.removeCalendar(CALENDAR_ID)
        assertEquals(
            CalendarPlanVerificationState.MISSING,
            fixture.controller.verifyPlan(plan).single { it.activityId == activityId }.state,
        )
    }

    private fun fixture(calendars: List<CalendarDescriptor> = listOf(localCalendar())): Fixture {
        val directory = createTempDirectory()
        val provider = FakeCalendarWriteProvider(calendars)
        val read = FakeReadConsent(true)
        val write = FakeWriteConsent(true)
        val selection = FakeSelection()
        val links = FileCalendarWriteLinkStore(File(directory, "links"))
        val service = durableService(provider, directory, links)
        val controller = controller(provider, service, links, directory, read, write, selection)
        return Fixture(controller, provider, write, selection)
    }

    private fun controller(
        provider: FakeCalendarWriteProvider,
        service: CalendarWriteService,
        links: FileCalendarWriteLinkStore,
        directory: File,
        readConsent: FakeReadConsent,
        writeConsent: FakeWriteConsent,
        selection: FakeSelection,
    ) = CalendarEffectController(
        provider = provider,
        service = service,
        linkStore = links,
        allLinks = {
            File(directory, "links").listFiles().orEmpty()
                .filter { it.extension == "json" }
                .mapNotNull { links.findByActivity(it.nameWithoutExtension) }
        },
        allOperations = { FileCalendarWriteJournal(File(directory, "operations")).allOperations() },
        readConsentStore = readConsent,
        writeConsentStore = writeConsent,
        selectionStore = selection,
        statusStore = FakeStatusStore(),
        nowMillis = { 1_700_000_000_000L },
    )

    private fun durableService(
        provider: FakeCalendarWriteProvider,
        directory: File,
        links: FileCalendarWriteLinkStore,
        afterEffect: (CalendarWriteOperation) -> Unit = {},
    ) = CalendarWriteService(
        provider = provider,
        journal = FileCalendarWriteJournal(File(directory, "operations")),
        nowMillis = { 1_700_000_000_000L },
        linkStore = links,
        afterProviderEffect = afterEffect,
    )

    private fun plan(activities: List<PlanActivity>): DayPlan {
        val now = 1_700_000_000_000L
        val planId = uuid()
        return DayPlan(
            planId = planId,
            localDate = "2030-05-07",
            timezone = "Europe/Paris",
            version = 2,
            currentRevisionId = uuid(),
            state = PlanState.APPROVED,
            activities = activities.map { it.copy(planId = planId) },
            createdAt = now,
            updatedAt = now,
            approvedAt = now,
            origin = PlanOrigin.LOCAL,
        )
    }

    private fun activity(title: String, startMinute: Int = 9 * 60): PlanActivity = PlanActivity(
        activityId = uuid(),
        planId = PLAN_ID_PLACEHOLDER,
        kind = ActivityKind.FOCUS,
        title = title,
        status = ActivityStatus.PLANNED,
        scheduledLocal = LocalSchedule("2030-05-07", startMinute, 60, "Europe/Paris"),
        priority = Priority.IMPORTANT,
        createdRevisionId = REVISION_ID_PLACEHOLDER,
        lastRevisionId = REVISION_ID_PLACEHOLDER,
    )

    private fun localCalendar() = CalendarDescriptor(
        id = CALENDAR_ID,
        displayName = "ATLAS R3A TEST",
        accountName = "synthetic-local",
        accountType = "dev.atlas.coach.calendar",
        ownerAccount = "synthetic-local",
        accessLevel = 700,
        visible = true,
        syncEvents = true,
        primary = false,
        timezone = "Europe/Paris",
        color = null,
    )

    private fun createTempDirectory(): File = kotlin.io.path.createTempDirectory("calendar-effect-controller").toFile()

    private fun uuid(): String = UUID.randomUUID().toString()

    private data class Fixture(
        val controller: CalendarEffectController,
        val provider: FakeCalendarWriteProvider,
        val writeConsentStore: FakeWriteConsent,
        val selectionStore: FakeSelection,
    )

    private class FakeReadConsent(var granted: Boolean) : CalendarReadConsentStore {
        override fun isGranted(): Boolean = granted
        override fun grant(): Boolean { granted = true; return true }
        override fun revoke(): Boolean { granted = false; return true }
    }

    private class FakeWriteConsent(var granted: Boolean) : CalendarWriteConsentStore {
        override fun isGranted(): Boolean = granted
        override fun grant(): Boolean { granted = true; return true }
        override fun revoke(): Boolean { granted = false; return true }
    }

    private class FakeSelection(var selected: Long? = null) : CalendarWriteSelectionStore {
        override fun selectedCalendarId(): Long? = selected
        override fun select(calendarId: Long): Boolean { selected = calendarId; return true }
        override fun clear(): Boolean { selected = null; return true }
    }

    private class FakeStatusStore : CalendarEffectStatusStore {
        var last: CalendarEffectLastStatus? = null
        override fun read(): CalendarEffectLastStatus? = last
        override fun write(status: CalendarEffectLastStatus): Boolean { last = status; return true }
    }

    private class FakeCalendarWriteProvider(
        calendars: List<CalendarDescriptor>,
    ) : CalendarWriteProvider {
        private val mutableCalendars = calendars.toMutableList()
        val events = linkedMapOf<Long, CalendarWriteEvent>()
        private var nextEventId = 10L
        var insertCount = 0
        var updateCount = 0
        var deleteCount = 0
        var readPermission = CalendarPermissionState.GRANTED
        var writePermission = CalendarPermissionState.GRANTED

        override fun permissions() = CalendarWritePermissions(readPermission, writePermission)
        override fun listCalendars(): List<CalendarDescriptor> = mutableCalendars.toList()

        override fun insert(calendarId: Long, draft: CalendarEventDraft, provenanceUri: String): Long {
            insertCount++
            val id = nextEventId++
            events[id] = draft.toEvent(id, calendarId, provenanceUri)
            return id
        }

        override fun update(eventId: Long, calendarId: Long, draft: CalendarEventDraft, provenanceUri: String) {
            updateCount++
            val current = events[eventId] ?: error("missing event")
            require(current.calendarId == calendarId && current.customAppUri == provenanceUri)
            events[eventId] = draft.toEvent(eventId, calendarId, provenanceUri)
        }

        override fun delete(eventId: Long, calendarId: Long, provenanceUri: String): Int {
            deleteCount++
            val current = events[eventId] ?: return 0
            if (current.calendarId != calendarId || current.customAppUri != provenanceUri) return 0
            events.remove(eventId)
            return 1
        }

        override fun getEvent(eventId: Long): CalendarWriteEvent? = events[eventId]

        override fun findByCustomAppUri(provenanceUri: String): List<CalendarWriteEvent> =
            events.values.filter { it.customAppUri == provenanceUri }

        fun mutateEvent(activityId: String, mutation: (CalendarWriteEvent) -> CalendarWriteEvent) {
            val event = events.values.single { it.customAppUri?.endsWith("/activity/$activityId") == true }
            events[event.eventId] = mutation(event)
        }

        fun removeCalendar(calendarId: Long) {
            mutableCalendars.removeAll { it.id == calendarId }
            events.values.filter { it.calendarId == calendarId }.map { it.eventId }.forEach(events::remove)
        }

        private fun CalendarEventDraft.toEvent(eventId: Long, calendarId: Long, uri: String) = CalendarWriteEvent(
            eventId = eventId,
            calendarId = calendarId,
            title = title,
            dtstartMillis = dtstartMillis,
            dtendMillis = dtendMillis,
            timezone = timezone,
            allDay = false,
            deleted = false,
            rrule = null,
            customAppPackage = "dev.atlas.coach",
            customAppUri = uri,
        )
    }

    private class SimulatedProcessDeath : Error()

    private companion object {
        const val CALENDAR_ID = 1L
        const val PLAN_ID_PLACEHOLDER = "00000000-0000-4000-8000-000000000001"
        const val REVISION_ID_PLACEHOLDER = "00000000-0000-4000-8000-000000000002"
    }
}
