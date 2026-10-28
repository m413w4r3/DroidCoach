package dev.atlas.coach.app

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
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
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.ui.AtlasTheme
import dev.atlas.coach.launcher.AndroidLauncherCatalog
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherPresentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun isolatedContext(): Context {
        val testPackageContext = InstrumentationRegistry.getInstrumentation().context
        return object : ContextWrapper(testPackageContext) {
            override fun getApplicationContext(): Context = this
        }
    }

    @Test
    fun homeHasNoCoachChromeAndSwipeSearchPinAndFavoritesWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stateContext = isolatedContext()
        val settingsApp = AndroidLauncherCatalog(context).list().firstOrNull { it.appPackage == "com.android.settings" }
        assertNotNull("Android Settings should be discoverable through MAIN/LAUNCHER", settingsApp)
        val appLabel = requireNotNull(settingsApp).appLabel
        val planOpens = AtomicInteger(0)
        lateinit var controller: AtlasController
        composeRule.runOnUiThread {
            stateContext.getSharedPreferences("atlas_local_state", Context.MODE_PRIVATE).edit().clear().commit()
            controller = AtlasController(stateContext)
        }
        composeRule.setContent {
            AtlasTheme {
                LauncherHomeScreen(
                    controller = controller,
                    context = context,
                    isHomeRoleHeld = true,
                    onRequestHomeRole = {},
                    openCoach = {},
                    openPlan = { planOpens.incrementAndGet() },
                    openPreviousLauncher = {},
                    openHomeSettings = {},
                )
            }
        }

        composeRule.onNodeWithText("Coach").assertIsDisplayed()
        composeRule.onNodeWithTag("launcher_emergency_pause").assertIsDisplayed()
        composeRule.onNodeWithTag("launcher_open_drawer").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_open_coach").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_open_plan").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, planOpens.get())
        composeRule.onNodeWithTag("launcher_more").assertHeightIsAtLeast(48.dp)
        composeRule.onAllNodesWithContentDescription("Ouvrir Tableau").assertCountEquals(0)
        composeRule.onNodeWithTag("launcher_favorites_empty").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("launcher_choose_apps").performScrollTo().performClick()
        composeRule.onNodeWithText("Glissez vers le bas pour fermer.").assertIsDisplayed()
        composeRule.onNodeWithTag("launcher_search").performScrollTo().assertIsDisplayed().performTextInput(appLabel)
        composeRule.onNodeWithContentDescription("Épingler $appLabel parmi les favoris").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Retirer $appLabel des favoris").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("launcher_drawer_header").performTouchInput { swipeDown(startY = centerY, endY = centerY + 400f) }
        composeRule.onNodeWithContentDescription("Ouvrir l'application favorite $appLabel").performScrollTo().assertIsDisplayed()
        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.onNodeWithTag("launcher_next_step_heading").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun emergencyAndLauncherExitActionsStayReachableAtFontScaleTwo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stateContext = isolatedContext()
        lateinit var controller: AtlasController
        composeRule.runOnUiThread {
            stateContext.getSharedPreferences("atlas_local_state", Context.MODE_PRIVATE).edit().clear().commit()
            controller = AtlasController(stateContext)
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
        composeRule.onNodeWithTag("launcher_emergency_pause").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_open_drawer").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_open_plan").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_more").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithTag("launcher_previous_launcher").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_home_settings").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_open_coach").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }
}
