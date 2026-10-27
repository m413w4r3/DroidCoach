package dev.atlas.coach.calendar.local

import android.Manifest
import android.content.Context
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Final campaign cleanup. Removes only the ATLAS synthetic account/calendar and
 * its ATLAS-package events. Personal calendars and accounts are counted before
 * and after but never listed, modified or deleted.
 */
@RunWith(AndroidJUnit4::class)
class AtlasCampaignCleanupInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test
    fun removesOnlyTheAtlasSyntheticCalendarAndEvents() {
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.READ_CALENDAR,
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.WRITE_CALENDAR,
        )

        val calendarsBefore = calendarCounts()
        val atlasEvents = atlasEventPackages()
        assertTrue(
            "ATLAS calendar contains an event from another package: $atlasEvents",
            atlasEvents.all { it == context.packageName },
        )

        assertTrue("ATLAS calendar cleanup failed", AtlasLocalCalendar.remove(context))

        val calendarsAfter = calendarCounts()
        assertEquals(0, calendarsAfter.atlas)
        assertEquals(
            "personal calendar count changed unexpectedly",
            calendarsBefore.personal,
            calendarsAfter.personal,
        )
        assertTrue("ATLAS events remain after cleanup", atlasEventPackages().isEmpty())
    }

    private fun calendarCounts(): Counts {
        var atlas = 0
        var personal = 0
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.ACCOUNT_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == AtlasLocalCalendar.ACCOUNT_TYPE) atlas += 1 else personal += 1
            }
        }
        return Counts(atlas = atlas, personal = personal)
    }

    private fun atlasEventPackages(): List<String> {
        val packages = mutableListOf<String>()
        val atlasCalendarIds = mutableSetOf<Long>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_TYPE),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == AtlasLocalCalendar.ACCOUNT_TYPE) atlasCalendarIds += cursor.getLong(0)
            }
        }
        if (atlasCalendarIds.isEmpty()) return packages
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.CUSTOM_APP_PACKAGE),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getLong(0) in atlasCalendarIds) {
                    packages += cursor.getString(1).orEmpty()
                }
            }
        }
        return packages
    }

    private data class Counts(val atlas: Int, val personal: Int)
}
