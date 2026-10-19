package dev.atlas.coach.sync.agent.inbox

import dev.atlas.coach.sync.OutboxStorage
import dev.atlas.coach.sync.agent.ProposalState
import dev.atlas.coach.sync.agent.ProposalV2
import org.json.JSONArray
import org.json.JSONObject

data class AgentInboxEntry(
    val proposal: ProposalV2,
    val snoozedUntilUtcMillis: Long? = null,
    val ackPending: Boolean = true,
    val effect: String? = null,
) {
    val id: String get() = proposal.proposalId
}

data class AgentInboxPullResult(val added: Int, val expired: Int, val cursor: String?)

/** Durable, bounded proposal inbox. Both proposal IDs and idempotency keys are de-duplicated. */
class AgentInboxRepository(
    private val storage: OutboxStorage,
    private val nowUtcMillis: () -> Long = System::currentTimeMillis,
) {
    private val entries = linkedMapOf<String, AgentInboxEntry>()
    private var cursor: String? = null

    init { restore(storage.read()) }

    @Synchronized
    fun mergePulled(proposals: List<ProposalV2>, nextCursor: String?): AgentInboxPullResult {
        var added = 0
        var expired = 0
        proposals.forEach { proposal ->
            if (proposal.state != ProposalState.PROPOSED) return@forEach
            if (proposal.expiresAtUtcMillis <= nowUtcMillis()) {
                expired++
                return@forEach
            }
            val duplicate = entries.values.any {
                it.proposal.proposalId == proposal.proposalId ||
                    it.proposal.idempotencyKey == proposal.idempotencyKey
            }
            if (!duplicate) {
                entries[proposal.proposalId] = AgentInboxEntry(proposal)
                added++
            }
        }
        cursor = nextCursor?.takeIf { it.isNotBlank() } ?: cursor
        prune()
        persist()
        return AgentInboxPullResult(added, expired, cursor)
    }

    @Synchronized
    fun insertLocal(proposal: ProposalV2, ackPending: Boolean = false): AgentInboxEntry? {
        if (proposal.state != ProposalState.PROPOSED || proposal.expiresAtUtcMillis <= nowUtcMillis()) return null
        if (entries.values.any { it.proposal.proposalId == proposal.proposalId || it.proposal.idempotencyKey == proposal.idempotencyKey }) {
            return null
        }
        val entry = AgentInboxEntry(proposal, ackPending = ackPending)
        entries[proposal.proposalId] = entry
        prune()
        persist()
        return entry
    }

    @Synchronized
    fun get(proposalId: String): AgentInboxEntry? {
        expireStale()
        return entries[proposalId]
    }

    @Synchronized
    fun all(): List<AgentInboxEntry> {
        expireStale()
        return entries.values.toList()
    }

    @Synchronized
    fun visible(): List<AgentInboxEntry> {
        expireStale()
        val now = nowUtcMillis()
        return entries.values.filter { entry ->
            entry.proposal.state in setOf(ProposalState.PROPOSED, ProposalState.PENDING_CONFIRMATION) &&
                (entry.snoozedUntilUtcMillis == null || entry.snoozedUntilUtcMillis <= now)
        }.sortedBy { it.proposal.createdAtUtcMillis }
    }

    @Synchronized
    fun update(proposalId: String, state: ProposalState, effect: String? = null): AgentInboxEntry? {
        val old = entries[proposalId] ?: return null
        require(allowedTransition(old.proposal.state, state)) { "Invalid proposal state transition" }
        val updated = old.copy(proposal = old.proposal.withState(state), snoozedUntilUtcMillis = null, effect = effect)
        entries[proposalId] = updated
        persist()
        return updated
    }

    @Synchronized
    fun replace(proposal: ProposalV2): Boolean {
        val old = entries[proposal.proposalId] ?: return false
        if (old.proposal.state.terminal || old.proposal.idempotencyKey != proposal.idempotencyKey) return false
        entries[proposal.proposalId] = old.copy(proposal = proposal.withState(ProposalState.PENDING_CONFIRMATION))
        persist()
        return true
    }

    @Synchronized
    fun postpone(proposalId: String, untilUtcMillis: Long): AgentInboxEntry? {
        val old = entries[proposalId] ?: return null
        if (old.proposal.state !in setOf(ProposalState.PROPOSED, ProposalState.PENDING_CONFIRMATION)) return null
        val updated = old.copy(
            proposal = old.proposal.withState(ProposalState.PENDING_CONFIRMATION),
            snoozedUntilUtcMillis = untilUtcMillis,
        )
        entries[proposalId] = updated
        persist()
        return updated
    }

    @Synchronized
    fun pendingAcks(): List<AgentInboxEntry> = entries.values.filter {
        it.ackPending && it.proposal.state in setOf(
            ProposalState.EXECUTED, ProposalState.REJECTED, ProposalState.EXPIRED, ProposalState.CANCELLED,
        )
    }

    @Synchronized
    fun markAcked(proposalId: String) {
        entries[proposalId]?.let { entries[proposalId] = it.copy(ackPending = false); persist() }
    }

    @Synchronized
    fun cursor(): String? = cursor

    private fun expireStale() {
        var changed = false
        val now = nowUtcMillis()
        entries.replaceAll { _, entry ->
            if (!entry.proposal.state.terminal && entry.proposal.expiresAtUtcMillis <= now) {
                changed = true
                entry.copy(proposal = entry.proposal.withState(ProposalState.EXPIRED), effect = "TTL expiré")
            } else entry
        }
        if (changed) persist()
    }

    private fun prune() {
        expireStale()
        if (entries.size > MAX_ENTRIES) {
            val removable = entries.values.filter { it.proposal.state.terminal }
                .sortedBy { it.proposal.expiresAtUtcMillis }
            removable.take(entries.size - MAX_ENTRIES).forEach { entries.remove(it.id) }
        }
    }

    private fun persist() {
        storage.write(JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("cursor", cursor ?: JSONObject.NULL)
            .put("entries", JSONArray().also { array -> entries.values.forEach { array.put(it.toJson()) } })
            .toString())
    }

    private fun restore(raw: String?) {
        if (raw.isNullOrBlank()) return
        runCatching {
            val root = JSONObject(raw)
            require(root.getInt("schema_version") == SCHEMA_VERSION)
            val storedCursor = root.get("cursor")
            cursor = if (storedCursor == JSONObject.NULL) null else storedCursor as? String
            val array = root.getJSONArray("entries")
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val proposal = ProposalV2.parse(item.getJSONObject("proposal").toString())
                entries[proposal.proposalId] = AgentInboxEntry(
                    proposal = proposal,
                    snoozedUntilUtcMillis = item.optLong("snoozed_until", 0L).takeIf { it > 0L },
                    ackPending = item.optBoolean("ack_pending", false),
                    effect = item.optString("effect").takeIf { it.isNotBlank() },
                )
            }
            prune()
        }.onFailure {
            entries.clear()
            cursor = null
        }
    }

    private fun AgentInboxEntry.toJson() = JSONObject()
        .put("proposal", proposal.toJson())
        .put("snoozed_until", snoozedUntilUtcMillis ?: JSONObject.NULL)
        .put("ack_pending", ackPending)
        .put("effect", effect ?: JSONObject.NULL)

    private fun allowedTransition(from: ProposalState, to: ProposalState): Boolean =
        !from.terminal && to in setOf(
            ProposalState.PENDING_CONFIRMATION, ProposalState.ACCEPTED, ProposalState.EXECUTED,
            ProposalState.REJECTED, ProposalState.EXPIRED, ProposalState.CANCELLED,
        )

    companion object {
        const val MAX_ENTRIES = 250
        private const val SCHEMA_VERSION = 1
    }
}
