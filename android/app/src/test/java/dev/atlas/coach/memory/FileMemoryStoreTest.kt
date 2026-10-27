package dev.atlas.coach.memory

import java.io.File
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileMemoryStoreTest {
    @Test
    fun writesVersionedDocumentAndReloadsAfterReinstantiation() = withTempDirectory { directory ->
        val file = File(directory, "facts.json")
        val expected = preference(ID_1)

        FileMemoryStore(file, clock = FixedClock(NOW)).add(expected)
        val reloaded = FileMemoryStore(file, clock = FixedClock(NOW))

        assertEquals(expected, reloaded.get(ID_1))
        assertTrue(file.readText().contains("\"schema_version\":1"))
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun addConfirmCorrectDeleteAndRevokeSurviveReload() = withTempDirectory { directory ->
        val file = File(directory, "facts.json")
        val first = FileMemoryStore(file, clock = FixedClock(NOW))
        first.add(hypothesis(ID_1))
        first.confirm(ID_1, MemoryCategory.PREFERENCE)
        first.correct(ID_1, "Use one short planning block")
        first.add(preference(ID_2))
        first.revoke(ID_2)
        first.add(preference(ID_3))
        assertTrue(first.delete(ID_3))

        val reloaded = FileMemoryStore(file, clock = FixedClock(NOW))

        assertEquals("Use one short planning block", reloaded.get(ID_1)?.statement)
        assertTrue(reloaded.get(ID_1)?.provenance?.userConfirmed == true)
        assertEquals(listOf(ID_1), reloaded.list().map(MemoryFact::factId))
        assertTrue(reloaded.get(ID_2)?.revoked == true)
        assertFalse(reloaded.list(includeRevoked = true).any { it.factId == ID_3 })
    }

    @Test
    fun retentionPurgeIsPersistedAcrossReload() = withTempDirectory { directory ->
        val file = File(directory, "facts.json")
        val policy = MemoryRetentionPolicy()
        val old = NOW.minusSeconds(8 * 24 * 60 * 60L)
        val store = FileMemoryStore(file, clock = FixedClock(NOW), retentionPolicy = policy)
        store.add(hypothesis(ID_1, createdAt = old))
        store.add(preference(ID_2, createdAt = old))

        assertEquals(1, store.purgeExpired())

        val reloaded = FileMemoryStore(file, clock = FixedClock(NOW), retentionPolicy = policy)
        assertNull(reloaded.get(ID_1))
        assertNotNull(reloaded.get(ID_2))
    }

    @Test
    fun duplicateIdsAreRejectedWithoutReplacingExistingFact() = withTempDirectory { directory ->
        val store = FileMemoryStore(File(directory, "facts.json"), clock = FixedClock(NOW))
        store.add(preference(ID_1))

        assertThrows(IllegalArgumentException::class.java) { store.add(preference(ID_1)) }
        assertEquals("Use a short planning block", store.get(ID_1)?.statement)
        assertEquals(1, store.list().size)
    }

    @Test
    fun corruptFileIsCopiedAndNeverReadAsAnEmptyStore() = withTempDirectory { directory ->
        val file = File(directory, "facts.json")
        val original = "{\"synthetic_marker\":\"keep-these-bytes\"".toByteArray(Charsets.UTF_8)
        file.writeBytes(original)

        val store = FileMemoryStore(file, clock = FixedClock(NOW))
        val quarantine = store.quarantineState

        assertNotNull(quarantine)
        assertTrue(quarantine?.copyAvailable == true)
        assertTrue(quarantine?.quarantineFileName?.contains("quarantine-20260102") == true)
        assertArrayEquals(original, file.readBytes())
        val copy = File(directory, quarantine!!.quarantineFileName!!)
        assertArrayEquals(original, copy.readBytes())
        assertThrows(IllegalStateException::class.java) { store.list() }
        assertArrayEquals(original, file.readBytes())
    }

    @Test
    fun nonFileStoragePathIsNotTreatedAsMissingDocument() = withTempDirectory { directory ->
        val file = File(directory, "facts.json")
        assertTrue(file.mkdir())

        val store = FileMemoryStore(file, clock = FixedClock(NOW))

        assertNotNull(store.quarantineState)
        assertFalse(store.quarantineState?.copyAvailable == true)
        assertThrows(IllegalStateException::class.java) { store.list() }
        assertTrue(file.isDirectory)
        assertFalse(store.recoverByStartingEmpty())
        assertTrue(file.isDirectory)
    }

    @Test
    fun recoveryRequiresAnExplicitCallAndKeepsQuarantineCopy() = withTempDirectory { directory ->
        val file = File(directory, "facts.json")
        val original = "not-json; keep this exact content".toByteArray(Charsets.UTF_8)
        file.writeBytes(original)
        val store = FileMemoryStore(file, clock = FixedClock(NOW))
        val quarantineName = store.quarantineState?.quarantineFileName

        assertArrayEquals(original, file.readBytes())
        assertThrows(IllegalStateException::class.java) { store.list() }
        assertTrue(store.recoverByStartingEmpty())

        assertNull(store.quarantineState)
        assertTrue(store.list().isEmpty())
        assertEquals(emptyList<MemoryFact>(), MemoryJsonCodec().decode(file.readText()))
        assertArrayEquals(original, File(directory, quarantineName!!).readBytes())
        assertFalse(store.recoverByStartingEmpty())
    }

    private fun preference(id: String, createdAt: Instant = NOW) = MemoryFact(
        factId = id,
        category = MemoryCategory.PREFERENCE,
        statement = "Use a short planning block",
        provenance = MemoryProvenance(
            source = "user",
            observedAt = createdAt,
            confidence = 1.0,
            userConfirmed = true,
        ),
        sensitivity = MemorySensitivity.NORMAL,
        createdAt = createdAt,
    )

    private fun hypothesis(id: String, createdAt: Instant = NOW) = MemoryFact(
        factId = id,
        category = MemoryCategory.HYPOTHESIS,
        statement = "A short planning block may help",
        provenance = MemoryProvenance(
            source = "local-observation",
            observedAt = createdAt,
            confidence = 0.65,
            userConfirmed = false,
        ),
        sensitivity = MemorySensitivity.NORMAL,
        createdAt = createdAt,
        expiresAt = MemoryRetentionPolicy().expiryFor(MemoryCategory.HYPOTHESIS, createdAt),
    )

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("atlas-memory-store-test-").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class FixedClock(private val instant: Instant) : MemoryClock {
        override fun now(): Instant = instant
    }

    private companion object {
        private val NOW = Instant.parse("2026-01-02T03:04:05Z")
        private const val ID_1 = "00000000-0000-0000-0000-000000000001"
        private const val ID_2 = "00000000-0000-0000-0000-000000000002"
        private const val ID_3 = "00000000-0000-0000-0000-000000000003"
    }
}
