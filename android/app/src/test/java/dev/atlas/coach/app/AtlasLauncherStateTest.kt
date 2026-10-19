package dev.atlas.coach.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasLauncherStateTest {
    @Test
    fun favoritesToggleIndependentlyAndRoleStateRefreshes() {
        val state = AtlasLauncherState(setOf("com.example.notes"))

        assertTrue(state.togglePin("com.android.settings"))
        assertFalse(state.togglePin("com.example.notes"))
        assertEquals(setOf("com.android.settings"), state.pinnedPackages)

        assertEquals(null, state.homeRoleHeld)
        state.refreshHomeRole(true)
        assertEquals(true, state.homeRoleHeld)
        state.refreshHomeRole(false)
        assertEquals(false, state.homeRoleHeld)
    }
}
