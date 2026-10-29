package dev.atlas.coach.plan

import dev.atlas.coach.plan.v4.PlanReportReceipt
import dev.atlas.coach.plan.v4.PlanV4JsonCodec
import dev.atlas.coach.plan.v4.PlanV4StoreDocument
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.json.JSONObject

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

enum class PlanReportRejection {
    PLAN_NOT_FOUND,
    PLAN_NOT_MODIFIABLE,
    PLAN_ID_MISMATCH,
    STALE_VERSION,
    CROSS_DATE_UNSUPPORTED,
    ACTIVITY_NOT_FOUND,
    TERMINAL_ACTIVITY,
    INVALID_SLOT,
    CONFLICT,
    IDEMPOTENCY_CONFLICT,
    STORAGE_FAILURE,
}

sealed interface PlanReportResult {
    data class Reported(
        val plan: DayPlan,
        val revision: PlanRevision,
        val replacement: PlanActivity,
        val statusEvent: PlanStatusEvent,
        val effectIds: List<String>,
        val scopeHash: String,
    ) : PlanReportResult

    data class Replayed(
        val plan: DayPlan,
        val revisionId: String,
        val replacementActivityId: String,
        val planVersion: Int,
        val scopeHash: String,
    ) : PlanReportResult

    data class Rejected(val reason: PlanReportRejection) : PlanReportResult
    data class Corrupt(val reason: String, val quarantine: PlanQuarantineMetadata?) : PlanReportResult
}

/** Local, synchronous repository. All successful mutations are durable before they return. */
class DayPlanRepository(
    private val backend: PlanStateBackend,
    private val clock: PlanClock,
    private val engine: PlanRevisionEngine = PlanRevisionEngine(),
) {
    fun load(): PlanLoadResult = when (val loaded = readDocument()) {
        InternalRead.Missing -> PlanLoadResult.Missing
        is InternalRead.Loaded -> PlanLoadResult.Loaded(loaded.document.store.dayPlan)
        is InternalRead.Corrupt -> PlanLoadResult.Corrupt(loaded.reason, loaded.quarantine)
    }

    fun create(plan: DayPlan): PlanWriteResult {
        if (plan.version != 1) return PlanWriteResult.Rejected(PlanRejectionReason.NON_MONOTONE_VERSION)
        return when (val loaded = readDocument()) {
            InternalRead.Missing -> persist(PlanV4StoreDocument(PlanStoreDocument(plan))).asWriteResult()
            is InternalRead.Loaded -> PlanWriteResult.Rejected(PlanRejectionReason.PLAN_ALREADY_EXISTS)
            is InternalRead.Corrupt -> PlanWriteResult.Corrupt(loaded.reason, loaded.quarantine)
        }
    }

    fun save(plan: DayPlan): PlanWriteResult = when (val loaded = readDocument()) {
        InternalRead.Missing -> PlanWriteResult.Rejected(PlanRejectionReason.PLAN_NOT_FOUND)
        is InternalRead.Corrupt -> PlanWriteResult.Corrupt(loaded.reason, loaded.quarantine)
        is InternalRead.Loaded -> {
            val current = loaded.document.store.dayPlan
            when {
                plan.planId != current.planId -> PlanWriteResult.Rejected(PlanRejectionReason.PLAN_ID_MISMATCH)
                plan == current -> PlanWriteResult.Saved(current)
                plan.version <= current.version -> PlanWriteResult.Rejected(PlanRejectionReason.NON_MONOTONE_VERSION)
                plan.version != current.version + 1 -> PlanWriteResult.Rejected(PlanRejectionReason.VERSION_CONFLICT)
                plan.createdAt != current.createdAt || plan.updatedAt < current.updatedAt ->
                    PlanWriteResult.Rejected(PlanRejectionReason.INVALID_CHANGES)
                !hasSameStatusProjection(current, plan) ->
                    PlanWriteResult.Rejected(PlanRejectionReason.INVALID_CHANGES)
                else -> persist(loaded.document.copy(store = loaded.document.store.copy(dayPlan = plan))).asWriteResult()
            }
        }
    }

    /** Status changes must pass through apply/report so their provenance is journaled. */
    private fun hasSameStatusProjection(before: DayPlan, after: DayPlan): Boolean =
        before.activities.associate { it.activityId to it.status } ==
            after.activities.associate { it.activityId to it.status }

    fun preview(revision: PlanRevision): PlanPreviewResult = when (val loaded = readDocument()) {
        InternalRead.Missing -> PlanPreviewResult.Rejected(PlanRejectionReason.PLAN_NOT_FOUND)
        is InternalRead.Corrupt -> PlanPreviewResult.Corrupt(loaded.reason, loaded.quarantine)
        is InternalRead.Loaded -> when (val preview = buildPreview(loaded.document.store.dayPlan, revision)) {
            is PreviewBuild.Ready -> PlanPreviewResult.Ready(preview.preview)
            is PreviewBuild.Rejected -> PlanPreviewResult.Rejected(preview.reason)
        }
    }

    fun apply(
        revision: PlanRevision,
        confirmation: PlanConfirmation,
        statusEventDraft: StatusEventDraft? = null,
    ): PlanApplyResult =
        when (val loaded = readDocument()) {
            InternalRead.Missing -> PlanApplyResult.Rejected(PlanRejectionReason.PLAN_NOT_FOUND)
            is InternalRead.Corrupt -> PlanApplyResult.Corrupt(loaded.reason, loaded.quarantine)
            is InternalRead.Loaded -> applyToDocument(loaded.document, revision, confirmation, statusEventDraft)
        }

    fun statusEvents(): List<PlanStatusEvent> = when (val loaded = readDocument()) {
        is InternalRead.Loaded -> loaded.document.statusEvents
        else -> emptyList()
    }

    fun reportActivity(request: ReportActivityRequest): PlanReportResult = when (val loaded = readDocument()) {
        InternalRead.Missing -> PlanReportResult.Rejected(PlanReportRejection.PLAN_NOT_FOUND)
        is InternalRead.Corrupt -> PlanReportResult.Corrupt(loaded.reason, loaded.quarantine)
        is InternalRead.Loaded -> reportInDocument(loaded.document, request)
    }

    private fun applyToDocument(
        document: PlanV4StoreDocument,
        revision: PlanRevision,
        confirmation: PlanConfirmation,
        statusEventDraft: StatusEventDraft?,
    ): PlanApplyResult {
        val statusChanges = revision.changes.filterIsInstance<PlanChange.StatusChanged>()
        if ((statusChanges.isNotEmpty() && statusEventDraft == null) ||
            (statusEventDraft != null && statusChanges.size != 1)
        ) return PlanApplyResult.Rejected(PlanRejectionReason.INVALID_CHANGES)
        if (statusEventDraft != null && statusChanges.singleOrNull()?.let { change ->
                PlanStatusPolicy.provenanceRejection(change.from, change.to, statusEventDraft.provenance, statusEventDraft.source) != null
            } != false
        ) return PlanApplyResult.Rejected(PlanRejectionReason.INVALID_CHANGES)
        if (statusEventDraft != null) {
            val transition = statusChanges.single()
            val activity = document.store.dayPlan.activities.firstOrNull { it.activityId == transition.activityId }
                ?: return PlanApplyResult.Rejected(PlanRejectionReason.INVALID_CHANGES)
            if (PlanStatusPolicy.rejection(
                    from = transition.from,
                    to = transition.to,
                    nowUtcMillis = clock.nowUtcMillis(),
                    schedule = activity.scheduledLocal,
                    engine = engine,
                    provenance = statusEventDraft.provenance,
                    source = statusEventDraft.source,
                ) != null
            ) return PlanApplyResult.Rejected(PlanRejectionReason.INVALID_CHANGES)
        }

        val previouslyApplied = document.store.appliedRevisions.firstOrNull { it.revisionId == revision.revisionId }
        if (previouslyApplied != null) {
            return if (sameRevisionIgnoringStatus(previouslyApplied, revision)) {
                PlanApplyResult.AlreadyApplied(
                    plan = document.store.dayPlan,
                    revision = previouslyApplied,
                    effectIds = engine.effectIds(previouslyApplied.changes, previouslyApplied.revisionId),
                )
            } else {
                PlanApplyResult.Rejected(PlanRejectionReason.REVISION_ID_CONFLICT)
            }
        }

        val preview = when (val result = buildPreview(document.store.dayPlan, revision)) {
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
        if (document.store.confirmations.any { it.confirmationId == confirmation.confirmationId }) {
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
        val statusEvent = statusEventDraft?.let { draft ->
            val transition = statusChanges.single()
            val finalActivity = preview.after.activities.first { it.activityId == transition.activityId }
            statusEventFor(
                planId = preview.after.planId,
                activityId = transition.activityId,
                continuityId = finalActivity.continuityId,
                from = transition.from,
                to = transition.to,
                draft = draft,
                atUtcMillis = now,
                planVersion = preview.after.version,
                scheduledLocal = document.store.dayPlan.activities
                    .single { it.activityId == transition.activityId }
                    .scheduledLocal,
            )
        }
        val updatedDocument = document.copy(
            store = document.store.copy(
                dayPlan = preview.after,
                appliedRevisions = document.store.appliedRevisions + appliedRevision,
                confirmations = document.store.confirmations + consumedConfirmation,
            ),
            statusEvents = document.statusEvents + listOfNotNull(statusEvent),
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

    private fun reportInDocument(
        document: PlanV4StoreDocument,
        request: ReportActivityRequest,
    ): PlanReportResult {
        val current = document.store.dayPlan
        val fingerprint = reportFingerprint(request)
        document.reportReceipts.firstOrNull { it.idempotencyKey == request.idempotencyKey }?.let { receipt ->
            return if (receipt.requestFingerprint == fingerprint) {
                PlanReportResult.Replayed(
                    plan = current,
                    revisionId = receipt.revisionId,
                    replacementActivityId = receipt.replacementActivityId,
                    planVersion = receipt.planVersion,
                    scopeHash = receipt.scopeHash,
                )
            } else {
                PlanReportResult.Rejected(PlanReportRejection.IDEMPOTENCY_CONFLICT)
            }
        }
        if (request.planId != current.planId) return PlanReportResult.Rejected(PlanReportRejection.PLAN_ID_MISMATCH)
        if (request.targetLocalDate != current.localDate) {
            return PlanReportResult.Rejected(PlanReportRejection.CROSS_DATE_UNSUPPORTED)
        }
        if (current.state == PlanState.ARCHIVED || current.state == PlanState.SUPERSEDED) {
            return PlanReportResult.Rejected(PlanReportRejection.PLAN_NOT_MODIFIABLE)
        }
        if (request.baseVersion != current.version) return PlanReportResult.Rejected(PlanReportRejection.STALE_VERSION)
        val original = current.activities.firstOrNull { it.activityId == request.activityId }
            ?: return PlanReportResult.Rejected(PlanReportRejection.ACTIVITY_NOT_FOUND)
        if (original.status in setOf(ActivityStatus.DONE, ActivityStatus.MISSED, ActivityStatus.CANCELLED, ActivityStatus.POSTPONED)) {
            return PlanReportResult.Rejected(PlanReportRejection.TERMINAL_ACTIVITY)
        }
        if (request.targetStartMinute + request.targetDurationMinutes > 1440) {
            return PlanReportResult.Rejected(PlanReportRejection.INVALID_SLOT)
        }

        val nextActivityId = UUID.randomUUID().toString()
        val revisionId = UUID.randomUUID().toString()
        val continuityId = original.continuityId ?: UUID.randomUUID().toString()
        val replacement = original.copy(
            activityId = nextActivityId,
            status = ActivityStatus.PLANNED,
            scheduledLocal = original.scheduledLocal.copy(
                localDate = current.localDate,
                startMinute = request.targetStartMinute,
                durationMinutes = request.targetDurationMinutes,
            ),
            createdRevisionId = revisionId,
            lastRevisionId = revisionId,
            actualMinutes = null,
            trainingRef = original.trainingRef?.let { reference ->
                reference.copy(activityId = if (reference.activityId == null) null else nextActivityId)
            },
            continuityId = continuityId,
            supersedesActivityId = original.activityId,
        )
        val replacementStart = replacement.scheduledLocal.startMinute
        val replacementEnd = replacementStart + replacement.scheduledLocal.durationMinutes
        val collision = current.activities
            .filter { it.activityId != original.activityId && it.status in setOf(ActivityStatus.PLANNED, ActivityStatus.IN_PROGRESS) }
            .any { existing ->
                val existingStart = existing.scheduledLocal.startMinute
                val existingEnd = existingStart + existing.scheduledLocal.durationMinutes
                replacementStart < existingEnd && existingStart < replacementEnd
            }
        if (collision) return PlanReportResult.Rejected(PlanReportRejection.CONFLICT)

        val now = clock.nowUtcMillis()
        val revision = PlanRevision(
            revisionId = revisionId,
            planId = current.planId,
            baseVersion = current.version,
            newVersion = current.version + 1,
            origin = RevisionOrigin.USER,
            changes = listOf(
                PlanChange.StatusChanged(original.activityId, original.status, ActivityStatus.POSTPONED),
                PlanChange.SlotAdded(replacement),
            ),
            summary = "Report : ${request.reason.take(220)}",
            createdAt = now,
            expiresAt = now + REPORT_CONFIRMATION_TTL_MILLIS,
            status = RevisionStatus.PROPOSED,
        )
        val appliedActivities = when (val applied = engine.applyChanges(current, revision)) {
            is ChangeApplicationResult.Applied -> applied.activities
            is ChangeApplicationResult.Rejected -> return PlanReportResult.Rejected(PlanReportRejection.INVALID_SLOT)
        }
        val updatedPlan = try {
            current.copy(
                version = revision.newVersion,
                currentRevisionId = revision.revisionId,
                state = PlanState.APPROVED,
                activities = appliedActivities,
                updatedAt = maxOf(current.updatedAt, now),
                approvedAt = maxOf(now, current.createdAt, current.approvedAt ?: current.createdAt),
            )
        } catch (_: IllegalArgumentException) {
            return PlanReportResult.Rejected(PlanReportRejection.INVALID_SLOT)
        }
        val effects = engine.effectIds(revision.changes, revision.revisionId)
            .filterNot { it == "PLAN_APPLY:${revision.revisionId}" }
        val scopeHash = engine.scopeHash(effects, revision.changes)
        val appliedRevision = revision.copy(status = RevisionStatus.APPLIED)
        val consumedConfirmation = PlanConfirmation(
            confirmationId = UUID.randomUUID().toString(),
            revisionId = revisionId,
            effectIds = effects,
            scopeHash = scopeHash,
            approvedAt = now,
            expiresAt = now + REPORT_CONFIRMATION_TTL_MILLIS,
            consumedAt = now,
        )
        val statusEvent = statusEventFor(
            planId = current.planId,
            activityId = original.activityId,
            continuityId = continuityId,
            from = original.status,
            to = ActivityStatus.POSTPONED,
            draft = StatusEventDraft(StatusProvenance.CONFIRMED, StatusSource.USER_UI, request.consentVersion),
            atUtcMillis = now,
            planVersion = updatedPlan.version,
        )
        val receipt = PlanReportReceipt(
            idempotencyKey = request.idempotencyKey,
            requestFingerprint = fingerprint,
            revisionId = revisionId,
            originalActivityId = original.activityId,
            replacementActivityId = replacement.activityId,
            planVersion = updatedPlan.version,
            scopeHash = scopeHash,
            reason = request.reason,
        )
        val updatedDocument = document.copy(
            store = document.store.copy(
                dayPlan = updatedPlan,
                appliedRevisions = document.store.appliedRevisions + appliedRevision,
                confirmations = document.store.confirmations + consumedConfirmation,
            ),
            statusEvents = document.statusEvents + statusEvent,
            reportReceipts = document.reportReceipts + receipt,
        )
        return when (persist(updatedDocument)) {
            PersistResult.Failed -> PlanReportResult.Rejected(PlanReportRejection.STORAGE_FAILURE)
            is PersistResult.Succeeded -> PlanReportResult.Reported(
                plan = updatedPlan,
                revision = appliedRevision,
                replacement = updatedPlan.activities.first { it.activityId == replacement.activityId },
                statusEvent = statusEvent,
                effectIds = effects,
                scopeHash = scopeHash,
            )
        }
    }

    private fun reportFingerprint(request: ReportActivityRequest): String {
        val canonical = listOf(
            request.planId,
            request.activityId,
            request.baseVersion.toString(),
            request.targetLocalDate,
            request.targetStartMinute.toString(),
            request.targetDurationMinutes.toString(),
            request.reason,
            request.consentVersion.toString(),
        ).joinToString("|") { value -> "${value.length}:$value" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun statusEventFor(
        planId: String,
        activityId: String,
        continuityId: String?,
        from: ActivityStatus,
        to: ActivityStatus,
        draft: StatusEventDraft,
        atUtcMillis: Long,
        planVersion: Int,
        scheduledLocal: LocalSchedule? = null,
    ) = PlanStatusEvent(
        statusEventId = UUID.randomUUID().toString(),
        planId = planId,
        activityId = activityId,
        continuityId = continuityId,
        fromStatus = from,
        toStatus = to,
        provenance = draft.provenance,
        source = draft.source,
        decidedAt = Instant.ofEpochMilli(atUtcMillis).toString(),
        observedAt = Instant.ofEpochMilli(atUtcMillis).toString(),
        planVersion = planVersion,
        consentVersion = draft.consentVersion,
        focusSessionId = draft.focusSessionId,
        slotEndAt = if (to == ActivityStatus.MISSED) {
            val schedule = requireNotNull(scheduledLocal) { "MISSED event requires its historical schedule" }
            engine.localToInstant(schedule)
                .plusSeconds(schedule.durationMinutes.toLong() * 60L)
                .toString()
        } else {
            null
        },
    )

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
                scopeHash = engine.scopeHash(effects, revision.changes),
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
            val root = JSONObject(raw)
            val version = root.opt("schema_version") as? Int
                ?: throw IllegalArgumentException("schema_version must be an integer")
            val document = when (version) {
                PlanJsonCodec.SCHEMA_VERSION -> PlanV4StoreDocument(
                    store = PlanJsonCodec.decodeStore(raw),
                    schema3Original = raw,
                )
                PlanV4JsonCodec.SCHEMA_VERSION -> PlanV4JsonCodec.decodeStore(raw)
                else -> throw IllegalArgumentException("unsupported plan schema version $version")
            }
            InternalRead.Loaded(document)
        } catch (_: Exception) {
            val quarantine = try {
                backend.quarantine(raw, "invalid or unsupported local plan schema; preserved for recovery", clock.nowUtcMillis())
            } catch (_: Exception) {
                null
            }
            InternalRead.Corrupt("invalid or unsupported local plan schema", quarantine)
        }
    }

    private fun persist(document: PlanV4StoreDocument): PersistResult = try {
        backend.write(PlanV4JsonCodec.encodeStore(document))
        PersistResult.Succeeded(document.store.dayPlan)
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
        data class Loaded(val document: PlanV4StoreDocument) : InternalRead
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

    private companion object {
        const val REPORT_CONFIRMATION_TTL_MILLIS = 60_000L
    }
}
