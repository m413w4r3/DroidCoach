package dev.atlas.coach.plan

import java.security.MessageDigest

internal const val PLAN_ID = "10000000-0000-4000-8000-000000000001"
internal const val INITIAL_REVISION_ID = "10000000-0000-4000-8000-000000000002"
internal const val ACTIVITY_ID = "20000000-0000-4000-8000-000000000001"
internal const val SECOND_ACTIVITY_ID = "20000000-0000-4000-8000-000000000002"
internal const val THIRD_ACTIVITY_ID = "20000000-0000-4000-8000-000000000003"
internal const val REVISION_ID = "30000000-0000-4000-8000-000000000001"
internal const val CONFIRMATION_ID = "40000000-0000-4000-8000-000000000001"
internal const val GOAL_ID = "50000000-0000-4000-8000-000000000001"
internal const val SUB_GOAL_ID = "50000000-0000-4000-8000-000000000002"
internal const val PLAN_DATE = "2026-10-26"
internal const val NOW = 1_800_000_000_000L

internal fun schedule(
    startMinute: Int = 9 * 60,
    durationMinutes: Int = 60,
    date: String = PLAN_DATE,
    timezone: String = "Europe/Paris",
) = LocalSchedule(date, startMinute, durationMinutes, timezone)

internal fun activity(
    id: String = ACTIVITY_ID,
    status: ActivityStatus = ActivityStatus.PLANNED,
    scheduled: LocalSchedule = schedule(),
    title: String = "Préparer le rapport",
    revisionId: String = INITIAL_REVISION_ID,
) = PlanActivity(
    activityId = id,
    planId = PLAN_ID,
    kind = ActivityKind.FOCUS,
    title = title,
    status = status,
    scheduledLocal = scheduled,
    priority = Priority.IMPORTANT,
    goalId = GOAL_ID,
    subGoalId = SUB_GOAL_ID,
    estimateMinutes = 45,
    actualMinutes = null,
    createdRevisionId = INITIAL_REVISION_ID,
    lastRevisionId = revisionId,
    notes = "Préparer les éléments utiles",
)

internal fun trainingReference(
    routineId: Int = 7,
    dayId: Int? = 21,
    slotId: Int? = 31,
    activityId: String? = ACTIVITY_ID,
    etag: String? = "\"sport-plan-v1\"",
    syncState: TrainingReferenceSyncState = TrainingReferenceSyncState.FRESH,
) = TrainingPlanReference(
    baseUrl = "https://wger.de",
    routineId = routineId,
    dayId = dayId,
    slotId = slotId,
    planId = PLAN_ID,
    activityId = activityId,
    fetchedAt = "2026-10-26T09:00:00Z",
    sourceUpdatedAt = "2026-10-26T08:30:00Z",
    etag = etag,
    syncState = syncState,
)

internal fun dayPlan(
    activities: List<PlanActivity> = listOf(activity()),
    version: Int = 1,
    currentRevisionId: String = INITIAL_REVISION_ID,
    date: String = PLAN_DATE,
    createdAt: Long = NOW - 60_000L,
    updatedAt: Long = NOW - 60_000L,
    state: PlanState = PlanState.DRAFT,
    approvedAt: Long? = null,
) = DayPlan(
    planId = PLAN_ID,
    localDate = date,
    timezone = "Europe/Paris",
    version = version,
    currentRevisionId = currentRevisionId,
    state = state,
    activities = activities,
    createdAt = createdAt,
    updatedAt = updatedAt,
    approvedAt = approvedAt,
    origin = PlanOrigin.LOCAL,
)

internal fun revisionFor(
    current: DayPlan,
    proposed: List<PlanActivity>,
    revisionId: String = REVISION_ID,
    createdAt: Long = NOW - 1_000L,
    ttlMillis: Long = PlanRevisionEngine.DEFAULT_REVISION_TTL_MILLIS,
) = PlanRevisionEngine().createRevision(
    current = current,
    proposedActivities = proposed,
    revisionId = revisionId,
    origin = RevisionOrigin.USER,
    summary = "Ajuster le planning",
    createdAt = createdAt,
    ttlMillis = ttlMillis,
)

internal fun confirmationFor(
    revision: PlanRevision,
    engine: PlanRevisionEngine = PlanRevisionEngine(),
    effects: List<String> = engine.effectIds(revision.changes, revision.revisionId),
    scopeHash: String = engine.scopeHash(effects, revision.changes),
    approvedAt: Long = NOW,
    expiresAt: Long = NOW + 60_000L,
) = PlanConfirmation(
    confirmationId = CONFIRMATION_ID,
    revisionId = revision.revisionId,
    effectIds = effects,
    scopeHash = scopeHash,
    approvedAt = approvedAt,
    expiresAt = expiresAt,
)

internal class MemoryPlanBackend(var value: String? = null) : PlanStateBackend {
    var writes = 0
    var quarantinedMetadata: PlanQuarantineMetadata? = null

    override fun read(): String? = value

    override fun write(value: String) {
        writes++
        this.value = value
    }

    override fun quarantine(rawValue: String, reason: String, atUtcMillis: Long): PlanQuarantineMetadata {
        val metadata = PlanQuarantineMetadata(
            reason = reason,
            quarantinedAtUtcMillis = atUtcMillis,
            valueLength = rawValue.toByteArray(Charsets.UTF_8).size,
            sha256 = MessageDigest.getInstance("SHA-256")
                .digest(rawValue.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
        )
        quarantinedMetadata = metadata
        return metadata
    }
}
