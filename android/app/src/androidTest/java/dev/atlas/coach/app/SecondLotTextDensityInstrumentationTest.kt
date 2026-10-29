package dev.atlas.coach.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.ui.AtlasRoutes
import dev.atlas.coach.app.ui.AtlasScreenFrame
import dev.atlas.coach.app.ui.AtlasTheme
import dev.atlas.coach.sync.relay.RelayTransportMode
import dev.atlas.coach.training.SportPlanFixture
import dev.atlas.coach.training.SportPlanJson
import dev.atlas.coach.training.SportPlanParseResult
import dev.atlas.coach.training.SportPlanUiState
import dev.atlas.coach.training.SyncState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SecondLotTextDensityInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun planKeepsDaySelectorPrimaryActionsAndOnDemandDetails() {
        val context = isolatedContext()
        val planController = PlanController(File(context.cacheDir, "plan-${UUID.randomUUID()}"))
        val atlasController = AtlasController(context)

        setScreen(context, "Plan du jour") {
            PlanScreen(planController, atlasController)
        }

        composeRule.onNodeWithTag("plan_day_today").assertIsDisplayed()
        composeRule.onNodeWithTag("plan_day_tomorrow").assertIsDisplayed()
        composeRule.onNodeWithText("Préparer la journée").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Créer le programme du jour").performScrollTo().assertHeightIsAtLeast(48.dp)
        composeRule.onAllNodesWithText(
            "Le programme est stocké uniquement sur ce téléphone. Il reste lisible hors ligne et après redémarrage.",
        ).assertCountEquals(0)
        composeRule.onNodeWithText("Détails").performScrollTo().performClick()
        composeRule.onNodeWithText(
            "Le programme est stocké uniquement sur ce téléphone. Il reste lisible hors ligne et après redémarrage.",
        ).performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("Créer le programme du jour").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Aujourd’hui").assertCountEquals(2)
        composeRule.onNodeWithText("Ajouter une activité").performScrollTo().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Activités").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun relayKeepsHumanStateAndPairedPcVisibleWithTechnicalValuesAdvanced() {
        val context = isolatedContext()
        val controller = AtlasController(context)
        val relay = controller.relaySettings
        composeRule.runOnUiThread {
            relay.selectMode(RelayTransportMode.DIRECT)
            relay.clearCredentials()
        }
        val publicKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        var pairingSaved = false
        composeRule.runOnUiThread {
            pairingSaved = relay.saveConfiguration(
                relay.relayUrl,
                "atlas-test-server",
                publicKey,
                UUID.randomUUID().toString(),
                "A".repeat(43),
            ).isSuccess
        }
        assertTrue(pairingSaved)

        setScreen(context, "Connexion relayée") {
            RelaySettingsScreen(relay)
        }

        val humanState = relayHumanConnectionState(relay.mode, relay.state, relay.lastError)
        composeRule.onNodeWithText(humanState.label).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("PC ATLAS").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Modifier l'appairage").performScrollTo().assertHeightIsAtLeast(48.dp)
        composeRule.onAllNodesWithText("URL du relais").assertCountEquals(0)
        composeRule.onNodeWithText("Avancé").performScrollTo().performClick()
        composeRule.onNodeWithText("URL du relais").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Identifiant serveur (serverId)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Clé publique X25519 du PC").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Identifiant de l'appareil (UUID)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Jeton d'appareil").performScrollTo().assertIsDisplayed()

        composeRule.runOnUiThread { relay.clearCredentials() }
    }

    @Test
    fun syncKeepsStatusCategoriesAndSwitchVisibleWithConfigurationAdvanced() {
        val context = isolatedContext()
        val controller = AtlasController(context)

        setScreen(context, "Partage backend") {
            SyncSettingsScreen(controller, controller.revision)
        }

        composeRule.onNodeWithTag("sync_status_card").assertIsDisplayed()
        composeRule.onNodeWithTag("sync_enabled_switch").assertIsDisplayed()
        composeRule.onNodeWithText("Catégories partagées").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Focus").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithTag("sync_backend_url").assertCountEquals(0)
        composeRule.onNodeWithText("Configuration avancée").performScrollTo().performClick()
        composeRule.onNodeWithTag("sync_backend_url").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun settingsKeepsHomeRoleStatusAndControlsVisible() {
        val context = isolatedContext()
        val controller = AtlasController(context)

        setScreen(context, "Paramètres") {
            SettingsScreen(
                controller = controller,
                requestHomeRole = {},
                openSystemHome = {},
                onExport = {},
                requestNotifications = {},
            )
        }

        composeRule.onNodeWithText("Accueil Android").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("HOME lance vos favoris ; le tableau de bord reste dans ATLAS et Android garde le choix final.")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Choisir ATLAS comme accueil…").performScrollTo().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Gérer ou retirer le rôle Accueil").performScrollTo().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun sportKeepsHumanStateSessionResultsAndOnDemandSourceDetails() {
        val context = isolatedContext()
        val parsed = SportPlanJson.parse(SportPlanFixture.json)
        assertTrue("Expected the synthetic sport fixture to parse", parsed is SportPlanParseResult.Valid)
        val plan = (parsed as SportPlanParseResult.Valid).plan

        setScreen(context, "Sport") {
            SportScreen(
                state = SportPlanUiState(plan = plan, syncState = SyncState.FRESH),
                onRefresh = {},
            )
        }

        composeRule.onNodeWithText("À jour").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Actualiser").performScrollTo().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Séance prévue").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Résultats déclarés").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Source : fixture de démonstration").assertCountEquals(0)
        composeRule.onNodeWithTag("sport_details_toggle").performScrollTo().performClick()
        composeRule.onNodeWithText("Source : fixture de démonstration").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Synchronisation : à jour").performScrollTo().assertIsDisplayed()
    }

    private fun setScreen(context: Context, title: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                AtlasTheme {
                    AtlasScreenFrame(
                        title = title,
                        activeDestination = AtlasRoutes.HOME,
                        canGoBack = false,
                        emergencyPaused = false,
                        onBack = {},
                        onEmergencyPause = {},
                        onNavigate = {},
                    ) {
                        content()
                    }
                }
            }
        }
    }

    private fun isolatedContext(): Context {
        val base = InstrumentationRegistry.getInstrumentation().context
        val namespace = "second_lot_${UUID.randomUUID()}"
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("${namespace}_$name", mode)

            override fun getCacheDir(): File = File(base.cacheDir, namespace).apply { mkdirs() }

            override fun getFilesDir(): File = File(base.filesDir, namespace).apply { mkdirs() }
        }
    }
}
