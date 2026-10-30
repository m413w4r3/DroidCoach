package dev.atlas.coach.e2e

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.AgentCoachScreen
import dev.atlas.coach.app.AtlasController
import dev.atlas.coach.app.AtlasController.Companion.CAP_COACH
import dev.atlas.coach.app.V4_CHAT_DEFAULT_ENABLED
import dev.atlas.coach.app.ui.AtlasTheme
import dev.atlas.coach.sync.relay.RelayTransportMode
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic Compose check; compile-only in this ticket, with no transport or device execution. */
@RunWith(AndroidJUnit4::class)
class E2eV63InstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var controller: AtlasController? = null

    @After
    fun closeSyntheticController() {
        val current = controller ?: return
        composeRule.runOnUiThread {
            current.relaySettings.selectMode(RelayTransportMode.DIRECT)
            current.relaySettings.close()
        }
    }

    @Test
    fun coachV4OptInIsVisibleOffByDefaultAndDisclosesNoV2Fallback() {
        assertFalse(V4_CHAT_DEFAULT_ENABLED)
        val synthetic = AtlasController(isolatedContext()).also { controller = it }
        composeRule.runOnUiThread {
            synthetic.setCapability(CAP_COACH, true)
            synthetic.relaySettings.clearCredentials()
            // This selects relay UI state with no pairing credentials, without opening a socket.
            synthetic.relaySettings.selectMode(RelayTransportMode.RELAY)
        }

        composeRule.setContent {
            AtlasTheme { AgentCoachScreen(synthetic, pullOnOpen = false) }
        }

        composeRule.onNodeWithTag("agent_v4_chat_toggle").assertIsDisplayed()
        composeRule.onNodeWithText("Désactivée par défaut. Le chat utilise la route V2.").assertIsDisplayed()
        val v4Switch = composeRule.onAllNodes(
            isToggleable().and(hasAnyAncestor(hasTestTag("agent_v4_chat_toggle"))),
        )[0]
        v4Switch.assertIsOff()
        v4Switch.performClick()
        v4Switch.assertIsOn()
        composeRule.onNodeWithText(
            "Route V4 uniquement. En cas d'indisponibilité, aucun repli V2 ne sera tenté.",
        ).assertIsDisplayed()
    }

    private fun isolatedContext(): Context {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        return object : ContextWrapper(testContext) {
            override fun getApplicationContext(): Context = this
        }
    }
}
