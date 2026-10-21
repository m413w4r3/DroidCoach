package dev.atlas.coach.goals

import dev.atlas.coach.app.GoalPanelCoordinator
import dev.atlas.coach.app.GoalPanelDraft
import dev.atlas.coach.data.LocalStateStore
import dev.atlas.coach.data.StateBackend
import dev.atlas.coach.data.StateReadResult
import dev.atlas.coach.focus.FakeClock
import dev.atlas.coach.focus.FocusEngine
import dev.atlas.coach.focus.FocusPlan
import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.focus.FocusStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalFocusPersistenceRecoveryTest {
    @Test
    fun linkedFocusRootCommitSurvivesRestartBeforePublicationAndCreditsWorkOnceOffline() {
        val backend = MemoryBackend()
        val store = LocalStateStore(backend)
        val startClock = FakeClock(startUtcMillis = STARTED_AT, startMonotonicMillis = 8_000L)
        store.write(emptyMap())
        val initialGoals = coordinator(store, startClock::nowMillis) { emptyList() }
        val goal = initialGoals.create(
            GoalPanelDraft(
                title = "Fixture objectif Focus",
                metric = GoalMetric.FOCUS_MINUTES,
                targetValue = 120L,
                description = "Donnée synthétique locale",
                nextAction = "Ouvrir le carnet",
            ),
        )
        val writesBeforeTransaction = backend.writeCount
        val engineBeforeCrash = FocusEngine(startClock)
        val preparedStart = engineBeforeCrash.prepareStart(
            plan = FocusPlan(
                presetId = "offline-restart-fixture",
                workSeconds = 25L * 60L,
                shortBreakSeconds = 5L * 60L,
                totalWorkCycles = 1,
            ),
            directiveId = null,
            sessionId = SESSION_ID,
            startedAtUtcMillis = startClock.nowMillis(),
            startedAtMonotonicMillis = startClock.monotonicMillis(),
        )
        val preparedLink = initialGoals.prepareFocusLink(goal.id, SESSION_ID, startClock.nowMillis())
        val oldRoot = loadedRoot(store)

        assertNull("prepare must leave the live engine idle", engineBeforeCrash.snapshot())
        assertTrue("prepare must not publish the Goal cache candidate", initialGoals.repository.get(goal.id)!!.linkedFocusSessionIds.isEmpty())
        assertEquals(0L, initialGoals.entries().single().progress.currentValue)

        // The process stops immediately after the single durable root commit, before either
        // in-memory candidate is published. The next process must see both halves together.
        store.write(
            oldRoot + mapOf(
                "focus" to preparedStart.session.toMap(),
                "goals" to preparedLink.goalsSubdocument,
            ),
        )
        assertEquals(writesBeforeTransaction + 1, backend.writeCount)
        assertNull(engineBeforeCrash.snapshot())
        assertFalse(initialGoals.repository.get(goal.id)!!.linkedFocusSessionIds.contains(SESSION_ID))

        val restartedRoot = loadedRoot(LocalStateStore(backend))
        val persistedStart = restartedRoot.getValue("focus").asStringMap()
        val restartedClock = FakeClock(startUtcMillis = STARTED_AT + 60_000L, startMonotonicMillis = 0L)
        val restartedEngine = FocusEngine(restartedClock)
        val resumed = restartedEngine.restore(persistedStart)
        var visibleSessions = listOf(resumed)
        val restartedGoals = coordinator(LocalStateStore(backend), restartedClock::nowMillis) { visibleSessions }

        assertEquals(FocusStatus.RUNNING, resumed.status)
        assertEquals(SESSION_ID, resumed.id)
        assertEquals(setOf(SESSION_ID), restartedGoals.repository.get(goal.id)!!.linkedFocusSessionIds)
        assertEquals(0L, restartedGoals.entries().single().progress.currentValue)

        restartedClock.advanceMillis(restartedEngine.snapshot()!!.remainingMillis)
        val finished = restartedEngine.snapshot()!!
        assertEquals(FocusStatus.FINISHED, finished.status)
        visibleSessions = listOf(finished, finished) // current session and history can both contain the ID
        assertEquals(
            "the current-session/history duplicate must count once",
            25L,
            restartedGoals.entries().single().progress.currentValue,
        )
        store.write(
            restartedRoot + mapOf(
                "focus" to finished.toMap(),
                "focusHistory" to listOf(finished.toMap()),
            ),
        )

        val reloadedRoot = loadedRoot(LocalStateStore(backend))
        val reloadedGoalCoordinator = coordinator(LocalStateStore(backend), restartedClock::nowMillis) {
            val current = reloadedRoot.getValue("focus").asStringMap()
            val history = (reloadedRoot["focusHistory"] as List<*>).map { it.asStringMap() }
            listOf(FocusSession.fromMap(current)) + history.map(FocusSession::fromMap)
        }

        assertEquals(setOf(SESSION_ID), reloadedGoalCoordinator.repository.get(goal.id)!!.linkedFocusSessionIds)
        assertEquals(25L, reloadedGoalCoordinator.entries().single().progress.currentValue)
        repeat(10) { assertEquals(25L, reloadedGoalCoordinator.entries().single().progress.currentValue) }
        assertEquals(25L, GoalProgressCalculator.completedFocusMinutes(
            reloadedGoalCoordinator.repository.get(goal.id)!!,
            listOf(FocusSession.fromMap(reloadedRoot.getValue("focus").asStringMap())) +
                (reloadedRoot["focusHistory"] as List<*>).map { FocusSession.fromMap(it.asStringMap()) },
        ))
    }

    private fun coordinator(
        store: LocalStateStore,
        nowUtcMillis: () -> Long,
        focusSessions: () -> List<FocusSession>,
    ) = GoalPanelCoordinator(
        readSubdocument = { loadedRootOrNull(store)?.get("goals").asNullableStringMap() },
        persistSubdocument = { goals ->
            val root = loadedRootOrNull(store).orEmpty()
            store.write(root + ("goals" to goals))
        },
        nowUtcMillis = nowUtcMillis,
        focusSessions = focusSessions,
        habits = { emptyList() },
        habitCheckIns = { emptyList() },
        newId = { GOAL_ID },
    )

    private fun loadedRoot(store: LocalStateStore): Map<String, Any?> =
        (store.readDocument() as StateReadResult.Loaded).state

    private fun loadedRootOrNull(store: LocalStateStore): Map<String, Any?>? =
        (store.readDocument() as? StateReadResult.Loaded)?.state

    private fun Any?.asNullableStringMap(): Map<String, Any?>? = this?.asStringMap()

    private fun Any?.asStringMap(): Map<String, Any?> = (this as? Map<*, *>)
        ?.entries
        ?.associate { (key, value) -> (key as? String ?: error("Expected string key")) to value }
        ?: error("Expected map")

    private class MemoryBackend : StateBackend {
        var raw: String? = null
        var writeCount = 0

        override fun read(): String? = raw

        override fun write(value: String) {
            writeCount++
            raw = value
        }
    }

    private companion object {
        const val STARTED_AT = 1_800_000_000_000L
        const val GOAL_ID = "offline-focus-goal"
        const val SESSION_ID = "offline-linked-focus"
    }
}
