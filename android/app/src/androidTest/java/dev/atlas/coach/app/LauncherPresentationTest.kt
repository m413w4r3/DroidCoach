package dev.atlas.coach.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.ui.AtlasRoutes
import dev.atlas.coach.app.ui.AtlasScreenFrame
import dev.atlas.coach.app.ui.AtlasTheme
import dev.atlas.coach.launcher.AndroidLauncherCatalog
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherPresentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun swipeSearchPinAndFavoritesWorkInTheLauncherScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settingsApp = AndroidLauncherCatalog(context).list().firstOrNull { it.appPackage == "com.android.settings" }
        assertNotNull("Android Settings should be discoverable through MAIN/LAUNCHER", settingsApp)
        val appLabel = requireNotNull(settingsApp).appLabel
        lateinit var controller: AtlasController
        composeRule.runOnUiThread {
            context.getSharedPreferences("atlas_local_state", Context.MODE_PRIVATE).edit().clear().commit()
            controller = AtlasController(context)
            controller.setCapability(AtlasController.CAP_LAUNCHER_HOME, true)
        }
        composeRule.setContent {
            AtlasTheme {
                AtlasScreenFrame(
                    title = "ATLAS",
                    activeDestination = AtlasRoutes.HOME,
                    canGoBack = false,
                    emergencyPaused = false,
                    onBack = {},
                    onEmergencyPause = {},
                    onNavigate = {},
                ) {
                    LauncherHomeScreen(controller, context, openSystemHome = {})
                }
            }
        }

        composeRule.onNodeWithTag("launcher_swipe_header").performTouchInput { swipeUp() }
        composeRule.onNodeWithTag("launcher_search").performScrollTo().assertIsDisplayed().performTextInput(appLabel)
        composeRule.onNodeWithContentDescription("Épingler $appLabel parmi les favoris").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Retirer $appLabel des favoris").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Favoris").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Ouvrir l'application favorite $appLabel").performScrollTo().assertIsDisplayed()
    }
}
