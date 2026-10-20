package dev.atlas.coach.app

import androidx.compose.runtime.mutableStateOf
import dev.atlas.coach.data.LocalStateStore
import dev.atlas.coach.data.StateBackend
import dev.atlas.coach.data.StateReadResult
import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.goals.GoalMetric
import dev.atlas.coach.habits.Habit
import dev.atlas.coach.habits.HabitCheckIn
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.coach.AgentContextCoordinator
import dev.atlas.coach.sync.agent.context.AgentContextDomainViews
import dev.atlas.coach.sync.agent.context.LastContextDisclosureAudit
import dev.atlas.coach.sync.agent.context.LastContextDisclosureStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class AtlasControllerInitialStateRecoveryTest {
    @Test
    fun unparseableDocumentIsQuarantinedRawThenGoalPersistsAcrossRecreation() {
        val backend = MemoryStateBackend("{pas du json")
        val store = LocalStateStore(backend)
        val initial = controllerForRead(store).readInitialStateForTest()
        val corrupt = initial.readResult as StateReadResult.Corrupt

        assertEquals("{pas du json", corrupt.rawJson)
        assertTrue(store.quarantine(corrupt.rawJson, corrupt.reason))
        assertEquals("{pas du json", backend.quarantinedValue)

        // The controller replaces its recovered empty state after quarantine succeeds.
        store.write(emptyMap())
        val goals = goalsCoordinator(store)
        val created = goals.create(GoalPanelDraft(
            title = "Objectif après récupération",
            metric = GoalMetric.MANUAL,
            targetValue = 1L,
            description = "",
            nextAction = "",
        ))

        val recreated = goalsCoordinator(store)
        assertEquals(created, recreated.repository.get(created.id))
        assertEquals("Objectif après récupération", recreated.repository.all().single().title)
    }

    @Test
    fun unknownSchemaQuarantinesSanitizedCopyAndDoesNotRestoreSharedPayload() {
        val legacyPayload = JSONObject()
            .put("consented_categories", JSONArray(listOf("focus")))
            .put("categories", JSONObject().put("focus", JSONArray(listOf("private legacy value"))))
            .toString()
        val raw = JSONObject()
            .put("schemaVersion", 99)
            .put("lastAgentSharedContext", legacyPayload)
            .put("lastAgentSharedContextAt", SENT_AT)
            .toString()
        val backend = MemoryStateBackend(raw)
        val store = LocalStateStore(backend)
        val initial = controllerForRead(store).readInitialStateForTest()
        val corrupt = initial.readResult as StateReadResult.Corrupt
        val quarantined = requireNotNull(corrupt.rawJson)

        assertTrue(store.quarantine(quarantined, corrupt.reason))
        val quarantinedJson = JSONObject(requireNotNull(backend.quarantinedValue))
        assertFalse(quarantinedJson.has("lastAgentSharedContext"))
        assertFalse(backend.quarantinedValue.orEmpty().contains("private legacy value"))
        assertEquals(null, initial.legacyContextAudit)
        assertFalse(initial.contextAuditMigrationPending)
        assertFalse(quarantinedJson.has("lastAgentSharedContextAt"))

        val unreadableController = controllerForRead(store).apply {
            setField(
                "lastSharedAgentContextJsonSnapshot" + "$" + "delegate",
                mutableStateOf<String?>(legacyPayload),
            )
            setField("agentContextCoordinator", AgentContextCoordinator(
                initialConsentAtUtcMillis = emptyMap(),
                legacyConsentedCategories = emptySet(),
                mandateProvider = { AgentMandate.default() },
                domainsProvider = { AgentContextDomainViews() },
                nowUtcMillis = { SENT_AT },
                persistConsentDates = {},
            ))
        }
        assertEquals(null, unreadableController.lastSharedAgentContextJson)
    }

    @Test
    fun legacyPayloadWithActiveConsentMigratesToMetadataAndStaysValueFreeAfterRecreation() {
        val categories = listOf("focus", "goals")
        val payload = JSONObject()
            .put("consented_categories", JSONArray(categories))
            .put("categories", JSONObject()
                .put("focus", JSONArray(listOf("private focus value")))
                .put("goals", JSONArray(listOf("private goal value"))))
            .toString()
        val mandate = AgentMandate.default().copy(sharedDataCategories = categories.toSet())
        val backend = MemoryStateBackend()
        val store = LocalStateStore(backend)
        store.write(mapOf(
            "agentMandate" to mandate.toJson().toString(),
            "agentContextConsentAt" to categories.associateWith { SENT_AT - 1_000L },
            "lastAgentSharedContext" to payload,
            "lastAgentSharedContextAt" to SENT_AT,
        ))

        val first = controllerForRead(store).readInitialStateForTest()
        val loaded = first.readResult as StateReadResult.Loaded
        val audit = requireNotNull(first.legacyContextAudit)
        assertEquals(SENT_AT, audit.sentAtUtcMillis)
        assertEquals(categories, audit.categoryNames)
        assertEquals(LastContextDisclosureStatus.ATTEMPTED, audit.status)
        assertTrue(first.contextAuditMigrationPending)
        assertFalse(loaded.state.containsKey("lastAgentSharedContext"))
        assertFalse(loaded.rawJson.contains("private focus value"))
        assertFalse(loaded.rawJson.contains("private goal value"))

        store.write(loaded.state + (LAST_AUDIT_KEY to audit.toMap()))
        val recreated = controllerForRead(store).readInitialStateForTest()
        val restored = recreated.readResult as StateReadResult.Loaded
        val restoredAudit = LastContextDisclosureAudit.fromMap(
            restored.state[LAST_AUDIT_KEY].asStringMap(),
        )

        assertEquals(audit, restoredAudit)
        assertFalse(restored.state.containsKey("lastAgentSharedContext"))
        assertFalse(restored.rawJson.contains("private focus value"))
        assertFalse(restored.rawJson.contains("private goal value"))
    }

    private fun goalsCoordinator(store: LocalStateStore) = GoalPanelCoordinator(
        readSubdocument = {
            val root = (store.readDocument() as StateReadResult.Loaded).state
            root["goals"].asStringMap()
        },
        persistSubdocument = { goals ->
            val root = (store.readDocument() as StateReadResult.Loaded).state
            store.write(root + ("goals" to goals))
        },
        nowUtcMillis = { SENT_AT },
        focusSessions = { emptyList<FocusSession>() },
        habits = { emptyList<Habit>() },
        habitCheckIns = { emptyList<HabitCheckIn>() },
        newId = { "recovery-goal" },
    )

    private fun controllerForRead(store: LocalStateStore): AtlasController {
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null) as Unsafe
        val controller = unsafe.allocateInstance(AtlasController::class.java) as AtlasController
        controller.setField("store", store)
        return controller
    }

    private fun AtlasController.readInitialStateForTest(): InitialStateView {
        val method = AtlasController::class.java.getDeclaredMethod("readInitialState").apply { isAccessible = true }
        val result = requireNotNull(method.invoke(this))
        return InitialStateView(
            readResult = result.readField("readResult") as StateReadResult,
            legacyContextAudit = result.readField("legacyContextAudit") as? LastContextDisclosureAudit,
            contextAuditMigrationPending = result.readField("contextAuditMigrationPending") as Boolean,
        )
    }

    private fun Any.readField(name: String): Any? = javaClass.getDeclaredField(name).let { field ->
        field.isAccessible = true
        field.get(this)
    }

    private fun Any.setField(name: String, value: Any) {
        javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(this@setField, value)
        }
    }

    private data class InitialStateView(
        val readResult: StateReadResult,
        val legacyContextAudit: LastContextDisclosureAudit?,
        val contextAuditMigrationPending: Boolean,
    )

    private class MemoryStateBackend(initial: String? = null) : StateBackend {
        var value: String? = initial
            private set
        var quarantinedValue: String? = null
            private set

        override fun read(): String? = value

        override fun write(value: String) {
            this.value = value
        }

        override fun quarantine(rawValue: String, reason: String): Boolean {
            quarantinedValue = rawValue
            return true
        }
    }

    private fun Any?.asStringMap(): Map<String, Any?>? = (this as? Map<*, *>)
        ?.entries
        ?.associate { (key, value) ->
            require(key is String) { "Expected string map keys" }
            key to value
        }

    private companion object {
        const val SENT_AT = 1_800_000_000_000L
        const val LAST_AUDIT_KEY = "lastAgentContextDisclosureAudit"
    }
}
