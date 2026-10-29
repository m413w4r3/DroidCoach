package dev.atlas.coach.sync.agent.inbox.v4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class InboxV4OutboxTest {
    @Test
    fun offlineAckSurvivesRepositoryRestartAndReplaysWithSameIdAndBody() {
        val storage = InMemoryInboxV4Storage()
        val firstRepository = repository(storage)
        val proposal = InboxV4Fixtures.proposal()
        firstRepository.mergePulled(InboxV4PullPage(listOf(proposal), null, false), 8)
        val ack = InboxV4Fixtures.ack(
            state = InboxV4AckState.REJECTED,
            resultCode = InboxV4AckResultCode.USER_REJECTED,
            proof = null,
        )
        firstRepository.appendAck(ack)

        val offlineTransport = FakeTransport(InboxV4DeliveryResult.Offline)
        val offlineResult = firstRepository.replayPendingAcks(offlineTransport)
        val restarted = repository(storage)

        assertEquals(1, offlineResult.stillPending)
        assertEquals(ack.canonicalBody(), restarted.pendingAcks().single().ack.canonicalBody())
        val retryTransport = FakeTransport(InboxV4DeliveryResult.Delivered)
        val replayResult = restarted.replayPendingAcks(retryTransport)
        assertEquals(listOf(ack.ackId), retryTransport.sent.map { it.ackId })
        assertEquals(listOf(ack.canonicalBody()), retryTransport.sent.map { it.canonicalBody() })
        assertEquals(1, replayResult.delivered)
        assertTrue(restarted.pendingAcks().isEmpty())
        assertEquals(InboxV4ProposalState.REJECTED, restarted.get(proposal.proposalId)?.proposal?.state)
    }

    @Test
    fun uncertainResultRemainsUncertainAfterSuccessfulAckDeliveryAndRestart() {
        val storage = InMemoryInboxV4Storage()
        val repository = repository(storage)
        val proposal = InboxV4Fixtures.proposal()
        repository.mergePulled(InboxV4PullPage(listOf(proposal), null, false), 8)
        repository.recordUncertain(proposal.proposalId, consentVersion = 3)

        val restored = repository(storage)
        assertEquals(InboxV4ProposalState.UNCERTAIN, restored.get(proposal.proposalId)?.proposal?.state)
        assertEquals(InboxV4AckState.UNCERTAIN, restored.pendingAcks().single().ack.state)
        restored.replayPendingAcks(FakeTransport(InboxV4DeliveryResult.Delivered))

        assertEquals(InboxV4ProposalState.UNCERTAIN, restored.get(proposal.proposalId)?.proposal?.state)
        assertEquals(InboxV4AckState.UNCERTAIN, restored.ackHistory(proposal.proposalId).single().state)
        assertFalse(restored.get(proposal.proposalId)?.proposal?.state == InboxV4ProposalState.APPLIED)
    }

    @Test
    fun exactAckReplayIsIdempotentButChangedBodyAndReusedSequenceConflict() {
        val repository = repository(InMemoryInboxV4Storage())
        val proposal = InboxV4Fixtures.proposal()
        repository.mergePulled(InboxV4PullPage(listOf(proposal), null, false), 8)
        val ack = InboxV4Fixtures.ack(
            state = InboxV4AckState.REJECTED,
            resultCode = InboxV4AckResultCode.USER_REJECTED,
            proof = null,
        )
        assertEquals(InboxV4AppendResult.Appended, repository.appendAck(ack))
        assertEquals(InboxV4AppendResult.Duplicate, repository.appendAck(ack))

        val changedBody = ack.copy(occurredAt = ack.occurredAt.plusSeconds(1))
        assertEquals("ACK_ID_CONFLICT", runCatching { repository.appendAck(changedBody) }
            .exceptionOrNull()?.message)
        val sameSequence = ack.copy(ackId = "00000000-0000-4000-8000-000000000616")
        assertEquals("ACK_SEQUENCE_CONFLICT", runCatching { repository.appendAck(sameSequence) }
            .exceptionOrNull()?.message)
        assertEquals(1, repository.ackHistory().size)
    }

    @Test
    fun failedAckPersistenceLeavesMemoryUntouchedAndSameAckRetryCommits() {
        val storage = FailOnceStorage()
        val repository = repository(storage)
        val proposal = InboxV4Fixtures.proposal()
        repository.mergePulled(InboxV4PullPage(listOf(proposal), null, false), 8)
        val stateBeforeAck = storage.delegate.raw
        val ack = InboxV4Fixtures.ack(
            state = InboxV4AckState.REJECTED,
            resultCode = InboxV4AckResultCode.USER_REJECTED,
            proof = null,
        )
        storage.failNextWrite = true

        assertFalse(runCatching { repository.appendAck(ack) }.isSuccess)
        assertEquals(emptyList<InboxV4Ack>(), repository.ackHistory())
        assertTrue(repository.pendingAcks().isEmpty())
        assertEquals(InboxV4ProposalState.RECEIVED, repository.get(proposal.proposalId)?.proposal?.state)
        assertEquals(stateBeforeAck, storage.delegate.raw)

        assertEquals(InboxV4AppendResult.Appended, repository.appendAck(ack))
        val restored = repository(storage)
        assertEquals(listOf(ack.canonicalBody()), restored.ackHistory().map { it.canonicalBody() })
        assertEquals(InboxV4ProposalState.REJECTED, restored.get(proposal.proposalId)?.proposal?.state)
    }

    @Test
    fun failedUncertainAckPersistenceDoesNotRetainProofOrMutateOutbox() {
        val storage = FailOnceStorage()
        val repository = repository(storage)
        val proposal = InboxV4Fixtures.proposal()
        repository.mergePulled(InboxV4PullPage(listOf(proposal), null, false), 8)
        storage.failNextWrite = true
        val ackId = "00000000-0000-4000-8000-000000000618"

        assertFalse(runCatching {
            repository.recordUncertain(
                proposal.proposalId,
                consentVersion = 3,
                knownApplicationProof = InboxV4Fixtures.proof(),
                ackId = ackId,
            )
        }.isSuccess)
        assertEquals(InboxV4ProposalState.RECEIVED, repository.get(proposal.proposalId)?.proposal?.state)
        assertNull(repository.get(proposal.proposalId)?.applicationProof)
        assertTrue(repository.ackHistory().isEmpty())
        assertTrue(repository.pendingAcks().isEmpty())

        assertEquals(InboxV4AppendResult.Appended, repository.recordUncertain(
            proposal.proposalId,
            consentVersion = 3,
            knownApplicationProof = InboxV4Fixtures.proof(),
            ackId = ackId,
        ))
        val restored = repository(storage)
        assertEquals(InboxV4AckState.UNCERTAIN, restored.ackHistory().single().state)
        assertEquals(InboxV4Fixtures.proof(), restored.get(proposal.proposalId)?.applicationProof)
    }

    @Test
    fun uncertainAckDeliveryStatusIsPersistedAndReplayedWithoutChangingState() {
        val storage = InMemoryInboxV4Storage()
        val repository = repository(storage)
        val proposal = InboxV4Fixtures.proposal()
        repository.mergePulled(InboxV4PullPage(listOf(proposal), null, false), 8)
        repository.recordUncertain(proposal.proposalId, consentVersion = 3)
        val uncertainTransport = FakeTransport(InboxV4DeliveryResult.Uncertain)

        val result = repository.replayPendingAcks(uncertainTransport)
        val restored = repository(storage)

        assertEquals(1, result.uncertain)
        assertEquals(InboxV4AckDeliveryState.UNCERTAIN, restored.pendingAcks().single().deliveryState)
        assertEquals(InboxV4ProposalState.UNCERTAIN, restored.get(proposal.proposalId)?.proposal?.state)
    }

    @Test
    fun fileStorageKeepsOfflineAckAcrossRepositoryRestart() {
        val directory = Files.createTempDirectory("atlas-inbox-v4-").toFile()
        try {
            val storage = FileInboxV4Storage(directory.resolve("inbox-v4.json"))
            val first = repository(storage)
            val proposal = InboxV4Fixtures.proposal()
            first.mergePulled(InboxV4PullPage(listOf(proposal), null, false), 8)
            val ack = InboxV4Fixtures.ack(
                state = InboxV4AckState.REJECTED,
                resultCode = InboxV4AckResultCode.USER_REJECTED,
                proof = null,
            )
            first.appendAck(ack)
            first.replayPendingAcks(FakeTransport(InboxV4DeliveryResult.Offline))

            val restarted = repository(storage)
            assertEquals(ack.canonicalBody(), restarted.pendingAcks().single().ack.canonicalBody())
            restarted.replayPendingAcks(FakeTransport(InboxV4DeliveryResult.Delivered))
            assertTrue(restarted.pendingAcks().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun repository(storage: InboxV4Storage) = InboxV4Repository(storage, now = { InboxV4Fixtures.CREATED_AT })

    private class FailOnceStorage : InboxV4Storage {
        val delegate = InMemoryInboxV4Storage()
        var failNextWrite = false

        override fun read(): String? = delegate.read()

        override fun write(serializedState: String) {
            if (failNextWrite) {
                failNextWrite = false
                error("injected storage failure")
            }
            delegate.write(serializedState)
        }

        override fun quarantine(raw: String, reason: String): String? = delegate.quarantine(raw, reason)
    }

    private class FakeTransport(private val result: InboxV4DeliveryResult) : InboxV4Transport {
        val sent = mutableListOf<InboxV4Ack>()
        override fun pull(cursor: String?, pageSize: Int) = error("pull not expected")
        override fun ack(ack: InboxV4Ack): InboxV4DeliveryResult {
            sent += ack
            return result
        }
    }
}
