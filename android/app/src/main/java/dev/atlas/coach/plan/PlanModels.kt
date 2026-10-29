package dev.atlas.coach.plan

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.net.URI
import java.util.UUID

enum class ActivityKind {
    FOCUS,
    TRAINING,
    APPOINTMENT,
    ADMIN,
    MEAL,
    REST,
    BUFFER,
    OTHER,
}

enum class TrainingReferenceSyncState {
    FRESH,
    STALE,
    UNAVAILABLE,
    MISMATCH,
}

data class TrainingPlanReference(
    val provider: String = "wger",
    val baseUrl: String,
    val routineId: Int,
    val dayId: Int? = null,
    val slotId: Int? = null,
    val planId: String,
    val activityId: String? = null,
    val fetchedAt: String,
    val sourceUpdatedAt: String? = null,
    val etag: String? = null,
    val syncState: TrainingReferenceSyncState,
) {
    init {
        require(provider == "wger") { "provider must be wger" }
        val url = try {
            URI(baseUrl)
        } catch (_: Exception) {
            throw IllegalArgumentException("baseUrl must be an HTTPS URL")
        }
        require(
            url.scheme == "https" && !url.host.isNullOrBlank() &&
                url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null,
        ) { "baseUrl must be an HTTPS URL without credentials or query" }
        require(routineId > 0) { "routineId must be positive" }
        dayId?.let { require(it > 0) { "dayId must be positive" } }
        slotId?.let { require(it > 0) { "slotId must be positive" } }
        requireCanonicalUuid(planId, "planId")
        activityId?.let { requireCanonicalUuid(it, "activityId") }
        requireTimestamp(fetchedAt, "fetchedAt")
        sourceUpdatedAt?.let { requireTimestamp(it, "sourceUpdatedAt") }
        etag?.let {
            require(it.length <= 256 && it.none { character -> character.code < 32 || character.code == 127 }) {
                "etag must be plain text up to 256 characters"
            }
        }
    }
}

enum class ActivityStatus {
    PLANNED,
    IN_PROGRESS,
    DONE,
    MISSED,
    CANCELLED,
    POSTPONED,
}

enum class StatusProvenance {
    DECLARED,
    MEASURED,
    CONFIRMED,
    UNKNOWN,
}

enum class StatusSource {
    USER_UI,
    FOCUS_TIMER,
    USAGE_STATS,
    WGER_READ,
    CORE_IMPORT,
}

data class StatusEventDraft(
    val provenance: StatusProvenance,
    val source: StatusSource,
    val consentVersion: Int = 0,
    val focusSessionId: String? = null,
) {
    init {
        require(consentVersion >= 0) { "consentVersion must not be negative" }
        focusSessionId?.let { requireCanonicalUuid(it, "focusSessionId") }
    }
}

data class PlanStatusEvent(
    val statusEventId: String,
    val planId: String,
    val activityId: String,
    val continuityId: String?,
    val fromStatus: ActivityStatus,
    val toStatus: ActivityStatus,
    val provenance: StatusProvenance,
    val source: StatusSource,
    val decidedAt: String,
    val observedAt: String,
    val planVersion: Int,
    val consentVersion: Int,
    val focusSessionId: String? = null,
) {
    init {
        requireCanonicalUuid(statusEventId, "statusEventId")
        requireCanonicalUuid(planId, "planId")
        requireCanonicalUuid(activityId, "activityId")
        continuityId?.let { requireCanonicalUuid(it, "continuityId") }
        requireTimestamp(decidedAt, "decidedAt")
        requireTimestamp(observedAt, "observedAt")
        require(planVersion >= 1) { "planVersion must be >= 1" }
        require(consentVersion >= 0) { "consentVersion must not be negative" }
        focusSessionId?.let { requireCanonicalUuid(it, "focusSessionId") }
    }
}

enum class Priority {
    REQUIRED,
    IMPORTANT,
    OPTIONAL,
}

enum class PlanState {
    DRAFT,
    APPROVED,
    SUPERSEDED,
    ARCHIVED,
}

enum class PlanOrigin {
    LOCAL,
    AGENT,
    MIGRATED,
    DEMO,
}

enum class RevisionOrigin {
    USER,
    AGENT,
    RECONCILIATION,
}

enum class RevisionStatus {
    PROPOSED,
    APPROVED,
    APPLIED,
    REJECTED,
    EXPIRED,
    SUPERSEDED,
}

enum class PlanRejectionReason {
    PLAN_ALREADY_EXISTS,
    PLAN_NOT_FOUND,
    PLAN_ID_MISMATCH,
    VERSION_CONFLICT,
    NON_MONOTONE_VERSION,
    STALE_REVISION,
    REVISION_EXPIRED,
    REVISION_NOT_APPLICABLE,
    INVALID_REVISION,
    EMPTY_CHANGESET,
    INVALID_CHANGES,
    DONE_ACTIVITY_REMOVAL,
    INVALID_CONFIRMATION,
    EFFECT_SCOPE_MISMATCH,
    CONFIRMATION_EXPIRED,
    CONFIRMATION_ALREADY_CONSUMED,
    REVISION_ID_CONFLICT,
    CORRUPT_STATE,
    STORAGE_FAILURE,
}

data class LocalSchedule(
    val localDate: String,
    val startMinute: Int,
    val durationMinutes: Int,
    val timezone: String,
) {
    init {
        requireCanonicalDate(localDate)
        require(startMinute in 0..1439) { "startMinute must be in 0..1439" }
        require(durationMinutes in 1..1440) { "durationMinutes must be in 1..1440" }
        requireIanaTimezone(timezone)
    }
}

data class PlanActivity(
    val activityId: String,
    val planId: String,
    val kind: ActivityKind,
    val title: String,
    val status: ActivityStatus,
    val scheduledLocal: LocalSchedule,
    val priority: Priority,
    val goalId: String? = null,
    val subGoalId: String? = null,
    val estimateMinutes: Int? = null,
    val actualMinutes: Int? = null,
    val createdRevisionId: String,
    val lastRevisionId: String,
    val notes: String? = null,
    val trainingRef: TrainingPlanReference? = null,
    val continuityId: String? = null,
    val supersedesActivityId: String? = null,
) {
    init {
        requireCanonicalUuid(activityId, "activityId")
        requireCanonicalUuid(planId, "planId")
        require(title.isNotBlank() && title.length <= 80) { "title must contain 1..80 characters" }
        goalId?.let { requireCanonicalUuid(it, "goalId") }
        subGoalId?.let { requireCanonicalUuid(it, "subGoalId") }
        estimateMinutes?.let { require(it > 0) { "estimateMinutes must be positive" } }
        actualMinutes?.let { require(it >= 0) { "actualMinutes must not be negative" } }
        requireCanonicalUuid(createdRevisionId, "createdRevisionId")
        requireCanonicalUuid(lastRevisionId, "lastRevisionId")
        notes?.let { require(it.length <= 280) { "notes must be at most 280 characters" } }
        continuityId?.let { requireCanonicalUuid(it, "continuityId") }
        supersedesActivityId?.let {
            requireCanonicalUuid(it, "supersedesActivityId")
            require(it != activityId) { "an activity cannot supersede itself" }
            require(continuityId != null) { "a superseding activity must carry a continuityId" }
        }
        trainingRef?.let { reference ->
            require(reference.planId == planId) { "trainingRef belongs to a different plan" }
            require(reference.activityId == null || reference.activityId == activityId) {
                "trainingRef belongs to a different activity"
            }
        }
    }
}

data class ReportActivityRequest(
    val planId: String,
    val activityId: String,
    val baseVersion: Int,
    val targetLocalDate: String,
    val targetStartMinute: Int,
    val targetDurationMinutes: Int,
    val reason: String,
    val idempotencyKey: String,
    val consentVersion: Int = 0,
) {
    init {
        requireCanonicalUuid(planId, "planId")
        requireCanonicalUuid(activityId, "activityId")
        requireCanonicalUuid(idempotencyKey, "idempotencyKey")
        require(baseVersion >= 1) { "baseVersion must be >= 1" }
        requireCanonicalDate(targetLocalDate)
        require(targetStartMinute in 0..1439) { "targetStartMinute must be in 0..1439" }
        require(targetDurationMinutes in 1..1440) { "targetDurationMinutes must be in 1..1440" }
        require(targetStartMinute + targetDurationMinutes <= 1440) { "target slot must remain in the day" }
        require(reason.isNotBlank() && reason.length <= 280) { "reason must contain 1..280 characters" }
        require(consentVersion >= 0) { "consentVersion must not be negative" }
    }
}

private fun requireTimestamp(value: String, field: String) {
    require(value.isNotBlank() && value.length <= 40) { "$field must be an ISO-8601 timestamp" }
    try {
        OffsetDateTime.parse(value)
    } catch (_: Exception) {
        throw IllegalArgumentException("$field must include a timezone offset")
    }
}

data class DayPlan(
    val planId: String,
    val localDate: String,
    val timezone: String,
    val version: Int,
    val currentRevisionId: String,
    val state: PlanState,
    val activities: List<PlanActivity>,
    val createdAt: Long,
    val updatedAt: Long,
    val approvedAt: Long? = null,
    val origin: PlanOrigin,
) {
    init {
        requireCanonicalUuid(planId, "planId")
        requireCanonicalDate(localDate)
        requireIanaTimezone(timezone)
        require(version >= 1) { "version must be >= 1" }
        requireCanonicalUuid(currentRevisionId, "currentRevisionId")
        require(createdAt >= 0L && updatedAt >= createdAt) { "invalid plan timestamps" }
        approvedAt?.let { require(it >= createdAt) { "approvedAt must not precede createdAt" } }
        val ids = mutableSetOf<String>()
        activities.forEach { activity ->
            require(activity.planId == planId) { "activity belongs to a different plan" }
            require(activity.scheduledLocal.localDate == localDate) { "activity date differs from plan date" }
            require(activity.scheduledLocal.timezone == timezone) { "activity timezone differs from plan timezone" }
            require(ids.add(activity.activityId)) { "duplicate activityId" }
        }
    }
}

sealed interface PlanChange {
    data class SlotAdded(val activity: PlanActivity) : PlanChange

    data class SlotRemoved(
        val activityId: String,
        val before: PlanActivity,
    ) : PlanChange

    data class SlotMoved(
        val activityId: String,
        val from: LocalSchedule,
        val to: LocalSchedule,
    ) : PlanChange

    data class SlotEdited(
        val activityId: String,
        val before: PlanActivity,
        val after: PlanActivity,
    ) : PlanChange

    data class StatusChanged(
        val activityId: String,
        val from: ActivityStatus,
        val to: ActivityStatus,
    ) : PlanChange
}

data class PlanRevision(
    val revisionId: String,
    val planId: String,
    val baseVersion: Int,
    val newVersion: Int,
    val origin: RevisionOrigin,
    val changes: List<PlanChange>,
    val summary: String,
    val createdAt: Long,
    val expiresAt: Long,
    val status: RevisionStatus,
) {
    init {
        requireCanonicalUuid(revisionId, "revisionId")
        requireCanonicalUuid(planId, "planId")
        require(baseVersion >= 1) { "baseVersion must be >= 1" }
        require(newVersion == baseVersion + 1) { "newVersion must equal baseVersion + 1" }
        require(changes.isNotEmpty()) { "revision must contain at least one change" }
        changes.forEach { change ->
            when (change) {
                is PlanChange.SlotAdded -> require(change.activity.planId == planId) {
                    "added activity belongs to a different plan"
                }
                is PlanChange.SlotRemoved -> require(
                    change.activityId == change.before.activityId && change.before.planId == planId,
                ) { "removed activity identity does not match" }
                is PlanChange.SlotMoved -> requireCanonicalUuid(change.activityId, "activityId")
                is PlanChange.SlotEdited -> require(
                    change.activityId == change.before.activityId &&
                        change.activityId == change.after.activityId &&
                        change.before.planId == planId && change.after.planId == planId,
                ) { "edited activity identity does not match" }
                is PlanChange.StatusChanged -> requireCanonicalUuid(change.activityId, "activityId")
            }
        }
        require(summary.isNotBlank() && summary.length <= 240) { "summary must contain 1..240 characters" }
        require(createdAt >= 0L && expiresAt > createdAt) { "invalid revision timestamps" }
    }
}

data class PlanConfirmation(
    val confirmationId: String,
    val revisionId: String,
    val effectIds: List<String>,
    val scopeHash: String,
    val approvedAt: Long,
    val expiresAt: Long,
    val consumedAt: Long? = null,
) {
    init {
        requireCanonicalUuid(confirmationId, "confirmationId")
        requireCanonicalUuid(revisionId, "revisionId")
        require(effectIds.isNotEmpty()) { "effectIds must not be empty" }
        require(effectIds.all { it.isNotBlank() }) { "effectIds must not contain blank values" }
        require(effectIds.toSet().size == effectIds.size) { "effectIds must be unique" }
        require(SHA256_PATTERN.matches(scopeHash)) { "scopeHash must be a lowercase SHA-256 hex string" }
        require(approvedAt >= 0L && expiresAt > approvedAt) { "invalid confirmation timestamps" }
        consumedAt?.let { require(it >= approvedAt) { "consumedAt must not precede approvedAt" } }
    }
}

internal fun requireCanonicalUuid(value: String, field: String) {
    val canonical = try {
        UUID.fromString(value).toString()
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("$field must be a canonical UUID")
    }
    require(value == canonical) { "$field must be a canonical UUID" }
}

internal fun requireCanonicalDate(value: String) {
    val parsed = try {
        LocalDate.parse(value)
    } catch (_: Exception) {
        throw IllegalArgumentException("localDate must be an ISO-8601 date")
    }
    require(parsed.toString() == value) { "localDate must be a canonical ISO-8601 date" }
}

internal fun requireIanaTimezone(value: String) {
    require(value in ZoneId.getAvailableZoneIds()) { "timezone must be an IANA timezone" }
    require(ZoneId.of(value).id == value) { "timezone must use its canonical IANA identifier" }
}

internal const val SHA256_PATTERN_TEXT = "[0-9a-f]{64}"
internal val SHA256_PATTERN = Regex(SHA256_PATTERN_TEXT)
