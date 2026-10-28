package dev.atlas.coach

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.AtlasController
import dev.atlas.coach.app.AtlasApplication
import dev.atlas.coach.app.GoalPanelDraft
import dev.atlas.coach.app.MainActivity
import dev.atlas.coach.data.AndroidRevocationBarrierStorage
import dev.atlas.coach.data.RevocationBarrierState
import dev.atlas.coach.focus.FakeClock
import dev.atlas.coach.focus.FocusPlan
import dev.atlas.coach.focus.FocusStatus
import dev.atlas.coach.goals.GoalMetric
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.launcher.AndroidLauncherCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class AtlasLaunchTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private var revocationBarrierPreferencesToRestore: Map<String, Any>? = null

    @After
    fun restoreRevocationBarrierAfterLegacyMigrationFixture() {
        val previous = revocationBarrierPreferencesToRestore ?: return
        val preferences = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("atlas_agent_revocation_barrier", android.content.Context.MODE_PRIVATE)
        val editor = preferences.edit().clear()
        previous.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> {
                    val strings = value.map { it as? String
                        ?: throw AssertionError("Unexpected non-string value in revocation barrier preferences")
                    }.toSet()
                    assertEquals("Unexpected duplicate value in revocation barrier preferences", value.size, strings.size)
                    editor.putStringSet(key, strings)
                }
                else -> throw AssertionError("Unsupported value in revocation barrier preferences: ${value::class.java.name}")
            }
        }
        assertTrue("Could not restore the pre-test revocation barrier preferences", editor.commit())
        assertEquals("Revocation barrier preferences were not restored exactly", previous, preferences.all)
        revocationBarrierPreferencesToRestore = null
    }

    @Before
    fun clearDemoState() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("atlas_local_state", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication)
            .resetControllerForTesting()
        composeRule.activityRule.scenario.recreate()
    }

    private fun openHome() = composeRule.onNodeWithContentDescription("Ouvrir Accueil").performClick()
    private fun openFocus() = composeRule.onNodeWithContentDescription("Ouvrir Focus").performClick()
    private fun openHabits() { openMore(); composeRule.onNodeWithText("Habitudes").performScrollTo().performClick() }
    private fun openMore() = composeRule.onNodeWithContentDescription("Ouvrir Plus").performClick()
    private fun openSettings() { openMore(); composeRule.onNodeWithText("Usage quotidien").performScrollTo().performClick() }
    private fun openLibrary() { openMore(); composeRule.onNodeWithText("Bibliothèque").performScrollTo().performClick() }
    private fun openCoach() = composeRule.onNodeWithContentDescription("Ouvrir Coach").performClick()

    @Test
    fun backReturnsFromDailySettingsToMoreAndTabsSelectTheirRoot() {
        openMore()
        composeRule.onNodeWithText("Usage quotidien").performScrollTo().performClick()
        composeRule.onNodeWithText("Accueil Android").performScrollTo().assertIsDisplayed()
        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.onNodeWithText("Autres espaces").assertIsDisplayed()

        openFocus()
        composeRule.onNodeWithTag("focus_task_title").assertIsDisplayed()
        openHome()
        composeRule.onNodeWithText("Directive du coach").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun directiveFocusHabitSimulatorAndLibraryJourneysWorkOffline() {
        composeRule.onNodeWithText("Aujourd'hui").assertIsDisplayed()
        composeRule.onNodeWithText("Lis/avance 10 min").assertIsDisplayed()

        composeRule.onNodeWithText("Commencer").performClick()
        composeRule.onNodeWithText("Lis/avance 10 min").assertIsDisplayed()
        composeRule.onNodeWithText("Pause").performScrollTo().performClick()
        composeRule.onNodeWithText("Reprendre").performScrollTo().assertIsDisplayed()
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("Reprendre").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Lis/avance 10 min").assertIsDisplayed()
        composeRule.onNodeWithText("Reprendre").performScrollTo().performClick()

        openHome()
        openHabits()
        composeRule.onNodeWithTag("habit_input").performTextInput("Lire dix minutes")
        composeRule.onNodeWithText("Ajouter l'habitude").performScrollTo().performClick()
        composeRule.onNodeWithText("Valider aujourd'hui").performScrollTo().performClick()
        composeRule.onNodeWithText("Annuler aujourd'hui").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Annuler aujourd'hui").performScrollTo().performClick()
        composeRule.onNodeWithText("Valider aujourd'hui").assertIsDisplayed()
        composeRule.onNodeWithText("30 jours").performClick()
        composeRule.onNodeWithText("Exporter CSV").performScrollTo().performClick()
        composeRule.onNodeWithText("record_type", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Fermer").performClick()

        composeRule.activityRule.scenario.recreate()
        openHome()
        openHabits()
        composeRule.onNodeWithText("Lire dix minutes").assertIsDisplayed()
        composeRule.onNodeWithText("Valider aujourd'hui").assertIsDisplayed()

        openHome()
        openFocus()
        composeRule.onNodeWithText("Terminer").performScrollTo().performClick()
        composeRule.onNodeWithText("Session terminée avant le dernier cycle", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Relier à une habitude…").assertCountEquals(0)

        openHome()
        openSettings()
        composeRule.onNodeWithText("Diagnostic avancé").performScrollTo().performClick()
        composeRule.onNodeWithText("Ouvrir le simulateur de friction").performScrollTo().performClick()
        composeRule.onNodeWithText("Événements simulés, pas une observation réelle").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("simulate_four").performScrollTo().performClick()
        composeRule.onNodeWithText("Propositions émises : 1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Répéter le dernier événement (doublon)").performScrollTo().performClick()
        composeRule.onNodeWithText("Doublon ignoré · 1 intervention(s) conservée(s).").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Injecter 4 autres · vérifier le délai d'attente").performScrollTo().performClick()
        composeRule.onNodeWithText("4 nouveaux événements · 0 propositions pendant la période d'attente.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Activer les heures calmes 22:00–07:00").performScrollTo().performClick()
        composeRule.onNodeWithText("Règle : heures calmes 22:00–07:00 · délai d'attente 15 min · pause inactive.").assertIsDisplayed()
        composeRule.onNodeWithText("Désactiver les heures calmes").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Activer la pause d'urgence de 30 minutes").performClick()
        composeRule.onNodeWithTag("simulate_four").performScrollTo().performClick()
        composeRule.onNodeWithText("4 événements simulés · 0 propositions affichées.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Terminer la pause d'urgence").performClick()

        openHome()
        composeRule.onNodeWithText("Autres réponses à la directive").performScrollTo().performClick()
        composeRule.onNodeWithText("Discuter").performScrollTo().performClick()
        composeRule.onNodeWithText("D'accord. Qu'est-ce qui rend cette étape difficile aujourd'hui ?").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Alternative").performScrollTo().performClick()
        composeRule.onNodeWithText("Essayons une version plus courte : Lis/avance 5 min.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Reporter").performScrollTo().performClick()
        composeRule.onNodeWithText("C'est reporté à plus tard aujourd'hui.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Pause coach").performScrollTo().performClick()
        composeRule.onNodeWithText("Le coach est en pause. Vous pouvez le réactiver quand vous le souhaitez.").performScrollTo().assertIsDisplayed()
        openCoach()
        composeRule.onNodeWithTag("agent_message_input").performScrollTo().assertIsDisplayed()
        openHome()
        openLibrary()
        composeRule.onNodeWithText("Recherches génériques").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Recherche; aucun lien vidéo précis sélectionné").get(0).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Ajouter un lien vidéo précis").performScrollTo().performClick()
        composeRule.onNodeWithText("Titre").performTextInput("Lien test")
        composeRule.onNodeWithText("URL YouTube HTTPS").performTextInput("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        composeRule.onNodeWithText("Ajouter").performClick()
        composeRule.onNodeWithText("Lien test").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Épingler").performScrollTo().performClick()
        composeRule.onNodeWithText("Désépingler").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Supprimer").performScrollTo().performClick()
        composeRule.onNodeWithText("Aucun lien vidéo ajouté.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun disablingFocusPausesSessionAndDisablingHabitsRemovesLinkButton() {
        openHabits()
        composeRule.onNodeWithTag("habit_input").performTextInput("Lire hors ligne")
        composeRule.onNodeWithText("Ajouter l'habitude").performScrollTo().performClick()
        openHome()

        composeRule.onNodeWithText("Commencer").performClick()
        composeRule.onNodeWithTag("focus_phase").assertTextEquals("Concentration · en cours")
        openHome()
        openSettings()
        composeRule.onNodeWithContentDescription("Activer ou désactiver Focus").performClick()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as AtlasApplication
        val controller = application.controller
        assertEquals("Disabling Focus must preserve the running session as paused", FocusStatus.PAUSED, controller.focusSession?.status)
        val pausedSessionId = checkNotNull(controller.focusSession).id
        val pausedRemainingSeconds = controller.focusSession!!.remainingSeconds
        val persistedFocus = org.json.JSONObject(checkNotNull(
            context.getSharedPreferences("atlas_local_state", android.content.Context.MODE_PRIVATE)
                .getString("state_v1", null),
        )).getJSONObject("focus")
        assertEquals("PAUSED", persistedFocus.getString("status"))
        application.resetControllerForTesting()
        composeRule.activityRule.scenario.recreate()
        val restoredController = application.controller
        assertEquals(pausedSessionId, restoredController.focusSession?.id)
        assertEquals(FocusStatus.PAUSED, restoredController.focusSession?.status)
        assertEquals(pausedRemainingSeconds, restoredController.focusSession?.remainingSeconds)
        openHome()
        openFocus()
        composeRule.onNodeWithText("Concentration · en pause").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("focus_active_task").assertIsDisplayed()
        composeRule.onNodeWithTag("focus_time_remaining").assertIsDisplayed()
        composeRule.onNodeWithTag("focus_disabled_message")
            .performScrollTo().assertTextEquals("Focus désactivé. La session est en pause.")
        composeRule.onNodeWithText("Annuler").assertIsDisplayed()
        composeRule.onAllNodesWithText("Reprendre").assertCountEquals(0)
        composeRule.onAllNodesWithText("Nouvelle session").assertCountEquals(0)
        composeRule.onAllNodesWithText("Commencer").assertCountEquals(0)
        composeRule.onNodeWithText("Terminer").performScrollTo().performClick()

        openHome()
        openSettings()
        composeRule.onNodeWithContentDescription("Activer ou désactiver Habitudes").performClick()
        openHome()
        openFocus()
        composeRule.onNodeWithText("Session terminée").assertIsDisplayed()
        composeRule.onAllNodesWithText("Relier à une habitude…").assertCountEquals(0)
    }

    @Test
    fun focusSessionRestorationSeed() {
        composeRule.onNodeWithText("Commencer").performClick()
        composeRule.onNodeWithTag("focus_phase").assertTextEquals("Concentration · en cours")
    }

    @Test
    fun homeRoleSettingsAreSeparateFromTheCoachDashboardAndLicenseAttributionIsVisible() {
        openSettings()
        composeRule.onNodeWithText("Accueil Android").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Choisir ATLAS comme accueil…").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Activer ou désactiver Accueil ATLAS facultatif").assertCountEquals(0)
        composeRule.onNodeWithText("À propos et licences").performScrollTo().performClick()
        composeRule.onNodeWithText("Le code source ATLAS est diffusé sous GNU GPL version 3.0.", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Reef par Pranav Purwar", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("github.com/aload0/Reef").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Olauncher · modèle de liste d'applications").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Loop Habit Tracker · force d'habitude et CSV").performScrollTo().assertIsDisplayed()

        openHome()
        openMore()
        composeRule.onAllNodesWithText("Accueil et applications favorites").assertCountEquals(0)
        openHome()
        composeRule.onNodeWithText("Directive du coach").performScrollTo().assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(AndroidLauncherCatalog(context).list().none { it.appPackage == context.packageName })
    }

    @Test
    fun finishedFocusHistoryRestoresFromLocalPersistence() {
        openFocus()
        composeRule.onNodeWithTag("focus_pomodoro_start").performScrollTo().performClick()
        composeRule.onNodeWithText("Terminer").performScrollTo().performClick()
        composeRule.onNodeWithText("Session terminée").assertIsDisplayed()
        composeRule.onNodeWithText("Statistiques de focus").performScrollTo().performClick()
        composeRule.onNodeWithText("1 sessions archivées", substring = true).assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        openFocus()
        composeRule.onNodeWithText("Statistiques de focus").performScrollTo().performClick()
        composeRule.onNodeWithText("1 sessions archivées", substring = true).assertIsDisplayed()
    }

    @Test
    fun focusRoutineIsCapabilityGatedAndRequiresExplicitStart() {
        // Isolate the routine capability check from the Android permission dialog.
        // Notification permission behavior is exercised by RuntimePermissionInstrumentationTest.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        openSettings()
        composeRule.onNodeWithContentDescription("Activer ou désactiver Routines de focus locales").performScrollTo().performClick()
        openMore()
        composeRule.onNodeWithText("Routines de focus").performScrollTo().performClick()
        composeRule.onNodeWithText("Nom de la routine").performClick()
        composeRule.onNodeWithText("Nom de la routine").performTextInput("Routine de test")
        composeRule.onNodeWithText("Ajouter la routine").performClick()
        composeRule.onNodeWithText("Routine de test").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Une routine envoie une proposition", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("Commencer").assertCountEquals(0)
    }

    @Test
    fun reefPomodoroSetupStartsAnAtlasSessionWithCycleState() {
        openFocus()
        composeRule.onNodeWithTag("focus_pomodoro_start").performScrollTo().performClick()
        composeRule.onNodeWithTag("focus_phase").assertIsDisplayed()
        composeRule.onNodeWithTag("focus_cycles").assertTextEquals("Cycle 1 / 4")
    }

    @Test
    fun malformedPolicyAndRuleSubstatesAreQuarantinedWithoutStartupCrash() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("atlas_local_state", android.content.Context.MODE_PRIVATE)
        val raw = """{"schemaVersion":1,"policy":{"quietStart":"bad-time","quietEnd":"07:00"},"rules":{"recentEventsByRuleAndPackage":{"rule\\u0000app":[{"packageName":"app","timestampUtc":10}]}}}"""
        prefs.edit().putString("state_v1", raw).commit()
        // Le contrôleur est partagé par processus : il faut le reconstruire pour relire le stockage.
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication)
            .resetControllerForTesting()
        composeRule.activityRule.scenario.recreate()

        assertTrueQuarantineContains(prefs.all, raw)
        val recoveryMessage = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication)
            .controller.latestMessage
        assertTrue(
            "Controller should expose the quarantined invalid-substate notice; actual=$recoveryMessage",
            recoveryMessage?.contains("sous-états invalides") == true,
        )
        composeRule.onNodeWithText("Aujourd'hui").assertIsDisplayed()
        composeRule.onNodeWithText("sous-états invalides", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun unknownSchemaIsQuarantinedAndShowsRecoveryMessage() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("atlas_local_state", android.content.Context.MODE_PRIVATE)
        val raw = """{"schemaVersion":88,"focus":{}}"""
        prefs.edit().putString("state_v1", raw).commit()
        // Le contrôleur est partagé par processus : il faut le reconstruire pour relire le stockage.
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication)
            .resetControllerForTesting()
        composeRule.activityRule.scenario.recreate()

        assertTrueQuarantineContains(prefs.all, raw)
        val recoveryMessage = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication)
            .controller.latestMessage
        assertTrue(
            "Controller should expose the unknown-schema recovery notice; actual=$recoveryMessage",
            recoveryMessage?.contains("version de schéma inconnue") == true,
        )
        val persisted = org.json.JSONObject(checkNotNull(prefs.getString("state_v1", null)))
        assertEquals(1, persisted.getInt("schemaVersion"))
        assertEquals(1, persisted.getJSONObject("goals").getInt("schema_version"))
        composeRule.onNodeWithText("Aujourd'hui").assertIsDisplayed()
        composeRule.onNodeWithText("version de schéma inconnue", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun recoveryDefersLegacyConsentAndGoalMigrationUntilTheStateGraphIsReady() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val barrierStorage = AndroidRevocationBarrierStorage(context)
        val barrierPreferences = context.getSharedPreferences(
            "atlas_agent_revocation_barrier",
            android.content.Context.MODE_PRIVATE,
        )
        revocationBarrierPreferencesToRestore = barrierPreferences.all.mapValues { (_, value) ->
            when (value) {
                is Set<*> -> value.toSet()
                null -> throw AssertionError("Unexpected null in revocation barrier preferences")
                else -> value
            }
        }
        assertTrue("Could not isolate the legacy consent migration fixture", barrierStorage.writeState(RevocationBarrierState()))
        val prefs = context.getSharedPreferences("atlas_local_state", android.content.Context.MODE_PRIVATE)
        val legacyMandate = AgentMandate.default().copy(sharedDataCategories = setOf("focus", "goals"))
        val raw = org.json.JSONObject()
            .put("schemaVersion", 1)
            .put("agentMandate", legacyMandate.toJson().toString())
            .put("policy", org.json.JSONObject().put("quietStart", "invalid-time").put("quietEnd", "07:00"))
            .put("lastAgentSharedContext", "{\"consented_categories\":[\"focus\"],\"categories\":{\"focus\":[\"fixture-sentinel\"]}}")
            .put("lastAgentSharedContextAt", 1_800_000_000_000L)
            .toString()
        prefs.edit().putString("state_v1", raw).commit()

        val app = context.applicationContext as AtlasApplication
        app.resetControllerForTesting()
        val controller = app.controller
        assertQuarantineSemanticsAndPrivacy(prefs.all)
        assertTrue(controller.latestMessage?.contains("sous-états invalides") == true)
        assertTrue(controller.agentContextPreview.authorizedCategories.containsAll(setOf("focus", "goals")))

        val firstPersisted = org.json.JSONObject(checkNotNull(prefs.getString("state_v1", null)))
        val firstConsentDates = firstPersisted.getJSONObject("agentContextConsentAt")
        val focusConsentAt = firstConsentDates.getLong("focus")
        val goalsConsentAt = firstConsentDates.getLong("goals")
        assertTrue(focusConsentAt > 0L)
        assertTrue(goalsConsentAt > 0L)
        assertEquals(1, firstPersisted.getJSONObject("goals").getInt("schema_version"))

        controller.createGoal(
            GoalPanelDraft(
                title = "Réviser le cours",
                metric = GoalMetric.MANUAL,
                targetValue = 1L,
                description = "Objectif local de démonstration",
                nextAction = "Ouvrir le chapitre 1",
            ),
        )
        assertEquals("Réviser le cours", controller.coachGoals.single().record.title)

        app.resetControllerForTesting()
        val restored = app.controller
        assertEquals("Réviser le cours", restored.coachGoals.single().record.title)
        assertTrue(restored.agentContextPreview.authorizedCategories.containsAll(setOf("focus", "goals")))
        val afterRestart = org.json.JSONObject(checkNotNull(prefs.getString("state_v1", null)))
            .getJSONObject("agentContextConsentAt")
        assertEquals(focusConsentAt, afterRestart.getLong("focus"))
        assertEquals(goalsConsentAt, afterRestart.getLong("goals"))
        assertQuarantineSemanticsAndPrivacy(prefs.all)
    }

    @Test
    fun focusControllerWritesOneCheckpointAfterTwentySecondsOfMonotonicTime() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context
            .getSharedPreferences("atlas_local_state", android.content.Context.MODE_PRIVATE)
        val clock = FakeClock(startUtcMillis = 1_000_000L, startMonotonicMillis = 1_000_000L)
        lateinit var controller: AtlasController
        instrumentation.runOnMainSync {
            controller = AtlasController(context, clock)
            controller.startFocus(FocusPlan.custom(workSeconds = 120L))
        }
        val writeTimes = CopyOnWriteArrayList<Long>()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "state_v1") writeTimes += System.currentTimeMillis()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        instrumentation.runOnMainSync {
            clock.advanceMonotonicMillis(19_999L)
            controller.tickFocus()
        }
        assertEquals("Checkpoint was written before twenty seconds", 0, writeTimes.size)
        instrumentation.runOnMainSync {
            clock.advanceMonotonicMillis(1L)
            controller.tickFocus()
        }
        composeRule.waitUntil(timeoutMillis = 2_000L) { writeTimes.isNotEmpty() }
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

        assertEquals(1, writeTimes.size)
        assertTrue("Checkpoint interval was shorter than 15 seconds", writeTimes.zipWithNext().all { (first, next) -> next - first >= 15_000L })
    }

    private fun assertTrueQuarantineContains(values: Map<String, *>, raw: String) {
        assertTrue("Raw state was not found under a quarantine key", values.entries.any { (key, value) ->
            key.startsWith("state_quarantine_") && !key.endsWith("_reason") && value == raw
        })
    }

    private fun assertQuarantineSemanticsAndPrivacy(values: Map<String, *>) {
        val quarantinedStates = values.entries.mapNotNull { (key, value) ->
            if (!key.startsWith("state_quarantine_") || key.endsWith("_reason")) return@mapNotNull null
            (value as? String)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        }
        assertTrue("No parseable state was found under a quarantine key", quarantinedStates.isNotEmpty())
        val quarantined = quarantinedStates.firstOrNull { it.has("agentMandate") && it.has("policy") }
            ?: throw AssertionError("Quarantine is missing the legacy mandate or policy fields")

        assertEquals(1, quarantined.getInt("schemaVersion"))
        val mandate = org.json.JSONObject(quarantined.getString("agentMandate"))
        assertEquals(setOf("focus", "goals"),
            (0 until mandate.getJSONArray("shared_data_categories").length())
                .map { mandate.getJSONArray("shared_data_categories").getString(it) }.toSet())
        val policy = quarantined.getJSONObject("policy")
        assertEquals("invalid-time", policy.getString("quietStart"))
        assertEquals("07:00", policy.getString("quietEnd"))
        assertFalse("Quarantine retained the legacy shared context", quarantined.has("lastAgentSharedContext"))
        assertFalse("Quarantine retained the legacy shared-context timestamp", quarantined.has("lastAgentSharedContextAt"))
    }
}
