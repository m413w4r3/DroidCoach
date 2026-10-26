package dev.atlas.coach.calendar

const val ATLAS_PACKAGE = "dev.atlas.coach"
const val ATLAS_URI_PREFIX = "atlas://plan/"

enum class CalendarPermissionState {
    NOT_DECLARED,
    DENIED,
    GRANTED,
    UNKNOWN,
}

data class CalendarDescriptor(
    val id: Long,
    val displayName: String,
    val accountName: String?,
    val accountType: String?,
    val ownerAccount: String?,
    val accessLevel: Int,
    val visible: Boolean,
    val syncEvents: Boolean,
    val primary: Boolean,
    val timezone: String?,
    val color: Int?,
) {
    init {
        require(accessLevel in 0..800) { "accessLevel must be between 0 and 800" }
    }
}

enum class CalendarEventProvenance {
    ATLAS_OWNED,
    FOREIGN,
    AMBIGUOUS,
}

data class CalendarEventSnapshot(
    val eventId: Long,
    val calendarId: Long,
    val title: String?,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val eventTimezone: String?,
    val rrule: String?,
    val status: Int?,
    val provenance: CalendarEventProvenance = CalendarEventProvenance.AMBIGUOUS,
    val atlasUri: String? = null,
    internal val customAppPackage: String? = null,
    internal val customAppUri: String? = null,
    internal val markerMetadataAvailable: Boolean = true,
)

data class BusyWindow(
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean = false,
    val eventId: Long? = null,
    val calendarId: Long? = null,
) {
    init {
        require(beginMillis < endMillis) { "busy window must have a positive duration" }
    }
}

data class EventConflict(
    val eventId: Long,
    val calendarId: Long,
    val windowIndex: Int,
    val overlapBeginMillis: Long,
    val overlapEndMillis: Long,
)

enum class CalendarProbeError {
    PERMISSION_CHECK_FAILED,
    CALENDAR_ACCESS_DENIED,
    CALENDAR_PROVIDER_UNAVAILABLE,
    CALENDAR_READ_FAILED,
    EVENT_QUERY_FAILED,
    ETAR_PACKAGE_PROBE_FAILED,
}

data class CalendarProbeReport(
    val permission: CalendarPermissionState,
    /** Total descriptors returned by the provider, before visibility/readability filtering. */
    val calendarsCount: Int,
    /** Visible descriptors with at least CAL_ACCESS_READ. */
    val readableCalendarsCount: Int,
    /** Readable descriptors with at least CAL_ACCESS_CONTRIBUTOR. */
    val writableCalendarsCount: Int,
    val etarInstalled: Boolean,
    val etarPackagesChecked: List<String>,
    val providerAvailable: Boolean,
    /** Fixed error codes only; exception messages and calendar/account data are never included. */
    val errors: List<CalendarProbeError>,
)

data class CalendarCatalog(
    val permission: CalendarPermissionState,
    val discoveredCalendarsCount: Int,
    /** Visible and readable calendars available for event queries. */
    val readableCalendars: List<CalendarDescriptor>,
    /** A subset of readableCalendars that has write-capable provider access. */
    val writableCalendars: List<CalendarDescriptor>,
    val providerAvailable: Boolean,
    val errors: List<CalendarProbeError>,
)

data class CalendarEventQueryResult(
    val permission: CalendarPermissionState,
    val events: List<CalendarEventSnapshot>,
    val providerAvailable: Boolean,
    val errors: List<CalendarProbeError>,
)

internal const val CALENDAR_ACCESS_READ = 200
internal const val CALENDAR_ACCESS_CONTRIBUTOR = 500
internal const val MAX_CALENDAR_PROBE_ERRORS = 8
