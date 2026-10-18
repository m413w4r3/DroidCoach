package dev.atlas.coach.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusSessionLinkingTest {
    @Test
    fun habitLinkWaitsForSelectedConditionAndExplicitConfirmation() {
        val clock = FakeClock()
        val engine = FocusEngine(clock, idFactory = { "link-session" })
        val started = engine.start(
            FocusPlan("link-plan", 1, 1, totalWorkCycles = 2),
            directiveId = "mock-directive",
        )
        val store = InMemoryFocusJournalLinkStore()
        val linker = FocusSessionLinker(store)

        assertEquals(
            FocusLinkResult.ConditionNotMet,
            linker.link(
                started, FocusHabitLinkCondition.AllWorkCyclesCompleted, habitId = "habit-1",
                habitConfirmed = true, linkedAtUtcMillis = 1L,
            ),
        )
        clock.advanceMillis(1_000L)
        assertEquals(FocusPhase.SHORT_BREAK, engine.currentSession()!!.phase)
        clock.advanceMillis(1_000L)
        clock.advanceMillis(1_000L)
        val finished = engine.currentSession()!!
        assertEquals(FocusStatus.FINISHED, finished.status)
        assertEquals(
            FocusLinkResult.HabitConfirmationRequired,
            linker.link(
                finished, FocusHabitLinkCondition.AllWorkCyclesCompleted, habitId = "habit-1",
                linkedAtUtcMillis = 4L,
            ),
        )
        assertTrue(store.find(finished.id) == null)
    }

    @Test
    fun linkIsIdempotentBySessionIdAcrossRetriesAndKeepsOptionalRelations() {
        val clock = FakeClock(startUtcMillis = 9_000L)
        val engine = FocusEngine(clock, idFactory = { "one-session-one-journal" })
        val started = engine.start(
            FocusPlan("one-cycle", 1, 1, totalWorkCycles = 1),
            directiveId = "mock-directive-1",
        )
        clock.advanceMillis(1_000L)
        val finished = engine.currentSession()!!
        val store = InMemoryFocusJournalLinkStore()
        val linker = FocusSessionLinker(store)
        val first = linker.link(
            finished,
            FocusHabitLinkCondition.AllWorkCyclesCompleted,
            projectId = "project-optional",
            habitId = "habit-1",
            habitConfirmed = true,
            linkedAtUtcMillis = 10_000L,
        ) as FocusLinkResult.Recorded
        val duplicate = linker.link(
            FocusSession.fromMap(finished.toMap()),
            FocusHabitLinkCondition.AllWorkCyclesCompleted,
            projectId = "different-project",
            habitId = "different-habit",
            habitConfirmed = true,
            linkedAtUtcMillis = 11_000L,
        ) as FocusLinkResult.AlreadyRecorded

        assertEquals(started.id, first.link.sessionId)
        assertEquals(started.id, first.link.journalEntryId)
        assertEquals("mock-directive-1", first.link.mockDirectiveId)
        assertEquals("project-optional", first.link.projectId)
        assertEquals("habit-1", duplicate.link.habitId)
        assertEquals(first.link, duplicate.link)
        assertFalse(store.insertIfAbsent(first.link))
        val restoredStore = InMemoryFocusJournalLinkStore(listOf(FocusJournalLink.fromMap(first.link.toMap())))
        assertFalse(restoredStore.insertIfAbsent(first.link))
        assertEquals(first.link, restoredStore.find(started.id))
        assertEquals(listOf(first.link), restoredStore.all())
    }

    @Test
    fun cancellationNeverSatisfiesFinishedSessionCondition() {
        val engine = FocusEngine(FakeClock())
        val started = engine.start(FocusPlan("cancel", 10, 1, totalWorkCycles = 1))
        val cancelled = engine.cancel(started.id)!!
        val linker = FocusSessionLinker(InMemoryFocusJournalLinkStore())
        val result = linker.link(
            cancelled,
            FocusHabitLinkCondition.SessionFinished,
            habitId = "habit",
            habitConfirmed = true,
            linkedAtUtcMillis = 0L,
        )
        assertEquals(FocusLinkResult.ConditionNotMet, result)
        assertFalse(FocusHabitLinkCondition.AllWorkCyclesCompleted.isSatisfiedBy(cancelled))
    }
}
