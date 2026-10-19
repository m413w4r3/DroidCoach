package dev.atlas.coach.app

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.ui.AtlasTheme
import dev.atlas.coach.app.ui.AtlasThemePreference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import android.os.ParcelFileDescriptor

@RunWith(AndroidJUnit4::class)
class LotEUiInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val isolatedContext: Context by lazy {
        val testPackageContext = InstrumentationRegistry.getInstrumentation().context
        object : ContextWrapper(testPackageContext) {
            override fun getApplicationContext(): Context = this
        }
    }

    @Test
    fun capturesSyntheticHomeInDarkAndAmoledThemes() {
        resetIsolatedState()
        val controller = AtlasController(isolatedContext)
        val preference = mutableStateOf(AtlasThemePreference.DARK)
        val syntheticState = HomeAgentState(
            directive = HomeDirective(
                title = "Lire quelques pages puis avancer sur une petite étape",
                body = "Un pas concret, préparé localement et disponible hors ligne.",
                primaryAction = HomePrimaryAction.START_FOCUS,
            ),
            focusCard = HomeAgentCard("Prochain focus", "25 minutes de concentration, pause de 5 minutes.", "Voir le Focus", HomeCardAction.OPEN_FOCUS),
            contentCard = HomeAgentCard("Suggestion de contenu", "Lecture de démonstration", "Ouvrir la bibliothèque", HomeCardAction.OPEN_LIBRARY),
            agentStatus = HomeAgentStatus.LOCAL_ONLY,
        )

        composeRule.setContent {
            AtlasTheme(preference.value) {
                LauncherHomeScreen(
                    controller = controller,
                    context = isolatedContext,
                    isHomeRoleHeld = true,
                    onRequestHomeRole = {},
                    openCoach = {},
                    openFocus = {},
                    openLibrary = {},
                    openPreviousLauncher = {},
                    openHomeSettings = {},
                    agentState = syntheticState,
                )
            }
        }

        composeRule.onNodeWithText("Prochaine étape").assertIsDisplayed()
        composeRule.onNodeWithText("Mode local · hors ligne").assertIsDisplayed()
        composeRule.waitForIdle()
        shell("screencap -p /sdcard/v6-home-dark.png")

        composeRule.runOnUiThread { preference.value = AtlasThemePreference.AMOLED }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Préparer le focus").assertIsDisplayed()
        composeRule.onNodeWithText("Voir le Focus").assertIsDisplayed()
        shell("screencap -p /sdcard/v6-home-amoled.png")
    }

    @Test
    fun proposedFocusRequiresTapAndSupportsPauseResumeFinish() {
        resetIsolatedState()
        val controller = AtlasController(isolatedContext)
        val proposed = ProposedFocusSession(
            focusMinutes = 25,
            breakMinutes = 5,
            cycles = 2,
            label = "Lecture",
            reason = "Une session courte proposée localement.",
        )
        composeRule.setContent {
            AtlasTheme(AtlasThemePreference.DARK) {
                FocusSessionScreen(controller = controller, proposedSession = proposed)
            }
        }

        composeRule.onNodeWithText("Session proposée").assertIsDisplayed()
        composeRule.onNodeWithText("25 min de focus · 5 min de pause · 2 cycle(s)").assertIsDisplayed()
        assertEquals(null, controller.focusSession)
        composeRule.onNodeWithText("Démarrer").performClick()
        composeRule.onNodeWithText("Session en cours").assertIsDisplayed()
        composeRule.onNodeWithText("Pause").assertIsDisplayed()
        composeRule.onNodeWithText("Annuler").assertIsDisplayed()
        composeRule.onNodeWithText("Terminer").assertIsDisplayed()
    }

    @Test
    fun emergencyAndExitActionsRemainReachableAtFontScaleTwoInLandscape() {
        resetIsolatedState()
        val controller = AtlasController(isolatedContext)
        composeRule.runOnUiThread {
            composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        composeRule.waitForIdle()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AtlasTheme(AtlasThemePreference.DARK) {
                    LauncherHomeScreen(
                        controller = controller,
                        context = isolatedContext,
                        isHomeRoleHeld = true,
                        onRequestHomeRole = {},
                        openCoach = {},
                        openFocus = {},
                        openLibrary = {},
                        openPreviousLauncher = {},
                        openHomeSettings = {},
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Activer la pause d'urgence hors ligne pendant 30 minutes").assertIsDisplayed()
        composeRule.onNodeWithText("Launcher précédent").assertIsDisplayed()
        composeRule.onNodeWithText("Réglages HOME").assertIsDisplayed()
        composeRule.onNodeWithText("Coach").assertIsDisplayed()
    }

    private fun resetIsolatedState() {
        // Instrumentation context belongs to the test APK, so this never clears app/user preferences.
        check(isolatedContext.packageName != InstrumentationRegistry.getInstrumentation().targetContext.packageName) {
            "Refusing to reset preferences outside the isolated instrumentation package"
        }
        isolatedContext.getSharedPreferences("atlas_local_state", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).use { descriptor -> BufferedReader(InputStreamReader(descriptor)).readText() }
}
