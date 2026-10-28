package dev.atlas.coach.app

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.ui.AtlasTheme
import dev.atlas.coach.app.ui.AtlasThemePreference
import dev.atlas.coach.data.StateBackend
import dev.atlas.coach.sync.FileOutboxStorage
import dev.atlas.coach.sync.agent.AgentActionType
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.ProposalLevel
import dev.atlas.coach.sync.agent.ProposalV2
import dev.atlas.coach.sync.agent.context.AgentContextCategory
import dev.atlas.coach.sync.agent.inbox.AgentInboxRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CoachTextDensityInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun coachAndContextKeepConsentActionsAndProposalConfirmationWithCollapsedDetails() {
        val context = isolatedContext()
        val now = System.currentTimeMillis()
        val proposalId = UUID.randomUUID().toString()
        val inboxFile = File(context.filesDir, "atlas_agent_inbox_v2.json")
        val proposal = ProposalV2(
            schemaVersion = 2,
            proposalId = proposalId,
            idempotencyKey = "coach-density-$proposalId",
            conversationId = null,
            mandateId = AgentMandate.default().id,
            mandateVersion = AgentMandate.default().version,
            contextVersion = "coach-text-density-test",
            createdAtUtcMillis = now,
            expiresAtUtcMillis = now + 600_000L,
            actionType = AgentActionType.HOME_SET_DIRECTIVE.wireValue,
            parameters = mapOf(
                "title" to "Étape de démonstration",
                "body" to "Une proposition synthétique.",
                "primary_action" to "open_coach",
            ),
            reason = "Proposition synthétique pour vérifier la confirmation.",
            requiredLevel = ProposalLevel.CONFIRM,
        )
        assertTrue(
            "The synthetic proposal must be available to the screen",
            AgentInboxRepository(FileOutboxStorage(inboxFile)).insertLocal(proposal) != null,
        )
        val controller = AtlasController(context, stateBackendOverride = MemoryStateBackend())
        controller.enableAgent()
        controller.setCapability(AtlasController.CAP_COACH, true)
        val showingContext = mutableStateOf(false)

        composeRule.setContent {
            AtlasTheme(AtlasThemePreference.DARK) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    if (showingContext.value) {
                        CoachContextScreen(controller)
                    } else {
                        AgentCoachScreen(
                            controller = controller,
                            pullOnOpen = false,
                            onOpenContext = { showingContext.value = true },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("agent_priority_proposal_$proposalId")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Action à confirmer").performScrollTo().assertIsDisplayed()
        listOf("agent_proposal_accept", "agent_proposal_edit", "agent_proposal_postpone", "agent_proposal_reject")
            .forEach { tag -> composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp) }
        composeRule.onNodeWithTag("agent_open_context")
            .performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithText("Contexte du coach").assertIsDisplayed()

        val categoryTags = AgentContextCategory.wireValues.map { "agent_context_category_$it" }
        composeRule.onNodeWithText("Aucune catégorie autorisée · 0 élément inclus").assertIsDisplayed()
        val collapsedCategoryTextCount = categoryTags.count { tag ->
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("agent_context_categories_toggle")
            .performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        val expandedCategoryTextCount = categoryTags.count { tag ->
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "Context per-category text should be reduced by default and available on demand: collapsed=$collapsedCategoryTextCount expanded=$expandedCategoryTextCount",
            collapsedCategoryTextCount < expandedCategoryTextCount,
        )
        composeRule.onNodeWithTag("agent_context_category_focus").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("Autoriser une demande ponctuelle de position approximative")
            .performScrollTo().assertIsDisplayed()
        val checkboxMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
        val consentCheckboxes = composeRule.onAllNodes(checkboxMatcher, useUnmergedTree = true)
        assertEquals("Location request, summary, and mobility consents stay available", 3, consentCheckboxes.fetchSemanticsNodes().size)
        consentCheckboxes[0].assertHeightIsAtLeast(48.dp)
        consentCheckboxes[0].performClick()
        assertTrue("The coarse location consent remains actionable", controller.locationPanelState.coarseRequestConsent)
        composeRule.onNodeWithText("Demander ma position approximative").performScrollTo().assertIsEnabled()

        composeRule.onNodeWithText("Autoriser séparément le partage du résumé avec le Coach")
            .performScrollTo().assertIsDisplayed()
        composeRule.onAllNodes(checkboxMatcher, useUnmergedTree = true)[1].performClick()
        assertTrue("The separate location summary consent remains actionable", controller.locationPanelState.coachSummaryConsent)
    }

    private fun isolatedContext(): Context {
        val instrumentationContext = InstrumentationRegistry.getInstrumentation().context
        check(instrumentationContext.packageName != InstrumentationRegistry.getInstrumentation().targetContext.packageName)
        val files = File(instrumentationContext.cacheDir, "coach-text-density-${UUID.randomUUID()}/files")
        check(files.mkdirs() || files.isDirectory)
        return object : ContextWrapper(instrumentationContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getSharedPreferences(name: String, mode: Int) =
                instrumentationContext.getSharedPreferences("coach_text_density_$name", mode)
        }
    }

    private class MemoryStateBackend : StateBackend {
        private var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) {
            this.value = value
        }
    }
}
