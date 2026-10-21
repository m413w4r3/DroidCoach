package dev.atlas.coach.app

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.ui.AtlasTheme
import dev.atlas.coach.data.LocalStateStore
import dev.atlas.coach.data.PersistenceDiagnosticsStore
import dev.atlas.coach.data.RevocationBarrierState
import dev.atlas.coach.data.RevocationBarrierStorage
import dev.atlas.coach.data.StateBackend
import dev.atlas.coach.data.StateReadResult
import dev.atlas.coach.focus.FakeClock
import dev.atlas.coach.focus.FocusPhase
import dev.atlas.coach.focus.FocusPlan
import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.focus.FocusStatus
import dev.atlas.coach.focus.PhaseAlarmScheduler
import dev.atlas.coach.focus.PhaseNotificationResult
import dev.atlas.coach.focus.PhaseNotifier
import dev.atlas.coach.focus.PhaseTransition
import dev.atlas.coach.goals.GoalMetric
import dev.atlas.coach.goals.GoalRecord
import dev.atlas.coach.goals.GoalStatus
import dev.atlas.coach.sync.EventOutbox
import dev.atlas.coach.sync.QueuedEvent
import dev.atlas.coach.sync.SyncConsent
import dev.atlas.coach.sync.SyncDataCategory
import dev.atlas.coach.sync.SyncEvent
import dev.atlas.coach.sync.SyncEventType
import dev.atlas.coach.sync.agent.AgentActionType
import dev.atlas.coach.sync.agent.AgentFocusCardState
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.ProposalLevel
import dev.atlas.coach.sync.agent.ProposalV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GoalFocusStartInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun focusGoalSelectorOffersNoneOneAndManyAndCreditsOnlyFinishedLinkedWork() {
        val backend = MemoryStateBackend()
        val clock = FakeClock(startUtcMillis = INITIAL_UTC_MILLIS, startMonotonicMillis = 20_000L)
        val alarms = RecordingPhaseAlarmScheduler(backend)
        val outbox = RecordingEventOutbox(backend)
        lateinit var controller: AtlasController
        val context = isolatedContext()
        composeRule.runOnUiThread {
            controller = newController(context, clock, backend, alarms, outbox)
            controller.setCapability(AtlasController.CAP_FOCUS, true)
            controller.setCapability(AtlasController.CAP_FOCUS_NOTIFICATIONS, true)
            consentToFocusEvents(controller)
        }
        val displayedController = mutableStateOf(controller)
        composeRule.setContent {
            AtlasTheme {
                val currentController = displayedController.value
                FocusSessionScreen(currentController, revision = currentController.revision)
            }
        }

        composeRule.onNodeWithTag("focus_goal_selector").assertDoesNotExist()

        lateinit var firstGoal: GoalsPanelEntry
        lateinit var secondGoal: GoalsPanelEntry
        lateinit var pausedGoal: GoalsPanelEntry
        composeRule.runOnUiThread {
            controller.createGoal(focusGoalDraft("Premier objectif"))
            assertEquals(0L, controller.coachGoals.single().progress.currentValue)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("focus_goal_selector").assertIsDisplayed()
        assertTrue(projectionText("focus_goal_selector").contains("Premier objectif"))

        composeRule.runOnUiThread {
            controller.createGoal(focusGoalDraft("Deuxième objectif"))
            controller.createGoal(focusGoalDraft("Objectif en pause"))
            pausedGoal = controller.coachGoals.single { it.record.title == "Objectif en pause" }
            controller.changeGoalStatus(pausedGoal.record.id, GoalStatus.PAUSED)
            controller.createGoal(GoalPanelDraft(
                title = "Objectif manuel",
                metric = GoalMetric.MANUAL,
                targetValue = 1L,
                description = "",
                nextAction = "",
            ))
            firstGoal = controller.coachGoals.first { it.record.title == "Premier objectif" }
            secondGoal = controller.coachGoals.first { it.record.title == "Deuxième objectif" }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("focus_goal_selector").assertIsDisplayed()
        assertTrue(projectionText("focus_goal_selector").contains("Sans objectif"))
        composeRule.onNodeWithTag("focus_goal_selector").performClick()
        composeRule.onNodeWithTag("focus_goal_option_${secondGoal.record.id}").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Objectif manuel").assertDoesNotExist()
        composeRule.onNodeWithText("Objectif en pause").assertDoesNotExist()
        composeRule.onNodeWithTag("focus_pomodoro_start").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            controller.focusSession?.status == FocusStatus.RUNNING
        }

        val started = requireNotNull(controller.focusSession)
        val persisted = (LocalStateStore(backend).readDocument() as StateReadResult.Loaded).state
        val persistedGoals = (persisted["goals"] as Map<*, *>)["goals"] as List<*>
        val selected = persistedGoals.filterIsInstance<Map<String, Any?>>()
            .single { it["id"] == secondGoal.record.id }
        val first = persistedGoals.filterIsInstance<Map<String, Any?>>()
            .single { it["id"] == firstGoal.record.id }
        assertEquals(listOf(started.id), selected["linkedFocusSessionIds"])
        assertEquals(emptyList<String>(), first["linkedFocusSessionIds"])
        assertEquals(0L, controller.coachGoals.single { it.record.id == secondGoal.record.id }.progress.currentValue)
        composeRule.onNodeWithTag("focus_goal_active_projection").assertIsDisplayed()
        val activeProjectionText = projectionText("focus_goal_active_projection")
        assertTrue(activeProjectionText.contains("Deuxième objectif"))
        assertTrue(activeProjectionText.contains("25 min prévues"))

        composeRule.onNodeWithTag("focus_pause_resume").performClick()
        composeRule.waitForIdle()
        assertEquals(FocusStatus.PAUSED, controller.focusSession?.status)
        assertEquals(0L, controller.coachGoals.single { it.record.id == secondGoal.record.id }.progress.currentValue)
        clock.advanceMillis(8 * 60 * 60_000L)
        assertEquals(0L, controller.coachGoals.single { it.record.id == secondGoal.record.id }.progress.currentValue)
        composeRule.onNodeWithTag("focus_pause_resume").performClick()
        composeRule.waitForIdle()
        assertEquals(FocusStatus.RUNNING, controller.focusSession?.status)
        clock.advanceMillis(25 * 60_000L)
        composeRule.onNodeWithTag("focus_finish").performClick()
        composeRule.waitForIdle()
        assertEquals(FocusStatus.FINISHED, controller.focusSession?.status)
        composeRule.onNodeWithTag("focus_session_validated_minutes").assertIsDisplayed()
        assertTrue(projectionText("focus_session_validated_minutes").contains("25 min"))
        composeRule.onNodeWithTag("focus_goal_finished_projection").assertIsDisplayed()
        assertTrue(projectionText("focus_goal_finished_projection").contains("25 / 120"))
        repeat(4) {
            assertEquals(25L, controller.coachGoals.single { it.record.id == secondGoal.record.id }.progress.currentValue)
        }
        assertEquals(listOf(SyncEventType.FOCUS_STARTED), outbox.events
            .filter { it.eventType == SyncEventType.FOCUS_STARTED }
            .map(SyncEvent::eventType))
        val startEventRoot = outbox.rootSnapshots.first { root ->
            root.focus()?.id == started.id && root.goal(secondGoal.record.id).linkedFocusSessionIds == setOf(started.id)
        }
        assertEquals(FocusStatus.RUNNING, startEventRoot.focus()?.status)

        lateinit var restored: AtlasController
        val restoredAlarms = RecordingPhaseAlarmScheduler(backend)
        composeRule.runOnUiThread {
            restored = newController(context, clock, backend, restoredAlarms, RecordingEventOutbox(backend))
        }
        assertEquals(FocusStatus.FINISHED, restored.focusSession?.status)
        assertEquals(25L, restored.coachGoals.single { it.record.id == secondGoal.record.id }.progress.currentValue)
        assertEquals(setOf(started.id), restored.coachGoals.single { it.record.id == secondGoal.record.id }.record.linkedFocusSessionIds)
        composeRule.runOnUiThread { displayedController.value = restored }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("focus_goal_finished_projection").assertIsDisplayed()
        composeRule.onNodeWithTag("focus_session_validated_minutes").assertIsDisplayed()
        assertTrue(projectionText("focus_goal_finished_projection").contains("25 / 120"))
    }

    @Test
    fun singleGoalCanChooseNoGoalAndChoiceStaysFreeWhenEligibilityChanges() {
        val backend = MemoryStateBackend()
        val clock = FakeClock(startUtcMillis = INITIAL_UTC_MILLIS, startMonotonicMillis = 0L)
        lateinit var controller: AtlasController
        lateinit var firstGoal: GoalsPanelEntry
        lateinit var secondGoal: GoalsPanelEntry
        composeRule.runOnUiThread {
            controller = newController(
                isolatedContext(), clock, backend,
                RecordingPhaseAlarmScheduler(backend), RecordingEventOutbox(backend),
            )
            controller.setCapability(AtlasController.CAP_FOCUS, true)
            controller.createGoal(focusGoalDraft("Premier choix"))
            firstGoal = controller.coachGoals.single()
        }
        composeRule.setContent { AtlasTheme { FocusSessionScreen(controller, revision = controller.revision) } }
        composeRule.onNodeWithTag("focus_goal_selector").assertIsDisplayed()
        assertTrue(projectionText("focus_goal_selector").contains("Premier choix"))

        composeRule.onNodeWithTag("focus_goal_selector").performClick()
        composeRule.onNodeWithTag("focus_goal_none").assertIsDisplayed().performClick()
        assertTrue(projectionText("focus_goal_selector").contains("Sans objectif"))
        composeRule.runOnUiThread {
            controller.createGoal(focusGoalDraft("Deuxième choix"))
            secondGoal = controller.coachGoals.single { it.record.title == "Deuxième choix" }
        }
        composeRule.waitForIdle()
        assertTrue(projectionText("focus_goal_selector").contains("Sans objectif"))
        composeRule.onNodeWithTag("focus_pomodoro_start").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            controller.focusSession?.status == FocusStatus.RUNNING
        }

        val root = loadedRoot(backend)
        assertTrue(root.goal(firstGoal.record.id).linkedFocusSessionIds.isEmpty())
        assertTrue(root.goal(secondGoal.record.id).linkedFocusSessionIds.isEmpty())
    }

    @Test
    fun localGoalLinkSurvivesPauseResumeFinishUnrelatedWriteAndRealControllerRecreation() {
        val backend = MemoryStateBackend()
        val context = isolatedContext()
        val clock = FakeClock(startUtcMillis = INITIAL_UTC_MILLIS, startMonotonicMillis = 0L)
        lateinit var controller: AtlasController
        lateinit var goal: GoalsPanelEntry
        composeRule.runOnUiThread {
            controller = newController(
                context, clock, backend,
                RecordingPhaseAlarmScheduler(backend), RecordingEventOutbox(backend),
            )
            controller.setCapability(AtlasController.CAP_FOCUS, true)
            controller.createGoal(focusGoalDraft("Persistance Focus locale"))
            goal = controller.coachGoals.single()
            controller.startFocus(FocusPlan.custom(25 * 60L, totalWorkCycles = 1), goalId = goal.record.id)
            controller.toggleFocusPause()
            clock.advanceMillis(3 * 60_000L)
            controller.toggleFocusPause()
            clock.advanceMillis(25 * 60_000L)
            controller.finishFocus()
            controller.reportMessage("Écriture racine hors Goal")
        }

        val root = loadedRoot(backend)
        val sessionId = requireNotNull(root.focus()?.id)
        assertEquals(FocusStatus.FINISHED, root.focus()?.status)
        assertEquals(setOf(sessionId), root.goal(goal.record.id).linkedFocusSessionIds)
        lateinit var restored: AtlasController
        composeRule.runOnUiThread {
            restored = newController(
                context, clock, backend,
                RecordingPhaseAlarmScheduler(backend), RecordingEventOutbox(backend),
            )
        }
        val restoredGoal = restored.coachGoals.single { it.record.id == goal.record.id }
        assertEquals(setOf(sessionId), restoredGoal.record.linkedFocusSessionIds)
        assertEquals(25L, restoredGoal.progress.currentValue)
    }

    @Test
    fun agentStartCommitsTheLocalGoalChoiceWithItsIdempotencyKeyOnce() {
        val backend = MemoryStateBackend()
        val clock = FakeClock(startUtcMillis = INITIAL_UTC_MILLIS, startMonotonicMillis = 20_000L)
        val alarms = RecordingPhaseAlarmScheduler(backend)
        val outbox = RecordingEventOutbox(backend)
        lateinit var controller: AtlasController
        composeRule.runOnUiThread {
            controller = newController(isolatedContext(), clock, backend, alarms, outbox)
            controller.setCapability(AtlasController.CAP_FOCUS, true)
            controller.setCapability(AtlasController.CAP_FOCUS_NOTIFICATIONS, true)
            controller.setCapability(AtlasController.CAP_AGENT, true)
            consentToFocusEvents(controller)
        }
        lateinit var goal: GoalsPanelEntry
        lateinit var cardId: String
        composeRule.runOnUiThread {
            controller.createGoal(focusGoalDraft("Objectif agent"))
            goal = controller.coachGoals.single()
            val mandate = AgentMandate.default()
            val now = clock.nowMillis()
            val proposal = ProposalV2(
                schemaVersion = 2,
                proposalId = UUID.randomUUID().toString(),
                idempotencyKey = UUID.randomUUID().toString(),
                conversationId = null,
                mandateId = mandate.id,
                mandateVersion = mandate.version,
                contextVersion = "goal-focus-instrumentation",
                createdAtUtcMillis = now,
                expiresAtUtcMillis = now + 60_000L,
                actionType = AgentActionType.FOCUS_PROPOSE.wireValue,
                parameters = mapOf(
                    "label" to "Session agent",
                    "focus_minutes" to 25,
                    "break_minutes" to 5,
                    "cycles" to 1,
                ),
                reason = "Test local agent proposal",
                requiredLevel = ProposalLevel.CONFIRM,
            )
            assertTrue(controller.proposeFocus(proposal).applied)
            cardId = requireNotNull(controller.agentHomeState.focusProposal).proposalId
        }
        composeRule.waitForIdle()
        val writesBeforeStart = backend.writes

        composeRule.runOnUiThread {
            controller.startAgentProposedFocus(
                cardId,
                taskTitle = null,
                config = null,
                goalId = goal.record.id,
            )
        }

        val started = requireNotNull(controller.focusSession)
        val persisted = (LocalStateStore(backend).readDocument() as StateReadResult.Loaded).state
        val goalRows = ((persisted["goals"] as Map<*, *>)["goals"] as List<*>)
            .filterIsInstance<Map<String, Any?>>()
        val savedGoal = goalRows.single { it["id"] == goal.record.id }
        val startEntry = controller.agentInbox.single {
            it.proposal.actionType == AgentActionType.FOCUS_START.wireValue
        }
        assertEquals(writesBeforeStart + 1, backend.writes)
        assertEquals(FocusStatus.RUNNING, started.status)
        assertEquals(listOf(started.id), savedGoal["linkedFocusSessionIds"])
        assertTrue((persisted["appliedLocalAgentEffectKeys"] as List<*>).contains(startEntry.proposal.idempotencyKey))
        assertFalse(startEntry.proposal.parameters.containsKey("goalId"))
        assertEquals(AgentFocusCardState.RUNNING, controller.agentHomeState.focusProposal?.state)
        assertEquals(listOf(started.id), alarms.scheduled.map(FocusSession::id))
        val focusStartedIndexes = outbox.events.indices.filter {
            outbox.events[it].eventType == SyncEventType.FOCUS_STARTED
        }
        assertEquals(1, focusStartedIndexes.size)
        val outboxRoot = outbox.rootSnapshots[focusStartedIndexes.single()]
        assertEquals(FocusStatus.RUNNING, outboxRoot.focus()?.status)
        assertEquals(setOf(started.id), outboxRoot.goal(goal.record.id).linkedFocusSessionIds)
        assertTrue((outboxRoot["appliedLocalAgentEffectKeys"] as List<*>).contains(startEntry.proposal.idempotencyKey))
        val alarmRoot = alarms.rootsAtSchedule.single()
        assertEquals(FocusStatus.RUNNING, alarmRoot?.focus()?.status)
        assertEquals(setOf(started.id), alarmRoot?.goal(goal.record.id)?.linkedFocusSessionIds)

        composeRule.runOnUiThread {
            controller.toggleFocusPause()
            clock.advanceMillis(3 * 60_000L)
            controller.toggleFocusPause()
            clock.advanceMillis(25 * 60_000L)
            controller.finishFocus()
            controller.reportMessage("Écriture racine hors Goal après Focus agent")
        }
        val afterLifecycle = loadedRoot(backend)
        assertEquals(FocusStatus.FINISHED, afterLifecycle.focus()?.status)
        assertEquals(setOf(started.id), afterLifecycle.goal(goal.record.id).linkedFocusSessionIds)
        assertTrue((afterLifecycle["appliedLocalAgentEffectKeys"] as List<*>).contains(startEntry.proposal.idempotencyKey))

        lateinit var restoredAgentController: AtlasController
        composeRule.runOnUiThread {
            restoredAgentController = newController(
                InstrumentationRegistry.getInstrumentation().targetContext,
                clock,
                backend,
                RecordingPhaseAlarmScheduler(backend),
                RecordingEventOutbox(backend),
            )
        }
        assertEquals(FocusStatus.FINISHED, restoredAgentController.focusSession?.status)
        val restoredAgentGoal = restoredAgentController.coachGoals.single { it.record.id == goal.record.id }
        assertEquals(setOf(started.id), restoredAgentGoal.record.linkedFocusSessionIds)
        assertEquals(25L, restoredAgentGoal.progress.currentValue)
    }

    @Test
    fun failedInitialCommitHasNoSessionGoalAlarmStartedEventOrRecreatedState() {
        val backend = MemoryStateBackend()
        val clock = FakeClock(startUtcMillis = INITIAL_UTC_MILLIS, startMonotonicMillis = 0L)
        val context = isolatedContext()
        val alarms = RecordingPhaseAlarmScheduler(backend)
        val outbox = RecordingEventOutbox(backend)
        lateinit var controller: AtlasController
        lateinit var goal: GoalsPanelEntry
        composeRule.runOnUiThread {
            controller = newController(context, clock, backend, alarms, outbox)
            controller.setCapability(AtlasController.CAP_FOCUS, true)
            controller.setCapability(AtlasController.CAP_FOCUS_NOTIFICATIONS, true)
            consentToFocusEvents(controller)
            controller.createGoal(focusGoalDraft("Échec atomique"))
            goal = controller.coachGoals.single()
        }
        val rootBefore = loadedRoot(backend)
        val writesBefore = backend.writes
        backend.failNextWrite = true

        composeRule.runOnUiThread {
            controller.startFocus(FocusPlan.custom(25 * 60L, totalWorkCycles = 1), goalId = goal.record.id)
        }

        assertEquals(writesBefore + 1, backend.writes)
        assertNull(controller.focusSession)
        assertEquals(
            "Focus non démarré. Aucun lien à l'objectif n'a été enregistré.",
            controller.latestMessage,
        )
        assertEquals(rootBefore, loadedRoot(backend))
        assertTrue(controller.coachGoals.single { it.record.id == goal.record.id }.record.linkedFocusSessionIds.isEmpty())
        assertTrue(alarms.scheduled.isEmpty())
        assertFalse(outbox.events.any { it.eventType == SyncEventType.FOCUS_STARTED })

        lateinit var restored: AtlasController
        composeRule.runOnUiThread {
            restored = newController(context, clock, backend, RecordingPhaseAlarmScheduler(backend), RecordingEventOutbox(backend))
        }
        assertNull(restored.focusSession)
        assertTrue(restored.coachGoals.single { it.record.id == goal.record.id }.record.linkedFocusSessionIds.isEmpty())
    }

    @Test
    fun controllerRecreationAfterElapsedFocusPersistsOneCompletedCycleAndProgress() {
        val backend = MemoryStateBackend()
        val context = isolatedContext()
        val initialClock = FakeClock(startUtcMillis = INITIAL_UTC_MILLIS, startMonotonicMillis = 0L)
        val firstAlarms = RecordingPhaseAlarmScheduler(backend)
        lateinit var goal: GoalsPanelEntry
        lateinit var first: AtlasController
        composeRule.runOnUiThread {
            first = newController(context, initialClock, backend, firstAlarms, RecordingEventOutbox(backend))
            first.setCapability(AtlasController.CAP_FOCUS, true)
            first.createGoal(focusGoalDraft("Reprise après arrêt"))
            goal = first.coachGoals.single()
            first.startFocus(FocusPlan.custom(25 * 60L, totalWorkCycles = 1), goalId = goal.record.id)
        }
        assertEquals(FocusStatus.RUNNING, first.focusSession?.status)
        assertEquals(0L, first.coachGoals.single { it.record.id == goal.record.id }.progress.currentValue)

        val resumedClock = FakeClock(
            startUtcMillis = INITIAL_UTC_MILLIS + 25 * 60_000L,
            startMonotonicMillis = 0L,
        )
        lateinit var resumed: AtlasController
        composeRule.runOnUiThread {
            resumed = newController(context, resumedClock, backend, RecordingPhaseAlarmScheduler(backend), RecordingEventOutbox(backend))
        }
        assertEquals(FocusStatus.FINISHED, resumed.focusSession?.status)
        assertEquals(25L, resumed.coachGoals.single { it.record.id == goal.record.id }.progress.currentValue)
        assertEquals(FocusStatus.FINISHED, loadedRoot(backend).focus()?.status)

        lateinit var resumedAgain: AtlasController
        composeRule.runOnUiThread {
            resumedAgain = newController(context, resumedClock, backend, RecordingPhaseAlarmScheduler(backend), RecordingEventOutbox(backend))
        }
        assertEquals(25L, resumedAgain.coachGoals.single { it.record.id == goal.record.id }.progress.currentValue)
        assertEquals(1, resumedAgain.coachGoals.single { it.record.id == goal.record.id }.record.linkedFocusSessionIds.size)
    }

    @Test
    fun legacyV621HistoryIsPreservedWithoutInventedGoalLinks() {
        val backend = MemoryStateBackend()
        val now = INITIAL_UTC_MILLIS
        val legacySession = FocusSession(
            id = "v621-historical-focus",
            directiveId = null,
            presetId = "legacy-25-minute",
            phase = FocusPhase.WORK,
            status = FocusStatus.FINISHED,
            createdAtUtcMillis = now - 60 * 60_000L,
            updatedAtUtcMillis = now,
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
        val legacyMap = legacySession.toMap().filterKeys {
            it !in setOf("remainingMillis", "totalWorkCycles", "continuous", "taskTitle")
        }
        LocalStateStore(backend).write(mapOf("focusHistory" to listOf(legacyMap)), durable = true)

        lateinit var controller: AtlasController
        composeRule.runOnUiThread {
            controller = newController(
                isolatedContext(),
                FakeClock(startUtcMillis = now, startMonotonicMillis = 0L),
                backend,
                RecordingPhaseAlarmScheduler(backend),
                RecordingEventOutbox(backend),
            )
            controller.createGoal(focusGoalDraft("Objectif après migration"))
        }
        val goal = controller.coachGoals.single()
        assertEquals(listOf(legacySession.id), controller.focusHistory.map(FocusSession::id))
        assertTrue(goal.record.linkedFocusSessionIds.isEmpty())
        assertEquals(0L, goal.progress.currentValue)

        composeRule.runOnUiThread { controller.linkFocusToGoal(goal.record.id, legacySession.id, linked = true) }
        assertEquals(setOf(legacySession.id), controller.coachGoals.single().record.linkedFocusSessionIds)
        assertEquals(25L, controller.coachGoals.single().progress.currentValue)
    }

    private fun focusGoalDraft(title: String) = GoalPanelDraft(
        title = title,
        metric = GoalMetric.FOCUS_MINUTES,
        targetValue = 120L,
        description = "",
        nextAction = "",
    )

    private fun projectionText(tag: String): String =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text]
            .joinToString(separator = " ") { it.text }

    private fun isolatedContext(): Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = UUID.randomUUID().toString()
        val files = File(target.cacheDir, "v622-goal-focus-$namespace").apply {
            check(mkdirs() || isDirectory)
        }
        return object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences("v622_goal_focus_${namespace}_$name", mode)
        }
    }

    private fun newController(
        context: Context,
        clock: FakeClock,
        backend: MemoryStateBackend,
        alarms: RecordingPhaseAlarmScheduler,
        outbox: RecordingEventOutbox,
    ) = AtlasController(
        context = context,
        clock = clock,
        stateBackendOverride = backend,
        revocationBarrierStorageOverride = TestBarrierStorage(),
        persistenceDiagnosticsStoreOverride = TestDiagnosticsStore(),
        syncOutboxOverride = outbox,
        phaseAlarmSchedulerOverride = alarms,
        phaseNotifierOverride = RecordingPhaseNotifier(),
    )

    private fun consentToFocusEvents(controller: AtlasController) {
        controller.setSyncCategory(SyncDataCategory.FOCUS, enabled = true)
        controller.setSyncEnabled(true)
    }

    private fun loadedRoot(backend: MemoryStateBackend): Map<String, Any?> =
        (LocalStateStore(backend).readDocument() as StateReadResult.Loaded).state

    private fun Map<String, Any?>.focus(): FocusSession? =
        (this["focus"] as? Map<*, *>)?.entries?.associate { it.key as String to it.value }?.let(FocusSession::fromMap)

    private fun Map<String, Any?>.goal(id: String): GoalRecord {
        val rows = ((this["goals"] as? Map<*, *>)?.get("goals") as? List<*>)
            .orEmpty().filterIsInstance<Map<String, Any?>>()
        return GoalRecord.fromMap(rows.single { it["id"] == id })
    }

    private class TestBarrierStorage : RevocationBarrierStorage {
        override fun readState() = RevocationBarrierState()
        override fun writeState(state: RevocationBarrierState) = true
    }

    private class TestDiagnosticsStore : PersistenceDiagnosticsStore {
        override fun read(): String? = null
        override fun write(value: String) = true
    }

    private class MemoryStateBackend : StateBackend {
        private var value: String? = null
        var writes = 0
        var failNextWrite = false
        override fun read(): String? = value
        override fun write(value: String) {
            writes++
            if (failNextWrite) {
                failNextWrite = false
                error("simulated root write failure")
            }
            this.value = value
        }
        override fun quarantine(rawValue: String, reason: String) = true
    }

    private class RecordingPhaseAlarmScheduler(
        private val backend: MemoryStateBackend,
    ) : PhaseAlarmScheduler {
        val scheduled = mutableListOf<FocusSession>()
        val cancelled = mutableListOf<String>()
        val rootsAtSchedule = mutableListOf<Map<String, Any?>?>()

        override fun schedule(session: FocusSession) {
            scheduled += session
            rootsAtSchedule += (LocalStateStore(backend).readDocument() as? StateReadResult.Loaded)?.state
        }

        override fun cancel(sessionId: String) {
            cancelled += sessionId
        }
    }

    private class RecordingEventOutbox(
        private val backend: MemoryStateBackend,
    ) : EventOutbox {
        val events = mutableListOf<SyncEvent>()
        val rootSnapshots = mutableListOf<Map<String, Any?>>()

        override fun enqueue(event: SyncEvent, consent: SyncConsent): Boolean {
            events += event
            rootSnapshots += (LocalStateStore(backend).readDocument() as StateReadResult.Loaded).state
            return false
        }
        override fun pending(nowUtcMillis: Long): List<QueuedEvent> = emptyList()
        override fun acknowledge(clientEventIds: Set<String>) = Unit
        override fun defer(clientEventId: String, nextAttemptAtUtcMillis: Long) = Unit
        override fun discardDisallowed(consent: SyncConsent) = 0
        override fun clear() = 0
        override fun makeDueNow() = 0
        override fun size() = events.size
        override fun nextAttemptAtUtcMillis(): Long? = null
    }

    private class RecordingPhaseNotifier : PhaseNotifier {
        override fun notify(transition: PhaseTransition) = PhaseNotificationResult.POSTED
        override fun cancel(sessionId: String) = Unit
    }

    companion object {
        private const val INITIAL_UTC_MILLIS = 1_800_000_000_000L
    }
}
