package dev.atlas.coach.app

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.ui.AtlasTheme
import dev.atlas.coach.sync.relay.RelayTransportMode
import dev.atlas.coach.sync.agent.context.AgentContextCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentCoachConversationInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun disabledAgentExplainsSendGateAndOpensMandate() {
        val controller = createController()
        composeRule.runOnUiThread {
            controller.relaySettings.selectMode(RelayTransportMode.RELAY)
            controller.disableAgent()
        }
        assertFalse(controller.isAgentEnabled)

        composeRule.setContent { coachContent(controller) }
        composeRule.onNodeWithTag("agent_message_input").performScrollTo().performTextInput("test ui")
        composeRule.onNodeWithTag("agent_send_message").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("agent_conversation_availability_reason")
            .performScrollTo()
            .assertTextContains("Coach OpenClaw désactivé")
        composeRule.onNodeWithTag("agent_conversation_access_action").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(AtlasScreenId.AGENT_SETTINGS, controller.navigationState.current)
    }

    @Test
    fun disabledLocalCoachExplainsCapabilityWithoutAddingASendGate() {
        val controller = createController()
        composeRule.runOnUiThread {
            controller.relaySettings.selectMode(RelayTransportMode.OFFLINE)
            controller.setCapability(AtlasController.CAP_COACH, false)
        }
        assertTrue(controller.isAgentEnabled)

        composeRule.setContent { coachContent(controller) }
        composeRule.onNodeWithTag("agent_local_coach_disabled").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("agent_message_input").performScrollTo().performTextInput("test ui")
        composeRule.onNodeWithTag("agent_send_message").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag("agent_local_coach_settings_action").performScrollTo().performClick()
        assertEquals(AtlasScreenId.SETTINGS, controller.navigationState.current)
    }

    @Test
    fun authorizedNonEmptyDraftStaysEnabledWhenRelayNeedsPairing() {
        val controller = createController()
        composeRule.runOnUiThread {
            controller.setAgentSharedCategory(AgentContextCategory.COACH_INTERACTION.wireValue, true)
            controller.relaySettings.selectMode(RelayTransportMode.RELAY)
        }
        assertTrue(controller.isAgentEnabled)

        composeRule.setContent { coachContent(controller) }
        composeRule.onNodeWithTag("agent_send_message").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("agent_conversation_availability_reason")
            .performScrollTo()
            .assertTextContains("Saisissez un message")
        composeRule.onNodeWithTag("agent_message_input").performScrollTo().performTextInput("test ui")
        composeRule.onNodeWithTag("agent_send_message").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag("agent_conversation_transport_reason")
            .performScrollTo()
            .assertTextContains("Relais non appairé")
        composeRule.onNodeWithTag("agent_conversation_transport_action").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(AtlasScreenId.RELAY_SETTINGS, controller.navigationState.current)
    }

    @Test
    fun conversationUsesDistinctUserAndAssistantBubbles() {
        val controller = createController()
        composeRule.runOnUiThread {
            controller.setAgentSharedCategory(AgentContextCategory.COACH_INTERACTION.wireValue, true)
            controller.relaySettings.selectMode(RelayTransportMode.OFFLINE)
            assertTrue(controller.sendAgentMessage("Message utilisateur"))
        }

        composeRule.setContent { coachContent(controller) }
        composeRule.onAllNodesWithTag("agent_message_bubble_user").assertCountEquals(1)
        composeRule.onAllNodesWithTag("agent_message_bubble_assistant").assertCountEquals(1)
        composeRule.onNodeWithTag("agent_message_source").assertIsDisplayed()
    }

    @Composable
    private fun coachContent(controller: AtlasController) {
        AtlasTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                AgentCoachScreen(controller, pullOnOpen = false)
            }
        }
    }

    private fun createController(): AtlasController =
        AtlasController(isolatedContext())

    private fun isolatedContext(): Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = UUID.randomUUID().toString()
        val files = File(target.cacheDir, "agent-coach-conversation-$namespace").apply {
            check(mkdirs() || isDirectory)
        }
        return object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences("agent_coach_conversation_${namespace}_$name", mode)
        }
    }
}
