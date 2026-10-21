package dev.atlas.coach.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.ui.AtlasRoutes
import dev.atlas.coach.app.ui.AtlasScreenFrame
import dev.atlas.coach.app.ui.AtlasTheme
import dev.atlas.coach.focus.FakeClock
import dev.atlas.coach.focus.FocusPlan
import dev.atlas.coach.focus.FocusStatus
import dev.atlas.coach.goals.GoalMetric
import dev.atlas.coach.goals.GoalProgressCalculator
import dev.atlas.coach.sync.agent.AgentActionType
import dev.atlas.coach.sync.agent.AgentMode
import dev.atlas.coach.sync.agent.ProposalState
import dev.atlas.coach.test.BackendArgumentsAwareRunner
import dev.atlas.coach.test.RequiresBackendArguments
import dev.atlas.coach.usage.android.AndroidEmergencyPauseStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Gateway run uses an injected Focus clock; it is not a 25 minute wall-clock session. */
@RunWith(BackendArgumentsAwareRunner::class)
class V62RealGatewayContextBridgeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var controller: AtlasController? = null
    private var composedAgentController: AtlasController? = null

    @After
    fun clearEphemeralBackendCredential() {
        controller?.let { target ->
            composeRule.runOnUiThread {
                target.clearSyncToken()
                target.saveSyncConfiguration("", null)
            }
        }
    }

    @Test
    @RequiresBackendArguments(
        keys = ["backend_url", "backend_token"],
        reason = "requires the local FastAPI adapter connected to the configured OpenClaw Gateway",
    )
    fun realGatewayUsesAccelerated25MinuteFocusContextAndAcceptedProposal() {
        val args = InstrumentationRegistry.getArguments()
        val backendUrl = requireNotNull(args.getString("backend_url"))
        val backendToken = requireNotNull(args.getString("backend_token"))
        require(backendToken.isNotBlank())

        val now = System.currentTimeMillis()
        val clock = FakeClock(now, android.os.SystemClock.elapsedRealtime())
        val localController = newIsolatedController(clock)
        controller = localController

        // The two sharing grants precede goal creation and Focus input.
        composeRule.runOnUiThread {
            localController.enableAgent()
            localController.setCapability(AtlasController.CAP_COACH, true)
            localController.setCapability(AtlasController.CAP_FOCUS, true)
            localController.setAgentMode(AgentMode.PROPOSALS_ONLY)
            localController.setAgentSharedCategory("focus", true)
            localController.setAgentSharedCategory("goals", true)
            val allowed = setOf("home.set_directive", "focus.propose", "focus.start")
            AgentActionType.entries.forEach { action ->
                localController.setAgentActionAllowed(action.wireValue, action.wireValue in allowed)
            }
            localController.setAgentFocusWindow(
                days = (1..7).toSet(), start = "00:00", end = "23:59",
                maxMinutes = 30, maxSessionsPerDay = 4, maxChangesPerDay = 50,
            )
            localController.createGoal(GoalPanelDraft(
                title = "Réviser l'algèbre",
                metric = GoalMetric.FOCUS_MINUTES,
                targetValue = 120,
                description = "Objectif synthétique de recette locale.",
                nextAction = "Relire le prochain exercice.",
            ))
            localController.syncLauncherHomeRole(true)
            localController.toggleLauncherPin("com.android.settings")
        }

        val goal = localController.coachGoals.single().record
        lateinit var focusSessionId: String
        composeRule.runOnUiThread {
            localController.startFocus(
                FocusPlan.custom(25 * 60L, totalWorkCycles = 1).copy(taskTitle = "Réviser l'algèbre"),
                goalId = goal.id,
            )
            val running = requireNotNull(localController.focusSession)
            assertEquals(FocusStatus.RUNNING, running.status)
            focusSessionId = running.id
            val linkedWhileRunning = requireNotNull(localController.coachGoals.singleOrNull { it.record.id == goal.id })
            assertEquals(0L, linkedWhileRunning.progress.currentValue)
            assertTrue(running.id in linkedWhileRunning.record.linkedFocusSessionIds)

            // Verify the atomic Goal-Focus start reached the isolated durable store before time advances.
            val startedOnDisk = isolatedPersistedState()
            assertEquals(running.id, startedOnDisk.getJSONObject("focus").getString("id"))
            assertEquals("RUNNING", startedOnDisk.getJSONObject("focus").getString("status"))
            assertPersistedGoalLink(startedOnDisk, goal.id, running.id)

            clock.advanceMillis(25 * 60_000L)
            localController.tickFocus()
            val finished = requireNotNull(localController.focusSession)
            assertEquals("Injected Focus clock must finish the 25 minute work phase", FocusStatus.FINISHED, finished.status)
            assertEquals(running.id, finished.id)
        }

        val linkedGoal = localController.coachGoals.single()
        assertEquals(setOf(focusSessionId), linkedGoal.record.linkedFocusSessionIds)
        assertEquals(25L, linkedGoal.progress.currentValue)
        assertEquals(120L, linkedGoal.progress.targetValue)
        val finishedOnDisk = isolatedPersistedState()
        assertPersistedGoalLink(finishedOnDisk, goal.id, focusSessionId)
        assertEquals(120L, persistedGoal(finishedOnDisk, goal.id).getLong("targetValue"))
        val persistedHistory = finishedOnDisk.getJSONArray("focusHistory")
        val persistedFinished = (0 until persistedHistory.length()).map { index ->
            persistedHistory.getJSONObject(index)
        }.single { session ->
            session.getString("id") == focusSessionId
        }
        assertEquals("FINISHED", persistedFinished.getString("status"))
        assertEquals(
            25L,
            persistedFinished.getLong("workSeconds") * persistedFinished.getInt("completedWorkCycles") / 60L,
        )
        val beforeContext = JSONObject(localController.agentContextPreview.serializedPayload)
        val initialContext = beforeContext.getJSONObject("categories")
        assertTrue(initialContext.getJSONArray("focus").getString(0).contains("completed | 25 min"))
        val goalSummary = initialContext.getJSONArray("goals").getString(0)
        assertTrue(goalSummary.contains("cible 120"))
        assertTrue(goalSummary.contains("validé 25"))
        assertEquals(25L, GoalProgressCalculator.calculate(
            linkedGoal.record,
            localController.focusHistory,
        ).currentValue)

        localController.saveSyncConfiguration(backendUrl, backendToken)
        assertTrue("The temporary ATLAS backend bearer was not configured", localController.syncTokenConfigured)
        composeRule.runOnUiThread { localController.navigationState.selectTab(AtlasScreenId.COACH) }
        showCoach(localController)

        composeRule.onNodeWithTag("agent_next_context_payload_toggle").performScrollTo().performClick()
        composeRule.onNodeWithTag("agent_next_context_payload").assertExists()
        composeRule.onNodeWithTag("agent_next_context_payload_toggle").performScrollTo().performClick()

        val assistantCountBefore = localController.agentConversation.count { it.role.name == "ASSISTANT" }
        composeRule.onNodeWithTag("agent_message_input").performScrollTo().performTextInput(
            "En français, résume les minutes réellement validées sur mon objectif et propose une prochaine action concrète adaptée à cette progression. Utilise le Focus terminé joint au contexte.",
        )
        composeRule.onNodeWithTag("agent_send_message").performScrollTo().assertIsEnabled().performClick()
        composeRule.waitUntil(timeoutMillis = 90_000) {
            localController.agentConversation.count { it.role.name == "ASSISTANT" } > assistantCountBefore
        }
        assertEquals(
            "Android must report the real OpenClaw adapter as connected: ${localController.agentStatusMessage}",
            dev.atlas.coach.sync.agent.coach.AgentStatus.CONNECTED,
            localController.agentStatus,
        )
        val realReply = localController.agentConversation.last { it.role.name == "ASSISTANT" }.text
        assertTrue("The real Gateway reply did not use the completed Focus minutes: $realReply", Regex("\\b25\\b").containsMatchIn(realReply))

        val sentContextJson = requireNotNull(localController.lastSharedAgentContextJson)
        val sentContext = JSONObject(sentContextJson)
        val sentCategories = sentContext.getJSONObject("categories")
        assertTrue(sentCategories.getJSONArray("focus").getString(0).contains("completed | 25 min"))
        assertTrue(sentCategories.getJSONArray("goals").getString(0).contains("validé 25"))
        assertTrue(sentCategories.getJSONArray("goals").getString(0).contains("120"))

        hideKeyboard()
        composeRule.onNodeWithTag("agent_last_shared_context_payload_toggle").performScrollTo().performClick()
        composeRule.onNodeWithTag("agent_last_shared_context_payload").assertExists()
        composeRule.onNodeWithTag("agent_last_shared_context_payload_toggle").performScrollTo().performClick()
        composeRule.onNodeWithText(realReply.take(80), substring = true).performScrollTo().assertIsDisplayed()
        capture("v62-coach-real-gateway.png")

        val directive = localController.visibleAgentProposals.firstOrNull { it.proposal.actionType == "home.set_directive" }
            ?: error("OpenClaw returned no actionable home.set_directive proposal; types=${localController.agentInbox.map { it.proposal.actionType }} reply=$realReply")
        acceptVisibleProposal(directive.id)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            localController.agentInbox.firstOrNull { it.id == directive.id }?.proposal?.state?.terminal == true
        }
        val accepted = localController.agentInbox.firstOrNull { it.id == directive.id }
            ?: error("Accepted proposal is absent from the Android inbox")
        assertEquals(ProposalState.EXECUTED, accepted.proposal.state)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            localController.agentInbox.firstOrNull { it.id == directive.id }?.ackPending == false
        }
        assertTrue(
            "The executed proposal ACK was not accepted by the backend",
            !requireNotNull(localController.agentInbox.firstOrNull { it.id == directive.id }).ackPending,
        )
        assertTrue("The accepted Gateway proposal must be in the Android effect journal", localController.agentEffectHistory.any {
            it.proposalId == directive.id && it.actionType == "home.set_directive" && it.state == ProposalState.EXECUTED
        })
        assertEquals(directive.id, localController.agentHomeState.directive?.sourceProposalId)

        // Revoke Goals, perform a second real call, and inspect the exact payload recorded after write.
        composeRule.runOnUiThread { localController.setAgentSharedCategory("goals", false) }
        val assistantCountAfterFirst = localController.agentConversation.count { it.role.name == "ASSISTANT" }
        composeRule.onNodeWithTag("agent_message_input").performScrollTo().performTextInput(
            "Quels éléments Focus sont disponibles dans le contexte local de cette requête ? Réponds en français.",
        )
        composeRule.onNodeWithTag("agent_send_message").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 90_000) {
            localController.agentConversation.count { it.role.name == "ASSISTANT" } > assistantCountAfterFirst
        }
        val revokedPayload = JSONObject(requireNotNull(localController.lastSharedAgentContextJson))
        val authorized = revokedPayload.getJSONArray("consented_categories")
        assertTrue((0 until authorized.length()).any { authorized.getString(it) == "focus" })
        assertTrue((0 until authorized.length()).none { authorized.getString(it) == "goals" })
        assertTrue("Revoked goal data remained in the next Android payload", !revokedPayload.toString().contains("Réviser l'algèbre") && !revokedPayload.toString().contains("120"))
        assertTrue(revokedPayload.getJSONObject("categories").getJSONArray("focus").getString(0).contains("25 min"))

        hideKeyboard()
        composeRule.runOnUiThread { localController.setEmergencyPause() }
        assertTrue("Emergency pause should work offline and immediately", localController.isEmergencyPaused)
        android.util.Log.i(
            "ATLAS_V62_REAL",
            "gateway=connected clock=accelerated focus=completed:25min goal=25/120 proposal=accepted journal=recorded goals_revoked=next_payload_empty",
        )
    }

    @Test
    fun localCoachRendersGoalAndAcceptsProposalAfterKeyboardDismissal() {
        val clock = FakeClock(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime())
        val localController = newIsolatedController(clock)
        controller = localController
        composeRule.runOnUiThread {
            localController.enableAgent()
            localController.setCapability(AtlasController.CAP_COACH, true)
            localController.setCapability(AtlasController.CAP_FOCUS, true)
            localController.setAgentMode(AgentMode.PROPOSALS_ONLY)
            localController.setAgentSharedCategory("focus", true)
            localController.setAgentSharedCategory("goals", true)
            val allowed = setOf("home.set_directive", "focus.propose", "focus.start")
            AgentActionType.entries.forEach { action ->
                localController.setAgentActionAllowed(action.wireValue, action.wireValue in allowed)
            }
            localController.setAgentFocusWindow(
                days = (1..7).toSet(), start = "00:00", end = "23:59",
                maxMinutes = 30, maxSessionsPerDay = 4, maxChangesPerDay = 50,
            )
            localController.createGoal(GoalPanelDraft(
                title = "Réviser l'algèbre",
                metric = GoalMetric.FOCUS_MINUTES,
                targetValue = 120,
                description = "Objectif synthétique de recette locale.",
                nextAction = "Relire le prochain exercice.",
            ))
            val goal = localController.coachGoals.single().record
            localController.startFocus(FocusPlan.custom(25 * 60L, totalWorkCycles = 1).copy(taskTitle = "Réviser l'algèbre"))
            clock.advanceMillis(25 * 60_000L)
            localController.tickFocus()
            val finished = requireNotNull(localController.focusSession)
            localController.linkFocusToGoal(goal.id, finished.id, linked = true)
            localController.syncLauncherHomeRole(true)
            localController.toggleLauncherPin("com.android.settings")
        }
        showCoach(localController)
        composeRule.onNodeWithTag("agent_message_input").performScrollTo().performTextInput("Propose une prochaine étape locale.")
        composeRule.onNodeWithTag("agent_send_message").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            localController.agentConversation.any { it.role.name == "ASSISTANT" }
        }
        hideKeyboard()
        composeRule.onNodeWithTag("agent_message_input").performScrollTo().assertIsDisplayed()
        val localProposal = localController.visibleAgentProposals.first()
        composeRule.onNodeWithTag("agent_priority_proposal_${localProposal.id}").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("agent_proposal_accept").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            localController.agentInbox.firstOrNull { it.id == localProposal.id }?.proposal?.state?.terminal == true
        }
    }

    @Test
    @RequiresBackendArguments(
        keys = ["backend_stopped"],
        reason = "offline recreation fixture runs only after the local backend has stopped",
    )
    fun prepareLocalFocusFixtureForOfflineRecreation() {
        assertEquals(
            "This fixture must run only after the v62 harness stops FastAPI",
            "true",
            InstrumentationRegistry.getArguments().getString("backend_stopped"),
        )
        val localController = newIsolatedController(
            FakeClock(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime()),
            clearState = false,
        )
        assertTrue(localController.focusHistory.any { it.status == dev.atlas.coach.focus.FocusStatus.FINISHED })
        assertTrue(localController.coachGoals.any { it.progress.currentValue == 25L && it.progress.targetValue == 120L })
        assertTrue("The launcher favorite must remain local", "com.android.settings" in localController.launcherPinnedPackages)
        localController.clearSyncToken()
        localController.saveSyncConfiguration("", null)
        localController.setEmergencyPause()
        assertTrue("Emergency pause must be persisted before process termination", localController.isEmergencyPaused)
    }

    @Test
    @RequiresBackendArguments(
        keys = ["backend_stopped"],
        reason = "offline recreation proof requires the harness to stop FastAPI first",
    )
    fun localGoalFocusFavoritesAndEmergencyPauseRemainAvailableAfterBackendStop() {
        assertEquals(
            "This proof must be invoked only after the v62 harness stops FastAPI",
            "true",
            InstrumentationRegistry.getArguments().getString("backend_stopped"),
        )
        val localController = newIsolatedController(
            FakeClock(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime()),
            clearState = false,
        )
        assertTrue(localController.focusHistory.any { it.status == dev.atlas.coach.focus.FocusStatus.FINISHED })
        assertTrue(localController.coachGoals.any { it.progress.currentValue == 25L && it.progress.targetValue == 120L })
        assertTrue("The launcher favorite must remain local", "com.android.settings" in localController.launcherPinnedPackages)
        assertTrue("Emergency pause must remain available without backend", localController.isEmergencyPaused)
    }

    private fun newIsolatedController(clock: FakeClock, clearState: Boolean = true): AtlasController {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        check(testContext.packageName != instrumentation.targetContext.packageName) {
            "Gateway fixtures must be stored in the isolated instrumentation package."
        }
        val targetContext = instrumentation.targetContext
        val fixtureFilesDir = File(targetContext.cacheDir, "v62-fixture-files")
        check(fixtureFilesDir.mkdirs() || fixtureFilesDir.isDirectory)
        val isolated = object : ContextWrapper(targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = fixtureFilesDir
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                targetContext.getSharedPreferences("v62_instrumented_$name", mode)
        }
        if (clearState) {
            isolated.getSharedPreferences("atlas_local_state", Context.MODE_PRIVATE).edit().clear().commit()
            isolated.getSharedPreferences("atlas_agent_sync_settings", Context.MODE_PRIVATE).edit().clear().commit()
            File(isolated.filesDir, "atlas_agent_inbox_v2.json").delete()
            File(isolated.filesDir, "atlas_agent_effects_v1.json").delete()
            AndroidEmergencyPauseStore.clear(isolated)
        }
        return AtlasController(isolated, clock)
    }

    private fun isolatedPersistedState(): JSONObject {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val raw = targetContext.getSharedPreferences("v62_instrumented_atlas_local_state", Context.MODE_PRIVATE)
            .getString("state_v1", null)
        return JSONObject(requireNotNull(raw) { "Expected the isolated ATLAS state to be committed" })
    }

    private fun assertPersistedGoalLink(state: JSONObject, goalId: String, focusSessionId: String) {
        val goal = persistedGoal(state, goalId)
        val linkedIds = goal.getJSONArray("linkedFocusSessionIds")
        assertTrue((0 until linkedIds.length()).any { linkedIds.getString(it) == focusSessionId })
    }

    private fun persistedGoal(state: JSONObject, goalId: String): JSONObject {
        val goals = state.getJSONObject("goals").getJSONArray("goals")
        return (0 until goals.length())
            .map { index -> goals.getJSONObject(index) }
            .single { it.getString("id") == goalId }
    }

    private fun showCoach(controller: AtlasController) {
        if (composedAgentController === controller) return
        composedAgentController = controller
        composeRule.setContent {
            val revision = controller.revision
            AtlasTheme {
                AtlasScreenFrame(
                    title = "Coach",
                    activeDestination = AtlasRoutes.HOME,
                    canGoBack = false,
                    emergencyPaused = controller.isEmergencyPaused,
                    onBack = {},
                    onEmergencyPause = {},
                    onNavigate = {},
                ) {
                    AgentCoachScreen(controller, pullOnOpen = false, revision = revision)
                }
            }
        }
    }

    private fun acceptVisibleProposal(proposalId: String) {
        val pending = controller!!.visibleAgentProposals
        val targetIndex = pending.indexOfFirst { it.id == proposalId }
        check(targetIndex >= 0) { "The real Gateway proposal disappeared before confirmation" }
        pending.take(targetIndex).forEach { older ->
            composeRule.runOnUiThread { controller!!.postponeAgentProposal(older.id) }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            controller!!.visibleAgentProposals.firstOrNull()?.id == proposalId
        }
        composeRule.onNodeWithTag("agent_priority_proposal_$proposalId").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("agent_proposal_accept").performScrollTo()
        composeRule.onNodeWithTag("agent_proposal_accept").performClick()
    }

    private fun hideKeyboard() {
        composeRule.runOnUiThread {
            val manager = composeRule.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            manager.hideSoftInputFromWindow(composeRule.activity.window.decorView.windowToken, 0)
        }
    }

    private fun capture(fileName: String) {
        composeRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val png = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("screencap -p"),
        ).use { it.readBytes() }
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        check(png.size >= signature.size && png.copyOfRange(0, signature.size).contentEquals(signature)) {
            "The emulator did not return a PNG screenshot for $fileName."
        }
        val directory = File(instrumentation.targetContext.filesDir, "v62-captures")
        check(directory.mkdirs() || directory.isDirectory)
        val output = File(directory, fileName)
        output.writeBytes(png)
        check(output.length() > 0L)
    }

}
