package dev.atlas.coach.calendar.local

import android.Manifest
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.calendar.write.AndroidCalendarContractWriteProvider
import dev.atlas.coach.calendar.write.CalendarEventLinkState
import dev.atlas.coach.calendar.write.CalendarWriteService
import dev.atlas.coach.calendar.write.FileCalendarWriteLinkStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Two-step qualification for a real manual edit inside Etar:
 *  1. create a linked ATLAS event through the durable engine;
 *  2. the parent edits it in Etar's editor, then runs the detection step.
 */
@RunWith(AndroidJUnit4::class)
class AtlasEtarManualDivergenceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val planId = "88888888-8888-4888-8888-888888888888"
    private val activityId = "99999999-9999-4999-8999-999999999999"
    private val provenance = "atlas://plan/$planId/activity/$activityId"
    private val storage = File(context.filesDir, "calendar-manual-divergence")

    @Test
    fun createsLinkedEventForManualEtarEdit() {
        grantPermissions()
        val provider = AndroidCalendarContractWriteProvider(context)
        val calendar = provider.listCalendars().firstOrNull {
            it.accountType == AtlasLocalCalendar.ACCOUNT_TYPE
        } ?: error("ATLAS local calendar missing")
        storage.deleteRecursively()
        storage.mkdirs()
        provider.findByCustomAppUri(provenance).forEach {
            provider.delete(it.eventId, it.calendarId, provenance)
        }
        val service = CalendarWriteService.durable(provider, storage)
        val draft = service.draftFromLocalTime(
            activityId = activityId,
            planId = planId,
            title = "ATLAS TEST manual divergence original",
            localDate = LocalDate.now().plusDays(1).toString(),
            startMinute = 600,
            durationMinutes = 45,
            timezone = ZoneId.systemDefault().id,
        )
        val result = service.create(calendar.id, draft, "r3a-manual-etar-create")
        assertNull(result.error)
        assertTrue(result.successful)
        assertEquals(CalendarEventLinkState.CONFIRMED, result.link?.state)
        assertEquals(1, provider.findByCustomAppUri(provenance).size)
    }

    @Test
    fun detectsManualEtarEdit() {
        grantPermissions()
        val provider = AndroidCalendarContractWriteProvider(context)
        val link = FileCalendarWriteLinkStore(File(storage, "links"))
            .findByActivity(activityId)
            ?: error("no linked event; run createsLinkedEventForManualEtarEdit first")
        val result = CalendarWriteService.durable(provider, storage).verify(link)
        assertEquals(CalendarEventLinkState.DIVERGED, result.link?.state)
        provider.findByCustomAppUri(provenance).forEach {
            provider.delete(it.eventId, it.calendarId, provenance)
        }
    }

    private fun grantPermissions() {
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.READ_CALENDAR,
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.WRITE_CALENDAR,
        )
    }
}
