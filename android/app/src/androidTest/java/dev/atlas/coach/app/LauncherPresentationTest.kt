package dev.atlas.coach.app

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.ui.AtlasTheme
import dev.atlas.coach.launcher.AndroidLauncherCatalog
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherPresentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun homeHasNoCoachChromeAndSwipeSearchPinAndFavoritesWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settingsApp = AndroidLauncherCatalog(context).list().firstOrNull { it.appPackage == "com.android.settings" }
        assertNotNull("Android Settings should be discoverable through MAIN/LAUNCHER", settingsApp)
        val appLabel = requireNotNull(settingsApp).appLabel
        lateinit var controller: AtlasController
        composeRule.runOnUiThread {
            context.getSharedPreferences("atlas_local_state", Context.MODE_PRIVATE).edit().clear().commit()
            controller = AtlasController(context)
        }
        composeRule.setContent {
            AtlasTheme {
                LauncherHomeScreen(
                    controller = controller,
                    context = context,
                    isHomeRoleHeld = true,
                    onRequestHomeRole = {},
                    openCoach = {},
                    openPreviousLauncher = {},
                    openHomeSettings = {},
                )
            }
        }

        composeRule.onNodeWithText("Coach").assertIsDisplayed()
        composeRule.onNodeWithText("Pause d'urgence · 30 min").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Ouvrir Tableau").assertCountEquals(0)
        composeRule.onNodeWithTag("launcher_swipe_header").performTouchInput { swipeUp() }
        composeRule.onNodeWithTag("launcher_search").performScrollTo().assertIsDisplayed().performTextInput(appLabel)
        composeRule.onNodeWithContentDescription("Épingler $appLabel parmi les favoris").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Retirer $appLabel des favoris").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("launcher_drawer_header").performTouchInput { swipeDown() }
        composeRule.onNodeWithContentDescription("Ouvrir l'application favorite $appLabel").performScrollTo().assertIsDisplayed()
        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.onNodeWithText("Directive du jour").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun emergencyAndLauncherExitActionsStayReachableAtFontScaleTwo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var controller: AtlasController
        composeRule.runOnUiThread {
            context.getSharedPreferences("atlas_local_state", Context.MODE_PRIVATE).edit().clear().commit()
            controller = AtlasController(context)
        }
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AtlasTheme {
                    LauncherHomeScreen(
                        controller = controller,
                        context = context,
                        isHomeRoleHeld = true,
                        onRequestHomeRole = {},
                        openCoach = {},
                        openPreviousLauncher = {},
                        openHomeSettings = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Pause d'urgence · 30 min").assertIsDisplayed()
        composeRule.onNodeWithText("Launcher précédent").assertIsDisplayed()
        composeRule.onNodeWithText("Réglages HOME").assertIsDisplayed()
        composeRule.onNodeWithText("Coach").assertIsDisplayed()
    }
}
