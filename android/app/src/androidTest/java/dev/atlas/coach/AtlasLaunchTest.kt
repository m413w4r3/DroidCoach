package dev.atlas.coach

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
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
import dev.atlas.coach.app.MainActivity
import dev.atlas.coach.focus.FakeClock
import dev.atlas.coach.focus.FocusPlan
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
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("atlas_local_state", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication)
            .resetControllerForTesting()
        composeRule.activityRule.scenario.recreate()
    }

    private fun openHome() = composeRule.onNodeWithContentDescription("Ouvrir Tableau").performClick()
    private fun openFocus() = composeRule.onNodeWithContentDescription("Ouvrir Focus").performClick()
    private fun openHabits() = composeRule.onNodeWithContentDescription("Ouvrir Habitudes").performClick()
    private fun openMore() = composeRule.onNodeWithContentDescription("Ouvrir Plus").performClick()
    private fun openSettings() { openMore(); composeRule.onNodeWithText("Usage quotidien").performScrollTo().performClick() }
    private fun openLibrary() { openMore(); composeRule.onNodeWithText("Bibliothèque").performScrollTo().performClick() }
    private fun openCoach() { openMore(); composeRule.onNodeWithText("Coach et journal").performScrollTo().performClick() }

    @Test
    fun backReturnsFromDailySettingsToMoreAndTabsSelectTheirRoot() {
        openMore()
        composeRule.onNodeWithText("Usage quotidien").performScrollTo().performClick()
        composeRule.onNodeWithText("Accueil Android").performScrollTo().assertIsDisplayed()
        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.onNodeWithText("Autres espaces").assertIsDisplayed()

        openFocus()
        composeRule.onNodeWithText("Un pas à la fois").assertIsDisplayed()
        openHome()
        composeRule.onNodeWithText("La prochaine étape").assertIsDisplayed()
    }

    @Test
    fun directiveFocusHabitSimulatorAndLibraryJourneysWorkOffline() {
        composeRule.onNodeWithText("Tableau de bord").assertIsDisplayed()
        composeRule.onNodeWithText("Lis/avance 10 min").assertIsDisplayed()

        composeRule.onNodeWithText("Commencer").performClick()
        composeRule.onNodeWithText("Session liée à : Lis/avance 10 min").assertIsDisplayed()
        composeRule.onNodeWithText("Mettre en pause").performScrollTo().performClick()
        composeRule.onNodeWithText("Reprendre").performScrollTo().assertIsDisplayed()
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("Reprendre").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Session liée à : Lis/avance 10 min").assertIsDisplayed()
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
        composeRule.onNodeWithText("Discuter").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Alternative").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Reporter").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Pause").performScrollTo().assertIsDisplayed()
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
        composeRule.onNodeWithText("Concentration · En cours").assertIsDisplayed()
        openHome()
        openSettings()
        composeRule.onNodeWithContentDescription("Activer ou désactiver Focus").performClick()
        openHome()
        openFocus()
        composeRule.onNodeWithText("Concentration · En pause").assertIsDisplayed()
        composeRule.onAllNodesWithText("Reprendre").assertCountEquals(0)
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
        composeRule.onNodeWithText("Concentration · En cours").assertIsDisplayed()
    }

    @Test
    fun homeRoleSettingsAreSeparateFromTheCoachDashboardAndLicenseAttributionIsVisible() {
        openSettings()
        composeRule.onNodeWithText("Accueil Android").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Choisir ATLAS comme accueil…").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Activer ou désactiver Accueil ATLAS facultatif").assertCountEquals(0)
        composeRule.onNodeWithText("À propos et licences").performScrollTo().performClick()
        composeRule.onNodeWithText("Le code source ATLAS est diffusé sous GNU GPL version 3.0.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Reef par Pranav Purwar", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("github.com/aload0/Reef").assertIsDisplayed()
        composeRule.onNodeWithText("Olauncher · modèle de liste d'applications").assertIsDisplayed()
        composeRule.onNodeWithText("Loop Habit Tracker · force d'habitude et CSV").assertIsDisplayed()

        openHome()
        openMore()
        composeRule.onAllNodesWithText("Accueil et applications favorites").assertCountEquals(0)
        openHome()
        composeRule.onNodeWithText("La prochaine étape").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(AndroidLauncherCatalog(context).list().none { it.appPackage == context.packageName })
    }

    @Test
    fun finishedFocusHistoryRestoresFromLocalPersistence() {
        openFocus()
        composeRule.onNodeWithText("Mode démo · 20 secondes").performScrollTo().performClick()
        composeRule.onNodeWithText("Terminer").performScrollTo().performClick()
        composeRule.onNodeWithText("Statistiques de focus").performScrollTo().performClick()
        composeRule.onNodeWithText("1 sessions archivées", substring = true).assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        openFocus()
        composeRule.onNodeWithText("Statistiques de focus").performScrollTo().performClick()
        composeRule.onNodeWithText("1 sessions archivées", substring = true).assertIsDisplayed()
    }

    @Test
    fun focusRoutineIsCapabilityGatedAndRequiresExplicitStart() {
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
        composeRule.onNodeWithText("Démarrer le Pomodoro").performScrollTo().performClick()
        composeRule.onNodeWithText("Pomodoro démarré · 25 min de concentration, pause courte de 5 min", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Cycle 1 / 4").assertIsDisplayed()
    }

    @Test
    fun malformedPolicyAndRuleSubstatesAreQuarantinedWithoutStartupCrash() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("atlas_local_state", android.content.Context.MODE_PRIVATE)
        val raw = """{"schemaVersion":1,"policy":{"quietStart":"bad-time","quietEnd":"07:00"},"rules":{"recentEventsByRuleAndPackage":{"rule\\u0000app":[{"packageName":"app","timestampUtc":10}]}}}"""
        prefs.edit().putString("state_v1", raw).commit()
        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithText("Tableau de bord").assertIsDisplayed()
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

        composeRule.onNodeWithText("Tableau de bord").assertIsDisplayed()
        composeRule.onNodeWithText("version de schéma inconnue", substring = true).assertIsDisplayed()
        assertTrueQuarantineContains(prefs.all, raw)
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
}
