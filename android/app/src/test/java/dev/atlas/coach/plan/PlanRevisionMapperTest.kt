package dev.atlas.coach.plan

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanRevisionMapperTest {
    private val mapper = PlanRevisionMapper()

    @Test
    fun mapsNewActivityAndRevisionIdsDeterministically() {
        val proposal = proposal(activities = listOf(proposedActivity(activityId = null)))

        val result = mapper.map(proposal, dayPlan(activities = emptyList()), NOW)
        val revision = (result as PlanRevisionMappingResult.Ready).revision
        val added = (revision.changes.single() as PlanChange.SlotAdded).activity

        assertEquals(
            UUID.nameUUIDFromBytes("$PLAN_ID:ref-new:1".toByteArray(StandardCharsets.UTF_8)).toString(),
            added.activityId,
        )
        assertEquals(
            UUID.nameUUIDFromBytes("plan-revision:${proposal.proposalId}".toByteArray(StandardCharsets.UTF_8)).toString(),
            revision.revisionId,
        )
        assertEquals(RevisionOrigin.AGENT, revision.origin)
        assertEquals(ActivityStatus.PLANNED, added.status)
        assertEquals(revision.revisionId, added.createdRevisionId)
        assertEquals(revision.revisionId, added.lastRevisionId)
        assertEquals("Déplacer le planning", revision.summary)
    }

    @Test
    fun editingExistingActivityPreservesStatusAndNullFieldsAndMapsTheDiff() {
        val existing = activity(status = ActivityStatus.IN_PROGRESS).copy(
            estimateMinutes = 75,
            actualMinutes = 20,
        )
        val proposed = proposedActivity(
            activityId = ACTIVITY_ID,
            title = "Titre revu",
            startMinute = 10 * 60,
            durationMinutes = 40,
            priority = Priority.REQUIRED,
            goalId = null,
            subGoalId = null,
            notes = null,
        )
        val result = mapper.map(proposal(activities = listOf(proposed)), dayPlan(listOf(existing)), NOW)
        val revision = (result as PlanRevisionMappingResult.Ready).revision
        val edited = revision.changes.filterIsInstance<PlanChange.SlotEdited>().single().after

        assertEquals(ActivityStatus.IN_PROGRESS, edited.status)
        assertEquals(existing.createdRevisionId, edited.createdRevisionId)
        assertEquals(75, edited.estimateMinutes)
        assertEquals(20, edited.actualMinutes)
        assertEquals(existing.goalId, edited.goalId)
        assertEquals(existing.subGoalId, edited.subGoalId)
        assertEquals(existing.notes, edited.notes)
        assertEquals("Titre revu", edited.title)
        assertEquals(10 * 60, edited.scheduledLocal.startMinute)
        assertEquals(40, edited.scheduledLocal.durationMinutes)
        assertEquals(Priority.REQUIRED, edited.priority)
        assertTrue(revision.changes.any { it is PlanChange.SlotMoved })

        val replacement = proposedActivity(
            activityId = ACTIVITY_ID,
            goalId = OTHER_GOAL_ID,
            subGoalId = OTHER_SUB_GOAL_ID,
            notes = "Note remplacée",
        )
        val replaced = mapper.map(proposal(activities = listOf(replacement)), dayPlan(listOf(existing)), NOW)
        val replacedActivity = (replaced as PlanRevisionMappingResult.Ready).revision.changes
            .filterIsInstance<PlanChange.SlotEdited>().single().after
        assertEquals(OTHER_GOAL_ID, replacedActivity.goalId)
        assertEquals(OTHER_SUB_GOAL_ID, replacedActivity.subGoalId)
        assertEquals("Note remplacée", replacedActivity.notes)
    }

    @Test
    fun rejectsPlanMismatchStaleVersionAndExpiredEnvelopeWithExplicitCodes() {
        val base = proposal(activities = listOf(proposedActivity(activityId = null)))
        val otherPlan = base.copy(parameters = base.parameters.copy(planId = SECOND_PLAN_ID))
        assertEquals(
            PlanRevisionMapper.PLAN_MISMATCH,
            (mapper.map(otherPlan, dayPlan(activities = emptyList()), NOW) as PlanRevisionMappingResult.Rejected).code,
        )

        val stale = base.copy(parameters = base.parameters.copy(baseVersion = 2))
        assertEquals(
            PlanRevisionMapper.STALE_AGENT_REVISION,
            (mapper.map(stale, dayPlan(activities = emptyList()), NOW) as PlanRevisionMappingResult.Rejected).code,
        )

        val expired = base.copy(expiresAt = Instant.ofEpochMilli(NOW - 1))
        assertEquals(
            PlanRevisionMapper.AGENT_REVISION_EXPIRED,
            (mapper.map(expired, dayPlan(activities = emptyList()), NOW) as PlanRevisionMappingResult.Rejected).code,
        )
    }

    @Test
    fun sameEnvelopeReplayIsAlreadyAppliedAndChangedContentConflicts() {
        val proposal = proposal(activities = listOf(proposedActivity(activityId = null)))
        val initial = dayPlan(activities = emptyList())
        val revision = (mapper.map(proposal, initial, NOW) as PlanRevisionMappingResult.Ready).revision
        val backend = MemoryPlanBackend()
        val repository = DayPlanRepository(backend, PlanClock { NOW + 2_000L })
        assertTrue(repository.create(initial) is PlanWriteResult.Saved)
        val preview = repository.preview(revision) as PlanPreviewResult.Ready
        val confirmation = PlanConfirmation(
            confirmationId = CONFIRMATION_ID,
            revisionId = revision.revisionId,
            effectIds = preview.preview.effectIds,
            scopeHash = preview.preview.scopeHash,
            approvedAt = NOW + 1_000L,
            expiresAt = NOW + 30_000L,
        )
        assertTrue(repository.apply(revision, confirmation) is PlanApplyResult.Applied)
        val updated = (repository.load() as PlanLoadResult.Loaded).plan

        assertEquals(PlanRevisionMappingResult.AlreadyApplied, mapper.map(proposal, updated, NOW + 3_000L))
        val altered = proposal.copy(parameters = proposal.parameters.copy(
            activities = listOf(proposedActivity(activityId = null, title = "Autre contenu")),
        ))
        assertEquals(
            PlanRevisionMapper.REVISION_ID_CONFLICT,
            (mapper.map(altered, updated, NOW + 3_000L) as PlanRevisionMappingResult.Rejected).code,
        )
    }

    private fun proposal(
        activities: List<PlanRevisionActivityV3>,
        planId: String = PLAN_ID,
        baseVersion: Int = 1,
        expiresAt: Instant = Instant.ofEpochMilli(NOW + 60 * 60 * 1_000L),
    ) = PlanRevisionProposalEnvelopeV3(
        proposalId = "60000000-0000-4000-8000-000000000001",
        idempotencyKey = "idempotency-1",
        conversationId = null,
        mandateId = "mandate-local",
        mandateVersion = 2,
        createdAt = Instant.ofEpochMilli(NOW),
        expiresAt = expiresAt,
        parameters = PlanRevisionParametersV3(
            planId = planId,
            baseVersion = baseVersion,
            localDate = PLAN_DATE,
            timezone = "Europe/Paris",
            summary = "Déplacer le planning",
            activities = activities,
        ),
    )

    private fun proposedActivity(
        activityId: String?,
        title: String = "Nouvelle activité",
        startMinute: Int = 10 * 60,
        durationMinutes: Int = 50,
        priority: Priority = Priority.IMPORTANT,
        goalId: String? = GOAL_ID,
        subGoalId: String? = SUB_GOAL_ID,
        notes: String? = "Note proposée",
    ) = PlanRevisionActivityV3(
        clientRef = if (activityId == null) "ref-new" else "ref-existing",
        activityId = activityId,
        kind = ActivityKind.ADMIN,
        title = title,
        localDate = PLAN_DATE,
        startMinute = startMinute,
        durationMinutes = durationMinutes,
        timezone = "Europe/Paris",
        priority = priority,
        goalId = goalId,
        subGoalId = subGoalId,
        notes = notes,
    )

    companion object {
        private const val SECOND_PLAN_ID = "10000000-0000-4000-8000-000000000099"
        private const val OTHER_GOAL_ID = "50000000-0000-4000-8000-000000000099"
        private const val OTHER_SUB_GOAL_ID = "50000000-0000-4000-8000-000000000098"
    }
}
