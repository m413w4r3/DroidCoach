package dev.atlas.coach.sync.relay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.AtlasApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R3-C explicit clear phase: exercises the same production clearCredentials()
 * path used by the UI confirmation, after DEVICE_REVOKED was observed.
 *
 * The 4001 close assertion remains in R3bA4RevocationInstrumentedTest; the
 * pinned relay currently closes the client with 1012 instead of forwarding
 * the connector's 4001, which is recorded as an R3-C limitation.
 */
@RunWith(AndroidJUnit4::class)
class R3cA4ClearInstrumentedTest {
    @Test
    fun explicitClearRemovesCredentialKeystoreAliasAndRequiresPairing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val controller = (context.applicationContext as AtlasApplication).controller
        var configuredBefore = false
        var deviceIdBefore = ""
        instrumentation.runOnMainSync {
            configuredBefore = controller.relaySettings.credentialsConfigured
            deviceIdBefore = controller.relaySettings.deviceId
            controller.relaySettings.clearCredentials()
        }

        assertTrue("The revocation phase left no credential to clear", configuredBefore)
        assertTrue("The cleared device id is empty", deviceIdBefore.isNotBlank())
        assertFalse("The credential is still configured after clear", controller.relaySettings.credentialsConfigured)
        assertEquals("", controller.relaySettings.deviceId)
        assertEquals(RelayConnectionState.PAIRING_REQUIRED, controller.relaySettings.state)
        assertR3bCredentialRemoved(context)
        assertFalse("The Keystore AES alias remains after clear", r3bRelayKeystoreAliasPresent())
        writeR3bA4ClearMarker(context, deviceIdBefore)
    }
}
