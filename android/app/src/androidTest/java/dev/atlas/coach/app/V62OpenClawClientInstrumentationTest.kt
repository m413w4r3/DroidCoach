package dev.atlas.coach.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.focus.FocusPhase
import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.focus.FocusStatus
import dev.atlas.coach.goals.GoalMetric
import dev.atlas.coach.goals.GoalRecord
import dev.atlas.coach.coach.proactive.CoachTrigger
import dev.atlas.coach.coach.proactive.CoachTriggerReliability
import dev.atlas.coach.coach.proactive.CoachTriggerSource
import dev.atlas.coach.coach.proactive.ProactiveEngagementMode
import dev.atlas.coach.coach.proactive.ProactivePlanRequest
import dev.atlas.coach.sync.BearerTokenProvider
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.context.AgentContextAssembler
import dev.atlas.coach.sync.agent.context.AgentContextDomainConsent
import dev.atlas.coach.sync.agent.context.AgentContextDomainViews
import dev.atlas.coach.sync.agent.coach.AgentApiClient
import dev.atlas.coach.sync.agent.coach.AgentRequestContext
import dev.atlas.coach.sync.agent.coach.AgentStatus
import dev.atlas.coach.test.BackendArgumentsAwareRunner
import dev.atlas.coach.test.RequiresBackendArguments
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Direct Android client proof against the configured local OpenClaw adapter, using synthetic data. */
@RunWith(BackendArgumentsAwareRunner::class)
class V62OpenClawClientInstrumentationTest {
    @Test
    @RequiresBackendArguments(
        keys = ["backend_url", "backend_token"],
        reason = "requires the existing local FastAPI adapter connected to OpenClaw",
    )
    fun chatUsesRealGoalProgressAndPlanDropsGoalsAfterRevocation() {
        val args = InstrumentationRegistry.getArguments()
        val baseUrl = requireNotNull(args.getString("backend_url"))
        val token = requireNotNull(args.getString("backend_token"))
        require(token.isNotBlank())

        val now = System.currentTimeMillis()
        val consentAt = now - 60_000L
        val session = FocusSession(
            id = "fixture-focus-25m",
            directiveId = null,
            presetId = "fixture-25m",
            phase = FocusPhase.WORK,
            status = FocusStatus.FINISHED,
            createdAtUtcMillis = consentAt + 1_000L,
            updatedAtUtcMillis = consentAt + 1_000L,
            remainingSeconds = 0L,
            completedWorkCycles = 1,
            workSeconds = 25 * 60L,
            shortBreakSeconds = 5 * 60L,
            longBreakSeconds = 15 * 60L,
            longBreakEveryCycles = 4,
            remainingMillis = 0L,
            totalWorkCycles = 1,
            continuous = false,
        )
        val goal = GoalRecord(
            id = "fixture-goal-25-of-120",
            title = "Fixture objectif Focus",
            description = "Donnée synthétique pour la recette Android.",
            metric = GoalMetric.FOCUS_MINUTES,
            targetValue = 120L,
            linkedFocusSessionIds = setOf(session.id),
            createdAt = consentAt,
            updatedAt = now,
        )
        val domains = AgentContextDomainViews(
            focusSessions = listOf(session),
            goals = listOf(goal),
        )
        val assembler = AgentContextAssembler()
        val authorizedCategories = setOf("focus", "goals")
        val mandate = AgentMandate.default().copy(sharedDataCategories = authorizedCategories)
        val initialConsentDates = authorizedCategories.associateWith { consentAt }
        val initialSnapshot = assembler.assemble(mandate, initialConsentDates, domains, now)
        var goalsSharingEnabled = true
        val writtenContexts = mutableListOf<String>()
        val client = AgentApiClient(
            baseUrl = baseUrl,
            tokenProvider = BearerTokenProvider { token },
            freshRequestContext = {
                val currentNow = System.currentTimeMillis()
                val currentCategories = if (goalsSharingEnabled) authorizedCategories else setOf("focus")
                val currentMandate = mandate.copy(sharedDataCategories = currentCategories)
                val currentConsentDates = currentCategories.associateWith { consentAt }
                val latest = assembler.assemble(currentMandate, currentConsentDates, domains, currentNow)
                AgentRequestContext(
                    mandate = currentMandate,
                    snapshot = latest,
                    consentAtUtcMillis = currentConsentDates,
                    domainConsent = AgentContextDomainConsent(),
                    nowUtcMillis = currentNow,
                )
            },
            onContextWritten = { writtenContexts += it.toJsonString() },
        )
        val conversationId = UUID.randomUUID().toString()

        val chat = client.chat(
            conversationId = conversationId,
            message = "Résume cette progression synthétique et propose une prochaine action.",
            mandate = mandate,
            context = initialSnapshot,
        )
        assertEquals(AgentStatus.CONNECTED, chat.status)
        val sentChatContext = JSONObject(requireNotNull(chat.sharedContextJson))
        assertEquals(1L, writtenContexts.size.toLong())
        assertTrue(sentChatContext.toString().contains("Fixture objectif Focus"))
        assertTrue(sentChatContext.toString().contains("cible 120"))
        assertTrue(sentChatContext.toString().contains("validé 25"))
        assertTrue(sentChatContext.toString().contains("completed | 25 min"))
        assertFalse(sentChatContext.toString().contains("Donnée synthétique"))

        goalsSharingEnabled = false
        val plan = client.plan(
            conversationId = conversationId,
            mandate = mandate,
            context = initialSnapshot,
            request = ProactivePlanRequest(
                reservationId = "fixture-reservation",
                requestedAtUtcMillis = now,
                mode = ProactiveEngagementMode.NORMAL,
                triggers = listOf(
                    CoachTrigger(
                        eventId = "fixture-focus-event",
                        source = CoachTriggerSource.FOCUS_COMPLETED,
                        occurredAtUtcMillis = now - 1_000L,
                        reliability = CoachTriggerReliability.VERIFIED,
                    ),
                ),
            ),
            allowSend = { true },
        )
        assertEquals(AgentStatus.CONNECTED, plan.status)
        assertEquals(2, writtenContexts.size)
        val sentPlanContext = JSONObject(requireNotNull(plan.sharedContextJson))
        val categories = sentPlanContext.getJSONArray("consented_categories")
        assertEquals(1, categories.length())
        assertEquals("focus", categories.getString(0))
        assertFalse(sentPlanContext.toString().contains("Fixture objectif Focus"))
        assertTrue(sentPlanContext.toString().contains("completed | 25 min"))
    }
}
