package dev.atlas.coach.app

import dev.atlas.coach.focus.FocusStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityRulesTest {
    @Test
    fun disabledHabitCapabilityRejectsLinkConfirmation() {
        assertFalse(CapabilityRules.mayConfirmHabitLink(mapOf(AtlasController.CAP_HABITS to false)))
        assertTrue(CapabilityRules.mayConfirmHabitLink(mapOf(AtlasController.CAP_HABITS to true)))
    }

    @Test
    fun disabledFocusCapabilityStopsTicksAndRequiresRunningSessionToPause() {
        val disabled = mapOf(AtlasController.CAP_FOCUS to false)
        assertFalse(CapabilityRules.mayTickFocus(disabled, FocusStatus.RUNNING))
        assertFalse(CapabilityRules.mayTickFocus(mapOf(AtlasController.CAP_FOCUS to true), FocusStatus.PAUSED))
        assertTrue(CapabilityRules.mustPauseFocusOnDisable(false, FocusStatus.RUNNING))
        assertFalse(CapabilityRules.mustPauseFocusOnDisable(false, FocusStatus.PAUSED))
    }

    @Test
    fun selectedAndroidHomeRoleRendersLauncherEvenWhenLegacyCapabilityIsOff() {
        val oldInstall = mapOf(AtlasController.CAP_LAUNCHER_HOME to false)
        assertTrue(CapabilityRules.mayRenderLauncherHome(oldInstall, isAndroidHomeRole = true))
        assertFalse(CapabilityRules.mayRenderLauncherHome(oldInstall, isAndroidHomeRole = false))
        assertTrue(CapabilityRules.mayRenderLauncherHome(mapOf(AtlasController.CAP_LAUNCHER_HOME to true), isAndroidHomeRole = false))
    }
}
