package dev.atlas.coach.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusEngineTest {
    @Test
    fun preparedStartDoesNotMutateEngineAndPublishesUsingTheCapturedMonotonicOrigin() {
        val clock = FakeClock(startUtcMillis = 1_234L, startMonotonicMillis = 5_678L)
        val engine = FocusEngine(clock, idFactory = { error("prepareStart must not use the ID factory") })
        val plan = FocusPlan("prepared", workSeconds = 10, shortBreakSeconds = 2)

        val prepared = engine.prepareStart(
            plan = plan,
            directiveId = "directive-prepared",
            sessionId = "session-prepared",
            startedAtUtcMillis = clock.nowMillis(),
            startedAtMonotonicMillis = clock.monotonicMillis(),
        )

        assertNull(engine.snapshot())
        assertEquals(FocusStatus.IDLE, engine.state)
        assertEquals("session-prepared", prepared.session.id)
        assertEquals(10_000L, prepared.session.remainingMillis)
        assertEquals(prepared.session, engine.publishPreparedStart(prepared))
        assertEquals(FocusStatus.RUNNING, engine.state)

        clock.advanceMonotonicMillis(1_250L)
        assertEquals(8_750L, engine.snapshot()!!.remainingMillis)
    }

    @Test
    fun pomodoro25_5CompletesWorkAndShortBreak() {
        val clock = FakeClock(startUtcMillis = 1_000_000L)
        val engine = FocusEngine(clock, idFactory = { "focus-1" })
        val started = engine.start(FocusPreset.POMODORO_25_5, directiveId = "directive-1")

        assertEquals(25 * 60L, started.remainingSeconds)
        assertEquals(FocusPhase.WORK, started.phase)
        clock.advanceMillis(25 * 60_000L)
        val shortBreak = engine.currentSession()!!
        assertEquals(FocusPhase.SHORT_BREAK, shortBreak.phase)
        assertEquals(5 * 60L, shortBreak.remainingSeconds)
        assertEquals(1, shortBreak.completedWorkCycles)

        clock.advanceMillis(5 * 60_000L)
        val nextWork = engine.currentSession()!!
        assertEquals(FocusPhase.WORK, nextWork.phase)
        assertEquals(25 * 60L, nextWork.remainingSeconds)
        assertEquals("directive-1", nextWork.directiveId)
    }

    @Test
    fun tenMinuteVariantUsesTwoMinuteBreak() {
        val clock = FakeClock()
        val engine = FocusEngine(clock)
        engine.start(FocusPreset.VARIANT_10_2)

        clock.advanceMillis(10 * 60_000L)
        val shortBreak = engine.currentSession()!!
        assertEquals(FocusPhase.SHORT_BREAK, shortBreak.phase)
        assertEquals(2 * 60L, shortBreak.remainingSeconds)

        clock.advanceMillis(2 * 60_000L)
        assertEquals(FocusPhase.WORK, engine.currentSession()!!.phase)
    }

    @Test
    fun longBreakFollowsConfiguredCycleAndOnlyRunsBetweenFiniteWorkCycles() {
        val clock = FakeClock()
        val engine = FocusEngine(clock)
        engine.start(FocusPlan("test-plan", workSeconds = 10, shortBreakSeconds = 2, totalWorkCycles = 5))

        repeat(3) {
            clock.advanceMillis(10_000)
            assertEquals(FocusPhase.SHORT_BREAK, engine.currentSession()!!.phase)
            clock.advanceMillis(2_000)
            assertEquals(FocusPhase.WORK, engine.currentSession()!!.phase)
        }
        clock.advanceMillis(10_000)
        val longBreak = engine.currentSession()!!
        assertEquals(FocusPhase.LONG_BREAK, longBreak.phase)
        assertEquals(15 * 60L, longBreak.remainingSeconds)
        assertEquals(4, longBreak.completedWorkCycles)
        clock.advanceMillis(15 * 60_000L)
        assertEquals(FocusPhase.WORK, engine.currentSession()!!.phase)
        clock.advanceMillis(10_000L)
        assertEquals(FocusStatus.FINISHED, engine.currentSession()!!.status)
        assertEquals(5, engine.currentSession()!!.completedWorkCycles)
    }

    @Test
    fun pauseResumeFinishAndCancelAreIdempotent() {
        val clock = FakeClock()
        val engine = FocusEngine(clock, idFactory = { "session" })
        val started = engine.start(durationSeconds = 10)
        val duplicateStart = engine.start(durationSeconds = 99)
        assertEquals(started.id, duplicateStart.id)
        assertEquals(10L, duplicateStart.remainingSeconds)

        clock.advanceMillis(4_000)
        val paused = engine.pause(started.id)!!
        assertEquals(FocusStatus.PAUSED, paused.status)
        assertEquals(6L, paused.remainingSeconds)
        assertEquals(paused, engine.pause(started.id))
        clock.advanceMillis(50_000)
        assertEquals(paused, engine.currentSession())

        val resumed = engine.resume(started.id)!!
        assertEquals(FocusStatus.RUNNING, resumed.status)
        assertEquals(resumed, engine.resume(started.id))
        clock.advanceMillis(6_000)
        assertEquals(FocusPhase.SHORT_BREAK, engine.currentSession()!!.phase)
        val finished = engine.finish(started.id)!!
        assertEquals(FocusStatus.FINISHED, finished.status)
        assertEquals(finished, engine.finish(started.id))
        assertEquals(finished, engine.cancel(started.id))

        val cancelEngine = FocusEngine(FakeClock(), idFactory = { "cancelled" })
        val toCancel = cancelEngine.start(durationSeconds = 20)
        val cancelled = cancelEngine.cancel(toCancel.id)!!
        assertEquals(FocusStatus.CANCELLED, cancelled.status)
        assertEquals(cancelled, cancelEngine.cancel(toCancel.id))
        assertEquals(cancelled, cancelEngine.finish(toCancel.id))
        assertTrue(cancelEngine.pause("missing") == null)
    }

    @Test
    fun snapshotRestoresRunningTimerUsingBoundedWallClockElapsedTime() {
        val firstClock = FakeClock(startUtcMillis = 10_000L)
        val firstEngine = FocusEngine(firstClock, idFactory = { "persisted" })
        firstEngine.start(durationSeconds = 20)
        firstClock.advanceMillis(3_000)
        val savedMap = firstEngine.snapshot()!!.toMap()
        val saved = FocusSession.fromMap(savedMap)
        assertEquals(17L, saved.remainingSeconds)

        val restartedClock = FakeClock(startUtcMillis = 18_000L, startMonotonicMillis = 0L)
        val restored = FocusEngine(restartedClock).restore(savedMap)
        assertEquals(12L, restored.remainingSeconds)
        assertEquals(FocusStatus.RUNNING, restored.status)
        assertEquals(12_000L, restored.remainingMillis)

        val rollbackClock = FakeClock(startUtcMillis = saved.updatedAtUtcMillis - 10_000L)
        val rollbackRestored = FocusEngine(rollbackClock).restore(saved)
        assertEquals(17L, rollbackRestored.remainingSeconds)

        val boundedSaved = saved.copy(
            remainingSeconds = 100_000L,
            remainingMillis = 100_000L * 1_000L,
            workSeconds = 100_000L,
            shortBreakSeconds = 100_000L,
            longBreakSeconds = 100_000L,
        )
        val longClock = FakeClock(startUtcMillis = saved.updatedAtUtcMillis + 48L * 60L * 60L * 1_000L)
        val longRestored = FocusEngine(longClock).restore(boundedSaved)
        assertEquals(100_000L - 24L * 60L * 60L, longRestored.remainingSeconds)
    }

    @Test
    fun forceStopRestorationPreservesSubsecondProgressFromWallClock() {
        val clock = FakeClock(startUtcMillis = 100_000L)
        val engine = FocusEngine(clock, idFactory = { "precise-session" })
        engine.start(durationSeconds = 10)
        clock.advanceMillis(1_250L)
        val saved = engine.snapshot()!!
        assertEquals(8_750L, saved.remainingMillis)

        clock.advanceMillis(2_375L)
        val restored = FocusEngine(clock).restore(saved)
        assertEquals(6_375L, restored.remainingMillis)
        assertEquals(7L, restored.remainingSeconds)
    }

    @Test
    fun acceleratedPlanCanUseScaledOrExplicitSecondDurations() {
        val plan = FocusPlan.fromPreset(FocusPreset.POMODORO_25_5).scaled(0.01)
        assertEquals(15L, plan.workSeconds)
        assertEquals(3L, plan.shortBreakSeconds)

        val clock = FakeClock()
        val engine = FocusEngine(clock)
        engine.start(plan)
        clock.advanceMillis(15_000)
        assertEquals(FocusPhase.SHORT_BREAK, engine.currentSession()!!.phase)
        assertEquals(3L, engine.currentSession()!!.remainingSeconds)
    }

    @Test
    fun finiteTwoCyclePlanTransitionsWorkBreakWorkThenFinishesWithoutFinalBreak() {
        val clock = FakeClock()
        val engine = FocusEngine(clock, idFactory = { "two-cycles" })
        engine.start(FocusPlan("two", workSeconds = 2, shortBreakSeconds = 1, totalWorkCycles = 2))

        clock.advanceMillis(2_000L)
        val pause = engine.currentSession()!!
        assertEquals(FocusPhase.SHORT_BREAK, pause.phase)
        assertEquals(1, pause.completedWorkCycles)

        clock.advanceMillis(1_000L)
        assertEquals(FocusPhase.WORK, engine.currentSession()!!.phase)
        clock.advanceMillis(2_000L)
        val finished = engine.currentSession()!!
        assertEquals(FocusStatus.FINISHED, finished.status)
        assertEquals(2, finished.completedWorkCycles)
        assertEquals(0L, finished.remainingMillis)
        clock.advanceMillis(30_000L)
        assertEquals(finished, engine.currentSession())
    }

    @Test
    fun oneCyclePlanFinishesAtWorkBoundaryWithoutStartingABreak() {
        val clock = FakeClock()
        val engine = FocusEngine(clock)
        engine.start(FocusPlan("one", workSeconds = 1, shortBreakSeconds = 1, totalWorkCycles = 1))
        clock.advanceMillis(1_000L)
        val ended = engine.currentSession()!!
        assertEquals(FocusStatus.FINISHED, ended.status)
        assertEquals(FocusPhase.WORK, ended.phase)
        assertEquals(1, ended.completedWorkCycles)
    }

    @Test
    fun continuousPlanKeepsRepeatingAndStillUsesLongBreakCadence() {
        val clock = FakeClock()
        val engine = FocusEngine(clock)
        engine.start(
            FocusPlan(
                "continuous", workSeconds = 1, shortBreakSeconds = 1, longBreakSeconds = 3,
                longBreakEveryCycles = 2, totalWorkCycles = 1, continuous = true,
            ),
        )
        clock.advanceMillis(1_000L)
        assertEquals(FocusPhase.SHORT_BREAK, engine.currentSession()!!.phase)
        clock.advanceMillis(1_000L)
        assertEquals(FocusPhase.WORK, engine.currentSession()!!.phase)
        clock.advanceMillis(1_000L)
        val longBreak = engine.currentSession()!!
        assertEquals(FocusPhase.LONG_BREAK, longBreak.phase)
        assertEquals(FocusStatus.RUNNING, longBreak.status)
        assertEquals(2, longBreak.completedWorkCycles)
    }

    @Test
    fun interruptionAndWallClockChangeDoNotChangeLiveMonotonicProgress() {
        val clock = FakeClock()
        val engine = FocusEngine(clock)
        val session = engine.start(FocusPlan("interrupted", 10, 2, totalWorkCycles = 2))
        clock.advanceWallMillis(60_000L)
        assertEquals(10_000L, engine.currentSession()!!.remainingMillis)

        clock.advanceMonotonicMillis(3_000L)
        val paused = engine.pause(session.id)!!
        assertEquals(7_000L, paused.remainingMillis)
        clock.setMonotonicMillis(1L)
        engine.resume(session.id)
        assertEquals(7_000L, engine.currentSession()!!.remainingMillis)
        clock.advanceMonotonicMillis(6_999L)
        assertEquals(1L, engine.currentSession()!!.remainingMillis)
        clock.advanceMonotonicMillis(1L)
        assertEquals(FocusPhase.SHORT_BREAK, engine.currentSession()!!.phase)
    }

    @Test
    fun restoringAcrossABoundaryUsesWallElapsedAndReplansFromCurrentPhase() {
        val originalClock = FakeClock(startUtcMillis = 10_000L)
        val original = FocusEngine(originalClock).start(
            FocusPlan("restore-boundary", 2, 1, totalWorkCycles = 2),
        )
        val restoredClock = FakeClock(startUtcMillis = 12_500L)
        val engine = FocusEngine(restoredClock)
        val restored = engine.restore(original.toMap())
        assertEquals(FocusPhase.SHORT_BREAK, restored.phase)
        assertEquals(500L, restored.remainingMillis)
        restoredClock.advanceMillis(500L)
        assertEquals(FocusPhase.WORK, engine.currentSession()!!.phase)
    }

    @Test
    fun legacyMapMigratesToContinuousWithoutLosingProgress() {
        val clock = FakeClock(startUtcMillis = 2_000L)
        val original = FocusEngine(clock).start(FocusPlan("before-migration", 30, 5, totalWorkCycles = 7))
        clock.advanceMillis(4_500L)
        val oldMap = FocusEngine(clock).apply { restore(original) }.snapshot()!!.toMap()
            .toMutableMap().apply {
                remove("totalWorkCycles")
                remove("continuous")
            }
        val migrated = FocusSession.fromMap(oldMap)
        assertEquals(25_500L, migrated.remainingMillis)
        assertEquals(Int.MAX_VALUE, migrated.totalWorkCycles)
        assertEquals(true, migrated.continuous)
        assertEquals(migrated, FocusSession.fromMap(migrated.toMap()))
    }

    @Test
    fun phaseScheduleDerivesFiniteBoundariesFromPersistedSnapshot() {
        val clock = FakeClock(startUtcMillis = 5_000L)
        val engine = FocusEngine(clock)
        val session = engine.start(
            FocusPlan("schedule", 2, 1, longBreakSeconds = 3, longBreakEveryCycles = 2, totalWorkCycles = 3),
        )
        val transitions = PhaseSchedule.transitions(session).toList()
        assertEquals(
            listOf(7_000L, 8_000L, 10_000L, 13_000L, 15_000L),
            transitions.map { it.atUtcMillis },
        )
        assertEquals(listOf(FocusPhase.SHORT_BREAK, FocusPhase.WORK, FocusPhase.LONG_BREAK, FocusPhase.WORK, null),
            transitions.map { it.nextPhase })
        val fake = FakePhaseAlarmScheduler()
        fake.schedule(session)
        assertEquals(transitions.first(), fake.scheduledTransitions.single())
        fake.cancel(session.id)
        assertTrue(fake.scheduledTransitions.isEmpty())
        val notifier = FakePhaseNotifier(permissionGranted = false)
        assertEquals(PhaseNotificationResult.PERMISSION_REQUIRED, notifier.notify(transitions.first()))
        assertTrue(notifier.notifications.isEmpty())
        notifier.permissionGranted = true
        assertEquals(PhaseNotificationResult.POSTED, notifier.notify(transitions.last()))
        assertEquals(transitions.last(), notifier.notifications.single())
    }

    @Test
    fun fakeClockAndRepositoryExposeSynchronousSessionLifecycle() {
        val clock = FakeClock(startUtcMillis = 123L)
        val repository = InMemoryFocusRepository(clock, idFactory = { "repo-session" })
        val session = repository.start(durationSeconds = 60, directiveId = "d-1")
        assertEquals(123L, session.createdAtUtcMillis)
        assertEquals("d-1", session.directiveId)
        assertNotNull(repository.pause(session.id))
        assertNotNull(repository.resume(session.id))
        assertNotNull(repository.cancel(session.id))
        assertEquals(FocusStatus.CANCELLED, repository.currentSession()!!.status)
        assertNull(repository.finish("missing"))
    }
}
