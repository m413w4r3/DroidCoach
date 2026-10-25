package dev.atlas.coach.calendar

/** Read-only boundary around Android CalendarContract and package visibility. */
interface CalendarProvider {
    fun permissionState(): CalendarPermissionState

    fun listCalendars(): List<CalendarDescriptor>

    fun queryEvents(
        calendarIds: Set<Long>,
        startMillis: Long,
        endMillis: Long,
    ): List<CalendarEventSnapshot>

    fun isPackageInstalled(packageName: String): Boolean
}
