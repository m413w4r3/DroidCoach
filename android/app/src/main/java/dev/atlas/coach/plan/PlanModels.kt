package dev.atlas.coach.plan

import java.time.LocalDate
import java.time.ZoneId
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

enum class ActivityStatus {
    PLANNED,
    IN_PROGRESS,
    DONE,
    MISSED,
    CANCELLED,
    POSTPONED,
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
