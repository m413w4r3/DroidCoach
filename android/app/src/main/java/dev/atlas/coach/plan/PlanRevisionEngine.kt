package dev.atlas.coach.plan

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.zone.ZoneRules

data class OverlapWarning(
    val firstActivityId: String,
    val secondActivityId: String,
    val overlapStart: Instant,
    val overlapEnd: Instant,
)

sealed interface ChangeApplicationResult {
    data class Applied(val activities: List<PlanActivity>) : ChangeApplicationResult
    data class Rejected(val reason: PlanRejectionReason) : ChangeApplicationResult
}

/** Deterministic plan diffing, effect scope calculation and explicit-zone time conversion. */
class PlanRevisionEngine {
    fun calculateChanges(current: DayPlan, proposedActivities: List<PlanActivity>): List<PlanChange> {
        validateActivitySet(current, proposedActivities)
        val oldById = current.activities.associateBy { it.activityId }
        val newById = proposedActivities.associateBy { it.activityId }
        val ids = (oldById.keys + newById.keys).sorted()
        return buildList {
            ids.forEach { activityId ->
                val before = oldById[activityId]
                val after = newById[activityId]
                when {
                    before == null && after != null -> add(PlanChange.SlotAdded(after))
                    before != null && after == null -> add(PlanChange.SlotRemoved(activityId, before))
                    before != null && after != null -> {
                        if (before.scheduledLocal != after.scheduledLocal) {
                            add(PlanChange.SlotMoved(activityId, before.scheduledLocal, after.scheduledLocal))
                        }
                        if (!sameDefinition(before, after)) {
                            add(PlanChange.SlotEdited(activityId, before, after))
                        }
                        if (before.status != after.status) {
                            add(PlanChange.StatusChanged(activityId, before.status, after.status))
                        }
                    }
                }
            }
        }
    }

    fun createRevision(
        current: DayPlan,
        proposedActivities: List<PlanActivity>,
        revisionId: String,
        origin: RevisionOrigin,
        summary: String,
        createdAt: Long,
        ttlMillis: Long = DEFAULT_REVISION_TTL_MILLIS,
    ): PlanRevision {
        requireCanonicalUuid(revisionId, "revisionId")
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
        val newVersion = Math.addExact(current.version, 1)
        val oldById = current.activities.associateBy { it.activityId }
        validateActivitySet(current, proposedActivities)
        val normalized = proposedActivities.map { candidate ->
            val old = oldById[candidate.activityId]
            when {
                old == null -> candidate.copy(createdRevisionId = revisionId, lastRevisionId = revisionId)
                old == candidate.copy(createdRevisionId = old.createdRevisionId, lastRevisionId = old.lastRevisionId) -> old
                else -> candidate.copy(createdRevisionId = old.createdRevisionId, lastRevisionId = revisionId)
            }
        }
        val changes = calculateChanges(current, normalized)
        require(changes.isNotEmpty()) { "a revision must change the plan" }
        return PlanRevision(
            revisionId = revisionId,
            planId = current.planId,
            baseVersion = current.version,
            newVersion = newVersion,
            origin = origin,
            changes = changes,
            summary = summary,
            createdAt = createdAt,
            expiresAt = Math.addExact(createdAt, ttlMillis),
            status = RevisionStatus.PROPOSED,
        )
    }

    fun effectIds(changes: List<PlanChange>, revisionId: String): List<String> {
        requireCanonicalUuid(revisionId, "revisionId")
        val effects = sortedSetOf<String>()
        changes.forEach { change ->
            when (change) {
                is PlanChange.SlotAdded -> effects += "PLAN_ACTIVITY_CREATE:${change.activity.activityId}"
                is PlanChange.SlotRemoved -> effects += "PLAN_ACTIVITY_DELETE:${change.activityId}"
                is PlanChange.SlotMoved,
                is PlanChange.SlotEdited,
                is PlanChange.StatusChanged,
                -> effects += "PLAN_ACTIVITY_UPDATE:${changeActivityId(change)}"
            }
        }
        effects += "PLAN_APPLY:$revisionId"
        return effects.toList()
    }

    /**
     * Binds a confirmation to the exact reviewed diff, not only to generic effect
     * identifiers. Altering the before/after content while keeping the same
     * revision id and effect ids therefore changes the hash and is rejected.
     */
    fun scopeHash(effectIds: List<String>, changes: List<PlanChange> = emptyList()): String {
        require(effectIds.isNotEmpty()) { "effectIds must not be empty" }
        require(effectIds.toSet().size == effectIds.size) { "effectIds must be unique" }
        val canonical = buildString {
            append(effectIds.sorted().joinToString(separator = "\n"))
            if (changes.isNotEmpty()) {
                append("\n--CHANGES--\n")
                append(changes.map(::canonicalChange).sorted().joinToString(separator = "\n"))
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun canonicalChange(change: PlanChange): String = when (change) {
        is PlanChange.SlotAdded -> "ADD|${canonicalActivity(change.activity)}"
        is PlanChange.SlotRemoved -> "REMOVE|${canonicalActivity(change.before)}"
        is PlanChange.SlotMoved -> "MOVE|${change.activityId}|${canonicalSchedule(change.from)}|${canonicalSchedule(change.to)}"
        is PlanChange.SlotEdited -> "EDIT|${canonicalActivity(change.before)}|${canonicalActivity(change.after)}"
        is PlanChange.StatusChanged -> "STATUS|${change.activityId}|${change.from.name}|${change.to.name}"
    }

    private fun canonicalActivity(activity: PlanActivity): String = listOf(
        activity.activityId,
        activity.planId,
        activity.kind.name,
        activity.title,
        activity.status.name,
        canonicalSchedule(activity.scheduledLocal),
        activity.priority.name,
        activity.goalId,
        activity.subGoalId,
        activity.estimateMinutes?.toString(),
        activity.actualMinutes?.toString(),
        activity.createdRevisionId,
        activity.lastRevisionId,
        activity.notes,
    ).joinToString(separator = "|") { encodeField(it) }

    private fun canonicalSchedule(schedule: LocalSchedule): String = listOf(
        schedule.localDate,
        schedule.startMinute.toString(),
        schedule.durationMinutes.toString(),
        schedule.timezone,
    ).joinToString(separator = "|") { encodeField(it) }

    /**
     * Length-prefixed encoding: null is "N", any string is "S<length>:<value>".
     * A literal "<null>" is therefore distinct from a null field, and embedded
     * separators cannot make two different field sequences collide.
     */
    private fun encodeField(value: String?): String =
        if (value == null) "N" else "S${value.length}:$value"

    /** Applies a reviewed diff only when every before-value still matches the current plan. */
    fun applyChanges(current: DayPlan, revision: PlanRevision): ChangeApplicationResult {
        if (revision.planId != current.planId) return ChangeApplicationResult.Rejected(PlanRejectionReason.PLAN_ID_MISMATCH)
        if (revision.baseVersion != current.version || revision.newVersion != current.version + 1) {
            return ChangeApplicationResult.Rejected(PlanRejectionReason.STALE_REVISION)
        }
        if (revision.changes.isEmpty()) return ChangeApplicationResult.Rejected(PlanRejectionReason.EMPTY_CHANGESET)

        val byId = current.activities.associateBy { it.activityId }.toMutableMap()
        val grouped = revision.changes.groupBy(::changeActivityId)
        for ((activityId, changes) in grouped) {
            if (changes.map(::changeCategory).toSet().size != changes.size) {
                return ChangeApplicationResult.Rejected(PlanRejectionReason.INVALID_CHANGES)
            }
            val addition = changes.filterIsInstance<PlanChange.SlotAdded>().singleOrNull()
            val removal = changes.filterIsInstance<PlanChange.SlotRemoved>().singleOrNull()
            if (addition != null || removal != null) {
                if (changes.size != 1) return ChangeApplicationResult.Rejected(PlanRejectionReason.INVALID_CHANGES)
                if (addition != null) {
                    val activity = addition.activity
                    if (activity.activityId != activityId || activity.planId != current.planId || byId.containsKey(activityId)) {
                        return ChangeApplicationResult.Rejected(PlanRejectionReason.INVALID_CHANGES)
                    }
                    if (!activityFitsPlan(current, activity)) return ChangeApplicationResult.Rejected(PlanRejectionReason.INVALID_CHANGES)
                    byId[activityId] = activity.copy(
                        createdRevisionId = revision.revisionId,
                        lastRevisionId = revision.revisionId,
                    )
                } else {
                    val before = removal!!.before
                    val existing = byId[activityId]
                    if (before.activityId != activityId || existing == null || before != existing) {
                        return ChangeApplicationResult.Rejected(PlanRejectionReason.INVALID_CHANGES)
                    }
                    if (existing.status == ActivityStatus.DONE) {
                        return ChangeApplicationResult.Rejected(PlanRejectionReason.DONE_ACTIVITY_REMOVAL)
                    }
                    byId.remove(activityId)
                }
                continue
            }

            val existing = byId[activityId]
                ?: return ChangeApplicationResult.Rejected(PlanRejectionReason.INVALID_CHANGES)
            val move = changes.filterIsInstance<PlanChange.SlotMoved>().singleOrNull()
            val edit = changes.filterIsInstance<PlanChange.SlotEdited>().singleOrNull()
            val status = changes.filterIsInstance<PlanChange.StatusChanged>().singleOrNull()
            if (changes.any { it !is PlanChange.SlotMoved && it !is PlanChange.SlotEdited && it !is PlanChange.StatusChanged }) {
                return ChangeApplicationResult.Rejected(PlanRejectionReason.INVALID_CHANGES)
            }
            if (move != null && (move.activityId != activityId || move.from != existing.scheduledLocal || move.to == move.from)) {
                return ChangeApplicationResult.Rejected(PlanRejectionReason.INVALID_CHANGES)
            }
            if (edit != null && (
                    edit.activityId != activityId ||
                        edit.before != existing ||
                        edit.after.activityId != activityId ||
                        edit.after.planId != current.planId ||
                        edit.after.createdRevisionId != existing.createdRevisionId ||
                        sameDefinition(edit.before, edit.after)
                    )
            ) {
                return ChangeApplicationResult.Rejected(PlanRejectionReason.INVALID_CHANGES)
            }
            if (status != null && (
                    status.activityId != activityId ||
                        status.from != existing.status ||
                        status.to == status.from ||
                        PlanStatusPolicy.structuralRejection(status.from, status.to) != null
                    )
            ) {
                return ChangeApplicationResult.Rejected(PlanRejectionReason.INVALID_CHANGES)
            }
            val targetSchedule = move?.to ?: existing.scheduledLocal
            val targetStatus = status?.to ?: existing.status
            if (edit != null && (edit.after.scheduledLocal != targetSchedule || edit.after.status != targetStatus)) {
                return ChangeApplicationResult.Rejected(PlanRejectionReason.INVALID_CHANGES)
            }
            val updated = existing.copy(
                title = edit?.after?.title ?: existing.title,
                kind = edit?.after?.kind ?: existing.kind,
                priority = edit?.after?.priority ?: existing.priority,
                goalId = edit?.after?.goalId ?: if (edit != null) edit.after.goalId else existing.goalId,
                subGoalId = edit?.after?.subGoalId ?: if (edit != null) edit.after.subGoalId else existing.subGoalId,
                estimateMinutes = edit?.after?.estimateMinutes ?: if (edit != null) edit.after.estimateMinutes else existing.estimateMinutes,
                actualMinutes = edit?.after?.actualMinutes ?: if (edit != null) edit.after.actualMinutes else existing.actualMinutes,
                notes = edit?.after?.notes ?: if (edit != null) edit.after.notes else existing.notes,
                scheduledLocal = move?.to ?: existing.scheduledLocal,
                status = status?.to ?: existing.status,
                lastRevisionId = revision.revisionId,
            )
            if (!activityFitsPlan(current, updated)) return ChangeApplicationResult.Rejected(PlanRejectionReason.INVALID_CHANGES)
            byId[activityId] = updated
        }
        return ChangeApplicationResult.Applied(byId.values.sortedBy { it.activityId })
    }

    fun overlapWarnings(activities: List<PlanActivity>): List<OverlapWarning> {
        val considered = activities
            .filter { it.status == ActivityStatus.PLANNED || it.status == ActivityStatus.IN_PROGRESS || it.status == ActivityStatus.POSTPONED }
            .sortedBy { it.activityId }
        val warnings = mutableListOf<OverlapWarning>()
        for (leftIndex in considered.indices) {
            val left = considered[leftIndex]
            val leftStart = localToInstant(left.scheduledLocal)
            val leftEnd = leftStart.plusSeconds(left.scheduledLocal.durationMinutes * 60L)
            for (rightIndex in leftIndex + 1 until considered.size) {
                val right = considered[rightIndex]
                val rightStart = localToInstant(right.scheduledLocal)
                val rightEnd = rightStart.plusSeconds(right.scheduledLocal.durationMinutes * 60L)
                val start = maxOf(leftStart, rightStart)
                val end = minOf(leftEnd, rightEnd)
                if (start < end) {
                    warnings += OverlapWarning(left.activityId, right.activityId, start, end)
                }
            }
        }
        return warnings
    }

    /** A local time in a spring DST gap maps to the transition's first valid local time. */
    fun localToInstant(schedule: LocalSchedule): Instant {
        val date = LocalDate.parse(schedule.localDate)
        val localDateTime = date.atStartOfDay().plusMinutes(schedule.startMinute.toLong())
        val zone = ZoneId.of(schedule.timezone)
        return resolveLocalDateTime(localDateTime, zone.rules).toInstant()
    }

    /** An autumn overlap chooses the earlier offset returned by ZoneRules. */
    fun resolveLocalDateTime(localDateTime: LocalDateTime, rules: ZoneRules): java.time.ZonedDateTime {
        val offsets = rules.getValidOffsets(localDateTime)
        return when (offsets.size) {
            1 -> localDateTime.atOffset(offsets.single()).toZonedDateTime()
            0 -> {
                val transition = rules.getTransition(localDateTime)
                    ?: throw IllegalArgumentException("missing zone transition for local time gap")
                transition.dateTimeAfter.atOffset(transition.offsetAfter).toZonedDateTime()
            }
            else -> localDateTime.atOffset(offsets.first()).toZonedDateTime()
        }
    }

    fun instantToSchedule(instant: Instant, durationMinutes: Int, timezone: String): LocalSchedule {
        require(durationMinutes in 1..1440)
        requireIanaTimezone(timezone)
        val local = instant.atZone(ZoneId.of(timezone))
        require(local.second == 0 && local.nano == 0) { "instant must have minute precision" }
        return LocalSchedule(
            localDate = local.toLocalDate().toString(),
            startMinute = local.hour * 60 + local.minute,
            durationMinutes = durationMinutes,
            timezone = timezone,
        )
    }

    private fun validateActivitySet(plan: DayPlan, activities: List<PlanActivity>) {
        require(activities.map { it.activityId }.toSet().size == activities.size) { "duplicate activityId" }
        require(activities.all { activityFitsPlan(plan, it) }) { "activity does not match plan date, timezone or id" }
    }

    private fun activityFitsPlan(plan: DayPlan, activity: PlanActivity): Boolean =
        activity.planId == plan.planId &&
            activity.scheduledLocal.localDate == plan.localDate &&
            activity.scheduledLocal.timezone == plan.timezone

    private fun sameDefinition(left: PlanActivity, right: PlanActivity): Boolean =
        left.copy(
            scheduledLocal = right.scheduledLocal,
            status = right.status,
            createdRevisionId = right.createdRevisionId,
            lastRevisionId = right.lastRevisionId,
        ) == right

    private fun changeActivityId(change: PlanChange): String = when (change) {
        is PlanChange.SlotAdded -> change.activity.activityId
        is PlanChange.SlotRemoved -> change.activityId
        is PlanChange.SlotMoved -> change.activityId
        is PlanChange.SlotEdited -> change.activityId
        is PlanChange.StatusChanged -> change.activityId
    }

    private fun changeCategory(change: PlanChange): String = when (change) {
        is PlanChange.SlotAdded -> "add"
        is PlanChange.SlotRemoved -> "remove"
        is PlanChange.SlotMoved -> "move"
        is PlanChange.SlotEdited -> "edit"
        is PlanChange.StatusChanged -> "status"
    }

    companion object {
        const val DEFAULT_REVISION_TTL_MILLIS: Long = 24 * 60 * 60 * 1000L
    }
}
