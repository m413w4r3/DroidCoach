package dev.atlas.coach.app

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.ui.AtlasTheme
import dev.atlas.coach.data.StateBackend
import dev.atlas.coach.focus.FocusPreset
import dev.atlas.coach.focus.FocusStatus
import dev.atlas.coach.sync.agent.coach.AgentMessageRole
import dev.atlas.coach.sync.relay.RelayTransportMode
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoachQuickActionsInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun quickActionsRequireConfirmationApplyOneEffectAndKeepConversationUsable() {
        val backend = MemoryStateBackend()
        val controller = AtlasController(isolatedContext(), stateBackendOverride = backend)
        controller.relaySettings.selectMode(RelayTransportMode.OFFLINE)
        controller.enableAgent()
        controller.setCapability(AtlasController.CAP_COACH, true)
        controller.setCapability(AtlasController.CAP_FOCUS, true)
        controller.navigationState.selectTab(AtlasScreenId.COACH)

        composeRule.setContent {
            AtlasTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    AgentCoachScreen(controller, pullOnOpen = false)
                }
            }
        }

        composeRule.onNodeWithTag("coach_quick_action_day").assertIsDisplayed()
        composeRule.onNodeWithTag("coach_quick_action_goal").assertIsDisplayed()
        composeRule.onNodeWithTag("coach_quick_action_focus").assertIsDisplayed()

        composeRule.onNodeWithTag("coach_quick_action_day").performClick()
        composeRule.onNodeWithTag("coach_day_start").assertTextEquals("09:00")
        composeRule.onNodeWithTag("coach_day_end").assertTextEquals("18:00")
        composeRule.onNodeWithTag("coach_day_review").performClick()
        val dayMessage = "Organise ma journée de 09:00 à 18:00. Propose un planning réaliste avec des pauses."
        composeRule.onNodeWithTag("coach_quick_confirmation_effect").assertIsDisplayed()
        composeRule.onNodeWithText(dayMessage).assertIsDisplayed()
        assertTrue(controller.agentConversation.isEmpty())
        composeRule.onNodeWithTag("coach_quick_cancel").performClick()
        assertTrue("Cancel leaves the conversation unchanged", controller.agentConversation.isEmpty())

        composeRule.onNodeWithTag("coach_quick_action_day").performClick()
        composeRule.onNodeWithTag("coach_day_review").performClick()
        composeRule.onNodeWithTag("coach_quick_confirm").performClick()
        composeRule.waitForIdle()
        assertEquals(1, controller.agentConversation.count { it.role == AgentMessageRole.USER })
        assertEquals(dayMessage, controller.agentConversation.single { it.role == AgentMessageRole.USER }.text)

        composeRule.onNodeWithTag("coach_quick_action_goal").performScrollTo().performClick()
        composeRule.onNodeWithTag("coach_goal_title").performTextReplacement("Objectif synthétique")
        composeRule.onNodeWithTag("coach_goal_target").assertTextEquals("120")
        composeRule.onNodeWithTag("coach_goal_next").performClick()
        composeRule.onNodeWithTag("coach_goal_next_action").performTextReplacement("Lire la première page")
        composeRule.onNodeWithTag("coach_goal_review").performClick()
        assertTrue("Review does not create a goal", controller.coachGoals.isEmpty())
        composeRule.onNodeWithTag("coach_quick_confirmation_effect").assertIsDisplayed()
        assertTrue(confirmationEffect().contains("Objectif synthétique"))
        assertTrue(confirmationEffect().contains("120 minutes Focus"))
        composeRule.onNodeWithTag("coach_quick_cancel").performClick()
        assertTrue("Cancel creates no goal", controller.coachGoals.isEmpty())

        composeRule.onNodeWithTag("coach_quick_action_goal").performScrollTo().performClick()
        composeRule.onNodeWithTag("coach_goal_title").performTextReplacement("Objectif synthétique")
        composeRule.onNodeWithTag("coach_goal_next").performClick()
        composeRule.onNodeWithTag("coach_goal_next_action").performTextReplacement("Lire la première page")
        composeRule.onNodeWithTag("coach_goal_review").performClick()
        composeRule.onNodeWithTag("coach_quick_confirm").performClick()
        composeRule.waitForIdle()
        assertEquals(1, controller.coachGoals.size)
        assertEquals("Objectif synthétique", controller.coachGoals.single().record.title)
        assertEquals(120L, controller.coachGoals.single().record.targetValue)

        val directiveTitle = controller.directive.title
        composeRule.onNodeWithTag("coach_quick_action_focus").performScrollTo().performClick()
        composeRule.onNodeWithTag("coach_focus_task").assertTextEquals(directiveTitle)
        composeRule.onNodeWithTag("coach_focus_preset").performClick()
        composeRule.onNodeWithText("10/2").performClick()
        composeRule.onNodeWithTag("coach_focus_cycles").performTextReplacement("2")
        composeRule.onNodeWithTag("coach_focus_review").performClick()
        assertNull("Review does not start focus", controller.focusSession)
        assertEquals(AtlasScreenId.COACH, controller.navigationState.current)
        assertTrue(confirmationEffect().contains("2 cycles"))
        assertTrue(confirmationEffect().contains("10 min de concentration"))
        assertTrue(confirmationEffect().contains("Tâche : «$directiveTitle»"))
        composeRule.onNodeWithTag("coach_quick_cancel").performClick()
        assertNull("Cancel does not start focus", controller.focusSession)
        assertEquals(AtlasScreenId.COACH, controller.navigationState.current)

        composeRule.onNodeWithTag("coach_quick_action_focus").performScrollTo().performClick()
        composeRule.onNodeWithTag("coach_focus_preset").performClick()
        composeRule.onNodeWithText("10/2").performClick()
        composeRule.onNodeWithTag("coach_focus_cycles").performTextReplacement("2")
        composeRule.onNodeWithTag("coach_focus_review").performClick()
        composeRule.onNodeWithTag("coach_quick_confirm").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { controller.focusSession?.status == FocusStatus.RUNNING }
        assertEquals(FocusPreset.VARIANT_10_2.presetId, controller.focusSession?.presetId)
        assertEquals(2, controller.focusSession?.totalWorkCycles)
        assertEquals(directiveTitle, controller.focusSession?.taskTitle)
        assertEquals(AtlasScreenId.FOCUS, controller.navigationState.current)

        composeRule.runOnUiThread { controller.navigationState.selectTab(AtlasScreenId.COACH) }
        composeRule.onNodeWithTag("agent_message_input").performScrollTo().performTextInput("Suivi synthétique")
        composeRule.onNodeWithTag("agent_send_message").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(2, controller.agentConversation.count { it.role == AgentMessageRole.USER })
        assertEquals("Suivi synthétique", controller.agentConversation.last { it.role == AgentMessageRole.USER }.text)
        assertEquals(1, controller.coachGoals.size)
    }

    @Test
    fun focusQuickActionExplainsDisabledCapabilityWithoutStartingOrNavigating() {
        val controller = AtlasController(isolatedContext(), stateBackendOverride = MemoryStateBackend())
        controller.setCapability(AtlasController.CAP_FOCUS, false)

        composeRule.setContent {
            AtlasTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    AgentCoachScreen(controller, pullOnOpen = false)
                }
            }
        }

        composeRule.onNodeWithTag("coach_quick_action_focus").performClick()
        composeRule.onNodeWithTag("coach_quick_action_status").assertIsDisplayed()
        composeRule.onNodeWithText("Focus désactivé dans les paramètres.").assertIsDisplayed()
        assertNull(controller.focusSession)
        assertEquals(AtlasScreenId.HOME, controller.navigationState.current)
    }

    private fun confirmationEffect(): String = composeRule.onNodeWithTag("coach_quick_confirmation_effect")
        .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
        .joinToString(" ") { it.text }

    private fun isolatedContext(): Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = UUID.randomUUID().toString()
        val files = File(target.cacheDir, "coach-quick-actions-$namespace").apply {
            check(mkdirs() || isDirectory)
        }
        return object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences("coach_quick_actions_${namespace}_$name", mode)
        }
    }

    private class MemoryStateBackend : StateBackend {
        private var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) {
            this.value = value
        }
        override fun quarantine(rawValue: String, reason: String) = true
    }
}
