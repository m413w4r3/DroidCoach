package dev.atlas.coach.calendar.write

import dev.atlas.coach.calendar.CalendarDescriptor

/** Injectable boundary for CalendarContract writes and their read-back. */
interface CalendarWriteProvider {
    fun permissions(): CalendarWritePermissions

    fun listCalendars(): List<CalendarDescriptor>

    fun insert(calendarId: Long, draft: CalendarEventDraft, provenanceUri: String): Long

    fun update(eventId: Long, calendarId: Long, draft: CalendarEventDraft, provenanceUri: String)

    /** Returns the number of provider rows affected by the ownership-scoped delete. */
    fun delete(eventId: Long, calendarId: Long, provenanceUri: String): Int

    fun getEvent(eventId: Long): CalendarWriteEvent?

    fun findByCustomAppUri(provenanceUri: String): List<CalendarWriteEvent>
}
