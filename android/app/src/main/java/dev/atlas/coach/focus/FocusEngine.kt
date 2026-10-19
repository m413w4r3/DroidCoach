package dev.atlas.coach.focus

import java.util.UUID

/** Pure Kotlin Pomodoro state machine. Call [snapshot] when saving local state. */
class FocusEngine(
    private val clock: Clock,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private var session: FocusSession? = null
    private var phaseRemainingMillis: Long = 0L
    private var phaseStartedMonotonicMillis: Long? = null

    val state: FocusStatus
        get() = session?.let { currentSession()?.status } ?: FocusStatus.IDLE

    fun start(
        preset: FocusPreset = FocusPreset.POMODORO_25_5,
        directiveId: String? = null,
    ): FocusSession = start(FocusPlan.fromPreset(preset), directiveId)

    fun start(
        plan: FocusPlan,
        directiveId: String? = null,
    ): FocusSession {
        val existing = session
        if (existing?.status == FocusStatus.RUNNING || existing?.status == FocusStatus.PAUSED) {
            return currentSession() ?: existing
        }

        val nowUtc = clock.nowMillis()
        phaseRemainingMillis = plan.workSeconds * MILLIS_PER_SECOND
        phaseStartedMonotonicMillis = clock.monotonicMillis()
        return FocusSession(
            id = idFactory(),
            directiveId = directiveId,
            presetId = plan.presetId,
            phase = FocusPhase.WORK,
            status = FocusStatus.RUNNING,
            createdAtUtcMillis = nowUtc,
            updatedAtUtcMillis = nowUtc,
            remainingSeconds = plan.workSeconds,
            completedWorkCycles = 0,
            workSeconds = plan.workSeconds,
            shortBreakSeconds = plan.shortBreakSeconds,
            longBreakSeconds = plan.longBreakSeconds,
            longBreakEveryCycles = plan.longBreakEveryCycles,
            totalWorkCycles = plan.totalWorkCycles,
            continuous = plan.continuous,
            taskTitle = plan.taskTitle,
        ).also { session = it }
    }

    /** Starts a configurable work phase; useful for the local demo and repository contract. */
    fun start(durationSeconds: Long, directiveId: String? = null): FocusSession =
        start(FocusPlan.custom(durationSeconds), directiveId)

    fun currentSession(): FocusSession? {
        synchronizeRunningTime()
        return session
    }

    /** Returns an up-to-date primitive snapshot that can be passed to [FocusSession.toMap]. */
    fun snapshot(): FocusSession? = currentSession()

    fun pause(id: String): FocusSession? {
        synchronizeRunningTime()
        val active = session ?: return null
        if (active.id != id || active.status != FocusStatus.RUNNING) return active.takeIf { it.id == id }
        phaseStartedMonotonicMillis = null
        return active.copy(
            status = FocusStatus.PAUSED,
            remainingSeconds = millisToCeilSeconds(phaseRemainingMillis),
            remainingMillis = phaseRemainingMillis,
            updatedAtUtcMillis = clock.nowMillis(),
        ).also { session = it }
    }

    fun resume(id: String): FocusSession? {
        synchronizeRunningTime()
        val active = session ?: return null
        if (active.id != id || active.status != FocusStatus.PAUSED) return active.takeIf { it.id == id }
        phaseStartedMonotonicMillis = clock.monotonicMillis()
        return active.copy(
            status = FocusStatus.RUNNING,
            remainingSeconds = millisToCeilSeconds(phaseRemainingMillis),
            remainingMillis = phaseRemainingMillis,
            updatedAtUtcMillis = clock.nowMillis(),
        ).also { session = it }
    }

    fun finish(id: String): FocusSession? = terminate(id, FocusStatus.FINISHED)

    fun cancel(id: String): FocusSession? = terminate(id, FocusStatus.CANCELLED)

    /** Restores an exported session. Wall-clock catch-up is capped at 24 hours. */
    fun restore(saved: FocusSession): FocusSession {
        session = saved
        phaseRemainingMillis = saved.remainingMillis
        phaseStartedMonotonicMillis = null
        if (saved.status == FocusStatus.RUNNING) {
            val nowUtc = clock.nowMillis()
            val wallElapsed = if (nowUtc > saved.updatedAtUtcMillis) {
                (nowUtc - saved.updatedAtUtcMillis).coerceAtMost(MAX_RESTORE_ELAPSED_MILLIS)
            } else {
                0L // A backwards wall-clock correction never adds elapsed focus time.
            }
            advanceBy(wallElapsed, clock.monotonicMillis())
            session = session?.copy(updatedAtUtcMillis = nowUtc)
            phaseStartedMonotonicMillis = clock.monotonicMillis()
        }
        return session ?: saved
    }

    fun restore(values: Map<String, Any?>): FocusSession = restore(FocusSession.fromMap(values))

    private fun terminate(id: String, terminalStatus: FocusStatus): FocusSession? {
        synchronizeRunningTime()
        val active = session ?: return null
        if (active.id != id || active.status == FocusStatus.FINISHED || active.status == FocusStatus.CANCELLED) {
            return active.takeIf { it.id == id }
        }
        phaseStartedMonotonicMillis = null
        return active.copy(
            status = terminalStatus,
            remainingSeconds = millisToCeilSeconds(phaseRemainingMillis),
            remainingMillis = phaseRemainingMillis,
            updatedAtUtcMillis = clock.nowMillis(),
        ).also { session = it }
    }

    private fun synchronizeRunningTime() {
        val active = session ?: return
        if (active.status != FocusStatus.RUNNING) return
        val nowMonotonic = clock.monotonicMillis()
        val startedAt = phaseStartedMonotonicMillis ?: nowMonotonic
        val elapsed = (nowMonotonic - startedAt).coerceAtLeast(0L)
        phaseStartedMonotonicMillis = nowMonotonic
        advanceBy(elapsed, nowMonotonic)
        session = session?.copy(
            updatedAtUtcMillis = clock.nowMillis(),
            remainingSeconds = millisToCeilSeconds(phaseRemainingMillis),
            remainingMillis = phaseRemainingMillis,
        )
    }

    private fun advanceBy(elapsedMillis: Long, nowMonotonic: Long) {
        var elapsed = elapsedMillis
        while (elapsed >= phaseRemainingMillis && session?.status == FocusStatus.RUNNING) {
            elapsed -= phaseRemainingMillis
            val active = session ?: return
            val nextPhase: FocusPhase?
            val completedCycles: Int
            if (active.phase == FocusPhase.WORK) {
                completedCycles = active.completedWorkCycles + 1
                nextPhase = FocusTransitions.afterWork(active, completedCycles)
                if (nextPhase == null) {
                    phaseRemainingMillis = 0L
                    phaseStartedMonotonicMillis = null
                    session = active.copy(
                        status = FocusStatus.FINISHED,
                        completedWorkCycles = completedCycles,
                        remainingSeconds = 0L,
                        remainingMillis = 0L,
                        updatedAtUtcMillis = clock.nowMillis(),
                    )
                    return
                }
            } else {
                completedCycles = active.completedWorkCycles
                nextPhase = FocusTransitions.afterBreak()
            }
            phaseRemainingMillis = when (nextPhase) {
                FocusPhase.WORK -> active.workSeconds * MILLIS_PER_SECOND
                FocusPhase.SHORT_BREAK -> active.shortBreakSeconds * MILLIS_PER_SECOND
                FocusPhase.LONG_BREAK -> active.longBreakSeconds * MILLIS_PER_SECOND
            }
            session = active.copy(
                phase = nextPhase,
                completedWorkCycles = completedCycles,
                remainingSeconds = millisToCeilSeconds(phaseRemainingMillis),
                remainingMillis = phaseRemainingMillis,
                updatedAtUtcMillis = clock.nowMillis(),
            )
        }
        if (elapsed < phaseRemainingMillis) phaseRemainingMillis -= elapsed
        phaseStartedMonotonicMillis = nowMonotonic
        session = session?.copy(
            remainingSeconds = millisToCeilSeconds(phaseRemainingMillis),
            remainingMillis = phaseRemainingMillis,
        )
    }

    private fun millisToCeilSeconds(millis: Long): Long =
        if (millis <= 0L) 0L else millis / MILLIS_PER_SECOND + if (millis % MILLIS_PER_SECOND == 0L) 0L else 1L

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L
        private const val MAX_RESTORE_ELAPSED_MILLIS = 24L * 60L * 60L * MILLIS_PER_SECOND
    }
}

/** Shared phase-transition rules used by the engine and the persisted-state alarm schedule. */
internal object FocusTransitions {
    fun afterWork(session: FocusSession, completedCycles: Int): FocusPhase? {
        if (!session.continuous && completedCycles >= session.totalWorkCycles) return null
        return if (completedCycles % session.longBreakEveryCycles == 0) {
            FocusPhase.LONG_BREAK
        } else {
            FocusPhase.SHORT_BREAK
        }
    }

    fun afterBreak(): FocusPhase = FocusPhase.WORK
}
