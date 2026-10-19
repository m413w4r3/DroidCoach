package dev.atlas.coach.app

import android.content.Context
import android.view.KeyEvent
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.ui.AtlasRoutes
import dev.atlas.coach.app.ui.AtlasScreenFrame
import dev.atlas.coach.app.ui.AtlasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader

@RunWith(AndroidJUnit4::class)
class AtlasPresentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setHome(fontScale: Float, capturePath: String? = null) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var controller: AtlasController
        composeRule.runOnUiThread {
            context.getSharedPreferences("atlas_local_state", Context.MODE_PRIVATE).edit().clear().commit()
            controller = AtlasController(context)
        }
        composeRule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(currentDensity.density, fontScale)) {
                AtlasTheme {
                    AtlasScreenFrame(
                        title = "ATLAS",
                        activeDestination = AtlasRoutes.HOME,
                        canGoBack = false,
                        emergencyPaused = controller.isEmergencyPaused,
                        onBack = {},
                        onEmergencyPause = { if (controller.isEmergencyPaused) controller.endEmergencyPause() else controller.setEmergencyPause() },
                        onNavigate = {},
                    ) {
                        HomeScreen(controller, controller.revision)
                    }
                }
            }
        }
        if (capturePath != null) {
            composeRule.waitForIdle()
            shell("screencap -p /sdcard/$capturePath")
        }
    }

    @Test
    fun homeHasOnePrimaryActionAndSecondaryNegotiationMenu() {
        setHome(fontScale = 1f)
        composeRule.onNodeWithText("Commencer").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Discuter").assertCountEquals(0)
        composeRule.onAllNodesWithText("Alternative").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Activer la pause d'urgence de 30 minutes").assertIsDisplayed()

        composeRule.onNodeWithText("Autres réponses à la directive").performScrollTo().performClick()
        composeRule.onNodeWithText("Discuter").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Alternative").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Reporter").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Pause coach").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun homeRemainsReadableAtFontScale13() {
        setHome(fontScale = 1.3f)
        composeRule.onNodeWithText("ATLAS").assertIsDisplayed()
        composeRule.onNodeWithText("Commencer").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Activer la pause d'urgence de 30 minutes").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Ouvrir Plus").assertIsDisplayed()
    }

    @Test
    fun homeRemainsScrollableAtFontScale20() {
        setHome(fontScale = 2.0f, capturePath = "G-font-2.0.png")
        composeRule.onNodeWithText("ATLAS").assertIsDisplayed()
        composeRule.onNodeWithText("Commencer").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Activer la pause d'urgence de 30 minutes").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Ouvrir Habitudes").assertIsDisplayed()
    }

    @Test
    fun bottomNavigationAndBackFollowTheScreenHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var controller: AtlasController
        composeRule.runOnUiThread {
            context.getSharedPreferences("atlas_local_state", Context.MODE_PRIVATE).edit().clear().commit()
            controller = AtlasController(context)
        }
        composeRule.setContent {
            AtlasTheme {
                AtlasApp(controller, context, requestHomeRole = {}, openSystemHome = {})
            }
        }

        composeRule.onNodeWithContentDescription("Ouvrir Tableau").assertIsSelected()
        composeRule.onNodeWithContentDescription("Ouvrir Plus").assertIsNotSelected()
        composeRule.onNodeWithContentDescription("Ouvrir Focus").performClick()
        composeRule.onNodeWithContentDescription("Ouvrir Focus").assertIsSelected()
        composeRule.onNodeWithContentDescription("Ouvrir Tableau").assertIsNotSelected()
        composeRule.onNodeWithText("Un pas à la fois").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Ouvrir Plus").performClick()
        composeRule.onNodeWithContentDescription("Ouvrir Plus").assertIsSelected()
        composeRule.onNodeWithText("Bibliothèque").performScrollTo().performClick()
        composeRule.onNodeWithText("Aucun lien vidéo ajouté.").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Bibliothèque").performScrollTo().assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("La prochaine étape").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Ouvrir Tableau").assertIsSelected()
        composeRule.onNodeWithContentDescription("Ouvrir Plus").assertIsNotSelected()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).use { descriptor -> BufferedReader(InputStreamReader(descriptor)).readText() }
}
