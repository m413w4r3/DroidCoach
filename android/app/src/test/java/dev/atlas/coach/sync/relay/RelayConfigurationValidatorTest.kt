package dev.atlas.coach.sync.relay

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class RelayConfigurationValidatorTest {
    private val publicKey = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
    private val deviceId = "4812ce79-9c7c-4316-9ea6-18ba8bb3e42b"
    private val token = "A".repeat(43)

    @Test
    fun acceptsACompleteCanonicalConfiguration() {
        assertNull(
            RelayConfigurationValidator.validate(
                relayUrl = "wss://relay.example:443",
                serverId = "atlas-pc-abc",
                serverPublicKey = publicKey,
                deviceId = deviceId,
                deviceToken = token,
                requireToken = true,
            ),
        )
    }

    @Test
    fun acceptsWssRemoteAndOnlyAllowsLoopbackWsInDebug() {
        assertNull(RelayConfigurationValidator.relayUrlError("wss://relay.example:443"))
        assertNull(RelayConfigurationValidator.relayUrlError("wss://[2001:db8::8]:8443"))
        assertNull(RelayConfigurationValidator.relayUrlError("ws://127.0.0.1:4400", debugBuild = true))
        assertNull(RelayConfigurationValidator.relayUrlError("ws://localhost:4400", debugBuild = true))
        assertNull(RelayConfigurationValidator.relayUrlError("ws://[::1]:4400", debugBuild = true))
        assertNull(RelayConfigurationValidator.relayUrlError("ws://10.0.2.2:4400", debugBuild = true))
        assertNotNull(RelayConfigurationValidator.relayUrlError("ws://127.0.0.1:4400", debugBuild = false))
        assertNotNull(RelayConfigurationValidator.relayUrlError("ws://relay.example:4400", debugBuild = true))
    }

    @Test
    fun rejectsNonWebSocketOrCredentialBearingUrls() {
        assertNotNull(RelayConfigurationValidator.relayUrlError("https://relay.example"))
        assertNotNull(RelayConfigurationValidator.relayUrlError("ws://user@relay.example:4400"))
        assertNotNull(RelayConfigurationValidator.relayUrlError("wss://@relay.example"))
        assertNotNull(RelayConfigurationValidator.relayUrlError("wss://relay.example/"))
        assertNotNull(RelayConfigurationValidator.relayUrlError("wss://relay.example:4400/path"))
        assertNotNull(RelayConfigurationValidator.relayUrlError("ws://relay.example:4400?x=1"))
        assertNotNull(RelayConfigurationValidator.relayUrlError("wss://relay.example:4400?"))
        assertNotNull(RelayConfigurationValidator.relayUrlError("wss://relay.example:4400#fragment"))
        assertNotNull(RelayConfigurationValidator.relayUrlError("wss://relay.example:4400#"))
        assertNotNull(RelayConfigurationValidator.relayUrlError("wss://relay.example:4400/ws"))
    }

    @Test
    fun rejectsMalformedHostsAndPorts() {
        for (url in listOf(
            "wss:///",
            "wss://relay..example",
            "wss://-relay.example",
            "wss://relay_.example",
            "wss://relay.example:",
            "wss://relay.example:abc",
            "wss://relay.example:0",
            "wss://relay.example:65536",
            "wss://999.1.1.1",
        )) {
            assertNotNull("expected rejection for $url", RelayConfigurationValidator.relayUrlError(url))
        }
        assertNull(RelayConfigurationValidator.relayUrlError("wss://relay.example:65535"))
    }

    @Test
    fun rejectsInvalidServerIds() {
        assertNotNull(RelayConfigurationValidator.serverIdError(""))
        assertNotNull(RelayConfigurationValidator.serverIdError("bad id"))
        assertNotNull(RelayConfigurationValidator.serverIdError("a".repeat(129)))
        assertNull(RelayConfigurationValidator.serverIdError("atlas-r3a-e2e"))
    }

    @Test
    fun rejectsWrongOrNonCanonicalPublicKeys() {
        assertNotNull(RelayConfigurationValidator.publicKeyError(""))
        assertNotNull(RelayConfigurationValidator.publicKeyError(Base64.getEncoder().encodeToString(ByteArray(31))))
        val padded = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        assertNotNull(RelayConfigurationValidator.publicKeyError(padded.trimEnd('=')))
        assertNull(RelayConfigurationValidator.publicKeyError(padded))
    }

    @Test
    fun rejectsNonCanonicalDeviceIds() {
        assertNotNull(RelayConfigurationValidator.deviceIdError("not-a-uuid"))
        assertNotNull(RelayConfigurationValidator.deviceIdError(deviceId.uppercase()))
        assertNull(RelayConfigurationValidator.deviceIdError(deviceId))
    }

    @Test
    fun rejectsInvalidTokensAndCanSkipTokenOnIdentityUpdate() {
        assertNotNull(RelayConfigurationValidator.deviceTokenError("short"))
        assertNotNull(RelayConfigurationValidator.deviceTokenError("A".repeat(44)))
        assertNotNull(RelayConfigurationValidator.deviceTokenError("A".repeat(42) + "="))
        assertNull(RelayConfigurationValidator.deviceTokenError(token))
        assertNull(
            RelayConfigurationValidator.validate(
                relayUrl = "wss://relay.example:4400",
                serverId = "atlas-r3a-e2e",
                serverPublicKey = publicKey,
                deviceId = deviceId,
                deviceToken = null,
                requireToken = false,
            ),
        )
    }
}
