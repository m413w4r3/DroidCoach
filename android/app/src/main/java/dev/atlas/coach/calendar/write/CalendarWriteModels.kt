package dev.atlas.coach.calendar.write

import dev.atlas.coach.calendar.CalendarDescriptor
import dev.atlas.coach.calendar.CalendarPermissionState
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.ZoneId
import java.util.UUID

enum class CalendarWriteOperationType {
    CREATE,
    UPDATE,
    DELETE,
}

enum class CalendarWriteOperationState {
    PREPARED,
    APPLIED,
    NOT_APPLIED,
    UNKNOWN,
    RECONCILED,
    ABANDONED,
}

enum class CalendarEventLinkState {
    PENDING,
    CONFIRMED,
    MISSING,
    DIVERGED,
    ORPHANED,
    UNKNOWN,
}

enum class CalendarWriteError {
    PERMISSION_DENIED,
    CALENDAR_NOT_LOCAL,
    PROVENANCE_AMBIGUOUS,
    DUPLICATE_URI,
    RECURRING_NOT_SUPPORTED,
    READ_BACK_MISMATCH,
    UNKNOWN_RESULT,
    FOREIGN_EVENT,
    MISSING_EVENT,
    PROVIDER_UNAVAILABLE,
    INVALID_WINDOW,
    IDEMPOTENCY_CONFLICT,
    CALENDAR_NOT_SELECTED,
    WRONG_CALENDAR,
    INVALID_LINK,
}

enum class CalendarWriteMarker {
    OWNED,
    FOREIGN,
    AMBIGUOUS,
}

data class CalendarWritePermissions(
    val read: CalendarPermissionState,
    val write: CalendarPermissionState,
) {
    val granted: Boolean
        get() = read == CalendarPermissionState.GRANTED && write == CalendarPermissionState.GRANTED
}

data class CalendarWriteCatalog(
    val permissions: CalendarWritePermissions,
    val calendars: List<CalendarDescriptor>,
    val providerAvailable: Boolean,
)

data class CalendarEventDraft(
    val activityId: String,
    val planId: String,
    val title: String,
    val dtstartMillis: Long,
    val dtendMillis: Long,
    val timezone: String,
    val allDay: Boolean = false,
) {
    init {
        requireCanonicalUuid(activityId, "activityId")
        requireCanonicalUuid(planId, "planId")
        require(title.isNotBlank() && title.length <= 80) { "title must contain 1..80 characters" }
        require(dtstartMillis < dtendMillis) { "event window must have a positive duration" }
        require(timezone in ZoneId.getAvailableZoneIds() && ZoneId.of(timezone).id == timezone) {
            "timezone must be a canonical IANA identifier"
        }
        require(!allDay) { "all-day events are not supported by calendar write" }
    }

    val provenanceUri: String
        get() = provenanceUri(planId, activityId)

    fun contentHash(calendarId: Long): String = sha256(
        canonical(
            calendarId.toString(),
            dtstartMillis.toString(),
            dtendMillis.toString(),
            timezone,
            title,
            allDay.toString(),
            "false", // deleted
        ),
    )
}

/** V6.3 §3.4 link; lastError contains a stable code only, never provider text. */
data class CalendarEventLink(
    val linkId: String,
    val activityId: String,
    val planId: String,
    val calendarId: Long,
    val eventId: Long?,
    val provenanceUri: String,
    val state: CalendarEventLinkState,
    val contentHash: String,
    val lastVerifiedAt: Long? = null,
    val lastError: String? = null,
) {
    init {
        requireCanonicalUuid(linkId, "linkId")
        requireCanonicalUuid(activityId, "activityId")
        requireCanonicalUuid(planId, "planId")
        require(calendarId >= 0L) { "calendarId must be non-negative" }
        require(eventId == null || eventId >= 0L) { "eventId must be non-negative" }
        require(provenanceUri == provenanceUri(planId, activityId)) { "provenanceUri must identify this activity" }
        require(SHA_256.matches(contentHash)) { "contentHash must be lowercase SHA-256" }
        require(lastVerifiedAt == null || lastVerifiedAt >= 0L) { "lastVerifiedAt must be non-negative" }
        require(lastError == null || lastError.length <= 120) { "lastError must be at most 120 characters" }
    }
}

/** Durable operation record. It deliberately contains no event title or account data. */
data class CalendarWriteOperation(
    val operationId: String,
    val type: CalendarWriteOperationType,
    val activityId: String,
    val planId: String,
    val calendarId: Long,
    val eventId: Long?,
    val payloadHash: String,
    val idempotencyKey: String,
    val state: CalendarWriteOperationState,
    val errorCode: CalendarWriteError? = null,
) {
    init {
        requireCanonicalUuid(operationId, "operationId")
        requireCanonicalUuid(activityId, "activityId")
        requireCanonicalUuid(planId, "planId")
        require(calendarId >= 0L) { "calendarId must be non-negative" }
        require(eventId == null || eventId >= 0L) { "eventId must be non-negative" }
        require(SHA_256.matches(payloadHash)) { "payloadHash must be lowercase SHA-256" }
        require(IDEMPOTENCY_KEY.matches(idempotencyKey)) { "idempotencyKey must be an opaque identifier" }
    }
}

/** Event projection used by the write domain; marker fields stay local to this package. */
data class CalendarWriteEvent(
    val eventId: Long,
    val calendarId: Long,
    val title: String?,
    val dtstartMillis: Long,
    val dtendMillis: Long,
    val timezone: String?,
    val allDay: Boolean,
    val deleted: Boolean,
    val rrule: String?,
    val customAppPackage: String?,
    val customAppUri: String?,
    val markerMetadataAvailable: Boolean = true,
)

data class CalendarSelectionResult(
    val calendar: CalendarDescriptor? = null,
    val error: CalendarWriteError? = null,
) {
    init {
        require((calendar == null) xor (error == null))
    }
}

data class CalendarWriteResult(
    val operation: CalendarWriteOperation?,
    val link: CalendarEventLink?,
    val error: CalendarWriteError? = null,
) {
    val successful: Boolean
        get() = error == null && (
            link?.state == CalendarEventLinkState.CONFIRMED ||
                (operation?.type == CalendarWriteOperationType.DELETE && link?.state == CalendarEventLinkState.MISSING)
            )
}

fun provenanceUri(planId: String, activityId: String): String =
    "atlas://plan/$planId/activity/$activityId"

internal const val ATLAS_WRITE_PACKAGE = "dev.atlas.coach"
internal const val LOCAL_ACCOUNT_TYPE = "LOCAL"
internal val SHA_256 = Regex("[0-9a-f]{64}")
private val IDEMPOTENCY_KEY = Regex("[A-Za-z0-9._:-]{1,160}")

internal fun markerFor(event: CalendarWriteEvent, expectedUri: String): CalendarWriteMarker = when {
    !event.markerMetadataAvailable -> CalendarWriteMarker.AMBIGUOUS
    event.customAppPackage == null && event.customAppUri == null -> CalendarWriteMarker.AMBIGUOUS
    event.customAppPackage == ATLAS_WRITE_PACKAGE && event.customAppUri == expectedUri ->
        CalendarWriteMarker.OWNED
    event.customAppPackage != null && event.customAppUri != null -> CalendarWriteMarker.FOREIGN
    else -> CalendarWriteMarker.AMBIGUOUS
}

internal fun eventContentHash(event: CalendarWriteEvent): String = sha256(
    canonical(
        event.calendarId.toString(),
        event.dtstartMillis.toString(),
        event.dtendMillis.toString(),
        event.timezone.orEmpty(),
        event.title.orEmpty(),
        event.allDay.toString(),
        event.deleted.toString(),
    ),
)

private fun canonical(vararg fields: String): String = fields.joinToString(separator = "") { field ->
    "${field.toByteArray(StandardCharsets.UTF_8).size}:$field"
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun requireCanonicalUuid(value: String, field: String) {
    val canonical = try {
        UUID.fromString(value).toString()
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("$field must be a canonical UUID")
    }
    require(value == canonical) { "$field must be a canonical UUID" }
}
