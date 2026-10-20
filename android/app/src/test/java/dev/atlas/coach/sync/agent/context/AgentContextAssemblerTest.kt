package dev.atlas.coach.sync.agent.context

import dev.atlas.coach.focus.FocusPhase
import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.focus.FocusStatus
import dev.atlas.coach.goals.GoalMetric
import dev.atlas.coach.goals.GoalRecord
import dev.atlas.coach.goals.GoalStatus
import dev.atlas.coach.habits.Habit
import dev.atlas.coach.habits.HabitCheckIn
import dev.atlas.coach.habits.HabitSchedule
import dev.atlas.coach.location.LocationSummary
import dev.atlas.coach.sync.DataScope
import dev.atlas.coach.sync.SyncDataCategory
import dev.atlas.coach.sync.SyncEvent
import dev.atlas.coach.sync.SyncEventType
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.coach.AgentConversationMessage
import dev.atlas.coach.sync.agent.coach.AgentMessageRole
import dev.atlas.coach.usage.ObservationConsent
import dev.atlas.coach.usage.ObservationPurpose
import dev.atlas.coach.usage.ObservationRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextAssemblerTest {
    private val assembler = AgentContextAssembler()

    @Test
    fun emptyContextHasExactPayloadShapeAndNoAvailableData() {
        val snapshot = assembler.assemble(
            mandate(shared = emptySet()),
            consentAtUtcMillis = emptyMap(),
            domains = AgentContextDomainViews(),
            nowUtcMillis = NOW,
        )

        assertEquals(emptyList<String>(), snapshot.consentedCategories)
        assertTrue(snapshot.categories.isEmpty())
        assertEquals("[]", snapshot.toMap()["consented_categories"].toString())
        assertEquals(emptyMap<String, List<String>>(), snapshot.toMap()["categories"])
        assertEquals(snapshot.toJsonString(), snapshot.preview().serializedPayload)
        assertTrue(snapshot.preview().availableCategories.isEmpty())
    }

    @Test
    fun focusCategoryIncludesOnlyRecentFinishedSessionsAndTheirActualMinutes() {
        val consentAt = NOW - 9L * DAY
        val sessions = listOf(
            session("finished", FocusStatus.FINISHED, NOW - 2L * HOUR, workSeconds = 25 * 60L, cycles = 1, remainingMillis = 0L),
            session("cancelled", FocusStatus.CANCELLED, NOW - HOUR, workSeconds = 90 * 60L, cycles = 0, remainingMillis = 0L),
            session("conflict", FocusStatus.FINISHED, NOW - HOUR, workSeconds = 90 * 60L, cycles = 1, remainingMillis = 0L),
            session("conflict", FocusStatus.CANCELLED, NOW - HOUR, workSeconds = 90 * 60L, cycles = 1, remainingMillis = 0L),
            session("running", FocusStatus.RUNNING, NOW - HOUR, workSeconds = 25 * 60L, cycles = 1, remainingMillis = 0L),
            session("outside-window", FocusStatus.FINISHED, NOW - 8L * DAY, workSeconds = 25 * 60L, cycles = 1, remainingMillis = 0L),
            session("before-consent", FocusStatus.FINISHED, consentAt - 1L, workSeconds = 25 * 60L, cycles = 1, remainingMillis = 0L),
        )
        val snapshot = assembler.assemble(
            mandate(shared = setOf("focus")),
            mapOf("focus" to consentAt),
            AgentContextDomainViews(focusSessions = sessions),
            NOW,
        )

        assertEquals(listOf("focus"), snapshot.consentedCategories)
        assertEquals(1, snapshot.categories.getValue("focus").size)
        assertTrue(snapshot.categories.getValue("focus").single().endsWith("| completed | 25 min"))
    }

    @Test
    fun multipleRealDomainsAreReadOnlyAndRawContentUsageTitlesAndCoachTextStayOut() {
        val consentAt = NOW - 6L * HOUR
        val usageConsent = ObservationConsent.grant(
            ObservationPurpose.GENTLE_FRICTION,
            setOf("org.example.fixtureapp"),
            nowUtc = consentAt,
        )
        val checkInDate = Instant.ofEpochMilli(NOW - HOUR).atZone(ZoneOffset.UTC).toLocalDate()
        val domains = AgentContextDomainViews(
            focusSessions = listOf(session("focus-1", FocusStatus.FINISHED, NOW - 2L * HOUR, 25 * 60L, 1, 0L)),
            habits = listOf(Habit("habit-1", "fixture habit title", HabitSchedule.daily(), consentAt - DAY)),
            habitCheckIns = listOf(checkIn("checkin-1", "habit-1", checkInDate, NOW - HOUR)),
            usageConsent = usageConsent,
            usageRecords = listOf(
                ObservationRecord("usage-1", "org.example.fixtureapp", NOW - 20L * 60_000L),
                ObservationRecord("usage-too-old", "org.example.fixtureapp", NOW - 25L * HOUR),
                // Historical views may retain a package removed from the current allowlist.
                ObservationRecord("usage-revoked-package", "org.example.revokedapp", NOW - 10L * 60_000L),
            ),
            contentActivity = listOf(contentEvent(NOW - 30L * 60_000L)),
            coachMessages = listOf(
                AgentConversationMessage(
                    id = "message-1", role = AgentMessageRole.USER, text = "fixture coach raw text",
                    createdAtUtcMillis = NOW - 25L * 60_000L,
                ),
            ),
        )
        val snapshot = assembler.assemble(
            mandate(shared = AgentMandate.ALLOWED_SHARED_CATEGORIES),
            AgentMandate.ALLOWED_SHARED_CATEGORIES.associateWith { consentAt },
            domains,
            NOW,
        )
        val json = snapshot.toJsonString()

        assertEquals(AgentMandate.ALLOWED_SHARED_CATEGORIES, snapshot.consentedCategories.toSet())
        assertEquals(
            setOf("focus", "habit", "content", "coach_interaction", "app_usage_summary"),
            snapshot.availableCategories,
        )
        assertTrue(json.contains("25 min"))
        assertTrue(json.contains("validation(s) d'habitude"))
        assertTrue(json.contains("titres et URL omis"))
        assertTrue(json.contains("texte omis"))
        assertTrue(json.contains("1 ouvertures observées | 1 applications"))
        assertFalse(json.contains("fixture habit title"))
        assertFalse(json.contains("fixture-content-title"))
        assertFalse(json.contains("https://fixture.example/secret"))
        assertFalse(json.contains("fixture coach raw text"))
        assertFalse(json.contains("fixture focus title"))
        assertFalse(json.contains("org.example.fixtureapp"))
        assertEquals(json, snapshot.preview().serializedPayload)
        assertEquals(snapshot.consentedCategories, snapshot.preview().authorizedCategories)
        assertEquals(snapshot.categories.keys, snapshot.preview().availableCategories)

        val domainConsent = AgentContextDomainConsent(usageConsent = usageConsent)
        val stillAllowed = snapshot.minimizeFor(
            mandate(shared = AgentMandate.ALLOWED_SHARED_CATEGORIES),
            AgentMandate.ALLOWED_SHARED_CATEGORIES.associateWith { consentAt },
            NOW,
            currentDomainConsent = domainConsent,
        )
        assertTrue("app_usage_summary" in stillAllowed.categories)
        val locallyRevoked = snapshot.minimizeFor(
            mandate(shared = AgentMandate.ALLOWED_SHARED_CATEGORIES),
            AgentMandate.ALLOWED_SHARED_CATEGORIES.associateWith { consentAt },
            NOW,
            currentDomainConsent = AgentContextDomainConsent(),
        )
        assertTrue("app_usage_summary" !in locallyRevoked.categories)
        val renewedUsageConsent = snapshot.minimizeFor(
            mandate(shared = AgentMandate.ALLOWED_SHARED_CATEGORIES),
            AgentMandate.ALLOWED_SHARED_CATEGORIES.associateWith { consentAt },
            NOW,
            currentDomainConsent = AgentContextDomainConsent(
                usageConsent = usageConsent.copy(grantedAtUtc = NOW - 10L * 60_000L),
            ),
        )
        assertTrue("app_usage_summary" !in renewedUsageConsent.categories)

        val onlyFocus = assembler.assemble(
            mandate(shared = setOf("focus")),
            AgentMandate.ALLOWED_SHARED_CATEGORIES.associateWith { consentAt },
            domains,
            NOW,
        )
        assertEquals(setOf("focus"), onlyFocus.categories.keys)
        assertFalse(onlyFocus.toJsonString().contains("fixture coach raw text"))
        assertFalse(onlyFocus.toJsonString().contains("org.example.fixtureapp"))
    }

    @Test
    fun onlyActiveGoalsAreIncludedInAgentContextForAllGoalStatuses() {
        val consentAt = NOW - HOUR
        val goals = GoalStatus.entries.map { status ->
            GoalRecord(
                id = "goal-${status.wireValue}",
                title = "Fixture ${status.wireValue} goal",
                description = "Synthetic test goal",
                metric = GoalMetric.FOCUS_MINUTES,
                targetValue = 120,
                status = status,
                createdAt = consentAt,
                updatedAt = NOW,
            )
        }

        val snapshot = assembler.assemble(
            mandate(shared = setOf("goals")),
            mapOf("goals" to consentAt),
            AgentContextDomainViews(goals = goals),
            NOW,
        )

        assertEquals(
            setOf(GoalStatus.ACTIVE, GoalStatus.COMPLETED, GoalStatus.PAUSED, GoalStatus.ABANDONED),
            goals.map { it.status }.toSet(),
        )
        assertEquals(listOf("goals"), snapshot.consentedCategories)
        val summaries = snapshot.categories.getValue("goals")
        assertEquals(1, summaries.size)
        assertTrue(summaries.single().contains("Fixture active goal"))
        assertFalse(summaries.single().contains("completed"))
        assertFalse(summaries.single().contains("paused"))
        assertFalse(summaries.single().contains("abandoned"))
    }

    @Test
    fun revokedCategoryAndRenewedConsentAreAppliedToTheSnapshotBeforeTransport() {
        val consentAt = NOW - 2L * HOUR
        val snapshot = assembler.assemble(
            mandate(shared = setOf("focus", "habit")),
            mapOf("focus" to consentAt, "habit" to consentAt),
            AgentContextDomainViews(
                focusSessions = listOf(session("focus-1", FocusStatus.FINISHED, NOW - HOUR, 25 * 60L, 1, 0L)),
                habits = listOf(Habit("habit-1", "fixture", HabitSchedule.daily(), consentAt)),
                habitCheckIns = listOf(checkIn("checkin-1", "habit-1", utcDate(NOW), NOW - 30L * 60_000L)),
            ),
            NOW,
        )

        val revoked = snapshot.minimizeFor(
            mandate(shared = setOf("habit")),
            mapOf("habit" to consentAt),
            NOW,
        )
        assertEquals(listOf("habit"), revoked.consentedCategories)
        assertEquals(setOf("habit"), revoked.categories.keys)

        val renewedAfterItems = revoked.minimizeFor(
            mandate(shared = setOf("habit")),
            mapOf("habit" to NOW - 10L * 60_000L),
            NOW,
        )
        assertEquals(listOf("habit"), renewedAfterItems.consentedCategories)
        assertTrue(renewedAfterItems.categories.isEmpty())
    }

    @Test
    fun missingHistoricalConsentDateIncludesNoDataUntilExplicitMigrationAtNow() {
        val oldSession = session("old", FocusStatus.FINISHED, NOW - 40L * DAY, 25 * 60L, 1, 0L)
        val noDate = assembler.assemble(
            mandate(shared = setOf("focus")),
            consentAtUtcMillis = emptyMap(),
            domains = AgentContextDomainViews(focusSessions = listOf(oldSession)),
            nowUtcMillis = NOW,
        )
        assertEquals(listOf("focus"), noDate.consentedCategories)
        assertTrue(noDate.categories.isEmpty())

        val epochDate = assembler.assemble(
            mandate(shared = setOf("focus")),
            consentAtUtcMillis = mapOf("focus" to 0L),
            domains = AgentContextDomainViews(focusSessions = listOf(oldSession)),
            nowUtcMillis = NOW,
        )
        assertTrue(epochDate.categories.isEmpty())

        val migrated = AgentContextConsentMigration.migrateLegacyConsentDatesToNow(setOf("focus"), NOW)
        assertEquals(NOW, migrated.getValue("focus"))
        assertTrue(migrated.getValue("focus") > 0L)
        val afterMigration = assembler.assemble(
            mandate(shared = setOf("focus")),
            consentAtUtcMillis = migrated,
            domains = AgentContextDomainViews(focusSessions = listOf(oldSession)),
            nowUtcMillis = NOW,
        )
        assertTrue(afterMigration.categories.isEmpty())
    }

    @Test
    fun categoriesAreExactlySevenAndAssemblerCapsItemsAndTotalUtf8Payload() {
        assertEquals(
            listOf("focus", "habit", "content", "coach_interaction", "app_usage_summary", "goals", "location_summary"),
            AgentContextCategory.wireValues,
        )
        assertEquals(7L * DAY, AgentContextAssembler.FOCUS_RETENTION_MILLIS)
        assertEquals(30L * DAY, AgentContextAssembler.HABIT_RETENTION_MILLIS)
        assertEquals(24L * HOUR, AgentContextAssembler.USAGE_RETENTION_MILLIS)
        assertEquals(15L * MINUTE, AgentContextAssembler.LOCATION_RETENTION_MILLIS)
        val sessions = (0 until 15).map { index ->
            session("focus-$index", FocusStatus.FINISHED, NOW - index * 60_000L, 60L, 1, 0L)
        }
        val snapshot = assembler.assemble(
            mandate(shared = setOf("focus")),
            mapOf("focus" to NOW - DAY),
            AgentContextDomainViews(focusSessions = sessions),
            NOW,
        )
        assertEquals(10, snapshot.categories.getValue("focus").size)
        assertTrue(snapshot.categories.values.flatten().all { it.codePointCount(0, it.length) <= 200 })
        assertTrue(snapshot.toJsonString().toByteArray(Charsets.UTF_8).size <= 8 * 1024)
        assertTrue(snapshot.categories.getValue("focus").first().endsWith("| completed | 1 min"))
    }

    @Test
    fun utf8OverflowCandidatesAreMeasuredBeforeSnapshotAndDroppedUntilPayloadFits() {
        val candidates = AgentContextCategory.wireValues.flatMap { category ->
            (0 until AgentContextSnapshot.MAX_ITEMS_PER_CATEGORY).map { index ->
                AgentContextAssembler.Candidate(
                    category = category,
                    value = "😀".repeat(AgentContextSnapshot.MAX_ITEM_CODE_POINTS),
                    timestampUtcMillis = NOW - index,
                    priority = 100,
                )
            }
        }

        val snapshot = assembler.fitPayload(AgentContextCategory.wireValues, candidates)

        assertTrue(snapshot.categories.isNotEmpty())
        assertTrue(snapshot.toJsonString().toByteArray(Charsets.UTF_8).size <= AgentContextSnapshot.MAX_CONTEXT_BYTES)
        assertTrue(snapshot.categories.values.sumOf(List<String>::size) < candidates.size)
        assertTrue(snapshot.categories.values.flatten().all {
            it.codePointCount(0, it.length) <= AgentContextSnapshot.MAX_ITEM_CODE_POINTS
        })
    }

    @Test
    fun snapshotFreezesInputsAndRejectsPayloadsOverEightKibibytes() {
        val mutableConsent = mutableListOf("focus")
        val mutableItems = mutableListOf("item")
        val mutableCategories = linkedMapOf("focus" to mutableItems)
        val mutableTimestamps = linkedMapOf("focus" to mutableListOf(NOW))
        val snapshot = AgentContextSnapshot(mutableConsent, mutableCategories, mutableTimestamps)
        mutableConsent.clear()
        mutableItems[0] = "mutated"
        mutableCategories.clear()
        mutableTimestamps.clear()
        assertEquals(listOf("focus"), snapshot.consentedCategories)
        assertEquals(listOf("item"), snapshot.categories.getValue("focus"))
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.categories as MutableMap<String, List<String>>).clear()
        }

        val allCategories = AgentContextCategory.wireValues
        val manyLongItems = allCategories.associateWith { List(10) { "é".repeat(200) } }
        val times = allCategories.associateWith { List(10) { NOW } }
        assertThrows(IllegalArgumentException::class.java) {
            AgentContextSnapshot(allCategories, manyLongItems, times)
        }
    }

    @Test
    fun goalsAndLocationHaveSerializableSlotsAndReadOnlyLocationUsesTheCoarsenedDomainType() {
        val snapshot = AgentContextSnapshot(
            consentedCategories = listOf("goals", "location_summary"),
            categories = mapOf(
                "goals" to listOf("Fixture goal | cible 120 focus_minutes | validé 50"),
                "location_summary" to listOf("2026-10-19T18:00:00Z | zone approximative | fixture city"),
            ),
            sourceTimestampsUtcMillis = mapOf("goals" to listOf(NOW), "location_summary" to listOf(NOW)),
        )
        assertNotNull(snapshot.toMap()["categories"])
        assertTrue(snapshot.toJsonString().contains("location_summary"))
        val location = LocationSummary(48.85661, 2.35222, 2_000.0, NOW)
        val views = AgentContextDomainViews(locationSummaries = listOf(location))
        assertEquals(listOf(location), views.locationSummaries)
    }

    @Test
    fun locationSummaryCoordinatesAreSerializedWithoutAnotherGridOrOffset() {
        val summary = LocationSummary(48.856123, 2.352345, 2_500.0, NOW)

        val snapshot = assembler.assemble(
            mandate(shared = setOf("location_summary")),
            mapOf("location_summary" to NOW - MINUTE),
            AgentContextDomainViews(
                locationSummarySharingEnabled = true,
                locationSummaries = listOf(summary),
            ),
            NOW,
        )

        val item = snapshot.categories.getValue("location_summary").single()
        assertTrue(item.contains("${summary.latitude}, ${summary.longitude}"))
    }

    private fun mandate(shared: Set<String>) = AgentMandate(id = "fixture-mandate", sharedDataCategories = shared)

    private fun session(
        id: String,
        status: FocusStatus,
        createdAt: Long,
        workSeconds: Long,
        cycles: Int,
        remainingMillis: Long,
    ) = FocusSession(
        id = id,
        directiveId = null,
        presetId = "fixture",
        phase = FocusPhase.WORK,
        status = status,
        createdAtUtcMillis = createdAt,
        updatedAtUtcMillis = createdAt,
        remainingSeconds = remainingMillis / 1_000L,
        completedWorkCycles = cycles,
        workSeconds = workSeconds,
        shortBreakSeconds = 5 * 60L,
        longBreakSeconds = 15 * 60L,
        longBreakEveryCycles = 4,
        remainingMillis = remainingMillis,
        totalWorkCycles = 4,
        continuous = false,
        taskTitle = "fixture focus title",
    )

    private fun checkIn(id: String, habitId: String, date: LocalDate, timestamp: Long) = HabitCheckIn(
        id = id,
        habitId = habitId,
        dateEpochDay = date.toEpochDay(),
        createdAtUtcMillis = timestamp,
    )

    private fun contentEvent(timestamp: Long) = SyncEvent(
        clientEventId = UUID.randomUUID().toString(),
        eventType = SyncEventType.CONTENT_OPENED,
        occurredAtUtcMillis = timestamp,
        category = SyncDataCategory.CONTENT,
        dataScope = DataScope.SHAREABLE,
        payload = mapOf(
            "title" to "fixture-content-title",
            "url" to "https://fixture.example/secret",
        ),
    )

    private fun utcDate(timestamp: Long) = Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate()

    private companion object {
        const val NOW = 1_798_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60L * MINUTE
        const val DAY = 24L * HOUR
    }
}
