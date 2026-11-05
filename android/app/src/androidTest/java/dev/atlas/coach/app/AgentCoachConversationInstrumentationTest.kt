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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.ui.AtlasTheme
import dev.atlas.coach.sync.AndroidSyncStorage
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.context.AgentContextDomainConsent
import dev.atlas.coach.sync.agent.coach.AgentChatV4Sharing
import dev.atlas.coach.sync.agent.coach.AgentChatClient
import dev.atlas.coach.sync.agent.coach.AgentChatResponse
import dev.atlas.coach.sync.agent.coach.AgentChatSendState
import dev.atlas.coach.sync.agent.coach.AgentRequestContext
import dev.atlas.coach.sync.agent.coach.RelayAgentChatClient
import dev.atlas.coach.sync.agent.coach.RelayChatRequestExecutor
import dev.atlas.coach.sync.agent.coach.AgentStatus
import dev.atlas.coach.sync.relay.RelayCallResult
import dev.atlas.coach.sync.relay.RelayTransportMode
import dev.atlas.coach.sync.relay.AndroidRelaySettingsStore
import dev.atlas.coach.sync.relay.KeystoreRelayCredentialStore
import dev.atlas.coach.sync.agent.context.AgentContextCategory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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

    @Test
    fun newConversationArchivesAndRestoresThePreviousThreadWithoutChangingOtherStores() {
        val context = isolatedContext()
        val controller = AtlasController(context)
        composeRule.runOnUiThread {
            controller.setAgentSharedCategory(AgentContextCategory.COACH_INTERACTION.wireValue, true)
            controller.relaySettings.selectMode(RelayTransportMode.OFFLINE)
            assertTrue(controller.sendAgentMessage("Message synthétique à archiver"))
        }
        val archivedId = requireNotNull(controller.currentAgentConversationId)
        val archivedMessages = controller.agentConversation.toList()
        val consentsBefore = AndroidSyncStorage.v4(context).consents()
        val mandateBefore = JSONObject(controller.exportAllData()).getString("agentMandate")
        val relaySettingsBefore = AndroidRelaySettingsStore(context).read()
        val credentialsBefore = KeystoreRelayCredentialStore(context, AndroidRelaySettingsStore(context)).credentials()
        val plansBefore = context.filesDir.listFiles()
            .orEmpty()
            .filter { it.name.startsWith("day-plan-") }
            .associate { it.name to it.readBytes().contentHashCode() }

        assertTrue(controller.startNewAgentConversation())

        val newId = requireNotNull(controller.currentAgentConversationId)
        assertFalse(archivedId == newId)
        assertTrue(controller.agentConversation.isEmpty())
        assertEquals(archivedMessages, controller.agentConversationArchives.single().messages)
        assertEquals(archivedId, controller.agentConversationArchives.single().conversationId)
        assertEquals(consentsBefore, AndroidSyncStorage.v4(context).consents())
        assertEquals(mandateBefore, JSONObject(controller.exportAllData()).getString("agentMandate"))
        assertEquals(relaySettingsBefore, AndroidRelaySettingsStore(context).read())
        assertEquals(credentialsBefore, KeystoreRelayCredentialStore(context, AndroidRelaySettingsStore(context)).credentials())
        assertEquals(
            plansBefore,
            context.filesDir.listFiles().orEmpty()
                .filter { it.name.startsWith("day-plan-") }
                .associate { it.name to it.readBytes().contentHashCode() },
        )

        val restoredController = AtlasController(context)
        assertEquals(newId, restoredController.currentAgentConversationId)
        assertTrue(restoredController.agentConversation.isEmpty())
        assertEquals(archivedMessages, restoredController.agentConversationArchives.single().messages)
    }

    @Test
    fun newConversationIsDisabledAndRejectedWhileAChatTurnIsSending() {
        val controller = createController()
        val blockingClient = BlockingChatClient()
        composeRule.runOnUiThread {
            controller.setAgentSharedCategory(AgentContextCategory.COACH_INTERACTION.wireValue, true)
            controller.relaySettings.selectMode(RelayTransportMode.RELAY)
            controller.setRelayAgentChatClientForTesting(blockingClient)
            assertTrue(controller.sendAgentMessage("Message unique"))
        }
        assertTrue(blockingClient.started.await(5, TimeUnit.SECONDS))
        val before = controller.agentConversation.toList()
        val previousId = controller.currentAgentConversationId

        composeRule.setContent { coachContent(controller) }
        composeRule.onNodeWithTag("agent_new_conversation").assertIsNotEnabled()
        assertFalse(controller.startNewAgentConversation())
        assertEquals(before, controller.agentConversation)
        assertEquals(previousId, controller.currentAgentConversationId)

        blockingClient.release.countDown()
    }

    @Test
    fun nextV4RequestUsesTheFreshConversationId() {
        val context = isolatedContext()
        val controller = AtlasController(context)
        lateinit var archivedConversationId: String
        composeRule.runOnUiThread {
            controller.relaySettings.selectMode(RelayTransportMode.OFFLINE)
            assertTrue(controller.sendAgentMessage("Ancien fil local"))
            archivedConversationId = requireNotNull(controller.currentAgentConversationId)
            assertTrue(controller.startNewAgentConversation())
            controller.relaySettings.setV4ChatEnabled(true)
            controller.relaySettings.selectMode(RelayTransportMode.RELAY)
        }
        val newConversationId = requireNotNull(controller.currentAgentConversationId)
        assertFalse(archivedConversationId == newConversationId)

        val requestBody = AtomicReference<JSONObject?>()
        val dispatched = CountDownLatch(1)
        val sharing = AgentChatV4Sharing.EMPTY
        val relayV4Client = RelayAgentChatClient(
            executor = RelayChatRequestExecutor { operation, _, _, payloadFactory, beforeDispatch, requestId ->
                assertEquals(RelayAgentChatClient.V4_CHAT_OPERATION, operation)
                assertTrue(beforeDispatch())
                requestBody.set(payloadFactory())
                dispatched.countDown()
                val id = requireNotNull(requestId)
                RelayCallResult.Success(
                    JSONObject()
                        .put("contract_version", 4)
                        .put("schema_version", 4)
                        .put("request_id", id)
                        .put("reply", "Réponse synthétique.")
                        .put("proposal_ids", JSONArray())
                        .put("agent_status", "ok")
                        .put("tool_state", "none")
                        .put("tool_call_count", 0),
                    replayed = false,
                )
            },
            freshRequestContext = { snapshot ->
                AgentRequestContext(
                    mandate = controller.agentMandate,
                    snapshot = snapshot,
                    consentAtUtcMillis = emptyMap(),
                    domainConsent = AgentContextDomainConsent(),
                    nowUtcMillis = System.currentTimeMillis(),
                )
            },
            onContextWritten = { true },
            v4ChatEnabled = true,
            v4Sharing = { sharing },
            v4SharingStillAuthorized = { it == sharing },
        )
        controller.setRelayAgentChatClientForTesting(relayV4Client)
        composeRule.runOnUiThread { assertTrue(controller.sendAgentMessage("Nouveau fil V4")) }

        assertTrue("the V4 request must reach the relay scheduler", dispatched.await(5, TimeUnit.SECONDS))
        val sentBody = requireNotNull(requestBody.get())
        assertEquals(4, sentBody.getInt("contract_version"))
        assertEquals(newConversationId, sentBody.getString("conversation_id"))
        assertFalse(archivedConversationId == sentBody.getString("conversation_id"))
        assertTrue(sentBody.has("conversation_id"))
    }

    @Test
    fun secondTriggerDuringOneSendingTurnAddsOneUserMessageAndOneRequest() {
        val controller = createController()
        val blockingClient = BlockingChatClient()
        composeRule.runOnUiThread {
            controller.setAgentSharedCategory(AgentContextCategory.COACH_INTERACTION.wireValue, true)
            controller.relaySettings.selectMode(RelayTransportMode.RELAY)
            controller.setRelayAgentChatClientForTesting(blockingClient)
            assertTrue(controller.sendAgentMessage("Même geste"))
        }
        assertTrue(blockingClient.started.await(5, TimeUnit.SECONDS))

        assertFalse(controller.sendAgentMessage("Même geste"))
        assertEquals(1, controller.agentConversation.count { it.role.name == "USER" })
        assertEquals(1, blockingClient.calls.get())

        composeRule.setContent { coachContent(controller) }
        composeRule.onNodeWithTag("agent_send_message").assertIsNotEnabled()
        blockingClient.release.countDown()
    }

    @Test
    fun v4OptInSurvivesColdStartAndModeExitWithoutNegotiatingOrChangingConsent() {
        val context = isolatedContext()
        val controller = AtlasController(context)
        val consentsBefore = AndroidSyncStorage.v4(context).consents()
        composeRule.runOnUiThread {
            controller.relaySettings.setV4ChatEnabled(true)
            controller.relaySettings.selectMode(RelayTransportMode.OFFLINE)
        }

        val restoredController = AtlasController(context)
        assertTrue(restoredController.relaySettings.v4ChatEnabled)
        assertFalse(restoredController.relaySettings.v4ChatNegotiated)
        assertEquals(consentsBefore, AndroidSyncStorage.v4(context).consents())
        composeRule.runOnUiThread { restoredController.relaySettings.selectMode(RelayTransportMode.RELAY) }
        assertTrue(restoredController.relaySettings.v4ChatEnabled)
        assertFalse(restoredController.relaySettings.v4ChatNegotiated)
        assertEquals(consentsBefore, AndroidSyncStorage.v4(context).consents())
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

    private class BlockingChatClient : AgentChatClient {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()

        override fun chat(
            conversationId: String?,
            message: String,
            mandate: AgentMandate,
            context: dev.atlas.coach.sync.agent.context.AgentContextSnapshot,
            idempotencyKey: String,
        ): AgentChatResponse {
            calls.incrementAndGet()
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            return AgentChatResponse("Réponse synthétique", emptyList(), AgentStatus.CONNECTED)
        }
    }
}
