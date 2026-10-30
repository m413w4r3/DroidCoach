package dev.atlas.coach.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.ActivityStatus
import dev.atlas.coach.plan.A1ActivityPolicy
import dev.atlas.coach.plan.A1ApplyResult
import dev.atlas.coach.plan.A1BoundedRevisionPolicy
import dev.atlas.coach.plan.A1Consent
import dev.atlas.coach.plan.A1MoveDiff
import dev.atlas.coach.plan.A1PolicyUpdateResult
import dev.atlas.coach.plan.A1RevisionProposal
import dev.atlas.coach.plan.A1PolicyRejection
import dev.atlas.coach.plan.A1RevisionReceipt
import dev.atlas.coach.plan.A1SettingsResult
import dev.atlas.coach.plan.A1UndoRejection
import dev.atlas.coach.plan.A1UndoResult
import dev.atlas.coach.plan.A1ValidationResult
import dev.atlas.coach.plan.DayPlan
import dev.atlas.coach.plan.DayPlanRepository
import dev.atlas.coach.plan.FilePlanBackend
import dev.atlas.coach.plan.LocalSchedule
import dev.atlas.coach.plan.PlanActivity
import dev.atlas.coach.plan.PlanApplyResult
import dev.atlas.coach.plan.PlanClock
import dev.atlas.coach.plan.PlanConfirmation
import dev.atlas.coach.plan.PlanPreviewResult
import dev.atlas.coach.plan.PlanRejectionReason
import dev.atlas.coach.plan.PlanLoadResult
import dev.atlas.coach.plan.PlanOrigin
import dev.atlas.coach.plan.PlanPreview
import dev.atlas.coach.plan.PlanRevision
import dev.atlas.coach.plan.PlanRevisionApiRequest
import dev.atlas.coach.plan.PlanRevisionApiResult
import dev.atlas.coach.plan.PlanRevisionEngine
import dev.atlas.coach.plan.PlanRevisionMapper
import dev.atlas.coach.plan.PlanRevisionMappingResult
import dev.atlas.coach.plan.PlanRevisionProposalClient
import dev.atlas.coach.plan.PlanState
import dev.atlas.coach.plan.PlanStatusPolicy
import dev.atlas.coach.plan.PlanWriteResult
import dev.atlas.coach.plan.Priority
import dev.atlas.coach.plan.RevisionOrigin
import dev.atlas.coach.plan.ReportActivityRequest
import dev.atlas.coach.plan.PlanReportResult
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4Ack
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4AckResultCode
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4AckState
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4ApplicationProof
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4ConfirmationMode
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4EntryView
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4LocalStore
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4Proposal
import dev.atlas.coach.sync.agent.inbox.v4.InboxV4ProposalState
import dev.atlas.coach.plan.StatusEventDraft
import dev.atlas.coach.plan.StatusProvenance
import dev.atlas.coach.plan.StatusSource
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.plan.v5.PlanV5JsonCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.time.Instant

data class A1InboxReview(
    val proposalBaseVersion: Long,
    val currentPlanVersion: Int?,
    val consent: A1Consent,
    val diff: List<A1MoveDiff>,
    val quotaLocalDate: String,
    val quotaUsed: Int,
    val quotaLimit: Int = A1BoundedRevisionPolicy.MAX_APPLICATIONS_PER_LOCAL_DATE,
    val rejection: A1PolicyRejection? = null,
) {
    val valid: Boolean get() = rejection == null && diff.isNotEmpty()
}

/**
 * UI-facing owner of the local day plan. It never talks to the network and never
 * authorizes an effect: a local revision is previewed, then confirmed by an explicit
 * user action. Each local date has its own atomic document, so a new day cannot
 * overwrite yesterday's approved plan.
 */
class PlanController(
    directory: File,
    private val clock: PlanClock = PlanClock { System.currentTimeMillis() },
    private val zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() },
    private val todayProvider: () -> LocalDate = { LocalDate.now(zoneIdProvider()) },
    private val engine: PlanRevisionEngine = PlanRevisionEngine(),
    private val agentRevisionMapper: PlanRevisionMapper = PlanRevisionMapper(engine),
    inboxRepository: dev.atlas.coach.sync.agent.inbox.v4.InboxV4Repository? = null,
) {
    private val directory = directory
    private val inboxRepository = inboxRepository ?: InboxV4LocalStore.repository(
        File(directory, InboxV4LocalStore.FILE_NAME),
    )
    private var repository: DayPlanRepository? = null
    private var loadedDate: String? = null
    private var pendingRevision: PlanRevision? = null
    private var pendingPreview: PlanPreview? = null
    private var pendingStatusEventDraft: StatusEventDraft? = null

    var plan by mutableStateOf<DayPlan?>(null)
        private set
    var preview by mutableStateOf<PlanPreview?>(null)
        private set
    var revision by mutableStateOf(0)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var corruption by mutableStateOf<String?>(null)
        private set
    var lastAppliedSummary by mutableStateOf<String?>(null)
        private set
    var inboxEntries by mutableStateOf(this.inboxRepository.all())
        private set
    var inboxPendingAckCount by mutableStateOf(this.inboxRepository.pendingAcks().size)
        private set

    init {
        refresh()
    }

    val today: String get() = todayProvider().toString()
    val timezone: String get() = zoneIdProvider().id

    /** Creates an independent controller pinned to [date] in the same local-plan store. */
    fun forDate(date: LocalDate): PlanController = PlanController(
        directory = directory,
        clock = clock,
        zoneIdProvider = { zoneIdProvider() },
        todayProvider = { date },
        engine = engine,
        agentRevisionMapper = agentRevisionMapper,
        inboxRepository = inboxRepository,
    )

    /** Reads a saved plan for [date] without changing this controller or its repository state. */
    fun planForDate(date: LocalDate): DayPlan? {
        val file = File(directory, "day-plan-$date.json")
        if (!file.isFile) return null
        val rawDocument = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return runCatching { PlanV5JsonCodec.decodeStore(rawDocument).store.dayPlan }
            .getOrNull()
            ?.takeIf { it.localDate == date.toString() }
    }

    /** Reloads the plan document and rotates to the current local date when needed. */
    fun refresh() {
        val date = today
        if (loadedDate != date) {
            loadedDate = date
            repository = DayPlanRepository(
                backend = FilePlanBackend(File(directory, "day-plan-$date.json")),
                clock = clock,
                engine = engine,
            )
            plan = null
            clearPreview()
            corruption = null
            lastAppliedSummary = null
        }
        when (val loaded = requireRepository().load()) {
            is PlanLoadResult.Loaded -> {
                plan = loaded.plan
                corruption = null
            }
            PlanLoadResult.Missing -> {
                plan = null
                corruption = null
            }
            is PlanLoadResult.Corrupt -> {
                plan = null
                corruption = loaded.reason
            }
        }
        refreshInbox()
        revision++
    }

    fun refreshInbox() {
        inboxEntries = inboxRepository.all()
        inboxPendingAckCount = inboxRepository.pendingAcks().size
    }

    fun a1Consent(): A1Consent? = requireRepository().a1Consent()

    fun a1ActivityPolicy(activityId: String): A1ActivityPolicy? {
        val current = plan ?: return null
        if (current.activities.none { it.activityId == activityId }) return null
        val document = runCatching {
            PlanV5JsonCodec.decodeStore(File(directory, "day-plan-${current.localDate}.json").readText(Charsets.UTF_8))
        }.getOrNull() ?: return A1ActivityPolicy.legacyProtected(activityId)
        return document.a1State.policyFor(activityId)
    }

    fun setA1Consent(enabled: Boolean, inboxConsentVersion: Long = 0L): Boolean = synchronized(inboxRepository) {
        val currentConsent = requireRepository().a1Consent()
        if (enabled && currentConsent?.enabled == false &&
            !terminalizePendingA1InboxProposals(inboxConsentVersion)
        ) {
            revision++
            return@synchronized fail("Consentement A1 non accordé : des propositions A1 en attente doivent d'abord être clôturées.")
        }
        when (val result = requireRepository().setA1Consent(enabled)) {
            is A1SettingsResult.Updated -> {
                val pendingClosed = enabled || terminalizePendingA1InboxProposals(inboxConsentVersion)
                message = if (enabled) {
                    "Consentement A1 accordé · version ${result.consent.version}. Chaque proposition reste à confirmer."
                } else if (pendingClosed) {
                    "Consentement A1 révoqué · version ${result.consent.version}. Les propositions A1 en attente sont invalidées."
                } else {
                    "Consentement A1 révoqué · version ${result.consent.version}. Certaines propositions restent visibles faute d'ACK terminal; A1 ne pourra pas être réactivé avant leur clôture."
                }
                revision++
                true
            }
            is A1SettingsResult.Rejected -> fail("Le choix A1 n'a pas été enregistré (${result.reason}).")
            is A1SettingsResult.Corrupt -> {
                corruption = result.reason
                fail("Le stockage local A1 est illisible; aucun choix n'a été enregistré.")
            }
        }
    }

    private fun terminalizePendingA1InboxProposals(consentVersion: Long): Boolean {
        val pending = inboxRepository.all().filter { entry ->
            entry.proposal.confirmationMode == InboxV4ConfirmationMode.A1_ANDROID_ELIGIBLE_TO_CHECK &&
                entry.proposal.state in REVIEWABLE_INBOX_STATES
        }
        if (pending.isEmpty()) return true
        if (consentVersion < 1L) return false

        var allTerminalized = true
        pending.forEach { entry ->
            if (!appendInboxAck(
                    entry,
                    consentVersion,
                    InboxV4AckState.VALIDATION_REJECTED,
                    InboxV4AckResultCode.VALIDATION_FAILED,
                    null,
                )) {
                allTerminalized = false
            }
        }
        return allTerminalized
    }

    fun setA1ActivityPolicy(policy: A1ActivityPolicy): Boolean = when (
        val result = requireRepository().setA1ActivityPolicy(policy)
    ) {
        is A1PolicyUpdateResult.Updated -> {
            message = "Classement A1 enregistré pour cette activité."
            revision++
            true
        }
        is A1PolicyUpdateResult.Rejected -> fail("Le classement A1 n'a pas été enregistré (${result.reason}).")
        is A1PolicyUpdateResult.Corrupt -> {
            corruption = result.reason
            fail("Le stockage local A1 est illisible; aucun classement n'a été enregistré.")
        }
    }

    /** Builds a fresh, policy-validated A1 diff from an inbox payload and the current V5 document. */
    fun inspectA1InboxProposal(proposalId: String): A1InboxReview? {
        val entry = inboxRepository.get(proposalId) ?: return null
        val proposal = entry.proposal.takeIf {
            it.confirmationMode == InboxV4ConfirmationMode.A1_ANDROID_ELIGIBLE_TO_CHECK
        } ?: return null
        val current = when (val loaded = requireRepository().load()) {
            is PlanLoadResult.Loaded -> loaded.plan.also { plan = it }
            else -> return null
        }
        val document = runCatching {
            PlanV5JsonCodec.decodeStore(File(directory, "day-plan-${current.localDate}.json").readText(Charsets.UTF_8))
        }.getOrNull() ?: return null
        val state = document.a1State
        val localDate = Instant.ofEpochMilli(clock.nowUtcMillis())
            .atZone(ZoneId.of(current.timezone)).toLocalDate().toString()
        val quotaUsed = state.quotaByLocalDate[localDate] ?: 0
        if (current.state != PlanState.APPROVED || proposal.conflicts.isNotEmpty()) {
            return A1InboxReview(
                proposalBaseVersion = proposal.baseVersion,
                currentPlanVersion = current.version,
                consent = state.consent,
                diff = emptyList(),
                quotaLocalDate = localDate,
                quotaUsed = quotaUsed,
                rejection = if (proposal.conflicts.isNotEmpty()) {
                    A1PolicyRejection.INVALID_PROPOSAL
                } else {
                    A1PolicyRejection.PLAN_NOT_MODIFIABLE
                },
            )
        }
        val proposedActivities = runCatching {
            mapInboxActivities(proposal, current, PlanRevisionMapper.revisionId(proposal.proposalId), a1Only = true)
        }.getOrNull() ?: return A1InboxReview(
            proposalBaseVersion = proposal.baseVersion,
            currentPlanVersion = current.version,
            consent = state.consent,
            diff = emptyList(),
            quotaLocalDate = localDate,
            quotaUsed = quotaUsed,
            rejection = A1PolicyRejection.INVALID_PROPOSAL,
        )
        val a1Proposal = runCatching {
            A1RevisionProposal(
                proposalId = proposal.proposalId,
                revisionId = PlanRevisionMapper.revisionId(proposal.proposalId),
                planId = proposal.planId,
                baseVersion = proposal.baseVersion.toInt(),
                createdAtUtcMillis = proposal.createdAt.toEpochMilli(),
                expiresAtUtcMillis = proposal.expiresAt.toEpochMilli(),
                consentVersion = state.consent.version,
                proposedActivities = proposedActivities,
            )
        }.getOrNull() ?: return A1InboxReview(
            proposalBaseVersion = proposal.baseVersion,
            currentPlanVersion = current.version,
            consent = state.consent,
            diff = emptyList(),
            quotaLocalDate = localDate,
            quotaUsed = quotaUsed,
            rejection = A1PolicyRejection.INVALID_PROPOSAL,
        )
        return when (val validation = A1BoundedRevisionPolicy.validate(
            current = current,
            proposal = a1Proposal,
            state = state,
            nowUtcMillis = clock.nowUtcMillis(),
            engine = engine,
        )) {
            is A1ValidationResult.Valid -> A1InboxReview(
                proposalBaseVersion = proposal.baseVersion,
                currentPlanVersion = current.version,
                consent = state.consent,
                diff = validation.revision.diff,
                quotaLocalDate = validation.revision.quotaLocalDate,
                quotaUsed = validation.revision.quotaUsedAfterCommit - 1,
            )
            is A1ValidationResult.Rejected -> A1InboxReview(
                proposalBaseVersion = proposal.baseVersion,
                currentPlanVersion = current.version,
                consent = state.consent,
                diff = emptyList(),
                quotaLocalDate = localDate,
                quotaUsed = quotaUsed,
                rejection = validation.reason,
            )
        }
    }

    /** The undo control is exposed only while its receipt still matches the exact current plan. */
    fun activeA1UndoReceipt(): A1RevisionReceipt? {
        val current = plan ?: return null
        val document = runCatching {
            PlanV5JsonCodec.decodeStore(File(directory, "day-plan-${current.localDate}.json").readText(Charsets.UTF_8))
        }.getOrNull() ?: return null
        val now = clock.nowUtcMillis()
        return document.a1State.receipts.asSequence()
            .sortedByDescending { it.appliedAtUtcMillis }
            .firstOrNull { receipt ->
                receipt.undoRevisionId == null && now < receipt.undoUntilUtcMillis &&
                    current.version == receipt.versionAfter && current.currentRevisionId == receipt.revisionId
            }
    }

    /** User-triggered undo delegates the 15-minute and exact-version checks to the durable engine. */
    fun undoA1(proposalId: String): Boolean = when (val result = requireRepository().undoA1(proposalId)) {
        is A1UndoResult.Undone -> {
            plan = result.plan
            message = "Révision A1 annulée. Le programme est maintenant en version ${result.plan.version}; le quota reste consommé."
            revision++
            true
        }
        is A1UndoResult.Rejected -> {
            plan = (requireRepository().load() as? PlanLoadResult.Loaded)?.plan
            revision++
            fail(when (result.reason) {
                A1UndoRejection.UNDO_EXPIRED -> "Le délai de 15 minutes pour annuler cette révision est écoulé."
                A1UndoRejection.VERSION_CHANGED, A1UndoRejection.CURRENT_PLAN_MISMATCH -> "Le programme a changé; cette révision ne peut plus être annulée."
                A1UndoRejection.ALREADY_UNDONE -> "Cette révision a déjà été annulée."
                A1UndoRejection.RECEIPT_NOT_FOUND -> "Le reçu A1 est introuvable."
                A1UndoRejection.STORAGE_FAILURE -> "L'annulation n'a pas été enregistrée durablement."
            })
        }
        is A1UndoResult.Corrupt -> {
            corruption = result.reason
            fail("Le stockage local A1 est illisible; l'annulation n'a pas été effectuée.")
        }
    }

    /** A refusal is terminal locally and its ACK is durable before transport replay. */
    fun rejectInboxProposal(proposalId: String, consentVersion: Long): Boolean {
        val entry = inboxRepository.get(proposalId) ?: return fail("Proposition inbox inconnue.")
        if (entry.proposal.state !in REVIEWABLE_INBOX_STATES) {
            return fail("Cette proposition ne peut plus être refusée depuis son état courant.")
        }
        val expired = !entry.proposal.expiresAt.isAfter(Instant.ofEpochMilli(clock.nowUtcMillis()))
        val appended = appendInboxAck(
            entry = entry,
            consentVersion = consentVersion,
            state = if (expired) InboxV4AckState.EXPIRED else InboxV4AckState.REJECTED,
            resultCode = if (expired) InboxV4AckResultCode.PROPOSAL_EXPIRED else InboxV4AckResultCode.USER_REJECTED,
            proof = null,
        )
        if (appended) message = if (expired) {
            "Proposition expirée. Aucun effet local n'a été appliqué."
        } else {
            "Proposition refusée. Le refus est conservé localement."
        }
        return appended
    }

    /** Applies only after the review dialog calls this method; all gates are checked again here. */
    fun applyInboxProposal(
        proposalId: String,
        planRevisionCapabilityEnabled: Boolean,
        dayPlanConsentGranted: Boolean,
        dayPlanConsentVersion: Long,
        // The UI always supplies CAP_A1_BOUNDED. Keep the fallback for older local callers;
        // it is never used by the interactive A1 route.
        a1CapabilityEnabled: () -> Boolean = { planRevisionCapabilityEnabled },
        currentDayPlanConsent: () -> Pair<Boolean, Long> = {
            dayPlanConsentGranted to dayPlanConsentVersion
        },
        onA1Committed: (DayPlan, A1RevisionReceipt) -> Unit = { _, _ -> },
    ): Boolean {
        val entry = inboxRepository.get(proposalId) ?: return fail("Proposition inbox inconnue.")
        val proposal = entry.proposal
        if (proposal.state !in REVIEWABLE_INBOX_STATES) {
            return fail("Cette proposition n'est plus en attente de confirmation.")
        }
        val isA1 = proposal.confirmationMode == InboxV4ConfirmationMode.A1_ANDROID_ELIGIBLE_TO_CHECK
        if (!isA1 && !planRevisionCapabilityEnabled) {
            return fail("La capability de révision du programme est désactivée.")
        }
        val initialInboxConsent = runCatching(currentDayPlanConsent).getOrDefault(false to 0L)
        if (!initialInboxConsent.first || initialInboxConsent.second < 1L) {
            return fail("Le consentement courant du programme ne permet pas cette révision.")
        }
        val current = when (val loaded = requireRepository().load()) {
            is PlanLoadResult.Loaded -> loaded.plan.also { plan = it }
            PlanLoadResult.Missing -> return rejectInboxProposalAsValidation(entry, initialInboxConsent.second)
            is PlanLoadResult.Corrupt -> {
                corruption = loaded.reason
                return recordInboxUncertain(entry, initialInboxConsent.second)
            }
        }
        val now = clock.nowUtcMillis()
        if (!proposal.expiresAt.isAfter(Instant.ofEpochMilli(now))) {
            return appendInboxAck(
                entry, initialInboxConsent.second, InboxV4AckState.EXPIRED,
                InboxV4AckResultCode.PROPOSAL_EXPIRED, null,
            )
        }
        if (current.planId != proposal.planId || current.state != PlanState.APPROVED) {
            return rejectInboxProposalAsValidation(entry, initialInboxConsent.second)
        }

        val revisionId = PlanRevisionMapper.revisionId(proposal.proposalId)
        persistedInboxProof(proposal, current)?.let { proof ->
            plan = current
            return appendAppliedInboxAck(entry, initialInboxConsent.second, proof)
        }
        if (current.currentRevisionId == revisionId && current.version.toLong() == proposal.baseVersion + 1L) {
            // A previous process may have committed the plan and stopped before ACK persistence.
            // If its atomic local receipt cannot be re-read, retain uncertainty and never apply again.
            return recordInboxUncertain(entry, initialInboxConsent.second)
        }
        if (current.version.toLong() != proposal.baseVersion) {
            return appendInboxAck(
                entry, initialInboxConsent.second, InboxV4AckState.STALE_VERSION,
                InboxV4AckResultCode.STALE_VERSION, null,
            )
        }
        if (proposal.conflicts.isNotEmpty()) return rejectInboxProposalAsValidation(entry, initialInboxConsent.second)

        if (proposal.confirmationMode == InboxV4ConfirmationMode.A1_ANDROID_ELIGIBLE_TO_CHECK) {
            if (!runCatching(a1CapabilityEnabled).getOrDefault(false)) {
                return fail("La capability A1 est désactivée. Aucun changement n'a été appliqué.")
            }
            return applyA1InboxProposal(
                entry = entry,
                current = current,
                a1CapabilityEnabled = a1CapabilityEnabled,
                currentDayPlanConsent = currentDayPlanConsent,
                onA1Committed = onA1Committed,
            )
        }
        return applyReviewedInboxRevision(entry, current, now, initialInboxConsent.second)
    }

    private fun persistedInboxProof(proposal: InboxV4Proposal, current: DayPlan): InboxV4ApplicationProof? {
        val revisionId = PlanRevisionMapper.revisionId(proposal.proposalId)
        val file = File(directory, "day-plan-${current.localDate}.json")
        val document = runCatching {
            PlanV5JsonCodec.decodeStore(file.readText(Charsets.UTF_8))
        }.getOrNull() ?: return null
        if (document.store.dayPlan != current) return null

        if (proposal.confirmationMode == InboxV4ConfirmationMode.A1_ANDROID_ELIGIBLE_TO_CHECK) {
            val receipt = document.a1State.receipts.singleOrNull { it.proposalId == proposal.proposalId }
                ?: return null
            if (receipt.planId != current.planId ||
                receipt.versionBefore.toLong() != proposal.baseVersion ||
                receipt.versionAfter != receipt.versionBefore + 1 ||
                receipt.revisionId != revisionId || receipt.versionAfter > current.version
            ) return null
            return runCatching {
                InboxV4ApplicationProof(
                    planId = receipt.planId,
                    appliedPlanVersion = receipt.versionAfter.toLong(),
                    revisionId = receipt.revisionId,
                    scopeHash = "sha256:${receipt.scopeHash}",
                    effectIds = receipt.effectIds,
                )
            }.getOrNull()
        }

        val applied = document.store.appliedRevisions.singleOrNull { it.revisionId == revisionId }
            ?: return null
        val confirmation = document.store.confirmations.singleOrNull { it.revisionId == revisionId }
            ?: return null
        if (applied.status != dev.atlas.coach.plan.RevisionStatus.APPLIED ||
            applied.planId != current.planId || applied.baseVersion.toLong() != proposal.baseVersion ||
            applied.newVersion != applied.baseVersion + 1 || applied.newVersion > current.version ||
            confirmation.consumedAt == null
        ) return null
        val effectIds = runCatching { engine.effectIds(applied.changes, revisionId) }.getOrNull() ?: return null
        if (confirmation.effectIds.sorted() != effectIds.sorted() ||
            confirmation.scopeHash != engine.scopeHash(effectIds, applied.changes)
        ) return null
        return runCatching {
            InboxV4ApplicationProof(
                planId = current.planId,
                appliedPlanVersion = applied.newVersion.toLong(),
                revisionId = revisionId,
                scopeHash = "sha256:${confirmation.scopeHash}",
                effectIds = effectIds,
            )
        }.getOrNull()
    }

    private fun applyA1InboxProposal(
        entry: InboxV4EntryView,
        current: DayPlan,
        a1CapabilityEnabled: () -> Boolean,
        currentDayPlanConsent: () -> Pair<Boolean, Long>,
        onA1Committed: (DayPlan, A1RevisionReceipt) -> Unit,
    ): Boolean {
        val proposal = entry.proposal
        if (!runCatching(a1CapabilityEnabled).getOrDefault(false)) {
            return fail("La capability A1 a été désactivée avant l'application. Aucun changement n'a été appliqué.")
        }
        val finalInboxConsent = runCatching(currentDayPlanConsent).getOrNull()
            ?: return fail("Le consentement de l'inbox n'a pas pu être vérifié. Aucun changement n'a été appliqué.")
        if (!finalInboxConsent.first || finalInboxConsent.second < 1L) {
            return fail("Le consentement de l'inbox a été retiré. Aucun changement n'a été appliqué.")
        }
        val consent = requireRepository().a1Consent()
        if (consent == null || !consent.enabled || consent.version < 1) {
            return fail("Le consentement A1 est absent ou révoqué. Aucun changement n'a été appliqué.")
        }
        val proposedActivities = try {
            mapInboxActivities(proposal, current, PlanRevisionMapper.revisionId(proposal.proposalId), a1Only = true)
        } catch (_: Exception) {
            return rejectInboxProposalAsValidation(entry, finalInboxConsent.second)
        }
        val a1Proposal = try {
            A1RevisionProposal(
                proposalId = proposal.proposalId,
                revisionId = PlanRevisionMapper.revisionId(proposal.proposalId),
                planId = proposal.planId,
                baseVersion = proposal.baseVersion.toInt(),
                createdAtUtcMillis = proposal.createdAt.toEpochMilli(),
                expiresAtUtcMillis = proposal.expiresAt.toEpochMilli(),
                consentVersion = consent.version,
                proposedActivities = proposedActivities,
            )
        } catch (_: Exception) {
            return rejectInboxProposalAsValidation(entry, finalInboxConsent.second)
        }
        // Recheck the inbox state and exact plan snapshot immediately before the durable transaction.
        val latestEntry = inboxRepository.get(proposal.proposalId)
            ?: return fail("La proposition a quitté l'inbox. Aucun changement n'a été appliqué.")
        if (latestEntry.proposal.state !in REVIEWABLE_INBOX_STATES ||
            latestEntry.proposal.copy(state = proposal.state) != proposal
        ) return fail("La proposition a changé ou a été clôturée. Aucun changement n'a été appliqué.")
        val latestPlan = when (val loaded = requireRepository().load()) {
            is PlanLoadResult.Loaded -> loaded.plan
            PlanLoadResult.Missing -> return rejectInboxProposalAsValidation(latestEntry, finalInboxConsent.second)
            is PlanLoadResult.Corrupt -> {
                corruption = loaded.reason
                return recordInboxUncertain(latestEntry, finalInboxConsent.second)
            }
        }
        if (latestPlan.version.toLong() != proposal.baseVersion) {
            return appendInboxAck(
                latestEntry, finalInboxConsent.second, InboxV4AckState.STALE_VERSION,
                InboxV4AckResultCode.STALE_VERSION, null,
            )
        }
        if (latestPlan.planId != proposal.planId || latestPlan.state != PlanState.APPROVED) {
            return rejectInboxProposalAsValidation(latestEntry, finalInboxConsent.second)
        }
        if (latestPlan != current) {
            return fail("Le programme a changé depuis la revue. Rouvrez le diff avant de confirmer.")
        }
        // Recheck all user-controlled gates immediately before entering the durable A1 transaction.
        if (!runCatching(a1CapabilityEnabled).getOrDefault(false)) {
            return fail("La capability A1 a été désactivée avant l'application. Aucun changement n'a été appliqué.")
        }
        val commitInboxConsent = runCatching(currentDayPlanConsent).getOrNull()
            ?: return fail("Le consentement de l'inbox n'a pas pu être vérifié. Aucun changement n'a été appliqué.")
        if (!commitInboxConsent.first || commitInboxConsent.second < 1L) {
            return fail("Le consentement de l'inbox a été retiré. Aucun changement n'a été appliqué.")
        }
        val commitA1Consent = requireRepository().a1Consent()
        if (commitA1Consent == null || !commitA1Consent.enabled || commitA1Consent.version != a1Proposal.consentVersion) {
            return fail("Le consentement A1 a changé avant l'application. Aucun changement n'a été appliqué.")
        }
        return when (val result = requireRepository().applyA1(a1Proposal, userApproved = true)) {
            is A1ApplyResult.Applied -> {
                plan = result.plan
                val proof = InboxV4ApplicationProof(
                    planId = result.receipt.planId,
                    appliedPlanVersion = result.receipt.versionAfter.toLong(),
                    revisionId = result.receipt.revisionId,
                    scopeHash = "sha256:${result.receipt.scopeHash}",
                    effectIds = result.receipt.effectIds,
                )
                revision++
                runCatching { onA1Committed(result.plan, result.receipt) }
                appendAppliedInboxAck(entry, commitInboxConsent.second, proof)
            }
            is A1ApplyResult.AlreadyApplied -> {
                plan = result.plan
                val proof = InboxV4ApplicationProof(
                    planId = result.receipt.planId,
                    appliedPlanVersion = result.receipt.versionAfter.toLong(),
                    revisionId = result.receipt.revisionId,
                    scopeHash = "sha256:${result.receipt.scopeHash}",
                    effectIds = result.receipt.effectIds,
                )
                revision++
                appendAppliedInboxAck(entry, commitInboxConsent.second, proof)
            }
            A1ApplyResult.Cancelled -> fail("La révision A1 a été annulée.")
            is A1ApplyResult.Rejected -> when (result.reason) {
                A1PolicyRejection.STALE_VERSION -> appendInboxAck(
                    entry, commitInboxConsent.second, InboxV4AckState.STALE_VERSION,
                    InboxV4AckResultCode.STALE_VERSION, null,
                )
                A1PolicyRejection.PROPOSAL_EXPIRED -> appendInboxAck(
                    entry, commitInboxConsent.second, InboxV4AckState.EXPIRED,
                    InboxV4AckResultCode.PROPOSAL_EXPIRED, null,
                )
                A1PolicyRejection.STORAGE_FAILURE, A1PolicyRejection.STALE_STATE ->
                    recordInboxUncertain(entry, commitInboxConsent.second)
                A1PolicyRejection.CONSENT_DISABLED, A1PolicyRejection.CONSENT_VERSION_MISMATCH -> fail(
                    "Le consentement A1 a été retiré ou renouvelé. La proposition reste en attente et n'a pas été appliquée.",
                )
                else -> appendInboxAck(
                    entry, commitInboxConsent.second, InboxV4AckState.VALIDATION_REJECTED,
                    InboxV4AckResultCode.VALIDATION_FAILED, null,
                )
            }
            is A1ApplyResult.Corrupt -> {
                corruption = result.reason
                recordInboxUncertain(entry, commitInboxConsent.second)
            }
        }
    }

    private fun applyReviewedInboxRevision(
        entry: InboxV4EntryView,
        current: DayPlan,
        now: Long,
        consentVersion: Long,
    ): Boolean {
        val proposal = entry.proposal
        val inboxRevision = try {
            val activities = mapInboxActivities(proposal, current, PlanRevisionMapper.revisionId(proposal.proposalId))
            engine.createRevision(
                current = current,
                proposedActivities = activities,
                revisionId = PlanRevisionMapper.revisionId(proposal.proposalId),
                origin = RevisionOrigin.AGENT,
                summary = proposal.justification.take(240),
                createdAt = now,
                ttlMillis = proposal.expiresAt.toEpochMilli() - now,
            )
        } catch (_: Exception) {
            return rejectInboxProposalAsValidation(entry, consentVersion)
        }
        val preview = when (val result = requireRepository().preview(inboxRevision)) {
            is PlanPreviewResult.Ready -> result.preview
            is PlanPreviewResult.Rejected -> return when (result.reason) {
                PlanRejectionReason.STALE_REVISION, PlanRejectionReason.VERSION_CONFLICT -> appendInboxAck(
                    entry, consentVersion, InboxV4AckState.STALE_VERSION,
                    InboxV4AckResultCode.STALE_VERSION, null,
                )
                PlanRejectionReason.REVISION_EXPIRED, PlanRejectionReason.CONFIRMATION_EXPIRED -> appendInboxAck(
                    entry, consentVersion, InboxV4AckState.EXPIRED,
                    InboxV4AckResultCode.PROPOSAL_EXPIRED, null,
                )
                else -> rejectInboxProposalAsValidation(entry, consentVersion)
            }
            is PlanPreviewResult.Corrupt -> {
                corruption = result.reason
                return recordInboxUncertain(entry, consentVersion)
            }
        }
        val confirmation = try {
            PlanConfirmation(
                confirmationId = UUID.randomUUID().toString(),
                revisionId = inboxRevision.revisionId,
                effectIds = preview.effectIds,
                scopeHash = preview.scopeHash,
                approvedAt = now,
                expiresAt = now + CONFIRMATION_TTL_MILLIS,
            )
        } catch (_: Exception) {
            return rejectInboxProposalAsValidation(entry, consentVersion)
        }
        return when (val applied = requireRepository().apply(inboxRevision, confirmation)) {
            is PlanApplyResult.Applied -> {
                plan = applied.plan
                lastAppliedSummary = inboxRevision.summary
                revision++
                val proof = InboxV4ApplicationProof(
                    planId = applied.plan.planId,
                    appliedPlanVersion = applied.plan.version.toLong(),
                    revisionId = applied.revision.revisionId,
                    scopeHash = "sha256:${confirmation.scopeHash}",
                    effectIds = applied.effectIds,
                )
                appendAppliedInboxAck(entry, consentVersion, proof)
            }
            is PlanApplyResult.AlreadyApplied -> {
                plan = applied.plan
                revision++
                val effectIds = applied.effectIds
                val proof = InboxV4ApplicationProof(
                    planId = applied.plan.planId,
                    appliedPlanVersion = applied.plan.version.toLong(),
                    revisionId = applied.revision.revisionId,
                    scopeHash = "sha256:${engine.scopeHash(effectIds, applied.revision.changes)}",
                    effectIds = effectIds,
                )
                appendAppliedInboxAck(entry, consentVersion, proof)
            }
            is PlanApplyResult.Rejected -> when (applied.reason) {
                PlanRejectionReason.STALE_REVISION, PlanRejectionReason.VERSION_CONFLICT -> appendInboxAck(
                    entry, consentVersion, InboxV4AckState.STALE_VERSION,
                    InboxV4AckResultCode.STALE_VERSION, null,
                )
                PlanRejectionReason.REVISION_EXPIRED, PlanRejectionReason.CONFIRMATION_EXPIRED -> appendInboxAck(
                    entry, consentVersion, InboxV4AckState.EXPIRED,
                    InboxV4AckResultCode.PROPOSAL_EXPIRED, null,
                )
                PlanRejectionReason.STORAGE_FAILURE -> recordInboxUncertain(entry, consentVersion)
                else -> rejectInboxProposalAsValidation(entry, consentVersion)
            }
            is PlanApplyResult.Corrupt -> {
                corruption = applied.reason
                recordInboxUncertain(entry, consentVersion)
            }
        }
    }

    private fun mapInboxActivities(
        proposal: InboxV4Proposal,
        current: DayPlan,
        revisionId: String,
        a1Only: Boolean = false,
    ): List<PlanActivity> {
        val activities = current.activities.associateByTo(linkedMapOf()) { it.activityId }
        val touched = mutableSetOf<String>()
        for (change in proposal.changes) {
            val op = change["op"] as? String ?: error("Missing operation")
            if (a1Only && op != "reschedule") error("A1 only permits a reschedule")
            when (op) {
                "reschedule" -> {
                    val id = change.requiredInboxString("activity_id")
                    require(touched.add(id)) { "Duplicate activity operation" }
                    val old = activities[id] ?: error("Unknown activity")
                    val target = change.requiredInboxInt("target_start_minute")
                    val duration = change["duration_minutes"]?.let { (it as? Number)?.toInt() } ?: old.scheduledLocal.durationMinutes
                    activities[id] = old.copy(
                        scheduledLocal = old.scheduledLocal.copy(startMinute = target, durationMinutes = duration),
                    )
                }
                "edit_activity" -> {
                    val id = change.requiredInboxString("activity_id")
                    require(touched.add(id)) { "Duplicate activity operation" }
                    val old = activities[id] ?: error("Unknown activity")
                    val title = change["title"] as? String ?: old.title
                    val priority = (change["priority"] as? String)?.let(Priority::valueOf) ?: old.priority
                    val estimate = change["estimate_minutes"]?.let { (it as? Number)?.toInt() ?: error("Invalid estimate") }
                    activities[id] = old.copy(title = title, priority = priority, estimateMinutes = estimate ?: old.estimateMinutes)
                }
                "create_activity" -> {
                    val clientRef = change.requiredInboxString("client_ref")
                    val id = PlanRevisionMapper.activityId(current.planId, clientRef, current.version)
                    require(touched.add(id) && !activities.containsKey(id)) { "Duplicate activity reference" }
                    val kind = ActivityKind.valueOf(change.requiredInboxString("kind"))
                    val title = change.requiredInboxString("title")
                    val priority = Priority.valueOf(change.requiredInboxString("priority"))
                    val start = change.requiredInboxInt("start_minute")
                    val duration = change.requiredInboxInt("duration_minutes")
                    val goalId = change["goal_id"] as? String
                    activities[id] = PlanActivity(
                        activityId = id,
                        planId = current.planId,
                        kind = kind,
                        title = title,
                        status = ActivityStatus.PLANNED,
                        scheduledLocal = LocalSchedule(current.localDate, start, duration, current.timezone),
                        priority = priority,
                        goalId = goalId,
                        createdRevisionId = revisionId,
                        lastRevisionId = revisionId,
                    )
                }
                else -> error("Unsupported operation")
            }
        }
        return activities.values.toList()
    }

    private fun appendAppliedInboxAck(
        entry: InboxV4EntryView,
        consentVersion: Long,
        proof: InboxV4ApplicationProof,
    ): Boolean {
        val appended = appendInboxAck(
            entry, consentVersion, InboxV4AckState.APPLIED,
            InboxV4AckResultCode.APPLIED, proof,
        )
        if (!appended) {
            runCatching {
                inboxRepository.recordUncertain(
                    entry.proposal.proposalId,
                    consentVersion,
                    knownApplicationProof = proof,
                )
            }
            refreshInbox()
            message = "La révision locale est enregistrée, mais son ACK est incertain. Aucun nouvel effet ne sera rejoué."
        }
        return appended
    }

    private fun appendInboxAck(
        entry: InboxV4EntryView,
        consentVersion: Long,
        state: InboxV4AckState,
        resultCode: InboxV4AckResultCode,
        proof: InboxV4ApplicationProof?,
    ): Boolean {
        if (consentVersion < 1L) return fail("Version de consentement invalide pour l'ACK inbox.")
        val current = inboxRepository.get(entry.proposal.proposalId) ?: return fail("Proposition inbox inconnue.")
        return try {
            val ack = InboxV4Ack(
                contractVersion = 4,
                schemaVersion = 4,
                ackId = UUID.randomUUID().toString(),
                capability = "proposal.ack",
                proposalId = entry.proposal.proposalId,
                sequence = current.lastAckSequence + 1L,
                state = state,
                resultCode = resultCode,
                occurredAt = Instant.ofEpochMilli(clock.nowUtcMillis()),
                consentVersion = consentVersion,
                proof = proof,
            )
            inboxRepository.appendAck(ack)
            refreshInbox()
            true
        } catch (_: Exception) {
            message = "L'ACK inbox n'a pas pu être conservé durablement. Réessayez sans répéter l'effet local."
            false
        }
    }

    private fun rejectInboxProposalAsValidation(entry: InboxV4EntryView, consentVersion: Long): Boolean = appendInboxAck(
        entry, consentVersion, InboxV4AckState.VALIDATION_REJECTED,
        InboxV4AckResultCode.VALIDATION_FAILED, null,
    )

    private fun recordInboxUncertain(entry: InboxV4EntryView, consentVersion: Long): Boolean {
        if (consentVersion < 1L) return fail("Version de consentement invalide pour l'ACK inbox.")
        return try {
            inboxRepository.recordUncertain(entry.proposal.proposalId, consentVersion)
            refreshInbox()
            message = "Résultat local incertain. La proposition ne sera pas réappliquée automatiquement."
            false
        } catch (_: Exception) {
            message = "Résultat local incertain et ACK non conservé. Aucun nouvel effet n'a été lancé."
            false
        }
    }

    fun createTodayPlan(): Boolean {
        val now = clock.nowUtcMillis()
        val created = DayPlan(
            planId = UUID.randomUUID().toString(),
            localDate = today,
            timezone = timezone,
            version = 1,
            currentRevisionId = UUID.randomUUID().toString(),
            state = PlanState.APPROVED,
            activities = emptyList(),
            createdAt = now,
            updatedAt = now,
            approvedAt = now,
            origin = PlanOrigin.LOCAL,
        )
        return when (val result = requireRepository().create(created)) {
            is PlanWriteResult.Saved -> {
                plan = result.plan
                corruption = null
                message = "Programme créé. Ajoutez une première activité."
                revision++
                true
            }
            is PlanWriteResult.Rejected -> {
                message = rejectionLabel(result.reason)
                false
            }
            is PlanWriteResult.Corrupt -> {
                corruption = result.reason
                message = null
                false
            }
        }
    }

    fun addActivity(
        title: String,
        kind: ActivityKind,
        startMinute: Int,
        durationMinutes: Int,
        priority: Priority,
        notes: String? = null,
    ): Boolean {
        val current = plan ?: run {
            message = "Créez d'abord le programme de cette date."
            return false
        }
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty() || cleanTitle.length > 80) {
            message = "Le titre doit contenir 1 à 80 caractères."
            return false
        }
        if (!validWindow(startMinute, durationMinutes)) {
            message = "Le créneau doit rester dans la journée et durer au moins une minute."
            return false
        }
        val now = clock.nowUtcMillis()
        val activity = PlanActivity(
            activityId = UUID.randomUUID().toString(),
            planId = current.planId,
            kind = kind,
            title = cleanTitle,
            status = ActivityStatus.PLANNED,
            scheduledLocal = LocalSchedule(
                localDate = current.localDate,
                startMinute = startMinute,
                durationMinutes = durationMinutes,
                timezone = current.timezone,
            ),
            priority = priority,
            createdRevisionId = current.currentRevisionId,
            lastRevisionId = current.currentRevisionId,
            notes = notes?.trim()?.takeIf(String::isNotEmpty),
        )
        return propose(current.activities + activity, "Ajout : $cleanTitle")
    }

    fun moveActivity(activityId: String, startMinute: Int): Boolean {
        val current = plan ?: return fail("Aucun plan chargé.")
        val target = current.activities.firstOrNull { it.activityId == activityId }
            ?: return fail("Activité introuvable.")
        if (!validWindow(startMinute, target.scheduledLocal.durationMinutes)) {
            return fail("Le déplacement sortirait de la journée.")
        }
        if (startMinute == target.scheduledLocal.startMinute) {
            message = "L'activité est déjà à cet horaire."
            return false
        }
        val moved = target.copy(
            scheduledLocal = target.scheduledLocal.copy(startMinute = startMinute),
        )
        return propose(
            current.activities.map { if (it.activityId == activityId) moved else it },
            "Déplacement : ${target.title}",
        )
    }

    fun editActivity(
        activityId: String,
        title: String,
        kind: ActivityKind,
        priority: Priority,
        notes: String? = null,
    ): Boolean {
        val current = plan ?: return fail("Aucun plan chargé.")
        val target = current.activities.firstOrNull { it.activityId == activityId }
            ?: return fail("Activité introuvable.")
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty() || cleanTitle.length > 80) return fail("Le titre doit contenir 1 à 80 caractères.")
        val edited = target.copy(
            title = cleanTitle,
            kind = kind,
            priority = priority,
            notes = notes?.trim()?.takeIf(String::isNotEmpty),
        )
        return propose(
            current.activities.map { if (it.activityId == activityId) edited else it },
            "Modification : $cleanTitle",
        )
    }

    fun setStatus(activityId: String, status: ActivityStatus): Boolean {
        val current = plan ?: return fail("Aucun plan chargé.")
        val target = current.activities.firstOrNull { it.activityId == activityId }
            ?: return fail("Activité introuvable.")
        val rejection = PlanStatusPolicy.rejection(
            from = target.status,
            to = status,
            nowUtcMillis = clock.nowUtcMillis(),
            schedule = target.scheduledLocal,
            engine = engine,
            provenance = if (status == ActivityStatus.DONE) StatusProvenance.CONFIRMED else StatusProvenance.DECLARED,
            source = StatusSource.USER_UI,
        )
        if (rejection != null) return fail(statusRejectionLabel(rejection))
        val changed = target.copy(status = status)
        return propose(
            current.activities.map { if (it.activityId == activityId) changed else it },
            "Statut : ${target.title} → ${statusLabel(status)}",
            statusEventDraft = StatusEventDraft(
                provenance = if (status == ActivityStatus.DONE) StatusProvenance.CONFIRMED else StatusProvenance.DECLARED,
                source = StatusSource.USER_UI,
            ),
        )
    }

    /** Applies a user-selected status in one UI gesture while retaining domain validation and provenance. */
    fun setStatusAndConfirm(activityId: String, status: ActivityStatus): Boolean {
        if (!setStatus(activityId, status)) return false
        return confirmPreview()
    }

    /** Applies a note-only UI edit without leaving a second preview step after explicit save. */
    fun editActivityAndConfirm(
        activityId: String,
        title: String,
        kind: ActivityKind,
        priority: Priority,
        notes: String?,
    ): Boolean {
        if (!editActivity(activityId, title, kind, priority, notes)) return false
        return confirmPreview()
    }

    /** Applies the explicit same-day report transaction, including its status event and replacement slot. */
    fun reportActivity(
        activityId: String,
        targetLocalDate: String,
        targetStartMinute: Int,
        targetDurationMinutes: Int,
        reason: String,
        baseVersion: Int = plan?.version ?: 0,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): Boolean {
        val current = plan ?: return fail("Aucun plan chargé.")
        if (preview != null) return fail("Confirmez ou annulez l'aperçu courant avant le report.")
        val request = try {
            ReportActivityRequest(
                planId = current.planId,
                activityId = activityId,
                baseVersion = baseVersion,
                targetLocalDate = targetLocalDate,
                targetStartMinute = targetStartMinute,
                targetDurationMinutes = targetDurationMinutes,
                reason = reason,
                idempotencyKey = idempotencyKey,
            )
        } catch (_: IllegalArgumentException) {
            return fail("Les informations du report sont invalides.")
        }
        return when (val result = requireRepository().reportActivity(request)) {
            is PlanReportResult.Reported -> {
                plan = result.plan
                clearPreview()
                corruption = null
                message = "Activité reportée dans la même révision (v${result.plan.version})."
                revision++
                true
            }
            is PlanReportResult.Replayed -> {
                plan = result.plan
                clearPreview()
                message = "Ce report avait déjà été appliqué ; aucun doublon."
                revision++
                true
            }
            is PlanReportResult.Rejected -> fail(reportRejectionLabel(result.reason))
            is PlanReportResult.Corrupt -> {
                corruption = result.reason
                false
            }
        }
    }

    fun statusEvents() = requireRepository().statusEvents()

    /** Statuses that the local rules currently allow for this activity. */
    fun allowedStatuses(activity: PlanActivity): List<ActivityStatus> =
        PlanStatusPolicy.allowedTargets(
            from = activity.status,
            nowUtcMillis = clock.nowUtcMillis(),
            schedule = activity.scheduledLocal,
            engine = engine,
        )

    fun removeActivity(activityId: String): Boolean {
        val current = plan ?: return fail("Aucun plan chargé.")
        val target = current.activities.firstOrNull { it.activityId == activityId }
            ?: return fail("Activité introuvable.")
        if (target.status == ActivityStatus.DONE) {
            return fail("Une activité terminée ne peut pas être supprimée silencieusement.")
        }
        return propose(
            current.activities.filterNot { it.activityId == activityId },
            "Suppression : ${target.title}",
        )
    }

    fun confirmPreview(): Boolean {
        val revisionToApply = pendingRevision ?: return fail("Aucune révision à confirmer.")
        val previewToApply = pendingPreview ?: return fail("Aucun aperçu à confirmer.")
        val now = clock.nowUtcMillis()
        val confirmation = PlanConfirmation(
            confirmationId = UUID.randomUUID().toString(),
            revisionId = revisionToApply.revisionId,
            effectIds = previewToApply.effectIds,
            scopeHash = previewToApply.scopeHash,
            approvedAt = now,
            expiresAt = now + CONFIRMATION_TTL_MILLIS,
        )
        return when (val result = requireRepository().apply(revisionToApply, confirmation, pendingStatusEventDraft)) {
            is PlanApplyResult.Applied -> {
                plan = result.plan
                lastAppliedSummary = revisionToApply.summary
                clearPreview()
                corruption = null
                message = "Révision v${result.plan.version} appliquée localement (${result.effectIds.size} effet(s))."
                revision++
                true
            }
            is PlanApplyResult.AlreadyApplied -> {
                plan = result.plan
                clearPreview()
                message = "Cette révision avait déjà été appliquée ; aucun doublon."
                revision++
                true
            }
            is PlanApplyResult.Rejected -> fail(rejectionLabel(result.reason))
            is PlanApplyResult.Corrupt -> {
                corruption = result.reason
                clearPreview()
                false
            }
        }
    }

    fun cancelPreview() {
        clearPreview()
        message = "Révision abandonnée. Aucun effet n'a été appliqué."
    }

    fun dismissMessage() {
        message = null
    }

    /** Surfaces a UI-only message produced by a related screen controller. */
    fun showTransientMessage(value: String) {
        message = value
    }

    /** Sends only a consented, minimized plan and stages the resulting diff for confirmation. */
    fun proposeAgentRevision(
        client: PlanRevisionProposalClient,
        conversationId: String?,
        message: String,
        mandate: AgentMandate,
        planContextConsented: Boolean,
        planRevisionCapabilityEnabled: Boolean,
        requestId: String = UUID.randomUUID().toString(),
    ): Boolean {
        val request = prepareAgentRevisionRequest(
            conversationId = conversationId,
            message = message,
            mandate = mandate,
            planContextConsented = planContextConsented,
            planRevisionCapabilityEnabled = planRevisionCapabilityEnabled,
            requestId = requestId,
        ) ?: return false
        return applyAgentRevisionResult(client.propose(request))
    }

    /** Runs blocking HTTP on IO and resumes all Compose state changes on the caller's dispatcher. */
    suspend fun proposeAgentRevisionSuspending(
        client: PlanRevisionProposalClient,
        conversationId: String?,
        message: String,
        mandate: AgentMandate,
        planContextConsented: Boolean,
        planRevisionCapabilityEnabled: Boolean,
        requestId: String = UUID.randomUUID().toString(),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Boolean {
        val request = prepareAgentRevisionRequest(
            conversationId = conversationId,
            message = message,
            mandate = mandate,
            planContextConsented = planContextConsented,
            planRevisionCapabilityEnabled = planRevisionCapabilityEnabled,
            requestId = requestId,
        ) ?: return false
        val result = withContext(ioDispatcher) { client.propose(request) }
        return applyAgentRevisionResult(result)
    }

    private fun prepareAgentRevisionRequest(
        conversationId: String?,
        message: String,
        mandate: AgentMandate,
        planContextConsented: Boolean,
        planRevisionCapabilityEnabled: Boolean,
        requestId: String,
    ): PlanRevisionApiRequest? {
        val current = plan
        if (current == null) {
            fail("PLAN_MISMATCH: aucun programme local n'est chargé.")
            return null
        }
        if (preview != null) {
            fail("Confirmez ou annulez l'aperçu courant avant une nouvelle proposition.")
            return null
        }
        if (!planContextConsented) {
            fail("Le partage du programme n'a pas été consenti.")
            return null
        }
        if (!planRevisionCapabilityEnabled) {
            fail("La capability de révision agent est désactivée.")
            return null
        }
        if (message.isBlank() || message.length > 2_000) {
            fail("La demande agent doit contenir 1 à 2000 caractères.")
            return null
        }
        if (conversationId != null && conversationId.length > 80) {
            fail("L'identifiant de conversation est invalide.")
            return null
        }
        if (mandate.revoked || mandate.suspended || clock.nowUtcMillis() >= mandate.expiresAtUtcMillis) {
            fail("Le mandat n'autorise plus la planification agent.")
            return null
        }
        if (current.state !in setOf(PlanState.DRAFT, PlanState.APPROVED)) {
            fail("PLAN_MISMATCH: le programme n'est plus modifiable.")
            return null
        }
        return PlanRevisionApiRequest(
            requestId = requestId,
            conversationId = conversationId,
            message = message,
            planContextConsented = true,
            mandate = mandate,
            plan = current,
        )
    }

    private fun applyAgentRevisionResult(result: PlanRevisionApiResult): Boolean {
        return when (result) {
            PlanRevisionApiResult.Unauthorized -> fail("Requête plan agent non authentifiée (401).")
            PlanRevisionApiResult.Forbidden -> fail("Mandat ou consentement agent refusé (403).")
            PlanRevisionApiResult.Conflict -> fail("Requête plan agent en conflit (409).")
            PlanRevisionApiResult.Unprocessable -> fail("Requête plan agent invalide (422).")
            is PlanRevisionApiResult.ServerError -> fail("Service plan agent indisponible (${result.statusCode}).")
            is PlanRevisionApiResult.HttpRejected -> fail("Requête plan agent refusée (${result.statusCode}).")
            PlanRevisionApiResult.Timeout -> fail("Délai d'attente dépassé pour la proposition agent.")
            PlanRevisionApiResult.InvalidResponse -> fail("Réponse plan agent V3 invalide.")
            is PlanRevisionApiResult.InvalidProposal -> fail("${result.code}: la proposition agent a été rejetée.")
            PlanRevisionApiResult.ResponseTooLarge -> fail("Réponse plan agent trop volumineuse.")
            PlanRevisionApiResult.Unavailable -> fail("Service plan agent indisponible.")
            PlanRevisionApiResult.InvalidRequest -> fail("Requête plan agent invalide.")
            is PlanRevisionApiResult.Success -> {
                if (preview != null) {
                    return fail("Un autre aperçu a été créé pendant la requête agent.")
                }
                val response = result.response
                if (response.agentStatus != "ok") {
                    return fail("Agent status ${response.agentStatus}: ${response.reply}")
                }
                val proposal = response.proposal
                    ?: return fail(response.reply.ifBlank { "Aucune proposition de révision n'a été reçue." })
                val latestPlan = plan ?: return fail("PLAN_MISMATCH: aucun programme local n'est chargé.")
                when (val mapped = agentRevisionMapper.map(proposal, latestPlan, clock.nowUtcMillis())) {
                    PlanRevisionMappingResult.AlreadyApplied -> {
                        message = "AlreadyApplied : cette révision a déjà été appliquée, sans doublon."
                        revision++
                        true
                    }
                    is PlanRevisionMappingResult.Rejected -> fail(agentRevisionRejection(mapped.code))
                    is PlanRevisionMappingResult.Ready -> previewAgentRevision(mapped.revision)
                }
            }
        }
    }

    private fun previewAgentRevision(revisionToPreview: PlanRevision): Boolean =
        when (val result = requireRepository().preview(revisionToPreview)) {
            is PlanPreviewResult.Ready -> {
                pendingRevision = revisionToPreview
                pendingPreview = result.preview
                preview = result.preview
                message = null
                true
            }
            is PlanPreviewResult.Rejected -> fail(agentRevisionRejection(result.reason.name))
            is PlanPreviewResult.Corrupt -> {
                corruption = result.reason
                false
            }
        }

    private fun agentRevisionRejection(code: String): String = when (code) {
        PlanRevisionMapper.PLAN_MISMATCH, PlanRejectionReason.PLAN_ID_MISMATCH.name ->
            "PLAN_MISMATCH: la proposition vise un autre programme, une autre date ou un autre fuseau."
        PlanRevisionMapper.STALE_AGENT_REVISION, PlanRejectionReason.STALE_REVISION.name ->
            "STALE_AGENT_REVISION: le programme a changé depuis la création de la proposition."
        PlanRevisionMapper.AGENT_REVISION_EXPIRED, PlanRejectionReason.REVISION_EXPIRED.name ->
            "AGENT_REVISION_EXPIRED: la proposition a expiré."
        PlanRevisionMapper.REVISION_ID_CONFLICT -> "REVISION_ID_CONFLICT: l'identifiant de révision est associé à un autre contenu."
        PlanRevisionMapper.NO_APPLICABLE_DIFF, PlanRejectionReason.EMPTY_CHANGESET.name ->
            "La proposition ne produit aucun changement applicable."
        else -> "PLAN_MISMATCH: la proposition contient une activité absente du programme local."
    }

    private fun propose(
        proposedActivities: List<PlanActivity>,
        summary: String,
        origin: RevisionOrigin = RevisionOrigin.USER,
        statusEventDraft: StatusEventDraft? = null,
    ): Boolean {
        val current = plan ?: return fail("Aucun plan chargé.")
        val now = clock.nowUtcMillis()
        val revisionToPreview = try {
            engine.createRevision(
                current = current,
                proposedActivities = proposedActivities,
                revisionId = UUID.randomUUID().toString(),
                origin = origin,
                summary = summary,
                createdAt = now,
                ttlMillis = REVISION_TTL_MILLIS,
            )
        } catch (_: IllegalArgumentException) {
            return fail("Ce changement ne produit aucune révision applicable.")
        }
        return when (val result = requireRepository().preview(revisionToPreview)) {
            is PlanPreviewResult.Ready -> {
                pendingRevision = revisionToPreview
                pendingPreview = result.preview
                preview = result.preview
                pendingStatusEventDraft = statusEventDraft
                message = null
                true
            }
            is PlanPreviewResult.Rejected -> fail(rejectionLabel(result.reason))
            is PlanPreviewResult.Corrupt -> {
                corruption = result.reason
                false
            }
        }
    }

    private fun clearPreview() {
        pendingRevision = null
        pendingPreview = null
        pendingStatusEventDraft = null
        preview = null
    }

    private fun fail(reason: String): Boolean {
        message = reason
        return false
    }

    private fun requireRepository(): DayPlanRepository =
        requireNotNull(repository) { "Plan repository is not initialized" }

    private fun validWindow(startMinute: Int, durationMinutes: Int): Boolean =
        startMinute in 0..1439 && durationMinutes in 1..1440 &&
            startMinute + durationMinutes <= MINUTES_PER_DAY

    companion object {
        const val MINUTES_PER_DAY = 1440
        const val REVISION_TTL_MILLIS = 24L * 60L * 60L * 1_000L
        const val CONFIRMATION_TTL_MILLIS = 5L * 60L * 1_000L

        fun timeLabel(minuteOfDay: Int): String {
            val normalized = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
            return String.format(Locale.ROOT, "%02d:%02d", normalized / 60, normalized % 60)
        }

        fun parseTime(text: String): Int? {
            val match = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").matchEntire(text.trim()) ?: return null
            return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
        }

        fun endTimeLabel(startMinute: Int, durationMinutes: Int): String =
            timeLabel(startMinute + durationMinutes)

        fun statusLabel(status: ActivityStatus): String = when (status) {
            ActivityStatus.PLANNED -> "Prévue"
            ActivityStatus.IN_PROGRESS -> "En cours"
            ActivityStatus.DONE -> "Terminée"
            ActivityStatus.MISSED -> "Manquée"
            ActivityStatus.CANCELLED -> "Annulée"
            ActivityStatus.POSTPONED -> "Reportée"
        }

        fun kindLabel(kind: ActivityKind): String = when (kind) {
            ActivityKind.FOCUS -> "Focus"
            ActivityKind.TRAINING -> "Entraînement"
            ActivityKind.APPOINTMENT -> "Rendez-vous"
            ActivityKind.ADMIN -> "Administratif"
            ActivityKind.MEAL -> "Repas"
            ActivityKind.REST -> "Repos"
            ActivityKind.BUFFER -> "Marge"
            ActivityKind.OTHER -> "Autre"
        }

        fun priorityLabel(priority: Priority): String = when (priority) {
            Priority.REQUIRED -> "Requise"
            Priority.IMPORTANT -> "Importante"
            Priority.OPTIONAL -> "Optionnelle"
        }

        fun statusRejectionLabel(rejection: dev.atlas.coach.plan.StatusTransitionRejection): String = when (rejection) {
            dev.atlas.coach.plan.StatusTransitionRejection.SAME_STATUS -> "L'activité a déjà ce statut."
            dev.atlas.coach.plan.StatusTransitionRejection.TERMINAL_STATUS ->
                "Ce statut est terminal ; aucune transition locale n'est proposée."
            dev.atlas.coach.plan.StatusTransitionRejection.POSTPONED_UNSUPPORTED ->
                "Le report sera disponible quand une replanification réelle pourra être confirmée."
            dev.atlas.coach.plan.StatusTransitionRejection.MEASURED_STATUS_CANNOT_TRANSITION ->
                "Une mesure Focus ne change pas le statut ; confirmez-le explicitement."
            dev.atlas.coach.plan.StatusTransitionRejection.USER_DECISION_REQUIRED ->
                "Cette transition demande une décision explicite dans ATLAS."
            dev.atlas.coach.plan.StatusTransitionRejection.MISSED_BEFORE_END ->
                "Une activité ne peut être marquée manquée qu'après la fin de son créneau."
            dev.atlas.coach.plan.StatusTransitionRejection.INVALID_TRANSITION ->
                "Transition de statut non autorisée."
        }

        fun revisionChangeLabel(change: dev.atlas.coach.plan.PlanChange): String = when (change) {
            is dev.atlas.coach.plan.PlanChange.SlotAdded ->
                "Ajouter « ${change.activity.title} » à ${timeLabel(change.activity.scheduledLocal.startMinute)}"
            is dev.atlas.coach.plan.PlanChange.SlotRemoved ->
                "Supprimer « ${change.before.title} »"
            is dev.atlas.coach.plan.PlanChange.SlotMoved ->
                "Déplacer de ${timeLabel(change.from.startMinute)} à ${timeLabel(change.to.startMinute)}"
            is dev.atlas.coach.plan.PlanChange.SlotEdited ->
                "Modifier « ${change.after.title} »"
            is dev.atlas.coach.plan.PlanChange.StatusChanged ->
                "Statut : ${statusLabel(change.from)} → ${statusLabel(change.to)}"
        }

        private fun rejectionLabel(reason: PlanRejectionReason): String = when (reason) {
            PlanRejectionReason.PLAN_ALREADY_EXISTS -> "Un programme existe déjà pour cette date."
            PlanRejectionReason.PLAN_NOT_FOUND -> "Aucun programme local n'est enregistré pour cette date."
            PlanRejectionReason.PLAN_ID_MISMATCH -> "Le programme visé ne correspond pas au programme local."
            PlanRejectionReason.VERSION_CONFLICT,
            PlanRejectionReason.NON_MONOTONE_VERSION,
            PlanRejectionReason.STALE_REVISION,
            -> "La révision est basée sur une version périmée ; rechargez le programme."
            PlanRejectionReason.REVISION_EXPIRED -> "La révision a expiré ; préparez-en une nouvelle."
            PlanRejectionReason.REVISION_NOT_APPLICABLE -> "La révision n'est plus applicable à ce programme."
            PlanRejectionReason.INVALID_REVISION -> "La révision est invalide ou antérieure au programme courant."
            PlanRejectionReason.EMPTY_CHANGESET -> "La révision ne contient aucun changement."
            PlanRejectionReason.INVALID_CHANGES -> "La révision contient un changement invalide."
            PlanRejectionReason.DONE_ACTIVITY_REMOVAL -> "Une activité terminée ne peut pas être supprimée silencieusement."
            PlanRejectionReason.INVALID_CONFIRMATION -> "La confirmation est invalide ou a déjà été utilisée."
            PlanRejectionReason.EFFECT_SCOPE_MISMATCH -> "L'ensemble des effets confirmés ne correspond pas à la révision."
            PlanRejectionReason.CONFIRMATION_EXPIRED -> "La confirmation a expiré ; recommencez l'aperçu."
            PlanRejectionReason.CONFIRMATION_ALREADY_CONSUMED -> "Cette confirmation a déjà été consommée."
            PlanRejectionReason.REVISION_ID_CONFLICT -> "Identifiant de révision déjà utilisé pour un autre contenu."
            PlanRejectionReason.CORRUPT_STATE -> "Le stockage local du programme est illisible."
            PlanRejectionReason.STORAGE_FAILURE -> "L'écriture locale a échoué ; aucun effet n'a été appliqué."
        }

        private fun reportRejectionLabel(reason: dev.atlas.coach.plan.PlanReportRejection): String = when (reason) {
            dev.atlas.coach.plan.PlanReportRejection.PLAN_NOT_FOUND -> "Aucun programme local n'est enregistré pour cette date."
            dev.atlas.coach.plan.PlanReportRejection.PLAN_NOT_MODIFIABLE -> "Ce programme n'est plus modifiable."
            dev.atlas.coach.plan.PlanReportRejection.PLAN_ID_MISMATCH -> "Le report vise un autre programme."
            dev.atlas.coach.plan.PlanReportRejection.STALE_VERSION -> "Le programme a changé ; rechargez-le avant de reporter."
            dev.atlas.coach.plan.PlanReportRejection.CROSS_DATE_UNSUPPORTED -> "Le report vers une autre date n'est pas encore disponible."
            dev.atlas.coach.plan.PlanReportRejection.ACTIVITY_NOT_FOUND -> "Activité introuvable."
            dev.atlas.coach.plan.PlanReportRejection.TERMINAL_ACTIVITY -> "Une activité terminale ne peut pas être reportée."
            dev.atlas.coach.plan.PlanReportRejection.INVALID_SLOT -> "Le créneau du report est invalide."
            dev.atlas.coach.plan.PlanReportRejection.CONFLICT -> "Le nouveau créneau entre en conflit avec une autre activité."
            dev.atlas.coach.plan.PlanReportRejection.IDEMPOTENCY_CONFLICT -> "Cette clé de report est déjà associée à une autre demande."
            dev.atlas.coach.plan.PlanReportRejection.STORAGE_FAILURE -> "L'écriture locale a échoué ; aucun effet n'a été appliqué."
        }
    }
}

private val REVIEWABLE_INBOX_STATES = setOf(
    InboxV4ProposalState.PENDING_ANDROID,
    InboxV4ProposalState.RECEIVED,
    InboxV4ProposalState.AWAITING_USER,
)

private fun Map<String, Any?>.requiredInboxString(key: String): String =
    this[key] as? String ?: error("Invalid $key")

private fun Map<String, Any?>.requiredInboxInt(key: String): Int {
    val number = this[key] as? Number ?: error("Invalid $key")
    val longValue = number.toLong()
    require(number.toDouble() == longValue.toDouble() && longValue in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "Invalid $key"
    }
    return longValue.toInt()
}
