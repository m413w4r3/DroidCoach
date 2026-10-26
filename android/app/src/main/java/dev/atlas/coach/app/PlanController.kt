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
import dev.atlas.coach.plan.PlanOrigin
import dev.atlas.coach.plan.PlanPreview
import dev.atlas.coach.plan.PlanPreviewResult
import dev.atlas.coach.plan.PlanRejectionReason
import dev.atlas.coach.plan.PlanRevision
import dev.atlas.coach.plan.PlanRevisionEngine
import dev.atlas.coach.plan.PlanState
import dev.atlas.coach.plan.PlanWriteResult
import dev.atlas.coach.plan.Priority
import dev.atlas.coach.plan.RevisionOrigin
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

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
) {
    private val directory = directory
    private var repository: DayPlanRepository? = null
    private var loadedDate: String? = null
    private var pendingRevision: PlanRevision? = null
    private var pendingPreview: PlanPreview? = null

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
                message = "Programme du jour créé. Ajoutez une première activité."
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
            message = "Créez d'abord le programme du jour."
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
        if (target.status == status) {
            message = "L'activité a déjà ce statut."
            return false
        }
        val changed = target.copy(status = status)
        return propose(
            current.activities.map { if (it.activityId == activityId) changed else it },
            "Statut : ${target.title} → ${statusLabel(status)}",
        )
    }

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
        return when (val result = requireRepository().apply(revisionToApply, confirmation)) {
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

    private fun propose(proposedActivities: List<PlanActivity>, summary: String): Boolean {
        val current = plan ?: return fail("Aucun plan chargé.")
        val now = clock.nowUtcMillis()
        val revisionToPreview = try {
            engine.createRevision(
                current = current,
                proposedActivities = proposedActivities,
                revisionId = UUID.randomUUID().toString(),
                origin = RevisionOrigin.USER,
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
    }
}
