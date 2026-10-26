package dev.atlas.coach.calendar.write

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import dev.atlas.coach.calendar.CalendarDescriptor
import dev.atlas.coach.calendar.CalendarPermissionState

/** Real CalendarContract adapter. JVM tests use [CalendarWriteProvider] fakes instead. */
class AndroidCalendarContractWriteProvider(context: Context) : CalendarWriteProvider {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override fun permissions(): CalendarWritePermissions = CalendarWritePermissions(
        read = permissionState(Manifest.permission.READ_CALENDAR),
        write = permissionState(Manifest.permission.WRITE_CALENDAR),
    )

    override fun listCalendars(): List<CalendarDescriptor> {
        requireReadPermission()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_TIME_ZONE,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )
        return resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)
            ?.use { cursor ->
                val columns = projection.associateWith(cursor::getColumnIndexOrThrow)
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            CalendarDescriptor(
                                id = cursor.getLong(columns.getValue(CalendarContract.Calendars._ID)),
                                displayName = cursor.getStringOrNull(columns.getValue(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)).orEmpty(),
                                accountName = cursor.getStringOrNull(columns.getValue(CalendarContract.Calendars.ACCOUNT_NAME)),
                                accountType = cursor.getStringOrNull(columns.getValue(CalendarContract.Calendars.ACCOUNT_TYPE)),
                                ownerAccount = cursor.getStringOrNull(columns.getValue(CalendarContract.Calendars.OWNER_ACCOUNT)),
                                accessLevel = cursor.getInt(columns.getValue(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)),
                                visible = cursor.getInt(columns.getValue(CalendarContract.Calendars.VISIBLE)) != 0,
                                syncEvents = cursor.getInt(columns.getValue(CalendarContract.Calendars.SYNC_EVENTS)) != 0,
                                primary = cursor.getInt(columns.getValue(CalendarContract.Calendars.IS_PRIMARY)) != 0,
                                timezone = cursor.getStringOrNull(columns.getValue(CalendarContract.Calendars.CALENDAR_TIME_ZONE)),
                                color = cursor.getIntOrNull(columns.getValue(CalendarContract.Calendars.CALENDAR_COLOR)),
                            ),
                        )
                    }
                }
            }
            .orEmpty()
    }

    override fun insert(calendarId: Long, draft: CalendarEventDraft, provenanceUri: String): Long {
        requireWritePermissions()
        val inserted = resolver.insert(CalendarContract.Events.CONTENT_URI, eventValues(calendarId, draft, provenanceUri))
            ?: throw IllegalStateException("calendar insert returned no row")
        return ContentUris.parseId(inserted)
    }

    override fun update(eventId: Long, calendarId: Long, draft: CalendarEventDraft, provenanceUri: String) {
        requireWritePermissions()
        val updated = resolver.update(
            CalendarContract.Events.CONTENT_URI,
            eventValues(calendarId, draft, provenanceUri),
            ownershipSelection(),
            ownershipArguments(eventId, calendarId, provenanceUri),
        )
        if (updated != 1) throw IllegalStateException("calendar update did not affect one row")
    }

    override fun delete(eventId: Long, calendarId: Long, provenanceUri: String): Int {
        requireWritePermissions()
        return resolver.delete(
            CalendarContract.Events.CONTENT_URI,
            ownershipSelection(),
            ownershipArguments(eventId, calendarId, provenanceUri),
        )
    }

    override fun getEvent(eventId: Long): CalendarWriteEvent? {
        requireReadPermission()
        return queryEvents(
            selection = "${CalendarContract.Events._ID} = ?",
            selectionArgs = arrayOf(eventId.toString()),
        ).firstOrNull()
    }

    override fun findByCustomAppUri(provenanceUri: String): List<CalendarWriteEvent> {
        requireReadPermission()
        return queryEvents(
            selection = "${CalendarContract.Events.CUSTOM_APP_URI} = ?",
            selectionArgs = arrayOf(provenanceUri),
        )
    }

    private fun queryEvents(selection: String, selectionArgs: Array<String>): List<CalendarWriteEvent> {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.DELETED,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.CUSTOM_APP_PACKAGE,
            CalendarContract.Events.CUSTOM_APP_URI,
        )
        return resolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            val columns = projection.associateWith(cursor::getColumnIndexOrThrow)
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        CalendarWriteEvent(
                            eventId = cursor.getLong(columns.getValue(CalendarContract.Events._ID)),
                            calendarId = cursor.getLong(columns.getValue(CalendarContract.Events.CALENDAR_ID)),
                            title = cursor.getStringOrNull(columns.getValue(CalendarContract.Events.TITLE)),
                            dtstartMillis = cursor.getLong(columns.getValue(CalendarContract.Events.DTSTART)),
                            dtendMillis = cursor.getLong(columns.getValue(CalendarContract.Events.DTEND)),
                            timezone = cursor.getStringOrNull(columns.getValue(CalendarContract.Events.EVENT_TIMEZONE)),
                            allDay = cursor.getInt(columns.getValue(CalendarContract.Events.ALL_DAY)) != 0,
                            deleted = cursor.getInt(columns.getValue(CalendarContract.Events.DELETED)) != 0,
                            rrule = cursor.getStringOrNull(columns.getValue(CalendarContract.Events.RRULE)),
                            customAppPackage = cursor.getStringOrNull(columns.getValue(CalendarContract.Events.CUSTOM_APP_PACKAGE)),
                            customAppUri = cursor.getStringOrNull(columns.getValue(CalendarContract.Events.CUSTOM_APP_URI)),
                            markerMetadataAvailable = true,
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    private fun eventValues(
        calendarId: Long,
        draft: CalendarEventDraft,
        provenanceUri: String,
    ): ContentValues = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calendarId)
        put(CalendarContract.Events.DTSTART, draft.dtstartMillis)
        put(CalendarContract.Events.DTEND, draft.dtendMillis)
        put(CalendarContract.Events.EVENT_TIMEZONE, draft.timezone)
        put(CalendarContract.Events.TITLE, draft.title)
        put(CalendarContract.Events.ALL_DAY, 0)
        put(CalendarContract.Events.CUSTOM_APP_PACKAGE, ATLAS_WRITE_PACKAGE)
        put(CalendarContract.Events.CUSTOM_APP_URI, provenanceUri)
    }

    private fun ownershipSelection(): String = listOf(
        "${CalendarContract.Events._ID} = ?",
        "${CalendarContract.Events.CALENDAR_ID} = ?",
        "${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ?",
        "${CalendarContract.Events.CUSTOM_APP_URI} = ?",
        "${CalendarContract.Events.RRULE} IS NULL",
        "${CalendarContract.Events.DELETED} = 0",
    ).joinToString(" AND ")

    private fun ownershipArguments(eventId: Long, calendarId: Long, provenanceUri: String): Array<String> = arrayOf(
        eventId.toString(),
        calendarId.toString(),
        ATLAS_WRITE_PACKAGE,
        provenanceUri,
    )

    private fun requireReadPermission() {
        if (permissionState(Manifest.permission.READ_CALENDAR) != CalendarPermissionState.GRANTED) {
            throw SecurityException("calendar read permission denied")
        }
    }

    private fun requireWritePermissions() {
        if (!permissions().granted) throw SecurityException("calendar write permissions denied")
    }

    private fun permissionState(permission: String): CalendarPermissionState =
        if (appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            CalendarPermissionState.GRANTED
        } else {
            CalendarPermissionState.DENIED
        }
}

private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

private fun Cursor.getIntOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)
