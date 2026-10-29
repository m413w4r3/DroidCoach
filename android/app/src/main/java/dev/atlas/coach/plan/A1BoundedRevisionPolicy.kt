package dev.atlas.coach.plan

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/** Explicit user assigned A1 class. Missing legacy policy is always treated as PROTECTED. */
enum class A1ActivityClass {
    READING,
    CHINESE,
    LEISURE,
    PERSONAL,
    PROTECTED,
}

/** Unknown linkage is fail closed; only an explicit NONE value is movable by A1. */
enum class A1ExternalLinkState {
    NONE,
    LINKED,
    UNKNOWN,
}

data class A1ActivityPolicy(
    val activityId: String,
    val activityClass: A1ActivityClass,
    val unlocked: Boolean,
    val externalLinkState: A1ExternalLinkState = A1ExternalLinkState.UNKNOWN,
) {
    init {
        requireCanonicalUuid(activityId, "activityId")
        require(activityClass != A1ActivityClass.PROTECTED || !unlocked) {
            "a protected activity cannot be unlocked"
        }
    }

    companion object {
        fun legacyProtected(activityId: String) = A1ActivityPolicy(
            activityId = activityId,
            activityClass = A1ActivityClass.PROTECTED,
            unlocked = false,
            externalLinkState = A1ExternalLinkState.UNKNOWN,
        )
    }
}

/** A1's consent is independent from plan sharing, coach sharing, and agent mandate. */
data class A1Consent(
    val version: Int = 0,
    val enabled: Boolean = false,
    val updatedAtUtcMillis: Long = 0L,
) {
    init {
        require(version >= 0) { "A1 consent version must not be negative" }
        require(updatedAtUtcMillis >= 0L) { "A1 consent timestamp must not be negative" }
        require(version != 0 || !enabled) { "A1 cannot be enabled without an explicit consent version" }
    }
}

data class A1MoveDiff(
    val activityId: String,
    val from: LocalSchedule,
    val to: LocalSchedule,
) {
    init {
        requireCanonicalUuid(activityId, "activityId")
        require(from != to) { "A1 move diff must change the schedule" }
        require(from.localDate == to.localDate && from.timezone == to.timezone) {
            "A1 move diff cannot change date or timezone"
        }
        require(from.durationMinutes == to.durationMinutes) { "A1 move diff cannot change duration" }
        require(kotlin.math.abs(to.startMinute - from.startMinute) in 1..A1BoundedRevisionPolicy.MAX_MOVE_MINUTES) {
            "A1 move diff exceeds its movement bound"
        }
    }
}

/** Complete proposal input. The supplied payload contains activities, never a trusted diff. */
data class A1RevisionProposal(
    val proposalId: String,
    val revisionId: String,
    val planId: String,
    val baseVersion: Int,
    val createdAtUtcMillis: Long,
    val expiresAtUtcMillis: Long,
    val consentVersion: Int,
    val proposedActivities: List<PlanActivity>,
) {
    init {
        requireCanonicalUuid(proposalId, "proposalId")
        requireCanonicalUuid(revisionId, "revisionId")
        requireCanonicalUuid(planId, "planId")
        require(baseVersion >= 1) { "baseVersion must be >= 1" }
        require(createdAtUtcMillis >= 0L) { "createdAtUtcMillis must not be negative" }
        require(expiresAtUtcMillis > createdAtUtcMillis) { "proposal expiry must follow creation" }
        require(consentVersion >= 0) { "consentVersion must not be negative" }
    }
}

data class A1RevisionReceipt(
    val proposalId: String,
    val revisionId: String,
    val requestFingerprint: String,
    val planId: String,
    val versionBefore: Int,
    val versionAfter: Int,
    val diff: List<A1MoveDiff>,
    val effectIds: List<String>,
    val scopeHash: String,
    val consentVersion: Int,
    /** Date on which the transaction consumed quota in the plan's local timezone. */
    val localDate: String,
    /** Quota count after this durable application. Undo never refunds it. */
    val quotaUsed: Int,
    val appliedAtUtcMillis: Long,
    val undoUntilUtcMillis: Long,
    val undoRevisionId: String? = null,
    val undoVersion: Int? = null,
    val undoneAtUtcMillis: Long? = null,
) {
    init {
        requireCanonicalUuid(proposalId, "proposalId")
        requireCanonicalUuid(revisionId, "revisionId")
        requireCanonicalUuid(planId, "planId")
        require(requestFingerprint.matches(SHA256_PATTERN)) { "requestFingerprint must be SHA-256" }
        require(versionBefore >= 1 && versionAfter == versionBefore + 1) { "invalid A1 receipt versions" }
        require(diff.isNotEmpty() && diff.size <= A1BoundedRevisionPolicy.MAX_MOVED_ACTIVITIES) {
            "invalid A1 receipt diff"
        }
        require(diff.map { it.activityId }.toSet().size == diff.size) { "duplicate A1 diff activity" }
        require(effectIds.isNotEmpty() && effectIds.all { it.isNotBlank() }) { "invalid A1 effects" }
        require(effectIds.toSet().size == effectIds.size) { "duplicate A1 effect" }
        val expectedEffects = diff.map { "PLAN_ACTIVITY_UPDATE:${it.activityId}" } + "PLAN_APPLY:$revisionId"
        require(effectIds.toSet() == expectedEffects.toSet()) { "A1 receipt contains an unexpected effect" }
        require(scopeHash.matches(SHA256_PATTERN)) { "scopeHash must be SHA-256" }
        require(consentVersion > 0) { "A1 receipt requires explicit consent" }
        requireCanonicalDate(localDate)
        require(quotaUsed in 1..A1BoundedRevisionPolicy.MAX_APPLICATIONS_PER_LOCAL_DATE) {
            "invalid A1 quota use"
        }
        require(appliedAtUtcMillis >= 0L) { "appliedAtUtcMillis must not be negative" }
        require(undoUntilUtcMillis == appliedAtUtcMillis + A1BoundedRevisionPolicy.UNDO_WINDOW_MILLIS) {
            "invalid A1 undo deadline"
        }
        val undoFieldsPresent = undoRevisionId != null || undoVersion != null || undoneAtUtcMillis != null
        require(!undoFieldsPresent || undoRevisionId != null && undoVersion == versionAfter + 1 && undoneAtUtcMillis != null) {
            "incomplete A1 undo receipt"
        }
        undoRevisionId?.let { requireCanonicalUuid(it, "undoRevisionId") }
        undoVersion?.let { require(it >= versionAfter + 1) { "invalid A1 undo version" } }
        undoneAtUtcMillis?.let { require(it >= appliedAtUtcMillis) { "undo precedes A1 application" } }
    }
}

/** Additive V5 state. Missing policies resolve to a synthesized legacy PROTECTED policy. */
data class A1PersistentState(
    val consent: A1Consent = A1Consent(),
    val activityPolicies: Map<String, A1ActivityPolicy> = emptyMap(),
    val quotaByLocalDate: Map<String, Int> = emptyMap(),
    val receipts: List<A1RevisionReceipt> = emptyList(),
) {
    init {
        require(activityPolicies.all { (id, policy) -> id == policy.activityId }) {
            "A1 activity policy key does not match its activity id"
        }
        quotaByLocalDate.forEach { (date, count) ->
            requireCanonicalDate(date)
            require(count in 1..A1BoundedRevisionPolicy.MAX_APPLICATIONS_PER_LOCAL_DATE) {
                "invalid A1 quota count"
            }
        }
        require(receipts.map { it.proposalId }.toSet().size == receipts.size) { "duplicate A1 proposal receipt" }
        require(receipts.map { it.revisionId }.toSet().size == receipts.size) { "duplicate A1 revision receipt" }
    }

    fun policyFor(activityId: String): A1ActivityPolicy =
        activityPolicies[activityId] ?: A1ActivityPolicy.legacyProtected(activityId)

    /** Explicitly migrates every currently unclassified activity to the protected default. */
    fun migrateLegacyActivities(plan: DayPlan): A1PersistentState = copy(
        activityPolicies = activityPolicies + plan.activities
            .filterNot { it.activityId in activityPolicies }
            .associate { it.activityId to A1ActivityPolicy.legacyProtected(it.activityId) },
    )
}

enum class A1PolicyRejection {
    PLAN_NOT_FOUND,
    PLAN_NOT_MODIFIABLE,
    PLAN_ID_MISMATCH,
    STALE_VERSION,
    CONSENT_DISABLED,
    CONSENT_VERSION_MISMATCH,
    PROPOSAL_NOT_YET_VALID,
    PROPOSAL_EXPIRED,
    PROPOSAL_TTL_TOO_LONG,
    EMPTY_CHANGESET,
    ADD_OR_REMOVE_FORBIDDEN,
    CONTENT_CHANGE_FORBIDDEN,
    STATUS_CHANGE_FORBIDDEN,
    INVALID_DATE_OR_TIMEZONE,
    ACTIVITY_NOT_FOUND,
    ACTIVITY_NOT_PLANNED,
    REQUIRED_ACTIVITY,
    PROTECTED_ACTIVITY_KIND,
    TRAINING_LINK_FORBIDDEN,
    POLICY_UNKNOWN,
    ACTIVITY_PROTECTED,
    ACTIVITY_LOCKED,
    EXTERNAL_LINK_UNKNOWN,
    EXTERNAL_LINK_FORBIDDEN,
    MOVE_LIMIT_EXCEEDED,
    MOVE_DISTANCE_EXCEEDED,
    QUOTA_EXCEEDED,
    IDEMPOTENCY_CONFLICT,
    REVISION_ID_CONFLICT,
    STORAGE_FAILURE,
    STALE_STATE,
    INVALID_PROPOSAL,
}

sealed interface A1ValidationResult {
    data class Valid(val revision: A1ValidatedRevision) : A1ValidationResult
    data class Rejected(val reason: A1PolicyRejection) : A1ValidationResult
}

data class A1ValidatedRevision(
    val revision: PlanRevision,
    val diff: List<A1MoveDiff>,
    val effectIds: List<String>,
    val scopeHash: String,
    val requestFingerprint: String,
    val quotaLocalDate: String,
    val quotaUsedAfterCommit: Int,
)

sealed interface A1ApplyResult {
    data class Applied(val plan: DayPlan, val receipt: A1RevisionReceipt) : A1ApplyResult
    data class AlreadyApplied(val plan: DayPlan, val receipt: A1RevisionReceipt) : A1ApplyResult
    data object Cancelled : A1ApplyResult
    data class Rejected(val reason: A1PolicyRejection) : A1ApplyResult
    data class Corrupt(val reason: String, val quarantine: PlanQuarantineMetadata?) : A1ApplyResult
}

sealed interface A1SettingsResult {
    data class Updated(val consent: A1Consent) : A1SettingsResult
    data class Rejected(val reason: PlanRejectionReason) : A1SettingsResult
    data class Corrupt(val reason: String, val quarantine: PlanQuarantineMetadata?) : A1SettingsResult
}

sealed interface A1PolicyUpdateResult {
    data class Updated(val policy: A1ActivityPolicy) : A1PolicyUpdateResult
    data class Rejected(val reason: PlanRejectionReason) : A1PolicyUpdateResult
    data class Corrupt(val reason: String, val quarantine: PlanQuarantineMetadata?) : A1PolicyUpdateResult
}

sealed interface A1UndoResult {
    data class Undone(val plan: DayPlan, val receipt: A1RevisionReceipt) : A1UndoResult
    data class Rejected(val reason: A1UndoRejection) : A1UndoResult
    data class Corrupt(val reason: String, val quarantine: PlanQuarantineMetadata?) : A1UndoResult
}

enum class A1UndoRejection {
    RECEIPT_NOT_FOUND,
    ALREADY_UNDONE,
    UNDO_EXPIRED,
    VERSION_CHANGED,
    CURRENT_PLAN_MISMATCH,
    STORAGE_FAILURE,
}

/** Pure deterministic eligibility, diff, effect, hash, and quota validation for A1. */
object A1BoundedRevisionPolicy {
    const val MAX_MOVED_ACTIVITIES = 3
    const val MAX_MOVE_MINUTES = 120
    const val MAX_APPLICATIONS_PER_LOCAL_DATE = 2
    const val MAX_PROPOSAL_TTL_MILLIS = 30L * 60L * 1000L
    const val UNDO_WINDOW_MILLIS = 15L * 60L * 1000L

    private val ALLOWED_CLASSES = setOf(
        A1ActivityClass.READING,
        A1ActivityClass.CHINESE,
        A1ActivityClass.LEISURE,
        A1ActivityClass.PERSONAL,
    )
    private val FORBIDDEN_KINDS = setOf(ActivityKind.REST, ActivityKind.APPOINTMENT, ActivityKind.TRAINING)

    fun validate(
        current: DayPlan,
        proposal: A1RevisionProposal,
        state: A1PersistentState,
        nowUtcMillis: Long,
        engine: PlanRevisionEngine = PlanRevisionEngine(),
    ): A1ValidationResult {
        if (current.state == PlanState.ARCHIVED || current.state == PlanState.SUPERSEDED) {
            return A1ValidationResult.Rejected(A1PolicyRejection.PLAN_NOT_MODIFIABLE)
        }
        if (proposal.planId != current.planId) return A1ValidationResult.Rejected(A1PolicyRejection.PLAN_ID_MISMATCH)
        if (proposal.baseVersion != current.version) return A1ValidationResult.Rejected(A1PolicyRejection.STALE_VERSION)
        if (!state.consent.enabled) return A1ValidationResult.Rejected(A1PolicyRejection.CONSENT_DISABLED)
        if (proposal.consentVersion != state.consent.version) {
            return A1ValidationResult.Rejected(A1PolicyRejection.CONSENT_VERSION_MISMATCH)
        }
        if (proposal.createdAtUtcMillis > nowUtcMillis) {
            return A1ValidationResult.Rejected(A1PolicyRejection.PROPOSAL_NOT_YET_VALID)
        }
        if (proposal.expiresAtUtcMillis <= nowUtcMillis) {
            return A1ValidationResult.Rejected(A1PolicyRejection.PROPOSAL_EXPIRED)
        }
        val ttl = try {
            Math.subtractExact(proposal.expiresAtUtcMillis, proposal.createdAtUtcMillis)
        } catch (_: ArithmeticException) {
            return A1ValidationResult.Rejected(A1PolicyRejection.INVALID_PROPOSAL)
        }
        if (ttl <= 0L || ttl > MAX_PROPOSAL_TTL_MILLIS) {
            return A1ValidationResult.Rejected(A1PolicyRejection.PROPOSAL_TTL_TOO_LONG)
        }
        if (proposal.proposedActivities.map { it.activityId }.toSet().size != proposal.proposedActivities.size) {
            return A1ValidationResult.Rejected(A1PolicyRejection.INVALID_PROPOSAL)
        }
        val oldById = current.activities.associateBy { it.activityId }
        val newById = proposal.proposedActivities.associateBy { it.activityId }
        if (oldById.keys != newById.keys) {
            return A1ValidationResult.Rejected(A1PolicyRejection.ADD_OR_REMOVE_FORBIDDEN)
        }

        for ((activityId, before) in oldById) {
            val after = newById[activityId] ?: return A1ValidationResult.Rejected(A1PolicyRejection.ADD_OR_REMOVE_FORBIDDEN)
            if (after.status != before.status) return A1ValidationResult.Rejected(A1PolicyRejection.STATUS_CHANGE_FORBIDDEN)
            if (after.scheduledLocal.localDate != before.scheduledLocal.localDate ||
                after.scheduledLocal.timezone != before.scheduledLocal.timezone
            ) return A1ValidationResult.Rejected(A1PolicyRejection.INVALID_DATE_OR_TIMEZONE)
            if (after.copy(
                    scheduledLocal = before.scheduledLocal,
                    createdRevisionId = before.createdRevisionId,
                    lastRevisionId = before.lastRevisionId,
                ) != before
            ) return A1ValidationResult.Rejected(A1PolicyRejection.CONTENT_CHANGE_FORBIDDEN)
        }

        val changes = try {
            engine.calculateChanges(current, proposal.proposedActivities)
        } catch (_: IllegalArgumentException) {
            return A1ValidationResult.Rejected(A1PolicyRejection.INVALID_PROPOSAL)
        }
        if (changes.isEmpty()) return A1ValidationResult.Rejected(A1PolicyRejection.EMPTY_CHANGESET)
        if (changes.any { it !is PlanChange.SlotMoved }) {
            return A1ValidationResult.Rejected(A1PolicyRejection.CONTENT_CHANGE_FORBIDDEN)
        }
        val moves = changes.filterIsInstance<PlanChange.SlotMoved>()
        if (moves.size > MAX_MOVED_ACTIVITIES) {
            return A1ValidationResult.Rejected(A1PolicyRejection.MOVE_LIMIT_EXCEEDED)
        }

        for (move in moves) {
            val activity = oldById[move.activityId]
                ?: return A1ValidationResult.Rejected(A1PolicyRejection.ACTIVITY_NOT_FOUND)
            if (activity.status != ActivityStatus.PLANNED) {
                return A1ValidationResult.Rejected(A1PolicyRejection.ACTIVITY_NOT_PLANNED)
            }
            if (activity.priority == Priority.REQUIRED) {
                return A1ValidationResult.Rejected(A1PolicyRejection.REQUIRED_ACTIVITY)
            }
            if (activity.kind in FORBIDDEN_KINDS) {
                return A1ValidationResult.Rejected(A1PolicyRejection.PROTECTED_ACTIVITY_KIND)
            }
            if (activity.trainingRef != null) {
                return A1ValidationResult.Rejected(A1PolicyRejection.TRAINING_LINK_FORBIDDEN)
            }
            val policy = state.activityPolicies[activity.activityId]
                ?: A1ActivityPolicy.legacyProtected(activity.activityId)
            if (policy.activityClass == A1ActivityClass.PROTECTED) {
                return A1ValidationResult.Rejected(A1PolicyRejection.ACTIVITY_PROTECTED)
            }
            if (policy.activityClass !in ALLOWED_CLASSES) {
                return A1ValidationResult.Rejected(A1PolicyRejection.POLICY_UNKNOWN)
            }
            if (!policy.unlocked) return A1ValidationResult.Rejected(A1PolicyRejection.ACTIVITY_LOCKED)
            when (policy.externalLinkState) {
                A1ExternalLinkState.NONE -> Unit
                A1ExternalLinkState.LINKED -> return A1ValidationResult.Rejected(A1PolicyRejection.EXTERNAL_LINK_FORBIDDEN)
                A1ExternalLinkState.UNKNOWN -> return A1ValidationResult.Rejected(A1PolicyRejection.EXTERNAL_LINK_UNKNOWN)
            }
            val distance = kotlin.math.abs(move.to.startMinute - move.from.startMinute)
            if (distance == 0 || distance > MAX_MOVE_MINUTES) {
                return A1ValidationResult.Rejected(A1PolicyRejection.MOVE_DISTANCE_EXCEEDED)
            }
            if (move.from.durationMinutes != move.to.durationMinutes) {
                return A1ValidationResult.Rejected(A1PolicyRejection.CONTENT_CHANGE_FORBIDDEN)
            }
        }

        val localDate = Instant.ofEpochMilli(nowUtcMillis).atZone(ZoneId.of(current.timezone)).toLocalDate().toString()
        val quotaUsed = state.quotaByLocalDate[localDate] ?: 0
        if (quotaUsed >= MAX_APPLICATIONS_PER_LOCAL_DATE) {
            return A1ValidationResult.Rejected(A1PolicyRejection.QUOTA_EXCEEDED)
        }
        val revision = try {
            engine.createRevision(
                current = current,
                proposedActivities = proposal.proposedActivities,
                revisionId = proposal.revisionId,
                origin = RevisionOrigin.AGENT,
                summary = "A1 bounded local move",
                createdAt = proposal.createdAtUtcMillis,
                ttlMillis = ttl,
            )
        } catch (_: IllegalArgumentException) {
            return A1ValidationResult.Rejected(A1PolicyRejection.INVALID_PROPOSAL)
        } catch (_: ArithmeticException) {
            return A1ValidationResult.Rejected(A1PolicyRejection.INVALID_PROPOSAL)
        }
        val effects = try {
            engine.effectIds(revision.changes, revision.revisionId)
        } catch (_: IllegalArgumentException) {
            return A1ValidationResult.Rejected(A1PolicyRejection.INVALID_PROPOSAL)
        }
        val diff = revision.changes.filterIsInstance<PlanChange.SlotMoved>().map { move ->
            A1MoveDiff(move.activityId, move.from, move.to)
        }
        return A1ValidationResult.Valid(
            A1ValidatedRevision(
                revision = revision,
                diff = diff,
                effectIds = effects,
                scopeHash = engine.scopeHash(effects, revision.changes),
                requestFingerprint = fingerprint(proposal),
                quotaLocalDate = localDate,
                quotaUsedAfterCommit = quotaUsed + 1,
            ),
        )
    }

    /** Stable digest over every proposal field and every activity field, independent of client diff data. */
    fun fingerprint(proposal: A1RevisionProposal): String {
        val fields = mutableListOf<String?>(
            proposal.proposalId,
            proposal.revisionId,
            proposal.planId,
            proposal.baseVersion.toString(),
            proposal.createdAtUtcMillis.toString(),
            proposal.expiresAtUtcMillis.toString(),
            proposal.consentVersion.toString(),
        )
        proposal.proposedActivities.sortedWith(compareBy<PlanActivity> { it.activityId }.thenBy { activityCanonical(it) })
            .forEach { fields += activityCanonical(it) }
        val canonical = fields.joinToString(separator = "") { value ->
            if (value == null) "N" else "S${value.length}:$value"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun activityCanonical(activity: PlanActivity): String = listOf(
        activity.activityId,
        activity.planId,
        activity.kind.name,
        activity.title,
        activity.status.name,
        activity.scheduledLocal.localDate,
        activity.scheduledLocal.startMinute.toString(),
        activity.scheduledLocal.durationMinutes.toString(),
        activity.scheduledLocal.timezone,
        activity.priority.name,
        activity.goalId,
        activity.subGoalId,
        activity.estimateMinutes?.toString(),
        activity.actualMinutes?.toString(),
        activity.createdRevisionId,
        activity.lastRevisionId,
        activity.notes,
        activity.trainingRef?.let { reference -> listOf(
            reference.provider, reference.baseUrl, reference.routineId.toString(), reference.dayId?.toString(),
            reference.slotId?.toString(), reference.planId, reference.activityId, reference.fetchedAt,
            reference.sourceUpdatedAt, reference.etag, reference.syncState.name,
        ).joinToString("") { value -> if (value == null) "N" else "S${value.length}:$value" } },
        activity.continuityId,
        activity.supersedesActivityId,
    ).joinToString("") { value -> if (value == null) "N" else "S${value.length}:$value" }
}
