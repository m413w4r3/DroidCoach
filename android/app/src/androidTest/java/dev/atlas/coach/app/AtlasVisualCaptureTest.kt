package dev.atlas.coach.app

import android.content.Context
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
class AtlasVisualCaptureTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun newController(): AtlasController {
        context.getSharedPreferences("atlas_local_state", Context.MODE_PRIVATE).edit().clear().commit()
        return AtlasController(context)
    }

    private fun show(controller: AtlasController, screen: MutableState<CaptureScreen>, night: MutableIntState, fontScale: MutableState<Float>) {
        composeRule.setContent {
            val baseConfig = LocalConfiguration.current
            val config = Configuration(baseConfig).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night.intValue
            }
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalConfiguration provides config,
                LocalDensity provides Density(baseDensity.density, fontScale.value),
            ) {
                AtlasTheme {
                    if (screen.value == CaptureScreen.LAUNCHER) {
                        LauncherHomeScreen(
                            controller = controller,
                            context = context,
                            isHomeRoleHeld = true,
                            onRequestHomeRole = {},
                            openCoach = {},
                            openPreviousLauncher = {},
                            openHomeSettings = {},
                        )
                    } else {
                        AtlasScreenFrame(
                            title = screen.value.title,
                            activeDestination = screen.value.activeDestination,
                            canGoBack = false,
                            emergencyPaused = controller.isEmergencyPaused,
                            onBack = {},
                            onEmergencyPause = { if (controller.isEmergencyPaused) controller.endEmergencyPause() else controller.setEmergencyPause() },
                            onNavigate = {},
                        ) {
                            when (screen.value) {
                                CaptureScreen.HOME -> HomeScreen(controller, controller.revision)
                                CaptureScreen.FOCUS -> FocusScreen(controller, controller.revision, onChooseHabit = {})
                                CaptureScreen.HABITS -> HabitsScreen(controller, controller.revision, onExport = {})
                                CaptureScreen.ROUTINES -> FocusRoutinesScreen(controller)
                                CaptureScreen.OBSERVATION -> ObservationScreen(controller, context, requestNotifications = {})
                                CaptureScreen.INTERVENTION -> SimulatorScreen(controller, controller.revision)
                                CaptureScreen.LIBRARY -> LibraryScreen(controller, controller.revision, context)
                                CaptureScreen.SETTINGS -> SettingsScreen(controller, {}, {}, {}, {})
                                CaptureScreen.DIAGNOSTICS -> DiagnosticsScreen(controller)
                                CaptureScreen.SYNC -> SyncSettingsScreen(controller, controller.revision)
                                CaptureScreen.LAUNCHER -> Unit
                            }
                        }
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        shell("screencap -p /sdcard/$name")
    }

    private fun select(screen: MutableState<CaptureScreen>, value: CaptureScreen) {
        composeRule.runOnUiThread { screen.value = value }
        composeRule.waitForIdle()
    }

    @Test
    fun capturesHomeInBothThemesAndAtFontScaleTwo() {
        val controller = newController()
        val screen = mutableStateOf(CaptureScreen.HOME)
        val night = mutableIntStateOf(Configuration.UI_MODE_NIGHT_NO)
        val fontScale = mutableStateOf(1f)
        show(controller, screen, night, fontScale)
        composeRule.onNodeWithContentDescription("Ouvrir Tableau").assertIsSelected()
        composeRule.onNodeWithContentDescription("Ouvrir Plus").assertIsNotSelected()
        composeRule.onNodeWithText("La prochaine étape").assertIsDisplayed()
        capture("G-home-light.png")
        composeRule.runOnUiThread { night.intValue = Configuration.UI_MODE_NIGHT_YES }
        composeRule.onNodeWithText("La prochaine étape").assertIsDisplayed()
        capture("G-home-dark.png")
        composeRule.runOnUiThread { fontScale.value = 2f }
        composeRule.onNodeWithText("ATLAS").assertIsDisplayed()
        capture("G-font-2.0.png")
    }

    @Test
    fun capturesFocusActivePausedAndFinished() {
        val controller = newController()
        controller.startDemoFocus()
        val screen = mutableStateOf(CaptureScreen.FOCUS)
        val night = mutableIntStateOf(Configuration.UI_MODE_NIGHT_NO)
        val fontScale = mutableStateOf(1f)
        show(controller, screen, night, fontScale)
        capture("G-focus-active.png")
        composeRule.onNodeWithText("Pause").performScrollTo().performClick()
        capture("G-focus-pause.png")
        composeRule.onNodeWithText("Reprendre").performScrollTo().performClick()
        composeRule.onNodeWithText("Terminer").performScrollTo().performClick()
        capture("G-focus-finished.png")
    }

    @Test
    fun capturesHabitsAndRoutines() {
        val controller = newController()
        val screen = mutableStateOf(CaptureScreen.HABITS)
        val night = mutableIntStateOf(Configuration.UI_MODE_NIGHT_NO)
        val fontScale = mutableStateOf(1f)
        show(controller, screen, night, fontScale)
        capture("G-habits.png")
        controller.setCapability(AtlasController.CAP_FOCUS_ROUTINES, true)
        select(screen, CaptureScreen.ROUTINES)
        capture("G-routines.png")
    }

    @Test
    fun capturesObservationInterventionAndLibrary() {
        val controller = newController()
        val screen = mutableStateOf(CaptureScreen.OBSERVATION)
        val night = mutableIntStateOf(Configuration.UI_MODE_NIGHT_NO)
        val fontScale = mutableStateOf(1f)
        show(controller, screen, night, fontScale)
        capture("G-observation-permissions.png")
        controller.simulateFourOpenings()
        select(screen, CaptureScreen.INTERVENTION)
        capture("G-intervention.png")
        select(screen, CaptureScreen.LIBRARY)
        capture("G-library.png")
    }

    @Test
    fun capturesEverydaySettingsAdvancedDiagnosticsAndOfflineSync() {
        val controller = newController()
        val screen = mutableStateOf(CaptureScreen.SETTINGS)
        val night = mutableIntStateOf(Configuration.UI_MODE_NIGHT_NO)
        val fontScale = mutableStateOf(1f)
        show(controller, screen, night, fontScale)
        capture("G-settings-daily.png")
        select(screen, CaptureScreen.DIAGNOSTICS)
        capture("G-settings-diagnostic.png")
        select(screen, CaptureScreen.SYNC)
        capture("G-sync-offline.png")
        composeRule.runOnUiThread { night.intValue = Configuration.UI_MODE_NIGHT_YES }
        composeRule.onNodeWithText("Désactivée").performScrollTo().assertIsDisplayed()
        capture("H3-sync-disabled-dark.png")
    }

    @Test
    fun capturesLauncherHomeAndSearchableAppsList() {
        val controller = newController()
        controller.syncLauncherHomeRole(true)
        val screen = mutableStateOf(CaptureScreen.LAUNCHER)
        val night = mutableIntStateOf(Configuration.UI_MODE_NIGHT_NO)
        val fontScale = mutableStateOf(1f)
        show(controller, screen, night, fontScale)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("launcher_open_drawer").fetchSemanticsNodes().isNotEmpty()
        }
        capture("G-launcher-home.png")
        composeRule.onNodeWithTag("launcher_open_drawer").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("launcher_search").fetchSemanticsNodes().isNotEmpty() &&
                (composeRule.onAllNodesWithText("Épingler").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Aucune application avec un raccourci d'accueil n'est visible.").fetchSemanticsNodes().isNotEmpty()
                )
        }
        if (composeRule.onAllNodesWithText("Épingler").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onAllNodesWithText("Épingler")[0].performScrollTo().performClick()
        }
        capture("G-launcher-apps.png")
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).use { descriptor -> BufferedReader(InputStreamReader(descriptor)).readText() }
}

private enum class CaptureScreen(val title: String) {
    HOME("ATLAS"),
    LAUNCHER("ATLAS"),
    FOCUS("Focus"),
    HABITS("Habitudes"),
    ROUTINES("Routines de focus"),
    OBSERVATION("Observation"),
    INTERVENTION("Diagnostic avancé"),
    LIBRARY("Bibliothèque"),
    SETTINGS("Paramètres"),
    DIAGNOSTICS("Diagnostic avancé"),
    SYNC("Partage backend");

    val activeDestination: String
        get() = when (this) {
            HOME, LAUNCHER -> AtlasRoutes.HOME
            FOCUS -> AtlasRoutes.FOCUS
            HABITS -> AtlasRoutes.HABITS
            else -> AtlasRoutes.MORE
        }
}
