package dev.atlas.coach.goals

import dev.atlas.coach.data.LocalStateStore
import dev.atlas.coach.data.PersistWriteException
import dev.atlas.coach.data.StateBackend
import dev.atlas.coach.data.StateReadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalPersistenceTransactionTest {
    @Test
    fun failedCreateUpdateStatusConfirmationAndLinkWritesKeepCacheEqualToDiskAfterRecreation() {
        val harness = Harness()
        val repository = harness.repository()
        val manual = goal("manual", GoalMetric.MANUAL)

        harness.failNextWrite()
        assertThrows(PersistWriteException::class.java) { repository.add(manual) }
        assertTrue(repository.all().isEmpty())
        harness.assertMatchesDisk(repository)

        repository.add(manual)
        harness.failNextWrite()
        assertThrows(PersistWriteException::class.java) {
            repository.update(manual.copy(title = "Titre modifié", updatedAt = 101L))
        }
        assertEquals("Titre initial", repository.get(manual.id)?.title)
        harness.assertMatchesDisk(repository)

        harness.failNextWrite()
        assertThrows(PersistWriteException::class.java) {
            repository.update(manual.copy(status = GoalStatus.PAUSED, updatedAt = 101L))
        }
        assertEquals(GoalStatus.ACTIVE, repository.get(manual.id)?.status)
        harness.assertMatchesDisk(repository)

        harness.failNextWrite()
        assertThrows(PersistWriteException::class.java) { repository.confirmManualCompletion(manual.id, 101L) }
        assertEquals(GoalStatus.ACTIVE, repository.get(manual.id)?.status)
        harness.assertMatchesDisk(repository)

        val focus = goal("focus", GoalMetric.FOCUS_MINUTES).copy(targetValue = 30L)
        repository.add(focus)
        harness.failNextWrite()
        assertThrows(PersistWriteException::class.java) {
            repository.update(focus.copy(linkedFocusSessionIds = setOf("finished-session"), updatedAt = 101L))
        }
        assertTrue(repository.get(focus.id)?.linkedFocusSessionIds.orEmpty().isEmpty())
        harness.assertMatchesDisk(repository)

        harness.failNextWrite()
        assertThrows(PersistWriteException::class.java) {
            repository.completeFromProgress(
                focus.id,
                GoalProgress(focus.id, focus.metric, currentValue = 30L, targetValue = 30L),
                101L,
            )
        }
        assertEquals(GoalStatus.ACTIVE, repository.get(focus.id)?.status)
        harness.assertMatchesDisk(repository)

        val restored = harness.repository()
        assertEquals(repository.all(), restored.all())
        assertEquals("Titre initial", restored.get(manual.id)?.title)
        assertFalse(restored.get(manual.id)?.linkedFocusSessionIds.orEmpty().contains("finished-session"))
    }

    private class Harness {
        private val backend = MemoryBackend()
        private val store = LocalStateStore(backend)
        private var currentGoals: Map<String, Any?> = mapOf(
            "schema_version" to GoalRepository.GOALS_SCHEMA_VERSION,
            "goals" to emptyList<Map<String, Any?>>(),
        )

        init {
            store.write(mapOf("schemaVersion" to 1, "goals" to currentGoals))
        }

        fun repository() = GoalRepository(
            readSubdocument = { (readRoot()["goals"] as? Map<*, *>)?.goalStringMap() },
            persistSubdocument = { next ->
                val previous = currentGoals
                currentGoals = next
                try {
                    store.write(readRoot() + ("goals" to next))
                } catch (error: Exception) {
                    currentGoals = previous
                    throw error
                }
            },
        )

        fun failNextWrite() {
            backend.failNextWrite = true
        }

        fun assertMatchesDisk(repository: GoalRepository) {
            val values = (store.readDocument() as StateReadResult.Loaded).state
            val subdocument = (values["goals"] as Map<*, *>).goalStringMap()
            val diskGoals = (subdocument["goals"] as List<*>).map { (it as Map<*, *>).goalStringMap() }
                .map(GoalRecord::fromMap)
            assertEquals(repository.all(), diskGoals)
        }

        private fun readRoot() = (store.readDocument() as StateReadResult.Loaded).state
    }

    private class MemoryBackend : StateBackend {
        var value: String? = null
        var failNextWrite = false
        override fun read(): String? = value
        override fun write(value: String) {
            if (failNextWrite) {
                failNextWrite = false
                throw IllegalStateException("injected write failure")
            }
            this.value = value
        }
    }

    private fun goal(id: String, metric: GoalMetric) = GoalRecord(
        id = id,
        title = "Titre initial",
        description = "",
        metric = metric,
        targetValue = if (metric == GoalMetric.MANUAL) 1L else 1L,
        createdAt = 100L,
        updatedAt = 100L,
    )

}

private fun Map<*, *>.goalStringMap(): Map<String, Any?> = entries.associate { (key, value) ->
    (key as String) to value
}
