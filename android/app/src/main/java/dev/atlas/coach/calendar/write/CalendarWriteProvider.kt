package dev.atlas.coach.calendar.write

import dev.atlas.coach.calendar.CalendarDescriptor

/** Injectable boundary for CalendarContract writes and their read-back. */
interface CalendarWriteProvider {
    fun permissions(): CalendarWritePermissions

    fun listCalendars(): List<CalendarDescriptor>

    fun insert(calendarId: Long, draft: CalendarEventDraft, provenanceUri: String): Long

    fun update(eventId: Long, calendarId: Long, draft: CalendarEventDraft, provenanceUri: String)

    fun delete(eventId: Long, calendarId: Long, provenanceUri: String)

    fun getEvent(eventId: Long): CalendarWriteEvent?

    fun findByCustomAppUri(provenanceUri: String): List<CalendarWriteEvent>
}
