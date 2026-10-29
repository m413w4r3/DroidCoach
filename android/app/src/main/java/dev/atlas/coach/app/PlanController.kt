package dev.atlas.coach.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.ActivityStatus
import dev.atlas.coach.plan.DayPlan
import dev.atlas.coach.plan.DayPlanRepository
import dev.atlas.coach.plan.FilePlanBackend
import dev.atlas.coach.plan.LocalSchedule
import dev.atlas.coach.plan.PlanActivity
import dev.atlas.coach.plan.PlanApplyResult
import dev.atlas.coach.plan.PlanClock
import dev.atlas.coach.plan.PlanConfirmation
import dev.atlas.coach.plan.PlanLoadResult
import dev.atlas.coach.plan.PlanJsonCodec
import dev.atlas.coach.plan.PlanOrigin
import dev.atlas.coach.plan.PlanPreview
import dev.atlas.coach.plan.PlanPreviewResult
import dev.atlas.coach.plan.PlanRejectionReason
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
import dev.atlas.coach.plan.ReportActivityRequest
import dev.atlas.coach.plan.PlanReportResult
import dev.atlas.coach.plan.RevisionOrigin
import dev.atlas.coach.plan.StatusEventDraft
import dev.atlas.coach.plan.StatusProvenance
import dev.atlas.coach.plan.StatusSource
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.plan.v4.PlanV4JsonCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

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
) {
    private val directory = directory
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
    )

    /** Reads a saved plan for [date] without changing this controller or its repository state. */
    fun planForDate(date: LocalDate): DayPlan? {
        val file = File(directory, "day-plan-$date.json")
        if (!file.isFile) return null
        val rawDocument = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return runCatching {
            when (JSONObject(rawDocument).opt("schema_version")) {
                PlanJsonCodec.SCHEMA_VERSION -> PlanJsonCodec.decodeStore(rawDocument).dayPlan
                PlanV4JsonCodec.SCHEMA_VERSION -> PlanV4JsonCodec.decodeStore(rawDocument).store.dayPlan
                else -> null
            }
        }
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
        revision++
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
