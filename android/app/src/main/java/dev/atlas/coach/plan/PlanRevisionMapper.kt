package dev.atlas.coach.plan

import java.nio.charset.StandardCharsets
import java.util.UUID

sealed interface PlanRevisionMappingResult {
    data class Ready(val revision: PlanRevision) : PlanRevisionMappingResult
    data object AlreadyApplied : PlanRevisionMappingResult
    data class Rejected(val code: String) : PlanRevisionMappingResult
}

/** Converts a validated V3 proposal to the local, reviewable DayPlan revision. */
class PlanRevisionMapper(
    private val engine: PlanRevisionEngine = PlanRevisionEngine(),
) {
    fun map(
        proposal: PlanRevisionProposalEnvelopeV3,
        current: DayPlan,
        nowUtcMillis: Long,
    ): PlanRevisionMappingResult {
        val parameters = proposal.parameters
        if (proposal.expiresAt.toEpochMilli() <= nowUtcMillis) {
            return PlanRevisionMappingResult.Rejected(AGENT_REVISION_EXPIRED)
        }
        if (parameters.planId != current.planId ||
            parameters.localDate != current.localDate ||
            parameters.timezone != current.timezone
        ) {
            return PlanRevisionMappingResult.Rejected(PLAN_MISMATCH)
        }

        val revisionId = revisionId(proposal.proposalId)
        val activities = try {
            mapActivities(proposal, current, revisionId)
        } catch (_: IllegalArgumentException) {
            return PlanRevisionMappingResult.Rejected(PLAN_MISMATCH)
        }

        // The persisted current revision id is the durable replay marker for the most recent revision.
        // Matching its complete resulting activity set makes a replay a no-op; changed payload is a conflict.
        if (current.currentRevisionId == revisionId) {
            return if (current.version == parameters.baseVersion + 1 &&
                sameActivities(current.activities, activities)
            ) {
                PlanRevisionMappingResult.AlreadyApplied
            } else {
                PlanRevisionMappingResult.Rejected(REVISION_ID_CONFLICT)
            }
        }
        if (parameters.baseVersion != current.version) {
            return PlanRevisionMappingResult.Rejected(STALE_AGENT_REVISION)
        }

        val ttlMillis = proposal.expiresAt.toEpochMilli() - nowUtcMillis
        if (ttlMillis <= 0L) return PlanRevisionMappingResult.Rejected(AGENT_REVISION_EXPIRED)
        val revision = try {
            engine.createRevision(
                current = current,
                proposedActivities = activities,
                revisionId = revisionId,
                origin = RevisionOrigin.AGENT,
                summary = parameters.summary,
                createdAt = nowUtcMillis,
                ttlMillis = ttlMillis,
            )
        } catch (_: IllegalArgumentException) {
            return PlanRevisionMappingResult.Rejected(NO_APPLICABLE_DIFF)
        } catch (_: ArithmeticException) {
            return PlanRevisionMappingResult.Rejected(NO_APPLICABLE_DIFF)
        }
        return PlanRevisionMappingResult.Ready(revision)
    }

    private fun mapActivities(
        proposal: PlanRevisionProposalEnvelopeV3,
        current: DayPlan,
        revisionId: String,
    ): List<PlanActivity> {
        val parameters = proposal.parameters
        val existing = current.activities.associateBy(PlanActivity::activityId)
        return parameters.activities.map { proposed ->
            val scheduled = LocalSchedule(
                localDate = proposed.localDate,
                startMinute = proposed.startMinute,
                durationMinutes = proposed.durationMinutes,
                timezone = proposed.timezone,
            )
            if (proposed.activityId != null) {
                val old = existing[proposed.activityId]
                    ?: throw IllegalArgumentException("proposal references an unknown activity")
                old.copy(
                    kind = proposed.kind,
                    title = proposed.title,
                    scheduledLocal = scheduled,
                    priority = proposed.priority,
                    goalId = proposed.goalId ?: old.goalId,
                    subGoalId = proposed.subGoalId ?: old.subGoalId,
                    notes = proposed.notes ?: old.notes,
                )
            } else {
                PlanActivity(
                    activityId = activityId(current.planId, proposed.clientRef, parameters.baseVersion),
                    planId = current.planId,
                    kind = proposed.kind,
                    title = proposed.title,
                    status = ActivityStatus.PLANNED,
                    scheduledLocal = scheduled,
                    priority = proposed.priority,
                    goalId = proposed.goalId,
                    subGoalId = proposed.subGoalId,
                    createdRevisionId = revisionId,
                    lastRevisionId = revisionId,
                    notes = proposed.notes,
                )
            }
        }
    }

    private fun sameActivities(left: List<PlanActivity>, right: List<PlanActivity>): Boolean =
        left.associateBy(PlanActivity::activityId) == right.associateBy(PlanActivity::activityId)

    companion object {
        const val PLAN_MISMATCH = "PLAN_MISMATCH"
        const val STALE_AGENT_REVISION = "STALE_AGENT_REVISION"
        const val AGENT_REVISION_EXPIRED = "AGENT_REVISION_EXPIRED"
        const val REVISION_ID_CONFLICT = "REVISION_ID_CONFLICT"
        const val NO_APPLICABLE_DIFF = "NO_APPLICABLE_DIFF"

        fun revisionId(proposalId: String): String = deterministicUuid("plan-revision:$proposalId")

        fun activityId(planId: String, clientRef: String, baseVersion: Int): String =
            deterministicUuid("$planId:$clientRef:$baseVersion")

        private fun deterministicUuid(value: String): String =
            UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8)).toString()
    }
}
