package dev.atlas.coach.app

import android.content.Context
import dev.atlas.coach.calendar.CalendarDescriptor
import dev.atlas.coach.calendar.CalendarPermissionState
import dev.atlas.coach.calendar.CalendarReadConsentStore
import dev.atlas.coach.calendar.write.CalendarEventDraft
import dev.atlas.coach.calendar.write.CalendarEventLink
import dev.atlas.coach.calendar.write.CalendarEventLinkState
import dev.atlas.coach.calendar.write.CalendarWriteEvent
import dev.atlas.coach.calendar.write.CalendarWriteError
import dev.atlas.coach.calendar.write.CalendarWriteJournalSnapshot
import dev.atlas.coach.calendar.write.CalendarWriteOperation
import dev.atlas.coach.calendar.write.CalendarWriteOperationType
import dev.atlas.coach.calendar.write.CalendarWritePermissions
import dev.atlas.coach.calendar.write.CalendarWriteProvider
import dev.atlas.coach.calendar.write.CalendarWriteService
import dev.atlas.coach.calendar.write.FileCalendarWriteJournal
import dev.atlas.coach.calendar.write.FileCalendarWriteLinkStore
import dev.atlas.coach.calendar.write.isWritableLocalAccountType
import dev.atlas.coach.plan.DayPlan
import dev.atlas.coach.plan.PlanActivity
import dev.atlas.coach.plan.PlanState
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

internal enum class CalendarEffectIssue {
    READ_CONSENT_REQUIRED,
    WRITE_CONSENT_REQUIRED,
    PERMISSION_REQUIRED,
    PROVIDER_UNAVAILABLE,
    CALENDAR_NOT_SELECTED,
    CALENDAR_NOT_LOCAL,
    SELECTION_STORAGE_UNAVAILABLE,
    STATUS_STORAGE_UNAVAILABLE,
    PLAN_NOT_APPROVED,
    UNKNOWN_REQUIRES_RECONCILIATION,
    DIVERGENCE_REQUIRES_REVIEW,
    INVALID_PLAN,
    STALE_PLAN,
    STALE_EFFECT_GROUP,
    EFFECT_FAILED,
    EFFECT_STATUS_UNKNOWN,
}

internal data class CalendarSyncEffect(
    val activityId: String,
    val type: CalendarWriteOperationType,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val timezone: String,
)

internal data class CalendarSyncPreview(
    val planId: String,
    val planVersion: Int,
    val planFingerprint: String,
    val effects: List<CalendarSyncEffect>,
    val issue: CalendarEffectIssue? = null,
) {
    val ready: Boolean get() = issue == null
}

internal enum class CalendarSyncStatus {
    APPLIED,
    NO_CHANGES,
    BLOCKED,
    STALE_PLAN,
    STALE_EFFECT_GROUP,
    RECONCILIATION_REQUIRED,
    DIVERGED,
    FAILED,
    EFFECT_UNKNOWN,
}

internal data class CalendarSyncResult(
    val status: CalendarSyncStatus,
    val issue: CalendarEffectIssue? = null,
    val effectCount: Int = 0,
    val completedCount: Int = 0,
) {
    val successful: Boolean get() = status in setOf(CalendarSyncStatus.APPLIED, CalendarSyncStatus.NO_CHANGES)
}

internal enum class CalendarPlanVerificationState {
    CONFIRMED,
    MISSING,
    DIVERGED,
    ORPHANED,
    UNKNOWN,
}

internal data class CalendarPlanVerification(
    val activityId: String,
    val title: String,
    val state: CalendarPlanVerificationState,
)

/**
 * Bridges an already applied DayPlan to the durable CalendarWriteService. Preview is read-only;
 * only the overload carrying the exact preview and a current plan may execute provider effects.
 */
internal class CalendarEffectController(
    private val provider: CalendarWriteProvider,
    private val service: CalendarWriteService,
    private val linkStore: dev.atlas.coach.calendar.write.CalendarWriteLinkStore,
    private val allLinks: () -> List<CalendarEventLink>,
    private val allOperations: () -> List<CalendarWriteOperation>,
    private val readConsentStore: CalendarReadConsentStore,
    private val writeConsentStore: CalendarWriteConsentStore,
    private val selectionStore: CalendarWriteSelectionStore,
    private val statusStore: CalendarEffectStatusStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    val recoveryResults: List<dev.atlas.coach.calendar.write.CalendarWriteResult>

    init {
        recoveryResults = recoverPending()
    }

    companion object {
        fun fromContext(context: Context): CalendarEffectController {
            val appContext = context.applicationContext
            val storageDirectory = File(appContext.filesDir, "calendar/write")
            val rawProvider = dev.atlas.coach.calendar.write.AndroidCalendarContractWriteProvider(appContext)
            val readConsent = dev.atlas.coach.calendar.SharedPreferencesCalendarReadConsentStore(appContext)
            val writeConsent = SharedPreferencesCalendarWriteConsentStore(appContext)
            val selection = SharedPreferencesCalendarWriteSelectionStore(appContext)
            val provider = ConsentGatedCalendarWriteProvider(rawProvider, readConsent, writeConsent, selection)
            val service = CalendarWriteService.durable(provider, storageDirectory)
            val linkDirectory = File(storageDirectory, "links")
            val links = FileCalendarWriteLinkStore(linkDirectory)
            val operationDirectory = File(storageDirectory, "operations")
            return CalendarEffectController(
                provider = provider,
                service = service,
                linkStore = links,
                allLinks = {
                    linkDirectory.listFiles()
                        .orEmpty()
                        .asSequence()
                        .filter { it.isFile && it.extension == "json" }
                        .mapNotNull { file -> file.nameWithoutExtension.takeIf(::isCanonicalUuid) }
                        .mapNotNull(links::findByActivity)
                        .toList()
                },
                allOperations = {
                    (FileCalendarWriteJournal(operationDirectory) as CalendarWriteJournalSnapshot).allOperations()
                },
                readConsentStore = readConsent,
                writeConsentStore = writeConsent,
                selectionStore = selection,
                statusStore = SharedPreferencesCalendarEffectStatusStore(appContext),
            )
        }
    }

    fun selectedCalendarId(): Long? = runCatching(selectionStore::selectedCalendarId).getOrNull()

    fun localCalendars(): List<CalendarDescriptor> {
        if (consentIssue() != null) return emptyList()
        val catalog = try {
            service.inspectCatalog()
        } catch (_: Exception) {
            return emptyList()
        }
        if (!catalog.permissions.granted || !catalog.providerAvailable) return emptyList()
        return catalog.calendars.filter { calendar ->
            isAcceptedCalendar(calendar) && service.selectCalendar(catalog, calendar.id).calendar != null
        }
    }

    fun providerAvailable(): Boolean {
        if (consentIssue() != null || !permissionsGranted()) return false
        return runCatching { service.inspectCatalog().providerAvailable }.getOrDefault(false)
    }

    /** Persist a selection only after re-reading the provider and validating its local identity. */
    fun selectCalendar(calendarId: Long): CalendarEffectIssue? {
        consentIssue()?.let { return it }
        val catalog = try {
            service.inspectCatalog()
        } catch (_: Exception) {
            return CalendarEffectIssue.PROVIDER_UNAVAILABLE
        }
        if (!catalog.permissions.granted) return CalendarEffectIssue.PERMISSION_REQUIRED
        if (!catalog.providerAvailable) return CalendarEffectIssue.PROVIDER_UNAVAILABLE
        val selection = service.selectCalendar(catalog, calendarId)
        val calendar = selection.calendar ?: return when (selection.error) {
            CalendarWriteError.PERMISSION_DENIED -> CalendarEffectIssue.PERMISSION_REQUIRED
            CalendarWriteError.PROVIDER_UNAVAILABLE -> CalendarEffectIssue.PROVIDER_UNAVAILABLE
            else -> CalendarEffectIssue.CALENDAR_NOT_LOCAL
        }
        if (!isAcceptedCalendar(calendar)) return CalendarEffectIssue.CALENDAR_NOT_LOCAL
        return if (selectionStore.select(calendar.id)) null else CalendarEffectIssue.SELECTION_STORAGE_UNAVAILABLE
    }

    fun clearSelection(): Boolean = runCatching(selectionStore::clear).getOrDefault(false)

    /** Returns a grouped, exact proposal. Calling this method never inserts, updates, or deletes. */
    fun syncAppliedPlan(plan: DayPlan): CalendarSyncPreview {
        val fingerprint = planFingerprint(plan)
        if (plan.state != PlanState.APPROVED) return preview(plan, fingerprint, issue = CalendarEffectIssue.PLAN_NOT_APPROVED)
        val gate = writeGate()
        if (gate.issue != null) return preview(plan, fingerprint, issue = gate.issue)
        val calendar = gate.calendar ?: return preview(plan, fingerprint, issue = CalendarEffectIssue.CALENDAR_NOT_SELECTED)

        // Recovery is reconciliation only; CalendarWriteService never replays an uncertain mutation.
        recoverPending()

        val planActivities = plan.activities.associateBy { it.activityId }
        val relatedLinks = try {
            allLinks().filter { it.planId == plan.planId }
        } catch (_: Exception) {
            return preview(plan, fingerprint, issue = CalendarEffectIssue.UNKNOWN_REQUIRES_RECONCILIATION)
        }
        val linkByActivity = linkedMapOf<String, CalendarEventLink>()
        for (link in relatedLinks) {
            val reconciled = try {
                service.reconcile(link).link
            } catch (_: Exception) {
                null
            } ?: return preview(plan, fingerprint, issue = CalendarEffectIssue.UNKNOWN_REQUIRES_RECONCILIATION)
            when (reconciled.state) {
                CalendarEventLinkState.UNKNOWN, CalendarEventLinkState.PENDING ->
                    return preview(plan, fingerprint, issue = CalendarEffectIssue.UNKNOWN_REQUIRES_RECONCILIATION)
                CalendarEventLinkState.DIVERGED, CalendarEventLinkState.ORPHANED ->
                    return preview(plan, fingerprint, issue = CalendarEffectIssue.DIVERGENCE_REQUIRES_REVIEW)
                CalendarEventLinkState.CONFIRMED, CalendarEventLinkState.MISSING -> Unit
            }
            linkByActivity[link.activityId] = reconciled
        }

        val effects = mutableListOf<CalendarSyncEffect>()
        for (activity in plan.activities.sortedBy { it.activityId }) {
            val draft = try {
                activity.toCalendarDraft(service)
            } catch (_: Exception) {
                return preview(plan, fingerprint, issue = CalendarEffectIssue.INVALID_PLAN)
            }
            val link = linkByActivity[activity.activityId]
            if (link == null || link.state == CalendarEventLinkState.MISSING) {
                effects += draft.toEffect(CalendarWriteOperationType.CREATE)
                continue
            }
            if (link.calendarId != calendar.id) {
                return preview(plan, fingerprint, issue = CalendarEffectIssue.CALENDAR_NOT_SELECTED)
            }
            if (link.state != CalendarEventLinkState.CONFIRMED) {
                return preview(plan, fingerprint, issue = CalendarEffectIssue.UNKNOWN_REQUIRES_RECONCILIATION)
            }
            if (draft.contentHash(link.calendarId) != link.contentHash) {
                effects += draft.toEffect(CalendarWriteOperationType.UPDATE)
            }
        }

        for (link in relatedLinks.sortedBy { it.activityId }) {
            if (link.activityId in planActivities) continue
            val reconciled = linkByActivity.getValue(link.activityId)
            if (reconciled.state == CalendarEventLinkState.MISSING) continue
            if (reconciled.state != CalendarEventLinkState.CONFIRMED || reconciled.calendarId != calendar.id) {
                return preview(plan, fingerprint, issue = CalendarEffectIssue.DIVERGENCE_REQUIRES_REVIEW)
            }
            val event = try {
                reconciled.eventId?.let(provider::getEvent)
            } catch (_: Exception) {
                null
            } ?: return preview(plan, fingerprint, issue = CalendarEffectIssue.UNKNOWN_REQUIRES_RECONCILIATION)
            if (event.deleted || event.calendarId != reconciled.calendarId) {
                return preview(plan, fingerprint, issue = CalendarEffectIssue.DIVERGENCE_REQUIRES_REVIEW)
            }
            effects += CalendarSyncEffect(
                activityId = reconciled.activityId,
                type = CalendarWriteOperationType.DELETE,
                title = event.title?.takeIf(String::isNotBlank) ?: "Activité ATLAS",
                startMillis = event.dtstartMillis,
                endMillis = event.dtendMillis,
                timezone = event.timezone?.takeIf { it in ZoneId.getAvailableZoneIds() } ?: plan.timezone,
            )
        }
        return preview(
            plan,
            fingerprint,
            effects.sortedWith(compareBy<CalendarSyncEffect>({ it.startMillis }, { it.activityId }, { it.type.name })),
        )
    }

    /** Executes only when the current applied plan and its freshly recomputed group still match. */
    fun syncAppliedPlan(
        planAtDialogOpen: DayPlan,
        confirmation: CalendarSyncPreview,
        currentPlan: DayPlan,
    ): CalendarSyncResult {
        if (!confirmation.ready || confirmation.planId != planAtDialogOpen.planId ||
            confirmation.planVersion != planAtDialogOpen.version ||
            confirmation.planFingerprint != planFingerprint(planAtDialogOpen)
        ) {
            return persistResult(CalendarSyncResult(CalendarSyncStatus.STALE_EFFECT_GROUP, CalendarEffectIssue.STALE_EFFECT_GROUP))
        }
        if (currentPlan.planId != confirmation.planId || currentPlan.version != confirmation.planVersion ||
            planFingerprint(currentPlan) != confirmation.planFingerprint
        ) {
            return persistResult(CalendarSyncResult(CalendarSyncStatus.STALE_PLAN, CalendarEffectIssue.STALE_PLAN))
        }
        val currentPreview = syncAppliedPlan(currentPlan)
        if (!currentPreview.ready) return persistResult(resultForIssue(currentPreview.issue!!))
        if (currentPreview.effects != confirmation.effects || currentPreview.planFingerprint != confirmation.planFingerprint) {
            return persistResult(CalendarSyncResult(CalendarSyncStatus.STALE_EFFECT_GROUP, CalendarEffectIssue.STALE_EFFECT_GROUP))
        }
        if (confirmation.effects.isEmpty()) {
            return persistResult(CalendarSyncResult(CalendarSyncStatus.NO_CHANGES))
        }

        var completed = 0
        for (effect in confirmation.effects) {
            val gate = writeGate()
            if (gate.issue != null) {
                return persistResult(resultForIssue(gate.issue, confirmation.effects.size, completed))
            }
            val calendar = gate.calendar ?: return persistResult(
                resultForIssue(CalendarEffectIssue.CALENDAR_NOT_SELECTED, confirmation.effects.size, completed),
            )
            val result = applyEffect(currentPlan, calendar, effect)
            if (result?.error == CalendarWriteError.UNKNOWN_RESULT || result?.link?.state == CalendarEventLinkState.UNKNOWN) {
                return persistResult(
                    CalendarSyncResult(
                        CalendarSyncStatus.EFFECT_UNKNOWN,
                        CalendarEffectIssue.EFFECT_STATUS_UNKNOWN,
                        confirmation.effects.size,
                        completed,
                    ),
                )
            }
            if (result?.successful != true) {
                val issue = if (result?.link?.state == CalendarEventLinkState.DIVERGED ||
                    result?.link?.state == CalendarEventLinkState.ORPHANED
                ) CalendarEffectIssue.DIVERGENCE_REQUIRES_REVIEW else CalendarEffectIssue.EFFECT_FAILED
                return persistResult(resultForIssue(issue, confirmation.effects.size, completed))
            }
            completed++
        }
        return persistResult(
            CalendarSyncResult(CalendarSyncStatus.APPLIED, effectCount = confirmation.effects.size, completedCount = completed),
        )
    }

    /** Read-only reconciliation; missing plan links and removed activities remain visible. */
    fun verifyPlan(plan: DayPlan): List<CalendarPlanVerification> {
        if (consentIssue() != null || !permissionsGranted() || selectedCalendarId() == null) {
            return plan.activities.map { CalendarPlanVerification(it.activityId, it.title, CalendarPlanVerificationState.UNKNOWN) }
        }
        val links = runCatching { allLinks().filter { it.planId == plan.planId } }.getOrElse {
            return plan.activities.map { CalendarPlanVerification(it.activityId, it.title, CalendarPlanVerificationState.UNKNOWN) }
        }
        val planActivities = plan.activities.associateBy { it.activityId }
        val byActivity = links.associateBy { it.activityId }
        val results = mutableListOf<CalendarPlanVerification>()
        for (activity in plan.activities) {
            val link = byActivity[activity.activityId]
            if (link == null) {
                results += CalendarPlanVerification(activity.activityId, activity.title, CalendarPlanVerificationState.MISSING)
                continue
            }
            val current = runCatching { service.reconcile(link).link }.getOrNull()
            results += CalendarPlanVerification(
                activity.activityId,
                activity.title,
                current?.state.toVerificationState(),
            )
        }
        for (link in links.filter { it.activityId !in planActivities }) {
            val current = runCatching { service.reconcile(link).link }.getOrNull()
            val state = current?.state.toVerificationState().let {
                if (it == CalendarPlanVerificationState.CONFIRMED) CalendarPlanVerificationState.ORPHANED else it
            }
            results += CalendarPlanVerification(link.activityId, "Activité retirée", state)
        }
        return results
    }

    fun operationHistory(): List<CalendarWriteOperation> = try {
        allOperations().sortedBy { it.operationId }
    } catch (_: Exception) {
        emptyList()
    }

    fun lastStatus(): CalendarEffectLastStatus? = runCatching(statusStore::read).getOrNull()

    /** Called at construction and before a sync preview. This reconciles only; it never retries writes. */
    fun recoverPending(): List<dev.atlas.coach.calendar.write.CalendarWriteResult> {
        if (writeGate().issue != null) return emptyList()
        return try {
            service.recoverPending()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun applyEffect(
        plan: DayPlan,
        selectedCalendar: CalendarDescriptor,
        effect: CalendarSyncEffect,
    ): dev.atlas.coach.calendar.write.CalendarWriteResult? {
        val operationKind = when (effect.type) {
            CalendarWriteOperationType.CREATE -> "create"
            CalendarWriteOperationType.UPDATE -> "update"
            CalendarWriteOperationType.DELETE -> "delete"
        }
        val idempotencyKey = "plan-${plan.planId}-v${plan.version}-${effect.activityId}-$operationKind"
        val link = runCatching { linkStore.findByActivity(effect.activityId) }.getOrNull()
        return when (effect.type) {
            CalendarWriteOperationType.CREATE -> {
                val activity = plan.activities.firstOrNull { it.activityId == effect.activityId } ?: return null
                val draft = runCatching { activity.toCalendarDraft(service) }.getOrNull() ?: return null
                service.create(selectedCalendar.id, draft, idempotencyKey)
            }
            CalendarWriteOperationType.UPDATE -> {
                val activity = plan.activities.firstOrNull { it.activityId == effect.activityId } ?: return null
                val draft = runCatching { activity.toCalendarDraft(service) }.getOrNull() ?: return null
                val confirmedLink = link?.takeIf { it.state == CalendarEventLinkState.CONFIRMED } ?: return null
                if (confirmedLink.calendarId != selectedCalendar.id) return null
                service.update(confirmedLink, draft, idempotencyKey)
            }
            CalendarWriteOperationType.DELETE -> {
                val confirmedLink = link?.takeIf { it.state == CalendarEventLinkState.CONFIRMED } ?: return null
                if (confirmedLink.calendarId != selectedCalendar.id) return null
                service.delete(confirmedLink, idempotencyKey)
            }
        }
    }

    private fun writeGate(): WriteGate {
        consentIssue()?.let { return WriteGate(issue = it) }
        val permissions = try {
            provider.permissions()
        } catch (_: Exception) {
            return WriteGate(issue = CalendarEffectIssue.PROVIDER_UNAVAILABLE)
        }
        if (!permissions.granted) return WriteGate(issue = CalendarEffectIssue.PERMISSION_REQUIRED)
        val selectedId = try {
            selectionStore.selectedCalendarId()
        } catch (_: Exception) {
            return WriteGate(issue = CalendarEffectIssue.SELECTION_STORAGE_UNAVAILABLE)
        } ?: return WriteGate(issue = CalendarEffectIssue.CALENDAR_NOT_SELECTED)
        val catalog = try {
            service.inspectCatalog()
        } catch (_: Exception) {
            return WriteGate(issue = CalendarEffectIssue.PROVIDER_UNAVAILABLE)
        }
        if (!catalog.providerAvailable) return WriteGate(issue = CalendarEffectIssue.PROVIDER_UNAVAILABLE)
        val selected = service.selectCalendar(catalog, selectedId).calendar
            ?: return WriteGate(issue = CalendarEffectIssue.CALENDAR_NOT_LOCAL)
        if (!isAcceptedCalendar(selected)) return WriteGate(issue = CalendarEffectIssue.CALENDAR_NOT_LOCAL)
        return WriteGate(calendar = selected)
    }

    private fun consentIssue(): CalendarEffectIssue? {
        val readConsent = try {
            readConsentStore.isGranted()
        } catch (_: Exception) {
            false
        }
        if (!readConsent) return CalendarEffectIssue.READ_CONSENT_REQUIRED
        val writeConsent = try {
            writeConsentStore.isGranted()
        } catch (_: Exception) {
            false
        }
        return if (writeConsent) null else CalendarEffectIssue.WRITE_CONSENT_REQUIRED
    }

    private fun permissionsGranted(): Boolean = try {
        provider.permissions().granted
    } catch (_: Exception) {
        false
    }

    private fun isAcceptedCalendar(calendar: CalendarDescriptor): Boolean =
        isWritableLocalAccountType(calendar.accountType) &&
            (calendar.accountType != "dev.atlas.coach.calendar" || calendar.displayName == "ATLAS R3A TEST")

    private fun persistResult(result: CalendarSyncResult): CalendarSyncResult {
        val persisted = runCatching {
            statusStore.write(
                CalendarEffectLastStatus(
                    code = result.status.name,
                    atMillis = nowMillis().coerceAtLeast(0L),
                    effectCount = result.effectCount,
                    completedCount = result.completedCount,
                ),
            )
        }.getOrDefault(false)
        return if (persisted || result.issue != null) result else result.copy(
            status = CalendarSyncStatus.FAILED,
            issue = CalendarEffectIssue.STATUS_STORAGE_UNAVAILABLE,
        )
    }

    private fun resultForIssue(
        issue: CalendarEffectIssue,
        effectCount: Int = 0,
        completedCount: Int = 0,
    ): CalendarSyncResult = when (issue) {
        CalendarEffectIssue.STALE_PLAN -> CalendarSyncResult(CalendarSyncStatus.STALE_PLAN, issue, effectCount, completedCount)
        CalendarEffectIssue.STALE_EFFECT_GROUP -> CalendarSyncResult(CalendarSyncStatus.STALE_EFFECT_GROUP, issue, effectCount, completedCount)
        CalendarEffectIssue.UNKNOWN_REQUIRES_RECONCILIATION -> CalendarSyncResult(CalendarSyncStatus.RECONCILIATION_REQUIRED, issue, effectCount, completedCount)
        CalendarEffectIssue.DIVERGENCE_REQUIRES_REVIEW -> CalendarSyncResult(CalendarSyncStatus.DIVERGED, issue, effectCount, completedCount)
        CalendarEffectIssue.EFFECT_STATUS_UNKNOWN -> CalendarSyncResult(CalendarSyncStatus.EFFECT_UNKNOWN, issue, effectCount, completedCount)
        CalendarEffectIssue.EFFECT_FAILED -> CalendarSyncResult(CalendarSyncStatus.FAILED, issue, effectCount, completedCount)
        else -> CalendarSyncResult(CalendarSyncStatus.BLOCKED, issue, effectCount, completedCount)
    }

    private fun preview(
        plan: DayPlan,
        fingerprint: String,
        effects: List<CalendarSyncEffect> = emptyList(),
        issue: CalendarEffectIssue? = null,
    ) = CalendarSyncPreview(plan.planId, plan.version, fingerprint, effects, issue)

    private fun dev.atlas.coach.plan.PlanActivity.toCalendarDraft(service: CalendarWriteService): CalendarEventDraft =
        service.draftFromLocalTime(
            activityId = activityId,
            planId = planId,
            title = title,
            localDate = scheduledLocal.localDate,
            startMinute = scheduledLocal.startMinute,
            durationMinutes = scheduledLocal.durationMinutes,
            timezone = scheduledLocal.timezone,
        )

    private fun CalendarEventDraft.toEffect(type: CalendarWriteOperationType) = CalendarSyncEffect(
        activityId = activityId,
        type = type,
        title = title,
        startMillis = dtstartMillis,
        endMillis = dtendMillis,
        timezone = timezone,
    )

    private fun CalendarEventLinkState?.toVerificationState(): CalendarPlanVerificationState = when (this) {
        CalendarEventLinkState.CONFIRMED -> CalendarPlanVerificationState.CONFIRMED
        CalendarEventLinkState.MISSING -> CalendarPlanVerificationState.MISSING
        CalendarEventLinkState.DIVERGED -> CalendarPlanVerificationState.DIVERGED
        CalendarEventLinkState.ORPHANED -> CalendarPlanVerificationState.ORPHANED
        CalendarEventLinkState.PENDING, CalendarEventLinkState.UNKNOWN, null -> CalendarPlanVerificationState.UNKNOWN
    }

    private fun planFingerprint(plan: DayPlan): String {
        val fields = buildList {
            add(plan.planId)
            add(plan.localDate)
            add(plan.timezone)
            add(plan.version.toString())
            add(plan.currentRevisionId)
            add(plan.state.name)
            add(plan.updatedAt.toString())
            add(plan.approvedAt?.toString().orEmpty())
            plan.activities.sortedBy { it.activityId }.forEach { activity ->
                add(activity.activityId)
                add(activity.planId)
                add(activity.kind.name)
                add(activity.title)
                add(activity.status.name)
                add(activity.scheduledLocal.localDate)
                add(activity.scheduledLocal.startMinute.toString())
                add(activity.scheduledLocal.durationMinutes.toString())
                add(activity.scheduledLocal.timezone)
                add(activity.priority.name)
                add(activity.goalId.orEmpty())
                add(activity.subGoalId.orEmpty())
                add(activity.estimateMinutes?.toString().orEmpty())
                add(activity.actualMinutes?.toString().orEmpty())
                add(activity.createdRevisionId)
                add(activity.lastRevisionId)
                add(activity.notes.orEmpty())
            }
        }.joinToString(separator = "") { field -> "${field.toByteArray(StandardCharsets.UTF_8).size}:$field" }
        return MessageDigest.getInstance("SHA-256")
            .digest(fields.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private data class WriteGate(
        val calendar: CalendarDescriptor? = null,
        val issue: CalendarEffectIssue? = null,
    )
}

/** Provider boundary that rechecks ATLAS consent, Android grants, and selection immediately at access. */
private class ConsentGatedCalendarWriteProvider(
    private val delegate: CalendarWriteProvider,
    private val readConsentStore: CalendarReadConsentStore,
    private val writeConsentStore: CalendarWriteConsentStore,
    private val selectionStore: CalendarWriteSelectionStore,
) : CalendarWriteProvider {
    override fun permissions(): CalendarWritePermissions = delegate.permissions()

    override fun listCalendars(): List<CalendarDescriptor> = synchronized(CalendarWriteConsentGate.lock) {
        requireConsentAndReadPermission()
        delegate.listCalendars()
    }

    override fun insert(calendarId: Long, draft: CalendarEventDraft, provenanceUri: String): Long =
        withWriteAuthorization(calendarId) { delegate.insert(calendarId, draft, provenanceUri) }

    override fun update(eventId: Long, calendarId: Long, draft: CalendarEventDraft, provenanceUri: String) =
        withWriteAuthorization(calendarId) { delegate.update(eventId, calendarId, draft, provenanceUri) }

    override fun delete(eventId: Long, calendarId: Long, provenanceUri: String): Int =
        withWriteAuthorization(calendarId) { delegate.delete(eventId, calendarId, provenanceUri) }

    override fun getEvent(eventId: Long): CalendarWriteEvent? = synchronized(CalendarWriteConsentGate.lock) {
        requireConsentAndReadPermission()
        delegate.getEvent(eventId)
    }

    override fun findByCustomAppUri(provenanceUri: String): List<CalendarWriteEvent> =
        synchronized(CalendarWriteConsentGate.lock) {
            requireConsentAndReadPermission()
            delegate.findByCustomAppUri(provenanceUri)
        }

    private fun <T> withWriteAuthorization(calendarId: Long, effect: () -> T): T =
        synchronized(CalendarWriteConsentGate.lock) {
            requireConsentAndReadPermission()
            val permissions = delegate.permissions()
            if (permissions.write != CalendarPermissionState.GRANTED) throw SecurityException("calendar write permission denied")
            val selectedId = runCatching(selectionStore::selectedCalendarId).getOrNull()
            if (selectedId != calendarId) throw SecurityException("calendar selection changed")
            val calendar = delegate.listCalendars().firstOrNull { it.id == calendarId }
            if (calendar == null || !isAcceptedCalendar(calendar) || !calendar.visible || calendar.accessLevel < 500) {
                throw SecurityException("selected local calendar is unavailable")
            }
            effect()
        }

    private fun requireConsentAndReadPermission() {
        val readGranted = runCatching(readConsentStore::isGranted).getOrDefault(false)
        val writeGranted = runCatching(writeConsentStore::isGranted).getOrDefault(false)
        if (!readGranted || !writeGranted) throw SecurityException("calendar consent revoked")
        if (delegate.permissions().read != CalendarPermissionState.GRANTED) {
            throw SecurityException("calendar read permission denied")
        }
    }
}

private fun isAcceptedCalendar(calendar: CalendarDescriptor): Boolean =
    isWritableLocalAccountType(calendar.accountType) &&
        (calendar.accountType != "dev.atlas.coach.calendar" || calendar.displayName == "ATLAS R3A TEST")

internal fun CalendarEffectIssue.userLabel(): String = when (this) {
    CalendarEffectIssue.READ_CONSENT_REQUIRED -> "Consentement de lecture requis."
    CalendarEffectIssue.WRITE_CONSENT_REQUIRED -> "Consentement d’écriture requis."
    CalendarEffectIssue.PERMISSION_REQUIRED -> "Les permissions Android READ_CALENDAR et WRITE_CALENDAR doivent être accordées."
    CalendarEffectIssue.PROVIDER_UNAVAILABLE -> "Le fournisseur de calendrier est indisponible."
    CalendarEffectIssue.CALENDAR_NOT_SELECTED -> "Sélectionnez à nouveau un calendrier local."
    CalendarEffectIssue.CALENDAR_NOT_LOCAL -> "Seul un calendrier local ATLAS ou LOCAL peut être sélectionné."
    CalendarEffectIssue.SELECTION_STORAGE_UNAVAILABLE -> "La sélection locale n’a pas pu être enregistrée."
    CalendarEffectIssue.STATUS_STORAGE_UNAVAILABLE -> "Le statut n’a pas pu être enregistré."
    CalendarEffectIssue.PLAN_NOT_APPROVED -> "Seul un programme appliqué peut être synchronisé."
    CalendarEffectIssue.UNKNOWN_REQUIRES_RECONCILIATION -> "Un effet reste à réconcilier. Aucun nouvel effet n’a été envoyé."
    CalendarEffectIssue.DIVERGENCE_REQUIRES_REVIEW -> "Un événement diffère du programme. Consultez le diagnostic avant toute action."
    CalendarEffectIssue.INVALID_PLAN -> "Le programme contient un créneau calendrier invalide."
    CalendarEffectIssue.STALE_PLAN -> "Le programme a changé. Rouvrez la confirmation."
    CalendarEffectIssue.STALE_EFFECT_GROUP -> "Les effets ont changé. Rouvrez la confirmation."
    CalendarEffectIssue.EFFECT_FAILED -> "Un effet n’a pas pu être confirmé par le fournisseur."
    CalendarEffectIssue.EFFECT_STATUS_UNKNOWN -> "Le résultat reste inconnu et doit être réconcilié."
}

internal fun CalendarSyncEffect.operationLabel(): String = when (type) {
    CalendarWriteOperationType.CREATE -> "Création"
    CalendarWriteOperationType.UPDATE -> "Mise à jour"
    CalendarWriteOperationType.DELETE -> "Suppression"
}

internal fun CalendarSyncEffect.localTimeLabel(): String {
    val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("UTC"))
    val formatter = DateTimeFormatter.ofPattern("dd MMM HH:mm", java.util.Locale.getDefault())
    val start = Instant.ofEpochMilli(startMillis).atZone(zone).format(formatter)
    val end = Instant.ofEpochMilli(endMillis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.getDefault()))
    return "$start–$end"
}

internal fun CalendarSyncStatus.userLabel(): String = when (this) {
    CalendarSyncStatus.APPLIED -> "Synchronisation confirmée"
    CalendarSyncStatus.NO_CHANGES -> "Calendrier à jour"
    CalendarSyncStatus.BLOCKED -> "Synchronisation bloquée"
    CalendarSyncStatus.STALE_PLAN -> "Programme modifié avant confirmation"
    CalendarSyncStatus.STALE_EFFECT_GROUP -> "Confirmation périmée"
    CalendarSyncStatus.RECONCILIATION_REQUIRED -> "Réconciliation requise"
    CalendarSyncStatus.DIVERGED -> "Divergence détectée"
    CalendarSyncStatus.FAILED -> "Synchronisation non confirmée"
    CalendarSyncStatus.EFFECT_UNKNOWN -> "Résultat inconnu"
}

private fun isCanonicalUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
