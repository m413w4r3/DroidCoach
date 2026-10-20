package dev.atlas.coach.coach.proactive.android

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.atlas.coach.app.AtlasController
import dev.atlas.coach.app.AtlasApplication
import dev.atlas.coach.app.GoalPanelDraft
import dev.atlas.coach.app.ProactiveCoachNotificationPublisher
import dev.atlas.coach.data.LocalStateStore
import dev.atlas.coach.data.PersistenceDiagnosticsStore
import dev.atlas.coach.data.RevocationBarrierState
import dev.atlas.coach.data.RevocationBarrierStorage
import dev.atlas.coach.data.StateBackend
import dev.atlas.coach.focus.FakeClock
import dev.atlas.coach.focus.Clock
import dev.atlas.coach.goals.GoalMetric
import dev.atlas.coach.coach.proactive.ProactiveCoachState
import dev.atlas.coach.coach.proactive.ProactiveRunStatus
import dev.atlas.coach.sync.agent.AgentFocusWindow
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.context.AgentContextCategory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ProactiveCoachWorkerInstrumentationTest {
    @Test
    fun periodicJobKeepsOneThirtyMinuteRequestAndWorkerRunsWithoutActivity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val workManager = WorkManager.getInstance(context)

        ProactiveCoachWorkScheduler.refresh(context, eligible = true)
        val first = workManager.getWorkInfosForUniqueWork(ProactiveCoachWorkScheduler.UNIQUE_WORK_NAME)
            .get(5, TimeUnit.SECONDS)
        ProactiveCoachWorkScheduler.refresh(context, eligible = true)
        val second = workManager.getWorkInfosForUniqueWork(ProactiveCoachWorkScheduler.UNIQUE_WORK_NAME)
            .get(5, TimeUnit.SECONDS)

        assertEquals(30L, ProactiveCoachWorkScheduler.REPEAT_INTERVAL_MINUTES)
        assertEquals(1, first.size)
        assertEquals(first.single().id, second.single().id)

        val worker = TestListenableWorkerBuilder<ProactiveCoachWorker>(context).build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertTrue(context is AtlasApplication)
        ProactiveCoachWorkScheduler.cancel(context)
    }

    @Test
    fun isolatedControllerRecordsAndDeduplicatesDueWorkWithoutActivityAndRespectsQuietHours() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = isolatedContext("worker-e2e")
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val workManager = WorkManager.getInstance(context)
        val zone = ZoneId.of("Europe/Paris")
        val date = LocalDate.of(2026, 10, 20)
        val initialTime = date.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
        val clock = FakeClock(initialTime, 0L)
        val backend = MemoryStateBackend()
        val barrier = MemoryBarrierStorage()
        val goalsGrantAt = date.atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
        val mandate = AgentMandate.default("worker-e2e").copy(
            focusWindow = AgentFocusWindow(timezone = zone.id),
            sharedDataCategories = setOf(AgentContextCategory.GOALS.wireValue),
        )
        LocalStateStore(backend).write(mapOf(
            "capabilities" to mapOf(
                AtlasController.CAP_AGENT to true,
                AtlasController.CAP_AGENT_PROACTIVE to true,
            ),
            "agentMandate" to mandate.toJson().toString(),
            "agentContextConsentAt" to mapOf(AgentContextCategory.GOALS.wireValue to goalsGrantAt),
        ))

        lateinit var controller: AtlasController
        instrumentation.runOnMainSync {
            controller = makeController(context, clock, backend, barrier)
        }
        assertTrue("fixture must have valid agent and proactive opt-ins", controller.isAgentEnabled)
        assertEquals(true, controller.capabilities[AtlasController.CAP_AGENT_PROACTIVE])
        assertTrue(
            "isolated context must expose notification availability without changing device settings",
            ProactiveCoachNotificationPublisher.notificationsAvailable(context),
        )

        val firstGoalDueAt = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        lateinit var firstGoalId: String
        instrumentation.runOnMainSync {
            controller.createGoal(GoalPanelDraft(
                title = "Test goal review",
                metric = GoalMetric.MANUAL,
                targetValue = 1L,
                description = "",
                nextAction = "",
                dueAt = firstGoalDueAt,
            ))
            firstGoalId = controller.coachGoals.single().record.id
        }
        clock.setWallMillis(date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli())

        val firstResult = runWorkerDecision(controller)
        // The fixture has no configured AgentApiClient: the scheduler durably reserves the
        // due trigger, then reports REQUEST_FAILED without an HTTP call or notification.
        assertEquals(ProactiveRunStatus.REQUEST_FAILED, firstResult.status)
        var state = readProactiveState(backend, clock.nowMillis())
        val goalEventId = "goal-review:$firstGoalId:$firstGoalDueAt"
        assertEquals(1, state.requestReservations.size)
        assertEquals("failed", state.requestReservations.single().status.wireValue)
        assertTrue(state.requestReservations.single().triggerEventIds.contains(goalEventId))
        assertTrue(state.requestReservations.single().triggerEventIds.contains("daily-plan:$date"))
        assertTrue(state.pendingTriggers.isEmpty())
        assertTrue(state.notificationReservations.isEmpty())

        lateinit var recreated: AtlasController
        instrumentation.runOnMainSync {
            recreated = makeController(context, clock, backend, barrier)
        }
        val secondResult = runWorkerDecision(recreated)
        assertEquals(ProactiveRunStatus.NO_ELIGIBLE_TRIGGERS, secondResult.status)
        state = readProactiveState(backend, clock.nowMillis())
        assertEquals("recreated controller must not reserve the same events again", 1, state.requestReservations.size)
        assertEquals(2, state.consumedEventTombstones.count { it.eventId in setOf(goalEventId, "daily-plan:$date") })
        assertTrue(state.pendingTriggers.isEmpty())
        assertTrue(state.notificationReservations.isEmpty())

        val secondGoalDueAt = date.atTime(11, 0).atZone(zone).toInstant().toEpochMilli()
        instrumentation.runOnMainSync {
            recreated.createGoal(GoalPanelDraft(
                title = "Test quiet-hours goal",
                metric = GoalMetric.MANUAL,
                targetValue = 1L,
                description = "",
                nextAction = "",
                dueAt = secondGoalDueAt,
            ))
        }
        clock.setWallMillis(date.atTime(23, 0).atZone(zone).toInstant().toEpochMilli())
        val quietHoursResult = runWorkerDecision(recreated)
        assertEquals(ProactiveRunStatus.QUIET_HOURS, quietHoursResult.status)
        state = readProactiveState(backend, clock.nowMillis())
        assertEquals("quiet-hours decision must not reserve a plan request", 1, state.requestReservations.size)
        assertEquals(1, state.pendingTriggers.size)
        assertEquals("goal_review_due", state.pendingTriggers.single().source.wireValue)
        assertTrue(state.notificationReservations.isEmpty())

        val activeBeforeRevocation = workManager
            .getWorkInfosForUniqueWork(ProactiveCoachWorkScheduler.UNIQUE_WORK_NAME)
            .get(5, TimeUnit.SECONDS)
        assertEquals(1, activeBeforeRevocation.size)
        assertFalse(activeBeforeRevocation.single().state == WorkInfo.State.CANCELLED)

        instrumentation.runOnMainSync { recreated.revokeAgentMandate() }
        val revokedResult = runWorkerDecision(recreated)
        assertEquals(ProactiveRunStatus.CONSENT_REVOKED, revokedResult.status)
        val afterRevocation = workManager
            .getWorkInfosForUniqueWork(ProactiveCoachWorkScheduler.UNIQUE_WORK_NAME)
            .get(5, TimeUnit.SECONDS)
        assertEquals(1, afterRevocation.size)
        assertEquals(WorkInfo.State.CANCELLED, afterRevocation.single().state)
    }

    private fun runWorkerDecision(controller: AtlasController) =
        controller.runProactiveWorkFromWorker().get(30, TimeUnit.SECONDS)

    private fun readProactiveState(backend: StateBackend, nowUtcMillis: Long): ProactiveCoachState {
        val json = LocalStateStore(backend).read()["proactiveCoach"] as? String
        assertNotNull("controller must persist proactive state", json)
        return ProactiveCoachState.fromJsonString(requireNotNull(json), nowUtcMillis)
    }

    private fun makeController(
        context: Context,
        clock: Clock,
        backend: StateBackend,
        barrier: RevocationBarrierStorage,
    ) = AtlasController(
        context = context,
        clock = clock,
        stateBackendOverride = backend,
        revocationBarrierStorageOverride = barrier,
        persistenceDiagnosticsStoreOverride = MemoryDiagnosticsStore(),
    )

    private fun isolatedContext(label: String): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "lot_c_${label}_${UUID.randomUUID()}_"
        val files = File(base.filesDir, prefix).apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences(prefix + name, mode)
            override fun getFilesDir(): File = files
            override fun getDatabasePath(name: String): File = File(files, name)
            override fun checkSelfPermission(permission: String): Int =
                if (permission == Manifest.permission.POST_NOTIFICATIONS) PackageManager.PERMISSION_GRANTED
                else base.checkSelfPermission(permission)
        }
    }

    private class MemoryStateBackend : StateBackend {
        @Volatile private var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
    }

    private class MemoryBarrierStorage : RevocationBarrierStorage {
        @Volatile private var state = RevocationBarrierState()
        override fun readState(): RevocationBarrierState = state
        override fun writeState(state: RevocationBarrierState): Boolean {
            this.state = state
            return true
        }
    }

    private class MemoryDiagnosticsStore : PersistenceDiagnosticsStore {
        override fun read(): String? = null
        override fun write(value: String): Boolean = true
    }
}
