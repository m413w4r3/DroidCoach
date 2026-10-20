package dev.atlas.coach.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.data.PersistenceDiagnosticsStore
import dev.atlas.coach.data.RevocationBarrierState
import dev.atlas.coach.data.RevocationBarrierStorage
import dev.atlas.coach.data.StateBackend
import dev.atlas.coach.focus.Clock
import dev.atlas.coach.goals.GoalMetric
import dev.atlas.coach.sync.agent.context.AgentContextCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentPersistenceFailureInstrumentationTest {
    @Test
    fun failedGoalCreateUpdateAndStatusKeepDiskCacheAndRecreatedControllerConsistent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = isolatedContext("goal")
        val backend = MemoryStateBackend()
        val barrier = MemoryBarrierStorage()
        lateinit var controller: AtlasController
        instrumentation.runOnMainSync {
            controller = makeController(context, backend, barrier)
            controller.createGoal(draft("Objectif conservé"))
        }
        assertEquals("Objectif conservé", controller.coachGoals.single().record.title)

        backend.failWrites = true
        instrumentation.runOnMainSync {
            controller.updateGoal(controller.coachGoals.single().record.id, draft("Titre non écrit"))
        }
        assertEquals("Objectif conservé", controller.coachGoals.single().record.title)
        assertNotEquals("Modifications de l'objectif enregistrées.", controller.latestMessage)

        backend.failWrites = true
        instrumentation.runOnMainSync {
            controller.changeGoalStatus(controller.coachGoals.single().record.id, dev.atlas.coach.goals.GoalStatus.ABANDONED)
        }
        assertEquals(dev.atlas.coach.goals.GoalStatus.ACTIVE, controller.coachGoals.single().record.status)
        assertNotEquals("Statut de l'objectif mis à jour.", controller.latestMessage)

        backend.failWrites = true
        instrumentation.runOnMainSync { controller.createGoal(draft("Objectif non écrit")) }
        assertEquals(1, controller.coachGoals.size)
        assertNotEquals("Objectif enregistré.", controller.latestMessage)

        backend.failWrites = false
        lateinit var recreated: AtlasController
        instrumentation.runOnMainSync { recreated = makeController(context, backend, barrier) }
        assertEquals(listOf("Objectif conservé"), recreated.coachGoals.map { it.record.title })
        assertEquals(dev.atlas.coach.goals.GoalStatus.ACTIVE, recreated.coachGoals.single().record.status)
    }

    @Test
    fun failedRevocationsRemainBlockedAfterControllerRecreation() {
        listOf("mandate", "disable", "category", "location").forEach { case ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = isolatedContext("revoke-$case")
            val backend = MemoryStateBackend()
            val barrier = MemoryBarrierStorage()
            lateinit var controller: AtlasController
            instrumentation.runOnMainSync {
                controller = makeController(context, backend, barrier)
                controller.enableAgent()
                when (case) {
                    "category" -> controller.setAgentSharedCategory(AgentContextCategory.FOCUS.wireValue, true)
                    "location" -> {
                        controller.setAgentSharedCategory(AgentContextCategory.LOCATION_SUMMARY.wireValue, true)
                        controller.setLocationSummaryConsent(true)
                    }
                }
            }
            assertTrue(controller.isAgentEnabled)

            backend.failWrites = true
            instrumentation.runOnMainSync {
                when (case) {
                    "mandate" -> controller.revokeAgentMandate()
                    "disable" -> controller.disableAgent()
                    "category" -> controller.setAgentSharedCategory(AgentContextCategory.FOCUS.wireValue, false)
                    "location" -> controller.setLocationSummaryConsent(false)
                }
            }
            val agentBlockedGlobally = case == "mandate" || case == "disable"
            assertEquals(agentBlockedGlobally, !controller.isAgentEnabled)
            assertTrue(barrier.blocked)
            val failureMessage = controller.latestMessage.orEmpty()
            assertTrue(
                failureMessage.contains("échec") || failureMessage.contains("échoué") ||
                    failureMessage.contains("pas pu être enregistré") || failureMessage.contains("n'est pas confirm"),
            )

            backend.failWrites = false
            lateinit var recreated: AtlasController
            instrumentation.runOnMainSync { recreated = makeController(context, backend, barrier) }
            assertEquals("agent access after $case revocation", !agentBlockedGlobally, recreated.isAgentEnabled)
            assertTrue("context categories after $case revocation", recreated.agentContextPreview.authorizedCategories.isEmpty())
        }
    }

    private fun makeController(context: Context, backend: StateBackend, barrier: RevocationBarrierStorage) = AtlasController(
        context = context,
        clock = FixedClock,
        stateBackendOverride = backend,
        revocationBarrierStorageOverride = barrier,
        persistenceDiagnosticsStoreOverride = MemoryDiagnosticsStore(),
    )

    private fun draft(title: String) = GoalPanelDraft(
        title = title,
        metric = GoalMetric.MANUAL,
        targetValue = 1L,
        description = "",
        nextAction = "",
    )

    private fun isolatedContext(label: String): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "lot_a_${label}_${UUID.randomUUID()}_"
        val files = File(base.filesDir, prefix).apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences(prefix + name, mode)
            override fun getFilesDir(): File = files
        }
    }

    private class MemoryStateBackend : StateBackend {
        @Volatile var value: String? = null
        @Volatile var failWrites = false
        override fun read(): String? = value
        override fun write(value: String) {
            check(!failWrites) { "injected state write failure" }
            this.value = value
        }
    }

    private class MemoryBarrierStorage : RevocationBarrierStorage {
        @Volatile var state = RevocationBarrierState()
        val blocked: Boolean get() = state.agentBlocked || state.blockedCategories.isNotEmpty()
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

    private object FixedClock : Clock {
        override fun nowMillis(): Long = 1_800_000_000_000L
        override fun monotonicMillis(): Long = 1_800_000_000_000L
    }
}
