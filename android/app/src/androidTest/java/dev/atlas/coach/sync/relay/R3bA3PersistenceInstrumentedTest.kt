package dev.atlas.coach.sync.relay

import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.AtlasApplication
import dev.atlas.coach.test.BackendArgumentsAwareRunner
import dev.atlas.coach.test.RequiresBackendArguments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Reused after force-stop and after reboot; successful auth proves Keystore decryption survived. */
@RunWith(BackendArgumentsAwareRunner::class)
class R3bA3PersistenceInstrumentedTest {
    @Test
    @RequiresBackendArguments(
        keys = ["relay_url", "server_id", "server_public_key"],
        reason = "A3 persistence requires the parent's non-secret relay identity arguments",
    )
    fun persistedCredentialDecryptsAndReconnectsWithoutTokenFile() {
        val arguments = requireR3bRelayArguments()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val marker = readR3bA3Marker(context)
        val optionalTokenFile = InstrumentationRegistry.getArguments()
            .getString("device_token_file")
            ?.let { File(context.filesDir, it) }
        val controller = (context.applicationContext as AtlasApplication).controller

        assertFalse("A3 persistence must not receive a replacement token file", optionalTokenFile?.exists() == true)
        assertTrue("The relay credential is not configured after restart", controller.relaySettings.credentialsConfigured)
        assertEquals(arguments.serverId, controller.relaySettings.serverId)
        assertEquals(arguments.serverPublicKey, controller.relaySettings.serverPublicKey)
        assertEquals(marker.deviceId, controller.relaySettings.deviceId)
        assertEquals(sha256Hex(arguments.serverPublicKeyBytes), marker.serverPublicKeySha256)

        val credentials = r3bCredentialStore(context).credentials()
        assertNotNull("The Android Keystore did not decrypt the persisted credential", credentials)
        assertEquals(marker.deviceId, requireNotNull(credentials).deviceId)
        assertEquals(marker.deviceTokenSha256, sha256Hex(credentials.deviceToken))
        assertEquals(marker.serverPublicKeySha256, sha256Hex(credentials.remoteStaticPublicKey))
        assertR3bTokenNotPlaintext(context, credentials.deviceToken)

        connectR3bRelay(controller.relaySettings)
        assertEquals(RelayConnectionState.RELAY_CONNECTED, controller.relaySettings.state)
        assertTrue(controller.relaySettings.credentialsConfigured)
        assertEquals(marker.deviceId, controller.relaySettings.deviceId)
    }
}
