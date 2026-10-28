package dev.atlas.coach.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasNavigationStateTest {
    @Test
    fun pushesScreensAndBackReturnsToPreviousScreen() {
        val navigation = AtlasNavigationState()
        navigation.navigate(AtlasScreenId.MORE)
        navigation.navigate(AtlasScreenId.SETTINGS)

        assertEquals(AtlasScreenId.SETTINGS, navigation.current)
        assertTrue(navigation.canGoBack)
        assertEquals(AtlasScreenId.MORE, navigation.back())
        assertEquals(AtlasScreenId.HOME, navigation.back())
        assertNull(navigation.back())
        assertFalse(navigation.canGoBack)
    }

    @Test
    fun selectingTabClearsNestedHistoryAndKeepsBackAtRoot() {
        val navigation = AtlasNavigationState()
        navigation.navigate(AtlasScreenId.MORE)
        navigation.navigate(AtlasScreenId.SETTINGS)

        assertEquals(AtlasScreenId.FOCUS, navigation.selectTab(AtlasScreenId.FOCUS))
        assertTrue(navigation.canGoBack)
        assertEquals(AtlasScreenId.HOME, navigation.back())
        assertFalse(navigation.canGoBack)
    }

    @Test
    fun allFivePrimaryTabsSelectTheirOwnRoot() {
        val navigation = AtlasNavigationState()
        val tabs = listOf(
            AtlasScreenId.HOME,
            AtlasScreenId.PLAN,
            AtlasScreenId.COACH,
            AtlasScreenId.FOCUS,
            AtlasScreenId.MORE,
        )

        tabs.forEach { tab ->
            assertEquals(tab, navigation.selectTab(tab))
            assertEquals(tab, navigation.current)
            if (tab == AtlasScreenId.HOME) {
                assertFalse(navigation.canGoBack)
            } else {
                assertTrue(navigation.canGoBack)
                assertEquals(AtlasScreenId.HOME, navigation.back())
            }
        }
    }

    @Test
    fun navigatingToExistingRoutePopsBackToIt() {
        val navigation = AtlasNavigationState()
        navigation.navigate(AtlasScreenId.MORE)
        navigation.navigate(AtlasScreenId.SETTINGS)
        navigation.navigate(AtlasScreenId.ABOUT)

        assertEquals(AtlasScreenId.MORE, navigation.navigate(AtlasScreenId.MORE))
        assertTrue(navigation.canGoBack)
        assertEquals(AtlasScreenId.HOME, navigation.back())
    }

    @Test
    fun planIsReachableFromMoreAndBackReturnsToMore() {
        val navigation = AtlasNavigationState()
        navigation.navigate(AtlasScreenId.MORE)
        navigation.navigate(AtlasScreenId.PLAN)

        assertEquals(AtlasScreenId.PLAN, navigation.current)
        assertEquals(AtlasScreenId.MORE, navigation.back())
    }

    @Test
    fun launcherCoachShortcutSelectsTheCoachDestinationDirectly() {
        val navigation = AtlasNavigationState()

        navigation.openCoachFromLauncher()

        assertEquals(AtlasScreenId.COACH, navigation.current)
        assertTrue(navigation.canGoBack)
        assertEquals(AtlasScreenId.HOME, navigation.back())
    }
}
