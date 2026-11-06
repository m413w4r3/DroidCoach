package dev.atlas.coach.app

import dev.atlas.coach.sync.relay.RelayTransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LegacyDirectSyncPolicyTest {
    @Test
    fun relayHidesTheLegacyControlAndDoesNotDispatchDirectSync() {
        val directCalls = AtomicInteger()

        assertFalse(isLegacyDirectSyncAvailable(RelayTransportMode.RELAY))
        assertEquals(LEGACY_DIRECT_SYNC_LABEL, "Synchronisation directe (V1–V3)")
        assertFalse(dispatchLegacyDirectSync(RelayTransportMode.RELAY) { directCalls.incrementAndGet() > 0 })
        assertEquals(0, directCalls.get())
    }

    @Test
    fun directModeKeepsTheLegacyControlAndDispatchesOnce() {
        val directCalls = AtomicInteger()

        assertTrue(isLegacyDirectSyncAvailable(RelayTransportMode.DIRECT))
        assertTrue(dispatchLegacyDirectSync(RelayTransportMode.DIRECT) { directCalls.incrementAndGet() })
        assertEquals(1, directCalls.get())
    }
}
