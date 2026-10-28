package dev.atlas.coach.sync.relay

import android.os.SystemClock
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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val controller = (context.applicationContext as AtlasApplication).controller
        var previousConnectionTimestamp = 0L
        instrumentation.runOnMainSync {
            previousConnectionTimestamp = controller.relaySettings.lastConnectedAtUtcMillis
        }

        assertR3bCredentialRemoved(context)
        instrumentation.runOnMainSync { controller.relaySettings.onAppResumed() }

        assertTrue(
            "The relaunched app did not settle in PAIRING_REQUIRED",
            awaitRelayState(controller.relaySettings, RelayConnectionState.PAIRING_REQUIRED, timeoutMillis = 5_000),
        )
        var observedMode: RelayTransportMode? = null
        var observedState: RelayConnectionState? = null
        var credentialsConfigured = true
        var deviceId = "not-cleared"
        var serverPublicKey = "not-cleared"
        var observedConnectionTimestamp = -1L
        var lastError: String? = null
        instrumentation.runOnMainSync {
            observedMode = controller.relaySettings.mode
            observedState = controller.relaySettings.state
            credentialsConfigured = controller.relaySettings.credentialsConfigured
            deviceId = controller.relaySettings.deviceId
            serverPublicKey = controller.relaySettings.serverPublicKey
            observedConnectionTimestamp = controller.relaySettings.lastConnectedAtUtcMillis
            lastError = controller.relaySettings.lastError
        }
        assertEquals(RelayTransportMode.RELAY, observedMode)
        assertEquals(RelayConnectionState.PAIRING_REQUIRED, observedState)
        assertFalse(credentialsConfigured)
        assertEquals("", deviceId)
        assertEquals("", serverPublicKey)
        assertEquals(previousConnectionTimestamp, observedConnectionTimestamp)
        assertTrue(
            "After-clear lastError must be absent or describe the pairing requirement",
            lastError == null || lastError.orEmpty().contains("appair", ignoreCase = true),
        )

        SystemClock.sleep(1_000)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            observedState = controller.relaySettings.state
            credentialsConfigured = controller.relaySettings.credentialsConfigured
            observedConnectionTimestamp = controller.relaySettings.lastConnectedAtUtcMillis
        }
        assertEquals("The app reconnected without credentials after relaunch", RelayConnectionState.PAIRING_REQUIRED, observedState)
        assertFalse(credentialsConfigured)
        assertEquals(previousConnectionTimestamp, observedConnectionTimestamp)
    }
}
