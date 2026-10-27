package dev.atlas.coach.calendar.local

import android.Manifest
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.calendar.write.AndroidCalendarContractWriteProvider
import dev.atlas.coach.calendar.write.CalendarEventDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Stepwise provider-only qualification helper for the Etar visibility fixture.
 *
 * The parent runs one method at a time and checks Etar between states:
 * restore -> move/edit -> delete -> restore.
 */
@RunWith(AndroidJUnit4::class)
class AtlasEtarLifecycleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Before
    fun grantCalendarPermissions() {
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.READ_CALENDAR,
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.WRITE_CALENDAR,
        )
    }

    @Test
    fun restoresFixture() {
        val (provider, calendarId) = readyProvider()
        val draft = draft(title = "ATLAS TEST Etar visibility", startOffsetMinutes = 0)
        val matches = provider.findByCustomAppUri(draft.provenanceUri)
        assertTrue(matches.size <= 1)
        val eventId = matches.singleOrNull()?.eventId
            ?: provider.insert(calendarId, draft, draft.provenanceUri)
        val readBack = provider.getEvent(eventId)
        assertNotNull(readBack)
        assertEquals("ATLAS TEST Etar visibility", readBack?.title)
        assertEquals(1, provider.findByCustomAppUri(draft.provenanceUri).size)
    }

    @Test
    fun movesAndEditsFixture() {
        val (provider, calendarId) = readyProvider()
        val current = provider.findByCustomAppUri(PROVENANCE).singleOrNull()
            ?: provider.findByCustomAppUri(PROVENANCE).firstOrNull()
            ?: error("fixture missing; run restoresFixture first")
        val moved = draft(title = "ATLAS TEST Etar moved", startOffsetMinutes = 30)
        provider.update(current.eventId, calendarId, moved, PROVENANCE)
        val readBack = provider.getEvent(current.eventId)
        assertNotNull(readBack)
        assertEquals("ATLAS TEST Etar moved", readBack?.title)
        assertEquals(moved.dtstartMillis, readBack?.dtstartMillis)
        assertEquals(moved.dtendMillis, readBack?.dtendMillis)
        assertEquals(1, provider.findByCustomAppUri(PROVENANCE).size)
    }

    @Test
    fun deletesFixture() {
        val (provider, calendarId) = readyProvider()
        val current = provider.findByCustomAppUri(PROVENANCE).firstOrNull()
            ?: return
        provider.delete(current.eventId, calendarId, PROVENANCE)
        val after = provider.getEvent(current.eventId)
        assertTrue(after == null || after.deleted)
        assertTrue(provider.findByCustomAppUri(PROVENANCE).none { !it.deleted })
    }

    private fun readyProvider(): Pair<AndroidCalendarContractWriteProvider, Long> {
        val provisioned = AtlasLocalCalendar.ensure(context)
        assertTrue(provisioned is AtlasLocalCalendar.Result.Ready)
        val calendar = provisioned as AtlasLocalCalendar.Result.Ready
        return AndroidCalendarContractWriteProvider(context) to calendar.calendarId
    }

    private fun draft(title: String, startOffsetMinutes: Int): CalendarEventDraft {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val baseDate = if (now.hour >= 22) now.toLocalDate().plusDays(1) else now.toLocalDate()
        val start = baseDate.atTime(22, 30)
            .atZone(ZoneId.systemDefault())
            .plusMinutes(startOffsetMinutes.toLong())
        return CalendarEventDraft(
            activityId = "00000000-0000-4000-8000-000000000002",
            planId = "00000000-0000-4000-8000-000000000001",
            title = title,
            dtstartMillis = start.toInstant().toEpochMilli(),
            dtendMillis = start.plusMinutes(45).toInstant().toEpochMilli(),
            timezone = ZoneId.systemDefault().id,
        )
    }

    private companion object {
        const val PROVENANCE =
            "atlas://plan/00000000-0000-4000-8000-000000000001/activity/00000000-0000-4000-8000-000000000002"
    }
}
