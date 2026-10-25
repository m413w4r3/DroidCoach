package dev.atlas.coach.calendar

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract

/** Android CalendarContract adapter. It deliberately exposes query methods only. */
class AndroidCalendarProvider(
    context: Context,
) : CalendarProvider {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val packageManager: PackageManager = appContext.packageManager

    override fun permissionState(): CalendarPermissionState {
        val info = try {
            requestedPermissionsPackageInfo()
        } catch (_: PackageManager.NameNotFoundException) {
            return CalendarPermissionState.UNKNOWN
        } catch (_: SecurityException) {
            return CalendarPermissionState.DENIED
        }
        val requested = info.requestedPermissions.orEmpty()
        if (Manifest.permission.READ_CALENDAR !in requested) {
            return CalendarPermissionState.NOT_DECLARED
        }
        return if (appContext.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            CalendarPermissionState.GRANTED
        } else {
            CalendarPermissionState.DENIED
        }
    }

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
        return resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            val columns = cursor.columnIndexes()
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        CalendarDescriptor(
                            id = cursor.getLong(columns.getValue(CalendarContract.Calendars._ID)),
                            displayName = cursor.getStringOrNull(
                                columns.getValue(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
                            ).orEmpty(),
                            accountName = cursor.getStringOrNull(columns.getValue(CalendarContract.Calendars.ACCOUNT_NAME)),
                            accountType = cursor.getStringOrNull(columns.getValue(CalendarContract.Calendars.ACCOUNT_TYPE)),
                            ownerAccount = cursor.getStringOrNull(columns.getValue(CalendarContract.Calendars.OWNER_ACCOUNT)),
                            accessLevel = cursor.getInt(
                                columns.getValue(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
                            )
                                .coerceIn(0, 800),
                            visible = cursor.getInt(columns.getValue(CalendarContract.Calendars.VISIBLE)) != 0,
                            syncEvents = cursor.getInt(columns.getValue(CalendarContract.Calendars.SYNC_EVENTS)) != 0,
                            primary = cursor.getInt(columns.getValue(CalendarContract.Calendars.IS_PRIMARY)) != 0,
                            timezone = cursor.getStringOrNull(columns.getValue(CalendarContract.Calendars.CALENDAR_TIME_ZONE)),
                            color = cursor.getIntOrNull(columns.getValue(CalendarContract.Calendars.CALENDAR_COLOR)),
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    override fun queryEvents(
        calendarIds: Set<Long>,
        startMillis: Long,
        endMillis: Long,
    ): List<CalendarEventSnapshot> {
        requireReadPermission()
        require(startMillis < endMillis) { "startMillis must be before endMillis" }
        if (calendarIds.isEmpty()) return emptyList()

        val instances = mutableListOf<CalendarInstance>()
        val instanceProjection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
        )
        val rangeUri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMillis.toString())
            .appendPath(endMillis.toString())
            .build()
        calendarIds.sorted().chunked(MAX_SELECTION_IDS).forEach { ids ->
            val (selection, arguments) = inSelection(CalendarContract.Instances.CALENDAR_ID, ids)
            resolver.query(rangeUri, instanceProjection, selection, arguments, null)?.use { cursor ->
                val columns = cursor.columnIndexes()
                while (cursor.moveToNext()) {
                    instances += CalendarInstance(
                        eventId = cursor.getLong(columns.getValue(CalendarContract.Instances.EVENT_ID)),
                        calendarId = cursor.getLong(columns.getValue(CalendarContract.Instances.CALENDAR_ID)),
                        beginMillis = cursor.getLong(columns.getValue(CalendarContract.Instances.BEGIN)),
                        endMillis = cursor.getLong(columns.getValue(CalendarContract.Instances.END)),
                    )
                }
            }
        }
        if (instances.isEmpty()) return emptyList()

        val eventMetadata = readEventMetadata(instances.map(CalendarInstance::eventId).distinct())
        return instances.map { instance ->
            val metadata = eventMetadata[instance.eventId]
            CalendarEventSnapshot(
                eventId = instance.eventId,
                calendarId = instance.calendarId,
                title = metadata?.title,
                beginMillis = instance.beginMillis,
                endMillis = instance.endMillis,
                allDay = metadata?.allDay ?: false,
                eventTimezone = metadata?.eventTimezone,
                rrule = metadata?.rrule,
                status = metadata?.status,
                customAppPackage = metadata?.customAppPackage,
                customAppUri = metadata?.customAppUri,
                markerMetadataAvailable = metadata != null,
            )
        }
    }

    override fun isPackageInstalled(packageName: String): Boolean {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        return try {
            packageInfo(packageName)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            // Android package visibility can report an installed but undisclosed app this way.
            false
        }
    }

    private fun readEventMetadata(eventIds: List<Long>): Map<Long, EventMetadata> {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.STATUS,
            CalendarContract.Events.CUSTOM_APP_PACKAGE,
            CalendarContract.Events.CUSTOM_APP_URI,
        )
        val result = mutableMapOf<Long, EventMetadata>()
        eventIds.sorted().chunked(MAX_SELECTION_IDS).forEach { ids ->
            val (selection, arguments) = inSelection(CalendarContract.Events._ID, ids)
            resolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                arguments,
                null,
            )?.use { cursor ->
                val columns = cursor.columnIndexes()
                while (cursor.moveToNext()) {
                    result[cursor.getLong(columns.getValue(CalendarContract.Events._ID))] = EventMetadata(
                        title = cursor.getStringOrNull(columns.getValue(CalendarContract.Events.TITLE)),
                        allDay = cursor.getInt(columns.getValue(CalendarContract.Events.ALL_DAY)) != 0,
                        eventTimezone = cursor.getStringOrNull(columns.getValue(CalendarContract.Events.EVENT_TIMEZONE)),
                        rrule = cursor.getStringOrNull(columns.getValue(CalendarContract.Events.RRULE)),
                        status = cursor.getIntOrNull(columns.getValue(CalendarContract.Events.STATUS)),
                        customAppPackage = cursor.getStringOrNull(
                            columns.getValue(CalendarContract.Events.CUSTOM_APP_PACKAGE),
                        ),
                        customAppUri = cursor.getStringOrNull(
                            columns.getValue(CalendarContract.Events.CUSTOM_APP_URI),
                        ),
                    )
                }
            }
        }
        return result
    }

    private fun requireReadPermission() {
        if (permissionState() != CalendarPermissionState.GRANTED) {
            throw SecurityException("Calendar read access is unavailable")
        }
    }

    @Suppress("DEPRECATION")
    private fun requestedPermissionsPackageInfo() =
        packageManager.getPackageInfo(appContext.packageName, PackageManager.GET_PERMISSIONS)

    @Suppress("DEPRECATION")
    private fun packageInfo(packageName: String) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
    } else {
        packageManager.getPackageInfo(packageName, 0)
    }

    private fun inSelection(column: String, ids: List<Long>): Pair<String, Array<String>> {
        require(ids.isNotEmpty())
        return "$column IN (${List(ids.size) { "?" }.joinToString(",")})" to
            ids.map(Long::toString).toTypedArray()
    }

    private fun Cursor.columnIndexes(): Map<String, Int> = buildMap {
        for (index in 0 until columnCount) put(getColumnName(index), index)
    }

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private fun Cursor.getIntOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)

    private data class CalendarInstance(
        val eventId: Long,
        val calendarId: Long,
        val beginMillis: Long,
        val endMillis: Long,
    )

    private data class EventMetadata(
        val title: String?,
        val allDay: Boolean,
        val eventTimezone: String?,
        val rrule: String?,
        val status: Int?,
        val customAppPackage: String?,
        val customAppUri: String?,
    )

    companion object {
        // Keep SQLite bind counts below the usual 999 argument limit.
        private const val MAX_SELECTION_IDS = 900
    }
}
