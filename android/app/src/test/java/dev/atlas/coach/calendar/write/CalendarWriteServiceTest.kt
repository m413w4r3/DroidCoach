package dev.atlas.coach.calendar.write

import dev.atlas.coach.calendar.CalendarDescriptor
import dev.atlas.coach.calendar.CalendarPermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.time.Instant

class CalendarWriteServiceTest {
    @Test
    fun insertCreatesOnceAndConfirmsLink() {
        val harness = Harness()

        val result = harness.service.create(CALENDAR_ID, draft(), "create-1")

        assertTrue(result.successful)
        assertEquals(CalendarEventLinkState.CONFIRMED, result.link?.state)
        assertEquals(1, harness.provider.insertCalls)
        assertEquals(CalendarWriteOperationState.APPLIED, result.operation?.state)
        assertEquals("atlas://plan/$PLAN_ID/activity/$ACTIVITY_ID", result.link?.provenanceUri)
        assertEquals(draft().contentHash(CALENDAR_ID), result.link?.contentHash)

        val updated = harness.service.update(requireNotNull(result.link), draft(title = "Revised"), "update-once")
        assertTrue(updated.successful)
        assertEquals(CalendarEventLinkState.CONFIRMED, updated.link?.state)
        assertEquals(1, harness.provider.updateCalls)
        assertEquals("Revised", harness.provider.getEvent(requireNotNull(updated.link?.eventId))?.title)
    }

    @Test
    fun sameIdempotencyKeyDoesNotInsertTwice() {
        val harness = Harness()
        val first = harness.service.create(CALENDAR_ID, draft(), "same-key")

        val replay = harness.service.create(CALENDAR_ID, draft(), "same-key")

        assertEquals(1, harness.provider.insertCalls)
        assertEquals(first.link, replay.link)
        assertTrue(replay.successful)
    }

    @Test
    fun createRechecksWritePermissionImmediatelyBeforeProviderMutation() {
        val provider = FakeCalendarWriteProvider()
        val service = CalendarWriteService(
            provider = provider,
            journal = InMemoryCalendarWriteJournal(),
            nowMillis = { NOW },
            beforeFinalPermissionRecheck = { provider.permissionsForCall = { deniedPermissions() } },
        )

        val result = service.create(CALENDAR_ID, draft(), "permission-race-create")

        assertEquals(CalendarWriteError.PERMISSION_DENIED, result.error)
        assertEquals(0, provider.insertCalls)
        assertTrue(provider.events.isEmpty())
    }

    @Test
    fun updateRechecksWritePermissionImmediatelyBeforeProviderMutation() {
        val harness = Harness()
        val created = harness.service.create(CALENDAR_ID, draft(), "permission-race-update-source")
        val service = CalendarWriteService(
            provider = harness.provider,
            journal = harness.journal,
            nowMillis = { NOW },
            beforeFinalPermissionRecheck = { harness.provider.permissionsForCall = { deniedPermissions() } },
        )

        val result = service.update(
            requireNotNull(created.link),
            draft(title = "Must remain original"),
            "permission-race-update",
        )

        assertEquals(CalendarWriteError.PERMISSION_DENIED, result.error)
        assertEquals(0, harness.provider.updateCalls)
        assertEquals("Focus", harness.provider.getEvent(requireNotNull(created.link?.eventId))?.title)
    }

    @Test
    fun deleteRechecksWritePermissionImmediatelyBeforeProviderMutation() {
        val harness = Harness()
        val created = harness.service.create(CALENDAR_ID, draft(), "permission-race-delete-source")
        val service = CalendarWriteService(
            provider = harness.provider,
            journal = harness.journal,
            nowMillis = { NOW },
            beforeFinalPermissionRecheck = { harness.provider.permissionsForCall = { deniedPermissions() } },
        )

        val result = service.delete(requireNotNull(created.link), "permission-race-delete")

        assertEquals(CalendarWriteError.PERMISSION_DENIED, result.error)
        assertEquals(0, harness.provider.deleteCalls)
        assertNotNull(harness.provider.getEvent(requireNotNull(created.link?.eventId)))
    }

    @Test
    fun sameKeyDifferentPayloadIsRejected() {
        val harness = Harness()
        harness.service.create(CALENDAR_ID, draft(), "conflicting-key")

        val result = harness.service.create(CALENDAR_ID, draft(title = "Different"), "conflicting-key")

        assertEquals(CalendarWriteError.IDEMPOTENCY_CONFLICT, result.error)
        assertEquals(1, harness.provider.insertCalls)
    }

    @Test
    fun fileJournalAndLinkIndexesSurviveServiceRestart() {
        val directory = Files.createTempDirectory("calendar-write-restart").toFile()
        val provider = FakeCalendarWriteProvider()
        val firstService = CalendarWriteService(
            provider = provider,
            journal = FileCalendarWriteJournal(File(directory, "journal")),
            nowMillis = { NOW },
            linkStore = FileCalendarWriteLinkStore(File(directory, "links")),
        )

        val created = firstService.create(CALENDAR_ID, draft(), "durable-create")
        val eventId = requireNotNull(created.link?.eventId)

        val restartedLinks = FileCalendarWriteLinkStore(File(directory, "links"))
        assertEquals(created.link, restartedLinks.findByActivity(ACTIVITY_ID))
        assertEquals(created.link, restartedLinks.findByEventId(eventId))
        assertEquals(created.link, restartedLinks.findByProvenanceUri(draft().provenanceUri))

        val restartedService = CalendarWriteService(
            provider = provider,
            journal = FileCalendarWriteJournal(File(directory, "journal")),
            nowMillis = { NOW },
            linkStore = restartedLinks,
        )
        val replay = restartedService.create(CALENDAR_ID, draft(), "durable-create")

        assertEquals(1, provider.insertCalls)
        assertEquals(CalendarEventLinkState.CONFIRMED, replay.link?.state)
        assertEquals(CalendarWriteOperationState.RECONCILED, replay.operation?.state)
    }

    @Test
    fun restartRecoversCrashBeforeEffectWithoutRetrying() {
        val directory = Files.createTempDirectory("calendar-write-before-effect").toFile()
        val provider = FakeCalendarWriteProvider()
        val journalPath = File(directory, "journal")
        val linksPath = File(directory, "links")
        val crashedService = CalendarWriteService(
            provider = provider,
            journal = FileCalendarWriteJournal(journalPath),
            nowMillis = { NOW },
            beforeProviderEffect = { throw SimulatedCrash() },
            linkStore = FileCalendarWriteLinkStore(linksPath),
        )

        try {
            crashedService.create(CALENDAR_ID, draft(), "crash-before-effect")
            throw AssertionError("expected simulated process death")
        } catch (_: SimulatedCrash) {
            // PREPARED plus its pending link were committed before the provider request.
        }

        val restartedJournal = FileCalendarWriteJournal(journalPath)
        val restartedLinks = FileCalendarWriteLinkStore(linksPath)
        val restartedService = CalendarWriteService(provider, restartedJournal, nowMillis = { NOW }, linkStore = restartedLinks)
        val recovered = restartedService.recoverPending().single()
        val replay = restartedService.create(CALENDAR_ID, draft(), "crash-before-effect")

        assertEquals(0, provider.insertCalls)
        assertEquals(CalendarWriteOperationState.NOT_APPLIED, recovered.operation?.state)
        assertEquals(CalendarEventLinkState.MISSING, recovered.link?.state)
        assertEquals(CalendarWriteError.MISSING_EVENT, recovered.error)
        assertEquals(CalendarWriteOperationState.NOT_APPLIED, replay.operation?.state)
    }

    @Test
    fun restartRecoversCrashAfterEffectByUriWithoutDuplicate() {
        val directory = Files.createTempDirectory("calendar-write-after-effect").toFile()
        val provider = FakeCalendarWriteProvider()
        val journalPath = File(directory, "journal")
        val linksPath = File(directory, "links")
        val crashedService = CalendarWriteService(
            provider = provider,
            journal = FileCalendarWriteJournal(journalPath),
            nowMillis = { NOW },
            linkStore = FileCalendarWriteLinkStore(linksPath),
            afterProviderEffect = { throw SimulatedCrash() },
        )

        try {
            crashedService.create(CALENDAR_ID, draft(), "crash-after-effect")
            throw AssertionError("expected simulated process death")
        } catch (_: SimulatedCrash) {
            // The provider row exists while the last durable operation is still PREPARED.
        }

        val restartedJournal = FileCalendarWriteJournal(journalPath)
        val restartedLinks = FileCalendarWriteLinkStore(linksPath)
        val restartedService = CalendarWriteService(provider, restartedJournal, nowMillis = { NOW }, linkStore = restartedLinks)
        val recovered = restartedService.recoverPending().single()

        assertEquals(1, provider.insertCalls)
        assertEquals(CalendarWriteOperationState.RECONCILED, recovered.operation?.state)
        assertEquals(CalendarEventLinkState.CONFIRMED, recovered.link?.state)
        assertEquals(recovered.link, restartedLinks.findByActivity(ACTIVITY_ID))
        assertEquals(1, provider.findByCustomAppUri(draft().provenanceUri).size)
    }

    @Test
    fun restartReconcilesUnknownOperationWithoutBlindRetry() {
        val directory = Files.createTempDirectory("calendar-write-unknown-restart").toFile()
        val provider = FakeCalendarWriteProvider().apply { throwAfterInsertWithoutCommit = true }
        val journalPath = File(directory, "journal")
        val linksPath = File(directory, "links")
        val firstService = CalendarWriteService(
            provider = provider,
            journal = FileCalendarWriteJournal(journalPath),
            nowMillis = { NOW },
            linkStore = FileCalendarWriteLinkStore(linksPath),
        )

        val first = firstService.create(CALENDAR_ID, draft(), "unknown-restart")
        provider.throwAfterInsertWithoutCommit = false
        val restartedService = CalendarWriteService(
            provider = provider,
            journal = FileCalendarWriteJournal(journalPath),
            nowMillis = { NOW },
            linkStore = FileCalendarWriteLinkStore(linksPath),
        )
        val recovered = restartedService.recoverPending().single()
        val replay = restartedService.create(CALENDAR_ID, draft(), "unknown-restart")

        assertEquals(CalendarWriteOperationState.UNKNOWN, first.operation?.state)
        assertEquals(CalendarWriteOperationState.NOT_APPLIED, recovered.operation?.state)
        assertEquals(CalendarEventLinkState.MISSING, recovered.link?.state)
        assertEquals(CalendarWriteOperationState.NOT_APPLIED, replay.operation?.state)
        assertEquals(1, provider.insertCalls)
        assertTrue(provider.events.isEmpty())
    }

    @Test
    fun appliedIdempotentReplayReverifiesExternalChanges() {
        val harness = Harness()
        val created = harness.service.create(CALENDAR_ID, draft(), "applied-reverify")
        harness.provider.mutate(requireNotNull(created.link?.eventId)) {
            it.copy(dtendMillis = it.dtendMillis + 60_000)
        }

        val replay = harness.service.create(CALENDAR_ID, draft(), "applied-reverify")

        assertEquals(CalendarWriteError.READ_BACK_MISMATCH, replay.error)
        assertEquals(CalendarEventLinkState.DIVERGED, replay.link?.state)
        assertEquals(1, harness.provider.insertCalls)
        assertEquals(0, harness.provider.updateCalls)
    }

    @Test
    fun externalTitleOrTimeEditDivergesAndIsNeverAutoRewritten() {
        val harness = Harness()
        val created = harness.service.create(CALENDAR_ID, draft(), "external-edit-source")
        val eventId = requireNotNull(created.link?.eventId)
        harness.provider.mutate(eventId) { it.copy(title = "Changed outside ATLAS") }

        val verified = harness.service.verify(requireNotNull(created.link))
        val update = harness.service.update(requireNotNull(created.link), draft(title = "ATLAS rewrite"), "external-edit-update")
        val delete = harness.service.delete(requireNotNull(created.link), "external-edit-delete")

        assertEquals(CalendarEventLinkState.DIVERGED, verified.link?.state)
        assertEquals(CalendarWriteError.READ_BACK_MISMATCH, verified.error)
        assertEquals(CalendarWriteError.READ_BACK_MISMATCH, update.error)
        assertEquals(CalendarWriteError.READ_BACK_MISMATCH, delete.error)
        assertEquals(0, harness.provider.updateCalls)
        assertEquals(0, harness.provider.deleteCalls)
        assertEquals("Changed outside ATLAS", harness.provider.getEvent(eventId)?.title)
    }

    @Test
    fun duplicateIdempotencyKeyIsRejectedByFileJournal() {
        val directory = Files.createTempDirectory("calendar-write-collision").toFile()
        val journal = FileCalendarWriteJournal(directory)
        journal.record(operation(OPERATION_ID_1, "shared-key"))

        try {
            journal.record(operation(OPERATION_ID_2, "shared-key"))
            throw AssertionError("expected idempotency collision rejection")
        } catch (_: IllegalArgumentException) {
            // A second complete operation cannot claim the same key.
        }

        assertEquals(OPERATION_ID_1, journal.findByIdempotencyKey("shared-key")?.operationId)
        assertNull(journal.findByOperation(OPERATION_ID_2))
    }

    @Test
    fun corruptAndIncompleteFilesDoNotHideCompleteJournalRecords() {
        val directory = Files.createTempDirectory("calendar-write-corrupt").toFile()
        val journal = FileCalendarWriteJournal(directory)
        journal.record(operation(OPERATION_ID_1, "complete-one"))
        journal.record(operation(OPERATION_ID_2, "complete-two"))
        journal.updateState(OPERATION_ID_1, CalendarWriteOperationState.APPLIED, eventId = 15L)
        File(directory, "${OPERATION_ID_1}.json.interrupted.tmp").writeText("{partial")
        File(directory, "$OPERATION_ID_3.json").writeText("{broken json")

        val restarted = FileCalendarWriteJournal(directory)

        assertEquals(CalendarWriteOperationState.APPLIED, restarted.findByOperation(OPERATION_ID_1)?.state)
        assertEquals(OPERATION_ID_2, restarted.findByIdempotencyKey("complete-two")?.operationId)
        assertNull(restarted.findByOperation(OPERATION_ID_3))
        assertTrue(File(directory, "$OPERATION_ID_3.json").exists())
    }

    @Test
    fun corruptReplacementKeepsLastCompleteInMemoryState() {
        val directory = Files.createTempDirectory("calendar-write-last-complete").toFile()
        val journal = FileCalendarWriteJournal(directory)
        journal.record(operation(OPERATION_ID_1, "last-complete"))
        journal.updateState(OPERATION_ID_1, CalendarWriteOperationState.APPLIED, eventId = 19L)
        File(directory, "$OPERATION_ID_1.json").writeText("{broken replacement")

        assertEquals(CalendarWriteOperationState.APPLIED, journal.findByOperation(OPERATION_ID_1)?.state)
        assertEquals(19L, journal.findByOperation(OPERATION_ID_1)?.eventId)
    }

    @Test
    fun journalMigrationHookCanUpgradeOlderRecordVersion() {
        val directory = Files.createTempDirectory("calendar-write-migration").toFile()
        val expected = operation(OPERATION_ID_1, "migrated-key")
        val oldRecord = JSONObject()
            .put("schema_version", 0)
            .put("operation_id", expected.operationId)
            .put("type", expected.type.name)
            .put("activity_id", expected.activityId)
            .put("plan_id", expected.planId)
            .put("calendar_id", expected.calendarId)
            .put("event_id", JSONObject.NULL)
            .put("payload_hash", expected.payloadHash)
            .put("idempotency_key", expected.idempotencyKey)
            .put("state", expected.state.name)
            .put("error_code", JSONObject.NULL)
        File(directory, "${expected.operationId}.json").writeText(oldRecord.toString())

        val journal = FileCalendarWriteJournal(directory) { version, record ->
            assertEquals(0, version)
            record.put("schema_version", 1)
        }

        assertEquals(expected, journal.findByOperation(expected.operationId))
    }

    @Test
    fun preexistingUriIsReusedWithoutSecondInsert() {
        val harness = Harness()
        harness.provider.seed(draft(), CALENDAR_ID)

        val result = harness.service.create(CALENDAR_ID, draft(), "reuse-key")

        assertTrue(result.successful)
        assertEquals(CalendarEventLinkState.CONFIRMED, result.link?.state)
        assertEquals(1, harness.provider.events.size)
        assertEquals(0, harness.provider.insertCalls)
        assertEquals(CalendarWriteOperationState.RECONCILED, result.operation?.state)
    }

    @Test
    fun duplicateUriReconciliationStopsWithoutChoosing() {
        val harness = Harness()
        val created = harness.service.create(CALENDAR_ID, draft(), "duplicate-key")
        harness.provider.seed(draft(), CALENDAR_ID, eventId = 2L)

        val result = harness.service.reconcile(requireNotNull(created.link))

        assertEquals(CalendarWriteError.DUPLICATE_URI, result.error)
        assertEquals(CalendarEventLinkState.ORPHANED, result.link?.state)
        assertNull(result.link?.eventId)
        assertEquals(1, harness.provider.insertCalls)
    }

    @Test
    fun crashBeforeProviderCallLeavesPreparedAndNoEffect() {
        val provider = FakeCalendarWriteProvider()
        val journal = InMemoryCalendarWriteJournal()
        val service = CalendarWriteService(
            provider = provider,
            journal = journal,
            nowMillis = { NOW },
            beforeProviderEffect = { throw SimulatedCrash() },
        )

        try {
            service.create(CALENDAR_ID, draft(), "before-call")
            throw AssertionError("expected simulated crash")
        } catch (_: SimulatedCrash) {
            // Process loss is represented by the durable PREPARED row and no provider request.
        }

        assertEquals(0, provider.insertCalls)
        assertEquals(0, provider.uriSearchCalls)
        assertEquals(CalendarWriteOperationState.PREPARED, journal.findByIdempotencyKey("before-call")?.state)
    }

    @Test
    fun crashAfterProviderCommitReconcilesByUri() {
        val harness = Harness()
        harness.provider.afterInsertCommit = { throw SimulatedCrash() }

        val unknown = harness.service.create(CALENDAR_ID, draft(), "after-commit")

        assertEquals(CalendarWriteError.UNKNOWN_RESULT, unknown.error)
        assertEquals(CalendarWriteOperationState.UNKNOWN, unknown.operation?.state)
        assertEquals(1, harness.provider.insertCalls)
        val reconciled = harness.service.reconcile(requireNotNull(unknown.link))
        assertEquals(CalendarEventLinkState.CONFIRMED, reconciled.link?.state)
        assertEquals(CalendarWriteOperationState.RECONCILED, reconciled.operation?.state)
        assertEquals(1, harness.provider.insertCalls)
    }

    @Test
    fun unknownResultNeverRetriesBlindly() {
        val harness = Harness()
        harness.provider.throwAfterInsertWithoutCommit = true

        val first = harness.service.create(CALENDAR_ID, draft(), "unknown-replay")
        val replay = harness.service.create(CALENDAR_ID, draft(), "unknown-replay")

        assertEquals(CalendarWriteError.UNKNOWN_RESULT, first.error)
        assertFalse(first.toString().contains("PRIVATE_PROVIDER_SENTINEL"))
        assertEquals(CalendarEventLinkState.MISSING, replay.link?.state)
        assertEquals(1, harness.provider.insertCalls)
        assertEquals(0, harness.provider.events.size)
    }

    @Test
    fun readBackMismatchBecomesDiverged() {
        val harness = Harness()
        harness.provider.readBackTransform = { it.copy(title = "Provider changed title") }

        val result = harness.service.create(CALENDAR_ID, draft(), "mismatch")

        assertEquals(CalendarWriteError.READ_BACK_MISMATCH, result.error)
        assertEquals(CalendarEventLinkState.DIVERGED, result.link?.state)
        assertEquals(CalendarWriteOperationState.APPLIED, result.operation?.state)
    }

    @Test
    fun foreignEventIsNeverUpdatedOrDeleted() {
        val harness = Harness()
        val created = harness.service.create(CALENDAR_ID, draft(), "foreign-source")
        harness.provider.mutate(requireNotNull(created.link?.eventId)) {
            it.copy(customAppPackage = "com.example.other", customAppUri = "content://foreign/1")
        }

        val update = harness.service.update(requireNotNull(created.link), draft(title = "Changed"), "foreign-update")
        val delete = harness.service.delete(requireNotNull(created.link), "foreign-delete")

        assertEquals(CalendarWriteError.FOREIGN_EVENT, update.error)
        assertEquals(CalendarWriteError.FOREIGN_EVENT, delete.error)
        assertEquals(0, harness.provider.updateCalls)
        assertEquals(0, harness.provider.deleteCalls)
    }

    @Test
    fun ambiguousMarkerIsReadOnly() {
        val harness = Harness()
        val created = harness.service.create(CALENDAR_ID, draft(), "ambiguous-source")
        harness.provider.mutate(requireNotNull(created.link?.eventId)) {
            it.copy(customAppPackage = ATLAS_WRITE_PACKAGE, customAppUri = null)
        }

        val update = harness.service.update(requireNotNull(created.link), draft(title = "Changed"), "ambiguous-update")
        val delete = harness.service.delete(requireNotNull(created.link), "ambiguous-delete")

        assertEquals(CalendarWriteError.PROVENANCE_AMBIGUOUS, update.error)
        assertEquals(CalendarWriteError.PROVENANCE_AMBIGUOUS, delete.error)
        assertEquals(0, harness.provider.updateCalls)
        assertEquals(0, harness.provider.deleteCalls)
    }

    @Test
    fun missingEventIsNotRecreated() {
        val harness = Harness()
        val created = harness.service.create(CALENDAR_ID, draft(), "missing-source")
        harness.provider.remove(requireNotNull(created.link?.eventId))

        val reconciled = harness.service.reconcile(requireNotNull(created.link))

        assertEquals(CalendarEventLinkState.MISSING, reconciled.link?.state)
        assertEquals(1, harness.provider.insertCalls)
        assertEquals(0, harness.provider.events.size)
    }

    @Test
    fun recurringEventIsRejected() {
        val harness = Harness()
        harness.provider.seed(draft(), CALENDAR_ID, rrule = "FREQ=DAILY")

        val result = harness.service.create(CALENDAR_ID, draft(), "recurring")

        assertEquals(CalendarWriteError.RECURRING_NOT_SUPPORTED, result.error)
        assertEquals(0, harness.provider.insertCalls)
        assertEquals(1, harness.provider.events.size)
    }

    @Test
    fun permissionRevokedBeforeEffectStops() {
        val provider = FakeCalendarWriteProvider().apply {
            permissionsForCall = { call ->
                if (call >= 2) deniedPermissions() else grantedPermissions()
            }
        }
        val journal = InMemoryCalendarWriteJournal()
        val service = CalendarWriteService(provider, journal, nowMillis = { NOW })

        val result = service.create(CALENDAR_ID, draft(), "revoked")

        assertEquals(CalendarWriteError.PERMISSION_DENIED, result.error)
        assertEquals(0, provider.insertCalls)
        assertEquals(CalendarWriteOperationState.NOT_APPLIED, result.operation?.state)
    }

    @Test
    fun permissionRevokedAtFinalCreateCheckStopsMutation() {
        val provider = FakeCalendarWriteProvider().apply {
            permissionsForCall = { call -> if (call >= 3) deniedPermissions() else grantedPermissions() }
        }
        val result = CalendarWriteService(provider, InMemoryCalendarWriteJournal(), nowMillis = { NOW })
            .create(CALENDAR_ID, draft(), "revoked-at-effect")

        assertEquals(CalendarWriteError.PERMISSION_DENIED, result.error)
        assertEquals(0, provider.insertCalls)
        assertTrue(provider.events.isEmpty())
    }

    @Test
    fun permissionRevocationBeforeUpdateAndDeleteEffectsStopsMutation() {
        val provider = FakeCalendarWriteProvider()
        val service = CalendarWriteService(provider, InMemoryCalendarWriteJournal(), nowMillis = { NOW })
        val created = service.create(CALENDAR_ID, draft(), "revocation-source")
        val link = requireNotNull(created.link)
        val revokeAt = provider.permissionCalls + 3
        provider.permissionsForCall = { call ->
            if (call >= revokeAt) deniedPermissions() else grantedPermissions()
        }

        val update = service.update(link, draft(title = "Denied update"), "revoked-update")
        val delete = service.delete(link, "revoked-delete")

        assertEquals(CalendarWriteError.PERMISSION_DENIED, update.error)
        assertEquals(CalendarWriteError.PERMISSION_DENIED, delete.error)
        assertEquals(0, provider.updateCalls)
        assertEquals(0, provider.deleteCalls)
        assertEquals(1, provider.events.size)
    }

    @Test
    fun unavailableCalendarProviderFailsClosed() {
        val provider = FakeCalendarWriteProvider().apply { calendarListUnavailable = true }

        val result = CalendarWriteService(provider, InMemoryCalendarWriteJournal(), nowMillis = { NOW })
            .create(CALENDAR_ID, draft(), "provider-unavailable")

        assertEquals(CalendarWriteError.PROVIDER_UNAVAILABLE, result.error)
        assertEquals(0, provider.insertCalls)
    }

    @Test
    fun dedicatedAtlasLocalCalendarIsAccepted() {
        val provider = FakeCalendarWriteProvider().apply {
            calendars = listOf(calendar(accountType = ATLAS_LOCAL_ACCOUNT_TYPE))
        }

        val result = CalendarWriteService(provider, InMemoryCalendarWriteJournal(), nowMillis = { NOW })
            .create(CALENDAR_ID, draft(), "atlas-local-calendar")

        assertNull(result.error)
        assertTrue(result.successful)
        assertEquals(1, provider.insertCalls)
    }

    @Test
    fun unknownAccountTypeThatMentionsAtlasIsRejected() {
        val provider = FakeCalendarWriteProvider().apply {
            calendars = listOf(calendar(accountType = "com.example.dev.atlas.coach.calendar"))
        }

        val result = CalendarWriteService(provider, InMemoryCalendarWriteJournal(), nowMillis = { NOW })
            .create(CALENDAR_ID, draft(), "lookalike-local-calendar")

        assertEquals(CalendarWriteError.CALENDAR_NOT_LOCAL, result.error)
        assertEquals(0, provider.insertCalls)
    }

    @Test
    fun nonLocalCalendarIsRejected() {
        val remote = FakeCalendarWriteProvider().apply {
            calendars = listOf(calendar(accountType = "com.google"))
        }
        val remoteResult = CalendarWriteService(remote, InMemoryCalendarWriteJournal(), nowMillis = { NOW })
            .create(CALENDAR_ID, draft(), "remote-calendar")
        assertEquals(CalendarWriteError.CALENDAR_NOT_LOCAL, remoteResult.error)
        assertEquals(0, remote.insertCalls)

        val invisible = FakeCalendarWriteProvider().apply {
            calendars = listOf(calendar(visible = false))
        }
        val invisibleResult = CalendarWriteService(invisible, InMemoryCalendarWriteJournal(), nowMillis = { NOW })
            .create(CALENDAR_ID, draft(), "invisible-calendar")
        assertEquals(CalendarWriteError.CALENDAR_NOT_LOCAL, invisibleResult.error)
        assertEquals(0, invisible.insertCalls)

        val insufficientAccess = FakeCalendarWriteProvider().apply {
            calendars = listOf(calendar(accessLevel = 499))
        }
        val accessResult = CalendarWriteService(insufficientAccess, InMemoryCalendarWriteJournal(), nowMillis = { NOW })
            .create(CALENDAR_ID, draft(), "access-calendar")
        assertEquals(CalendarWriteError.CALENDAR_NOT_LOCAL, accessResult.error)
        assertEquals(0, insufficientAccess.insertCalls)

        val noCalendar = FakeCalendarWriteProvider().apply { calendars = emptyList() }
        val noCalendarResult = CalendarWriteService(noCalendar, InMemoryCalendarWriteJournal(), nowMillis = { NOW })
            .create(null, draft(), "no-calendar")
        assertEquals(CalendarWriteError.CALENDAR_NOT_LOCAL, noCalendarResult.error)
        assertEquals(0, noCalendar.insertCalls)

        val noExplicitSelection = Harness()
        assertEquals(
            CalendarWriteError.CALENDAR_NOT_SELECTED,
            noExplicitSelection.service.create(null, draft(), "no-explicit-selection").error,
        )
        assertEquals(0, noExplicitSelection.provider.insertCalls)
    }

    @Test
    fun wrongCalendarIsRejected() {
        val harness = Harness()
        val created = harness.service.create(CALENDAR_ID, draft(), "wrong-calendar-source")
        harness.provider.mutate(requireNotNull(created.link?.eventId)) { it.copy(calendarId = OTHER_CALENDAR_ID) }

        val update = harness.service.update(requireNotNull(created.link), draft(title = "Changed"), "wrong-calendar-update")
        val delete = harness.service.delete(requireNotNull(created.link), "wrong-calendar-delete")

        assertEquals(CalendarWriteError.WRONG_CALENDAR, update.error)
        assertEquals(CalendarWriteError.WRONG_CALENDAR, delete.error)
        assertEquals(0, harness.provider.updateCalls)
        assertEquals(0, harness.provider.deleteCalls)
    }

    @Test
    fun dstSpringForwardAndFallBackProduceExpectedUtc() {
        val service = CalendarWriteService(FakeCalendarWriteProvider(), InMemoryCalendarWriteJournal())
        val spring = service.draftFromLocalTime(
            ACTIVITY_ID,
            PLAN_ID,
            "Spring",
            "2026-03-29",
            startMinute = 150,
            durationMinutes = 60,
            timezone = "Europe/Paris",
        )
        val fall = service.draftFromLocalTime(
            ACTIVITY_ID,
            PLAN_ID,
            "Fall",
            "2026-10-25",
            startMinute = 150,
            durationMinutes = 60,
            timezone = "Europe/Paris",
        )

        assertEquals(Instant.parse("2026-03-29T01:00:00Z").toEpochMilli(), spring.dtstartMillis)
        assertEquals(Instant.parse("2026-03-29T02:00:00Z").toEpochMilli(), spring.dtendMillis)
        assertEquals(Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(), fall.dtstartMillis)
        assertEquals(Instant.parse("2026-10-25T01:30:00Z").toEpochMilli(), fall.dtendMillis)
    }

    @Test
    fun deleteRequiresConfirmedMarkedLinkAndReadsBackAbsence() {
        val harness = Harness()
        val created = harness.service.create(CALENDAR_ID, draft(), "delete-source")

        val result = harness.service.delete(requireNotNull(created.link), "delete-key")

        assertNull(harness.provider.getEvent(requireNotNull(created.link?.eventId)))
        assertEquals(1, harness.provider.deleteCalls)
        assertEquals(CalendarEventLinkState.MISSING, result.link?.state)
        assertEquals(CalendarWriteOperationState.APPLIED, result.operation?.state)
        assertNull(result.error)
        assertTrue(result.successful)

        val notConfirmed = created.link!!.copy(state = CalendarEventLinkState.PENDING)
        val rejected = harness.service.delete(notConfirmed, "delete-pending")
        assertEquals(CalendarWriteError.INVALID_LINK, rejected.error)
        assertEquals(1, harness.provider.deleteCalls)
    }

    @Test
    fun deleteWithZeroRowsAndSilentDisappearanceIsNotReportedSuccessful() {
        val harness = Harness()
        val created = harness.service.create(CALENDAR_ID, draft(), "silent-delete-source")
        harness.provider.deleteReturnsZero = true
        harness.provider.removeRowDespiteZeroDeleteCount = true

        val result = harness.service.delete(requireNotNull(created.link), "silent-delete")

        assertEquals(0, harness.provider.events.size)
        assertEquals(CalendarWriteError.MISSING_EVENT, result.error)
        assertEquals(CalendarEventLinkState.MISSING, result.link?.state)
        assertFalse(result.successful)
    }

    @Test
    fun deleteWithZeroRowsAndPresentEventIsNotReportedSuccessful() {
        val harness = Harness()
        val created = harness.service.create(CALENDAR_ID, draft(), "zero-row-delete-source")
        harness.provider.deleteReturnsZero = true

        val result = harness.service.delete(requireNotNull(created.link), "zero-row-delete")

        assertEquals(1, harness.provider.events.size)
        assertEquals(CalendarWriteError.READ_BACK_MISMATCH, result.error)
        assertFalse(result.successful)
        assertEquals(1, harness.provider.deleteCalls)
    }
}

private class Harness {
    val provider = FakeCalendarWriteProvider()
    val journal = InMemoryCalendarWriteJournal()
    val service = CalendarWriteService(provider, journal, nowMillis = { NOW })
}

private class FakeCalendarWriteProvider : CalendarWriteProvider {
    var calendars: List<CalendarDescriptor> = listOf(calendar())
    var permissionsForCall: (Int) -> CalendarWritePermissions = { grantedPermissions() }
    var permissionCalls = 0
    var insertCalls = 0
    var updateCalls = 0
    var deleteCalls = 0
    var uriSearchCalls = 0
    var afterInsertCommit: (() -> Unit)? = null
    var throwAfterInsertWithoutCommit = false
    var calendarListUnavailable = false
    var deleteReturnsZero = false
    var removeRowDespiteZeroDeleteCount = false
    var readBackTransform: (CalendarWriteEvent) -> CalendarWriteEvent = { it }
    val events = linkedMapOf<Long, CalendarWriteEvent>()
    private var nextId = 1L

    override fun permissions(): CalendarWritePermissions = permissionsForCall(++permissionCalls)

    override fun listCalendars(): List<CalendarDescriptor> {
        if (calendarListUnavailable) throw IllegalStateException("calendar provider unavailable")
        return calendars
    }

    override fun insert(calendarId: Long, draft: CalendarEventDraft, provenanceUri: String): Long {
        insertCalls++
        if (throwAfterInsertWithoutCommit) throw IllegalStateException("PRIVATE_PROVIDER_SENTINEL")
        val eventId = nextId++
        events[eventId] = event(calendarId, eventId, draft, provenanceUri)
        afterInsertCommit?.invoke()
        return eventId
    }

    override fun update(eventId: Long, calendarId: Long, draft: CalendarEventDraft, provenanceUri: String) {
        updateCalls++
        val current = requireNotNull(events[eventId])
        check(
            current.calendarId == calendarId && current.customAppPackage == ATLAS_WRITE_PACKAGE &&
                current.customAppUri == provenanceUri && current.rrule == null && !current.deleted,
        )
        events[eventId] = event(calendarId, eventId, draft, provenanceUri).copy(rrule = current.rrule)
    }

    override fun delete(eventId: Long, calendarId: Long, provenanceUri: String): Int {
        deleteCalls++
        val current = events[eventId] ?: return 0
        if (current.calendarId != calendarId || current.customAppPackage != ATLAS_WRITE_PACKAGE ||
            current.customAppUri != provenanceUri || current.rrule != null || current.deleted
        ) return 0
        if (deleteReturnsZero) {
            if (removeRowDespiteZeroDeleteCount) events.remove(eventId)
            return 0
        }
        events.remove(eventId)
        return 1
    }

    override fun getEvent(eventId: Long): CalendarWriteEvent? = events[eventId]?.let(readBackTransform)

    override fun findByCustomAppUri(provenanceUri: String): List<CalendarWriteEvent> {
        uriSearchCalls++
        return events.values.filter { it.customAppUri == provenanceUri }.map(readBackTransform)
    }

    fun seed(
        draft: CalendarEventDraft,
        calendarId: Long,
        eventId: Long = nextId++,
        rrule: String? = null,
    ) {
        nextId = maxOf(nextId, eventId + 1)
        events[eventId] = event(calendarId, eventId, draft, draft.provenanceUri).copy(rrule = rrule)
    }

    fun mutate(eventId: Long, transform: (CalendarWriteEvent) -> CalendarWriteEvent) {
        events[eventId] = transform(requireNotNull(events[eventId]))
    }

    fun remove(eventId: Long) {
        events.remove(eventId)
    }
}

private fun event(
    calendarId: Long,
    eventId: Long,
    draft: CalendarEventDraft,
    provenanceUri: String,
): CalendarWriteEvent = CalendarWriteEvent(
    eventId = eventId,
    calendarId = calendarId,
    title = draft.title,
    dtstartMillis = draft.dtstartMillis,
    dtendMillis = draft.dtendMillis,
    timezone = draft.timezone,
    allDay = false,
    deleted = false,
    rrule = null,
    customAppPackage = ATLAS_WRITE_PACKAGE,
    customAppUri = provenanceUri,
)

private fun draft(title: String = "Focus"): CalendarEventDraft = CalendarEventDraft(
    activityId = ACTIVITY_ID,
    planId = PLAN_ID,
    title = title,
    dtstartMillis = 1_798_000_000_000,
    dtendMillis = 1_798_003_600_000,
    timezone = "Europe/Paris",
)

private fun calendar(
    id: Long = CALENDAR_ID,
    accountType: String? = "LOCAL",
    visible: Boolean = true,
    accessLevel: Int = 500,
): CalendarDescriptor = CalendarDescriptor(
    id = id,
    displayName = "Local",
    accountName = null,
    accountType = accountType,
    ownerAccount = null,
    accessLevel = accessLevel,
    visible = visible,
    syncEvents = false,
    primary = false,
    timezone = "Europe/Paris",
    color = null,
)

private fun grantedPermissions() = CalendarWritePermissions(
    read = CalendarPermissionState.GRANTED,
    write = CalendarPermissionState.GRANTED,
)

private fun deniedPermissions() = CalendarWritePermissions(
    read = CalendarPermissionState.DENIED,
    write = CalendarPermissionState.DENIED,
)

private fun operation(operationId: String, idempotencyKey: String) = CalendarWriteOperation(
    operationId = operationId,
    type = CalendarWriteOperationType.CREATE,
    activityId = ACTIVITY_ID,
    planId = PLAN_ID,
    calendarId = CALENDAR_ID,
    eventId = null,
    payloadHash = draft().contentHash(CALENDAR_ID),
    idempotencyKey = idempotencyKey,
    state = CalendarWriteOperationState.PREPARED,
)

private class SimulatedCrash : RuntimeException()

private const val PLAN_ID = "00000000-0000-0000-0000-000000000001"
private const val ACTIVITY_ID = "00000000-0000-0000-0000-000000000002"
private const val OPERATION_ID_1 = "00000000-0000-0000-0000-000000000011"
private const val OPERATION_ID_2 = "00000000-0000-0000-0000-000000000012"
private const val OPERATION_ID_3 = "00000000-0000-0000-0000-000000000013"
private const val CALENDAR_ID = 10L
private const val OTHER_CALENDAR_ID = 11L
private const val NOW = 1_800_000_000_000L
