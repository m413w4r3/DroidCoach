package dev.atlas.coach.sync.relay

import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.AtlasApplication
import dev.atlas.coach.test.BackendArgumentsAwareRunner
import dev.atlas.coach.test.RequiresBackendArguments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** First A3 phase: pair through the production controller and Android Keystore store. */
@RunWith(BackendArgumentsAwareRunner::class)
class R3bA3PairingInstrumentedTest {
    @Test
    @RequiresBackendArguments(
        keys = ["relay_url", "server_id", "server_public_key", "device_token_file"],
        reason = "A3 pairing requires the parent's synthetic relay arguments and private token file",
    )
    fun pairingPersistsOnlyKeystoreCredentialAndConnects() {
        val arguments = requireR3bRelayArguments(includeDeviceTokenFile = true)
        val tokenFileName = requireNotNull(arguments.deviceTokenFile)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val tokenInput = readR3bDeviceToken(context, tokenFileName)
        val controller = (context.applicationContext as AtlasApplication).controller
        var saved: Result<Unit>? = null

        instrumentation.runOnMainSync {
            controller.relaySettings.selectMode(RelayTransportMode.DIRECT)
            saved = controller.relaySettings.saveConfiguration(
                relayUrl = arguments.relayUrl,
                serverId = arguments.serverId,
                serverPublicKey = arguments.serverPublicKey,
                deviceId = tokenInput.deviceId,
                deviceToken = tokenInput.token,
            )
        }
        assertTrue("The production relay configuration was rejected", requireNotNull(saved).isSuccess)
        deleteR3bDeviceTokenFile(context, tokenFileName)

        assertTrue("The production controller did not retain credentials", controller.relaySettings.credentialsConfigured)
        assertEquals(tokenInput.deviceId, controller.relaySettings.deviceId)
        assertEquals(arguments.serverPublicKey, controller.relaySettings.serverPublicKey)
        assertR3bTokenNotPlaintext(context, tokenInput.token)
        val credentials = r3bCredentialStore(context).credentials()
        assertNotNull("The real Keystore store could not decrypt the relay token", credentials)
        assertEquals(tokenInput.deviceId, requireNotNull(credentials).deviceId)
        assertEquals(sha256Hex(tokenInput.token), sha256Hex(credentials.deviceToken))
        assertEquals(sha256Hex(arguments.serverPublicKeyBytes), sha256Hex(credentials.remoteStaticPublicKey))

        connectR3bRelay(controller.relaySettings)
        assertEquals(RelayConnectionState.RELAY_CONNECTED, controller.relaySettings.state)
        assertTrue(controller.relaySettings.credentialsConfigured)
        assertR3bTokenNotPlaintext(context, tokenInput.token)
        writeR3bA3Marker(
            context,
            R3bA3Marker(
                deviceId = tokenInput.deviceId,
                serverPublicKeySha256 = sha256Hex(arguments.serverPublicKeyBytes),
                deviceTokenSha256 = sha256Hex(tokenInput.token),
                createdAtUtcMillis = System.currentTimeMillis(),
            ),
        )
    }
}
