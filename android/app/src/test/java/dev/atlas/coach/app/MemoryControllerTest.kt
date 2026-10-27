package dev.atlas.coach.app

import dev.atlas.coach.memory.FileMemoryStore
import dev.atlas.coach.memory.MemoryCategory
import dev.atlas.coach.memory.MemoryClock
import dev.atlas.coach.memory.MemoryFact
import dev.atlas.coach.memory.MemoryProvenance
import dev.atlas.coach.memory.MemoryRetentionPolicy
import dev.atlas.coach.memory.MemorySensitivity
import java.io.File
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryControllerTest {
    @Test
    fun preferenceRequiresConsentAndIsDurable() = withTempDirectory { directory ->
        val controller = MemoryController(directory, clock = FixedClock(NOW))

        assertFalse(controller.addPreference("Use a short planning block", MemorySensitivity.NORMAL, false))
        assertTrue(controller.facts.isEmpty())
        assertTrue(controller.addPreference("  Use a short planning block  ", MemorySensitivity.SENSITIVE, true))

        val saved = controller.facts.single()
        assertEquals(MemoryCategory.PREFERENCE, saved.category)
        assertEquals("Use a short planning block", saved.statement)
        assertEquals(MemorySensitivity.SENSITIVE, saved.sensitivity)
        assertEquals("user", saved.provenance.source)
        assertTrue(saved.provenance.userConfirmed)
        assertEquals(saved, MemoryController(directory, clock = FixedClock(NOW)).facts.single())
    }

    @Test
    fun screenActionsConfirmCorrectRevokeDeleteAndPurge() = withTempDirectory { directory ->
        val storageFile = File(directory, "memory-facts.json")
        val seed = FileMemoryStore(storageFile, clock = FixedClock(NOW))
        seed.add(hypothesis(ID_1))
        seed.add(observation(ID_2, observedAt = NOW.minusSeconds(48 * 60 * 60L)))
        seed.add(preference(ID_3))
        val controller = MemoryController(directory, clock = FixedClock(NOW))

        assertTrue(controller.confirmHypothesis(ID_1, MemoryCategory.CONFIRMED_HABIT))
        assertEquals(MemoryCategory.CONFIRMED_HABIT, controller.facts.first { it.factId == ID_1 }.category)
        assertTrue(controller.correct(ID_1, "Use one planning block"))
        assertEquals("Use one planning block", controller.facts.first { it.factId == ID_1 }.statement)
        assertTrue(controller.revoke(ID_3))
        assertTrue(controller.facts.first { it.factId == ID_3 }.revoked)
        assertTrue(controller.delete(ID_3))
        assertEquals(1, controller.purgeRetention())
        assertNull(controller.facts.firstOrNull { it.factId == ID_2 })
        assertTrue(controller.facts.any { it.factId == ID_1 })
    }

    @Test
    fun corruptStateIsVisibleUntilExplicitRecovery() = withTempDirectory { directory ->
        val file = File(directory, "memory-facts.json")
        val original = "invalid memory document".toByteArray(Charsets.UTF_8)
        file.writeBytes(original)
        val controller = MemoryController(directory, clock = FixedClock(NOW))

        assertTrue(controller.quarantineState?.copyAvailable == true)
        assertTrue(controller.facts.isEmpty())
        assertFalse(controller.addPreference("Use a short planning block", MemorySensitivity.NORMAL, true))
        assertTrue(file.readBytes().contentEquals(original))

        assertTrue(controller.recoverByStartingEmpty())
        assertNull(controller.quarantineState)
        assertTrue(controller.facts.isEmpty())
    }

    private fun hypothesis(id: String) = MemoryFact(
        factId = id,
        category = MemoryCategory.HYPOTHESIS,
        statement = "A short block may help",
        provenance = MemoryProvenance("observation", observedAt = NOW, confidence = 0.6, userConfirmed = false),
        sensitivity = MemorySensitivity.NORMAL,
        createdAt = NOW,
        expiresAt = MemoryRetentionPolicy().expiryFor(MemoryCategory.HYPOTHESIS, NOW),
    )

    private fun observation(id: String, observedAt: Instant) = MemoryFact(
        factId = id,
        category = MemoryCategory.OBSERVATION,
        statement = "A planning block was completed",
        provenance = MemoryProvenance("local-observation", observedAt = observedAt, confidence = 0.7, userConfirmed = false),
        sensitivity = MemorySensitivity.NORMAL,
        createdAt = observedAt,
        expiresAt = MemoryRetentionPolicy().expiryFor(MemoryCategory.OBSERVATION, observedAt),
    )

    private fun preference(id: String) = MemoryFact(
        factId = id,
        category = MemoryCategory.PREFERENCE,
        statement = "Use a short planning block",
        provenance = MemoryProvenance("user", observedAt = NOW, confidence = 1.0, userConfirmed = true),
        sensitivity = MemorySensitivity.NORMAL,
        createdAt = NOW,
    )

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("atlas-memory-controller-test-").toFile()
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
