package dev.atlas.coach

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.MainActivity
import dev.atlas.coach.launcher.AndroidLauncherCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class AtlasLaunchTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun clearDemoState() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("atlas_local_state", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        composeRule.activityRule.scenario.recreate()
    }

    @Test
    fun directiveFocusHabitSimulatorAndLibraryJourneysWorkOffline() {
        composeRule.onNodeWithText("ATLAS").assertIsDisplayed()
        composeRule.onNodeWithText("Lis/avance 10 min").assertIsDisplayed()

        composeRule.onNodeWithText("Commencer").performClick()
        composeRule.onNodeWithText("Session liée à : Lis/avance 10 min").assertIsDisplayed()
        composeRule.onNodeWithText("Mettre en pause").performScrollTo().performClick()
        composeRule.onNodeWithText("Reprendre").performScrollTo().assertIsDisplayed()
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("Reprendre").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Session liée à : Lis/avance 10 min").assertIsDisplayed()
        composeRule.onNodeWithText("Reprendre").performScrollTo().performClick()

        composeRule.onNodeWithText("Accueil").performClick()
        composeRule.onNodeWithText("Habitudes").performScrollTo().performClick()
        composeRule.onNodeWithTag("habit_input").performTextInput("Lire dix minutes")
        composeRule.onNodeWithText("Ajouter").performClick()
        composeRule.onNodeWithText("Valider aujourd'hui").performScrollTo().performClick()
        composeRule.onNodeWithText("Annuler aujourd'hui").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Annuler aujourd'hui").performScrollTo().performClick()
        composeRule.onNodeWithText("Valider aujourd'hui").assertIsDisplayed()
        composeRule.onNodeWithText("30 jours").performClick()
        composeRule.onNodeWithText("Exporter").performClick()
        composeRule.onNodeWithText("record_type", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Fermer").performClick()

        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("Accueil").performClick()
        composeRule.onNodeWithText("Habitudes").performScrollTo().performClick()
        composeRule.onNodeWithText("Lire dix minutes").assertIsDisplayed()
        composeRule.onNodeWithText("Valider aujourd'hui").assertIsDisplayed()

        composeRule.onNodeWithText("Accueil").performClick()
        composeRule.onNodeWithText("Focus").performScrollTo().performClick()
        composeRule.onNodeWithText("Terminer").performScrollTo().performClick()
        composeRule.onNodeWithText("Relier à une habitude…").performScrollTo().performClick()
        composeRule.onNodeWithText("Lire dix minutes").performClick()
        composeRule.onNodeWithText("Annuler").performClick()
        composeRule.onNodeWithText("Relier à une habitude…").performScrollTo().performClick()
        composeRule.onNodeWithText("Lire dix minutes").performClick()
        composeRule.onNodeWithText("Confirmer").performClick()
        composeRule.onNodeWithText("Habitude cochée après votre confirmation.").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("Accueil").performClick()
        composeRule.onNodeWithText("Paramètres").performClick()
        composeRule.onNodeWithText("Simulateur de friction · Diagnostic").performScrollTo().performClick()
        composeRule.onNodeWithText("Événements simulés, pas une observation réelle").assertIsDisplayed()
        composeRule.onNodeWithTag("simulate_four").performScrollTo().performClick()
        composeRule.onNodeWithText("Propositions émises : 1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Répéter le dernier événement (doublon)").performScrollTo().performClick()
        composeRule.onNodeWithText("Doublon ignoré · 1 intervention(s) conservée(s).").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Injecter 4 autres · vérifier le délai d'attente").performScrollTo().performClick()
        composeRule.onNodeWithText("4 nouveaux événements · 0 propositions pendant la période d'attente.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Activer les heures calmes 22:00–07:00").performScrollTo().performClick()
        composeRule.onNodeWithText("Règle : heures calmes 22:00–07:00 · délai d'attente 15 min · pause inactive.").assertIsDisplayed()
        composeRule.onNodeWithText("Désactiver les heures calmes").performScrollTo().performClick()
        composeRule.onNodeWithText("Pause d'urgence 30 min").performClick()
        composeRule.onNodeWithTag("simulate_four").performScrollTo().performClick()
        composeRule.onNodeWithText("4 événements simulés · 0 propositions affichées.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Pause d'urgence active · terminer").performClick()

        composeRule.onNodeWithText("Accueil").performClick()
        composeRule.onNodeWithText("Discuter").performScrollTo().performClick()
        composeRule.onNodeWithText("D'accord. Qu'est-ce qui rend cette étape difficile aujourd'hui ?").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Alternative").performScrollTo().performClick()
        composeRule.onNodeWithText("Essayons une version plus courte : Lis/avance 5 min.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Reporter").performScrollTo().performClick()
        composeRule.onNodeWithText("C'est reporté à plus tard aujourd'hui.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Mettre le coach en pause").performScrollTo().performClick()
        composeRule.onNodeWithText("Le coach est en pause. Vous pouvez le réactiver quand vous le souhaitez.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Coach · journal").performScrollTo().performClick()
        composeRule.onNodeWithText("Commencer").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Discuter").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Alternative").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Reporter").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Pause").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Accueil").performClick()
        composeRule.onNodeWithText("Bibliothèque").performClick()
        composeRule.onNodeWithTag("content_focus-music").performScrollTo().performClick()
    }

    @Test
    fun disablingFocusPausesSessionAndDisablingHabitsRemovesLinkButton() {
        composeRule.onNodeWithText("Habitudes").performScrollTo().performClick()
        composeRule.onNodeWithTag("habit_input").performTextInput("Lire hors ligne")
        composeRule.onNodeWithText("Ajouter").performClick()
        composeRule.onNodeWithText("Accueil").performClick()

        composeRule.onNodeWithText("Commencer").performClick()
        composeRule.onNodeWithText("Concentration · En cours").assertIsDisplayed()
        composeRule.onNodeWithText("Accueil").performClick()
        composeRule.onNodeWithText("Paramètres").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Activer ou désactiver Focus").performClick()
        composeRule.onNodeWithText("Accueil").performClick()
        composeRule.onNodeWithText("Focus").performScrollTo().performClick()
        composeRule.onNodeWithText("Concentration · En pause").assertIsDisplayed()
        composeRule.onAllNodesWithText("Reprendre").assertCountEquals(0)
        composeRule.onNodeWithText("Terminer").performScrollTo().performClick()

        composeRule.onNodeWithText("Accueil").performClick()
        composeRule.onNodeWithText("Paramètres").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Activer ou désactiver Habitudes").performClick()
        composeRule.onNodeWithText("Accueil").performClick()
        composeRule.onNodeWithText("Focus").performScrollTo().performClick()
        composeRule.onNodeWithText("Session terminée").assertIsDisplayed()
        composeRule.onAllNodesWithText("Relier à une habitude…").assertCountEquals(0)
    }

    @Test
    fun focusSessionRestorationSeed() {
        composeRule.onNodeWithText("Commencer").performClick()
        composeRule.onNodeWithText("Concentration · En cours").assertIsDisplayed()
    }

    @Test
    fun optionalLauncherAndLicenseAttributionAreVisible() {
        composeRule.onNodeWithText("Paramètres").performScrollTo().performClick()
        composeRule.onNodeWithText("À propos / Licences").performScrollTo().performClick()
        composeRule.onNodeWithText("Reef par Pranav Purwar", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("github.com/aload0/Reef").assertIsDisplayed()
        composeRule.onNodeWithText("Olauncher · modèle de liste d'applications").assertIsDisplayed()
        composeRule.onNodeWithText("Loop Habit Tracker · force d'habitude et CSV").assertIsDisplayed()

        composeRule.onNodeWithText("Accueil").performClick()
        composeRule.onNodeWithText("Paramètres").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Activer ou désactiver Accueil launcher facultatif ATLAS").performScrollTo().performClick()
        composeRule.onNodeWithText("Accueil").performClick()
        composeRule.onNodeWithText("Accueil launcher facultatif").performScrollTo().performClick()

        composeRule.onNodeWithText("Directive du jour").assertIsDisplayed()
        composeRule.onNodeWithText("Toutes les applications").performScrollTo().performClick()
        composeRule.onNodeWithText("Applications").performScrollTo().assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(AndroidLauncherCatalog(context).list().any { it.appPackage == context.packageName })
        composeRule.onAllNodesWithText("Épingler").get(0).performScrollTo().performClick()
        assertTrue(composeRule.onAllNodesWithText("Épinglée").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun finishedFocusHistoryRestoresFromLocalPersistence() {
        composeRule.onNodeWithText("Focus").performScrollTo().performClick()
        composeRule.onNodeWithText("Mode démo · 20 secondes").performScrollTo().performClick()
        composeRule.onNodeWithText("Terminer").performScrollTo().performClick()
        composeRule.onNodeWithText("Statistiques de focus").performScrollTo().performClick()
        composeRule.onNodeWithText("1 sessions archivées", substring = true).assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("Focus").performScrollTo().performClick()
        composeRule.onNodeWithText("Statistiques de focus").performScrollTo().performClick()
        composeRule.onNodeWithText("1 sessions archivées", substring = true).assertIsDisplayed()
    }

    @Test
    fun focusRoutineIsCapabilityGatedAndRequiresExplicitStart() {
        composeRule.onNodeWithText("Paramètres").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Activer ou désactiver Routines de focus locales").performScrollTo().performClick()
        composeRule.onNodeWithText("Accueil").performClick()
        composeRule.onNodeWithText("Routines de focus").performScrollTo().performClick()
        composeRule.onNodeWithText("Nom de la routine").performClick()
        composeRule.onNodeWithText("Nom de la routine").performTextInput("Routine de test")
        composeRule.onNodeWithText("Ajouter la routine").performClick()
        composeRule.onNodeWithText("Routine de test").assertIsDisplayed()
        composeRule.onNodeWithText("Une routine ne lance rien automatiquement", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("Commencer").assertCountEquals(0)
    }

    @Test
    fun reefPomodoroSetupStartsAnAtlasSessionWithCycleState() {
        composeRule.onNodeWithText("Focus").performScrollTo().performClick()
        composeRule.onNodeWithText("Démarrer le Pomodoro").performScrollTo().performClick()
        composeRule.onNodeWithText("Pomodoro 25/5 démarré", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Cycle 1 / 4").assertIsDisplayed()
    }

    @Test
    fun malformedPolicyAndRuleSubstatesAreQuarantinedWithoutStartupCrash() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("atlas_local_state", android.content.Context.MODE_PRIVATE)
        val raw = """{"schemaVersion":1,"policy":{"quietStart":"bad-time","quietEnd":"07:00"},"rules":{"recentEventsByRuleAndPackage":{"rule\\u0000app":[{"packageName":"app","timestampUtc":10}]}}}"""
        prefs.edit().putString("state_v1", raw).commit()
        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithText("ATLAS").assertIsDisplayed()
        composeRule.onNodeWithText("sous-états invalides", substring = true).assertIsDisplayed()
        assertTrueQuarantineContains(prefs.all, raw)
    }

    @Test
    fun unknownSchemaIsQuarantinedAndShowsRecoveryMessage() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("atlas_local_state", android.content.Context.MODE_PRIVATE)
        val raw = """{"schemaVersion":88,"focus":{}}"""
        prefs.edit().putString("state_v1", raw).commit()
        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithText("ATLAS").assertIsDisplayed()
        composeRule.onNodeWithText("version de schéma inconnue", substring = true).assertIsDisplayed()
        assertTrueQuarantineContains(prefs.all, raw)
    }

    @Test
    fun visibleFocusTimerWritesOneCheckpointAfterTwentySeconds() {
        composeRule.onNodeWithText("Commencer").performClick()
        composeRule.onNodeWithText("Concentration · En cours").assertIsDisplayed()
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("atlas_local_state", android.content.Context.MODE_PRIVATE)
        val writeTimes = CopyOnWriteArrayList<Long>()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "state_v1") writeTimes += System.currentTimeMillis()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        composeRule.waitUntil(timeoutMillis = 27_000L) { writeTimes.isNotEmpty() }
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

        assertEquals(1, writeTimes.size)
        assertTrue("Checkpoint interval was shorter than 15 seconds", writeTimes.zipWithNext().all { (first, next) -> next - first >= 15_000L })
    }

    private fun assertTrueQuarantineContains(values: Map<String, *>, raw: String) {
        assertTrue("Raw state was not found under a quarantine key", values.entries.any { (key, value) ->
            key.startsWith("state_quarantine_") && !key.endsWith("_reason") && value == raw
        })
    }
}
