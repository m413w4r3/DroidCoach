package dev.atlas.coach.app

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.atlas.coach.sync.relay.AndroidRelaySettingsStore
import dev.atlas.coach.sync.relay.RelayConnectionState
import dev.atlas.coach.sync.relay.RelayTransportMode
import dev.atlas.coach.sync.relay.assertR3bTokenNotPlaintext
import dev.atlas.coach.sync.relay.connectR3bRelay
import dev.atlas.coach.sync.relay.r3bCredentialStore
import dev.atlas.coach.sync.relay.requireR3bRelayArguments
import dev.atlas.coach.sync.relay.writeR3bA4ClearMarker
import dev.atlas.coach.test.BackendArgumentsAwareRunner
import dev.atlas.coach.test.RequiresBackendArguments
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Waits for the parent-triggered PC revocation, then clears credentials through the production UI. */
@RunWith(BackendArgumentsAwareRunner::class)
class R3bA4RevocationInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private var connectedMarker: File? = null

    @After
    fun removePublicPhaseMarker() {
        connectedMarker?.delete()
    }

    @Test
    @RequiresBackendArguments(
        keys = ["relay_url", "server_id", "server_public_key"],
        reason = "A4 revocation requires the parent's non-secret relay identity arguments",
    )
    fun revokedSessionIsReportedAndExplicitUiClearRemovesCredentials() {
        val arguments = requireR3bRelayArguments()
        val context = composeRule.activity.applicationContext
        connectedMarker = File(context.filesDir, "r3b-a4-connected.marker").also { it.delete() }
        val controller = (composeRule.activity.application as AtlasApplication).controller
        var initiallyConfigured = false
        var configuredServerId = ""
        var configuredServerPublicKey = ""
        var connectedDeviceId = ""
        composeRule.runOnUiThread {
            initiallyConfigured = controller.relaySettings.credentialsConfigured
            configuredServerId = controller.relaySettings.serverId
            configuredServerPublicKey = controller.relaySettings.serverPublicKey
            connectedDeviceId = controller.relaySettings.deviceId
        }
        assertTrue("The first device is not paired", initiallyConfigured)
        assertEquals(arguments.serverId, configuredServerId)
        assertEquals(arguments.serverPublicKey, configuredServerPublicKey)
        assertTrue("The stored device identity is empty", connectedDeviceId.isNotBlank())

        connectR3bRelay(controller.relaySettings)
        composeRule.waitUntil(timeoutMillis = 25_000) {
            controller.relaySettings.state == RelayConnectionState.RELAY_CONNECTED
        }
        var stateAfterConnect: RelayConnectionState? = null
        composeRule.runOnUiThread { stateAfterConnect = controller.relaySettings.state }
        assertEquals(RelayConnectionState.RELAY_CONNECTED, stateAfterConnect)
        val credentials = r3bCredentialStore(context).credentials()
        assertNotNull("The Keystore credential disappeared before revocation", credentials)
        val storedToken = requireNotNull(credentials).deviceToken
        assertR3bTokenNotPlaintext(context, storedToken)

        connectedMarker?.writeText("connected:${System.currentTimeMillis()}\n", Charsets.UTF_8)
        composeRule.waitUntil(timeoutMillis = 30_000) {
            controller.relaySettings.state == RelayConnectionState.REVOKED
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            controller.relaySettings.lastError.orEmpty().contains("révoqué", ignoreCase = true)
        }
        var revokedState: RelayConnectionState? = null
        var revocationError: String? = null
        var credentialsConfiguredAfterRevocation = false
        composeRule.runOnUiThread {
            revokedState = controller.relaySettings.state
            revocationError = controller.relaySettings.lastError
            credentialsConfiguredAfterRevocation = controller.relaySettings.credentialsConfigured
        }
        assertEquals("The Android controller did not report PC revocation within 30s", RelayConnectionState.REVOKED, revokedState)
        assertTrue("Revocation incorrectly erased the local credential before user choice", credentialsConfiguredAfterRevocation)
        assertTrue("The controller did not expose the revocation error", revocationError.orEmpty().contains("révoqué", ignoreCase = true))
        assertR3bTokenNotPlaintext(context, storedToken)
        SystemClock.sleep(1_000)
        composeRule.waitUntil(timeoutMillis = 1_000) {
            controller.relaySettings.state == RelayConnectionState.REVOKED
        }
        var stateAfterRevocationGracePeriod: RelayConnectionState? = null
        composeRule.runOnUiThread { stateAfterRevocationGracePeriod = controller.relaySettings.state }
        assertEquals("The controller attempted to reconnect after revocation", RelayConnectionState.REVOKED, stateAfterRevocationGracePeriod)

        composeRule.runOnUiThread { controller.navigationState.selectTab(AtlasScreenId.RELAY_SETTINGS) }
        composeRule.waitForIdle()
        val clearCredentialsButton = composeRule.onNodeWithText("Supprimer les identifiants de cet appareil")
        clearCredentialsButton.performScrollTo()
        clearCredentialsButton.assertIsDisplayed()
        clearCredentialsButton.performClick()
        composeRule.onNodeWithText("Supprimer l'appairage local ?").assertIsDisplayed()
        composeRule.onNodeWithText("Supprimer", substring = false).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            !controller.relaySettings.credentialsConfigured &&
                controller.relaySettings.state == RelayConnectionState.PAIRING_REQUIRED
        }
        var clearedState: RelayConnectionState? = null
        var clearedMode: RelayTransportMode? = null
        var credentialsConfiguredAfterClear = true
        var clearedDeviceId = "not-cleared"
        var clearedServerPublicKey = "not-cleared"
        composeRule.runOnUiThread {
            clearedState = controller.relaySettings.state
            clearedMode = controller.relaySettings.mode
            credentialsConfiguredAfterClear = controller.relaySettings.credentialsConfigured
            clearedDeviceId = controller.relaySettings.deviceId
            clearedServerPublicKey = controller.relaySettings.serverPublicKey
        }
        assertEquals(RelayConnectionState.PAIRING_REQUIRED, clearedState)
        assertFalse(credentialsConfiguredAfterClear)
        assertEquals("", clearedDeviceId)
        assertEquals("", clearedServerPublicKey)
        assertEquals(RelayTransportMode.RELAY, clearedMode)
        assertFalse("The clear UI left the encrypted token preference behind", context
            .getSharedPreferences(AndroidRelaySettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .contains("relay_device_token"))
        assertTrue("The cleared Keystore credential is still readable", r3bCredentialStore(context).credentials() == null)
        writeR3bA4ClearMarker(context, connectedDeviceId)
    }
}
