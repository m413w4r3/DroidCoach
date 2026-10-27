package dev.atlas.coach.calendar.local

import android.Manifest
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.calendar.write.AndroidCalendarContractWriteProvider
import dev.atlas.coach.calendar.write.CalendarEventLinkState
import dev.atlas.coach.calendar.write.CalendarWriteError
import dev.atlas.coach.calendar.write.CalendarWriteService
import dev.atlas.coach.calendar.write.FileCalendarWriteJournal
import dev.atlas.coach.calendar.write.FileCalendarWriteLinkStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId
import java.util.UUID

/**
 * Qualifies the durable write engine against the real CalendarProvider and the
 * dedicated ATLAS local calendar. Uses synthetic data only and a per-run
 * storage directory; the fixture calendar itself is never deleted.
 */
@RunWith(AndroidJUnit4::class)
class AtlasCalendarEngineInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val planId = "33333333-3333-4333-8333-333333333333"
    private val activityId = "44444444-4444-4444-8444-444444444444"
    private val crashActivityId = "55555555-5555-4555-8555-555555555555"
    private lateinit var storage: File
    private lateinit var provider: AndroidCalendarContractWriteProvider
    private var calendarId: Long = 0

    @Before
    fun setUp() {
        val automation = instrumentation.uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CALENDAR)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_CALENDAR)
        val provisioned = AtlasLocalCalendar.ensure(context)
        assertTrue(provisioned is AtlasLocalCalendar.Result.Ready)
        calendarId = (provisioned as AtlasLocalCalendar.Result.Ready).calendarId
        provider = AndroidCalendarContractWriteProvider(context)
        storage = File(context.cacheDir, "calendar-engine-${UUID.randomUUID()}").apply { mkdirs() }
        cleanupProvenance(planId, activityId)
        cleanupProvenance(planId, crashActivityId)
    }

    @After
    fun tearDown() {
        cleanupProvenance(planId, activityId)
        cleanupProvenance(planId, crashActivityId)
        storage.deleteRecursively()
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.WRITE_CALENDAR,
        )
    }

    @Test
    fun durableCreateRestartVerifyIdempotenceDivergenceAndDelete() {
        val service = newService()
        val draft = draft(activityId, "ATLAS TEST engine create", startMinute = 600)

        val created = service.create(calendarId, draft, "r3a-engine-create")
        assertNull(created.error)
        assertTrue(created.successful)
        val link = requireNotNull(created.link)
        assertEquals(CalendarEventLinkState.CONFIRMED, link.state)
        val eventId = requireNotNull(link.eventId)
        assertEquals(1, provider.findByCustomAppUri(draft.provenanceUri).size)

        // New service instance on the same durable directory = app restart.
        val restarted = newService()
        val recovered = restarted.verify(link)
        assertNull(recovered.error)
        assertEquals(CalendarEventLinkState.CONFIRMED, recovered.link?.state)
        assertEquals(eventId, recovered.link?.eventId)

        // Same idempotency key must not duplicate the provider row.
        val replay = restarted.create(calendarId, draft, "r3a-engine-create")
        assertNull(replay.error)
        assertEquals(1, provider.findByCustomAppUri(draft.provenanceUri).size)

        val moved = restarted.draftFromLocalTime(
            activityId = activityId,
            planId = planId,
            title = "ATLAS TEST engine moved",
            localDate = draft.localDate(),
            startMinute = 630,
            durationMinutes = 50,
            timezone = ZoneId.systemDefault().id,
        )
        val updated = restarted.update(requireNotNull(recovered.link), moved, "r3a-engine-update")
        assertNull(updated.error)
        val movedLink = requireNotNull(updated.link)
        assertEquals(CalendarEventLinkState.CONFIRMED, movedLink.state)
        assertEquals(1, provider.findByCustomAppUri(draft.provenanceUri).size)

        // A manual edit in Etar (same markers, different title) is a divergence.
        provider.update(eventId, calendarId, moved.copy(title = "ATLAS TEST manual Etar edit"), moved.provenanceUri)
        val diverged = restarted.verify(movedLink)
        assertEquals(CalendarWriteError.READ_BACK_MISMATCH, diverged.error)
        assertEquals(CalendarEventLinkState.DIVERGED, diverged.link?.state)

        // Restore the expected content, then delete through the engine.
        provider.update(eventId, calendarId, moved, moved.provenanceUri)
        val restored = restarted.verify(movedLink)
        assertEquals(CalendarEventLinkState.CONFIRMED, restored.link?.state)
        val deleted = restarted.delete(requireNotNull(restored.link), "r3a-engine-delete")
        assertNull(deleted.error)
        assertEquals(CalendarEventLinkState.MISSING, deleted.link?.state)
        assertTrue(provider.findByCustomAppUri(draft.provenanceUri).none { !it.deleted })
    }

    @Test
    fun crashBeforeProviderEffectRecoversWithoutDuplicate() {
        val crashing = CalendarWriteService(
            provider = provider,
            journal = FileCalendarWriteJournal(File(storage, "operations")),
            linkStore = FileCalendarWriteLinkStore(File(storage, "links")),
            beforeProviderEffect = { throw SimulatedCrash() },
        )
        val draft = draft(crashActivityId, "ATLAS TEST engine crash", startMinute = 800)
        try {
            crashing.create(calendarId, draft, "r3a-engine-crash")
            error("the simulated crash did not run")
        } catch (_: SimulatedCrash) {
            // Expected: the operation is durable and PREPARED, no provider effect yet.
        }
        assertTrue(provider.findByCustomAppUri(draft.provenanceUri).isEmpty())

        val restarted = newService()
        val results = restarted.recoverPending()
        assertNotNull(results)
        assertTrue(provider.findByCustomAppUri(draft.provenanceUri).isEmpty())
    }

    private fun newService(): CalendarWriteService = CalendarWriteService.durable(provider, storage)

    private fun draft(activity: String, title: String, startMinute: Int) = CalendarWriteService.durable(provider, storage)
        .draftFromLocalTime(
            activityId = activity,
            planId = planId,
            title = title,
            localDate = java.time.LocalDate.now().toString(),
            startMinute = startMinute,
            durationMinutes = 45,
            timezone = ZoneId.systemDefault().id,
        )

    private fun dev.atlas.coach.calendar.write.CalendarEventDraft.localDate(): String =
        java.time.Instant.ofEpochMilli(dtstartMillis)
            .atZone(ZoneId.of(timezone))
            .toLocalDate()
            .toString()

    private fun cleanupProvenance(plan: String, activity: String) {
        val uri = "atlas://plan/$plan/activity/$activity"
        provider.findByCustomAppUri(uri)
            .filterNot { it.deleted }
            .forEach { provider.delete(it.eventId, it.calendarId, uri) }
    }

    private class SimulatedCrash : RuntimeException()
}
