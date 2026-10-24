package dev.atlas.coach.app

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import dev.atlas.coach.coach.CoachClock
import dev.atlas.coach.coach.Directive
import dev.atlas.coach.coach.MockCoachRepository
import dev.atlas.coach.content.ContentCatalog
import dev.atlas.coach.data.LocalStateStore
import dev.atlas.coach.data.PersistenceDiagnostics
import dev.atlas.coach.data.PersistenceDiagnosticsStore
import dev.atlas.coach.data.RevocationBarrier
import dev.atlas.coach.data.RevocationBarrierState
import dev.atlas.coach.data.RevocationBarrierStorage
import dev.atlas.coach.data.StateBackend
import dev.atlas.coach.data.StateReadResult
import dev.atlas.coach.focus.Clock
import dev.atlas.coach.focus.FocusEngine
import dev.atlas.coach.focus.FocusPlan
import dev.atlas.coach.focus.FocusPreset
import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.focus.FocusStatus
import dev.atlas.coach.focus.InMemoryFocusJournalLinkStore
import dev.atlas.coach.focus.PhaseAlarmScheduler
import dev.atlas.coach.focus.PhaseNotificationResult
import dev.atlas.coach.focus.PhaseNotifier
import dev.atlas.coach.focus.PhaseTransition
import dev.atlas.coach.focus.ReefPomodoroConfig
import dev.atlas.coach.goals.GoalMetric
import dev.atlas.coach.goals.GoalProgressCalculator
import dev.atlas.coach.goals.GoalRecord
import dev.atlas.coach.goals.GoalStatus
import dev.atlas.coach.habits.Habit
import dev.atlas.coach.habits.HabitCheckIn
import dev.atlas.coach.habits.HabitLinkPolicy
import dev.atlas.coach.habits.InMemoryHabitRepository
import dev.atlas.coach.routines.InMemoryFocusRoutineRepository
import dev.atlas.coach.rules.LocalPolicy
import dev.atlas.coach.rules.RuleEvaluator
import dev.atlas.coach.sync.SyncConsent
import dev.atlas.coach.sync.EventOutbox
import dev.atlas.coach.sync.QueuedEvent
import dev.atlas.coach.sync.SyncDataCategory
import dev.atlas.coach.sync.SyncEvent
import dev.atlas.coach.sync.SyncEventType
import dev.atlas.coach.sync.agent.AgentActionType
import dev.atlas.coach.sync.agent.AgentFocusCardState
import dev.atlas.coach.sync.agent.AgentFocusProposalCard
import dev.atlas.coach.sync.agent.AgentHomeState
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.LocalMandateStore
import dev.atlas.coach.sync.agent.ProposalLevel
import dev.atlas.coach.sync.agent.ProposalV2
import dev.atlas.coach.sync.agent.coach.AgentContextCoordinator
import dev.atlas.coach.sync.agent.context.AgentContextDomainViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

class GoalFocusStartTransactionTest {
    @Test
    fun focusAndGoalLinkUseOneRootCommit() {
        val harness = Harness()
        val goal = harness.createFocusGoal()
        val writesBeforeStart = harness.backend.writes
        harness.enableFocusNotifications()

        harness.controller.startFocus(ReefPomodoroConfig.DEFAULT, "Écriture", goal.id)

        val root = harness.persistedRoot()
        val savedGoal = root.goal(goal.id)
        val started = requireNotNull(harness.controller.focusSession)
        assertEquals(writesBeforeStart + 1, harness.backend.writes)
        assertEquals(FocusStatus.RUNNING, started?.status)
        assertEquals("Écriture", started?.taskTitle)
        assertEquals(setOf(started.id), savedGoal.linkedFocusSessionIds)
        assertEquals(FocusStatus.RUNNING, root.focus()?.status)
        assertTrue(harness.controller.latestMessage.orEmpty().contains("Pomodoro démarré"))
        assertEquals(listOf(SyncEventType.FOCUS_STARTED), harness.outbox.events.map(SyncEvent::eventType))
        assertEquals(listOf("alarm", "event"), harness.effectOrder)
        assertFalse(harness.latestMessageAtAlarm.orEmpty().contains("démarré"))
        assertFalse(harness.latestMessageAtEvent.orEmpty().contains("démarré"))
        assertEquals(AtlasScreenId.HOME, harness.routeAtAlarm)
        assertEquals(AtlasScreenId.HOME, harness.routeAtEvent)
        assertEquals(AtlasScreenId.FOCUS, harness.currentRoute())
        assertEquals(listOf(started.id), harness.alarms.scheduled.map { it.id })
        assertEquals(FocusStatus.RUNNING, harness.alarms.persistedFocusAtSchedule?.status)
        assertEquals(setOf(started.id), harness.alarms.persistedGoalAtSchedule?.linkedFocusSessionIds)
    }

    @Test
    fun failedRootCommitPublishesNeitherFocusNorGoalLink() {
        val harness = Harness()
        val goal = harness.createFocusGoal()
        val rootBefore = harness.persistedRoot()
        val writesBeforeStart = harness.backend.writes
        harness.enableFocusNotifications()
        harness.backend.failNextWrite = true

        harness.controller.startFocus(ReefPomodoroConfig.DEFAULT, goalId = goal.id)

        val rootAfter = harness.persistedRoot()
        assertEquals(writesBeforeStart + 1, harness.backend.writes)
        assertNull(harness.controller.focusSession)
        assertTrue(harness.goal(goal.id).linkedFocusSessionIds.isEmpty())
        assertEquals(rootBefore, rootAfter)
        assertTrue(rootAfter.goal(goal.id).linkedFocusSessionIds.isEmpty())
        assertEquals(rootBefore.goal(goal.id), rootAfter.goal(goal.id))
        assertNull(rootAfter.focus())
        assertEquals(
            "Focus non démarré. Aucun lien à l'objectif n'a été enregistré.",
            harness.controller.latestMessage,
        )
        assertFalse(harness.controller.latestMessage.orEmpty().contains("disponible en mémoire"))
        assertTrue(harness.alarms.scheduled.isEmpty())
        assertTrue(harness.outbox.events.isEmpty())
    }

    @Test
    fun processStopAfterProductRootWriteRestoresLinkedFocusAndCreditsOnce() {
        val firstProcess = Harness()
        val goal = firstProcess.createFocusGoal()
        val writesBeforeStart = firstProcess.backend.writes
        firstProcess.backend.crashAfterNextWrite = true

        val processStop = runCatching {
            firstProcess.controller.startFocus(
                ReefPomodoroConfig(25, 5, 15, cycles = 1, totalWorkCycles = 1),
                goalId = goal.id,
            )
        }.exceptionOrNull()

        assertTrue("the simulated process stop must occur after the durable write", processStop is SimulatedProcessStop)
        assertEquals(writesBeforeStart + 1, firstProcess.backend.writes)
        assertNull("the stopped process did not publish its Focus candidate", firstProcess.controller.focusSession)
        assertTrue("the stopped process did not publish its Goal candidate", firstProcess.goal(goal.id).linkedFocusSessionIds.isEmpty())

        val committedRoot = firstProcess.persistedRoot()
        val committedFocus = requireNotNull(committedRoot.focus())
        assertEquals(FocusStatus.RUNNING, committedFocus.status)
        assertEquals(setOf(committedFocus.id), committedRoot.goal(goal.id).linkedFocusSessionIds)

        // Recreate the domain engine and Goal coordinator from the exact backend bytes written by
        // AtlasController.startFocus. No test-side candidate or transaction write is involved.
        val resumedProcess = Harness(
            backend = firstProcess.backend,
            clock = TestClock(STARTED_AT + 60_000L, 0L),
            restoreFromBackend = true,
        )
        assertEquals(FocusStatus.RUNNING, resumedProcess.controller.focusSession?.status)
        assertEquals(committedFocus.id, resumedProcess.controller.focusSession?.id)
        assertEquals(setOf(committedFocus.id), resumedProcess.goal(goal.id).linkedFocusSessionIds)
        assertEquals(0L, resumedProcess.coachGoal(goal.id).progress.currentValue)

        resumedProcess.clock.advanceMillis(resumedProcess.controller.focusSession!!.remainingMillis)
        resumedProcess.controller.finishFocus()
        assertEquals(FocusStatus.FINISHED, resumedProcess.controller.focusSession?.status)
        assertEquals(25L, resumedProcess.coachGoal(goal.id).progress.currentValue)

        val reloadedProcess = Harness(
            backend = firstProcess.backend,
            clock = TestClock(STARTED_AT + 26 * 60_000L, 0L),
            restoreFromBackend = true,
        )
        assertEquals(FocusStatus.FINISHED, reloadedProcess.controller.focusSession?.status)
        assertEquals(setOf(committedFocus.id), reloadedProcess.goal(goal.id).linkedFocusSessionIds)
        repeat(10) { assertEquals(25L, reloadedProcess.coachGoal(goal.id).progress.currentValue) }
        assertEquals(
            25L,
            GoalProgressCalculator.completedFocusMinutes(
                reloadedProcess.goal(goal.id),
                listOfNotNull(reloadedProcess.controller.focusSession) + reloadedProcess.controller.focusHistory,
            ),
        )
    }

    @Test
    fun staleGoalSnapshotIsRejectedBeforeAnyRootWrite() {
        val harness = Harness()
        val goal = harness.createFocusGoal()
        val writesBeforeStart = harness.backend.writes
        val coordinator = harness.controller.field("goalPanelCoordinator") as GoalPanelCoordinator
        val repository = coordinator.repository
        val recordsField = repository.javaClass.getDeclaredField("records").apply { isAccessible = true }
        var changed = false
        val originalCapabilities = mapOf(AtlasController.CAP_FOCUS to true)
        harness.controller.setField(
            "capabilities" + "\$" + "delegate",
            HookMutableState(originalCapabilities) {
                if (!changed) {
                    changed = true
                    recordsField.set(repository, listOf(goal.copy(title = "Objectif modifié hors transaction")))
                }
            },
        )

        harness.controller.startFocus(ReefPomodoroConfig.DEFAULT, goalId = goal.id)

        assertTrue(changed)
        assertEquals(writesBeforeStart, harness.backend.writes)
        assertNull(harness.controller.focusSession)
        assertTrue(harness.controller.latestMessage.orEmpty().contains("a changé pendant la préparation"))
        assertTrue(harness.goal(goal.id).linkedFocusSessionIds.isEmpty())
    }

    @Test
    fun goalMutationWaitsUntilFocusTransactionCommits() {
        val harness = Harness()
        val goal = harness.createFocusGoal()
        val writing = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        harness.backend.blockNextWrite = writing to releaseWrite
        val startThread = Thread {
            harness.controller.startFocus(ReefPomodoroConfig.DEFAULT, goalId = goal.id)
        }
        startThread.start()
        assertTrue("Focus did not reach its root write", writing.await(5, TimeUnit.SECONDS))

        val mutationStarted = CountDownLatch(1)
        val mutationThread = Thread {
            mutationStarted.countDown()
            harness.controller.updateGoal(
                goal.id,
                GoalPanelDraft(
                    title = "Objectif renommé",
                    metric = goal.metric,
                    targetValue = goal.targetValue,
                    description = goal.description,
                    nextAction = goal.nextAction.orEmpty(),
                ),
            )
        }
        mutationThread.start()
        assertTrue(mutationStarted.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertTrue("Goal mutation entered while Focus held the transaction lock", mutationThread.isAlive)
        assertTrue(harness.goal(goal.id).linkedFocusSessionIds.isEmpty())

        releaseWrite.countDown()
        startThread.join(5_000)
        mutationThread.join(5_000)

        assertFalse(startThread.isAlive)
        assertFalse(mutationThread.isAlive)
        val finalGoal = harness.goal(goal.id)
        assertEquals("Objectif renommé", finalGoal.title)
        val started = requireNotNull(harness.controller.focusSession)
        assertTrue(finalGoal.linkedFocusSessionIds.contains(started.id))
    }

    @Test
    fun allSevenGoalMutationEntryPointsWaitForTheFocusTransactionLock() {
        val mutations: List<Pair<GoalMetric, (AtlasController, GoalRecord) -> Unit>> = listOf(
            GoalMetric.MANUAL to { controller, _ ->
                controller.createGoal(GoalPanelDraft("Créé en parallèle", GoalMetric.MANUAL, 1L, "", ""))
            },
            GoalMetric.FOCUS_MINUTES to { controller, goal ->
                controller.updateGoal(goal.id, draft(goal, "Modifié en parallèle"))
            },
            GoalMetric.FOCUS_MINUTES to { controller, goal ->
                controller.changeGoalStatus(goal.id, GoalStatus.PAUSED)
            },
            GoalMetric.FOCUS_MINUTES to { controller, goal -> controller.completeGoalFromProgress(goal.id) },
            GoalMetric.MANUAL to { controller, goal -> controller.confirmManualGoalCompletion(goal.id) },
            GoalMetric.FOCUS_MINUTES to { controller, goal -> controller.linkFocusToGoal(goal.id, "finished-session", true) },
            GoalMetric.HABIT_CHECKINS to { controller, goal -> controller.linkHabitToGoal(goal.id, "habit-id", true) },
        )

        mutations.forEach { (mutationMetric, mutate) ->
            val harness = Harness()
            val focusGoal = harness.createFocusGoal()
            val mutationGoal = harness.createGoal(mutationMetric, "Objectif à muter")
            val writing = CountDownLatch(1)
            val releaseWrite = CountDownLatch(1)
            harness.backend.blockNextWrite = writing to releaseWrite
            val startThread = Thread {
                harness.controller.startFocus(ReefPomodoroConfig.DEFAULT, goalId = focusGoal.id)
            }
            startThread.start()
            assertTrue("Focus did not reach its root write", writing.await(5, TimeUnit.SECONDS))

            val mutationStarted = CountDownLatch(1)
            val mutationFinished = CountDownLatch(1)
            val mutationThread = Thread {
                mutationStarted.countDown()
                try {
                    mutate(harness.controller, mutationGoal)
                } finally {
                    mutationFinished.countDown()
                }
            }
            mutationThread.start()
            assertTrue(mutationStarted.await(5, TimeUnit.SECONDS))
            assertFalse("Goal mutation '${mutationMetric.name}' escaped the Focus transaction", mutationFinished.await(100, TimeUnit.MILLISECONDS))

            releaseWrite.countDown()
            startThread.join(5_000)
            mutationThread.join(5_000)
            assertFalse(startThread.isAlive)
            assertFalse(mutationThread.isAlive)
            assertEquals(FocusStatus.RUNNING, harness.controller.focusSession?.status)
        }
    }

    @Test
    fun concurrentDoubleTapStartRequestsCommitOnlyOneFocusSession() {
        val harness = Harness()
        val goal = harness.createFocusGoal()
        harness.enableFocusNotifications()
        val startGate = CountDownLatch(1)
        val ready = CountDownLatch(2)
        val requests = List(2) {
            Thread {
                ready.countDown()
                startGate.await()
                harness.controller.startFocus(ReefPomodoroConfig.DEFAULT, goalId = goal.id)
            }.also(Thread::start)
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        val writesBeforeStart = harness.backend.writes
        startGate.countDown()
        requests.forEach { it.join(5_000) }

        assertTrue(requests.none(Thread::isAlive))
        assertEquals(writesBeforeStart + 1, harness.backend.writes)
        assertEquals(FocusStatus.RUNNING, harness.controller.focusSession?.status)
        assertEquals(1, harness.goal(goal.id).linkedFocusSessionIds.size)
        assertEquals(1, harness.alarms.scheduled.size)
        assertEquals(listOf(SyncEventType.FOCUS_STARTED), harness.outbox.events.map(SyncEvent::eventType))
    }

    @Test
    fun everyLegacyLocalStartEntryPointUsesOneRootTransaction() {
        val starts: List<Pair<String, (Harness) -> Unit>> = listOf(
            "preset" to { it.controller.startFocus(FocusPreset.POMODORO_25_5) },
            "plan" to { it.controller.startFocus(FocusPlan.custom(30L, totalWorkCycles = 1)) },
            "config" to { it.controller.startFocus(ReefPomodoroConfig.DEFAULT) },
            "config-title" to { it.controller.startFocus(ReefPomodoroConfig.DEFAULT, "Session locale") },
            "demo" to { it.controller.startDemoFocus() },
        )

        starts.forEach { (name, start) ->
            val harness = Harness()
            val before = harness.backend.writes
            start(harness)
            assertEquals("$name performed more than one root commit", before + 1, harness.backend.writes)
            assertEquals(FocusStatus.RUNNING, harness.controller.focusSession?.status)
            assertEquals(listOf(SyncEventType.FOCUS_STARTED), harness.outbox.events.map(SyncEvent::eventType))
        }

        val responseHarness = Harness()
        val beforeResponse = responseHarness.backend.writes
        responseHarness.controller.respond(dev.atlas.coach.coach.DirectiveChoice.START)
        assertEquals(beforeResponse + 1, responseHarness.backend.writes)
        assertEquals(FocusStatus.RUNNING, responseHarness.controller.focusSession?.status)
        assertTrue(responseHarness.outbox.events.any { it.eventType == SyncEventType.FOCUS_STARTED })
    }

    @Test
    fun controlledClockCreditsOnlyCompletedLinkedWorkAndRepeatedReadsDoNotDoubleCredit() {
        val harness = Harness()
        val goal = harness.createFocusGoal()
        assertEquals(0L, harness.coachGoal(goal.id).progress.currentValue)

        harness.controller.startFocus(
            ReefPomodoroConfig(25, 5, 15, cycles = 4, totalWorkCycles = 1),
            goalId = goal.id,
        )
        assertEquals(0L, harness.coachGoal(goal.id).progress.currentValue)
        harness.controller.toggleFocusPause()
        assertEquals(FocusStatus.PAUSED, harness.controller.focusSession?.status)
        assertEquals(0L, harness.coachGoal(goal.id).progress.currentValue)
        harness.clock.advanceMillis(8 * 60 * 60_000L)
        assertEquals(0L, harness.coachGoal(goal.id).progress.currentValue)
        harness.controller.toggleFocusPause()
        assertEquals(FocusStatus.RUNNING, harness.controller.focusSession?.status)

        harness.clock.advanceMillis(25 * 60_000L)
        harness.controller.finishFocus()

        assertEquals(FocusStatus.FINISHED, harness.controller.focusSession?.status)
        assertEquals(25L, harness.coachGoal(goal.id).progress.currentValue)
        repeat(5) { assertEquals(25L, harness.coachGoal(goal.id).progress.currentValue) }
        assertEquals(setOf(harness.controller.focusSession!!.id), harness.goal(goal.id).linkedFocusSessionIds)
    }

    @Test
    fun localGoalLinkSurvivesPauseResumeFinishAndUnrelatedRootWrites() {
        val harness = Harness()
        val goal = harness.createFocusGoal()
        harness.enableFocusNotifications()
        val session = harness.startLinkedFocus(goal.id)
        assertEquals(setOf(session.id), harness.persistedRoot().goal(goal.id).linkedFocusSessionIds)

        harness.controller.toggleFocusPause()
        harness.clock.advanceMillis(3 * 60_000L)
        harness.controller.toggleFocusPause()
        harness.clock.advanceMillis(25 * 60_000L)
        harness.controller.finishFocus()
        harness.controller.reportMessage("Sauvegarde indépendante du Goal")

        val persisted = harness.persistedRoot()
        assertEquals(FocusStatus.FINISHED, persisted.focus()?.status)
        assertEquals(setOf(session.id), persisted.goal(goal.id).linkedFocusSessionIds)
        assertEquals(25L, harness.coachGoal(goal.id).progress.currentValue)
    }

    @Test
    fun agentGoalLinkSurvivesPauseResumeFinishAndUnrelatedRootWrites() {
        val harness = Harness()
        val goal = harness.createFocusGoal()
        harness.enableFocusNotifications()
        val session = harness.startAgentLinkedFocus(goal.id)
        assertEquals(setOf(session.id), harness.persistedRoot().goal(goal.id).linkedFocusSessionIds)
        assertEquals(listOf("alarm", "event"), harness.effectOrder)
        assertEquals(AgentFocusCardState.PROPOSED, harness.agentCardStateAtAlarm)
        assertEquals(AgentFocusCardState.PROPOSED, harness.agentCardStateAtEvent)
        assertEquals(AgentFocusCardState.RUNNING, harness.agentCardState())
        harness.effectOrder.clear()

        harness.controller.toggleFocusPause()
        harness.clock.advanceMillis(3 * 60_000L)
        harness.controller.toggleFocusPause()
        harness.clock.advanceMillis(25 * 60_000L)
        harness.controller.finishFocus()
        harness.controller.reportMessage("Sauvegarde indépendante du Goal agent")

        val persisted = harness.persistedRoot()
        assertEquals(FocusStatus.FINISHED, persisted.focus()?.status)
        assertEquals(setOf(session.id), persisted.goal(goal.id).linkedFocusSessionIds)
        assertEquals(25L, harness.coachGoal(goal.id).progress.currentValue)
    }

    @Test
    fun inactiveAndManualGoalsAreRefusedWithoutStartingOrWritingFocus() {
        val pausedHarness = Harness()
        val paused = pausedHarness.createFocusGoal()
        pausedHarness.controller.changeGoalStatus(paused.id, GoalStatus.PAUSED)
        val pausedWrites = pausedHarness.backend.writes
        pausedHarness.controller.startFocus(ReefPomodoroConfig.DEFAULT, goalId = paused.id)
        assertEquals(pausedWrites, pausedHarness.backend.writes)
        assertNull(pausedHarness.controller.focusSession)
        assertTrue(pausedHarness.goal(paused.id).linkedFocusSessionIds.isEmpty())

        val manualHarness = Harness()
        val manual = manualHarness.createGoal(GoalMetric.MANUAL, "Objectif manuel")
        val manualWrites = manualHarness.backend.writes
        manualHarness.controller.startFocus(ReefPomodoroConfig.DEFAULT, goalId = manual.id)
        assertEquals(manualWrites, manualHarness.backend.writes)
        assertNull(manualHarness.controller.focusSession)
        assertTrue(manualHarness.goal(manual.id).linkedFocusSessionIds.isEmpty())
    }

    @Test
    fun unlinkedSessionAndCancelledLinkedSessionNeverChangeFocusGoalProgress() {
        val harness = Harness()
        val goal = harness.createFocusGoal()
        harness.controller.startFocus(FocusPlan.custom(25 * 60L, totalWorkCycles = 1))
        harness.clock.advanceMillis(10 * 60_000L)
        harness.controller.finishFocus()
        assertTrue(harness.goal(goal.id).linkedFocusSessionIds.isEmpty())
        assertEquals(0L, harness.coachGoal(goal.id).progress.currentValue)

        val linked = harness.startLinkedFocus(goal.id)
        assertEquals(FocusStatus.RUNNING, linked.status)
        harness.controller.cancelFocus()
        assertEquals(FocusStatus.CANCELLED, harness.controller.focusSession?.status)
        assertEquals(0L, harness.coachGoal(goal.id).progress.currentValue)
    }

    private inner class Harness(
        val backend: MemoryStateBackend = MemoryStateBackend(),
        val clock: TestClock = TestClock(STARTED_AT, 20_000L),
        private val restoreFromBackend: Boolean = false,
    ) {
        val store = LocalStateStore(backend)
        val controller = unsafeController()
        private val restoredRoot = if (restoreFromBackend) {
            (store.readDocument() as? StateReadResult.Loaded)?.state.orEmpty()
        } else {
            emptyMap()
        }
        val effectOrder = mutableListOf<String>()
        var latestMessageAtAlarm: String? = null
        var latestMessageAtEvent: String? = null
        var routeAtAlarm: String? = null
        var routeAtEvent: String? = null
        var agentCardStateAtAlarm: AgentFocusCardState? = null
        var agentCardStateAtEvent: AgentFocusCardState? = null
        val outbox = RecordingEventOutbox { event ->
            if (event.eventType == SyncEventType.FOCUS_STARTED) {
                effectOrder += "event"
                latestMessageAtEvent = controller.latestMessage
                routeAtEvent = currentRoute()
                agentCardStateAtEvent = agentCardState()
            }
        }
        val alarms = RecordingPhaseAlarmScheduler(::persistedRootOrNull) {
            effectOrder += "alarm"
            latestMessageAtAlarm = controller.latestMessage
            routeAtAlarm = currentRoute()
            agentCardStateAtAlarm = agentCardState()
        }
        private val phaseNotifier = RecordingPhaseNotifier()
        private val habits = InMemoryHabitRepository(clock = clock::nowMillis)
        private val focusRoutines = InMemoryFocusRoutineRepository()
        private val initialGoals = stateObject(restoredRoot["goals"]) ?: mapOf(
            "schema_version" to 1,
            "goals" to emptyList<Map<String, Any?>>(),
        )
        private val restoredFocusHistory = (restoredRoot["focusHistory"] as? List<*>)
            .orEmpty()
            .mapNotNull { row -> stateObject(row)?.let { runCatching { FocusSession.fromMap(it) }.getOrNull() } }
            .filter { it.status in setOf(FocusStatus.FINISHED, FocusStatus.CANCELLED) }
        private val restoredFocusEngine = FocusEngine(clock).also { engine ->
            stateObject(restoredRoot["focus"])?.let { saved ->
                runCatching { engine.restore(FocusSession.fromMap(saved)) }
            }
        }
        private val mandateStore = LocalMandateStore(AgentMandate.default())

        init {
            val directive = Directive.demo(clock.nowMillis())
            val coach = MockCoachRepository(CoachClock(clock::nowMillis), directive)
            val catalog = ContentCatalog.demo()
            val rules = RuleEvaluator(ZoneId.of("UTC"))
            val coordinator = AgentContextCoordinator(
                initialConsentAtUtcMillis = emptyMap(),
                legacyConsentedCategories = emptySet(),
                mandateProvider = mandateStore::current,
                domainsProvider = { AgentContextDomainViews() },
                nowUtcMillis = clock::nowMillis,
                persistConsentDates = {},
            )
            controller.setField("clock", clock)
            controller.setField("goalFocusTransactionLock", Any())
            controller.setField("acceptedAgentFocusGoalId", ThreadLocal<String?>())
            controller.setField("focusEngine", restoredFocusEngine)
            controller.setField("phaseAlarms", alarms)
            controller.setField("focusNotifications", phaseNotifier)
            controller.setField("focusRoutineRepository", focusRoutines)
            controller.setField("focusLinkStore", InMemoryFocusJournalLinkStore())
            controller.setField("habits", habits)
            controller.setField("habitLinkPolicy", HabitLinkPolicy(habits))
            controller.setField("coach", coach)
            controller.setField("catalog", catalog)
            controller.setField("ruleEvaluator", rules)
            controller.setField("agentMandateStore", mandateStore)
            controller.setField("agentContextCoordinator", coordinator)
            controller.setField("store", store)
            controller.setField("persistenceAllowed", true)
            controller.setField("revocationBarrier", RevocationBarrier(object : RevocationBarrierStorage {
                override fun readState() = RevocationBarrierState()
                override fun writeState(state: RevocationBarrierState) = true
            }))
            controller.setField("agentAuthorizationCommitInProgress", AtomicBoolean(false))
            controller.setField("agentRequestGeneration", AtomicLong(0L))
            controller.setField("proactiveEffectGeneration", AtomicLong(0L))
            controller.setField("persistenceDiagnostics", PersistenceDiagnostics(object : PersistenceDiagnosticsStore {
                override fun read(): String? = null
                override fun write(value: String) = true
            }))
            controller.setField("launcherState", AtlasLauncherState(emptySet()))
            controller.setField("goalsSubdocument", initialGoals)
            controller.setField("contentActivityTimestamps", emptyList<Long>())
            controller.setField("agentConversationId", null)
            controller.setField("proactiveCoachStateValue", null)
            controller.setField("lastSimulatedEvent", null)
            controller.setField("appliedLocalAgentEffectKeys", mutableSetOf<String>())
            controller.setField("agentMandateStore", mandateStore)
            controller.setField("agentContextCoordinator", coordinator)
            controller.setField("agentConsentMigrationPending", false)
            controller.setField("agentConsentMigrationReady", java.util.concurrent.atomic.AtomicBoolean(true))
            controller.setField("stateGraphReady", true)
            controller.setField("goalPanelCoordinator", GoalPanelCoordinator(
                readSubdocument = { controller.field("goalsSubdocument") as? Map<String, Any?> },
                persistSubdocument = { goals ->
                    controller.setField("goalsSubdocument", goals)
                    controller.persistForGoalTest()
                },
                nowUtcMillis = clock::nowMillis,
                focusSessions = {
                    listOfNotNull<FocusSession>(controller.focusSession) + controller.focusHistory
                },
                habits = habits::list,
                habitCheckIns = { emptyList<HabitCheckIn>() },
            ))
            controller.setState("focusSnapshot", restoredFocusEngine.currentSession())
            controller.setState("focusHistoryEntries", restoredFocusHistory)
            controller.setState("focusRoutinesSnapshot", focusRoutines.list())
            controller.setState("directiveSnapshot", directive)
            controller.setState("habitsSnapshot", emptyList<Habit>())
            controller.setState("checkedHabitIdsSnapshot", emptySet<String>())
            controller.setState("journalSnapshot", emptyList<dev.atlas.coach.coach.CoachJournalEntry>())
            controller.setState("interventionsSnapshot", emptyList<dev.atlas.coach.rules.Intervention>())
            controller.setState("coachPausedSnapshot", false)
            controller.setState("contentItemsSnapshot", catalog.items)
            controller.setState("agentHomeStateSnapshot", AgentHomeState())
            controller.setState("agentConversationSnapshot", emptyList<dev.atlas.coach.sync.agent.coach.AgentConversationMessage>())
            controller.setState("agentSuggestionsSnapshot", emptyList<String>())
            controller.setState("policy", LocalPolicy())
            controller.setState("capabilities", mapOf(
                AtlasController.CAP_FOCUS to true,
                AtlasController.CAP_FOCUS_NOTIFICATIONS to false,
                AtlasController.CAP_COACH to true,
            ))
            controller.setState("latestMessage", null)
            controller.setState("simulationMessage", null)
            controller.setState("lastSimulationInterventions", 0, integer = true)
            controller.setState("revision", 0, integer = true)
            controller.setState("agentPlanReply", null)
            controller.setState("lastContextDisclosureAuditSnapshot", null)
            controller.setField("navigationState", AtlasNavigationState())
            controller.setField(
                "effectiveConsent",
                AtomicReference(SyncConsent(setOf(SyncDataCategory.FOCUS))),
            )
            controller.setField("syncOutbox", outbox)
        }

        fun createFocusGoal(): GoalRecord = coordinator().create(GoalPanelDraft(
            title = "Objectif Focus",
            metric = GoalMetric.FOCUS_MINUTES,
            targetValue = 120L,
            description = "",
            nextAction = "",
        ))

        fun createGoal(metric: GoalMetric, title: String): GoalRecord = coordinator().create(GoalPanelDraft(
            title = title,
            metric = metric,
            targetValue = if (metric == GoalMetric.MANUAL) 1L else 10L,
            description = "",
            nextAction = "",
        ))

        fun goal(id: String): GoalRecord = coordinator().repository.get(id)!!

        fun coachGoal(id: String): GoalsPanelEntry = controller.coachGoals.single { it.record.id == id }

        fun startLinkedFocus(goalId: String): FocusSession {
            controller.startFocus(
                ReefPomodoroConfig(25, 5, 15, cycles = 4, totalWorkCycles = 1),
                goalId = goalId,
            )
            return requireNotNull(controller.focusSession)
        }

        fun startAgentLinkedFocus(goalId: String): FocusSession {
            val proposalId = UUID.randomUUID().toString()
            val idempotencyKey = UUID.randomUUID().toString()
            controller.setState("agentHomeStateSnapshot", AgentHomeState(
                focusProposal = AgentFocusProposalCard(
                    proposalId = proposalId,
                    label = "Session agent",
                    focusMinutes = 25,
                    breakMinutes = 5,
                    cycles = 1,
                    state = AgentFocusCardState.PROPOSED,
                ),
            ))
            val acceptedGoal = controller.field("acceptedAgentFocusGoalId") as ThreadLocal<String?>
            acceptedGoal.set(goalId)
            val now = clock.nowMillis()
            val proposal = ProposalV2(
                schemaVersion = 2,
                proposalId = UUID.randomUUID().toString(),
                idempotencyKey = idempotencyKey,
                conversationId = null,
                mandateId = AgentMandate.default().id,
                mandateVersion = AgentMandate.default().version,
                contextVersion = "goal-focus-root-mirror-test",
                createdAtUtcMillis = now,
                expiresAtUtcMillis = now + 60_000L,
                actionType = AgentActionType.FOCUS_START.wireValue,
                parameters = mapOf("session_ref" to proposalId),
                reason = "Test local agent start",
                requiredLevel = ProposalLevel.CONFIRM,
            )
            return try {
                assertTrue(controller.startFocus(proposal).applied)
                requireNotNull(controller.focusSession)
            } finally {
                acceptedGoal.remove()
            }
        }

        fun enableFocusNotifications() {
            controller.setState(
                "capabilities",
                controller.capabilities + (AtlasController.CAP_FOCUS_NOTIFICATIONS to true),
            )
        }

        fun persistedRoot(): Map<String, Any?> =
            (store.readDocument() as StateReadResult.Loaded).state

        fun currentRoute(): String = (controller.field("navigationState") as AtlasNavigationState).current

        fun agentCardState(): AgentFocusCardState? =
            ((controller.field("agentHomeStateSnapshot\$delegate") as MutableState<AgentHomeState>)
                .value.focusProposal?.state)

        private fun persistedRootOrNull(): Map<String, Any?>? =
            (store.readDocument() as? StateReadResult.Loaded)?.state

        private fun coordinator() = controller.field("goalPanelCoordinator") as GoalPanelCoordinator

        private fun AtlasController.persistForGoalTest() {
            val method = AtlasController::class.java.getDeclaredMethod("persistAfterInitializationOrThrow", String::class.java)
                .apply { isAccessible = true }
            method.invoke(this, "goals.write")
        }
    }

    private class MemoryStateBackend : StateBackend {
        var value: String? = null
        var writes = 0
        var failNextWrite = false
        var crashAfterNextWrite = false
        var blockNextWrite: Pair<CountDownLatch, CountDownLatch>? = null

        override fun read(): String? = value

        override fun write(value: String) {
            writes++
            blockNextWrite?.let { (entered, release) ->
                blockNextWrite = null
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release the test write" }
            }
            if (failNextWrite) {
                failNextWrite = false
                throw IllegalStateException("simulated root write failure")
            }
            this.value = value
            if (crashAfterNextWrite) {
                crashAfterNextWrite = false
                throw SimulatedProcessStop()
            }
        }

        override fun quarantine(rawValue: String, reason: String): Boolean = true
    }

    private class SimulatedProcessStop : Error("simulated process stop after root write")

    private class RecordingEventOutbox(
        private val onEnqueue: (SyncEvent) -> Unit = {},
    ) : EventOutbox {
        val events = mutableListOf<SyncEvent>()
        override fun enqueue(event: SyncEvent, consent: SyncConsent): Boolean {
            events += event
            onEnqueue(event)
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

    private class RecordingPhaseAlarmScheduler(
        private val readCommittedRoot: () -> Map<String, Any?>?,
        private val onSchedule: () -> Unit = {},
    ) : PhaseAlarmScheduler {
        val scheduled = mutableListOf<FocusSession>()
        val cancelled = mutableListOf<String>()
        var persistedFocusAtSchedule: FocusSession? = null
            private set
        var persistedGoalAtSchedule: GoalRecord? = null
            private set

        override fun schedule(session: FocusSession) {
            scheduled += session
            readCommittedRoot()?.let { root ->
                persistedFocusAtSchedule = (root["focus"] as? Map<*, *>)
                    ?.entries?.associate { it.key as String to it.value }
                    ?.let(FocusSession::fromMap)
                val goalRows = ((root["goals"] as? Map<*, *>)?.get("goals") as? List<*>)
                    .orEmpty().filterIsInstance<Map<String, Any?>>()
                persistedGoalAtSchedule = goalRows.singleOrNull {
                    session.id in GoalRecord.fromMap(it).linkedFocusSessionIds
                }?.let { GoalRecord.fromMap(it) }
            }
            onSchedule()
        }

        override fun cancel(sessionId: String) {
            cancelled += sessionId
        }
    }

    private class RecordingPhaseNotifier : PhaseNotifier {
        val notifications = mutableListOf<PhaseTransition>()
        val cancelled = mutableListOf<String>()

        override fun notify(transition: PhaseTransition): PhaseNotificationResult {
            notifications += transition
            return PhaseNotificationResult.POSTED
        }

        override fun cancel(sessionId: String) {
            cancelled += sessionId
        }
    }

    private class TestClock(
        private var utcMillis: Long,
        private var elapsedMillis: Long,
    ) : Clock {
        override fun nowMillis() = utcMillis
        override fun monotonicMillis() = elapsedMillis
        fun advanceMillis(millis: Long) {
            utcMillis += millis
            elapsedMillis += millis
        }
    }

    private class HookMutableState<T>(
        initial: T,
        private val onSecondRead: () -> Unit,
    ) : MutableState<T> {
        private var current = initial
        private var reads = 0

        override var value: T
            get() {
                reads++
                if (reads == 2) onSecondRead()
                return current
            }
            set(value) { current = value }

        override operator fun component1(): T = value
        override operator fun component2(): (T) -> Unit = { value = it }
    }

    private fun rootGoal(root: Map<String, Any?>, id: String): GoalRecord {
        val goals = root["goals"] as Map<*, *>
        val rows = goals["goals"] as List<*>
        val values = rows.filterIsInstance<Map<String, Any?>>().single { it["id"] == id }
        return GoalRecord.fromMap(values)
    }

    private fun Map<String, Any?>.goal(id: String): GoalRecord = rootGoal(this, id)

    private fun Map<String, Any?>.focus(): FocusSession? =
        (this["focus"] as? Map<*, *>)?.entries?.associate { it.key as String to it.value }?.let(FocusSession::fromMap)

    private fun stateObject(value: Any?): Map<String, Any?>? {
        val raw = value as? Map<*, *> ?: return null
        if (raw.keys.any { it !is String }) return null
        return raw.entries.associate { (key, item) -> (key as String) to item }
    }

    private fun draft(goal: GoalRecord, title: String) = GoalPanelDraft(
        title = title,
        metric = goal.metric,
        targetValue = goal.targetValue,
        description = goal.description,
        nextAction = goal.nextAction.orEmpty(),
    )

    private fun unsafeController(): AtlasController {
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null) as Unsafe
        return unsafe.allocateInstance(AtlasController::class.java) as AtlasController
    }

    private fun AtlasController.setState(name: String, value: Any?, integer: Boolean = false) {
        setField(name + "\$" + "delegate", if (integer) mutableIntStateOf(value as Int) else mutableStateOf(value))
    }

    private fun AtlasController.setField(name: String, value: Any?) {
        AtlasController::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(this@setField, value)
        }
    }

    private fun AtlasController.field(name: String): Any? =
        AtlasController::class.java.getDeclaredField(name).let { field ->
            field.isAccessible = true
            field.get(this)
        }

    private companion object {
        const val STARTED_AT = 1_800_000_000_000L
    }
}
