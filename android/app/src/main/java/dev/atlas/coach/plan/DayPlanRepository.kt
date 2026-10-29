package dev.atlas.coach.plan

import dev.atlas.coach.plan.v4.PlanReportReceipt
import dev.atlas.coach.plan.v4.PlanV4JsonCodec
import dev.atlas.coach.plan.v4.PlanV4StoreDocument
import dev.atlas.coach.plan.v5.PlanV5JsonCodec
import dev.atlas.coach.plan.v5.PlanV5StoreDocument
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
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

    @Synchronized
    fun create(plan: DayPlan): PlanWriteResult = synchronized(backend.transactionLock) {
        createTransaction(plan)
    }

    private fun createTransaction(plan: DayPlan): PlanWriteResult {
        if (plan.version != 1) return PlanWriteResult.Rejected(PlanRejectionReason.NON_MONOTONE_VERSION)
        return when (val loaded = readDocument()) {
            InternalRead.Missing -> persist(
                PlanV5StoreDocument.fromV4(PlanV4StoreDocument(PlanStoreDocument(plan))),
            ).asWriteResult()
            is InternalRead.Loaded -> PlanWriteResult.Rejected(PlanRejectionReason.PLAN_ALREADY_EXISTS)
            is InternalRead.Corrupt -> PlanWriteResult.Corrupt(loaded.reason, loaded.quarantine)
        }
    }

    @Synchronized
    fun save(plan: DayPlan): PlanWriteResult = synchronized(backend.transactionLock) {
        saveTransaction(plan)
    }

    private fun saveTransaction(plan: DayPlan): PlanWriteResult = when (val loaded = readDocument()) {
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
                else -> persist(
                    loaded.document.copy(
                        store = loaded.document.store.copy(dayPlan = plan),
                        a1State = reconcileA1Policies(
                            state = loaded.document.a1State,
                            before = current,
                            after = plan,
                            migrateNewActivities = loaded.document.usesV5Envelope,
                        ),
                    ),
                ).asWriteResult()
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

    @Synchronized
    fun apply(
        revision: PlanRevision,
        confirmation: PlanConfirmation,
        statusEventDraft: StatusEventDraft? = null,
    ): PlanApplyResult = synchronized(backend.transactionLock) {
        applyTransaction(revision, confirmation, statusEventDraft)
    }

    private fun applyTransaction(
        revision: PlanRevision,
        confirmation: PlanConfirmation,
        statusEventDraft: StatusEventDraft?,
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

    @Synchronized
    fun reportActivity(request: ReportActivityRequest): PlanReportResult = synchronized(backend.transactionLock) {
        reportActivityTransaction(request)
    }

    private fun reportActivityTransaction(request: ReportActivityRequest): PlanReportResult = when (val loaded = readDocument()) {
        InternalRead.Missing -> PlanReportResult.Rejected(PlanReportRejection.PLAN_NOT_FOUND)
        is InternalRead.Corrupt -> PlanReportResult.Corrupt(loaded.reason, loaded.quarantine)
        is InternalRead.Loaded -> reportInDocument(loaded.document, request)
    }

    fun a1Consent(): A1Consent? = when (val loaded = readDocument()) {
        is InternalRead.Loaded -> loaded.document.a1State.consent
        else -> null
    }

    /** Records an explicit A1 consent decision, separately from all sharing and agent mandates. */
    @Synchronized
    fun setA1Consent(enabled: Boolean): A1SettingsResult = synchronized(backend.transactionLock) {
        setA1ConsentTransaction(enabled)
    }

    private fun setA1ConsentTransaction(enabled: Boolean): A1SettingsResult {
        return when (val loaded = readDocument()) {
            InternalRead.Missing -> A1SettingsResult.Rejected(PlanRejectionReason.PLAN_NOT_FOUND)
            is InternalRead.Corrupt -> A1SettingsResult.Corrupt(loaded.reason, loaded.quarantine)
            is InternalRead.Loaded -> {
                val current = loaded.document
                val now = clock.nowUtcMillis()
                val nextConsent = try {
                    A1Consent(
                        version = Math.addExact(current.a1State.consent.version, 1),
                        enabled = enabled,
                        updatedAtUtcMillis = now,
                    )
                } catch (_: Exception) {
                    return A1SettingsResult.Rejected(PlanRejectionReason.INVALID_CHANGES)
                }
                val migrated = current.a1State.migrateLegacyActivities(current.store.dayPlan).copy(consent = nextConsent)
                val updated = current.copy(a1State = migrated, usesV5Envelope = true)
                when (persist(updated)) {
                    PersistResult.Failed -> A1SettingsResult.Rejected(PlanRejectionReason.STORAGE_FAILURE)
                    is PersistResult.Succeeded -> A1SettingsResult.Updated(nextConsent)
                }
            }
        }
    }

    /** Classification and unlocking are explicit; titles and notes are never inspected. */
    @Synchronized
    fun setA1ActivityPolicy(policy: A1ActivityPolicy): A1PolicyUpdateResult = synchronized(backend.transactionLock) {
        setA1ActivityPolicyTransaction(policy)
    }

    private fun setA1ActivityPolicyTransaction(policy: A1ActivityPolicy): A1PolicyUpdateResult {
        return when (val loaded = readDocument()) {
            InternalRead.Missing -> A1PolicyUpdateResult.Rejected(PlanRejectionReason.PLAN_NOT_FOUND)
            is InternalRead.Corrupt -> A1PolicyUpdateResult.Corrupt(loaded.reason, loaded.quarantine)
            is InternalRead.Loaded -> {
                val current = loaded.document
                if (current.store.dayPlan.activities.none { it.activityId == policy.activityId }) {
                    return A1PolicyUpdateResult.Rejected(PlanRejectionReason.INVALID_CHANGES)
                }
                val migrated = current.a1State.migrateLegacyActivities(current.store.dayPlan).let { state ->
                    state.copy(activityPolicies = state.activityPolicies + (policy.activityId to policy))
                }
                when (persist(current.copy(a1State = migrated, usesV5Envelope = true))) {
                    PersistResult.Failed -> A1PolicyUpdateResult.Rejected(PlanRejectionReason.STORAGE_FAILURE)
                    is PersistResult.Succeeded -> A1PolicyUpdateResult.Updated(policy)
                }
            }
        }
    }

    /**
     * Applies one user-approved local revision. The model supplied no trusted diff: it is rebuilt
     * from the activity payload and current plan immediately before the single durable write.
     */
    @Synchronized
    fun applyA1(proposal: A1RevisionProposal, userApproved: Boolean): A1ApplyResult = synchronized(backend.transactionLock) {
        applyA1Transaction(proposal, userApproved)
    }

    private fun applyA1Transaction(proposal: A1RevisionProposal, userApproved: Boolean): A1ApplyResult {
        if (!userApproved) return A1ApplyResult.Cancelled
        val initiallyLoaded = when (val loaded = readDocument()) {
            InternalRead.Missing -> return A1ApplyResult.Rejected(A1PolicyRejection.PLAN_NOT_FOUND)
            is InternalRead.Corrupt -> return A1ApplyResult.Corrupt(loaded.reason, loaded.quarantine)
            is InternalRead.Loaded -> loaded.document
        }
        val initialState = initiallyLoaded.a1State
        val fingerprint = try {
            A1BoundedRevisionPolicy.fingerprint(proposal)
        } catch (_: Exception) {
            return A1ApplyResult.Rejected(A1PolicyRejection.INVALID_PROPOSAL)
        }
        initialState.receipts.firstOrNull { it.proposalId == proposal.proposalId }?.let { receipt ->
            return if (receipt.requestFingerprint == fingerprint) {
                A1ApplyResult.AlreadyApplied(initiallyLoaded.store.dayPlan, receipt)
            } else {
                A1ApplyResult.Rejected(A1PolicyRejection.IDEMPOTENCY_CONFLICT)
            }
        }
        if (initialState.receipts.any { it.revisionId == proposal.revisionId }) {
            return A1ApplyResult.Rejected(A1PolicyRejection.REVISION_ID_CONFLICT)
        }

        val firstValidation = A1BoundedRevisionPolicy.validate(
            current = initiallyLoaded.store.dayPlan,
            proposal = proposal,
            state = initialState,
            nowUtcMillis = clock.nowUtcMillis(),
            engine = engine,
        )
        if (firstValidation !is A1ValidationResult.Valid) {
            return A1ApplyResult.Rejected((firstValidation as A1ValidationResult.Rejected).reason)
        }

        // Re-read consent, plan version, policies, receipts and quota at the commit boundary.
        val latest = when (val loaded = readDocument()) {
            InternalRead.Missing -> return A1ApplyResult.Rejected(A1PolicyRejection.PLAN_NOT_FOUND)
            is InternalRead.Corrupt -> return A1ApplyResult.Corrupt(loaded.reason, loaded.quarantine)
            is InternalRead.Loaded -> loaded.document
        }
        if (!latest.a1State.consent.enabled) return A1ApplyResult.Rejected(A1PolicyRejection.CONSENT_DISABLED)
        if (latest.a1State.consent.version != proposal.consentVersion) {
            return A1ApplyResult.Rejected(A1PolicyRejection.CONSENT_VERSION_MISMATCH)
        }
        if (latest.store.dayPlan.version != proposal.baseVersion) {
            return A1ApplyResult.Rejected(A1PolicyRejection.STALE_VERSION)
        }
        if (latest != initiallyLoaded) return A1ApplyResult.Rejected(A1PolicyRejection.STALE_STATE)
        val commitNow = clock.nowUtcMillis()
        val finalValidation = A1BoundedRevisionPolicy.validate(
            current = latest.store.dayPlan,
            proposal = proposal,
            state = latest.a1State,
            nowUtcMillis = commitNow,
            engine = engine,
        )
        if (finalValidation !is A1ValidationResult.Valid) {
            return A1ApplyResult.Rejected((finalValidation as A1ValidationResult.Rejected).reason)
        }
        val validated = finalValidation.revision
        val currentPlan = latest.store.dayPlan
        val updatedActivities = when (val applied = engine.applyChanges(currentPlan, validated.revision)) {
            is ChangeApplicationResult.Applied -> applied.activities
            is ChangeApplicationResult.Rejected -> return A1ApplyResult.Rejected(A1PolicyRejection.INVALID_PROPOSAL)
        }
        val updatedPlan = try {
            currentPlan.copy(
                version = validated.revision.newVersion,
                currentRevisionId = validated.revision.revisionId,
                activities = updatedActivities,
                updatedAt = maxOf(currentPlan.updatedAt, commitNow),
            )
        } catch (_: IllegalArgumentException) {
            return A1ApplyResult.Rejected(A1PolicyRejection.INVALID_PROPOSAL)
        }
        val receipt = try {
            A1RevisionReceipt(
                proposalId = proposal.proposalId,
                revisionId = validated.revision.revisionId,
                requestFingerprint = validated.requestFingerprint,
                planId = currentPlan.planId,
                versionBefore = currentPlan.version,
                versionAfter = updatedPlan.version,
                diff = validated.diff,
                effectIds = validated.effectIds,
                scopeHash = validated.scopeHash,
                consentVersion = latest.a1State.consent.version,
                localDate = validated.quotaLocalDate,
                quotaUsed = validated.quotaUsedAfterCommit,
                appliedAtUtcMillis = commitNow,
                undoUntilUtcMillis = Math.addExact(commitNow, A1BoundedRevisionPolicy.UNDO_WINDOW_MILLIS),
            )
        } catch (_: Exception) {
            return A1ApplyResult.Rejected(A1PolicyRejection.INVALID_PROPOSAL)
        }
        val nextA1 = latest.a1State.migrateLegacyActivities(currentPlan).copy(
            quotaByLocalDate = latest.a1State.quotaByLocalDate +
                (validated.quotaLocalDate to validated.quotaUsedAfterCommit),
            receipts = latest.a1State.receipts + receipt,
        )
        val updatedDocument = latest.copy(
            store = latest.store.copy(dayPlan = updatedPlan),
            a1State = nextA1,
            usesV5Envelope = true,
        )
        return when (persist(updatedDocument)) {
            PersistResult.Failed -> A1ApplyResult.Rejected(A1PolicyRejection.STORAGE_FAILURE)
            is PersistResult.Succeeded -> A1ApplyResult.Applied(updatedPlan, receipt)
        }
    }

    /** Undo is local and user initiated, creates a new version, and never refunds quota. */
    @Synchronized
    fun undoA1(proposalId: String): A1UndoResult = synchronized(backend.transactionLock) {
        undoA1Transaction(proposalId)
    }

    private fun undoA1Transaction(proposalId: String): A1UndoResult {
        val loaded = when (val result = readDocument()) {
            InternalRead.Missing -> return A1UndoResult.Rejected(A1UndoRejection.RECEIPT_NOT_FOUND)
            is InternalRead.Corrupt -> return A1UndoResult.Corrupt(result.reason, result.quarantine)
            is InternalRead.Loaded -> result.document
        }
        val receiptIndex = loaded.a1State.receipts.indexOfFirst { it.proposalId == proposalId }
        if (receiptIndex < 0) return A1UndoResult.Rejected(A1UndoRejection.RECEIPT_NOT_FOUND)
        val receipt = loaded.a1State.receipts[receiptIndex]
        if (receipt.undoRevisionId != null) return A1UndoResult.Rejected(A1UndoRejection.ALREADY_UNDONE)
        val now = clock.nowUtcMillis()
        if (now >= receipt.undoUntilUtcMillis) return A1UndoResult.Rejected(A1UndoRejection.UNDO_EXPIRED)
        val current = loaded.store.dayPlan
        if (current.version != receipt.versionAfter || current.currentRevisionId != receipt.revisionId) {
            return A1UndoResult.Rejected(A1UndoRejection.VERSION_CHANGED)
        }
        val byId = current.activities.associateBy { it.activityId }
        if (receipt.diff.any { move ->
                val activity = byId[move.activityId]
                activity == null || activity.scheduledLocal != move.to || activity.status != ActivityStatus.PLANNED
            }
        ) return A1UndoResult.Rejected(A1UndoRejection.CURRENT_PLAN_MISMATCH)

        val undoRevisionId = UUID.randomUUID().toString()
        val proposed = current.activities.map { activity ->
            val move = receipt.diff.firstOrNull { it.activityId == activity.activityId }
            if (move == null) activity else activity.copy(scheduledLocal = move.from)
        }
        val undoRevision = try {
            engine.createRevision(
                current = current,
                proposedActivities = proposed,
                revisionId = undoRevisionId,
                origin = RevisionOrigin.USER,
                summary = "Undo A1 local move",
                createdAt = now,
            )
        } catch (_: Exception) {
            return A1UndoResult.Rejected(A1UndoRejection.CURRENT_PLAN_MISMATCH)
        }
        val activities = when (val result = engine.applyChanges(current, undoRevision)) {
            is ChangeApplicationResult.Applied -> result.activities
            is ChangeApplicationResult.Rejected -> return A1UndoResult.Rejected(A1UndoRejection.CURRENT_PLAN_MISMATCH)
        }
        val updatedPlan = current.copy(
            version = undoRevision.newVersion,
            currentRevisionId = undoRevision.revisionId,
            activities = activities,
            updatedAt = maxOf(current.updatedAt, now),
        )
        val updatedReceipt = receipt.copy(
            undoRevisionId = undoRevision.revisionId,
            undoVersion = updatedPlan.version,
            undoneAtUtcMillis = now,
        )
        val updatedReceipts = loaded.a1State.receipts.toMutableList().also { it[receiptIndex] = updatedReceipt }
        val updatedDocument = loaded.copy(
            store = loaded.store.copy(dayPlan = updatedPlan),
            a1State = loaded.a1State.copy(receipts = updatedReceipts),
            usesV5Envelope = true,
        )
        return when (persist(updatedDocument)) {
            PersistResult.Failed -> A1UndoResult.Rejected(A1UndoRejection.STORAGE_FAILURE)
            is PersistResult.Succeeded -> A1UndoResult.Undone(updatedPlan, updatedReceipt)
        }
    }

    private fun applyToDocument(
        document: PlanV5StoreDocument,
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
            a1State = reconcileA1Policies(
                state = document.a1State,
                before = document.store.dayPlan,
                after = preview.after,
                migrateNewActivities = document.usesV5Envelope,
            ),
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
        document: PlanV5StoreDocument,
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
            a1State = reconcileA1Policies(
                state = document.a1State,
                before = current,
                after = updatedPlan,
                migrateNewActivities = document.usesV5Envelope,
            ),
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
            InternalRead.Loaded(PlanV5JsonCodec.decodeStore(raw))
        } catch (_: Exception) {
            val quarantine = try {
                backend.quarantine(raw, "invalid or unsupported local plan schema; preserved for recovery", clock.nowUtcMillis())
            } catch (_: Exception) {
                null
            }
            InternalRead.Corrupt("invalid or unsupported local plan schema", quarantine)
        }
    }

    private fun persist(document: PlanV5StoreDocument): PersistResult = try {
        val hasA1State = document.usesV5Envelope ||
            document.a1State.consent.version > 0 ||
            document.a1State.activityPolicies.isNotEmpty() ||
            document.a1State.quotaByLocalDate.isNotEmpty() ||
            document.a1State.receipts.isNotEmpty()
        if (hasA1State) {
            backend.write(PlanV5JsonCodec.encodeStore(document.copy(usesV5Envelope = true)))
        } else {
            backend.write(PlanV4JsonCodec.encodeStore(document.asV4()))
        }
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

    private fun reconcileA1Policies(
        state: A1PersistentState,
        before: DayPlan,
        after: DayPlan,
        migrateNewActivities: Boolean,
    ): A1PersistentState {
        if (!migrateNewActivities && state.activityPolicies.isEmpty()) return state
        val beforeById = before.activities.associateBy { it.activityId }
        val afterIds = after.activities.map { it.activityId }.toSet()
        val policies = state.activityPolicies.filterKeys { it in afterIds }.toMutableMap()
        after.activities.forEach { activity ->
            val previous = beforeById[activity.activityId]
            val contentChanged = previous != null && previous.copy(
                scheduledLocal = activity.scheduledLocal,
                status = activity.status,
                createdRevisionId = activity.createdRevisionId,
                lastRevisionId = activity.lastRevisionId,
            ) != activity
            if ((previous == null || contentChanged) && (migrateNewActivities || activity.activityId in policies)) {
                policies[activity.activityId] = A1ActivityPolicy.legacyProtected(activity.activityId)
            }
        }
        return state.copy(activityPolicies = policies)
    }

    private sealed interface InternalRead {
        data object Missing : InternalRead
        data class Loaded(val document: PlanV5StoreDocument) : InternalRead
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
