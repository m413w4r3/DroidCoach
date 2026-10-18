package dev.atlas.coach.rules

import dev.atlas.coach.focus.FakeClock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class RuleEvaluatorTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun fourthOpenWithinTwentyFiveMinutesProducesExactlyOneIntervention() {
        val evaluator = RuleEvaluator(utc)
        val policy = LocalPolicy()
        val events = listOf(0L, 5L, 10L, 15L, 16L).mapIndexed { index, minute ->
            event("open-$index", "video.app", minute * MINUTE)
        }

        val results = events.map { evaluator.evaluate(it, policy) }
        assertEquals(1, results.count { it != null })
        assertEquals("open-3", results[3]!!.eventId)
        assertNull(results[4])
    }

    @Test
    fun threeOpensDoNotTrigger() {
        val evaluator = RuleEvaluator(utc)
        repeat(3) { index ->
            assertNull(evaluator.evaluate(event("three-$index", "video.app", index * MINUTE), LocalPolicy()))
        }
    }

    @Test
    fun duplicateEventIdsAreIgnored() {
        val evaluator = RuleEvaluator(utc)
        val policy = LocalPolicy()
        val duplicate = event("same", "video.app", 0L)
        evaluator.evaluate(duplicate, policy)
        assertNull(evaluator.evaluate(duplicate, policy))
        evaluator.evaluate(event("other-1", "video.app", MINUTE), policy)
        evaluator.evaluate(event("other-2", "video.app", 2 * MINUTE), policy)
        assertNotNull(evaluator.evaluate(event("other-3", "video.app", 3 * MINUTE), policy))
    }

    @Test
    fun cooldownSuppressesEventsAndRearmsAfterFifteenMinutes() {
        val evaluator = RuleEvaluator(utc)
        val policy = LocalPolicy()
        repeat(4) { index ->
            val intervention = evaluator.evaluate(event("first-$index", "video.app", index * MINUTE), policy)
            if (index == 3) assertNotNull(intervention) else assertNull(intervention)
        }

        repeat(4) { index ->
            assertNull(evaluator.evaluate(event("cooldown-$index", "video.app", (4 + index) * MINUTE), policy))
        }
        repeat(4) { index ->
            val intervention = evaluator.evaluate(event("rearm-$index", "video.app", (19 + index) * MINUTE), policy)
            if (index == 3) assertNotNull(intervention) else assertNull(intervention)
        }
    }

    @Test
    fun eventsOlderThanWindowArePurged() {
        val evaluator = RuleEvaluator(utc)
        val policy = LocalPolicy()
        evaluator.evaluate(event("old-1", "video.app", 0L), policy)
        evaluator.evaluate(event("old-2", "video.app", MINUTE), policy)
        evaluator.evaluate(event("old-3", "video.app", 2 * MINUTE), policy)
        assertNull(evaluator.evaluate(event("after-window", "video.app", 28 * MINUTE), policy))
        assertEquals(
            1,
            evaluator.snapshot().recentEventsByRuleAndPackage["app-open-frequency\u0000video.app"]!!.size,
        )
    }

    @Test
    fun processedIdsExpireWithTheRuleWindow() {
        val evaluator = RuleEvaluator(utc)
        val policy = LocalPolicy()
        evaluator.evaluate(event("old-id", "video.app", 0L), policy)
        evaluator.evaluate(event("new-id", "video.app", 26 * MINUTE), policy)

        assertEquals(setOf("new-id"), evaluator.snapshot().processedEventIds)
    }

    @Test
    fun interventionHistoryIsBoundedToTwoHundredEntries() {
        val evaluator = RuleEvaluator(utc)
        val policy = LocalPolicy()
        repeat(201) { block ->
            val base = block * 20L * MINUTE
            repeat(4) { index ->
                evaluator.evaluate(event("$block-$index", "video.app", base + index * MINUTE), policy)
            }
        }

        assertEquals(200, evaluator.snapshot().interventions.size)
        assertEquals("intervention:1-3", evaluator.snapshot().interventions.first().interventionId)
    }

    @Test
    fun oldPackageCountersAreRemovedFromRecentRuleState() {
        val evaluator = RuleEvaluator(utc)
        val policy = LocalPolicy()
        repeat(31) { minute ->
            evaluator.evaluate(event("package-$minute", "app-$minute", minute * MINUTE), policy)
        }

        assertEquals(26, evaluator.snapshot().recentEventsByRuleAndPackage.size)
    }

    @Test
    fun quietHoursCoverDaytimeBoundariesAndIntervalsAcrossMidnight() {
        val quietPolicy = LocalPolicy(
            openThreshold = 1,
            quietHours = QuietHours(LocalTime.of(22, 0), LocalTime.of(6, 0)),
        )
        val evaluator = RuleEvaluator(utc)
        assertNull(evaluator.evaluate(event("night-1", "video.app", utcMillis(23, 30)), quietPolicy))
        assertNull(evaluator.evaluate(event("night-2", "video.app", utcMillis(3, 30, dayOffset = 1)), quietPolicy))
        assertNotNull(evaluator.evaluate(event("morning", "video.app", utcMillis(6, 0, dayOffset = 1)), quietPolicy))

        val daytimeQuiet = LocalPolicy(
            openThreshold = 1,
            quietHours = QuietHours(LocalTime.of(13, 0), LocalTime.of(14, 0)),
        )
        assertNull(RuleEvaluator(utc).evaluate(event("lunch", "video.app", utcMillis(13, 0)), daytimeQuiet))

        val parisNight = Instant.parse("2026-01-15T21:30:00Z").toEpochMilli()
        assertNull(
            RuleEvaluator(ZoneId.of("Europe/Paris")).evaluate(
                event("paris-night", "video.app", parisNight),
                quietPolicy,
            ),
        )
    }

    @Test
    fun emergencyPauseLastsThirtyMinutes() {
        val now = utcMillis(12, 0)
        val policy = LocalPolicy(openThreshold = 1).withEmergencyPause(now)
        assertEquals(now + 30 * MINUTE, policy.pauseUntil)
        val evaluator = RuleEvaluator(utc)
        assertNull(evaluator.evaluate(event("paused", "video.app", now + 29 * MINUTE), policy))
        assertNotNull(evaluator.evaluate(event("resumed", "video.app", now + 30 * MINUTE), policy))
    }

    @Test
    fun vitalPackagesAreNeverIntervenedOn() {
        val policy = LocalPolicy(openThreshold = 1, vitalPackageNames = setOf("phone.app"))
        assertNull(RuleEvaluator(utc).evaluate(event("call", "phone.app", 0L), policy))
    }

    @Test
    fun evaluatorStateCanBeSnapshottedAndRestored() {
        val first = RuleEvaluator(utc)
        val policy = LocalPolicy()
        repeat(3) { index -> first.evaluate(event("restore-$index", "video.app", index * MINUTE), policy) }
        val state = first.snapshot()
        val restored = RuleEvaluator(utc, state)
        assertNotNull(restored.evaluate(event("restore-3", "video.app", 3 * MINUTE), policy))
        assertNull(restored.evaluate(event("restore-3", "video.app", 3 * MINUTE), policy))
    }

    @Test
    fun simulatorCreatesNSpacedSyntheticEventsFromInjectedClock() {
        val clock = FakeClock(startUtcMillis = utcMillis(10, 0))
        var nextId = 0
        val simulator = AppEventSimulator(clock, eventIdFactory = { "sim-${nextId++}" })
        val events = simulator.generate("demo.app", count = 4, intervalSeconds = 60)
        assertEquals(4, events.size)
        assertEquals("sim-0", events.first().eventId)
        assertEquals(60_000L, events[1].timestampUtc - events[0].timestampUtc)
        assertEquals("demo.app", events.last().packageName)

        val evaluator = RuleEvaluator(utc)
        assertEquals(1, events.count { evaluator.evaluate(it, LocalPolicy()) != null })
    }

    @Test
    fun ruleCooldownIsIndependentPerAppAndRule() {
        val evaluator = RuleEvaluator(utc)
        val policy = LocalPolicy(openThreshold = 1)
        assertNotNull(evaluator.evaluate(event("app-a", "a.app", 0L), policy))
        assertNotNull(evaluator.evaluate(event("app-b", "b.app", 0L), policy))
        assertNotNull(evaluator.evaluate(event("rule-b", "a.app", 0L), policy.copy(ruleId = "another-rule")))
    }

    private fun event(eventId: String, packageName: String, timestamp: Long) =
        AppOpenEvent(eventId, packageName, timestamp)

    private fun utcMillis(hour: Int, minute: Int, dayOffset: Int = 0): Long =
        (dayOffset * 24L * 60L + hour * 60L + minute) * MINUTE

    companion object {
        private const val MINUTE = 60_000L
    }
}
