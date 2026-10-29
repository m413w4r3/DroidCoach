package dev.atlas.coach.sync.agent.inbox.v4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class InboxV4RepositoryTest {
    @Test
    fun repeatedPullIsNoOpAndIdempotencyBodyConflictIsPreserved() {
        val storage = InMemoryInboxV4Storage()
        val repository = repository(storage)
        val proposal = InboxV4Fixtures.proposal()

        assertEquals(1, repository.mergePulled(InboxV4PullPage(listOf(proposal), "c1", true), 10).added)
        val repeated = repository.mergePulled(InboxV4PullPage(listOf(proposal), "c1", true), 10)
        assertEquals(1, repeated.duplicates)
        assertEquals(InboxV4ProposalState.RECEIVED, repository.get(proposal.proposalId)?.proposal?.state)

        val conflict = InboxV4Fixtures.proposal(
            proposalId = "00000000-0000-4000-8000-000000000777",
            idempotencyKey = proposal.idempotencyKey,
        )
        val result = repository.mergePulled(InboxV4PullPage(listOf(conflict), "c2", false), 10)

        assertEquals(1, result.conflicts)
        assertEquals("c1", result.cursor)
        assertEquals(1, repository.all().size)
        assertEquals(1, repository.quarantinedPayloads().size)
    }

    @Test
    fun duplicateAcrossPagesDoesNotDuplicateEntryAndCursorTracksPullPages() {
        val repository = repository(InMemoryInboxV4Storage())
        val first = InboxV4Fixtures.proposal()
        val second = InboxV4Fixtures.proposal(
            proposalId = "00000000-0000-4000-8000-000000000701",
            idempotencyKey = "00000000-0000-4000-8000-000000000702",
        )
        val transport = FakeTransport(
            pages = ArrayDeque(listOf(
                InboxV4PullPage(listOf(first), "page-2", true),
                InboxV4PullPage(listOf(first, second), null, false),
            )),
        )

        val page1 = repository.pullNextPage(transport, 16)
        val page2 = repository.pullNextPage(transport, 16)

        assertEquals(listOf(null, "page-2"), transport.pullCursors)
        assertEquals(1, page1.added)
        assertEquals(1, page2.duplicates)
        assertEquals(1, page2.added)
        assertNull(repository.cursor())
        assertEquals(2, repository.all().size)
    }

    @Test
    fun truncatedPageRequiresCursorAndCapacityDoesNotAdvanceCursor() {
        val now = InboxV4Fixtures.CREATED_AT
        val repository = InboxV4Repository(InMemoryInboxV4Storage(), { now }, maxEntries = 1)
        val one = InboxV4Fixtures.proposal()
        val two = InboxV4Fixtures.proposal(
            proposalId = "00000000-0000-4000-8000-000000000711",
            idempotencyKey = "00000000-0000-4000-8000-000000000712",
        )

        val result = repository.mergePulled(InboxV4PullPage(listOf(one, two), "next", true), 2)
        assertEquals(1, result.deferredForCapacity)
        assertNull(repository.cursor())
        assertFalse(runCatching {
            repository.mergePulled(InboxV4PullPage(emptyList(), null, true), 2)
        }.isSuccess)
    }

    @Test
    fun expiredProposalProducesDurableExpiredAckWithoutApplicationProof() {
        val clock = MutableClock(InboxV4Fixtures.CREATED_AT)
        val storage = InMemoryInboxV4Storage()
        val repository = InboxV4Repository(storage, clock::now)
        val proposal = InboxV4Fixtures.proposal()
        repository.mergePulled(InboxV4PullPage(listOf(proposal), null, false), 8)
        clock.value = proposal.expiresAt

        val expired = repository.expireDue(consentVersion = 3).single()

        assertEquals(InboxV4AckState.EXPIRED, expired.state)
        assertNull(expired.proof)
        assertEquals(InboxV4ProposalState.EXPIRED, repository.get(proposal.proposalId)?.proposal?.state)
        assertEquals(1, repository.pendingAcks().size)
    }

    @Test
    fun appliedNeedsProofAndOnlyAllowedTransitionsCanBeAppended() {
        val repository = repository(InMemoryInboxV4Storage())
        val proposal = InboxV4Fixtures.proposal()
        repository.mergePulled(InboxV4PullPage(listOf(proposal), null, false), 8)
        repository.markAwaitingUser(proposal.proposalId)

        assertFalse(runCatching { InboxV4Fixtures.ack(proof = null) }.isSuccess)
        val valid = InboxV4Fixtures.ack(state = InboxV4AckState.REJECTED,
            resultCode = InboxV4AckResultCode.USER_REJECTED, proof = null)
        repository.appendAck(valid)
        assertEquals(InboxV4ProposalState.REJECTED, repository.get(proposal.proposalId)?.proposal?.state)

        val invalid = InboxV4Fixtures.ack(
            ackId = "00000000-0000-4000-8000-000000000612",
            sequence = 2,
            state = InboxV4AckState.APPLIED,
            resultCode = InboxV4AckResultCode.APPLIED,
        )
        assertFalse(runCatching { repository.appendAck(invalid) }.isSuccess)
        assertEquals(1, repository.ackHistory().size)
    }

    @Test
    fun appliedAckWithCompleteProofIsAccepted() {
        val repository = repository(InMemoryInboxV4Storage())
        val proposal = InboxV4Fixtures.proposal()
        repository.mergePulled(InboxV4PullPage(listOf(proposal), null, false), 8)
        val ack = InboxV4Fixtures.ack()

        repository.appendAck(ack)

        assertEquals(InboxV4ProposalState.APPLIED, repository.get(proposal.proposalId)?.proposal?.state)
        assertEquals(InboxV4Fixtures.proof(), repository.get(proposal.proposalId)?.applicationProof)
    }

    @Test
    fun reconciledAppliedMustMatchPreviouslyRecordedProof() {
        val repository = repository(InMemoryInboxV4Storage())
        val proposal = InboxV4Fixtures.proposal()
        repository.mergePulled(InboxV4PullPage(listOf(proposal), null, false), 8)
        val proof = InboxV4Fixtures.proof()
        repository.recordUncertain(proposal.proposalId, consentVersion = 3, knownApplicationProof = proof)
        val wrongProof = proof.copy(appliedPlanVersion = proof.appliedPlanVersion + 1)
        val conflictingReconciliation = InboxV4Fixtures.ack(
            ackId = "00000000-0000-4000-8000-000000000613",
            sequence = 2,
            state = InboxV4AckState.RECONCILED,
            resultCode = InboxV4AckResultCode.RECONCILED_APPLIED,
            proof = wrongProof,
        )

        assertFalse(runCatching { repository.appendAck(conflictingReconciliation) }.isSuccess)
        assertEquals(InboxV4ProposalState.UNCERTAIN, repository.get(proposal.proposalId)?.proposal?.state)

        repository.appendAck(conflictingReconciliation.copy(
            ackId = "00000000-0000-4000-8000-000000000614",
            proof = proof,
        ))
        assertEquals(InboxV4ProposalState.RECONCILED, repository.get(proposal.proposalId)?.proposal?.state)
        assertEquals(proof, repository.get(proposal.proposalId)?.applicationProof)
    }

    @Test
    fun reconciledAppliedWithoutRetainedLocalProofIsRejected() {
        val repository = repository(InMemoryInboxV4Storage())
        val proposal = InboxV4Fixtures.proposal()
        repository.mergePulled(InboxV4PullPage(listOf(proposal), null, false), 8)
        repository.recordUncertain(proposal.proposalId, consentVersion = 3)
        val reconciliation = InboxV4Fixtures.ack(
            ackId = "00000000-0000-4000-8000-000000000615",
            sequence = 2,
            state = InboxV4AckState.RECONCILED,
            resultCode = InboxV4AckResultCode.RECONCILED_APPLIED,
        )

        assertFalse(runCatching { repository.appendAck(reconciliation) }.isSuccess)
        assertEquals(InboxV4ProposalState.UNCERTAIN, repository.get(proposal.proposalId)?.proposal?.state)
    }

    @Test
    fun rejectedAndUncertainAcksCannotCarryApplicationProof() {
        assertFalse(runCatching {
            InboxV4Fixtures.ack(
                state = InboxV4AckState.UNCERTAIN,
                resultCode = InboxV4AckResultCode.RESULT_UNCERTAIN,
            )
        }.isSuccess)
        assertFalse(runCatching {
            InboxV4Fixtures.ack(
                state = InboxV4AckState.REJECTED,
                resultCode = InboxV4AckResultCode.USER_REJECTED,
            )
        }.isSuccess)
    }

    private fun repository(storage: InboxV4Storage) = InboxV4Repository(storage, now = { InboxV4Fixtures.CREATED_AT })

    private class MutableClock(var value: Instant) {
        fun now(): Instant = value
    }

    private class FakeTransport(
        private val pages: ArrayDeque<InboxV4PullPage>,
    ) : InboxV4Transport {
        val pullCursors = mutableListOf<String?>()
        override fun pull(cursor: String?, pageSize: Int): InboxV4PullPage {
            assertTrue(pageSize in 1..MAX_PAGE_SIZE)
            pullCursors += cursor
            return pages.removeFirst()
        }

        override fun ack(ack: InboxV4Ack) = InboxV4DeliveryResult.Delivered
    }
}
