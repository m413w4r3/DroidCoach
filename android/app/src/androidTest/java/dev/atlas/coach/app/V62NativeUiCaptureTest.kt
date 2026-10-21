package dev.atlas.coach.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.ui.AtlasRoutes
import dev.atlas.coach.app.ui.AtlasScreenFrame
import dev.atlas.coach.app.ui.AtlasTheme
import dev.atlas.coach.app.ui.AtlasThemePalettes
import dev.atlas.coach.app.ui.AtlasThemePreference
import dev.atlas.coach.app.ui.wcagContrastRatio
import dev.atlas.coach.focus.FakeClock
import dev.atlas.coach.focus.FocusPlan
import dev.atlas.coach.launcher.AndroidLauncherCatalog
import dev.atlas.coach.sync.agent.DirectiveProvenance
import dev.atlas.coach.sync.agent.coach.AgentMessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native Compose captures and layout checks for a disposable AVD. All records are synthetic. */
@RunWith(AndroidJUnit4::class)
class V62NativeUiCaptureTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val captureFailures = mutableListOf<String>()

    @Test
    fun nativeHomeMatrixUsesConfiguredOrActualWidth() {
        val widthArgument = InstrumentationRegistry.getArguments().getString("expected_width_dp")
        val requestedWidthDp = widthArgument?.toIntOrNull()
        require(widthArgument == null || requestedWidthDp != null) {
            "expected_width_dp must be an integer when supplied; got '$widthArgument'"
        }
        val widthDp = requestedWidthDp ?: composeRule.activity.resources.configuration.screenWidthDp
        val controller = newController()
        controller.syncLauncherHomeRole(true)
        controller.setCapability(AtlasController.CAP_FOCUS, true)

        val catalog = AndroidLauncherCatalog(InstrumentationRegistry.getInstrumentation().targetContext)
        val applications = catalog.list()
        assertTrue("The AVD launcher should expose at least five demo favorites", applications.size >= 5)
        applications.take(5).forEach { controller.toggleLauncherPin(it.appPackage) }

        controller.startFocus(FocusPlan.custom(25 * 60L).copy(taskTitle = "Réviser un chapitre"))
        val activeHome = HomeAgentState.localDefault(controller)
        val theme = mutableStateOf(AtlasThemePreference.DARK)
        val fontScale = mutableStateOf(1f)
        val state = mutableStateOf(activeHome)
        showHome(controller, theme, fontScale, state)

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("launcher_open_drawer").fetchSemanticsNodes().isNotEmpty()
        }
        if (requestedWidthDp != null) {
            assertEquals("Configured capture width must match the AVD viewport", requestedWidthDp,
                composeRule.activity.resources.configuration.screenWidthDp)
        } else {
            Log.i("ATLAS_UI_CAPTURE", "expected_width_dp not supplied; using actual viewport ${widthDp}dp")
        }
        composeRule.onNodeWithTag("launcher_focus_card_action").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_open_drawer").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_open_coach").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_emergency_pause").assertHeightIsAtLeast(48.dp)
        capture("v62-home-dark-focus-${widthDp}dp.png")
        capture("v62-home-s4-accent-${widthDp}dp.png")

        composeRule.runOnUiThread { theme.value = AtlasThemePreference.AMOLED }
        composeRule.onNodeWithText("ATLAS").assertIsDisplayed()
        capture("v62-home-amoled-focus-${widthDp}dp.png")

        val noRemoteDirective = HomeAgentState(
            directive = HomeDirective(
                title = "Aucune directive distante",
                body = "Le mode local reste disponible.",
                primaryAction = HomePrimaryAction.NONE,
                provenance = DirectiveProvenance.MANUAL_ENTRY,
            ),
            directiveProvenance = DirectiveProvenance.MANUAL_ENTRY,
        )
        composeRule.runOnUiThread {
            state.value = noRemoteDirective
            theme.value = AtlasThemePreference.DARK
        }
        composeRule.onNodeWithText("Local").assertIsDisplayed()
        capture("v62-home-no-remote-directive-${widthDp}dp.png")

        val lastFavorite = applications.take(5).last()
        composeRule.onNodeWithTag("launcher_favorite_${lastFavorite.appPackage}").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("launcher_favorite_${lastFavorite.appPackage}").assertHeightIsAtLeast(48.dp)
        val favoriteBounds = composeRule.onNodeWithTag("launcher_favorite_${lastFavorite.appPackage}")
            .fetchSemanticsNode().boundsInRoot
        val footerBounds = composeRule.onNodeWithTag("launcher_fixed_footer").fetchSemanticsNode().boundsInRoot
        assertTrue("Scrolled favorite is covered by the fixed footer: favorite=$favoriteBounds footer=$footerBounds", favoriteBounds.bottom <= footerBounds.top)
        capture("v62-home-five-favorites-${widthDp}dp.png")

        composeRule.onNodeWithTag("launcher_open_drawer").performClick()
        composeRule.onNodeWithTag("launcher_search").assertIsDisplayed()
        capture("v62-app-drawer-${widthDp}dp.png")
        val settingsApp = applications.firstOrNull { it.appPackage == "com.android.settings" }
            ?: error("Android Settings should be discoverable on the disposable AVD")
        composeRule.onNodeWithTag("launcher_search").performTextInput(settingsApp.appLabel)
        // The editable field and the filtered result both expose the exact query text.
        composeRule.onAllNodesWithText(settingsApp.appLabel).assertCountEquals(2)
        capture("v62-app-search-${widthDp}dp.png")
        composeRule.onNodeWithTag("launcher_search").performImeAction()
        composeRule.onNodeWithTag("launcher_search").assertIsDisplayed()
        composeRule.onNodeWithTag("launcher_open_drawer").performClick()

        composeRule.runOnUiThread { fontScale.value = 1.5f }
        composeRule.onNodeWithTag("launcher_emergency_pause").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_open_coach").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("v62-home-font-1_5-${widthDp}dp.png")
        composeRule.runOnUiThread { fontScale.value = 2f }
        composeRule.onNodeWithTag("launcher_emergency_pause").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_more").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("v62-home-font-2_0-${widthDp}dp.png")

        assertThemeTextContrast(AtlasThemePalettes.dark)
        assertThemeTextContrast(AtlasThemePalettes.amoled)
        assertThemeTextContrast(AtlasThemePalettes.light)
        assertTrue("The injected 25-minute visual Focus should remain active during capture", controller.focusSession?.remainingSeconds?.let { it in 1L..1_500L } == true)
    }

    @Test
    fun systemFontScaleTwoKeepsEmergencyControlsAccessible() {
        val expectedFontScale = InstrumentationRegistry.getArguments()
            .getString("expected_system_font_scale")?.toFloatOrNull()
            ?: error("expected_system_font_scale must be supplied by the disposable-AVD recipe")
        assertEquals("The AVD must apply the requested system font scale", expectedFontScale,
            composeRule.activity.resources.configuration.fontScale, 0.01f)

        val controller = newController()
        controller.syncLauncherHomeRole(true)
        val theme = mutableStateOf(AtlasThemePreference.DARK)
        val fontScale = mutableStateOf(composeRule.activity.resources.configuration.fontScale)
        val state = mutableStateOf(HomeAgentState.localDefault(controller))
        showHome(controller, theme, fontScale, state)

        composeRule.onNodeWithTag("launcher_emergency_pause").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_open_drawer").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_open_coach").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_more").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertTrue("The actual system font scale must stay at 2x",
            composeRule.activity.resources.configuration.fontScale >= 2.0f)
        capture("v623-home-system-font-2_0.png")
    }

    @Test
    fun screenshotCapturePipelineProducesPng() {
        composeRule.setContent {
            AtlasTheme {
                androidx.compose.material3.Text("Capture de test synthétique")
            }
        }
        composeRule.onNodeWithText("Capture de test synthétique").assertIsDisplayed()
        capture("v623-screenshot-pipeline.png")
        assertTrue("The dedicated screenshot pipeline must produce a valid PNG: ${captureFailures.joinToString("; ")}",
            captureFailures.isEmpty())
    }

    @Test
    fun landscapeHomeKeepsPauseAndFooterOutsideScrollableContent() {
        val controller = newController()
        controller.syncLauncherHomeRole(true)
        try {
            composeRule.runOnUiThread {
                composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            val theme = mutableStateOf(AtlasThemePreference.DARK)
            val fontScale = mutableStateOf(1f)
            val state = mutableStateOf(HomeAgentState.localDefault(controller))
            showHome(controller, theme, fontScale, state)
            composeRule.waitUntil(10_000) {
                composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            composeRule.onNodeWithTag("launcher_emergency_pause").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            composeRule.onNodeWithTag("launcher_open_drawer").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            composeRule.onNodeWithTag("launcher_open_coach").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            composeRule.onNodeWithTag("launcher_more").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            composeRule.onNodeWithTag("launcher_directive_cta").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val directiveBounds = composeRule.onNodeWithTag("launcher_directive_cta").fetchSemanticsNode().boundsInRoot
            val footerBounds = composeRule.onNodeWithTag("launcher_fixed_footer").fetchSemanticsNode().boundsInRoot
            assertTrue("Scrolled directive CTA is covered by the fixed footer: CTA=$directiveBounds footer=$footerBounds", directiveBounds.bottom <= footerBounds.top)
            capture("v62-home-landscape.png")
        } finally {
            composeRule.runOnUiThread {
                composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            composeRule.waitUntil(10_000) {
                composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            }
        }
    }

    @Test
    fun focusScreenAndContrastTokensAreVerifiableWithoutGateway() {
        val controller = newController()
        controller.setCapability(AtlasController.CAP_FOCUS, true)
        controller.startFocus(FocusPlan.custom(25 * 60L).copy(taskTitle = "Session Focus de démonstration"))
        composeRule.setContent {
            AtlasTheme(AtlasThemePreference.DARK) {
                AtlasScreenFrame(
                    title = "Focus",
                    activeDestination = AtlasRoutes.FOCUS,
                    canGoBack = false,
                    emergencyPaused = controller.isEmergencyPaused,
                    onBack = {},
                    onEmergencyPause = {},
                    onNavigate = {},
                ) {
                    FocusScreen(controller, controller.revision, onChooseHabit = {})
                }
            }
        }
        composeRule.onNodeWithText("Session Focus de démonstration").assertIsDisplayed()
        capture("v62-focus-active.png")
        assertThemeTextContrast(AtlasThemePalettes.dark)
        assertThemeTextContrast(AtlasThemePalettes.amoled)
        assertThemeTextContrast(AtlasThemePalettes.light)
    }

    @Test
    fun coachScreenShowsNamedLocalFixtureWithoutRawProvenanceHash() {
        val controller = newController()
        check(controller.isAgentEnabled) { "The isolated Coach fixture requires its default local capability." }
        controller.sendAgentMessage("Fixture explicite de recette : afficher une réponse locale du Coach.")
        val assistant = controller.agentConversation.firstOrNull { it.role == AgentMessageRole.ASSISTANT }
            ?: error("The deterministic offline adapter did not persist the named Coach fixture reply.")
        check("Adaptateur local déterministe" in assistant.contextSources) {
            "The Coach capture must use the local fixture adapter without a model request."
        }
        val widthDp = InstrumentationRegistry.getArguments().getString("expected_width_dp")?.toInt()
            ?: composeRule.activity.resources.configuration.screenWidthDp
        assertEquals("Coach fixture capture must match the configured viewport", widthDp,
            composeRule.activity.resources.configuration.screenWidthDp)

        composeRule.setContent {
            AtlasTheme(AtlasThemePreference.DARK) {
                AtlasScreenFrame(
                    title = "Coach",
                    activeDestination = AtlasRoutes.MORE,
                    canGoBack = true,
                    emergencyPaused = controller.isEmergencyPaused,
                    onBack = {},
                    onEmergencyPause = {},
                    onNavigate = {},
                ) {
                    CoachScreen(controller, controller.revision)
                }
            }
        }
        composeRule.waitUntil(10_000) { composeRule.activity.hasWindowFocus() }
        composeRule.onNodeWithText("Local").assertIsDisplayed()
        composeRule.onNodeWithText(assistant.text).assertIsDisplayed()
        composeRule.onAllNodesWithText("local-demo-v1").assertCountEquals(0)
        composeRule.onAllNodesWithText("Adaptateur local déterministe").assertCountEquals(0)
        capture("v62-coach-local-fixture-${widthDp}dp.png")
    }

    private fun newController(): AtlasController {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        check(testContext.packageName != instrumentation.targetContext.packageName) {
            "Native UI fixtures must remain separate from the instrumentation package."
        }
        val targetContext = instrumentation.targetContext
        val fixtureFilesDir = File(targetContext.cacheDir, "v62-native-files")
        check(fixtureFilesDir.mkdirs() || fixtureFilesDir.isDirectory)
        val isolated = object : ContextWrapper(targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = fixtureFilesDir
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                targetContext.getSharedPreferences("v62_native_$name", mode)
        }
        isolated.getSharedPreferences("atlas_local_state", Context.MODE_PRIVATE).edit().clear().commit()
        isolated.getSharedPreferences("atlas_theme_preference", Context.MODE_PRIVATE).edit().clear().commit()
        return AtlasController(isolated, FakeClock(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime()))
    }

    private fun showHome(
        controller: AtlasController,
        theme: androidx.compose.runtime.MutableState<AtlasThemePreference>,
        fontScale: androidx.compose.runtime.MutableState<Float>,
        state: androidx.compose.runtime.MutableState<HomeAgentState>,
    ) {
        composeRule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(currentDensity.density, fontScale.value)) {
                AtlasTheme(theme.value) {
                    LauncherHomeScreen(
                        controller = controller,
                        context = InstrumentationRegistry.getInstrumentation().targetContext,
                        isHomeRoleHeld = true,
                        onRequestHomeRole = {},
                        openCoach = {},
                        openPreviousLauncher = {},
                        openHomeSettings = {},
                        agentState = state.value,
                    )
                }
            }
        }
    }

    private fun assertThemeTextContrast(palette: dev.atlas.coach.app.ui.AtlasThemePalette) {
        val failing = palette.textContrastPairs().mapNotNull { pair ->
            val ratio = wcagContrastRatio(pair.foreground, pair.background)
            if (ratio < 4.5) "${pair.name}=${"%.2f".format(java.util.Locale.ROOT, ratio)}:1" else null
        }
        assertTrue("Palette contains text pairs below 4.5:1: $failing", failing.isEmpty())
    }

    private fun capture(fileName: String) {
        composeRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val decorView = composeRule.activity.window.decorView
        val drawn = CountDownLatch(1)
        val drawListener = ViewTreeObserver.OnDrawListener { drawn.countDown() }
        composeRule.runOnUiThread {
            decorView.viewTreeObserver.addOnDrawListener(drawListener)
            decorView.invalidate()
        }
        val didDraw = drawn.await(5, TimeUnit.SECONDS)
        composeRule.runOnUiThread {
            val observer = decorView.viewTreeObserver
            if (observer.isAlive) observer.removeOnDrawListener(drawListener)
        }
        if (!didDraw) {
            recordCaptureFailure(fileName, "window did not draw within 5 seconds")
            return
        }
        instrumentation.waitForIdleSync()
        val png = try {
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("screencap -p"),
            ).use { it.readBytes() }
        } catch (error: Exception) {
            recordCaptureFailure(fileName, "screencap command failed: ${error.javaClass.simpleName}: ${error.message}")
            return
        }
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        if (png.size < signature.size || !png.copyOfRange(0, signature.size).contentEquals(signature)) {
            val prefix = png.take(24).joinToString("") { "%02x".format(it) }
            recordCaptureFailure(fileName, "invalid PNG bytes=${png.size} prefix=$prefix")
            return
        }
        try {
            val directory = File(instrumentation.targetContext.filesDir, "v62-captures")
            check(directory.mkdirs() || directory.isDirectory)
            val output = File(directory, fileName)
            output.writeBytes(png)
            check(output.length() > 0L)
            Log.i("ATLAS_UI_CAPTURE", "CAPTURE_OK file=$fileName bytes=${output.length()}")
        } catch (error: Exception) {
            recordCaptureFailure(fileName, "could not save PNG: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun recordCaptureFailure(fileName: String, reason: String) {
        val failure = "$fileName: $reason"
        captureFailures += failure
        Log.e("ATLAS_UI_CAPTURE", "CAPTURE_BLOCKED $failure")
    }
}
