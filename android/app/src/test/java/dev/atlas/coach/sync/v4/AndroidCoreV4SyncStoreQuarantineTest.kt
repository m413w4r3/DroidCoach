package dev.atlas.coach.sync.v4

import dev.atlas.coach.sync.AndroidCoreV4SyncStore
import dev.atlas.coach.sync.CoreV4SyncPersistence
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCoreV4SyncStoreQuarantineTest {
    @Test
    fun invalidRootIsQuarantinedWithItsRawValueBeforeAnyWrite() {
        val raw = "{invalid-v4-state"
        val persistence = MemoryPersistence(raw)

        AndroidCoreV4SyncStore(persistence).presentation()

        assertEquals(listOf(raw), persistence.quarantined.map { it.first })
        assertEquals("Invalid JSON", persistence.quarantined.single().second)
        assertEquals(raw, persistence.activeValue)
        assertEquals(listOf("quarantine"), persistence.events)
        assertEquals(0, persistence.writeCount)
    }

    @Test
    fun unknownSchemaIsQuarantinedWithItsRawValue() {
        val raw = "{\"schema_version\":999,\"retained\":\"value\"}"
        val persistence = MemoryPersistence(raw)

        AndroidCoreV4SyncStore(persistence).presentation()

        assertEquals(listOf(raw), persistence.quarantined.map { it.first })
        assertEquals("Unsupported schema version", persistence.quarantined.single().second)
        assertEquals(raw, persistence.activeValue)
        assertEquals(0, persistence.writeCount)
    }

    @Test
    fun failedQuarantineRefusesMutationAndKeepsActiveValue() {
        val raw = "{invalid-v4-state"
        val persistence = MemoryPersistence(raw, quarantineSucceeds = false)
        val store = AndroidCoreV4SyncStore(persistence)

        val failure = runCatching { store.setConsent(CoreV4Category.DAY_PLAN, granted = true) }
            .exceptionOrNull()

        assertTrue("Expected writeRoot to fail closed", failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("quarantin", ignoreCase = true))
        assertEquals(raw, persistence.activeValue)
        assertEquals(0, persistence.writeCount)
        assertEquals(1, persistence.quarantineAttempts)
    }

    @Test
    fun successfulQuarantineAllowsNextMutationToWriteValidState() {
        val raw = "{\"schema_version\":999,\"retained\":\"value\"}"
        val persistence = MemoryPersistence(raw)

        AndroidCoreV4SyncStore(persistence).setConsent(CoreV4Category.DAY_PLAN, granted = true)

        assertEquals(listOf(raw), persistence.quarantined.map { it.first })
        val written = JSONObject(checkNotNull(persistence.activeValue))
        assertEquals(1, written.getInt("schema_version"))
        assertEquals("GRANTED", written.getJSONObject("consents")
            .getJSONObject(CoreV4Category.DAY_PLAN.name).getString("state"))
        assertTrue(persistence.writeCount > 0)
        assertTrue(persistence.events.indexOf("quarantine") < persistence.events.indexOf("write"))
    }

    @Test
    fun absentAndValidRootsKeepNormalPersistenceBehavior() {
        val absent = MemoryPersistence()
        AndroidCoreV4SyncStore(absent).setConsent(CoreV4Category.DAY_PLAN, granted = true)
        assertTrue(absent.quarantined.isEmpty())
        assertEquals("GRANTED", JSONObject(checkNotNull(absent.activeValue))
            .getJSONObject("consents").getJSONObject(CoreV4Category.DAY_PLAN.name)
            .getString("state"))

        val validRaw = "{\"schema_version\":1,\"retained\":\"value\"}"
        val valid = MemoryPersistence(validRaw)
        AndroidCoreV4SyncStore(valid).setConsent(CoreV4Category.DAY_PLAN, granted = true)
        assertTrue(valid.quarantined.isEmpty())
        assertEquals("value", JSONObject(checkNotNull(valid.activeValue)).getString("retained"))
        assertTrue(valid.writeCount > 0)
    }

    private class MemoryPersistence(
        initialValue: String? = null,
        private val quarantineSucceeds: Boolean = true,
    ) : CoreV4SyncPersistence {
        var activeValue: String? = initialValue
            private set
        val quarantined = mutableListOf<Pair<String, String>>()
        val events = mutableListOf<String>()
        var quarantineAttempts: Int = 0
            private set
        var writeCount: Int = 0
            private set

        override fun read(): String? = activeValue

        override fun write(value: String) {
            events += "write"
            writeCount += 1
            activeValue = value
        }

        override fun quarantine(raw: String, reason: String): Boolean {
            events += "quarantine"
            quarantineAttempts += 1
            if (!quarantineSucceeds) return false
            quarantined += raw to reason
            return true
        }
    }
}
