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
import java.util.UUID

/**
 * Qualifies the real sync-adapter path on API 37: the provider must accept the
 * ATLAS local calendar row, then accept and read back a synthetic event.
 *
 * This test grants only READ_CALENDAR and WRITE_CALENDAR through UiAutomation;
 * it never modifies an existing calendar or a personal account.
 */
@RunWith(AndroidJUnit4::class)
class AtlasLocalCalendarInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Before
    fun grantCalendarPermissions() {
        val automation = instrumentation.uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CALENDAR)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_CALENDAR)
    }

    @Test
    fun createsDedicatedLocalCalendarAndWritesSyntheticEvent() {
        val provisioned = AtlasLocalCalendar.ensure(context)
        assertTrue("provisioning failed: $provisioned", provisioned is AtlasLocalCalendar.Result.Ready)
        val ready = provisioned as AtlasLocalCalendar.Result.Ready
        assertTrue(ready.calendarId > 0)
        assertEquals(AtlasLocalCalendar.CALENDAR_DISPLAY_NAME, ready.displayName)

        val provider = AndroidCalendarContractWriteProvider(context)
        val descriptor = provider.listCalendars().single { it.id == ready.calendarId }
        assertEquals(AtlasLocalCalendar.ACCOUNT_TYPE, descriptor.accountType)
        assertEquals(AtlasLocalCalendar.ACCOUNT_NAME, descriptor.accountName)
        assertTrue(descriptor.visible)

        val activityId = UUID.randomUUID().toString()
        val planId = UUID.randomUUID().toString()
        val start = System.currentTimeMillis() + 3_600_000L
        val draft = CalendarEventDraft(
            activityId = activityId,
            planId = planId,
            title = "ATLAS TEST local calendar",
            dtstartMillis = start,
            dtendMillis = start + 1_800_000L,
            timezone = ZoneId.systemDefault().id,
        )

        val eventId = provider.insert(ready.calendarId, draft, draft.provenanceUri)
        try {
            val event = provider.getEvent(eventId)
            assertNotNull(event)
            requireNotNull(event)
            assertEquals(ready.calendarId, event.calendarId)
            assertEquals("ATLAS TEST local calendar", event.title)
            assertEquals(start, event.dtstartMillis)
            assertEquals("dev.atlas.coach", event.customAppPackage)
            assertEquals(draft.provenanceUri, event.customAppUri)

            val matches = provider.findByCustomAppUri(draft.provenanceUri)
            assertEquals(1, matches.size)
            assertEquals(eventId, matches.single().eventId)
        } finally {
            provider.delete(eventId, ready.calendarId, draft.provenanceUri)
        }
        val afterDelete = provider.getEvent(eventId)
        assertTrue(afterDelete == null || afterDelete.deleted)
    }
}
