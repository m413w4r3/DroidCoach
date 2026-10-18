package dev.atlas.coach.app

import dev.atlas.coach.coach.DirectiveEffect
import dev.atlas.coach.coach.displayLabel
import dev.atlas.coach.focus.FocusStatus
import dev.atlas.coach.focus.FocusPhase
import dev.atlas.coach.focus.displayLabel as focusLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayLabelsTest {
    @Test
    fun focusStatusesAndCoachEffectsUseFrenchLabels() {
        assertEquals("En cours", FocusStatus.RUNNING.focusLabel())
        assertEquals("En pause", FocusStatus.PAUSED.focusLabel())
        assertEquals("Pause longue", FocusPhase.LONG_BREAK.focusLabel())
        assertEquals("Concentration demandée", DirectiveEffect.FOCUS_REQUESTED.displayLabel())
    }
}
