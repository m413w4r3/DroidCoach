package dev.atlas.coach.calendar.local

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.os.Process
import android.provider.CalendarContract
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.calendar.CalendarDescriptor
import dev.atlas.coach.calendar.CalendarPermissionState
import dev.atlas.coach.calendar.write.CalendarEventDraft
import dev.atlas.coach.calendar.write.CalendarEventLink
import dev.atlas.coach.calendar.write.CalendarEventLinkState
import dev.atlas.coach.calendar.write.CalendarWriteError
import dev.atlas.coach.calendar.write.CalendarWriteOperationState
import dev.atlas.coach.calendar.write.CalendarWriteService
import dev.atlas.coach.calendar.write.FileCalendarWriteJournal
import dev.atlas.coach.calendar.write.FileCalendarWriteLinkStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real CalendarContract scenarios. Every event and temporary calendar is synthetic and targeted. */
@RunWith(AndroidJUnit4::class)
class AtlasR3aPlusCalendarInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val provider by lazy { dev.atlas.coach.calendar.write.AndroidCalendarContractWriteProvider(context) }

    /** Phase 1: parent expects this instrumentation process to terminate by SIGKILL. */
    @Test
    fun c1Phase1PersistUnknownThenKillRealProcess() {
        requireReadWritePermissions()
        val calendar = readyAtlasCalendar()
        val storage = File(context.filesDir, C1_STORAGE)
        val draft = draftFor(C1_PLAN, C1_ACTIVITY, "ATLAS TEST C1 process death", "UTC")

        cleanOwnedUri(draft.provenanceUri, draft.title)
        storage.deleteRecursively()
        val journal = FileCalendarWriteJournal(File(storage, "operations"))
        val links = FileCalendarWriteLinkStore(File(storage, "links"))
        val service = CalendarWriteService(
            provider = provider,
            journal = journal,
            linkStore = links,
            afterProviderEffect = { operation ->
                assertEquals(C1_ACTIVITY, operation.activityId)
                val inserted = provider.findByCustomAppUri(draft.provenanceUri).single()
                assertEquals(draft.title, inserted.title)
                journal.updateState(
                    operation.operationId,
                    CalendarWriteOperationState.UNKNOWN,
                    eventId = inserted.eventId,
                    errorCode = CalendarWriteError.UNKNOWN_RESULT,
                )
                val pendingLink = requireNotNull(links.findByActivity(C1_ACTIVITY))
                links.upsert(
                    pendingLink.copy(
                        eventId = inserted.eventId,
                        state = CalendarEventLinkState.UNKNOWN,
                        lastError = CalendarWriteError.UNKNOWN_RESULT.name,
                    ),
                )

                // Verify durable state before ending the real process at the provider/journal boundary.
                assertEquals(
                    CalendarWriteOperationState.UNKNOWN,
                    FileCalendarWriteJournal(File(storage, "operations"))
                        .findByOperation(operation.operationId)?.state,
                )
                assertEquals(
                    CalendarEventLinkState.UNKNOWN,
                    FileCalendarWriteLinkStore(File(storage, "links"))
                        .findByActivity(C1_ACTIVITY)?.state,
                )
                Process.killProcess(Process.myPid())
                error("Process.killProcess returned without terminating the test process")
            },
        )

        service.create(calendar.id, draft, C1_IDEMPOTENCY)
        error("C1 real process death hook was not reached")
    }

    /** Phase 2: run in a new am instrument process after phase 1 exited by SIGKILL. */
    @Test
    fun c1Phase2RecoverUnknownWithoutDuplicateAndCleanFixture() {
        requireReadWritePermissions()
        val draft = draftFor(C1_PLAN, C1_ACTIVITY, "ATLAS TEST C1 process death", "UTC")
        val matchesBefore = provider.findByCustomAppUri(draft.provenanceUri)
        assertEquals(1, matchesBefore.size)
        assertEquals(draft.title, matchesBefore.single().title)
        assertEquals(context.packageName, matchesBefore.single().customAppPackage)

        val storage = File(context.filesDir, C1_STORAGE)
        val journal = FileCalendarWriteJournal(File(storage, "operations"))
        val links = FileCalendarWriteLinkStore(File(storage, "links"))
        val before = journal.findByActivity(C1_ACTIVITY).single()
        assertEquals(CalendarWriteOperationState.UNKNOWN, before.state)
        assertEquals(matchesBefore.single().eventId, before.eventId)
        assertEquals(CalendarEventLinkState.UNKNOWN, links.findByActivity(C1_ACTIVITY)?.state)

        val service = CalendarWriteService(
            provider = provider,
            journal = journal,
            linkStore = links,
        )
        val recovered = service.recoverPending().single()
        assertNull(recovered.error)
        assertEquals(CalendarWriteOperationState.RECONCILED, recovered.operation?.state)
        assertEquals(CalendarEventLinkState.CONFIRMED, recovered.link?.state)
        assertEquals(matchesBefore.single().eventId, recovered.link?.eventId)
        assertEquals(1, provider.findByCustomAppUri(draft.provenanceUri).size)

        val replay = service.create(readyAtlasCalendar().id, draft, C1_IDEMPOTENCY)
        assertTrue(replay.successful)
        assertEquals(matchesBefore.single().eventId, replay.link?.eventId)
        assertEquals(1, provider.findByCustomAppUri(draft.provenanceUri).size)
        assertEquals(CalendarWriteOperationState.RECONCILED, journal.findByActivity(C1_ACTIVITY).single().state)

        cleanOwnedUri(draft.provenanceUri, draft.title)
        storage.deleteRecursively()
    }

    @Test
    fun c2ForeignSyntheticEventIsRejectedAndFullProviderRowIsUnchanged() {
        requireReadWritePermissions()
        val calendar = readyAtlasCalendar()
        val planId = UUID.randomUUID().toString()
        val activityId = UUID.randomUUID().toString()
        val original = draftFor(planId, activityId, "ATLAS TEST C2 foreign fixture", "UTC")
        val foreignUri = "atlas-test://foreign/${UUID.randomUUID()}"
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendar.id)
            put(CalendarContract.Events.TITLE, original.title)
            put(CalendarContract.Events.DTSTART, original.dtstartMillis)
            put(CalendarContract.Events.DTEND, original.dtendMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, original.timezone)
            put(CalendarContract.Events.ALL_DAY, 0)
            // The package is the test caller; the mismatched URI makes this row foreign to ATLAS.
            put(CalendarContract.Events.CUSTOM_APP_PACKAGE, context.packageName)
            put(CalendarContract.Events.CUSTOM_APP_URI, foreignUri)
        }
        val inserted = requireNotNull(context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values))
        val eventId = ContentUris.parseId(inserted)
        try {
            val before = fullEventRow(eventId)
            assertNotNull(before)
            val link = CalendarEventLink(
                linkId = UUID.nameUUIDFromBytes(original.provenanceUri.toByteArray()).toString(),
                activityId = activityId,
                planId = planId,
                calendarId = calendar.id,
                eventId = eventId,
                provenanceUri = original.provenanceUri,
                state = CalendarEventLinkState.CONFIRMED,
                contentHash = original.contentHash(calendar.id),
            )
            val storage = temporaryStorage("c2-foreign")
            try {
                val result = calendarService(storage).update(
                    link,
                    original.copy(title = "ATLAS TEST C2 must not change"),
                    "r3ap-c2-${UUID.randomUUID()}",
                )
                assertEquals(CalendarWriteError.FOREIGN_EVENT, result.error)
                assertEquals(before, fullEventRow(eventId))
            } finally {
                storage.deleteRecursively()
            }
        } finally {
            deleteForeignFixtureIfUnchanged(eventId, calendar.id, original.provenanceUri, foreignUri)
        }
    }

    @Test
    fun c3DeletedEmptyDisposableCalendarFailsClosed() {
        requireReadWritePermissions()
        val base = readyAtlasCalendar()
        val disposableId = createDisposableCalendar(base)
        val planId = UUID.randomUUID().toString()
        val activityId = UUID.randomUUID().toString()
        val draft = draftFor(planId, activityId, "ATLAS TEST C3 unavailable calendar", "UTC")
        val storage = temporaryStorage("c3-calendar")
        try {
            assertTrue("disposable calendar must start empty", calendarEventIds(disposableId).isEmpty())
            assertTrue("disposable calendar deletion failed", deleteDisposableCalendar(disposableId, base.accountName!!))

            val result = calendarService(storage).create(disposableId, draft, "r3ap-c3-${UUID.randomUUID()}")
            assertEquals(CalendarWriteError.CALENDAR_NOT_LOCAL, result.error)
            assertTrue(provider.findByCustomAppUri(draft.provenanceUri).isEmpty())
        } finally {
            // Cleanup is restricted to this run's calendar and only while it remains empty.
            if (provider.listCalendars().any { it.id == disposableId }) {
                assertTrue(calendarEventIds(disposableId).isEmpty())
                deleteDisposableCalendar(disposableId, base.accountName!!)
            }
            storage.deleteRecursively()
        }
    }

    @Test
    fun c4ReadPermissionDeniedDoesNotEnumerateCalendars() {
        val storage = temporaryStorage("c4-read-denied")
        try {
            assertEquals(CalendarPermissionState.DENIED, provider.permissions().read)
            val catalog = calendarService(storage).inspectCatalog()
            assertEquals(CalendarPermissionState.DENIED, catalog.permissions.read)
            assertTrue(catalog.calendars.isEmpty())
            assertFalse(catalog.providerAvailable)
        } finally {
            storage.deleteRecursively()
        }
    }

    @Test
    fun c4WritePermissionDeniedBlocksCreate() {
        assertEquals(CalendarPermissionState.GRANTED, provider.permissions().read)
        assertEquals(CalendarPermissionState.DENIED, provider.permissions().write)
        val calendar = existingAtlasCalendar()
        val draft = draftFor(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "ATLAS TEST C4 write denied", "UTC")
        val storage = temporaryStorage("c4-write-denied")
        try {
            val result = calendarService(storage).create(calendar.id, draft, "r3ap-c4-denied-${UUID.randomUUID()}")
            assertEquals(CalendarWriteError.PERMISSION_DENIED, result.error)
            assertTrue(provider.findByCustomAppUri(draft.provenanceUri).isEmpty())
        } finally {
            storage.deleteRecursively()
        }
    }

    @Test
    fun c4RestoredPermissionsAllowSyntheticCreateAndDelete() {
        requireReadWritePermissions()
        val calendar = readyAtlasCalendar()
        val draft = draftFor(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "ATLAS TEST C4 restored", "UTC")
        val storage = temporaryStorage("c4-restored")
        try {
            cleanOwnedUri(draft.provenanceUri, draft.title)
            val service = calendarService(storage)
            val created = service.create(calendar.id, draft, "r3ap-c4-restored-${UUID.randomUUID()}")
            assertTrue(created.successful)
            assertEquals(1, provider.findByCustomAppUri(draft.provenanceUri).size)
            val deleted = service.delete(requireNotNull(created.link), "r3ap-c4-restored-delete-${UUID.randomUUID()}")
            assertNull(deleted.error)
            assertTrue(provider.findByCustomAppUri(draft.provenanceUri).isEmpty())
        } finally {
            cleanOwnedUri(draft.provenanceUri, draft.title)
            storage.deleteRecursively()
        }
    }

    /** Parent revokes WRITE_CALENDAR after the log marker while this callback is blocked. */
    @Test
    fun c4PermissionRevokedAtFinalGateFailsClosed() {
        requireReadWritePermissions()
        val calendar = readyAtlasCalendar()
        val draft = draftFor(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "ATLAS TEST C4 revoke race", "UTC")
        val storage = temporaryStorage("c4-revoke-race")
        val service = calendarService(storage) { operation ->
            Log.i(PERMISSION_TAG, "FINAL_PERMISSION_RECHECK_BLOCKED operation=${operation.operationId}")
            // Parent observes the log marker and performs the runtime revocation from its ADB session.
            CountDownLatch(1).await(30, TimeUnit.SECONDS)
            assertEquals(
                "parent must revoke WRITE_CALENDAR while this hook is blocked",
                CalendarPermissionState.DENIED,
                provider.permissions().write,
            )
        }
        try {
            val result = service.create(calendar.id, draft, "r3ap-c4-revoke-${UUID.randomUUID()}")
            assertEquals(CalendarWriteError.PERMISSION_DENIED, result.error)
            assertTrue(provider.findByCustomAppUri(draft.provenanceUri).isEmpty())
        } finally {
            cleanOwnedUri(draft.provenanceUri, draft.title)
            storage.deleteRecursively()
        }
    }

    @Test
    fun c5ExplicitTimezoneChangeAndDstFixturesRoundTrip() {
        requireReadWritePermissions()
        val calendar = readyAtlasCalendar()
        val storage = temporaryStorage("c5-timezone")
        val plan = UUID.randomUUID().toString()
        val activity = UUID.randomUUID().toString()
        val cleanupUris = mutableListOf<Pair<String, String>>()
        try {
            val service = calendarService(storage, nowMillis = { FIXED_CLOCK })
            val eastern = service.draftFromLocalTime(
                activity, plan, "ATLAS TEST C5 timezone eastern", "2027-01-15", 9 * 60, 45, "America/New_York",
            )
            val created = service.create(calendar.id, eastern, "r3ap-c5-timezone-create")
            assertTrue(created.successful)
            assertEquals(FIXED_CLOCK, created.link?.lastVerifiedAt)

            val auckland = service.draftFromLocalTime(
                activity, plan, "ATLAS TEST C5 timezone auckland", "2027-01-15", 9 * 60, 45, "Pacific/Auckland",
            )
            val updated = service.update(requireNotNull(created.link), auckland, "r3ap-c5-timezone-update")
            assertTrue(updated.successful)
            val moved = requireNotNull(provider.findByCustomAppUri(auckland.provenanceUri).singleOrNull())
            assertEquals("Pacific/Auckland", moved.timezone)
            assertEquals(auckland.dtstartMillis, moved.dtstartMillis)
            assertEquals(1, provider.findByCustomAppUri(auckland.provenanceUri).size)

            val springActivity = UUID.randomUUID().toString()
            val spring = service.draftFromLocalTime(
                springActivity, plan, "ATLAS TEST C5 DST spring", "2027-03-14", 150, 45, "America/New_York",
            )
            cleanupUris += spring.provenanceUri to spring.title
            assertEquals(Instant.parse("2027-03-14T07:00:00Z").toEpochMilli(), spring.dtstartMillis)
            val springResult = service.create(calendar.id, spring, "r3ap-c5-dst-spring")
            assertTrue(springResult.successful)
            assertEquals("America/New_York", provider.getEvent(requireNotNull(springResult.link?.eventId))?.timezone)

            val fallActivity = UUID.randomUUID().toString()
            val fall = service.draftFromLocalTime(
                fallActivity, plan, "ATLAS TEST C5 DST fall", "2027-11-07", 90, 45, "America/New_York",
            )
            cleanupUris += fall.provenanceUri to fall.title
            assertEquals(Instant.parse("2027-11-07T05:30:00Z").toEpochMilli(), fall.dtstartMillis)
            val fallResult = service.create(calendar.id, fall, "r3ap-c5-dst-fall")
            assertTrue(fallResult.successful)
            assertEquals("America/New_York", provider.getEvent(requireNotNull(fallResult.link?.eventId))?.timezone)
        } finally {
            cleanupUris.forEach { (uri, title) -> cleanOwnedUri(uri, title) }
            // The timezone fixture has one provenance URI because the update preserves activity/plan.
            cleanOwnedUri("atlas://plan/$plan/activity/$activity", "ATLAS TEST C5 timezone")
            storage.deleteRecursively()
        }
    }

    @Test
    fun c5ExternalEditAndExternalDeleteAreReconciled() {
        requireReadWritePermissions()
        val calendar = readyAtlasCalendar()
        val storage = temporaryStorage("c5-external-change")
        val plan = UUID.randomUUID().toString()
        val editActivity = UUID.randomUUID().toString()
        val deleteActivity = UUID.randomUUID().toString()
        val editDraft = draftFor(plan, editActivity, "ATLAS TEST C5 external edit", "UTC")
        val deleteDraft = draftFor(plan, deleteActivity, "ATLAS TEST C5 external delete", "UTC")
        try {
            val service = calendarService(storage)
            val edited = service.create(calendar.id, editDraft, "r3ap-c5-external-edit")
            assertTrue(edited.successful)
            val editId = requireNotNull(edited.link?.eventId)
            val titleUpdate = ContentValues().apply { put(CalendarContract.Events.TITLE, "ATLAS TEST C5 edited outside") }
            assertEquals(1, context.contentResolver.update(
                CalendarContract.Events.CONTENT_URI,
                titleUpdate,
                "${CalendarContract.Events._ID} = ? AND ${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ? AND ${CalendarContract.Events.CUSTOM_APP_URI} = ?",
                arrayOf(editId.toString(), calendar.id.toString(), context.packageName, editDraft.provenanceUri),
            ))
            val diverged = service.verify(requireNotNull(edited.link))
            assertEquals(CalendarWriteError.READ_BACK_MISMATCH, diverged.error)
            assertEquals(CalendarEventLinkState.DIVERGED, diverged.link?.state)

            // Restore only this synthetic row before the separate external-delete case.
            val restore = ContentValues().apply {
                put(CalendarContract.Events.TITLE, editDraft.title)
                put(CalendarContract.Events.DTSTART, editDraft.dtstartMillis)
                put(CalendarContract.Events.DTEND, editDraft.dtendMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, editDraft.timezone)
            }
            assertEquals(1, context.contentResolver.update(
                CalendarContract.Events.CONTENT_URI,
                restore,
                "${CalendarContract.Events._ID} = ? AND ${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ? AND ${CalendarContract.Events.CUSTOM_APP_URI} = ?",
                arrayOf(editId.toString(), calendar.id.toString(), context.packageName, editDraft.provenanceUri),
            ))
            val restored = service.verify(requireNotNull(edited.link))
            assertNull(restored.error)
            assertEquals(CalendarEventLinkState.CONFIRMED, restored.link?.state)

            val deleted = service.create(calendar.id, deleteDraft, "r3ap-c5-external-delete")
            assertTrue(deleted.successful)
            val deleteId = requireNotNull(deleted.link?.eventId)
            assertEquals(1, context.contentResolver.delete(
                CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events._ID} = ? AND ${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ? AND ${CalendarContract.Events.CUSTOM_APP_URI} = ?",
                arrayOf(deleteId.toString(), calendar.id.toString(), context.packageName, deleteDraft.provenanceUri),
            ))
            val missing = service.verify(requireNotNull(deleted.link))
            assertEquals(CalendarWriteError.MISSING_EVENT, missing.error)
            assertEquals(CalendarEventLinkState.MISSING, missing.link?.state)
            assertTrue(provider.findByCustomAppUri(deleteDraft.provenanceUri).isEmpty())
        } finally {
            cleanOwnedUri(editDraft.provenanceUri, editDraft.title)
            cleanOwnedUri(deleteDraft.provenanceUri, deleteDraft.title)
            storage.deleteRecursively()
        }
    }

    @Test
    fun c5NewPlanReplayAndParallelSameActivityDoNotDuplicate() {
        requireReadWritePermissions()
        val calendar = readyAtlasCalendar()
        val storage = temporaryStorage("c5-idempotence-parallel")
        val newPlanDraft = draftFor(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "ATLAS TEST C5 new plan", "UTC")
        val revisionPlan = UUID.randomUUID().toString()
        val revisionActivity = UUID.randomUUID().toString()
        val firstRevision = draftFor(revisionPlan, revisionActivity, "ATLAS TEST C5 prior plan", "UTC")
        val nextRevision = CalendarWriteService(
            provider = provider,
            journal = dev.atlas.coach.calendar.write.InMemoryCalendarWriteJournal(),
        ).draftFromLocalTime(
            revisionActivity,
            revisionPlan,
            "ATLAS TEST C5 new plan revision",
            "2027-01-15",
            14 * 60,
            50,
            "UTC",
        )
        val parallelPlan = UUID.randomUUID().toString()
        val parallelActivity = UUID.randomUUID().toString()
        val parallelDraft = draftFor(parallelPlan, parallelActivity, "ATLAS TEST C5 parallel", "UTC")
        try {
            val service = calendarService(storage)
            val firstPlanWrite = service.create(calendar.id, newPlanDraft, "r3ap-c5-new-plan")
            val replayedPlanWrite = service.create(calendar.id, newPlanDraft, "r3ap-c5-new-plan")
            assertTrue(firstPlanWrite.successful)
            assertTrue(replayedPlanWrite.successful)
            assertEquals(firstPlanWrite.link?.eventId, replayedPlanWrite.link?.eventId)
            assertEquals(1, provider.findByCustomAppUri(newPlanDraft.provenanceUri).size)

            val priorRevisionWrite = service.create(calendar.id, firstRevision, "r3ap-c5-revision-v1")
            val nextRevisionWrite = service.update(
                requireNotNull(priorRevisionWrite.link),
                nextRevision,
                "r3ap-c5-revision-v2",
            )
            assertTrue(priorRevisionWrite.successful)
            assertTrue(nextRevisionWrite.successful)
            assertEquals(priorRevisionWrite.link?.eventId, nextRevisionWrite.link?.eventId)
            assertEquals(1, provider.findByCustomAppUri(nextRevision.provenanceUri).size)
            assertEquals("ATLAS TEST C5 new plan revision", provider.getEvent(requireNotNull(nextRevisionWrite.link?.eventId))?.title)

            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val writers = listOf("parallel-a", "parallel-b").map { key ->
                    pool.submit<dev.atlas.coach.calendar.write.CalendarWriteResult> {
                        start.await(5, TimeUnit.SECONDS)
                        calendarService(storage).create(calendar.id, parallelDraft, "r3ap-c5-$key")
                    }
                }
                start.countDown()
                val results = writers.map { it.get(30, TimeUnit.SECONDS) }
                assertTrue(results.all { it.successful })
                assertEquals(results[0].link?.eventId, results[1].link?.eventId)
                assertEquals(1, provider.findByCustomAppUri(parallelDraft.provenanceUri).size)
            } finally {
                pool.shutdownNow()
            }
        } finally {
            cleanOwnedUri(newPlanDraft.provenanceUri, newPlanDraft.title)
            cleanOwnedUri(nextRevision.provenanceUri, "ATLAS TEST C5")
            cleanOwnedUri(parallelDraft.provenanceUri, parallelDraft.title)
            storage.deleteRecursively()
        }
    }

    /** Phase 1: create a durable event; parent runs am force-stop before phase 2. */
    @Test
    fun c5Phase1CreateBeforeForceStop() {
        requireReadWritePermissions()
        val calendar = readyAtlasCalendar()
        val draft = draftFor(C5_PLAN, C5_ACTIVITY, "ATLAS TEST C5 force-stop restart", "UTC")
        val storage = File(context.filesDir, C5_STORAGE)
        val existing = provider.findByCustomAppUri(draft.provenanceUri)
        assertTrue(existing.size <= 1)
        if (existing.isNotEmpty()) {
            assertEquals(draft.title, existing.single().title)
            assertEquals(context.packageName, existing.single().customAppPackage)
        }
        val result = calendarService(storage).create(calendar.id, draft, C5_IDEMPOTENCY)
        assertTrue(result.successful)
        assertEquals(1, provider.findByCustomAppUri(draft.provenanceUri).size)
        assertEquals(CalendarEventLinkState.CONFIRMED, result.link?.state)
    }

    /** Phase 2: run after am force-stop in a fresh instrumentation process, then remove the fixture. */
    @Test
    fun c5Phase2VerifyAfterForceStopAndCleanFixture() {
        requireReadWritePermissions()
        val draft = draftFor(C5_PLAN, C5_ACTIVITY, "ATLAS TEST C5 force-stop restart", "UTC")
        val storage = File(context.filesDir, C5_STORAGE)
        val journal = FileCalendarWriteJournal(File(storage, "operations"))
        val links = FileCalendarWriteLinkStore(File(storage, "links"))
        assertEquals(CalendarWriteOperationState.APPLIED, journal.findByActivity(C5_ACTIVITY).single().state)
        val service = CalendarWriteService(provider, journal, linkStore = links)
        service.recoverPending()
        val link = requireNotNull(links.findByActivity(C5_ACTIVITY))
        val verified = service.verify(link)
        assertNull(verified.error)
        assertEquals(CalendarEventLinkState.CONFIRMED, verified.link?.state)
        assertEquals(1, provider.findByCustomAppUri(draft.provenanceUri).size)
        val replay = service.create(readyAtlasCalendar().id, draft, C5_IDEMPOTENCY)
        assertTrue(replay.successful)
        assertEquals(1, provider.findByCustomAppUri(draft.provenanceUri).size)
        assertNull(service.delete(requireNotNull(replay.link), "r3ap-c5-force-stop-cleanup").error)
        assertTrue(provider.findByCustomAppUri(draft.provenanceUri).isEmpty())
        storage.deleteRecursively()
    }

    private fun requireReadWritePermissions() {
        val permissions = provider.permissions()
        assertEquals(CalendarPermissionState.GRANTED, permissions.read)
        assertEquals(CalendarPermissionState.GRANTED, permissions.write)
    }

    private fun readyAtlasCalendar(): CalendarDescriptor {
        requireReadWritePermissions()
        existingAtlasCalendarOrNull()?.let { return it }
        val provisioned = AtlasLocalCalendar.ensure(context)
        assertTrue("ATLAS synthetic calendar provisioning failed: $provisioned", provisioned is AtlasLocalCalendar.Result.Ready)
        return requireNotNull(existingAtlasCalendarOrNull())
    }

    private fun existingAtlasCalendar(): CalendarDescriptor {
        assertEquals(CalendarPermissionState.GRANTED, provider.permissions().read)
        return requireNotNull(existingAtlasCalendarOrNull()) {
            "Run the explicit ATLAS local calendar provisioning test while READ/WRITE_CALENDAR are granted first"
        }
    }

    private fun existingAtlasCalendarOrNull(): CalendarDescriptor? = provider.listCalendars().firstOrNull {
        it.accountType == AtlasLocalCalendar.ACCOUNT_TYPE &&
            it.accountName == AtlasLocalCalendar.ACCOUNT_NAME &&
            it.displayName == AtlasLocalCalendar.CALENDAR_DISPLAY_NAME
    }

    private fun draftFor(planId: String, activityId: String, title: String, timezone: String): CalendarEventDraft {
        val service = CalendarWriteService(
            provider = provider,
            journal = dev.atlas.coach.calendar.write.InMemoryCalendarWriteJournal(),
        )
        return service.draftFromLocalTime(
            activityId = activityId,
            planId = planId,
            title = title,
            localDate = java.time.LocalDate.now(ZoneId.of(timezone)).plusDays(2).toString(),
            startMinute = 13 * 60,
            durationMinutes = 45,
            timezone = timezone,
        )
    }

    private fun calendarService(
        storage: File,
        nowMillis: () -> Long = System::currentTimeMillis,
        beforeFinalPermissionRecheck: (dev.atlas.coach.calendar.write.CalendarWriteOperation) -> Unit = {},
    ): CalendarWriteService = CalendarWriteService(
        provider = provider,
        journal = FileCalendarWriteJournal(File(storage, "operations")),
        nowMillis = nowMillis,
        linkStore = FileCalendarWriteLinkStore(File(storage, "links")),
        beforeFinalPermissionRecheck = beforeFinalPermissionRecheck,
    )

    private fun temporaryStorage(label: String): File =
        File(context.cacheDir, "calendar-r3ap-$label-${UUID.randomUUID()}").apply { mkdirs() }

    private fun cleanOwnedUri(uri: String, expectedTitlePrefix: String) {
        provider.findByCustomAppUri(uri).forEach { event ->
            assertEquals(context.packageName, event.customAppPackage)
            assertTrue(event.title.orEmpty().startsWith(expectedTitlePrefix))
            assertTrue(provider.delete(event.eventId, event.calendarId, uri) == 1)
        }
    }

    private fun createDisposableCalendar(base: CalendarDescriptor): Long {
        assertEquals(AtlasLocalCalendar.ACCOUNT_NAME, base.accountName)
        assertEquals(AtlasLocalCalendar.ACCOUNT_TYPE, base.accountType)
        val syncAdapterUri = syncAdapterCalendarUri(base.accountName!!)
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, base.accountName)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, AtlasLocalCalendar.ACCOUNT_TYPE)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, base.accountName)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, C3_CALENDAR_DISPLAY_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, AtlasLocalCalendar.CALENDAR_COLOR)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "UTC")
            put(CalendarContract.Calendars.DIRTY, 0)
            put(CalendarContract.Calendars.DELETED, 0)
        }
        val inserted = requireNotNull(context.contentResolver.insert(syncAdapterUri, values))
        val id = ContentUris.parseId(inserted)
        try {
            val descriptor = provider.listCalendars().single { it.id == id }
            assertEquals(AtlasLocalCalendar.ACCOUNT_NAME, descriptor.accountName)
            assertEquals(AtlasLocalCalendar.ACCOUNT_TYPE, descriptor.accountType)
            assertEquals(C3_CALENDAR_DISPLAY_NAME, descriptor.displayName)
            assertTrue(calendarEventIds(id).isEmpty())
            return id
        } catch (failure: Throwable) {
            val disposable = provider.listCalendars().firstOrNull { it.id == id }
            if (disposable?.accountName == base.accountName &&
                disposable.accountType == AtlasLocalCalendar.ACCOUNT_TYPE &&
                calendarEventIds(id).isEmpty()
            ) {
                deleteCreatedEmptyCalendar(id, base.accountName!!)
            }
            throw failure
        }
    }

    private fun deleteDisposableCalendar(calendarId: Long, accountName: String): Boolean {
        val descriptor = provider.listCalendars().firstOrNull { it.id == calendarId } ?: return true
        require(descriptor.accountType == AtlasLocalCalendar.ACCOUNT_TYPE)
        require(descriptor.accountName == accountName)
        require(descriptor.displayName == C3_CALENDAR_DISPLAY_NAME)
        return deleteCreatedEmptyCalendar(calendarId, accountName)
    }

    private fun deleteCreatedEmptyCalendar(calendarId: Long, accountName: String): Boolean {
        val descriptor = provider.listCalendars().firstOrNull { it.id == calendarId } ?: return true
        require(descriptor.accountType == AtlasLocalCalendar.ACCOUNT_TYPE)
        require(descriptor.accountName == accountName)
        require(calendarEventIds(calendarId).isEmpty())
        return context.contentResolver.delete(
            syncAdapterCalendarUri(accountName),
            "${CalendarContract.Calendars._ID} = ? AND ${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(calendarId.toString(), accountName, AtlasLocalCalendar.ACCOUNT_TYPE),
        ) == 1
    }

    private fun syncAdapterCalendarUri(accountName: String) = CalendarContract.Calendars.CONTENT_URI.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, AtlasLocalCalendar.ACCOUNT_TYPE)
        .build()

    private fun calendarEventIds(calendarId: Long): List<Long> = context.contentResolver.query(
        CalendarContract.Events.CONTENT_URI,
        arrayOf(CalendarContract.Events._ID),
        "${CalendarContract.Events.CALENDAR_ID} = ?",
        arrayOf(calendarId.toString()),
        null,
    )?.use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }.orEmpty()

    private fun fullEventRow(eventId: Long): List<Pair<String, String?>>? = context.contentResolver.query(
        CalendarContract.Events.CONTENT_URI,
        null,
        "${CalendarContract.Events._ID} = ?",
        arrayOf(eventId.toString()),
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        cursor.columnNames.mapIndexed { index, name -> name to cursorValue(cursor, index) }
    }

    private fun cursorValue(cursor: Cursor, index: Int): String? = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index).toString()
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index).toString()
        Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
        Cursor.FIELD_TYPE_BLOB -> Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP)
        else -> error("unknown CalendarContract cursor field type")
    }

    private fun deleteForeignFixtureIfUnchanged(
        eventId: Long,
        calendarId: Long,
        expectedProvenanceUri: String,
        foreignUri: String,
    ) {
        val row = provider.getEvent(eventId) ?: return
        val observedUri = row.customAppUri ?: return
        if (row.calendarId != calendarId || !row.title.orEmpty().startsWith("ATLAS TEST C2 ") ||
            row.customAppPackage != context.packageName || observedUri !in setOf(foreignUri, expectedProvenanceUri)
        ) return
        val deleted = context.contentResolver.delete(
            CalendarContract.Events.CONTENT_URI,
            "${CalendarContract.Events._ID} = ? AND ${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ? AND ${CalendarContract.Events.CUSTOM_APP_URI} = ?",
            arrayOf(eventId.toString(), calendarId.toString(), context.packageName, observedUri),
        )
        assertEquals(1, deleted)
    }

    private companion object {
        const val C1_STORAGE = "calendar-r3ap-c1-real-process-death"
        const val C1_PLAN = "73000000-0000-4000-8000-000000000001"
        const val C1_ACTIVITY = "73000000-0000-4000-8000-000000000002"
        const val C1_IDEMPOTENCY = "r3ap-c1-real-process-death"
        const val C5_STORAGE = "calendar-r3ap-c5-force-stop"
        const val C5_PLAN = "75000000-0000-4000-8000-000000000001"
        const val C5_ACTIVITY = "75000000-0000-4000-8000-000000000002"
        const val C5_IDEMPOTENCY = "r3ap-c5-force-stop"
        const val C3_CALENDAR_DISPLAY_NAME = "ATLAS R3A PLUS TEST"
        const val PERMISSION_TAG = "AtlasR3aPlusCalendar"
        const val FIXED_CLOCK = 1_800_000_000_000L
    }
}
