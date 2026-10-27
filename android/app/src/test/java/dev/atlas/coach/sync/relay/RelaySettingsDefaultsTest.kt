package dev.atlas.coach.sync.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RelaySettingsDefaultsTest {
    @Test
    fun relayDefaultsToProductionWssAndKeepsExplicitDebugLoopbackSupport() {
        assertEquals("wss://relay.example.org", RelaySettings().relayUrl)
        assertNull(RelayConfigurationValidator.relayUrlError("ws://10.0.2.2:4400", debugBuild = true))
        assertNotNull(RelayConfigurationValidator.relayUrlError("ws://10.0.2.2:4400", debugBuild = false))
    }
}
