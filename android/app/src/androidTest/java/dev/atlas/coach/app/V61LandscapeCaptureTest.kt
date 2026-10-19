package dev.atlas.coach.app

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.ui.AtlasTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class V61LandscapeCaptureTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capturesHomeWithDirectiveCtaAboveFixedFooter() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val testContext = instrumentation.context
        check(testContext.packageName != appContext.packageName) {
            "The capture fixture must not write into application preferences."
        }
        val stateContext = object : ContextWrapper(testContext) {
            override fun getApplicationContext(): Context = this
        }
        val controller = AtlasController(stateContext)
        val homeFixture = HomeAgentState(
            directive = HomeDirective(
                title = "Lire un chapitre",
                body = "Lis tranquillement un texte pendant 25 minutes, puis fais une courte pause.",
                primaryAction = HomePrimaryAction.OPEN_COACH,
            ),
        )
        val orientation = AtomicInteger(Configuration.ORIENTATION_UNDEFINED)

        composeRule.setContent {
            val configuration = LocalConfiguration.current
            SideEffect { orientation.set(configuration.orientation) }
            AtlasTheme {
                LauncherHomeScreen(
                    controller = controller,
                    context = appContext,
                    isHomeRoleHeld = true,
                    onRequestHomeRole = {},
                    openCoach = {},
                    openPreviousLauncher = {},
                    openHomeSettings = {},
                    agentState = homeFixture,
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            orientation.get() == Configuration.ORIENTATION_LANDSCAPE
        }
        composeRule.onNodeWithTag("launcher_directive_cta").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("launcher_emergency_pause").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_previous_launcher").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_home_settings").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_open_coach").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("16-home-landscape.png")
    }

    private fun capture(fileName: String) {
        composeRule.waitForIdle()
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/$fileName"),
        ).bufferedReader().use { it.readText() }
    }
}
