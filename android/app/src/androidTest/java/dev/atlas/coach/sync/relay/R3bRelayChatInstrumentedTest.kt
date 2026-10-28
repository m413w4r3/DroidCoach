package dev.atlas.coach.sync.relay

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.AtlasApplication
import dev.atlas.coach.app.AtlasController
import dev.atlas.coach.app.AtlasScreenId
import dev.atlas.coach.app.MainActivity
import dev.atlas.coach.sync.agent.AgentActionType
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.AgentMode
import dev.atlas.coach.sync.agent.coach.AgentMessageRole
import dev.atlas.coach.sync.agent.ProposalState
import dev.atlas.coach.sync.agent.coach.AgentStatus
import dev.atlas.coach.test.BackendArgumentsAwareRunner
import dev.atlas.coach.test.RequiresBackendArguments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Full app conversation over the Funnel-backed relay, with direct bearer auth removed. */
@RunWith(BackendArgumentsAwareRunner::class)
class R3bRelayChatInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    @RequiresBackendArguments(
        keys = ["relay_url", "server_id", "server_public_key", "device_token_file"],
        reason = "Relay chat needs the parent's synthetic relay identity and private device token file",
    )
    fun coachConversationUsesRelayWithoutDirectBackendFallback() {
        val arguments = requireR3bRelayArguments(includeDeviceTokenFile = true)
        val tokenFileName = requireNotNull(arguments.deviceTokenFile)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = composeRule.activity.applicationContext
        val tokenInput = readR3bDeviceToken(context, tokenFileName)
        val controller = (composeRule.activity.application as AtlasApplication).controller
        var saved: Result<Unit>? = null

        instrumentation.runOnMainSync {
            controller.clearSyncToken()
            controller.saveSyncConfiguration("", null)
            controller.relaySettings.selectMode(RelayTransportMode.DIRECT)
            saved = controller.relaySettings.saveConfiguration(
                relayUrl = arguments.relayUrl,
                serverId = arguments.serverId,
                serverPublicKey = arguments.serverPublicKey,
                deviceId = tokenInput.deviceId,
                deviceToken = tokenInput.token,
            )
        }
        assertTrue("The production relay configuration was rejected", requireNotNull(saved).isSuccess)
        deleteR3bDeviceTokenFile(context, tokenFileName)
        assertR3bTokenNotPlaintext(context, tokenInput.token)

        instrumentation.runOnMainSync {
            controller.enableAgent()
            controller.setCapability(AtlasController.CAP_AGENT, true)
            controller.setCapability(AtlasController.CAP_COACH, true)
            controller.setAgentMode(AgentMode.PROPOSALS_ONLY)
            val allowedActions = setOf("focus.propose")
            AgentActionType.entries.forEach { action ->
                controller.setAgentActionAllowed(action.wireValue, action.wireValue in allowedActions)
            }
            AgentMandate.ALLOWED_SHARED_CATEGORIES.forEach { category ->
                controller.setAgentSharedCategory(category, false)
            }
            controller.setAgentFocusWindow(
                days = (1..7).toSet(),
                start = "00:00",
                end = "23:59",
                maxMinutes = 30,
                maxSessionsPerDay = 4,
                maxChangesPerDay = 20,
            )
        }
        connectR3bRelay(controller.relaySettings)
        assertFalse("A direct backend token is configured", controller.syncTokenConfigured)
        assertEquals("A direct backend URL remains configured", "", controller.syncBaseUrl)
        assertTrue(controller.isAgentEnabled)
        assertEquals(AgentMode.PROPOSALS_ONLY, controller.agentMandate.mode)
        assertEquals(setOf("focus.propose"), controller.agentMandate.allowedActions)
        assertTrue(controller.agentMandate.sharedDataCategories.isEmpty())
        assertTrue(controller.capabilities[AtlasController.CAP_COACH] == true)

        instrumentation.runOnMainSync { controller.navigationState.selectTab(AtlasScreenId.COACH) }
        composeRule.waitForIdle()
        val assistantCountBefore = controller.agentConversation.count { it.role == AgentMessageRole.ASSISTANT }
        val proposalIdsBefore = controller.agentInbox.mapTo(mutableSetOf()) { it.id }
        val message = "R3B relay chat ${UUID.randomUUID()} : Quelle est une prochaine étape simple " +
            "pour reprendre une tâche après une courte pause ?"
        composeRule.onNodeWithTag("agent_message_input").performScrollTo().performTextInput(message)
        val sendButton = composeRule.onNodeWithTag("agent_send_message")
        sendButton.performScrollTo()
        sendButton.assertIsEnabled()
        sendButton.performClick()
        composeRule.waitUntil(timeoutMillis = 75_000) {
            controller.agentConversation.count { it.role == AgentMessageRole.ASSISTANT } > assistantCountBefore &&
                controller.agentStatus == AgentStatus.CONNECTED
        }

        assertTrue("The synthetic user message was not retained", controller.agentConversation.any {
            it.role == AgentMessageRole.USER && it.text == message
        })
        assertEquals(AgentStatus.CONNECTED, controller.agentStatus)
        assertTrue(controller.relaySettings.state == RelayConnectionState.RELAY_CONNECTED)
        val response = requireNotNull(controller.agentConversation.lastOrNull { it.role == AgentMessageRole.ASSISTANT })
        assertTrue("No assistant response was retained", response.text.isNotBlank())
        assertTrue(
            "The assistant response does not carry connected relay provenance",
            response.contextSources.contains("agent_status:connected"),
        )
        composeRule.onNodeWithTag("agent_connection_status")
            .assertTextContains("Connecté", substring = true)
        assertTrue(
            "The conversation did not show the compact OpenClaw source label",
            composeRule.onAllNodesWithText("OpenClaw", substring = false).fetchSemanticsNodes().isNotEmpty(),
        )
        composeRule.onNodeWithText(message, substring = false).assertExists()

        val returnedProposals = controller.agentInbox.filter { it.id !in proposalIdsBefore }
        assertTrue(
            "A relay response executed a proposal without the user's confirmation",
            returnedProposals.none { it.proposal.state == ProposalState.EXECUTED },
        )
        assertFalse("A backend direct-token fallback became active", controller.syncTokenConfigured)
        assertEquals("", controller.syncBaseUrl)

        val draft = "Brouillon local R3-B à conserver après rotation."
        composeRule.onNodeWithTag("agent_message_input").performScrollTo().performTextInput(draft)
        val originalOrientation = composeRule.activity.requestedOrientation
        try {
            composeRule.runOnUiThread {
                composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            composeRule.onNodeWithTag("agent_message_input").assertTextContains(draft, substring = true)
            composeRule.onNodeWithTag("agent_connection_status")
                .assertTextContains("Connecté", substring = true)
            composeRule.onNodeWithText(message, substring = false).assertExists()
            assertTrue("The conversation did not survive activity rotation", controller.agentConversation.any {
                it.role == AgentMessageRole.USER && it.text == message
            })
        } finally {
            composeRule.runOnUiThread {
                composeRule.activity.requestedOrientation = originalOrientation
            }
        }
        assertFalse("The relay chat left a direct backend token configured", controller.syncTokenConfigured)
        assertEquals("", controller.syncBaseUrl)
    }
}
