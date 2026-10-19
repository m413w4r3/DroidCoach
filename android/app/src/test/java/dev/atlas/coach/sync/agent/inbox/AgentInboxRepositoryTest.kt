package dev.atlas.coach.sync.agent.inbox

import dev.atlas.coach.sync.OutboxStorage
import dev.atlas.coach.sync.agent.AgentBackendObservation
import dev.atlas.coach.sync.agent.AgentBackendState
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

    @Test
    fun acceptedProposalRemainsAvailableForEffectReconciliationAfterTtl() {
        val storage = MemoryStorage()
        var now = 3_000_000L
        val accepted = proposal("accepted", now)
        val repository = AgentInboxRepository(storage) { now }
        repository.insertLocal(accepted)
        repository.update(accepted.proposalId, ProposalState.ACCEPTED, "Effet préparé")

        now = accepted.expiresAtUtcMillis + 1L

        assertEquals(ProposalState.ACCEPTED, repository.get(accepted.proposalId)?.proposal?.state)
    }

    @Test
    fun backendSuccessIsPersistedAndBecomesUnverifiedAfterFreshnessTtl() {
        val storage = MemoryStorage()
        var now = 10_000L
        val repository = AgentInboxRepository(storage) { now }
        repository.mergePulled(emptyList(), "cursor-3")
        val restored = AgentInboxRepository(storage) { now }

        assertEquals(AgentBackendState.REACHABLE, restored.backendObservation().stateAt(now))
        assertEquals(now, restored.backendObservation().lastSuccessfulContactAtUtcMillis)

        now += AgentBackendObservation.FRESHNESS_TTL_MILLIS + 1L
        assertEquals(AgentBackendState.UNVERIFIED, restored.backendObservation().stateAt(now))
    }

    @Test
    fun failedBackendContactAndLastSuccessSurviveRepositoryRestart() {
        val storage = MemoryStorage()
        val repository = AgentInboxRepository(storage) { 10_000L }
        repository.markBackendContactSucceeded(10_000L)
        repository.markBackendUnavailable(11_000L)

        val restored = AgentInboxRepository(storage) { 11_001L }

        assertEquals(AgentBackendState.UNAVAILABLE, restored.backendObservation().stateAt(11_001L))
        assertEquals(10_000L, restored.backendObservation().lastSuccessfulContactAtUtcMillis)
        assertEquals(11_000L, restored.backendObservation().checkedAtUtcMillis)
    }

    @Test
    fun corruptInboxIsCopiedToQuarantineBeforeFreshStateIsWritten() {
        val storage = MemoryStorage(raw = "{not-json")
        val repository = AgentInboxRepository(storage) { 50_000L }

        assertEquals("{not-json", storage.quarantinedRaw)
        assertEquals("agent-inbox.quarantine", repository.quarantineInfo()?.location)
        assertTrue(repository.quarantineInfo()?.reason?.contains("JSONException") == true)
        assertTrue(repository.all().isEmpty())

        repository.insertLocal(proposal("after-quarantine", 50_000L))
        assertTrue(storage.raw?.contains("after-quarantine") == true)
        assertEquals("{not-json", storage.quarantinedRaw)
    }

    @Test
    fun corruptInboxWithoutQuarantineSupportIsKeptReadOnly() {
        val storage = MemoryStorage(raw = "broken").apply { canQuarantine = false }
        val repository = AgentInboxRepository(storage) { 50_000L }

        assertEquals("broken", storage.raw)
        assertTrue(runCatching { repository.insertLocal(proposal("blocked", 50_000L)) }.isFailure)
        assertEquals("broken", storage.raw)
        assertNull(storage.quarantinedRaw)
    }

    @Test
    fun inboxCapacityDoesNotAdvanceCursorPastProposalsItCouldNotStore() {
        val now = 60_000L
        val repository = AgentInboxRepository(MemoryStorage()) { now }
        val page = (0 until AgentInboxRepository.MAX_ENTRIES).map { index -> proposal("capacity-$index", now) }
        assertEquals(AgentInboxRepository.MAX_ENTRIES, repository.mergePulled(page, "cursor-full").added)

        val overflow = repository.mergePulled(listOf(proposal("capacity-overflow", now)), "cursor-overflow")

        assertEquals(1, overflow.deferredForCapacity)
        assertEquals("cursor-full", overflow.cursor)
        assertNull(repository.all().firstOrNull { it.proposal.idempotencyKey == "capacity-overflow" })
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

    private class MemoryStorage(var raw: String? = null) : OutboxStorage {
        var quarantinedRaw: String? = null
        var canQuarantine = true
        override fun read() = raw
        override fun write(value: String) { raw = value }
        override fun quarantine(raw: String, category: String, reason: String): String? {
            if (!canQuarantine) return null
            quarantinedRaw = raw
            return "$category.quarantine"
        }
    }
}
