package dev.atlas.coach.app

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.ui.AtlasTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class HomeLandscapeFooterInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun directiveAndCtaCanScrollAboveTheFixedFooterInLandscape() = verifyLandscapeFooter(fontScale = 1f)

    @Test
    fun directiveAndCtaCanScrollAboveTheFixedFooterAtFontScaleTwo() = verifyLandscapeFooter(fontScale = 2f)

    private fun verifyLandscapeFooter(fontScale: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val testPackageContext = instrumentation.context
        check(testPackageContext.packageName != appContext.packageName) {
            "The footer test must keep its state in the instrumentation package."
        }
        val stateContext = object : ContextWrapper(testPackageContext) {
            override fun getApplicationContext(): Context = this
        }
        stateContext.getSharedPreferences("atlas_local_state", Context.MODE_PRIVATE).edit().clear().commit()
        val controller = AtlasController(stateContext)
        val orientation = AtomicInteger(Configuration.ORIENTATION_UNDEFINED)

        composeRule.setContent {
            val configuration = LocalConfiguration.current
            val density = LocalDensity.current
            SideEffect { orientation.set(configuration.orientation) }
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AtlasTheme {
                    LauncherHomeScreen(
                        controller = controller,
                        context = appContext,
                        isHomeRoleHeld = true,
                        onRequestHomeRole = {},
                        openCoach = {},
                        openPreviousLauncher = {},
                        openHomeSettings = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, orientation.get())

        val scrollRegion = composeRule.onNodeWithTag("launcher_home_scroll_region")
        val footer = composeRule.onNodeWithTag("launcher_fixed_footer")
        val initialScrollBounds = scrollRegion.fetchSemanticsNode().boundsInRoot
        val initialFooterBounds = footer.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Scrollable content must end before the fixed footer (fontScale=$fontScale).",
            initialScrollBounds.bottom <= initialFooterBounds.top + 1f,
        )

        composeRule.onNodeWithTag("launcher_directive_cta").performScrollTo().assertIsDisplayed()
        val ctaBounds = composeRule.onNodeWithTag("launcher_directive_cta").fetchSemanticsNode().boundsInRoot
        val footerTop = footer.fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "The directive CTA must be fully above the footer after scrolling (fontScale=$fontScale).",
            ctaBounds.bottom <= footerTop + 1f,
        )

        composeRule.onNodeWithTag("launcher_emergency_pause").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_previous_launcher").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_home_settings").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_open_coach").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val footerHeightDp = footer.fetchSemanticsNode().boundsInRoot.height / appContext.resources.displayMetrics.density
        assertTrue("Compact footer should stay short (was ${footerHeightDp}dp).", footerHeightDp <= 140f)
    }
}
