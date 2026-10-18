package dev.atlas.coach.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStateStoreTest {
    @Test
    fun nestedMapsListsNullsAndFlagsRoundTripThroughJsonBackend() {
        val backend = MemoryBackend()
        val store = LocalStateStore(backend)
        store.write(
            mapOf(
                "schemaVersion" to 1,
                "capabilities" to mapOf("focus" to true, "observation" to false),
                "habits" to listOf(mapOf("id" to "habit-1", "name" to "Lire", "archived" to null)),
            ),
        )

        val restored = store.read()
        assertEquals(1, (restored["schemaVersion"] as Number).toInt())
        val capabilities = restored["capabilities"] as Map<*, *>
        assertEquals(true, capabilities["focus"])
        assertEquals(false, capabilities["observation"])
        val habit = (restored["habits"] as List<*>).single() as Map<*, *>
        assertEquals("Lire", habit["name"])
        assertEquals(null, habit["archived"])
        assertTrue(backend.value!!.startsWith("{"))
    }

    @Test
    fun missingCorruptAndUnknownSchemaAreDifferentReadResults() {
        val missing = LocalStateStore(MemoryBackend()).readDocument()
        assertEquals(StateReadResult.Missing, missing)

        val brokenJson = "{broken"
        val corruptBackend = MemoryBackend().apply { value = brokenJson }
        val syntaxError = LocalStateStore(corruptBackend).readDocument()
        assertTrue(syntaxError is StateReadResult.Corrupt)
        assertEquals(brokenJson, (syntaxError as StateReadResult.Corrupt).rawJson)
        assertEquals(brokenJson, corruptBackend.value)

        val unknownBackend = MemoryBackend().apply { value = "{\"schemaVersion\":9,\"focus\":{}}" }
        val unknown = LocalStateStore(unknownBackend).readDocument() as StateReadResult.Corrupt
        assertTrue(unknown.reason.contains("version"))
        assertTrue(unknown.rawJson!!.contains("\"schemaVersion\":9"))
        assertEquals(unknown.rawJson, unknownBackend.value)
    }

    @Test
    fun quarantineKeepsRawBlobBeforeAReplacementIsWritten() {
        val raw = "{broken"
        val backend = MemoryBackend().apply { value = raw }
        val store = LocalStateStore(backend)
        assertTrue(store.quarantine(raw, "test corruption"))
        assertEquals(raw, backend.value)
        assertEquals(raw, backend.quarantined)
        store.write(mapOf("schemaVersion" to 1))
        assertTrue(backend.value!!.contains("schemaVersion"))
        assertEquals(raw, backend.quarantined)
    }

    private class MemoryBackend : StateBackend {
        var value: String? = null
        var quarantined: String? = null
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
        override fun quarantine(rawValue: String, reason: String): Boolean {
            quarantined = rawValue
            return true
        }
    }
}
