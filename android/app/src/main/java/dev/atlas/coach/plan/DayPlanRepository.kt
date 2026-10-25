package dev.atlas.coach.plan

internal data class PlanStoreDocument(
    val dayPlan: DayPlan,
    val appliedRevisions: List<PlanRevision> = emptyList(),
    val confirmations: List<PlanConfirmation> = emptyList(),
)

sealed interface PlanLoadResult {
    data object Missing : PlanLoadResult
    data class Loaded(val plan: DayPlan) : PlanLoadResult
    data class Corrupt(val reason: String, val quarantine: PlanQuarantineMetadata?) : PlanLoadResult
}

sealed interface PlanWriteResult {
    data class Saved(val plan: DayPlan) : PlanWriteResult
    data class Rejected(val reason: PlanRejectionReason) : PlanWriteResult
    data class Corrupt(val reason: String, val quarantine: PlanQuarantineMetadata?) : PlanWriteResult
}

data class PlanPreview(
    val before: DayPlan,
    val after: DayPlan,
    val revision: PlanRevision,
    val effectIds: List<String>,
    val scopeHash: String,
    val overlapWarnings: List<OverlapWarning>,
)

sealed interface PlanPreviewResult {
    data class Ready(val preview: PlanPreview) : PlanPreviewResult
    data class Rejected(val reason: PlanRejectionReason) : PlanPreviewResult
    data class Corrupt(val reason: String, val quarantine: PlanQuarantineMetadata?) : PlanPreviewResult
}

sealed interface PlanApplyResult {
    data class Applied(
        val plan: DayPlan,
        val revision: PlanRevision,
        val confirmation: PlanConfirmation,
        val effectIds: List<String>,
    ) : PlanApplyResult

    data class AlreadyApplied(
        val plan: DayPlan,
        val revision: PlanRevision,
        val effectIds: List<String>,
    ) : PlanApplyResult

    data class Rejected(val reason: PlanRejectionReason) : PlanApplyResult
    data class Corrupt(val reason: String, val quarantine: PlanQuarantineMetadata?) : PlanApplyResult
}

/** Local, synchronous repository. All successful mutations are durable before they return. */
class DayPlanRepository(
    private val backend: PlanStateBackend,
    private val clock: PlanClock,
    private val engine: PlanRevisionEngine = PlanRevisionEngine(),
) {
    fun load(): PlanLoadResult = when (val loaded = readDocument()) {
        InternalRead.Missing -> PlanLoadResult.Missing
        is InternalRead.Loaded -> PlanLoadResult.Loaded(loaded.document.dayPlan)
        is InternalRead.Corrupt -> PlanLoadResult.Corrupt(loaded.reason, loaded.quarantine)
    }

    fun create(plan: DayPlan): PlanWriteResult {
        if (plan.version != 1) return PlanWriteResult.Rejected(PlanRejectionReason.NON_MONOTONE_VERSION)
        return when (val loaded = readDocument()) {
            InternalRead.Missing -> persist(PlanStoreDocument(plan)).asWriteResult()
            is InternalRead.Loaded -> PlanWriteResult.Rejected(PlanRejectionReason.PLAN_ALREADY_EXISTS)
            is InternalRead.Corrupt -> PlanWriteResult.Corrupt(loaded.reason, loaded.quarantine)
        }
    }

    fun save(plan: DayPlan): PlanWriteResult = when (val loaded = readDocument()) {
        InternalRead.Missing -> PlanWriteResult.Rejected(PlanRejectionReason.PLAN_NOT_FOUND)
        is InternalRead.Corrupt -> PlanWriteResult.Corrupt(loaded.reason, loaded.quarantine)
        is InternalRead.Loaded -> {
            val current = loaded.document.dayPlan
            when {
                plan.planId != current.planId -> PlanWriteResult.Rejected(PlanRejectionReason.PLAN_ID_MISMATCH)
                plan == current -> PlanWriteResult.Saved(current)
                plan.version <= current.version -> PlanWriteResult.Rejected(PlanRejectionReason.NON_MONOTONE_VERSION)
                plan.version != current.version + 1 -> PlanWriteResult.Rejected(PlanRejectionReason.VERSION_CONFLICT)
                plan.createdAt != current.createdAt || plan.updatedAt < current.updatedAt ->
                    PlanWriteResult.Rejected(PlanRejectionReason.INVALID_CHANGES)
                else -> persist(loaded.document.copy(dayPlan = plan)).asWriteResult()
            }
        }
    }

    fun preview(revision: PlanRevision): PlanPreviewResult = when (val loaded = readDocument()) {
        InternalRead.Missing -> PlanPreviewResult.Rejected(PlanRejectionReason.PLAN_NOT_FOUND)
        is InternalRead.Corrupt -> PlanPreviewResult.Corrupt(loaded.reason, loaded.quarantine)
        is InternalRead.Loaded -> when (val preview = buildPreview(loaded.document.dayPlan, revision)) {
            is PreviewBuild.Ready -> PlanPreviewResult.Ready(preview.preview)
            is PreviewBuild.Rejected -> PlanPreviewResult.Rejected(preview.reason)
        }
    }

    fun apply(revision: PlanRevision, confirmation: PlanConfirmation): PlanApplyResult =
        when (val loaded = readDocument()) {
            InternalRead.Missing -> PlanApplyResult.Rejected(PlanRejectionReason.PLAN_NOT_FOUND)
            is InternalRead.Corrupt -> PlanApplyResult.Corrupt(loaded.reason, loaded.quarantine)
            is InternalRead.Loaded -> applyToDocument(loaded.document, revision, confirmation)
        }

    private fun applyToDocument(
        document: PlanStoreDocument,
        revision: PlanRevision,
        confirmation: PlanConfirmation,
    ): PlanApplyResult {
        val previouslyApplied = document.appliedRevisions.firstOrNull { it.revisionId == revision.revisionId }
        if (previouslyApplied != null) {
            return if (sameRevisionIgnoringStatus(previouslyApplied, revision)) {
                PlanApplyResult.AlreadyApplied(
                    plan = document.dayPlan,
                    revision = previouslyApplied,
                    effectIds = engine.effectIds(previouslyApplied.changes, previouslyApplied.revisionId),
                )
            } else {
                PlanApplyResult.Rejected(PlanRejectionReason.REVISION_ID_CONFLICT)
            }
        }

        val preview = when (val result = buildPreview(document.dayPlan, revision)) {
            is PreviewBuild.Ready -> result.preview
            is PreviewBuild.Rejected -> return PlanApplyResult.Rejected(result.reason)
        }
        val now = clock.nowUtcMillis()
        if (confirmation.revisionId != revision.revisionId ||
            confirmation.approvedAt < revision.createdAt || confirmation.approvedAt > now
        ) {
            return PlanApplyResult.Rejected(PlanRejectionReason.INVALID_CONFIRMATION)
        }
        if (confirmation.consumedAt != null) {
            return PlanApplyResult.Rejected(PlanRejectionReason.CONFIRMATION_ALREADY_CONSUMED)
        }
        if (document.confirmations.any { it.confirmationId == confirmation.confirmationId }) {
            return PlanApplyResult.Rejected(PlanRejectionReason.INVALID_CONFIRMATION)
        }
        if (confirmation.expiresAt <= now) {
            return PlanApplyResult.Rejected(PlanRejectionReason.CONFIRMATION_EXPIRED)
        }
        if (confirmation.effectIds.sorted() != preview.effectIds.sorted() || confirmation.scopeHash != preview.scopeHash) {
            return PlanApplyResult.Rejected(PlanRejectionReason.EFFECT_SCOPE_MISMATCH)
        }

        val appliedRevision = revision.copy(status = RevisionStatus.APPLIED)
        val consumedConfirmation = confirmation.copy(consumedAt = now)
        val updatedDocument = document.copy(
            dayPlan = preview.after,
            appliedRevisions = document.appliedRevisions + appliedRevision,
            confirmations = document.confirmations + consumedConfirmation,
        )
        return when (persist(updatedDocument)) {
            is PersistResult.Failed -> PlanApplyResult.Rejected(PlanRejectionReason.STORAGE_FAILURE)
            is PersistResult.Succeeded -> PlanApplyResult.Applied(
                plan = preview.after,
                revision = appliedRevision,
                confirmation = consumedConfirmation,
                effectIds = preview.effectIds,
            )
        }
    }

    private fun buildPreview(current: DayPlan, revision: PlanRevision): PreviewBuild {
        val now = clock.nowUtcMillis()
        if (revision.planId != current.planId) return PreviewBuild.Rejected(PlanRejectionReason.PLAN_ID_MISMATCH)
        if (revision.baseVersion != current.version || revision.newVersion != current.version + 1) {
            return PreviewBuild.Rejected(PlanRejectionReason.STALE_REVISION)
        }
        if (revision.status == RevisionStatus.EXPIRED || revision.expiresAt <= now) {
            return PreviewBuild.Rejected(PlanRejectionReason.REVISION_EXPIRED)
        }
        if (revision.createdAt > now) return PreviewBuild.Rejected(PlanRejectionReason.INVALID_REVISION)
        if (revision.status != RevisionStatus.PROPOSED && revision.status != RevisionStatus.APPROVED) {
            return PreviewBuild.Rejected(PlanRejectionReason.REVISION_NOT_APPLICABLE)
        }
        if (current.state == PlanState.ARCHIVED || current.state == PlanState.SUPERSEDED) {
            return PreviewBuild.Rejected(PlanRejectionReason.REVISION_NOT_APPLICABLE)
        }
        val activities = when (val result = engine.applyChanges(current, revision)) {
            is ChangeApplicationResult.Applied -> result.activities
            is ChangeApplicationResult.Rejected -> return PreviewBuild.Rejected(result.reason)
        }
        val updatedAt = maxOf(current.updatedAt, now)
        val after = try {
            current.copy(
                version = revision.newVersion,
                currentRevisionId = revision.revisionId,
                state = PlanState.APPROVED,
                activities = activities,
                updatedAt = updatedAt,
                approvedAt = maxOf(now, current.createdAt, current.approvedAt ?: current.createdAt),
            )
        } catch (_: IllegalArgumentException) {
            return PreviewBuild.Rejected(PlanRejectionReason.INVALID_CHANGES)
        }
        val effects = try {
            engine.effectIds(revision.changes, revision.revisionId)
        } catch (_: IllegalArgumentException) {
            return PreviewBuild.Rejected(PlanRejectionReason.INVALID_CHANGES)
        }
        return PreviewBuild.Ready(
            PlanPreview(
                before = current,
                after = after,
                revision = revision,
                effectIds = effects,
                scopeHash = engine.scopeHash(effects),
                overlapWarnings = engine.overlapWarnings(activities),
            ),
        )
    }

    private fun readDocument(): InternalRead {
        val raw = try {
            backend.read()
        } catch (_: Exception) {
            return InternalRead.Corrupt("local plan storage could not be read", null)
        } ?: return InternalRead.Missing
        return try {
            InternalRead.Loaded(PlanJsonCodec.decodeStore(raw))
        } catch (_: Exception) {
            val quarantine = try {
                backend.quarantine(raw, "invalid schema-version-3 plan document", clock.nowUtcMillis())
            } catch (_: Exception) {
                null
            }
            InternalRead.Corrupt("invalid schema-version-3 plan document", quarantine)
        }
    }

    private fun persist(document: PlanStoreDocument): PersistResult = try {
        backend.write(PlanJsonCodec.encodeStore(document))
        PersistResult.Succeeded(document.dayPlan)
    } catch (_: Exception) {
        PersistResult.Failed
    }

    private fun PersistResult.asWriteResult(): PlanWriteResult = when (this) {
        is PersistResult.Succeeded -> PlanWriteResult.Saved(plan)
        PersistResult.Failed -> PlanWriteResult.Rejected(PlanRejectionReason.STORAGE_FAILURE)
    }

    private fun sameRevisionIgnoringStatus(left: PlanRevision, right: PlanRevision): Boolean =
        left.copy(status = RevisionStatus.PROPOSED) == right.copy(status = RevisionStatus.PROPOSED)

    private sealed interface InternalRead {
        data object Missing : InternalRead
        data class Loaded(val document: PlanStoreDocument) : InternalRead
        data class Corrupt(val reason: String, val quarantine: PlanQuarantineMetadata?) : InternalRead
    }

    private sealed interface PreviewBuild {
        data class Ready(val preview: PlanPreview) : PreviewBuild
        data class Rejected(val reason: PlanRejectionReason) : PreviewBuild
    }

    private sealed interface PersistResult {
        data class Succeeded(val plan: DayPlan) : PersistResult
        data object Failed : PersistResult
    }
}
