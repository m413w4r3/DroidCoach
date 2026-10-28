package dev.atlas.coach.sync.relay

import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.AtlasApplication
import dev.atlas.coach.test.BackendArgumentsAwareRunner
import dev.atlas.coach.test.RequiresBackendArguments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly pairs the second synthetic PC device after the previous local credential was cleared. */
@RunWith(BackendArgumentsAwareRunner::class)
class R3bA4RepairInstrumentedTest {
    @Test
    @RequiresBackendArguments(
        keys = ["relay_url", "server_id", "server_public_key", "device_token_file"],
        reason = "A4 repair requires the parent's second synthetic device token file",
    )
    fun secondDeviceRequiresExplicitPairingAndConnects() {
        val arguments = requireR3bRelayArguments(includeDeviceTokenFile = true)
        val tokenFileName = requireNotNull(arguments.deviceTokenFile)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val previousDeviceId = readR3bA4ClearedDeviceId(context)
        assertNotNull("The first device must be explicitly cleared before repair", previousDeviceId)
        val tokenInput = readR3bDeviceToken(context, tokenFileName)
        val controller = (context.applicationContext as AtlasApplication).controller

        assertTrue("The old credential is still configured before repair", !controller.relaySettings.credentialsConfigured)
        assertR3bCredentialRemoved(context)
        assertNotEquals("Repair must use the second synthetic device identity", previousDeviceId, tokenInput.deviceId)

        controller.relaySettings.selectMode(RelayTransportMode.DIRECT)
        val saved = controller.relaySettings.saveConfiguration(
            relayUrl = arguments.relayUrl,
            serverId = arguments.serverId,
            serverPublicKey = arguments.serverPublicKey,
            deviceId = tokenInput.deviceId,
            deviceToken = tokenInput.token,
        )
        assertTrue("The explicit repair configuration was rejected", saved.isSuccess)
        deleteR3bDeviceTokenFile(context, tokenFileName)
        assertR3bTokenNotPlaintext(context, tokenInput.token)
        assertEquals(tokenInput.deviceId, r3bCredentialStore(context).credentials()?.deviceId)

        connectR3bRelay(controller.relaySettings)
        assertEquals(RelayConnectionState.RELAY_CONNECTED, controller.relaySettings.state)
        assertTrue(controller.relaySettings.credentialsConfigured)
        assertEquals(tokenInput.deviceId, controller.relaySettings.deviceId)
    }
}
