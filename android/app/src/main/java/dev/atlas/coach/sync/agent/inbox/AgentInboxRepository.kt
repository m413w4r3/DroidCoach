package dev.atlas.coach.sync.agent.inbox

import dev.atlas.coach.sync.OutboxStorage
import dev.atlas.coach.sync.StoredStateQuarantine
import dev.atlas.coach.sync.agent.AgentBackendObservation
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

data class AgentInboxPullResult(val added: Int, val expired: Int, val cursor: String?, val deferredForCapacity: Int = 0)

/** Durable, bounded proposal inbox. Both proposal IDs and idempotency keys are de-duplicated. */
class AgentInboxRepository(
    private val storage: OutboxStorage,
    private val nowUtcMillis: () -> Long = System::currentTimeMillis,
) {
    private val entries = linkedMapOf<String, AgentInboxEntry>()
    private var cursor: String? = null
    private var backendObservation = AgentBackendObservation()
    private var quarantine: StoredStateQuarantine? = null
    private var writesBlocked = false

    init { restore(storage.read()) }

    @Synchronized
    fun quarantineInfo(): StoredStateQuarantine? = quarantine

    @Synchronized
    fun backendObservation(): AgentBackendObservation = backendObservation

    @Synchronized
    fun markBackendContactSucceeded(atUtcMillis: Long = nowUtcMillis()) {
        ensureWritable()
        backendObservation = AgentBackendObservation(
            state = dev.atlas.coach.sync.agent.AgentBackendState.REACHABLE,
            checkedAtUtcMillis = atUtcMillis,
            lastSuccessfulContactAtUtcMillis = atUtcMillis,
        )
        persist()
    }

    @Synchronized
    fun mergePulled(proposals: List<ProposalV2>, nextCursor: String?): AgentInboxPullResult {
        ensureWritable()
        var added = 0
        var expired = 0
        var deferredForCapacity = 0
        expireStale()
        prune()
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
            if (!duplicate && !makeRoomForOne()) {
                deferredForCapacity++
            } else if (!duplicate) {
                entries[proposal.proposalId] = AgentInboxEntry(proposal)
                added++
            }
        }
        if (deferredForCapacity == 0) cursor = nextCursor?.takeIf { it.isNotBlank() } ?: cursor
        markBackendContactSucceeded(nowUtcMillis())
        prune()
        return AgentInboxPullResult(added, expired, cursor, deferredForCapacity)
    }

    @Synchronized
    fun markBackendUnavailable(atUtcMillis: Long = nowUtcMillis()) {
        ensureWritable()
        backendObservation = backendObservation.copy(
            state = dev.atlas.coach.sync.agent.AgentBackendState.UNAVAILABLE,
            checkedAtUtcMillis = atUtcMillis,
        )
        persist()
    }

    @Synchronized
    fun insertLocal(proposal: ProposalV2, ackPending: Boolean = false): AgentInboxEntry? {
        ensureWritable()
        if (proposal.state != ProposalState.PROPOSED || proposal.expiresAtUtcMillis <= nowUtcMillis()) return null
        expireStale()
        prune()
        if (!makeRoomForOne()) return null
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
        ensureWritable()
        val old = entries[proposalId] ?: return null
        require(allowedTransition(old.proposal.state, state)) { "Invalid proposal state transition" }
        val updated = old.copy(proposal = old.proposal.withState(state), snoozedUntilUtcMillis = null, effect = effect)
        entries[proposalId] = updated
        persist()
        return updated
    }

    @Synchronized
    fun replace(proposal: ProposalV2): Boolean {
        ensureWritable()
        val old = entries[proposal.proposalId] ?: return false
        if (old.proposal.state.terminal || old.proposal.state == ProposalState.ACCEPTED ||
            old.proposal.idempotencyKey != proposal.idempotencyKey
        ) return false
        entries[proposal.proposalId] = old.copy(proposal = proposal.withState(ProposalState.PENDING_CONFIRMATION))
        persist()
        return true
    }

    @Synchronized
    fun postpone(proposalId: String, untilUtcMillis: Long): AgentInboxEntry? {
        ensureWritable()
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
    fun pendingAcks(): List<AgentInboxEntry> {
        ensureWritable()
        expireStale()
        return entries.values.filter {
            it.ackPending && it.proposal.state in setOf(
                ProposalState.EXECUTED, ProposalState.REJECTED, ProposalState.EXPIRED, ProposalState.CANCELLED,
            )
        }
    }

    @Synchronized
    fun markAcked(proposalId: String) {
        ensureWritable()
        entries[proposalId]?.let { entries[proposalId] = it.copy(ackPending = false); persist() }
    }

    @Synchronized
    fun cursor(): String? = cursor

    private fun expireStale() {
        var changed = false
        val now = nowUtcMillis()
        entries.replaceAll { _, entry ->
            if (entry.proposal.state in setOf(ProposalState.PROPOSED, ProposalState.PENDING_CONFIRMATION) &&
                entry.proposal.expiresAtUtcMillis <= now
            ) {
                changed = true
                entry.copy(proposal = entry.proposal.withState(ProposalState.EXPIRED), effect = "TTL expiré")
            } else entry
        }
        if (changed) persist()
    }

    private fun prune() {
        expireStale()
        if (entries.size > MAX_ENTRIES) {
            val removable = entries.values.filter { it.proposal.state.terminal && !it.ackPending }
                .sortedBy { it.proposal.expiresAtUtcMillis }
            removable.take(entries.size - MAX_ENTRIES).forEach { entries.remove(it.id) }
        }
    }

    private fun makeRoomForOne(): Boolean {
        if (entries.size < MAX_ENTRIES) return true
        val removable = entries.values.filter { it.proposal.state.terminal && !it.ackPending }
            .sortedBy { it.proposal.expiresAtUtcMillis }
        removable.take(entries.size - MAX_ENTRIES + 1).forEach { entries.remove(it.id) }
        return entries.size < MAX_ENTRIES
    }

    private fun persist() {
        ensureWritable()
        storage.write(JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("cursor", cursor ?: JSONObject.NULL)
            .put("backend_observation", backendObservation.toJson())
            .put("entries", JSONArray().also { array -> entries.values.forEach { array.put(it.toJson()) } })
            .toString())
    }

    private fun restore(raw: String?) {
        if (raw.isNullOrBlank()) return
        val restoreResult = runCatching {
            val root = JSONObject(raw)
            require(root.getInt("schema_version") in 1..SCHEMA_VERSION)
            val storedCursor = root.get("cursor")
            cursor = if (storedCursor == JSONObject.NULL) null else storedCursor as? String
            root.optJSONObject("backend_observation")?.let {
                backendObservation = AgentBackendObservation.fromJson(it)
            }
            val array = root.getJSONArray("entries")
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val proposal = ProposalV2.parse(item.getJSONObject("proposal").toString())
                require(proposal.proposalId !in entries)
                require(entries.values.none { it.proposal.idempotencyKey == proposal.idempotencyKey })
                entries[proposal.proposalId] = AgentInboxEntry(
                    proposal = proposal,
                    snoozedUntilUtcMillis = item.optLong("snoozed_until", 0L).takeIf { it > 0L },
                    ackPending = item.optBoolean("ack_pending", false),
                    effect = item.optString("effect").takeIf { it.isNotBlank() },
                )
            }
        }
        restoreResult.onFailure { error ->
            entries.clear()
            cursor = null
            backendObservation = AgentBackendObservation()
            val reason = "${error.javaClass.simpleName}: invalid inbox JSON or schema"
            val location = runCatching { storage.quarantine(raw, "agent-inbox", reason) }.getOrNull()
            quarantine = StoredStateQuarantine("agent-inbox", reason, location, nowUtcMillis())
            if (location == null) {
                writesBlocked = true
            } else {
                runCatching { storage.write(serialize()) }.onFailure { writesBlocked = true }
            }
        }
        if (restoreResult.isSuccess) prune()
    }

    private fun serialize(): String = JSONObject()
        .put("schema_version", SCHEMA_VERSION)
        .put("cursor", cursor ?: JSONObject.NULL)
        .put("backend_observation", backendObservation.toJson())
        .put("entries", JSONArray().also { array -> entries.values.forEach { array.put(it.toJson()) } })
        .toString()

    private fun ensureWritable() {
        check(!writesBlocked) { "Agent inbox state is quarantined and read-only." }
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
        private const val SCHEMA_VERSION = 2
    }
}
