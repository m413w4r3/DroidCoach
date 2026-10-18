package dev.atlas.coach.data

import dev.atlas.coach.coach.CoachClock
import dev.atlas.coach.coach.Directive
import dev.atlas.coach.coach.DirectiveChoice
import dev.atlas.coach.coach.MockCoachRepository
import dev.atlas.coach.focus.FakeClock
import dev.atlas.coach.habits.HabitClock
import dev.atlas.coach.habits.HabitSchedule
import dev.atlas.coach.habits.InMemoryHabitRepository
import dev.atlas.coach.rules.AppEventSimulator
import dev.atlas.coach.rules.LocalPolicy
import dev.atlas.coach.rules.RuleEvaluator
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class PersistedRepositoriesTest {
    @Test
    fun ruleCountsAndCooldownSurviveJsonRoundTrip() {
        val clock = FakeClock(startUtcMillis = 1_000_000L)
        val evaluator = RuleEvaluator(ZoneId.of("UTC"))
        val policy = LocalPolicy()
        var eventIndex = 0
        val events = AppEventSimulator(clock) { "event-${eventIndex++}" }.generate("demo.app", 4, 60L, clock.nowMillis())
        assertEquals(1, events.count { evaluator.evaluate(it, policy) != null })

        val backend = MemoryBackend()
        val store = LocalStateStore(backend)
        store.write(mapOf("rules" to evaluator.snapshot().toStorageMap(), "policy" to policy.toStorageMap()))
        val restoredMap = store.read()
        val restoredPolicy = localPolicyFromStorage(restoredMap.getValue("policy") as Map<String, Any?>)
        val restored = RuleEvaluator(ZoneId.of("UTC"), ruleEvaluatorStateFromStorage(restoredMap.getValue("rules") as Map<String, Any?>))
        assertNull(restored.evaluate(events.last(), restoredPolicy)) // the last event is a processed duplicate
        var laterIndex = 0
        val duringCooldown = AppEventSimulator(clock) { "later-${laterIndex++}" }.generate("demo.app", 4, 60L, clock.nowMillis() + 1)
        assertEquals(0, duringCooldown.count { restored.evaluate(it, restoredPolicy) != null })
        assertEquals(1, restored.snapshot().interventions.size)
    }

    @Test
    fun habitsAndCoachRestoreSnapshotsAndKeepIdempotency() {
        val now = 1_700_000_000_000L
        val habits = InMemoryHabitRepository(HabitClock { now }, { "habit-id" })
        val habit = habits.add("Lire dix minutes", HabitSchedule.daily())
        val today = java.time.Instant.ofEpochMilli(now).atZone(ZoneId.of("UTC")).toLocalDate()
        habits.checkIn(habit.id, today)
        val restoredHabits = InMemoryHabitRepository(HabitClock { now }, { "new-id" })
        restoredHabits.restoreMaps(habits.exportMaps())
        assertEquals(habit, restoredHabits.list().single())
        assertEquals(1, restoredHabits.checkIns(habit.id).size)
        assertNotNull(restoredHabits.history(habit.id, today, 7))

        var nextId = 0
        val initial = Directive.demo(now, "directive-test")
        val sourceCoach = MockCoachRepository(CoachClock { now }, initial) { "journal-${nextId++}" }
        val firstResult = sourceCoach.respond(initial.id, DirectiveChoice.DISCUSS)
        val savedCoach = sourceCoach.snapshotState()
        val restoredCoach = MockCoachRepository(CoachClock { now }, initial) { "unused" }
        restoredCoach.restoreState(savedCoach)
        assertEquals(firstResult, restoredCoach.respond(initial.id, DirectiveChoice.DISCUSS))
        assertEquals(1, restoredCoach.listJournal().size)
        assertEquals(DirectiveChoice.DISCUSS, restoredCoach.listJournal().single().choice)
    }

    private class MemoryBackend : StateBackend {
        private var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
    }
}
