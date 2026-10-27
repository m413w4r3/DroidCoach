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
 * Idempotent, synthetic fixture used to qualify real Etar visibility.
 *
 * It uses fixed provenance IDs so repeated runs reuse the same event instead of
 * creating duplicates. It deliberately leaves the event in place for the Etar
 * screen check; the parent removes it through the same provider afterwards.
 */
@RunWith(AndroidJUnit4::class)
class AtlasEtarFixtureInstrumentedTest {
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
    fun leavesOneSyntheticEventVisibleForEtar() {
        val provisioned = AtlasLocalCalendar.ensure(context)
        assertTrue(provisioned is AtlasLocalCalendar.Result.Ready)
        val calendar = provisioned as AtlasLocalCalendar.Result.Ready

        val provider = AndroidCalendarContractWriteProvider(context)
        val draft = fixtureDraft()
        val existing = provider.findByCustomAppUri(draft.provenanceUri)
        assertTrue("fixture must not be duplicated", existing.size <= 1)

        val eventId = existing.singleOrNull()?.eventId
            ?: provider.insert(calendar.calendarId, draft, draft.provenanceUri)
        val readBack = provider.getEvent(eventId)
        assertNotNull(readBack)
        assertEquals("ATLAS TEST Etar visibility", readBack?.title)
        assertEquals(calendar.calendarId, readBack?.calendarId)
        assertEquals("dev.atlas.coach", readBack?.customAppPackage)
        assertEquals(draft.provenanceUri, readBack?.customAppUri)
        assertEquals(1, provider.findByCustomAppUri(draft.provenanceUri).size)
    }

    private fun fixtureDraft(): CalendarEventDraft {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val start = now.toLocalDate()
            .atTime(22, 30)
            .atZone(ZoneId.systemDefault())
            .plusDays(if (now.hour >= 22) 1 else 0)
        return CalendarEventDraft(
            activityId = "00000000-0000-4000-8000-000000000002",
            planId = "00000000-0000-4000-8000-000000000001",
            title = "ATLAS TEST Etar visibility",
            dtstartMillis = start.toInstant().toEpochMilli(),
            dtendMillis = start.plusMinutes(45).toInstant().toEpochMilli(),
            timezone = ZoneId.systemDefault().id,
        )
    }
}
