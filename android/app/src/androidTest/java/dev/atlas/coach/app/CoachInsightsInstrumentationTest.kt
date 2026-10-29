package dev.atlas.coach.app

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.ui.AtlasTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CoachInsightsInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun coachOpensConversationAndLocalInsightPeriodsRemainAccessible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val isolatedContext = object : ContextWrapper(instrumentation.context) {
            override fun getApplicationContext(): Context = this
        }
        lateinit var controller: AtlasController
        composeRule.runOnUiThread {
            isolatedContext.getSharedPreferences("atlas_local_state", Context.MODE_PRIVATE).edit().clear().commit()
            controller = AtlasController(isolatedContext)
        }
        val planController = PlanController(File(targetContext.cacheDir, "coach-insights-instrumentation-${System.nanoTime()}"))

        composeRule.setContent {
            AtlasTheme {
                AtlasApp(
                    controller = controller,
                    planController = planController,
                    context = targetContext,
                    requestHomeRole = {},
                    openSystemHome = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Ouvrir Coach").performClick()
        composeRule.onNodeWithTag("coach_conversation_tab").assertIsSelected()
        composeRule.onNodeWithTag("agent_open_context").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("coach_insights_tab").performScrollTo().performClick()
        composeRule.onNodeWithTag("coach_insights_period_day").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("coach_insights_insufficient").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Données insuffisantes").assertIsDisplayed()
        composeRule.onNodeWithText("Aucune donnée disponible pour le jour courant.")
            .assertIsDisplayed()

        composeRule.onNodeWithTag("coach_insights_period_week").performScrollTo().performClick()
        composeRule.onNodeWithTag("coach_insights_period_week").assertIsSelected()
        composeRule.onNodeWithTag("coach_insights_period_month").performScrollTo().performClick()
        composeRule.onNodeWithTag("coach_insights_period_month").assertIsSelected()

        composeRule.onNodeWithTag("coach_conversation_tab").performScrollTo().performClick()
        composeRule.onNodeWithTag("coach_conversation_tab").assertIsSelected()
        composeRule.onNodeWithTag("agent_open_context").performScrollTo().assertIsDisplayed()
    }
}
