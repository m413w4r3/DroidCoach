package dev.atlas.coach.calendar.write

import dev.atlas.coach.calendar.CALENDAR_ACCESS_CONTRIBUTOR
import dev.atlas.coach.calendar.CalendarDescriptor
import dev.atlas.coach.calendar.CalendarPermissionState
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Local-only CalendarContract write engine. It requires an explicit local calendar for each new
 * create and never chooses an event when provenance or URI uniqueness is uncertain.
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
) {
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
    ): CalendarWriteResult {
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
        if (!record(operation)) return failure(CalendarWriteError.IDEMPOTENCY_CONFLICT)

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
        return try {
            val eventId = provider.insert(calendar.id, draft, draft.provenanceUri)
            journal.updateState(operation.operationId, CalendarWriteOperationState.APPLIED, eventId)
            verifyAfterEffect(requireNotNull(journal.findByOperation(operation.operationId)), draft, eventId)
        } catch (_: Exception) {
            finishUnknown(operation)
        }
    }

    @Synchronized
    fun update(
        link: CalendarEventLink,
        draft: CalendarEventDraft,
        idempotencyKey: String,
        operationId: String = UUID.randomUUID().toString(),
    ): CalendarWriteResult {
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
        )
        if (!record(operation)) return failure(CalendarWriteError.IDEMPOTENCY_CONFLICT, link)
        beforeProviderEffect(operation)

        val currentTarget = revalidateTarget(link, eventId)
        if (currentTarget.error != null) return finishWithoutEffect(
            operation,
            currentTarget.error,
            currentTarget.link?.state ?: CalendarEventLinkState.MISSING,
            link,
        )
        return try {
            provider.update(eventId, link.calendarId, draft, draft.provenanceUri)
            journal.updateState(operation.operationId, CalendarWriteOperationState.APPLIED, eventId)
            verifyAfterEffect(requireNotNull(journal.findByOperation(operation.operationId)), draft, eventId)
        } catch (_: Exception) {
            finishUnknown(operation, link)
        }
    }

    @Synchronized
    fun delete(
        link: CalendarEventLink,
        idempotencyKey: String,
        operationId: String = UUID.randomUUID().toString(),
    ): CalendarWriteResult {
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
        if (!record(operation)) return failure(CalendarWriteError.IDEMPOTENCY_CONFLICT, link)
        beforeProviderEffect(operation)

        val currentTarget = revalidateTarget(link, eventId)
        if (currentTarget.error != null) return finishWithoutEffect(
            operation,
            currentTarget.error,
            currentTarget.link?.state ?: CalendarEventLinkState.MISSING,
            link,
        )
        try {
            provider.delete(eventId, link.calendarId, link.provenanceUri)
        } catch (_: Exception) {
            return finishUnknown(operation, link)
        }
        journal.updateState(operation.operationId, CalendarWriteOperationState.APPLIED, eventId)
        val readBack = try {
            provider.getEvent(eventId)
        } catch (_: Exception) {
            return finishUnknown(operation, link)
        }
        if (readBack == null || readBack.deleted) {
            val stored = requireNotNull(journal.findByOperation(operation.operationId))
            return result(stored, buildLink(stored, CalendarEventLinkState.MISSING), null)
        }
        journal.updateState(
            operation.operationId,
            CalendarWriteOperationState.APPLIED,
            eventId,
            CalendarWriteError.READ_BACK_MISMATCH,
        )
        val stored = requireNotNull(journal.findByOperation(operation.operationId))
        return result(
            stored,
            buildLink(stored, CalendarEventLinkState.DIVERGED, CalendarWriteError.READ_BACK_MISMATCH),
            CalendarWriteError.READ_BACK_MISMATCH,
        )
    }

    /** Reconciles a saved link by URI only; it never creates or mutates an event. */
    @Synchronized
    fun reconcile(link: CalendarEventLink): CalendarWriteResult {
        val permissions = try {
            provider.permissions()
        } catch (_: SecurityException) {
            return failure(CalendarWriteError.PERMISSION_DENIED, link)
        } catch (_: Exception) {
            return failure(CalendarWriteError.PROVIDER_UNAVAILABLE, link)
        }
        if (permissions.read != CalendarPermissionState.GRANTED) {
            return failure(CalendarWriteError.PERMISSION_DENIED, link)
        }
        val matches = try {
            provider.findByCustomAppUri(link.provenanceUri)
        } catch (_: SecurityException) {
            return failure(CalendarWriteError.PERMISSION_DENIED, link)
        } catch (_: Exception) {
            return failure(CalendarWriteError.PROVIDER_UNAVAILABLE, link)
        }
        val latest = journal.findByActivity(link.activityId)
            .lastOrNull { it.calendarId == link.calendarId && it.payloadHash == link.contentHash }
        if (matches.isEmpty()) {
            latest?.let {
                journal.updateState(
                    it.operationId,
                    CalendarWriteOperationState.RECONCILED,
                    errorCode = CalendarWriteError.MISSING_EVENT,
                )
            }
            val updated = link.copy(state = CalendarEventLinkState.MISSING, eventId = null, lastVerifiedAt = timestamp(), lastError = null)
            return result(latest?.let { journal.findByOperation(it.operationId) }, updated, null)
        }
        if (matches.size > 1) {
            latest?.let {
                journal.updateState(it.operationId, CalendarWriteOperationState.RECONCILED, errorCode = CalendarWriteError.DUPLICATE_URI)
            }
            val updated = link.copy(
                eventId = null,
                state = CalendarEventLinkState.ORPHANED,
                lastVerifiedAt = timestamp(),
                lastError = CalendarWriteError.DUPLICATE_URI.name,
            )
            return result(latest?.let { journal.findByOperation(it.operationId) }, updated, CalendarWriteError.DUPLICATE_URI)
        }

        val event = matches.single()
        val marker = markerFor(event, link.provenanceUri)
        val error = when {
            marker == CalendarWriteMarker.AMBIGUOUS -> CalendarWriteError.PROVENANCE_AMBIGUOUS
            marker == CalendarWriteMarker.FOREIGN -> CalendarWriteError.FOREIGN_EVENT
            event.calendarId != link.calendarId -> CalendarWriteError.WRONG_CALENDAR
            event.rrule != null -> CalendarWriteError.RECURRING_NOT_SUPPORTED
            event.deleted -> CalendarWriteError.MISSING_EVENT
            eventContentHash(event) != link.contentHash -> CalendarWriteError.READ_BACK_MISMATCH
            else -> null
        }
        val state = when (error) {
            null -> CalendarEventLinkState.CONFIRMED
            CalendarWriteError.READ_BACK_MISMATCH -> CalendarEventLinkState.DIVERGED
            CalendarWriteError.MISSING_EVENT -> CalendarEventLinkState.MISSING
            else -> CalendarEventLinkState.ORPHANED
        }
        latest?.let {
            journal.updateState(it.operationId, CalendarWriteOperationState.RECONCILED, event.eventId, error)
        }
        val updated = link.copy(
            eventId = event.eventId,
            state = state,
            lastVerifiedAt = timestamp(),
            lastError = error?.name,
        )
        return result(latest?.let { journal.findByOperation(it.operationId) }, updated, error)
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
        val existing = journal.findByIdempotencyKey(idempotencyKey) ?: return null
        val same = existing.type == type && existing.calendarId == calendarId &&
            existing.payloadHash == payloadHash &&
            (activityId.isEmpty() || existing.activityId == activityId) &&
            (planId.isEmpty() || existing.planId == planId) &&
            (eventId == null || existing.eventId == eventId)
        if (!same) return failure(CalendarWriteError.IDEMPOTENCY_CONFLICT, buildLink(existing))
        if (existing.state == CalendarWriteOperationState.UNKNOWN || existing.state == CalendarWriteOperationState.PREPARED) {
            val link = buildLink(existing, CalendarEventLinkState.UNKNOWN, CalendarWriteError.UNKNOWN_RESULT)
            return reconcile(link)
        }
        if (type == CalendarWriteOperationType.DELETE &&
            existing.state in setOf(CalendarWriteOperationState.APPLIED, CalendarWriteOperationState.RECONCILED)
        ) {
            return reconcile(buildLink(existing))
        }
        return result(existing, buildLink(existing), existing.errorCode)
    }

    private fun record(operation: CalendarWriteOperation): Boolean = try {
        journal.record(operation)
        true
    } catch (_: IllegalArgumentException) {
        false
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
        journal.updateState(operation.operationId, operationState, event.eventId, error)
        val stored = requireNotNull(journal.findByOperation(operation.operationId))
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
        val event = try {
            provider.getEvent(eventId)
        } catch (_: SecurityException) {
            return finishUnknown(operation)
        } catch (_: Exception) {
            return finishUnknown(operation)
        }
        val valid = event != null && markerFor(event, draft.provenanceUri) == CalendarWriteMarker.OWNED &&
            event.eventId == eventId && event.calendarId == operation.calendarId && !event.deleted &&
            event.rrule == null && eventContentHash(event) == operation.payloadHash
        if (!valid) {
            journal.updateState(operation.operationId, CalendarWriteOperationState.APPLIED, eventId,
                CalendarWriteError.READ_BACK_MISMATCH)
            val stored = requireNotNull(journal.findByOperation(operation.operationId))
            return result(
                stored,
                buildLink(stored, CalendarEventLinkState.DIVERGED, CalendarWriteError.READ_BACK_MISMATCH),
                CalendarWriteError.READ_BACK_MISMATCH,
            )
        }
        journal.updateState(operation.operationId, CalendarWriteOperationState.APPLIED, eventId, errorCode = null)
        val stored = requireNotNull(journal.findByOperation(operation.operationId))
        return result(stored, buildLink(stored, CalendarEventLinkState.CONFIRMED), null)
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
        journal.updateState(operation.operationId, CalendarWriteOperationState.NOT_APPLIED, errorCode = error)
        val stored = journal.findByOperation(operation.operationId) ?: operation.copy(
            state = CalendarWriteOperationState.NOT_APPLIED,
            errorCode = error,
        )
        val link = previousLink?.copy(state = linkState, lastError = error.name)
            ?: buildLink(stored, linkState, error)
        return result(stored, link, error)
    }

    private fun finishUnknown(
        operation: CalendarWriteOperation,
        previousLink: CalendarEventLink? = null,
    ): CalendarWriteResult {
        journal.updateState(operation.operationId, CalendarWriteOperationState.UNKNOWN, errorCode = CalendarWriteError.UNKNOWN_RESULT)
        val stored = requireNotNull(journal.findByOperation(operation.operationId))
        val link = previousLink?.copy(
            state = CalendarEventLinkState.UNKNOWN,
            lastVerifiedAt = null,
            lastError = CalendarWriteError.UNKNOWN_RESULT.name,
        ) ?: buildLink(stored, CalendarEventLinkState.UNKNOWN, CalendarWriteError.UNKNOWN_RESULT)
        return result(stored, link, CalendarWriteError.UNKNOWN_RESULT)
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
    ): CalendarWriteResult = CalendarWriteResult(null, link?.copy(lastError = error.name), error)

    private fun result(
        operation: CalendarWriteOperation?,
        link: CalendarEventLink?,
        error: CalendarWriteError?,
    ): CalendarWriteResult = CalendarWriteResult(operation, link, error)

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
}
