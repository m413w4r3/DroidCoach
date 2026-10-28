package dev.atlas.coach.app

import dev.atlas.coach.focus.FocusPhase
import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.focus.FocusStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusGaugeTest {
    @Test
    fun workPhaseProgressCoversFullHalfAndZero() {
        assertEquals(1f, focusGaugeProgress(session(remainingMillis = 1_500_000L)), 0f)
        assertEquals(0.5f, focusGaugeProgress(session(remainingMillis = 750_000L)), 0f)
        assertEquals(0f, focusGaugeProgress(session(remainingMillis = 0L)), 0f)
    }

    @Test
    fun pausedSessionKeepsProgressBasedOnItsRemainingTime() {
        val paused = session(
            remainingMillis = 600_000L,
            workSeconds = 1_200L,
            status = FocusStatus.PAUSED,
        )

        assertEquals(0.5f, focusGaugeProgress(paused), 0f)
    }

    @Test
    fun shortAndLongBreaksUseTheirOwnDurations() {
        val shortBreak = session(
            phase = FocusPhase.SHORT_BREAK,
            remainingMillis = 150_000L,
            shortBreakSeconds = 300L,
        )
        val longBreak = session(
            phase = FocusPhase.LONG_BREAK,
            remainingMillis = 450_000L,
            longBreakSeconds = 900L,
        )

        assertEquals(0.5f, focusGaugeProgress(shortBreak), 0f)
        assertEquals(0.5f, focusGaugeProgress(longBreak), 0f)
    }

    @Test
    fun incoherentRemainingValuesAreClamped() {
        assertEquals(0f, focusGaugeProgress(session(remainingMillis = -1L)), 0f)
        assertEquals(1f, focusGaugeProgress(session(remainingMillis = 1_500_001L)), 0f)
    }

    @Test
    fun zeroPhaseDurationReturnsZeroWithoutDividingByZero() {
        val zeroDuration = session(remainingMillis = 1L, workSeconds = 0L)

        assertEquals(0f, focusGaugeProgress(zeroDuration), 0f)
    }

    @Test
    fun descriptionRoundsUpAndIncludesMinutesAndSeconds() {
        assertEquals("Temps restant 12 min 30 s", focusGaugeContentDescription(750_000L))
        assertEquals("Temps restant 0 min 1 s", focusGaugeContentDescription(1L))
        assertEquals("Temps restant 0 min 0 s", focusGaugeContentDescription(-1L))
    }

    private fun session(
        phase: FocusPhase = FocusPhase.WORK,
        status: FocusStatus = FocusStatus.RUNNING,
        remainingMillis: Long,
        workSeconds: Long = 1_500L,
        shortBreakSeconds: Long = 300L,
        longBreakSeconds: Long = 900L,
    ) = FocusSession(
        id = "gauge-test",
        directiveId = null,
        presetId = "gauge-test",
        phase = phase,
        status = status,
        createdAtUtcMillis = 0L,
        updatedAtUtcMillis = 0L,
        remainingSeconds = remainingMillis.coerceAtLeast(0L) / 1_000L,
        completedWorkCycles = 0,
        workSeconds = workSeconds,
        shortBreakSeconds = shortBreakSeconds,
        longBreakSeconds = longBreakSeconds,
        longBreakEveryCycles = 4,
        remainingMillis = remainingMillis,
        totalWorkCycles = 4,
        continuous = false,
    )
}
