package dev.atlas.coach.sync.agent.inbox

import dev.atlas.coach.sync.OutboxStorage
import dev.atlas.coach.sync.agent.ProposalLevel
import dev.atlas.coach.sync.agent.ProposalState
import dev.atlas.coach.sync.agent.ProposalV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AgentInboxRepositoryTest {
    @Test
    fun inboxPersistsCursorAndDeduplicatesByIdAndIdempotencyKey() {
        val storage = MemoryStorage()
        var now = 1_000_000L
        val first = proposal("idem-1", now)
        val duplicateKey = proposal("idem-1", now, id = UUID.randomUUID().toString())
        val repository = AgentInboxRepository(storage) { now }

        val result = repository.mergePulled(listOf(first, duplicateKey, first), "cursor-1")
        assertEquals(1, result.added)
        assertEquals("cursor-1", result.cursor)

        val restored = AgentInboxRepository(storage) { now }
        assertEquals(listOf(first.proposalId), restored.visible().map { it.id })
        assertEquals("cursor-1", restored.cursor())
        assertEquals(0, restored.pendingAcks().size)

        restored.postpone(first.proposalId, now + 60_000L)
        assertTrue(restored.visible().isEmpty())
        now += 60_001L
        assertEquals(1, restored.visible().size)
        restored.update(first.proposalId, ProposalState.EXECUTED, "Session proposée")
        assertEquals(1, restored.pendingAcks().size)
        restored.markAcked(first.proposalId)
        assertFalse(restored.pendingAcks().isNotEmpty())
    }

    @Test
    fun expiredProposalsAreNotAddedAndStoredPendingItemsExpireOnRead() {
        val storage = MemoryStorage()
        var now = 2_000_000L
        val expired = proposal("expired", now - 120_000L, ttl = 60_000L)
        val repository = AgentInboxRepository(storage) { now }
        assertEquals(1, repository.mergePulled(listOf(expired), "cursor-2").expired)
        assertNull(repository.get(expired.proposalId))

        val pending = proposal("pending", now)
        repository.insertLocal(pending)
        now = pending.expiresAtUtcMillis
        assertEquals(ProposalState.EXPIRED, repository.get(pending.proposalId)?.proposal?.state)
    }

    private fun proposal(key: String, created: Long, ttl: Long = 600_000L, id: String = UUID.randomUUID().toString()) =
        ProposalV2(
            schemaVersion = 2,
            proposalId = id,
            idempotencyKey = key,
            conversationId = null,
            mandateId = "mandate-v2",
            mandateVersion = 1,
            contextVersion = "ctx-test",
            createdAtUtcMillis = created,
            expiresAtUtcMillis = created + ttl,
            actionType = "focus.propose",
            parameters = mapOf("focus_minutes" to 25, "break_minutes" to 5, "cycles" to 1),
            reason = "Une courte session peut aider.",
            requiredLevel = ProposalLevel.CONFIRM,
        )

    private class MemoryStorage : OutboxStorage {
        private var raw: String? = null
        override fun read() = raw
        override fun write(value: String) { raw = value }
    }
}
