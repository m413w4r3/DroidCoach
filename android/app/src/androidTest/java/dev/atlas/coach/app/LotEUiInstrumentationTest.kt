package dev.atlas.coach.app

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.ui.AtlasTheme
import dev.atlas.coach.app.ui.AtlasThemePreference
import dev.atlas.coach.data.StateBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import dev.atlas.coach.sync.agent.AgentActionType
import dev.atlas.coach.sync.agent.ProposalLevel
import dev.atlas.coach.sync.agent.ProposalV2
import dev.atlas.coach.sync.agent.dispatch.AgentEffectReconciliation
import android.os.ParcelFileDescriptor

@RunWith(AndroidJUnit4::class)
class LotEUiInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val isolatedContext: Context by lazy {
        val testPackageContext = InstrumentationRegistry.getInstrumentation().context
        object : ContextWrapper(testPackageContext) {
            override fun getApplicationContext(): Context = this
        }
    }

    @Test
    fun capturesSyntheticHomeInDarkAndAmoledThemes() {
        resetIsolatedState()
        val controller = AtlasController(isolatedContext)
        val preference = mutableStateOf(AtlasThemePreference.DARK)
        val syntheticState = HomeAgentState(
            directive = HomeDirective(
                title = "Lire quelques pages puis avancer sur une petite étape",
                body = "Un pas concret, préparé localement et disponible hors ligne.",
                primaryAction = HomePrimaryAction.START_FOCUS,
            ),
            focusCard = HomeAgentCard("Prochain focus", "25 minutes de concentration, pause de 5 minutes.", "Voir le Focus", HomeCardAction.OPEN_FOCUS),
            contentCard = HomeAgentCard("Suggestion de contenu", "Lecture de démonstration", "Ouvrir la bibliothèque", HomeCardAction.OPEN_LIBRARY),
            agentStatus = HomeAgentStatus.LOCAL_ONLY,
        )

        composeRule.setContent {
            AtlasTheme(preference.value) {
                LauncherHomeScreen(
                    controller = controller,
                    context = isolatedContext,
                    isHomeRoleHeld = true,
                    onRequestHomeRole = {},
                    openCoach = {},
                    openFocus = {},
                    openLibrary = {},
                    openPreviousLauncher = {},
                    openHomeSettings = {},
                    agentState = syntheticState,
                )
            }
        }

        composeRule.onNodeWithContentDescription(
            "Directive : Lire quelques pages puis avancer sur une petite étape. Afficher le détail.",
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("launcher_directive_status").assertIsDisplayed()
        composeRule.onNodeWithText("Voir le Focus")
            .performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onAllNodesWithText("Suggestion de contenu").assertCountEquals(0)
        composeRule.waitForIdle()
        shell("screencap -p /sdcard/v6-home-dark.png")

        composeRule.runOnUiThread { preference.value = AtlasThemePreference.AMOLED }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Préparer le focus").assertIsDisplayed()
        composeRule.onNodeWithText("Voir le Focus").assertIsDisplayed()
        shell("screencap -p /sdcard/v6-home-amoled.png")
    }

    @Test
    fun proposedFocusRequiresTapAndSupportsPauseResumeFinish() {
        resetIsolatedState()
        val backend = object : StateBackend {
            private var value: String? = null
            override fun read(): String? = value
            override fun write(value: String) {
                this.value = value
            }
        }
        val controller = AtlasController(isolatedContext, stateBackendOverride = backend)
        val proposed = ProposedFocusSession(
            focusMinutes = 25,
            breakMinutes = 5,
            cycles = 2,
            label = "Lecture",
            reason = "Une session courte proposée localement.",
        )
        composeRule.setContent {
            AtlasTheme(AtlasThemePreference.DARK) {
                FocusSessionScreen(controller = controller, proposedSession = proposed)
            }
        }

        composeRule.onNodeWithText("Session proposée").assertIsDisplayed()
        composeRule.onNodeWithTag("focus_task_title").assertIsDisplayed()
        composeRule.onNodeWithTag("focus_duration_value").assertTextEquals("25")
        assertEquals(null, controller.focusSession)
        composeRule.onNodeWithTag("focus_advanced_settings").performClick()
        composeRule.onNodeWithTag("focus_advanced_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("focus_advanced_done").performClick()
        composeRule.onNodeWithTag("focus_task_title").performTextReplacement("Lecture de test")
        composeRule.onNodeWithTag("focus_duration_minus").performClick()
        composeRule.onNodeWithTag("focus_pomodoro_start").performClick()
        assertNotNull(
            "Focus start was refused (enabled=${controller.capabilities[AtlasController.CAP_FOCUS]}, message=${controller.latestMessage})",
            controller.focusSession,
        )
        assertEquals("Lecture de test", controller.focusSession?.taskTitle)
        assertEquals(24L * 60L, controller.focusSession?.workSeconds)
        composeRule.onNodeWithTag("focus_phase").assertIsDisplayed()
        composeRule.onNodeWithText("Pause").assertIsDisplayed()
        composeRule.onNodeWithText("Annuler").assertIsDisplayed()
        composeRule.onNodeWithText("Terminer").assertIsDisplayed()
    }

    @Test
    fun coachContextScreenKeepsIntegrationAndAdvancedLinks() {
        resetIsolatedState()
        val controller = AtlasController(isolatedContext)
        controller.navigationState.replace(AtlasScreenId.COACH)
        val currentRoute = mutableStateOf(AtlasScreenId.COACH)
        val showingContext = mutableStateOf(false)
        composeRule.setContent {
            AtlasTheme(AtlasThemePreference.DARK) {
                androidx.compose.foundation.layout.Column(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                ) {
                    when (currentRoute.value) {
                        AtlasScreenId.AGENT_SETTINGS -> AgentSettingsScreen(controller)
                        AtlasScreenId.AGENT_SHARING -> AgentSharingScreen(controller)
                        AtlasScreenId.AGENT_EFFECTS -> AgentEffectsScreen(controller)
                        else -> if (showingContext.value) {
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
        }

        composeRule.onNodeWithText("Conversation").assertIsDisplayed()
        composeRule.onNodeWithText("Proposition prioritaire").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Objectifs").assertCountEquals(0)
        composeRule.onAllNodesWithText("Localisation").assertCountEquals(0)
        composeRule.onNodeWithTag("agent_open_context").performScrollTo().performClick()
        composeRule.onNodeWithText("Contexte du coach").assertIsDisplayed()
        composeRule.onNodeWithText("Objectifs").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Localisation").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Contexte du prochain envoi").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Tâches en arrière-plan").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("agent_settings_link").performScrollTo().performClick()
        assertEquals(AtlasScreenId.AGENT_SETTINGS, controller.navigationState.current)
        composeRule.runOnUiThread { currentRoute.value = controller.navigationState.current }
        composeRule.onNodeWithText("Mandat du Coach").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Données partagées avec le coach").assertCountEquals(0)

        composeRule.runOnUiThread {
            controller.navigationState.replace(AtlasScreenId.COACH)
            currentRoute.value = AtlasScreenId.COACH
        }
        composeRule.onNodeWithTag("agent_sharing_link").performScrollTo().performClick()
        assertEquals(AtlasScreenId.AGENT_SHARING, controller.navigationState.current)
        composeRule.runOnUiThread { currentRoute.value = controller.navigationState.current }
        composeRule.onNodeWithText("Données partagées avec le coach").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Mandat et autonomie").assertCountEquals(0)

        composeRule.runOnUiThread {
            controller.navigationState.replace(AtlasScreenId.COACH)
            currentRoute.value = AtlasScreenId.COACH
        }
        composeRule.onNodeWithTag("agent_effects_link").performScrollTo().performClick()
        assertEquals(AtlasScreenId.AGENT_EFFECTS, controller.navigationState.current)
        composeRule.runOnUiThread { currentRoute.value = controller.navigationState.current }
        composeRule.onNodeWithTag("agent_effect_history_screen").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun recreatingControllerRestoresRemoteDirectiveProvenanceAndAppliedEffectMarker() {
        resetIsolatedState()
        val firstController = AtlasController(isolatedContext)
        val now = System.currentTimeMillis()
        val proposal = ProposalV2(
            schemaVersion = 2,
            proposalId = UUID.randomUUID().toString(),
            idempotencyKey = UUID.randomUUID().toString(),
            conversationId = null,
            mandateId = firstController.agentMandate.id,
            mandateVersion = firstController.agentMandate.version,
            contextVersion = "lot-e-restart-test",
            createdAtUtcMillis = now,
            expiresAtUtcMillis = now + 600_000L,
            actionType = AgentActionType.HOME_SET_DIRECTIVE.wireValue,
            parameters = mapOf(
                "title" to "Directive durable",
                "body" to "Une étape locale persistée.",
                "primary_action" to "open_coach",
            ),
            reason = "Vérifier la persistance après reconstruction du contrôleur.",
            requiredLevel = ProposalLevel.CONFIRM,
        )
        firstController.setHomeDirective(proposal)

        val restartedController = AtlasController(isolatedContext)

        assertEquals("Directive durable", restartedController.agentHomeState.directive?.title)
        assertEquals(proposal.proposalId, restartedController.agentHomeState.directive?.sourceProposalId)
        assertEquals(
            AgentEffectReconciliation.APPLIED,
            restartedController.reconcile(proposal, proposal.idempotencyKey),
        )
    }

    @Test
    fun emergencyAndExitActionsRemainReachableAtFontScaleTwoInLandscape() {
        resetIsolatedState()
        val controller = AtlasController(isolatedContext)
        composeRule.runOnUiThread {
            composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        composeRule.waitForIdle()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AtlasTheme(AtlasThemePreference.DARK) {
                    LauncherHomeScreen(
                        controller = controller,
                        context = isolatedContext,
                        isHomeRoleHeld = true,
                        onRequestHomeRole = {},
                        openCoach = {},
                        openFocus = {},
                        openLibrary = {},
                        openPreviousLauncher = {},
                        openHomeSettings = {},
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        }
        composeRule.onNodeWithContentDescription("Activer la pause d'urgence hors ligne pendant 30 minutes").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_open_coach").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_more").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithTag("launcher_previous_launcher").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("launcher_home_settings").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    private fun resetIsolatedState() {
        // Instrumentation context belongs to the test APK, so this never clears app/user preferences.
        check(isolatedContext.packageName != InstrumentationRegistry.getInstrumentation().targetContext.packageName) {
            "Refusing to reset preferences outside the isolated instrumentation package"
        }
        isolatedContext.getSharedPreferences("atlas_local_state", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).use { descriptor -> BufferedReader(InputStreamReader(descriptor)).readText() }
}
