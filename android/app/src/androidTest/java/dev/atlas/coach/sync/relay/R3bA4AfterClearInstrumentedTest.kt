package dev.atlas.coach.sync.relay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.AtlasApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Relaunch phase after the UI clear: the app must require explicit pairing without reconnecting. */
@RunWith(AndroidJUnit4::class)
class R3bA4AfterClearInstrumentedTest {
    @Test
    fun relaunchedAppRequiresPairingAndDoesNotReconnectImplicitly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = (context.applicationContext as AtlasApplication).controller
        val previousConnectionTimestamp = controller.relaySettings.lastConnectedAtUtcMillis

        assertFalse(controller.relaySettings.credentialsConfigured)
        assertR3bCredentialRemoved(context)
        assertEquals("", controller.relaySettings.deviceId)
        assertEquals("", controller.relaySettings.serverPublicKey)

        controller.relaySettings.onAppResumed()

        assertEquals(RelayTransportMode.RELAY, controller.relaySettings.mode)
        assertEquals(RelayConnectionState.PAIRING_REQUIRED, controller.relaySettings.state)
        assertFalse(controller.relaySettings.credentialsConfigured)
        assertEquals(previousConnectionTimestamp, controller.relaySettings.lastConnectedAtUtcMillis)
        assertEquals("Aucun appareil appairé", controller.relaySettings.lastError)
    }
}
