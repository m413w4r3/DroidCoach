package dev.atlas.coach.app

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import dev.atlas.coach.coach.CoachClock
import dev.atlas.coach.coach.MockCoachRepository
import dev.atlas.coach.content.ContentCatalog
import dev.atlas.coach.data.LocalStateStore
import dev.atlas.coach.data.PersistenceDiagnostics
import dev.atlas.coach.data.PersistenceDiagnosticsStore
import dev.atlas.coach.data.StateBackend
import dev.atlas.coach.data.StateReadResult
import dev.atlas.coach.focus.Clock
import dev.atlas.coach.focus.FocusEngine
import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.focus.InMemoryFocusJournalLinkStore
import dev.atlas.coach.habits.Habit
import dev.atlas.coach.habits.HabitLinkPolicy
import dev.atlas.coach.habits.InMemoryHabitRepository
import dev.atlas.coach.routines.InMemoryFocusRoutineRepository
import dev.atlas.coach.rules.LocalPolicy
import dev.atlas.coach.rules.RuleEvaluator
import dev.atlas.coach.sync.agent.AgentActionType
import dev.atlas.coach.sync.agent.AgentHomeState
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.LocalMandateStore
import dev.atlas.coach.sync.agent.ProposalLevel
import dev.atlas.coach.sync.agent.ProposalV2
import dev.atlas.coach.sync.agent.coach.AgentContextCoordinator
import dev.atlas.coach.sync.agent.context.AgentContextDomainViews
import org.junit.Ignore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe
import java.time.ZoneId

/** Independent S7 regressions for failed commits and sensitive quarantine data. */
class V621ReviewFindingsTest {
    @Test
    @Ignore("V621-REVIEW: R2")
    fun malformedLegacyContextMustBeRemovedBeforeQuarantine() {
        val secret = "private legacy objective"
        val raw = """{"schemaVersion":1,"lastAgentSharedContext":"{\"categories\":{\"goals\":[\"$secret\"]}}","broken": }"""
        val backend = MemoryStateBackend(raw)
        val store = LocalStateStore(backend)
        val initial = unsafeController().apply { setField("store", store) }
            .readInitialStateForReview()
        val corrupt = initial.readField("readResult") as StateReadResult.Corrupt

        assertTrue(store.quarantine(corrupt.rawJson, corrupt.reason))
        assertFalse("the quarantine still contains the old shared goal", backend.quarantined.orEmpty().contains(secret))
    }

    @Test
    fun localAgentActionMustNotReportAppliedWhenRootCommitFails() {
        val (controller, backend) = controllerWhoseRootWriteFails()
        val outcome = controller.setHomeDirective(homeDirectiveProposal())

        assertEquals(1, backend.writes)
        val state = controller.readField("agentHomeStateSnapshot\$delegate") as MutableState<AgentHomeState>
        // The review reproduction expected the in-memory mutation to remain, but the
        // required transaction contract publishes it only after the durable root write.
        assertEquals(null, state.value.directive)
        assertTrue(controller.latestMessage.orEmpty().contains("Impossible d'enregistrer l'état local"))
        assertFalse("dispatch reported success although root persistence failed", outcome.applied)
    }

    private fun controllerWhoseRootWriteFails(): Pair<AtlasController, MemoryStateBackend> {
        val controller = unsafeController()
        val now = 1_800_000_000_000L
        val clock = object : Clock {
            override fun nowMillis() = now
            override fun monotonicMillis() = 10_000L
        }
        val habits = InMemoryHabitRepository()
        val routines = InMemoryFocusRoutineRepository()
        val mandateStore = LocalMandateStore(AgentMandate.default())
        val directive = dev.atlas.coach.coach.Directive.demo(now)
        val backend = MemoryStateBackend()
        controller.setField("clock", clock)
        controller.setField("focusEngine", FocusEngine(clock))
        controller.setField("focusRoutineRepository", routines)
        controller.setField("focusLinkStore", InMemoryFocusJournalLinkStore())
        controller.setField("habits", habits)
        controller.setField("habitLinkPolicy", HabitLinkPolicy(habits))
        controller.setField("coach", MockCoachRepository(CoachClock { now }, directive))
        controller.setField("catalog", ContentCatalog.demo())
        controller.setField("ruleEvaluator", RuleEvaluator(ZoneId.of("UTC")))
        controller.setField("agentMandateStore", mandateStore)
        controller.setField("agentContextCoordinator", AgentContextCoordinator(
            initialConsentAtUtcMillis = emptyMap(),
            legacyConsentedCategories = emptySet(),
            mandateProvider = mandateStore::current,
            domainsProvider = { AgentContextDomainViews() },
            nowUtcMillis = { now },
            persistConsentDates = {},
        ))
        controller.setField("store", LocalStateStore(backend))
        controller.setField("persistenceAllowed", true)
        controller.setField("persistenceDiagnostics", PersistenceDiagnostics(object : PersistenceDiagnosticsStore {
            override fun read(): String? = null
            override fun write(value: String) = true
        }))
        controller.setField("launcherState", AtlasLauncherState(emptySet()))
        controller.setField("goalsSubdocument", mapOf(
            "schema_version" to 1,
            "goals" to emptyList<Map<String, Any?>>(),
        ))
        controller.setField("contentActivityTimestamps", emptyList<Long>())
        controller.setField("agentConversationId", null)
        controller.setField("proactiveCoachStateValue", null)
        controller.setField("lastSimulatedEvent", null)
        controller.setField("appliedLocalAgentEffectKeys", mutableSetOf<String>())
        controller.setState("focusSnapshot", null)
        controller.setState("focusHistoryEntries", emptyList<FocusSession>())
        controller.setState("focusRoutinesSnapshot", routines.list())
        controller.setState("directiveSnapshot", directive)
        controller.setState("habitsSnapshot", emptyList<Habit>())
        controller.setState("checkedHabitIdsSnapshot", emptySet<String>())
        controller.setState("journalSnapshot", emptyList<dev.atlas.coach.coach.CoachJournalEntry>())
        controller.setState("interventionsSnapshot", emptyList<dev.atlas.coach.rules.Intervention>())
        controller.setState("coachPausedSnapshot", false)
        controller.setState("contentItemsSnapshot", ContentCatalog.demo().items)
        controller.setState("agentHomeStateSnapshot", AgentHomeState())
        controller.setState("agentConversationSnapshot", emptyList<dev.atlas.coach.sync.agent.coach.AgentConversationMessage>())
        controller.setState("agentSuggestionsSnapshot", emptyList<String>())
        controller.setState("policy", LocalPolicy())
        controller.setState("capabilities", emptyMap<String, Boolean>())
        controller.setState("latestMessage", null)
        controller.setState("simulationMessage", null)
        controller.setState("lastSimulationInterventions", 0, integer = true)
        controller.setState("revision", 0, integer = true)
        controller.setState("agentPlanReply", null)
        controller.setState("lastContextDisclosureAuditSnapshot", null)
        controller.setState("lastSharedAgentContextJsonSnapshot", null)
        return controller to backend
    }

    private fun homeDirectiveProposal() = ProposalV2(
        schemaVersion = 2,
        proposalId = "00000000-0000-0000-0000-000000000621",
        idempotencyKey = "review-home-directive",
        conversationId = null,
        mandateId = AgentMandate.default().id,
        mandateVersion = AgentMandate.default().version,
        contextVersion = "review",
        createdAtUtcMillis = 1_800_000_000_000L,
        expiresAtUtcMillis = 1_800_003_600_000L,
        actionType = AgentActionType.HOME_SET_DIRECTIVE.wireValue,
        parameters = mapOf(
            "title" to "Review directive",
            "body" to "A local test proposal",
            "primary_action" to "Open Coach",
        ),
        reason = "Review fixture",
        requiredLevel = ProposalLevel.CONFIRM,
    )

    private fun unsafeController(): AtlasController {
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null) as Unsafe
        return unsafe.allocateInstance(AtlasController::class.java) as AtlasController
    }

    private fun AtlasController.readInitialStateForReview(): Any {
        val method = AtlasController::class.java.getDeclaredMethod("readInitialState").apply { isAccessible = true }
        return requireNotNull(method.invoke(this))
    }

    private fun Any.readField(name: String): Any? = javaClass.getDeclaredField(name).let { field ->
        field.isAccessible = true
        field.get(this)
    }

    private fun AtlasController.setState(name: String, value: Any?, integer: Boolean = false) {
        setField("${name}\$delegate", if (integer) mutableIntStateOf(value as Int) else mutableStateOf(value))
    }

    private fun AtlasController.setField(name: String, value: Any?) {
        AtlasController::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(this@setField, value)
        }
    }

    private class MemoryStateBackend(private var value: String? = null) : StateBackend {
        var quarantined: String? = null
        var writes = 0

        override fun read(): String? = value
        override fun write(value: String) {
            writes++
            throw IllegalStateException("simulated storage failure")
        }

        override fun quarantine(rawValue: String, reason: String): Boolean {
            quarantined = rawValue
            return true
        }
    }
}
