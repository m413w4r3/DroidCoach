package dev.atlas.coach.calendar.write

import dev.atlas.coach.calendar.CALENDAR_ACCESS_CONTRIBUTOR
import dev.atlas.coach.calendar.CalendarDescriptor
import dev.atlas.coach.calendar.CalendarPermissionState
import java.nio.charset.StandardCharsets
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Local-only CalendarContract write engine. It requires an explicit local calendar for each new
 * create and never chooses an event when provenance or URI uniqueness is uncertain.
 *
 * Legacy callers may omit [linkStore] for the read-only diagnostic path. Calendar-writing callers
 * must use [durable] or inject file-backed journal and link stores.
 *
 * [beforeProviderEffect] is a deterministic fault seam for JVM crash-window tests. It runs after
 * write-ahead journaling and immediately before the first mutation request. Production callers should
 * leave it at its no-op default.
 */
class CalendarWriteService(
    private val provider: CalendarWriteProvider,
    private val journal: CalendarWriteJournal,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val beforeProviderEffect: (CalendarWriteOperation) -> Unit = {},
    private val linkStore: CalendarWriteLinkStore = InMemoryCalendarWriteLinkStore(),
    private val afterProviderEffect: (CalendarWriteOperation) -> Unit = {},
) {
    companion object {
        private val activityLocks = ConcurrentHashMap<String, Any>()

        /** Production factory: journal and links live in independent versioned app-private files. */
        fun durable(
            provider: CalendarWriteProvider,
            storageDirectory: File,
            nowMillis: () -> Long = System::currentTimeMillis,
        ): CalendarWriteService = CalendarWriteService(
            provider = provider,
            journal = FileCalendarWriteJournal(File(storageDirectory, "operations")),
            nowMillis = nowMillis,
            linkStore = FileCalendarWriteLinkStore(File(storageDirectory, "links")),
        )
    }

    fun inspectCatalog(): CalendarWriteCatalog {
        val permissions = try {
            provider.permissions()
        } catch (_: SecurityException) {
            return CalendarWriteCatalog(deniedPermissions(), emptyList(), providerAvailable = false)
        } catch (_: Exception) {
            return CalendarWriteCatalog(unknownPermissions(), emptyList(), providerAvailable = false)
        }
        if (permissions.read != CalendarPermissionState.GRANTED) {
            return CalendarWriteCatalog(permissions, emptyList(), providerAvailable = false)
        }
        return try {
            CalendarWriteCatalog(permissions, provider.listCalendars(), providerAvailable = true)
        } catch (_: SecurityException) {
            CalendarWriteCatalog(permissions.copy(read = CalendarPermissionState.DENIED), emptyList(), false)
        } catch (_: Exception) {
            CalendarWriteCatalog(permissions, emptyList(), providerAvailable = false)
        }
    }

    fun selectCalendar(catalog: CalendarWriteCatalog, explicitCalendarId: Long?): CalendarSelectionResult {
        if (!catalog.permissions.granted) return CalendarSelectionResult(error = CalendarWriteError.PERMISSION_DENIED)
        if (!catalog.providerAvailable) return CalendarSelectionResult(error = CalendarWriteError.PROVIDER_UNAVAILABLE)
        if (explicitCalendarId == null) {
            val hasSelectableLocal = catalog.calendars.any {
                it.accountType == LOCAL_ACCOUNT_TYPE && it.visible &&
                    it.accessLevel >= CALENDAR_ACCESS_CONTRIBUTOR
            }
            return CalendarSelectionResult(
                error = if (hasSelectableLocal) CalendarWriteError.CALENDAR_NOT_SELECTED
                else CalendarWriteError.CALENDAR_NOT_LOCAL,
            )
        }
        val calendar = catalog.calendars.firstOrNull { it.id == explicitCalendarId }
            ?: return CalendarSelectionResult(error = CalendarWriteError.CALENDAR_NOT_LOCAL)
        if (calendar.accountType != LOCAL_ACCOUNT_TYPE || !calendar.visible ||
            calendar.accessLevel < CALENDAR_ACCESS_CONTRIBUTOR
        ) {
            return CalendarSelectionResult(error = CalendarWriteError.CALENDAR_NOT_LOCAL)
        }
        return CalendarSelectionResult(calendar = calendar)
    }

    @Synchronized
    fun create(
        explicitCalendarId: Long?,
        draft: CalendarEventDraft,
        idempotencyKey: String,
        operationId: String = UUID.randomUUID().toString(),
    ): CalendarWriteResult = synchronized(activityLock(draft.activityId)) {
        val catalog = inspectCatalog()
        val selection = selectCalendar(catalog, explicitCalendarId)
        val calendar = selection.calendar ?: return failure(selection.error!!)
        val payloadHash = draft.contentHash(calendar.id)
        existingIdempotentOperation(
            idempotencyKey = idempotencyKey,
            type = CalendarWriteOperationType.CREATE,
            draft = draft,
            calendarId = calendar.id,
            payloadHash = payloadHash,
        )?.let { return it }

        val operation = CalendarWriteOperation(
            operationId = operationId,
            type = CalendarWriteOperationType.CREATE,
            activityId = draft.activityId,
            planId = draft.planId,
            calendarId = calendar.id,
            eventId = null,
            payloadHash = payloadHash,
            idempotencyKey = idempotencyKey,
            state = CalendarWriteOperationState.PREPARED,
        )
        record(operation)?.let { return failure(it) }
        persistPendingLink(operation, buildLink(operation, CalendarEventLinkState.PENDING))
            ?.let { return failure(it, buildLink(operation, CalendarEventLinkState.PENDING)) }

        // Simulates process death after write-ahead journaling but before the first provider call.
        beforeProviderEffect(operation)

        val existing = try {
            provider.findByCustomAppUri(draft.provenanceUri)
        } catch (_: SecurityException) {
            return finishWithoutEffect(operation, CalendarWriteError.PERMISSION_DENIED)
        } catch (_: Exception) {
            return finishWithoutEffect(operation, CalendarWriteError.PROVIDER_UNAVAILABLE)
        }
        if (existing.size > 1) {
            return finishWithoutEffect(operation, CalendarWriteError.DUPLICATE_URI, CalendarEventLinkState.ORPHANED)
        }
        if (existing.size == 1) {
            return reuseExisting(operation, draft, existing.single())
        }

        val current = revalidateCalendar(calendar.id)
        if (current.error != null) return finishWithoutEffect(operation, current.error)
        val permissionError = permissionsBeforeEffect()
        if (permissionError != null) return finishWithoutEffect(operation, permissionError)
        val eventId = try {
            provider.insert(calendar.id, draft, draft.provenanceUri)
        } catch (_: Exception) {
            return finishUnknown(operation)
        }
        // This seam models process death after CalendarContract committed but before journal commit.
        afterProviderEffect(operation)
        if (!updateJournalState(operation, CalendarWriteOperationState.APPLIED, eventId)) {
            return finishUnknown(operation, eventId = eventId)
        }
        return verifyAfterEffect(requireNotNull(journal.findByOperation(operation.operationId)), draft, eventId)
    }

    @Synchronized
    fun update(
        link: CalendarEventLink,
        draft: CalendarEventDraft,
        idempotencyKey: String,
        operationId: String = UUID.randomUUID().toString(),
    ): CalendarWriteResult = synchronized(activityLock(draft.activityId)) {
        val linkError = validateConfirmedLink(link, draft.activityId, draft.planId)
        if (linkError != null) return failure(linkError, link)
        val eventId = link.eventId ?: return failure(CalendarWriteError.MISSING_EVENT, link)
        if (draft.provenanceUri != link.provenanceUri) return failure(CalendarWriteError.INVALID_LINK, link)
        val payloadHash = draft.contentHash(link.calendarId)
        existingIdempotentOperation(
            idempotencyKey,
            CalendarWriteOperationType.UPDATE,
            draft,
            link.calendarId,
            payloadHash,
            eventId = link.eventId,
        )?.let { return it }

        val initial = inspectTarget(link, eventId)
        if (initial.error != null) return failure(initial.error, initial.link ?: link)

        val operation = CalendarWriteOperation(
            operationId = operationId,
            type = CalendarWriteOperationType.UPDATE,
            activityId = draft.activityId,
            planId = draft.planId,
            calendarId = link.calendarId,
            eventId = eventId,
            payloadHash = payloadHash,
            idempotencyKey = idempotencyKey,
            state = CalendarWriteOperationState.PREPARED,
            previousContentHash = link.contentHash,
        )
        record(operation)?.let { return failure(it, link) }
        persistPendingLink(operation, link.copy(state = CalendarEventLinkState.PENDING, lastError = null))
            ?.let { return failure(it, link) }
        beforeProviderEffect(operation)

        val currentTarget = revalidateTarget(link, eventId)
        if (currentTarget.error != null) return finishWithoutEffect(
            operation,
            currentTarget.error,
            currentTarget.link?.state ?: CalendarEventLinkState.MISSING,
            link,
        )
        val permissionError = permissionsBeforeEffect()
        if (permissionError != null) return finishWithoutEffect(operation, permissionError, previousLink = link)
        try {
            provider.update(eventId, link.calendarId, draft, draft.provenanceUri)
        } catch (_: Exception) {
            return finishUnknown(operation, link)
        }
        afterProviderEffect(operation)
        if (!updateJournalState(operation, CalendarWriteOperationState.APPLIED, eventId)) {
            return finishUnknown(operation, link, eventId)
        }
        return verifyAfterEffect(requireNotNull(journal.findByOperation(operation.operationId)), draft, eventId)
    }

    @Synchronized
    fun delete(
        link: CalendarEventLink,
        idempotencyKey: String,
        operationId: String = UUID.randomUUID().toString(),
    ): CalendarWriteResult = synchronized(activityLock(link.activityId)) {
        val linkError = validateConfirmedLink(link, link.activityId, link.planId)
        if (linkError != null) return failure(linkError, link)
        val eventId = link.eventId ?: return failure(CalendarWriteError.MISSING_EVENT, link)
        existingIdempotentOperation(
            idempotencyKey,
            CalendarWriteOperationType.DELETE,
            draft = null,
            calendarId = link.calendarId,
            payloadHash = link.contentHash,
            activityId = link.activityId,
            planId = link.planId,
            eventId = eventId,
        )?.let { return it }

        val initial = inspectTarget(link, eventId)
        if (initial.error != null) return failure(initial.error, initial.link ?: link)
        val operation = CalendarWriteOperation(
            operationId = operationId,
            type = CalendarWriteOperationType.DELETE,
            activityId = link.activityId,
            planId = link.planId,
            calendarId = link.calendarId,
            eventId = eventId,
            payloadHash = link.contentHash,
            idempotencyKey = idempotencyKey,
            state = CalendarWriteOperationState.PREPARED,
        )
        record(operation)?.let { return failure(it, link) }
        persistPendingLink(operation, link.copy(state = CalendarEventLinkState.PENDING, lastError = null))
            ?.let { return failure(it, link) }
        beforeProviderEffect(operation)

        val currentTarget = revalidateTarget(link, eventId)
        if (currentTarget.error != null) return finishWithoutEffect(
            operation,
            currentTarget.error,
            currentTarget.link?.state ?: CalendarEventLinkState.MISSING,
            link,
        )
        val permissionError = permissionsBeforeEffect()
        if (permissionError != null) return finishWithoutEffect(operation, permissionError, previousLink = link)
        val affectedRows = try {
            provider.delete(eventId, link.calendarId, link.provenanceUri)
        } catch (_: Exception) {
            return finishUnknown(operation, link)
        }
        afterProviderEffect(operation)
        val readBack = try {
            provider.getEvent(eventId)
        } catch (_: Exception) {
            return finishUnknown(operation, link, eventId)
        }
        if (affectedRows == 1 && (readBack == null || readBack.deleted)) {
            if (!updateJournalState(operation, CalendarWriteOperationState.APPLIED, eventId)) {
                return finishUnknown(operation, link, eventId)
            }
            val stored = requireNotNull(journal.findByOperation(operation.operationId))
            return result(stored, buildLink(stored, CalendarEventLinkState.MISSING), null)
        }
        val absentWithoutAffectedRow = affectedRows == 0 && (readBack == null || readBack.deleted)
        val unchangedWithoutAffectedRow = affectedRows == 0 && readBack != null && !readBack.deleted &&
            markerFor(readBack, link.provenanceUri) == CalendarWriteMarker.OWNED &&
            readBack.calendarId == link.calendarId && eventContentHash(readBack) == link.contentHash
        val error = when {
            absentWithoutAffectedRow -> CalendarWriteError.MISSING_EVENT
            else -> CalendarWriteError.READ_BACK_MISMATCH
        }
        val operationState = if (affectedRows == 0) CalendarWriteOperationState.NOT_APPLIED
        else CalendarWriteOperationState.APPLIED
        if (!updateJournalState(operation, operationState, eventId, error)) {
            return finishUnknown(operation, link, eventId)
        }
        val stored = requireNotNull(journal.findByOperation(operation.operationId))
        return result(
            stored,
            buildLink(
                stored,
                when {
                    error == CalendarWriteError.MISSING_EVENT -> CalendarEventLinkState.MISSING
                    unchangedWithoutAffectedRow -> CalendarEventLinkState.CONFIRMED
                    else -> CalendarEventLinkState.DIVERGED
                },
                error,
            ),
            error,
        )
    }

    /** Verifies a durable link by provenance URI only and never creates or mutates a provider row. */
    @Synchronized
    fun verify(link: CalendarEventLink): CalendarWriteResult {
        val verified = verifyByUri(link)
        return result(null, verified.link, verified.error)
    }

    /** Reconciles a saved link by URI only and updates its matching journal operation when known. */
    @Synchronized
    fun reconcile(link: CalendarEventLink): CalendarWriteResult {
        val verified = verifyByUri(link)
        val matching = try {
            journal.findByActivity(link.activityId)
        } catch (_: Exception) {
            return result(
                null,
                verified.link.copy(state = CalendarEventLinkState.UNKNOWN, lastError = CalendarWriteError.STORAGE_UNAVAILABLE.name),
                CalendarWriteError.STORAGE_UNAVAILABLE,
            )
        }
            .lastOrNull { it.calendarId == link.calendarId && it.payloadHash == link.contentHash }
        val deleteAlreadyConfirmed = matching?.type == CalendarWriteOperationType.DELETE &&
            matching.state in setOf(CalendarWriteOperationState.APPLIED, CalendarWriteOperationState.RECONCILED) &&
            matching.errorCode == null && verified.link.state == CalendarEventLinkState.MISSING
        val resultError = if (deleteAlreadyConfirmed) null else verified.error
        val resultLink = if (deleteAlreadyConfirmed) verified.link.copy(lastError = null) else verified.link
        val stored = if (matching != null && verified.link.state != CalendarEventLinkState.UNKNOWN) {
            if (!updateJournalState(
                matching,
                CalendarWriteOperationState.RECONCILED,
                eventId = resultLink.eventId,
                errorCode = resultError,
            )) {
                return result(
                    matching,
                    resultLink.copy(state = CalendarEventLinkState.UNKNOWN, lastError = CalendarWriteError.STORAGE_UNAVAILABLE.name),
                    CalendarWriteError.STORAGE_UNAVAILABLE,
                )
            }
            runCatching { journal.findByOperation(matching.operationId) }.getOrNull() ?: matching
        } else {
            matching
        }
        return result(stored, resultLink, resultError)
    }

    /**
     * Rechecks PREPARED and UNKNOWN operations after process restart. It queries only each operation's
     * provenance URI and never retries an insert, update, or delete.
     */
    @Synchronized
    fun recoverPending(): List<CalendarWriteResult> {
        val snapshot = try {
            (journal as? CalendarWriteJournalSnapshot)?.allOperations().orEmpty()
        } catch (_: Exception) {
            return listOf(failure(CalendarWriteError.STORAGE_UNAVAILABLE))
        }
        return snapshot
            .filter { operation ->
                operation.state == CalendarWriteOperationState.PREPARED ||
                    operation.state == CalendarWriteOperationState.UNKNOWN ||
                    (operation.state == CalendarWriteOperationState.APPLIED &&
                        linkStore.findByActivity(operation.activityId)?.state in setOf(
                            null,
                            CalendarEventLinkState.PENDING,
                            CalendarEventLinkState.UNKNOWN,
                        ))
            }
            .map { operation -> synchronized(activityLock(operation.activityId)) { resolveOperation(operation) } }
    }

    /** Resolve a plan-local time with the same gap/overlap policy as PlanRevisionEngine. */
    fun draftFromLocalTime(
        activityId: String,
        planId: String,
        title: String,
        localDate: String,
        startMinute: Int,
        durationMinutes: Int,
        timezone: String,
    ): CalendarEventDraft {
        if (startMinute !in 0..1439 || durationMinutes !in 1..1440) {
            throw IllegalArgumentException(CalendarWriteError.INVALID_WINDOW.name)
        }
        val dateTime = try {
            java.time.LocalDate.parse(localDate).atStartOfDay().plusMinutes(startMinute.toLong())
        } catch (_: Exception) {
            throw IllegalArgumentException(CalendarWriteError.INVALID_WINDOW.name)
        }
        val zone = try {
            java.time.ZoneId.of(timezone)
        } catch (_: Exception) {
            throw IllegalArgumentException(CalendarWriteError.INVALID_WINDOW.name)
        }
        val validOffsets = zone.rules.getValidOffsets(dateTime)
        val start = when (validOffsets.size) {
            1 -> dateTime.atOffset(validOffsets.single()).toInstant()
            0 -> {
                val transition = zone.rules.getTransition(dateTime)
                    ?: throw IllegalArgumentException(CalendarWriteError.INVALID_WINDOW.name)
                transition.dateTimeAfter.atOffset(transition.offsetAfter).toInstant()
            }
            else -> dateTime.atOffset(validOffsets.first()).toInstant()
        }
        val end = start.plusSeconds(durationMinutes * 60L)
        return CalendarEventDraft(
            activityId = activityId,
            planId = planId,
            title = title,
            dtstartMillis = start.toEpochMilli(),
            dtendMillis = end.toEpochMilli(),
            timezone = timezone,
        )
    }

    private fun existingIdempotentOperation(
        idempotencyKey: String,
        type: CalendarWriteOperationType,
        draft: CalendarEventDraft?,
        calendarId: Long,
        payloadHash: String,
        activityId: String = draft?.activityId ?: "",
        planId: String = draft?.planId ?: "",
        eventId: Long? = null,
    ): CalendarWriteResult? {
        val existing = try {
            journal.findByIdempotencyKey(idempotencyKey)
        } catch (_: Exception) {
            return failure(CalendarWriteError.STORAGE_UNAVAILABLE)
        } ?: return null
        val same = existing.type == type && existing.calendarId == calendarId &&
            existing.payloadHash == payloadHash &&
            (activityId.isEmpty() || existing.activityId == activityId) &&
            (planId.isEmpty() || existing.planId == planId) &&
            (eventId == null || existing.eventId == eventId)
        if (!same) return failure(CalendarWriteError.IDEMPOTENCY_CONFLICT, buildLink(existing))
        if (existing.state in setOf(
                CalendarWriteOperationState.PREPARED,
                CalendarWriteOperationState.UNKNOWN,
                CalendarWriteOperationState.APPLIED,
                CalendarWriteOperationState.RECONCILED,
            )
        ) {
            return resolveOperation(existing)
        }
        return result(existing, buildLink(existing), existing.errorCode)
    }

    private fun record(operation: CalendarWriteOperation): CalendarWriteError? = try {
        journal.record(operation)
        null
    } catch (_: IllegalArgumentException) {
        CalendarWriteError.IDEMPOTENCY_CONFLICT
    } catch (_: Exception) {
        CalendarWriteError.STORAGE_UNAVAILABLE
    }

    private fun persistPendingLink(
        operation: CalendarWriteOperation,
        link: CalendarEventLink,
    ): CalendarWriteError? = try {
        linkStore.upsert(link)
        null
    } catch (_: Exception) {
        CalendarWriteError.STORAGE_UNAVAILABLE
    }

    private fun reuseExisting(
        operation: CalendarWriteOperation,
        draft: CalendarEventDraft,
        event: CalendarWriteEvent,
    ): CalendarWriteResult {
        val marker = markerFor(event, draft.provenanceUri)
        val error = when {
            marker == CalendarWriteMarker.AMBIGUOUS -> CalendarWriteError.PROVENANCE_AMBIGUOUS
            marker == CalendarWriteMarker.FOREIGN -> CalendarWriteError.FOREIGN_EVENT
            event.calendarId != operation.calendarId -> CalendarWriteError.WRONG_CALENDAR
            event.rrule != null -> CalendarWriteError.RECURRING_NOT_SUPPORTED
            event.deleted -> CalendarWriteError.MISSING_EVENT
            eventContentHash(event) != operation.payloadHash -> CalendarWriteError.READ_BACK_MISMATCH
            else -> null
        }
        val state = when (error) {
            null -> CalendarEventLinkState.CONFIRMED
            CalendarWriteError.READ_BACK_MISMATCH -> CalendarEventLinkState.DIVERGED
            CalendarWriteError.MISSING_EVENT -> CalendarEventLinkState.MISSING
            else -> CalendarEventLinkState.ORPHANED
        }
        val operationState = if (error == null) CalendarWriteOperationState.RECONCILED else CalendarWriteOperationState.NOT_APPLIED
        if (!updateJournalState(operation, operationState, event.eventId, error)) {
            return finishUnknown(operation, eventId = event.eventId)
        }
        val stored = journal.findByOperation(operation.operationId) ?: operation.copy(
            state = operationState,
            eventId = event.eventId,
            errorCode = error,
        )
        val safeEventId = if (error in setOf(
                CalendarWriteError.FOREIGN_EVENT,
                CalendarWriteError.PROVENANCE_AMBIGUOUS,
                CalendarWriteError.WRONG_CALENDAR,
            )
        ) null else event.eventId
        return result(stored, buildLink(stored, state, error).copy(eventId = safeEventId), error)
    }

    private fun verifyAfterEffect(
        operation: CalendarWriteOperation,
        draft: CalendarEventDraft,
        eventId: Long,
    ): CalendarWriteResult {
        val verified = verifyByUri(buildLink(operation, CalendarEventLinkState.CONFIRMED))
        if (verified.error in setOf(CalendarWriteError.PERMISSION_DENIED, CalendarWriteError.PROVIDER_UNAVAILABLE)) {
            return finishUnknown(operation, eventId = eventId)
        }
        if (!updateJournalState(operation, CalendarWriteOperationState.APPLIED, eventId, verified.error)) {
            return finishUnknown(operation, eventId = eventId)
        }
        val stored = journal.findByOperation(operation.operationId) ?: operation.copy(
            state = CalendarWriteOperationState.APPLIED,
            eventId = eventId,
            errorCode = verified.error,
        )
        val link = verified.link.copy(eventId = verified.link.eventId ?: eventId)
        return result(stored, link, verified.error)
    }

    private fun verifyByUri(link: CalendarEventLink): Verification {
        val query = queryByUri(link.provenanceUri)
        if (query.error != null) {
            return Verification(
                link.copy(
                    state = CalendarEventLinkState.UNKNOWN,
                    lastVerifiedAt = null,
                    lastError = query.error.name,
                ),
                query.error,
            )
        }
        val matches = query.events.orEmpty().filterNot { it.deleted }
        if (matches.isEmpty()) {
            return Verification(
                link.copy(
                    eventId = null,
                    state = CalendarEventLinkState.MISSING,
                    lastVerifiedAt = timestamp(),
                    lastError = CalendarWriteError.MISSING_EVENT.name,
                ),
                CalendarWriteError.MISSING_EVENT,
            )
        }
        if (matches.size > 1) {
            return Verification(
                link.copy(
                    eventId = null,
                    state = CalendarEventLinkState.ORPHANED,
                    lastVerifiedAt = timestamp(),
                    lastError = CalendarWriteError.DUPLICATE_URI.name,
                ),
                CalendarWriteError.DUPLICATE_URI,
            )
        }

        val event = matches.single()
        val error = when {
            markerFor(event, link.provenanceUri) == CalendarWriteMarker.AMBIGUOUS ->
                CalendarWriteError.PROVENANCE_AMBIGUOUS
            markerFor(event, link.provenanceUri) == CalendarWriteMarker.FOREIGN -> CalendarWriteError.FOREIGN_EVENT
            event.calendarId != link.calendarId -> CalendarWriteError.WRONG_CALENDAR
            event.rrule != null -> CalendarWriteError.RECURRING_NOT_SUPPORTED
            eventContentHash(event) != link.contentHash -> CalendarWriteError.READ_BACK_MISMATCH
            else -> null
        }
        val state = when (error) {
            null -> CalendarEventLinkState.CONFIRMED
            CalendarWriteError.READ_BACK_MISMATCH,
            CalendarWriteError.RECURRING_NOT_SUPPORTED -> CalendarEventLinkState.DIVERGED
            else -> CalendarEventLinkState.ORPHANED
        }
        return Verification(
            link.copy(
                eventId = event.eventId,
                state = state,
                lastVerifiedAt = timestamp(),
                lastError = error?.name,
            ),
            error,
        )
    }

    private fun resolveOperation(operation: CalendarWriteOperation): CalendarWriteResult {
        val query = queryByUri(provenanceUri(operation.planId, operation.activityId))
        if (query.error != null) {
            return result(
                operation,
                buildLink(operation, CalendarEventLinkState.UNKNOWN, query.error),
                query.error,
            )
        }

        val matches = query.events.orEmpty().filterNot { it.deleted }
        if (matches.size > 1) {
            return completeResolution(
                operation,
                CalendarWriteOperationState.RECONCILED,
                buildLink(operation, CalendarEventLinkState.ORPHANED, CalendarWriteError.DUPLICATE_URI)
                    .copy(eventId = null, lastVerifiedAt = timestamp()),
                CalendarWriteError.DUPLICATE_URI,
            )
        }
        if (matches.isEmpty()) {
            val deletionConfirmed = operation.type == CalendarWriteOperationType.DELETE &&
                operation.state in setOf(CalendarWriteOperationState.APPLIED, CalendarWriteOperationState.RECONCILED) &&
                operation.errorCode == null
            val operationState = when {
                deletionConfirmed -> CalendarWriteOperationState.RECONCILED
                operation.state in setOf(CalendarWriteOperationState.PREPARED, CalendarWriteOperationState.UNKNOWN) ->
                    CalendarWriteOperationState.NOT_APPLIED
                else -> CalendarWriteOperationState.RECONCILED
            }
            val error = if (deletionConfirmed) null else CalendarWriteError.MISSING_EVENT
            return completeResolution(
                operation,
                operationState,
                buildLink(operation, CalendarEventLinkState.MISSING, error)
                    .copy(eventId = null, lastVerifiedAt = timestamp()),
                error,
            )
        }

        val event = matches.single()
        val baseLink = buildLink(operation, CalendarEventLinkState.CONFIRMED)
        val marker = markerFor(event, baseLink.provenanceUri)
        val ownershipError = when {
            marker == CalendarWriteMarker.AMBIGUOUS -> CalendarWriteError.PROVENANCE_AMBIGUOUS
            marker == CalendarWriteMarker.FOREIGN -> CalendarWriteError.FOREIGN_EVENT
            event.calendarId != operation.calendarId -> CalendarWriteError.WRONG_CALENDAR
            event.rrule != null -> CalendarWriteError.RECURRING_NOT_SUPPORTED
            else -> null
        }
        if (ownershipError != null) {
            val state = if (ownershipError == CalendarWriteError.RECURRING_NOT_SUPPORTED) {
                CalendarEventLinkState.DIVERGED
            } else {
                CalendarEventLinkState.ORPHANED
            }
            return completeResolution(
                operation,
                CalendarWriteOperationState.RECONCILED,
                baseLink.copy(
                    eventId = if (state == CalendarEventLinkState.ORPHANED) null else event.eventId,
                    state = state,
                    lastVerifiedAt = timestamp(),
                    lastError = ownershipError.name,
                ),
                ownershipError,
            )
        }

        val observedHash = eventContentHash(event)
        if (operation.type == CalendarWriteOperationType.DELETE && observedHash == operation.payloadHash) {
            val unchanged = baseLink.copy(
                eventId = event.eventId,
                state = CalendarEventLinkState.CONFIRMED,
                lastVerifiedAt = timestamp(),
                lastError = null,
            )
            return completeResolution(
                operation,
                CalendarWriteOperationState.NOT_APPLIED,
                unchanged,
                CalendarWriteError.READ_BACK_MISMATCH,
            )
        }
        if (operation.type != CalendarWriteOperationType.DELETE && observedHash == operation.payloadHash) {
            return completeResolution(
                operation,
                CalendarWriteOperationState.RECONCILED,
                baseLink.copy(eventId = event.eventId, state = CalendarEventLinkState.CONFIRMED, lastVerifiedAt = timestamp()),
                null,
            )
        }

        if (operation.type == CalendarWriteOperationType.UPDATE &&
            operation.previousContentHash != null && observedHash == operation.previousContentHash
        ) {
            val oldLink = baseLink.copy(
                eventId = event.eventId,
                contentHash = operation.previousContentHash,
                state = CalendarEventLinkState.CONFIRMED,
                lastVerifiedAt = timestamp(),
                lastError = null,
            )
            return completeResolution(
                operation,
                CalendarWriteOperationState.NOT_APPLIED,
                oldLink,
                CalendarWriteError.READ_BACK_MISMATCH,
            )
        }

        val diverged = baseLink.copy(
            eventId = event.eventId,
            state = CalendarEventLinkState.DIVERGED,
            lastVerifiedAt = timestamp(),
            lastError = CalendarWriteError.READ_BACK_MISMATCH.name,
        )
        return completeResolution(
            operation,
            CalendarWriteOperationState.RECONCILED,
            diverged,
            CalendarWriteError.READ_BACK_MISMATCH,
        )
    }

    private fun completeResolution(
        operation: CalendarWriteOperation,
        operationState: CalendarWriteOperationState,
        link: CalendarEventLink,
        error: CalendarWriteError?,
    ): CalendarWriteResult {
        if (!persistLink(link)) {
            val unknown = link.copy(state = CalendarEventLinkState.UNKNOWN, lastError = CalendarWriteError.STORAGE_UNAVAILABLE.name)
            return result(operation, unknown, CalendarWriteError.STORAGE_UNAVAILABLE)
        }
        if (!updateJournalState(operation, operationState, link.eventId, error)) {
            val unknown = link.copy(state = CalendarEventLinkState.UNKNOWN, lastError = CalendarWriteError.STORAGE_UNAVAILABLE.name)
            return result(operation, unknown, CalendarWriteError.STORAGE_UNAVAILABLE)
        }
        val stored = journal.findByOperation(operation.operationId) ?: operation.copy(
            state = operationState,
            eventId = link.eventId ?: operation.eventId,
            errorCode = error,
        )
        return result(stored, link, error)
    }

    private fun queryByUri(uri: String): UriQuery {
        val permissions = try {
            provider.permissions()
        } catch (_: SecurityException) {
            return UriQuery(error = CalendarWriteError.PERMISSION_DENIED)
        } catch (_: Exception) {
            return UriQuery(error = CalendarWriteError.PROVIDER_UNAVAILABLE)
        }
        if (permissions.read != CalendarPermissionState.GRANTED) {
            return UriQuery(error = CalendarWriteError.PERMISSION_DENIED)
        }
        return try {
            UriQuery(events = provider.findByCustomAppUri(uri))
        } catch (_: SecurityException) {
            UriQuery(error = CalendarWriteError.PERMISSION_DENIED)
        } catch (_: Exception) {
            UriQuery(error = CalendarWriteError.PROVIDER_UNAVAILABLE)
        }
    }

    private fun inspectTarget(link: CalendarEventLink, eventId: Long): TargetCheck {
        val current = revalidateCalendar(link.calendarId)
        if (current.error != null) return TargetCheck(error = current.error)
        val event = try {
            provider.getEvent(eventId)
        } catch (_: SecurityException) {
            return TargetCheck(error = CalendarWriteError.PERMISSION_DENIED)
        } catch (_: Exception) {
            return TargetCheck(error = CalendarWriteError.PROVIDER_UNAVAILABLE)
        } ?: return TargetCheck(error = CalendarWriteError.MISSING_EVENT, link = link.copy(state = CalendarEventLinkState.MISSING))
        return validateTargetEvent(link, event)
    }

    private fun revalidateTarget(link: CalendarEventLink, eventId: Long): TargetCheck {
        val current = revalidateCalendar(link.calendarId)
        if (current.error != null) return TargetCheck(error = current.error)
        val event = try {
            provider.getEvent(eventId)
        } catch (_: SecurityException) {
            return TargetCheck(error = CalendarWriteError.PERMISSION_DENIED)
        } catch (_: Exception) {
            return TargetCheck(error = CalendarWriteError.PROVIDER_UNAVAILABLE)
        } ?: return TargetCheck(error = CalendarWriteError.MISSING_EVENT, link = link.copy(state = CalendarEventLinkState.MISSING))
        return validateTargetEvent(link, event)
    }

    private fun validateTargetEvent(link: CalendarEventLink, event: CalendarWriteEvent): TargetCheck {
        val error = when {
            event.eventId != link.eventId -> CalendarWriteError.MISSING_EVENT
            event.calendarId != link.calendarId -> CalendarWriteError.WRONG_CALENDAR
            markerFor(event, link.provenanceUri) == CalendarWriteMarker.AMBIGUOUS ->
                CalendarWriteError.PROVENANCE_AMBIGUOUS
            markerFor(event, link.provenanceUri) == CalendarWriteMarker.FOREIGN -> CalendarWriteError.FOREIGN_EVENT
            event.deleted -> CalendarWriteError.MISSING_EVENT
            event.rrule != null -> CalendarWriteError.RECURRING_NOT_SUPPORTED
            eventContentHash(event) != link.contentHash -> CalendarWriteError.READ_BACK_MISMATCH
            else -> null
        }
        val state = when (error) {
            CalendarWriteError.MISSING_EVENT -> CalendarEventLinkState.MISSING
            CalendarWriteError.RECURRING_NOT_SUPPORTED -> CalendarEventLinkState.DIVERGED
            null -> CalendarEventLinkState.CONFIRMED
            else -> CalendarEventLinkState.ORPHANED
        }
        return TargetCheck(error = error, link = link.copy(state = state, lastError = error?.name))
    }

    private fun revalidateCalendar(calendarId: Long): CalendarSelectionResult {
        val catalog = inspectCatalog()
        return selectCalendar(catalog, calendarId)
    }

    private fun finishWithoutEffect(
        operation: CalendarWriteOperation,
        error: CalendarWriteError,
        linkState: CalendarEventLinkState = CalendarEventLinkState.PENDING,
        previousLink: CalendarEventLink? = null,
    ): CalendarWriteResult {
        val persisted = updateJournalState(operation, CalendarWriteOperationState.NOT_APPLIED, errorCode = error)
        val effectiveError = if (persisted) error else CalendarWriteError.STORAGE_UNAVAILABLE
        val stored = journal.findByOperation(operation.operationId) ?: operation.copy(
            state = CalendarWriteOperationState.NOT_APPLIED,
            errorCode = effectiveError,
        )
        val link = previousLink?.copy(state = linkState, lastError = effectiveError.name)
            ?: buildLink(stored, linkState, effectiveError)
        return result(stored, link, effectiveError)
    }

    private fun finishUnknown(
        operation: CalendarWriteOperation,
        previousLink: CalendarEventLink? = null,
        eventId: Long? = null,
    ): CalendarWriteResult {
        val persisted = updateJournalState(
            operation,
            CalendarWriteOperationState.UNKNOWN,
            eventId,
            CalendarWriteError.UNKNOWN_RESULT,
        )
        val stored = journal.findByOperation(operation.operationId) ?: operation.copy(
            state = CalendarWriteOperationState.UNKNOWN,
            eventId = eventId ?: operation.eventId,
            errorCode = if (persisted) CalendarWriteError.UNKNOWN_RESULT else CalendarWriteError.STORAGE_UNAVAILABLE,
        )
        val link = previousLink?.copy(
            state = CalendarEventLinkState.UNKNOWN,
            lastVerifiedAt = null,
            lastError = if (persisted) CalendarWriteError.UNKNOWN_RESULT.name else CalendarWriteError.STORAGE_UNAVAILABLE.name,
        ) ?: buildLink(
            stored,
            CalendarEventLinkState.UNKNOWN,
            if (persisted) CalendarWriteError.UNKNOWN_RESULT else CalendarWriteError.STORAGE_UNAVAILABLE,
        )
        return result(
            stored,
            link,
            if (persisted) CalendarWriteError.UNKNOWN_RESULT else CalendarWriteError.STORAGE_UNAVAILABLE,
        )
    }

    private fun updateJournalState(
        operation: CalendarWriteOperation,
        state: CalendarWriteOperationState,
        eventId: Long? = null,
        errorCode: CalendarWriteError? = null,
    ): Boolean = try {
        journal.updateState(operation.operationId, state, eventId, errorCode)
        journal.findByOperation(operation.operationId) != null
    } catch (_: Exception) {
        false
    }

    private fun persistLink(link: CalendarEventLink): Boolean = try {
        linkStore.upsert(link)
        true
    } catch (_: Exception) {
        false
    }

    private fun permissionsBeforeEffect(): CalendarWriteError? {
        val permissions = try {
            provider.permissions()
        } catch (_: SecurityException) {
            return CalendarWriteError.PERMISSION_DENIED
        } catch (_: Exception) {
            return CalendarWriteError.PROVIDER_UNAVAILABLE
        }
        return if (permissions.granted) null else CalendarWriteError.PERMISSION_DENIED
    }

    private fun buildLink(
        operation: CalendarWriteOperation,
        state: CalendarEventLinkState = stateFor(operation),
        error: CalendarWriteError? = operation.errorCode,
    ): CalendarEventLink = CalendarEventLink(
        linkId = UUID.nameUUIDFromBytes(
            provenanceUri(operation.planId, operation.activityId).toByteArray(StandardCharsets.UTF_8),
        ).toString(),
        activityId = operation.activityId,
        planId = operation.planId,
        calendarId = operation.calendarId,
        eventId = operation.eventId,
        provenanceUri = provenanceUri(operation.planId, operation.activityId),
        state = state,
        contentHash = operation.payloadHash,
        lastVerifiedAt = if (state == CalendarEventLinkState.PENDING || state == CalendarEventLinkState.UNKNOWN) null else timestamp(),
        lastError = error?.name,
    )

    private fun stateFor(operation: CalendarWriteOperation): CalendarEventLinkState = when {
        operation.type == CalendarWriteOperationType.DELETE && operation.state == CalendarWriteOperationState.APPLIED ->
            CalendarEventLinkState.MISSING
        operation.errorCode == CalendarWriteError.DUPLICATE_URI -> CalendarEventLinkState.ORPHANED
        operation.errorCode == CalendarWriteError.READ_BACK_MISMATCH -> CalendarEventLinkState.DIVERGED
        operation.errorCode == CalendarWriteError.MISSING_EVENT -> CalendarEventLinkState.MISSING
        operation.errorCode in setOf(
            CalendarWriteError.FOREIGN_EVENT,
            CalendarWriteError.PROVENANCE_AMBIGUOUS,
            CalendarWriteError.WRONG_CALENDAR,
        ) -> CalendarEventLinkState.ORPHANED
        operation.state == CalendarWriteOperationState.UNKNOWN || operation.state == CalendarWriteOperationState.PREPARED ->
            CalendarEventLinkState.UNKNOWN
        operation.state == CalendarWriteOperationState.APPLIED || operation.state == CalendarWriteOperationState.RECONCILED ->
            CalendarEventLinkState.CONFIRMED
        else -> CalendarEventLinkState.PENDING
    }

    private fun validateConfirmedLink(link: CalendarEventLink, activityId: String, planId: String): CalendarWriteError? = when {
        link.state != CalendarEventLinkState.CONFIRMED -> CalendarWriteError.INVALID_LINK
        link.eventId == null -> CalendarWriteError.MISSING_EVENT
        link.activityId != activityId || link.planId != planId -> CalendarWriteError.INVALID_LINK
        link.provenanceUri != provenanceUri(link.planId, link.activityId) -> CalendarWriteError.PROVENANCE_AMBIGUOUS
        else -> null
    }

    private fun failure(
        error: CalendarWriteError,
        link: CalendarEventLink? = null,
    ): CalendarWriteResult = result(null, link?.copy(lastError = error.name), error)

    private fun result(
        operation: CalendarWriteOperation?,
        link: CalendarEventLink?,
        error: CalendarWriteError?,
    ): CalendarWriteResult {
        if (link == null) return CalendarWriteResult(operation, null, error)
        if (!persistLink(link)) {
            return CalendarWriteResult(
                operation,
                link.copy(state = CalendarEventLinkState.UNKNOWN, lastError = CalendarWriteError.STORAGE_UNAVAILABLE.name),
                CalendarWriteError.STORAGE_UNAVAILABLE,
            )
        }
        return CalendarWriteResult(operation, link, error)
    }

    private fun activityLock(activityId: String): Any = activityLocks.computeIfAbsent(activityId) { Any() }

    private fun timestamp(): Long = nowMillis().coerceAtLeast(0L)

    private fun deniedPermissions() = CalendarWritePermissions(
        CalendarPermissionState.DENIED,
        CalendarPermissionState.DENIED,
    )

    private fun unknownPermissions() = CalendarWritePermissions(
        CalendarPermissionState.UNKNOWN,
        CalendarPermissionState.UNKNOWN,
    )

    private data class TargetCheck(
        val error: CalendarWriteError? = null,
        val link: CalendarEventLink? = null,
    )

    private data class Verification(
        val link: CalendarEventLink,
        val error: CalendarWriteError?,
    )

    private data class UriQuery(
        val events: List<CalendarWriteEvent>? = null,
        val error: CalendarWriteError? = null,
    )

}
