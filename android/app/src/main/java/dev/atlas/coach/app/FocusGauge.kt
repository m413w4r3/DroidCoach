package dev.atlas.coach.app

import dev.atlas.coach.focus.FocusPhase
import dev.atlas.coach.focus.FocusSession

/** Progress through the active phase, independent of rendering or wall-clock state. */
internal fun focusGaugeProgress(session: FocusSession): Float {
    val phaseDurationSeconds = when (session.phase) {
        FocusPhase.WORK -> session.workSeconds
        FocusPhase.SHORT_BREAK -> session.shortBreakSeconds
        FocusPhase.LONG_BREAK -> session.longBreakSeconds
    }
    if (phaseDurationSeconds <= 0L) return 0f

    // Double arithmetic avoids overflowing when malformed inputs exceed FocusPlan's bounds.
    val durationMillis = phaseDurationSeconds.toDouble() * MILLIS_PER_SECOND
    return (session.remainingMillis.toDouble() / durationMillis)
        .coerceIn(0.0, 1.0)
        .toFloat()
}

/** A short TalkBack description matching the rounded-up countdown shown in the gauge. */
internal fun focusGaugeContentDescription(remainingMillis: Long): String {
    val nonNegativeMillis = remainingMillis.coerceAtLeast(0L)
    val totalSeconds = nonNegativeMillis / MILLIS_PER_SECOND +
        if (nonNegativeMillis % MILLIS_PER_SECOND == 0L) 0L else 1L
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "Temps restant $minutes min $seconds s"
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
