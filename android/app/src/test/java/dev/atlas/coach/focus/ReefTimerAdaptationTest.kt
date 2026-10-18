package dev.atlas.coach.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReefTimerAdaptationTest {
    @Test
    fun reefDurationsConvertFromMillisecondsToFocusPlanSeconds() {
        val plan = ReefPomodoroConfig(25, 5, 15, 4).toPomodoroConfig().toFocusPlan("custom")
        assertEquals("custom", plan.presetId)
        assertEquals(25 * 60L, plan.workSeconds)
        assertEquals(5 * 60L, plan.shortBreakSeconds)
        assertEquals(15 * 60L, plan.longBreakSeconds)
        assertEquals(4, plan.longBreakEveryCycles)
    }

    @Test
    fun timerStateProjectionKeepsAtlasPauseAndPhaseSemantics() {
        val engine = FocusEngine(FakeClock(), idFactory = { "s" })
        val session = engine.start(ReefPomodoroConfig(1, 1, 3, 2).toPomodoroConfig().toFocusPlan())
        val started = session.toReefTimerState()
        assertTrue(started.isRunning)
        assertEquals(PomodoroPhase.FOCUS, started.pomodoroPhase)
        assertEquals(60_000L, started.timeRemaining)
        assertEquals(2, started.totalCycles)

        engine.pause(session.id)
        val paused = engine.currentSession()!!.toReefTimerState()
        assertFalse(paused.isRunning)
        assertTrue(paused.isPaused)
    }

    @Test
    fun focusStatisticsCountElapsedWorkAndCompletedCycles() {
        val clock = FakeClock()
        val engine = FocusEngine(clock, idFactory = { "stats" })
        val session = engine.start(durationSeconds = 10)
        clock.advanceMillis(2_500)
        val partial = ReefFocusStats.from(engine.currentSession()!!)
        assertEquals(2_500L, partial.focusTimeMillis)
        assertEquals(0, partial.completedCycles)

        clock.advanceMillis(7_500)
        val afterCycle = ReefFocusStats.from(engine.currentSession()!!)
        assertEquals(10_000L, afterCycle.focusTimeMillis)
        assertEquals(1, afterCycle.completedCycles)
        assertEquals(FocusPhase.SHORT_BREAK, engine.currentSession()!!.phase)
    }
}
