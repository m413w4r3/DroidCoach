package dev.atlas.coach.app

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.ui.AtlasRoutes
import dev.atlas.coach.app.ui.AtlasScreenFrame
import dev.atlas.coach.app.ui.AtlasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlanCalendarScrollInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun isolatedContext(): Context {
        val testPackageContext = InstrumentationRegistry.getInstrumentation().context
        return object : ContextWrapper(testPackageContext) {
            override fun getApplicationContext(): Context = this
        }
    }

    @Test
    fun planScreenComposesInsideAtlasScrollableFrame() {
        val context = isolatedContext()
        lateinit var planController: PlanController
        composeRule.runOnUiThread {
            planController = PlanController(
                directory = File(context.cacheDir, "plan-scroll-${UUID.randomUUID()}"),
            )
        }

        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                AtlasTheme {
                    AtlasScreenFrame(
                        title = "Plan du jour",
                        activeDestination = AtlasRoutes.HOME,
                        canGoBack = false,
                        emergencyPaused = false,
                        onBack = {},
                        onEmergencyPause = {},
                        onNavigate = {},
                    ) {
                        PlanScreen(planController)
                    }
                }
            }
        }

        composeRule.onAllNodesWithText("Plan du jour").assertCountEquals(2)
    }

    @Test
    fun calendarPermissionScreenComposesInsideAtlasScrollableFrame() {
        val context = isolatedContext()

        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                AtlasTheme {
                    AtlasScreenFrame(
                        title = "Diagnostic calendrier",
                        activeDestination = AtlasRoutes.MORE,
                        canGoBack = true,
                        emergencyPaused = false,
                        onBack = {},
                        onEmergencyPause = {},
                        onNavigate = {},
                    ) {
                        CalendarPermissionScreen()
                    }
                }
            }
        }

        composeRule.onAllNodesWithText("Calendrier").assertCountEquals(1)
    }
}
