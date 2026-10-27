package dev.atlas.coach.calendar.local

import android.Manifest
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.calendar.write.AndroidCalendarContractWriteProvider
import dev.atlas.coach.calendar.write.CalendarWriteError
import dev.atlas.coach.calendar.write.CalendarWriteService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId
import java.util.UUID

/**
 * Permission gates are exercised with the OS permission changed externally by
 * the parent between two fresh instrumentation processes. Revoking a runtime
 * permission from inside the process kills it, so it must never be done here.
 */
@RunWith(AndroidJUnit4::class)
class AtlasCalendarPermissionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val planId = "66666666-6666-4666-8666-666666666666"
    private val activityId = "77777777-7777-4777-8777-777777777777"
    private lateinit var provider: AndroidCalendarContractWriteProvider
    private lateinit var storage: File

    @Before
    fun setUp() {
        provider = AndroidCalendarContractWriteProvider(context)
        storage = File(context.cacheDir, "calendar-permission-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @Test
    fun writePermissionDeniedBlocksCreateWithoutEffect() {
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.READ_CALENDAR,
        )
        val calendar = provider.listCalendars().firstOrNull {
            it.accountType == AtlasLocalCalendar.ACCOUNT_TYPE
        } ?: error("ATLAS local calendar missing; run provisioning first")
        val draft = draft("ATLAS TEST permission denied")
        val result = CalendarWriteService.durable(provider, storage)
            .create(calendar.id, draft, "r3a-permission-denied")
        assertEquals(CalendarWriteError.PERMISSION_DENIED, result.error)
        assertTrue(provider.findByCustomAppUri(draft.provenanceUri).isEmpty())
    }

    @Test
    fun writePermissionGrantedAllowsCreateAndDelete() {
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.READ_CALENDAR,
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.WRITE_CALENDAR,
        )
        val calendar = provider.listCalendars().firstOrNull {
            it.accountType == AtlasLocalCalendar.ACCOUNT_TYPE
        } ?: error("ATLAS local calendar missing; run provisioning first")
        val draft = draft("ATLAS TEST permission granted")
        provider.findByCustomAppUri(draft.provenanceUri).forEach {
            provider.delete(it.eventId, it.calendarId, draft.provenanceUri)
        }
        val service = CalendarWriteService.durable(provider, storage)
        val created = service.create(calendar.id, draft, "r3a-permission-granted")
        assertEquals(null, created.error)
        assertTrue(created.successful)
        val link = requireNotNull(created.link)
        assertEquals(1, provider.findByCustomAppUri(draft.provenanceUri).size)
        val deleted = service.delete(link, "r3a-permission-granted-delete")
        assertEquals(null, deleted.error)
        assertTrue(provider.findByCustomAppUri(draft.provenanceUri).isEmpty())
    }

    private fun draft(title: String) = CalendarWriteService.durable(provider, storage)
        .draftFromLocalTime(
            activityId = activityId,
            planId = planId,
            title = title,
            localDate = java.time.LocalDate.now().toString(),
            startMinute = 900,
            durationMinutes = 30,
            timezone = ZoneId.systemDefault().id,
        )
}
