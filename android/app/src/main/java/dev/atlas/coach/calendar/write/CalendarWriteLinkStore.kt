package dev.atlas.coach.calendar.write

import org.json.JSONObject
import java.io.File

/** Durable mapping from one plan activity to its current CalendarContract event. */
interface CalendarWriteLinkStore {
    fun upsert(link: CalendarEventLink)

    fun findByActivity(activityId: String): CalendarEventLink?

    fun findByEventId(eventId: Long): CalendarEventLink?

    fun findByProvenanceUri(provenanceUri: String): CalendarEventLink?
}

/** Volatile fallback retained for existing diagnostic and JVM callers. */
class InMemoryCalendarWriteLinkStore : CalendarWriteLinkStore {
    private val links = linkedMapOf<String, CalendarEventLink>()

    @Synchronized
    override fun upsert(link: CalendarEventLink) {
        validateLinkIndexes(links.values, link)
        links[link.activityId] = link
    }

    @Synchronized
    override fun findByActivity(activityId: String): CalendarEventLink? = links[activityId]

    @Synchronized
    override fun findByEventId(eventId: Long): CalendarEventLink? = links.values.firstOrNull { it.eventId == eventId }

    @Synchronized
    override fun findByProvenanceUri(provenanceUri: String): CalendarEventLink? =
        links.values.firstOrNull { it.provenanceUri == provenanceUri }
}

/**
 * File-backed link index with one versioned JSON record per activity. Version 1 contains link,
 * activity, plan, calendar, and event IDs; provenance URI; state; content hash; verification time;
 * and stable error code. Every replacement is fsynced and atomically renamed, so lookups can be
 * rebuilt after restart.
 */
class FileCalendarWriteLinkStore(
    private val directory: File,
    private val migration: CalendarWriteRecordMigration =
        CalendarWriteFileStorage.migrationForCurrentVersion(FORMAT_VERSION),
) : CalendarWriteLinkStore {
    private val monitor = CalendarWriteFileStorage.lockFor(directory)
    private val links = linkedMapOf<String, CalendarEventLink>()
    private val quarantinedActivities = mutableSetOf<String>()

    init {
        synchronized(monitor) {
            CalendarWriteFileStorage.ensureDirectory(directory)
            reloadFromDisk()
        }
    }

    override fun upsert(link: CalendarEventLink) = synchronized(monitor) {
        reloadFromDisk()
        require(link.activityId !in quarantinedActivities) { "calendar link record is quarantined" }
        validateLinkIndexes(links.values, link)
        CalendarWriteFileStorage.writeAtomically(directory, "${link.activityId}.json", link.toJson().toString())
        links[link.activityId] = link
    }

    override fun findByActivity(activityId: String): CalendarEventLink? = synchronized(monitor) {
        reloadFromDisk()
        links[activityId]
    }

    override fun findByEventId(eventId: Long): CalendarEventLink? = synchronized(monitor) {
        reloadFromDisk()
        links.values.firstOrNull { it.eventId == eventId }
    }

    override fun findByProvenanceUri(provenanceUri: String): CalendarEventLink? = synchronized(monitor) {
        reloadFromDisk()
        links.values.firstOrNull { it.provenanceUri == provenanceUri }
    }

    private fun reloadFromDisk() {
        val prior = links.toMap()
        val loaded = linkedMapOf<String, CalendarEventLink>()
        quarantinedActivities.clear()
        CalendarWriteFileStorage.jsonRecords(directory).forEach { (name, raw) ->
            val expectedActivityId = name.removeSuffix(".json")
            val parsed = runCatching { raw.toLink() }.getOrNull()
            val link = parsed?.takeIf { it.activityId == expectedActivityId }
                ?: prior[expectedActivityId]
                ?: run {
                    quarantinedActivities += expectedActivityId
                    return@forEach
                }
            validateLinkIndexes(loaded.values, link)
            loaded[link.activityId] = link
        }
        links.clear()
        links.putAll(loaded)
    }

    private fun CalendarEventLink.toJson(): JSONObject = JSONObject()
        .put("schema_version", FORMAT_VERSION)
        .put("link_id", linkId)
        .put("activity_id", activityId)
        .put("plan_id", planId)
        .put("calendar_id", calendarId)
        .put("event_id", eventId ?: JSONObject.NULL)
        .put("provenance_uri", provenanceUri)
        .put("state", state.name)
        .put("content_hash", contentHash)
        .put("last_verified_at", lastVerifiedAt ?: JSONObject.NULL)
        .put("last_error", lastError ?: JSONObject.NULL)

    private fun String.toLink(): CalendarEventLink {
        val json = CalendarWriteFileStorage.decodeCurrent(this, FORMAT_VERSION, migration)
        return CalendarEventLink(
            linkId = json.getString("link_id"),
            activityId = json.getString("activity_id"),
            planId = json.getString("plan_id"),
            calendarId = json.getLong("calendar_id"),
            eventId = json.longOrNull("event_id"),
            provenanceUri = json.getString("provenance_uri"),
            state = CalendarEventLinkState.valueOf(json.getString("state")),
            contentHash = json.getString("content_hash"),
            lastVerifiedAt = json.longOrNull("last_verified_at"),
            lastError = json.stringOrNull("last_error"),
        )
    }

    private fun JSONObject.longOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)

    private fun JSONObject.stringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)

    private companion object {
        const val FORMAT_VERSION = 1
    }
}

private fun validateLinkIndexes(existing: Collection<CalendarEventLink>, candidate: CalendarEventLink) {
    val duplicateActivity = existing.firstOrNull {
        it.activityId == candidate.activityId && it.linkId != candidate.linkId
    }
    require(duplicateActivity == null) { "activity already has a different calendar link" }
    val duplicateEvent = candidate.eventId?.let { eventId ->
        existing.firstOrNull { it.eventId == eventId && it.activityId != candidate.activityId }
    }
    require(duplicateEvent == null) { "event already links to another activity" }
    val duplicateUri = existing.firstOrNull {
        it.provenanceUri == candidate.provenanceUri && it.activityId != candidate.activityId
    }
    require(duplicateUri == null) { "provenance URI already links to another activity" }
}
