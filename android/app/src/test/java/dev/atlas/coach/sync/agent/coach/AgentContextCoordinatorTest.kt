package dev.atlas.coach.sync.agent.coach

import dev.atlas.coach.goals.GoalMetric
import dev.atlas.coach.goals.GoalRecord
import dev.atlas.coach.habits.HabitCheckIn
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.context.AgentContextDomainViews
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class AgentContextCoordinatorTest {
    @Test
    fun requestContextReassemblesLatestDomainRecordsAfterQueueDelay() {
        val now = 1_800_000_000_000L
        val consentAt = now - 60_000L
        val mandate = AgentMandate.default().copy(sharedDataCategories = setOf("goals"))
        var currentDomains = AgentContextDomainViews(goals = listOf(goal("stale-goal", "Stale fixture goal", consentAt)))
        val coordinator = AgentContextCoordinator(
            initialConsentAtUtcMillis = mapOf("goals" to consentAt),
            legacyConsentedCategories = emptySet(),
            mandateProvider = { mandate },
            domainsProvider = { currentDomains },
            nowUtcMillis = { now },
            persistConsentDates = {},
        )

        val clickTimeSnapshot = coordinator.assembleCurrent()
        currentDomains = AgentContextDomainViews(goals = listOf(goal("current-goal", "Current fixture goal", consentAt)))

        val request = requireNotNull(coordinator.requestContext(clickTimeSnapshot))
        val payload = request.minimizedSnapshot().toJsonString()

        assertTrue(payload.contains("Current fixture goal"))
        assertFalse(payload.contains("Stale fixture goal"))
    }

    @Test
    fun requestContextDropsRevokedCategoryFromAuthorizationAndPayload() {
        val now = 1_800_000_000_000L
        val consentAt = now - 60_000L
        val mandate = AgentMandate.default().copy(sharedDataCategories = setOf("goals"))
        val coordinator = AgentContextCoordinator(
            initialConsentAtUtcMillis = mapOf("goals" to consentAt),
            legacyConsentedCategories = emptySet(),
            mandateProvider = { mandate },
            domainsProvider = { AgentContextDomainViews(goals = listOf(goal("goal-1", "Fixture goal", consentAt))) },
            nowUtcMillis = { now },
            persistConsentDates = {},
        )
        val clickTimeSnapshot = coordinator.assembleCurrent()

        coordinator.setCategoryConsent("goals", enabled = false)
        val request = requireNotNull(coordinator.requestContext(clickTimeSnapshot))
        val minimized = request.minimizedSnapshot()

        assertTrue(request.mandate.sharedDataCategories.isEmpty())
        assertTrue(minimized.consentedCategories.isEmpty())
        assertTrue(minimized.categories.isEmpty())
    }

    @Test
    fun requestContextDropsDeletedCheckInsAndUsesUpdatedGoalAfterQueueDelay() {
        val now = 1_800_000_000_000L
        val consentAt = now - 60_000L
        val mandate = AgentMandate.default().copy(sharedDataCategories = setOf("goals"))
        val oldGoal = GoalRecord(
            id = "goal-1",
            title = "Stale goal title",
            description = "Synthetic local goal",
            metric = GoalMetric.HABIT_CHECKINS,
            targetValue = 3,
            linkedHabitIds = setOf("habit-1"),
            createdAt = consentAt,
            updatedAt = consentAt,
        )
        var currentDomains = AgentContextDomainViews(
            goals = listOf(oldGoal),
            habitCheckIns = listOf(checkIn(
                "checkin-1",
                "habit-1",
                Instant.ofEpochMilli(consentAt).atZone(ZoneOffset.UTC).toLocalDate(),
                consentAt + 1_000L,
            )),
        )
        val coordinator = AgentContextCoordinator(
            initialConsentAtUtcMillis = mapOf("goals" to consentAt),
            legacyConsentedCategories = emptySet(),
            mandateProvider = { mandate },
            domainsProvider = { currentDomains },
            nowUtcMillis = { now },
            persistConsentDates = {},
        )
        val clickTimeSnapshot = coordinator.assembleCurrent()
        assertTrue(clickTimeSnapshot.toJsonString().contains("validé 1"))
        currentDomains = AgentContextDomainViews(
            goals = listOf(oldGoal.copy(title = "Current goal title", updatedAt = now - 1_000L)),
            habitCheckIns = emptyList(),
        )

        val request = requireNotNull(coordinator.requestContext(clickTimeSnapshot))
        val payload = request.minimizedSnapshot().toJsonString()

        assertTrue(payload.contains("Current goal title"))
        assertTrue(payload.contains("validé 0"))
        assertFalse(payload.contains("Stale goal title"))
        assertFalse(payload.contains("validé 1"))
    }

    @Test
    fun durableExistingGoalsChoiceIncludesOldGoalStateAtRequestTime() {
        val now = 1_800_000_000_000L
        val consentAt = now - 60_000L
        val mandate = AgentMandate.default().copy(sharedDataCategories = setOf("goals"))
        var persistedChoice = false
        val coordinator = AgentContextCoordinator(
            initialConsentAtUtcMillis = mapOf("goals" to consentAt),
            legacyConsentedCategories = emptySet(),
            mandateProvider = { mandate },
            domainsProvider = { AgentContextDomainViews(goals = listOf(goal("old", "Old active fixture", consentAt - 30L * DAY))) },
            nowUtcMillis = { now },
            persistConsentDates = {},
            initialIncludeExistingGoals = false,
            persistIncludeExistingGoals = { persistedChoice = it },
        )

        coordinator.setIncludeExistingGoals(true)
        val request = requireNotNull(coordinator.requestContext(coordinator.assembleCurrent()))

        assertTrue(persistedChoice)
        assertTrue(request.minimizedSnapshot().toJsonString().contains("Old active fixture"))
    }

    @Test
    fun failedChoicePersistenceDoesNotOpenSharingForAnOldGoal() {
        val now = 1_800_000_000_000L
        val consentAt = now - 60_000L
        val mandate = AgentMandate.default().copy(sharedDataCategories = setOf("goals"))
        val coordinator = AgentContextCoordinator(
            initialConsentAtUtcMillis = mapOf("goals" to consentAt),
            legacyConsentedCategories = emptySet(),
            mandateProvider = { mandate },
            domainsProvider = { AgentContextDomainViews(goals = listOf(goal("old", "Old private fixture", consentAt - DAY))) },
            nowUtcMillis = { now },
            persistConsentDates = {},
            persistIncludeExistingGoals = { error("injected durable choice failure") },
        )

        assertTrue(runCatching { coordinator.setIncludeExistingGoals(true) }.isFailure)

        assertFalse(coordinator.includesExistingGoals())
        assertFalse(coordinator.preview().serializedPayload.contains("Old private fixture"))
    }

    @Test
    fun failedDurableGoalsOptInLeavesCategoryClosedAndChoiceOff() {
        val now = 1_800_000_000_000L
        val mandate = AgentMandate.default().copy(sharedDataCategories = emptySet())
        val coordinator = AgentContextCoordinator(
            initialConsentAtUtcMillis = emptyMap(),
            legacyConsentedCategories = emptySet(),
            mandateProvider = { mandate },
            domainsProvider = { AgentContextDomainViews(goals = listOf(goal("old", "Old private fixture", now - DAY))) },
            nowUtcMillis = { now },
            persistConsentDates = { error("injected goals opt-in write failure") },
            persistIncludeExistingGoals = {},
        )

        assertTrue(runCatching {
            coordinator.setCategoryConsent("goals", enabled = true, resetIncludeExistingGoals = true)
        }.isFailure)

        assertFalse(coordinator.includesExistingGoals())
        assertFalse("failed consent persistence must not authorize goals", "goals" in coordinator.preview().authorizedCategories)
        assertFalse(coordinator.preview().serializedPayload.contains("Old private fixture"))
    }

    @Test
    fun goalCategoryRevocationAndNewOptInResetExistingGoalsChoiceToOff() {
        val now = 1_800_000_000_000L
        val coordinator = AgentContextCoordinator(
            initialConsentAtUtcMillis = mapOf("goals" to now - 60_000L),
            legacyConsentedCategories = emptySet(),
            mandateProvider = { AgentMandate.default().copy(sharedDataCategories = setOf("goals")) },
            domainsProvider = { AgentContextDomainViews() },
            nowUtcMillis = { now },
            persistConsentDates = {},
            initialIncludeExistingGoals = true,
        )

        coordinator.resetCategoryConsents(setOf("goals"))
        assertFalse(coordinator.includesExistingGoals())
        coordinator.setIncludeExistingGoals(true)
        coordinator.setCategoryConsent("goals", enabled = false, resetIncludeExistingGoals = true)
        assertFalse(coordinator.includesExistingGoals())
        coordinator.setIncludeExistingGoals(true)
        coordinator.setCategoryConsent("goals", enabled = true, resetIncludeExistingGoals = true)
        assertFalse(coordinator.includesExistingGoals())
    }

    private fun goal(id: String, title: String, at: Long) = GoalRecord(
        id = id,
        title = title,
        description = "Synthetic local goal",
        metric = GoalMetric.MANUAL,
        targetValue = 1,
        createdAt = at,
        updatedAt = at,
    )

    private fun checkIn(id: String, habitId: String, date: LocalDate, at: Long) = HabitCheckIn(
        id = id,
        habitId = habitId,
        dateEpochDay = date.toEpochDay(),
        createdAtUtcMillis = at,
    )

    private companion object {
        const val DAY = 86_400_000L
    }
}
