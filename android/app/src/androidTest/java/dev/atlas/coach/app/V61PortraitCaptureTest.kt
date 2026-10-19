package dev.atlas.coach.app

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.ui.AtlasRoutes
import dev.atlas.coach.app.ui.AtlasScreenFrame
import dev.atlas.coach.app.ui.AtlasTheme
import dev.atlas.coach.sync.SyncDataCategory
import dev.atlas.coach.sync.agent.AgentActionType
import dev.atlas.coach.sync.agent.ProposalLevel
import dev.atlas.coach.sync.agent.ProposalState
import dev.atlas.coach.sync.agent.ProposalV2
import dev.atlas.coach.sync.agent.inbox.AgentInboxEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class V61PortraitCaptureTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private enum class CaptureScreen { SYNC, PROPOSAL }

    @Test
    fun capturesSyncConsentAndUserFacingDirectiveEdit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val stateContext = isolatedContext()
        val controller = AtlasController(stateContext)
        controller.setSyncEnabled(true)
        controller.setSyncCategory(SyncDataCategory.COACH_INTERACTION, true)
        val screen = mutableStateOf(CaptureScreen.SYNC)
        val orientation = AtomicInteger(Configuration.ORIENTATION_UNDEFINED)
        val fixture = directiveFixture(controller.agentMandate.id, System.currentTimeMillis())

        composeRule.setContent {
            val configuration = LocalConfiguration.current
            SideEffect { orientation.set(configuration.orientation) }
            AtlasTheme {
                when (screen.value) {
                    CaptureScreen.SYNC -> AtlasScreenFrame(
                        title = "Partage backend",
                        activeDestination = AtlasRoutes.MORE,
                        canGoBack = false,
                        emergencyPaused = controller.isEmergencyPaused,
                        onBack = {},
                        onEmergencyPause = {},
                        onNavigate = {},
                    ) {
                        SyncSettingsScreen(controller, controller.revision)
                    }
                    CaptureScreen.PROPOSAL -> AtlasScreenFrame(
                        title = "Coach",
                        activeDestination = AtlasRoutes.MORE,
                        canGoBack = false,
                        emergencyPaused = controller.isEmergencyPaused,
                        onBack = {},
                        onEmergencyPause = {},
                        onNavigate = {},
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AgentCoachScreen(controller, pullOnOpen = false)
                            ProposalEditDialog(entry = fixture, onDismiss = {}, onSave = {})
                        }
                    }
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            orientation.get() == Configuration.ORIENTATION_PORTRAIT
        }
        composeRule.onNodeWithText("Activée · configuration requise").assertIsDisplayed()
        composeRule.onNodeWithTag("sync_enabled_switch").assertIsOn()
        composeRule.onNodeWithText("Réponses du coach").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Réponses du coach").assertIsOn()
        capture("20-sync-status-after-consent.png")

        composeRule.runOnUiThread { screen.value = CaptureScreen.PROPOSAL }
        composeRule.waitForIdle()
        assertEquals(Configuration.ORIENTATION_PORTRAIT, orientation.get())
        composeRule.onNodeWithTag("agent_edit_field_primary_action")
            .assertIsDisplayed()
            .assertTextEquals("Action principale", "Ouvrir le Coach")
        composeRule.onAllNodesWithText("open_coach").assertCountEquals(0)
        capture("22-openclaw-directive-edit.png")
    }

    private fun isolatedContext(): Context {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        check(testContext.packageName != instrumentation.targetContext.packageName) {
            "Capture fixtures must not write into application preferences."
        }
        return object : ContextWrapper(testContext) {
            override fun getApplicationContext(): Context = this
        }
    }

    private fun directiveFixture(mandateId: String, nowUtcMillis: Long): AgentInboxEntry {
        val proposal = ProposalV2(
            schemaVersion = 2,
            proposalId = UUID.randomUUID().toString(),
            idempotencyKey = "v61-directive-capture",
            conversationId = null,
            mandateId = mandateId,
            mandateVersion = 1L,
            contextVersion = "v61-local-capture-fixture",
            createdAtUtcMillis = nowUtcMillis,
            expiresAtUtcMillis = nowUtcMillis + 600_000L,
            actionType = AgentActionType.HOME_SET_DIRECTIVE.wireValue,
            parameters = linkedMapOf(
                "body" to "Lis tranquillement un texte pendant 25 minutes.",
                "primary_action" to "open_coach",
                "title" to "Lecture calme — 25 minutes",
            ),
            reason = "Fixture locale déterministe pour la revue visuelle.",
            requiredLevel = ProposalLevel.CONFIRM,
            state = ProposalState.PROPOSED,
        )
        return AgentInboxEntry(proposal)
    }

    private fun capture(fileName: String) {
        composeRule.waitForIdle()
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/$fileName"),
        ).bufferedReader().use { it.readText() }
    }
}
