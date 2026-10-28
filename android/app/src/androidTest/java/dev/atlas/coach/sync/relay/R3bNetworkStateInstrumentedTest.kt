package dev.atlas.coach.sync.relay

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.atlas.coach.app.AtlasApplication
import dev.atlas.coach.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Parent-orchestrated network transitions. The parent toggles Wi-Fi/data after
 * observing the public markers; the app must report OFFLINE and reconnect
 * without losing the local conversation.
 */
@RunWith(AndroidJUnit4::class)
class R3bNetworkStateInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun relayRecoversAfterForcedOffline() {
        val context = composeRule.activity.applicationContext
        val controller = (composeRule.activity.application as AtlasApplication).controller
        assertTrue("The repaired relay credential is not configured", controller.relaySettings.credentialsConfigured)
        val conversationBefore = controller.agentConversation.size

        connectR3bRelay(controller.relaySettings)
        assertEquals(RelayConnectionState.RELAY_CONNECTED, controller.relaySettings.state)
        marker(context, "r3b-net-connected.marker", "connected")

        composeRule.waitUntil(timeoutMillis = 60_000) {
            controller.relaySettings.state == RelayConnectionState.OFFLINE ||
                controller.relaySettings.state == RelayConnectionState.ERROR
        }
        marker(context, "r3b-net-offline.marker", controller.relaySettings.state.name)
        assertEquals("The local conversation was lost while offline", conversationBefore, controller.agentConversation.size)

        composeRule.waitUntil(timeoutMillis = 90_000) {
            controller.relaySettings.state == RelayConnectionState.RELAY_CONNECTED
        }
        assertEquals(RelayConnectionState.RELAY_CONNECTED, controller.relaySettings.state)
        assertEquals("The local conversation was lost after reconnection", conversationBefore, controller.agentConversation.size)
        marker(context, "r3b-net-recovered.marker", "connected")
    }

    @Test
    fun relayRemainsUsableAfterWifiCellularHandover() {
        val context = composeRule.activity.applicationContext
        val controller = (composeRule.activity.application as AtlasApplication).controller
        assertTrue("The repaired relay credential is not configured", controller.relaySettings.credentialsConfigured)
        val conversationBefore = controller.agentConversation.size

        connectR3bRelay(controller.relaySettings)
        assertEquals(RelayConnectionState.RELAY_CONNECTED, controller.relaySettings.state)
        marker(context, "r3b-net-handover-start.marker", "connected")

        // The parent switches Wi-Fi -> LTE/5G -> Wi-Fi, then writes the public
        // completion marker. A brief OFFLINE or a seamless transition is
        // acceptable; the final state must be connected.
        composeRule.waitUntil(timeoutMillis = 120_000) {
            File(context.filesDir, "r3b-net-handover-go.marker").isFile
        }
        composeRule.waitUntil(timeoutMillis = 90_000) {
            controller.relaySettings.state == RelayConnectionState.RELAY_CONNECTED
        }
        assertEquals(RelayConnectionState.RELAY_CONNECTED, controller.relaySettings.state)
        assertEquals("The local conversation was lost across the handover", conversationBefore, controller.agentConversation.size)
        marker(context, "r3b-net-handover-done.marker", "connected")
    }

    private fun marker(context: android.content.Context, name: String, state: String) {
        File(context.filesDir, name).writeText(
            """{"state":"$state","atUtcMillis":${System.currentTimeMillis()}}""",
            Charsets.UTF_8,
        )
    }
}
