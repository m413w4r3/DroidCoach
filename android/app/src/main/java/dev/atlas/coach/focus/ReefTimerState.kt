/*
 * Adapted from Reef@044358fb26bf11aa85dddadf919106ca4053d926:
 * Reef/src/main/java/dev/pranav/reef/timer/TimerState.kt and TimerScreen.kt, MIT.
 * The focus-time/cycle summary also adapts Reef/src/main/java/dev/pranav/reef/data/FocusStats.kt.
 * Reef's global StateFlow manager and strict/blocking modes are deliberately omitted;
 * ATLAS keeps FocusEngine and local snapshot persistence as the source of truth.
 */
package dev.atlas.coach.focus

data class TimerSessionState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val timeRemaining: Long = 0L,
    val focusTimeElapsed: Long = 0L,
    val breakBudget: Long = 0L,
    val pomodoroPhase: PomodoroPhase = PomodoroPhase.FOCUS,
    val currentCycle: Int = 0,
    val totalCycles: Int = 4,
    val isPomodoroMode: Boolean = false,
    val continuous: Boolean = false,
)

enum class PomodoroPhase { FOCUS, SHORT_BREAK, LONG_BREAK, COMPLETE }

/** Reef stores these durations in milliseconds; conversion to FocusPlan is explicit. */
data class PomodoroConfig(
    val focusDuration: Long,
    val shortBreakDuration: Long,
    val longBreakDuration: Long,
    val cyclesBeforeLongBreak: Int,
    val totalWorkCycles: Int = 4,
    val continuous: Boolean = false,
) {
    init {
        require(focusDuration > 0 && shortBreakDuration > 0 && longBreakDuration > 0)
        require(cyclesBeforeLongBreak > 0)
        require(totalWorkCycles > 0)
    }

    fun toFocusPlan(presetId: String = "reef-pomodoro"): FocusPlan = FocusPlan(
        presetId = presetId,
        workSeconds = focusDuration.toSecondsRoundedUp(),
        shortBreakSeconds = shortBreakDuration.toSecondsRoundedUp(),
        longBreakSeconds = longBreakDuration.toSecondsRoundedUp(),
        longBreakEveryCycles = cyclesBeforeLongBreak,
        totalWorkCycles = totalWorkCycles,
        continuous = continuous,
    )
}

/** Pomodoro setup shape adapted from TimerScreen.TimerConfig.Pomodoro; strict mode is excluded. */
data class ReefPomodoroConfig(
    val focusMinutes: Int,
    val shortBreakMinutes: Int,
    val longBreakMinutes: Int,
    val cycles: Int,
    val totalWorkCycles: Int = cycles,
    val continuous: Boolean = false,
) {
    init {
        require(focusMinutes in 1..180)
        require(shortBreakMinutes in 1..60)
        require(longBreakMinutes in 1..120)
        require(cycles in 1..12)
        require(totalWorkCycles in 1..100)
    }

    fun toPomodoroConfig(): PomodoroConfig = PomodoroConfig(
        focusDuration = focusMinutes * MILLIS_PER_MINUTE,
        shortBreakDuration = shortBreakMinutes * MILLIS_PER_MINUTE,
        longBreakDuration = longBreakMinutes * MILLIS_PER_MINUTE,
        cyclesBeforeLongBreak = cycles,
        totalWorkCycles = totalWorkCycles,
        continuous = continuous,
    )

    companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        val DEFAULT = ReefPomodoroConfig(25, 5, 15, 4)
    }
}

fun FocusSession.toReefTimerState(): TimerSessionState {
    val phase = when {
        status == FocusStatus.FINISHED -> PomodoroPhase.COMPLETE
        phase == FocusPhase.SHORT_BREAK -> PomodoroPhase.SHORT_BREAK
        phase == FocusPhase.LONG_BREAK -> PomodoroPhase.LONG_BREAK
        else -> PomodoroPhase.FOCUS
    }
    val remainingMillis = remainingMillis.coerceAtLeast(0L)
    val completedFocusMillis = completedWorkCycles * workSeconds * 1_000L +
        if (this.phase == FocusPhase.WORK) (workSeconds * 1_000L - remainingMillis).coerceAtLeast(0L) else 0L
    return TimerSessionState(
        isRunning = status == FocusStatus.RUNNING,
        isPaused = status == FocusStatus.PAUSED,
        timeRemaining = remainingMillis,
        focusTimeElapsed = completedFocusMillis,
        breakBudget = if (this.phase == FocusPhase.WORK) 0L else remainingMillis,
        pomodoroPhase = phase,
        currentCycle = (completedWorkCycles.toLong() + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            .let { if (continuous) it else it.coerceAtMost(totalWorkCycles) },
        totalCycles = totalWorkCycles,
        isPomodoroMode = true,
        continuous = continuous,
    )
}

/** Focus-duration and cycle totals adapted from Reef's FocusSession statistics model. */
data class ReefFocusStats(val focusTimeMillis: Long, val completedCycles: Int) {
    companion object {
        fun from(session: FocusSession): ReefFocusStats {
            val focusMillis = session.completedWorkCycles.toLong() * session.workSeconds * 1_000L +
                if (session.phase == FocusPhase.WORK) {
                    (session.workSeconds * 1_000L - session.remainingMillis).coerceAtLeast(0L)
                } else {
                    0L
                }
            return ReefFocusStats(focusMillis, session.completedWorkCycles)
        }
    }
}

private fun Long.toSecondsRoundedUp(): Long = this / 1_000L + if (this % 1_000L == 0L) 0L else 1L
