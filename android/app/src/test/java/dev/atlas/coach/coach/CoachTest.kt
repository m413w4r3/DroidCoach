package dev.atlas.coach.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachTest {
    private val now = 1_800_000_000_000L

    @Test
    fun everyChoiceChangesStateReturnsVisibleEffectAndWritesOneJournalEntry() {
        DirectiveChoice.entries.forEachIndexed { index, choice ->
            var sequence = 0
            val repo = MockCoachRepository(
                clock = CoachClock { now },
                newId = { "journal-${index}-${sequence++}" },
            )
            val directive = repo.currentDirective()
            val result = repo.respond(directive.id, choice)

            assertTrue(result.message.isNotBlank())
            assertEquals(choice, result.choice)
            assertEquals(now, result.createdAtUtcMillis)
            assertEquals(1, repo.listJournal().size)
            assertEquals(result.message, repo.listJournal().single().message)
            assertTrue(repo.exportJournalCsv().contains(choice.contractValue))

            when (choice) {
                DirectiveChoice.START -> {
                    assertEquals(DirectiveState.STARTED, repo.currentDirective().state)
                    assertEquals(10, result.requestedDurationMinutes)
                    assertEquals(DirectiveEffect.FOCUS_REQUESTED, result.effect)
                }
                DirectiveChoice.DISCUSS -> {
                    assertEquals(DirectiveState.IN_DISCUSSION, repo.currentDirective().state)
                    assertEquals(DirectiveEffect.DISCUSSION_MESSAGE, result.effect)
                }
                DirectiveChoice.ALTERNATIVE -> {
                    assertEquals(DirectiveState.ALTERNATIVE_PROPOSED, repo.findDirective(directive.id)?.state)
                    assertNotNull(result.alternativeDirective)
                    assertEquals("Lis/avance 5 min", repo.currentDirective().title)
                    assertEquals(DirectiveEffect.ALTERNATIVE_PROPOSED, result.effect)
                }
                DirectiveChoice.POSTPONE -> {
                    assertEquals(DirectiveState.POSTPONED, repo.currentDirective().state)
                    assertEquals(now + 60 * 60 * 1000L, result.nextDueAtUtcMillis)
                    assertEquals(result.nextDueAtUtcMillis, repo.currentDirective().expiresAtUtcMillis)
                    assertEquals(DirectiveEffect.POSTPONED, result.effect)
                }
                DirectiveChoice.PAUSE -> {
                    assertEquals(DirectiveState.PAUSED, repo.currentDirective().state)
                    assertTrue(repo.isPaused())
                    assertEquals(DirectiveEffect.COACH_PAUSED, result.effect)
                }
            }

            assertEquals(result, repo.respond(directive.id, choice))
            assertEquals("Repeated id/choice is idempotent", 1, repo.listJournal().size)
            assertEquals(repo.listJournal(), repo.journal().list())
            assertEquals(repo.listJournal(), CoachJournal.fromMaps(repo.journal().toMaps()).entries)
            assertEquals(directive, Directive.fromMap(directive.toMap()))
            assertEquals(result, DirectiveResult.fromMap(result.toMap()))
            assertEquals(repo.listJournal().single(), CoachJournalEntry.fromMap(repo.listJournal().single().toMap()))
        }
    }

    @Test
    fun choicesAreProposalsAndUnlistedChoicesAreRejected() {
        val repo = MockCoachRepository(CoachClock { now })
        assertFalse(repo.isPaused())
        val onlyStart = repo.currentDirective().copy(choices = listOf(DirectiveChoice.START, DirectiveChoice.DISCUSS,
            DirectiveChoice.POSTPONE, DirectiveChoice.PAUSE))
        val constrained = MockCoachRepository(CoachClock { now }, onlyStart)
        try {
            constrained.respond(onlyStart.id, DirectiveChoice.ALTERNATIVE)
            throw AssertionError("Expected an invalid choice to be rejected")
        } catch (_: IllegalArgumentException) {
            assertTrue(constrained.listJournal().isEmpty())
        }
    }

    @Test
    fun coachJournalAndSnapshotHistoryStayWithinRetentionLimit() {
        var sequence = 0
        val repo = MockCoachRepository(
            clock = CoachClock { now },
            newId = { "journal-${sequence++}" },
        )
        repeat(MockCoachRepository.MAX_JOURNAL_ENTRIES + 1) {
            val directive = repo.currentDirective()
            repo.respond(directive.id, DirectiveChoice.ALTERNATIVE)
        }

        assertEquals(MockCoachRepository.MAX_JOURNAL_ENTRIES, repo.listJournal().size)
        val restored = MockCoachRepository(CoachClock { now }, repo.currentDirective())
        restored.restoreState(repo.snapshotState())
        assertEquals(MockCoachRepository.MAX_JOURNAL_ENTRIES, restored.snapshotState().journalEntries.size)
    }
}
