package dev.atlas.coach.app

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.ui.AtlasTheme
import dev.atlas.coach.launcher.LauncherAppModel
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherDrawerLazyInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun drawerScrollsToLastSyntheticAppAndKeepsFooterVisibleAndClickable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val testContext = instrumentation.context
        val stateContext = object : ContextWrapper(testContext) {
            override fun getApplicationContext(): Context = this
        }
        stateContext.getSharedPreferences("atlas_local_state", Context.MODE_PRIVATE).edit().clear().commit()
        lateinit var controller: AtlasController
        composeRule.runOnUiThread {
            composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            controller = AtlasController(stateContext)
        }
        val coachClicks = AtomicInteger(0)

        for (fontScale in listOf(1f, 2f)) {
            for (viewportWidthDp in listOf(320, 360)) {
                for (appCount in listOf(100, 250)) {
                    val fixture = syntheticApps(appCount)
                    composeRule.setContent {
                        val currentConfiguration = LocalConfiguration.current
                        val currentDensity = LocalDensity.current
                        val fixtureConfiguration = Configuration(currentConfiguration).apply {
                            orientation = Configuration.ORIENTATION_PORTRAIT
                            screenWidthDp = viewportWidthDp
                        }
                        CompositionLocalProvider(
                            LocalConfiguration provides fixtureConfiguration,
                            LocalDensity provides Density(currentDensity.density, fontScale),
                        ) {
                            AtlasTheme {
                                Box(Modifier.width(viewportWidthDp.dp).fillMaxHeight()) {
                                    LauncherHomeScreen(
                                        controller = controller,
                                        context = appContext,
                                        isHomeRoleHeld = true,
                                        onRequestHomeRole = {},
                                        openCoach = { coachClicks.incrementAndGet() },
                                        openPreviousLauncher = {},
                                        openHomeSettings = {},
                                        drawerAppsOverrideForTests = fixture,
                                    )
                                }
                            }
                        }
                    }

                    composeRule.onNodeWithTag("launcher_open_drawer").performClick()
                    val lastAppLabel = syntheticAppLabel(appCount)
                    composeRule.onNodeWithContentDescription("Ouvrir $lastAppLabel")
                        .performScrollTo()
                        .assertIsDisplayed()
                    composeRule.onNodeWithTag("launcher_fixed_footer").assertIsDisplayed()
                    composeRule.onNodeWithTag("launcher_open_coach").assertIsDisplayed().performClick()
                    composeRule.runOnIdle { }
                    assertEquals(
                        "Coach footer click should work with $appCount apps at ${viewportWidthDp}dp and fontScale $fontScale",
                        1,
                        coachClicks.getAndSet(0),
                    )
                }
            }
        }
    }

    private fun syntheticApps(count: Int): List<LauncherAppModel> = (1..count).map { index ->
        LauncherAppModel(
            appLabel = syntheticAppLabel(index),
            appPackage = "dev.atlas.fixture.drawer.app${index.toString().padStart(3, '0')}",
            activityClassName = null,
        )
    }

    private fun syntheticAppLabel(index: Int): String = "Synthetic app ${index.toString().padStart(3, '0')}"
}
