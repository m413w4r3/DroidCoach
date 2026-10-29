package dev.atlas.coach.sync.agent.inbox.v4

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

interface InboxV4Transport {
    fun pull(cursor: String?, pageSize: Int): InboxV4PullPage
    fun ack(ack: InboxV4Ack): InboxV4DeliveryResult
}

/** Local proposal ledger and append-only ACK outbox. It has no Android framework dependencies. */
class InboxV4Repository(
    private val storage: InboxV4Storage,
    private val now: () -> Instant = Instant::now,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    private data class Entry(
        val proposal: InboxV4Proposal,
        val sourceBody: String,
        val applicationProof: InboxV4ApplicationProof? = null,
        val lastAckSequence: Long = 0L,
    )
    private data class RetiredEntry(
        val proposalId: String,
        val idempotencyKey: String,
        val sourceBodyDigest: String,
    )

    private val entries = linkedMapOf<String, Entry>()
    private val retiredEntries = linkedMapOf<String, RetiredEntry>()
    private val ackLedger = linkedMapOf<String, InboxV4Ack>()
    private val outbox = linkedMapOf<String, InboxV4OutboxAck>()
    private val quarantine = mutableListOf<InboxV4QuarantinedPayload>()
    private var cursor: String? = null
    private var writesBlocked = false

    init {
        require(maxEntries > 0)
        restore(storage.read())
    }

    @Synchronized
    fun cursor(): String? = cursor

    @Synchronized
    fun get(proposalId: String): InboxV4EntryView? = entries[proposalId]?.toView()

    @Synchronized
    fun all(): List<InboxV4EntryView> = entries.values.map { it.toView() }

    /** Removes oldest terminal proposals to free inbox capacity, retaining the append-only ACK ledger. */
    @Synchronized
    fun evictTerminalEntries(maxToEvict: Int = Int.MAX_VALUE): Int {
        ensureWritable()
        require(maxToEvict >= 0)
        if (maxToEvict == 0) return 0
        val evictedIds = entries.values.asSequence()
            .filter { it.proposal.state.terminal }
            .take(maxToEvict)
            .map { it.proposal.proposalId }
            .toList()
        if (evictedIds.isEmpty()) return 0
        val candidateEntries = entries.toMutableMap()
        val candidateRetired = retiredEntries.toMutableMap()
        evictedIds.forEach { id ->
            val entry = candidateEntries.remove(id) ?: return@forEach
            candidateRetired[id] = RetiredEntry(id, entry.proposal.idempotencyKey, digest(entry.sourceBody))
        }
        writeSnapshot(candidateEntries, outbox, quarantine, cursor, candidateRetired)
        entries.clear()
        entries.putAll(candidateEntries)
        retiredEntries.clear()
        retiredEntries.putAll(candidateRetired)
        return evictedIds.size
    }

    @Synchronized
    fun quarantinedPayloads(): List<InboxV4QuarantinedPayload> = quarantine.toList()

    @Synchronized
    fun ackHistory(proposalId: String? = null): List<InboxV4Ack> = ackLedger.values
        .filter { proposalId == null || it.proposalId == proposalId }

    @Synchronized
    fun pendingAcks(): List<InboxV4OutboxAck> = outbox.values
        .filter { it.deliveryState != InboxV4AckDeliveryState.DELIVERED }

    @Synchronized
    fun mergePulled(page: InboxV4PullPage, pageSize: Int): InboxV4MergeResult {
        ensureWritable()
        InboxV4PullRequest(cursor, pageSize)
        if (page.proposals.size > pageSize) throw InboxV4FormatException("Pull page exceeds page_size")
        require(page.proposals.none { it.state.terminal }) { "Pull page contains a terminal proposal" }
        var added = 0
        var duplicates = 0
        var expired = 0
        var conflicts = 0
        var deferred = 0
        var changed = false
        val timestamp = now()

        page.proposals.forEach { incoming ->
            val sourceBody = incoming.canonicalBody()
            val matches = entries.values.filter {
                it.proposal.proposalId == incoming.proposalId || it.proposal.idempotencyKey == incoming.idempotencyKey
            }
            val retiredMatches = retiredEntries.values.filter {
                it.proposalId == incoming.proposalId || it.idempotencyKey == incoming.idempotencyKey
            }
            if (matches.isNotEmpty() || retiredMatches.isNotEmpty() || ackLedger.values.any {
                    it.proposalId == incoming.proposalId
                }) {
                val activeDuplicate = matches.size == 1 && retiredMatches.isEmpty() &&
                    matches.single().proposal.proposalId == incoming.proposalId &&
                    matches.single().proposal.idempotencyKey == incoming.idempotencyKey &&
                    matches.single().sourceBody == sourceBody
                val retiredDuplicate = matches.isEmpty() && retiredMatches.size == 1 &&
                    retiredMatches.single().proposalId == incoming.proposalId &&
                    retiredMatches.single().idempotencyKey == incoming.idempotencyKey &&
                    retiredMatches.single().sourceBodyDigest == digest(sourceBody)
                if (activeDuplicate || retiredDuplicate) {
                    duplicates++
                } else {
                    conflicts++
                    quarantine += InboxV4QuarantinedPayload(sourceBody, "Proposal ID or idempotency key conflict", timestamp)
                    changed = true
                }
                return@forEach
            }
            if (incoming.expiresAt <= timestamp) {
                expired++
                return@forEach
            }
            if (entries.size >= maxEntries && evictTerminalEntries(maxToEvict = 1) > 0) {
                changed = true
            }
            if (entries.size >= maxEntries) {
                deferred++
                return@forEach
            }
            val locallyReceived = if (incoming.state == InboxV4ProposalState.PENDING_ANDROID) {
                incoming.withState(InboxV4ProposalState.RECEIVED)
            } else incoming
            entries[locallyReceived.proposalId] = Entry(locallyReceived, sourceBody)
            added++
            changed = true
        }

        // Keep the current cursor when the page cannot be fully consumed; retrying is safe because
        // accepted proposals are deduplicated by both identifiers and their canonical body.
        if (deferred == 0 && conflicts == 0) {
            cursor = page.nextCursor
            changed = true
        }
        if (changed) persist()
        return InboxV4MergeResult(
            added = added,
            duplicates = duplicates,
            expired = expired,
            conflicts = conflicts,
            deferredForCapacity = deferred,
            cursor = cursor,
            truncated = page.truncated,
        )
    }

    /** Parses each wire item strictly; rejected and unsupported payloads are retained verbatim. */
    @Synchronized
    fun ingestRawPage(
        rawProposals: List<String>,
        nextCursor: String?,
        truncated: Boolean,
        pageSize: Int,
    ): InboxV4MergeResult {
        ensureWritable()
        InboxV4PullRequest(cursor, pageSize)
        require(rawProposals.size <= pageSize) { "Pull page exceeds page_size" }
        require(!truncated || nextCursor != null) { "A truncated pull page must provide next_cursor" }
        val accepted = mutableListOf<InboxV4Proposal>()
        rawProposals.forEach { raw ->
            when (val result = InboxV4ProposalParser.parse(raw)) {
                is InboxV4ProposalParseResult.Parsed -> accepted += result.proposal
                is InboxV4ProposalParseResult.Quarantined -> quarantine +=
                    InboxV4QuarantinedPayload(result.raw, result.reason, now())
                is InboxV4ProposalParseResult.Rejected -> if (result.quarantinable) {
                    quarantine += InboxV4QuarantinedPayload(result.raw, result.reason, now())
                }
            }
        }
        val merged = mergePulled(InboxV4PullPage(accepted, nextCursor, truncated), pageSize)
        if (rawProposals.size != accepted.size) persist()
        return merged
    }

    fun pullNextPage(transport: InboxV4Transport, pageSize: Int): InboxV4MergeResult {
        val request = InboxV4PullRequest(cursor, pageSize)
        return mergePulled(transport.pull(request.cursor, request.pageSize), request.pageSize)
    }

    @Synchronized
    fun markAwaitingUser(proposalId: String): InboxV4EntryView {
        ensureWritable()
        val old = entries[proposalId] ?: error("Unknown proposal")
        require(old.proposal.state in setOf(InboxV4ProposalState.PENDING_ANDROID, InboxV4ProposalState.RECEIVED)) {
            "Invalid transition to AWAITING_USER"
        }
        entries[proposalId] = old.copy(proposal = old.proposal.withState(InboxV4ProposalState.AWAITING_USER))
        persist()
        return entries.getValue(proposalId).toView()
    }

    /** Creates an UNCERTAIN ACK with no claimed application proof, retaining any local evidence separately. */
    @Synchronized
    fun recordUncertain(
        proposalId: String,
        consentVersion: Long,
        knownApplicationProof: InboxV4ApplicationProof? = null,
        ackId: String = UUID.randomUUID().toString(),
    ): InboxV4AppendResult {
        val entry = entries[proposalId] ?: error("Unknown proposal")
        if (knownApplicationProof != null) validateProofFor(entry.proposal, knownApplicationProof)
        if (entry.applicationProof != null && knownApplicationProof != null) {
            require(entry.applicationProof == knownApplicationProof) { "Application proof changed" }
        }
        return appendAckInternal(
            InboxV4Ack(
                contractVersion = CONTRACT_VERSION,
                schemaVersion = SCHEMA_VERSION,
                ackId = ackId,
                capability = ACK_CAPABILITY,
                proposalId = proposalId,
                sequence = entry.lastAckSequence + 1L,
                state = InboxV4AckState.UNCERTAIN,
                resultCode = InboxV4AckResultCode.RESULT_UNCERTAIN,
                occurredAt = now(),
                consentVersion = consentVersion,
            ),
            knownApplicationProof,
        )
    }

    @Synchronized
    fun appendAck(ack: InboxV4Ack): InboxV4AppendResult {
        return appendAckInternal(ack, knownApplicationProof = null)
    }

    private fun appendAckInternal(
        ack: InboxV4Ack,
        knownApplicationProof: InboxV4ApplicationProof?,
    ): InboxV4AppendResult {
        ensureWritable()
        val body = ack.canonicalBody()
        ackLedger[ack.ackId]?.let { previous ->
            if (previous.canonicalBody() == body) return InboxV4AppendResult.Duplicate
            throw InboxV4ConflictException("ACK_ID_CONFLICT")
        }
        val existingAtSequence = ackLedger.values.firstOrNull {
            it.proposalId == ack.proposalId && it.sequence == ack.sequence
        }
        if (existingAtSequence != null) throw InboxV4ConflictException("ACK_SEQUENCE_CONFLICT")

        val entry = entries[ack.proposalId] ?: error("Unknown proposal")
        if (knownApplicationProof != null) validateProofFor(entry.proposal, knownApplicationProof)
        if (entry.applicationProof != null && knownApplicationProof != null) {
            require(entry.applicationProof == knownApplicationProof) { "Application proof changed" }
        }
        val retainedProof = knownApplicationProof ?: entry.applicationProof
        require(ack.sequence > entry.lastAckSequence) { "ACK sequence must increase" }
        validateTransition(entry.proposal.state, ack.state)
        when (ack.state) {
            InboxV4AckState.APPLIED -> {
                val proof = requireNotNull(ack.proof)
                validateProofFor(entry.proposal, proof)
            }
            InboxV4AckState.RECONCILED -> {
                if (ack.resultCode == InboxV4AckResultCode.RECONCILED_APPLIED) {
                    val expectedProof = retainedProof
                        ?: throw IllegalArgumentException("RECONCILED_APPLIED requires previously recorded local proof")
                    require(ack.proof == expectedProof) { "RECONCILED_APPLIED proof must match local evidence" }
                } else {
                    require(retainedProof == null) { "Cannot reconcile as not applied after recording application proof" }
                }
            }
            InboxV4AckState.UNCERTAIN -> if (retainedProof != null) {
                validateProofFor(entry.proposal, retainedProof)
            }
            else -> require(retainedProof == null) {
                "A proposal with application proof cannot transition to ${ack.state}"
            }
        }

        val nextProof = when (ack.state) {
            InboxV4AckState.APPLIED -> ack.proof
            InboxV4AckState.UNCERTAIN -> retainedProof
            InboxV4AckState.RECONCILED -> ack.proof
            else -> null
        }
        val candidateEntries = entries.toMutableMap().apply {
            put(ack.proposalId, entry.copy(
                proposal = entry.proposal.withState(ack.state.toProposalState()),
                applicationProof = nextProof,
                lastAckSequence = ack.sequence,
            ))
        }
        val candidateAckLedger = ackLedger.toMutableMap().apply { put(ack.ackId, ack) }
        val candidateOutbox = outbox.toMutableMap().apply {
            put(ack.ackId, InboxV4OutboxAck(ack, InboxV4AckDeliveryState.PENDING))
        }

        // Commit the candidate snapshot first; visible in-memory state changes only after success.
        writeSnapshot(candidateEntries, candidateOutbox, quarantine, cursor)
        entries.clear()
        entries.putAll(candidateEntries)
        ackLedger.clear()
        ackLedger.putAll(candidateAckLedger)
        outbox.clear()
        outbox.putAll(candidateOutbox)
        return InboxV4AppendResult.Appended
    }

    @Synchronized
    fun expireDue(consentVersion: Long): List<InboxV4Ack> {
        ensureWritable()
        require(consentVersion > 0L)
        val currentTime = now()
        val expiredAcks = entries.values
            .filter {
                it.proposal.state in ACTIVE_STATES && !it.proposal.expiresAt.isAfter(currentTime)
            }
            .map { entry ->
                InboxV4Ack(
                    contractVersion = CONTRACT_VERSION,
                    schemaVersion = SCHEMA_VERSION,
                    ackId = UUID.randomUUID().toString(),
                    capability = ACK_CAPABILITY,
                    proposalId = entry.proposal.proposalId,
                    sequence = entry.lastAckSequence + 1L,
                    state = InboxV4AckState.EXPIRED,
                    resultCode = InboxV4AckResultCode.PROPOSAL_EXPIRED,
                    occurredAt = currentTime,
                    consentVersion = consentVersion,
                )
            }
        expiredAcks.forEach(::appendAck)
        return expiredAcks
    }

    fun replayPendingAcks(transport: InboxV4Transport): InboxV4ReplayResult {
        val pendingSnapshot = synchronized(this) {
            ensureWritable()
            pendingAcks()
        }
        var delivered = 0
        var uncertain = 0
        for (pending in pendingSnapshot) {
            val delivery = try {
                transport.ack(pending.ack)
            } catch (_: Exception) {
                InboxV4DeliveryResult.Uncertain
            }
            val nextState = synchronized(this) {
                val current = outbox[pending.ack.ackId] ?: return@synchronized InboxV4AckDeliveryState.DELIVERED
                if (current.deliveryState == InboxV4AckDeliveryState.DELIVERED) {
                    return@synchronized InboxV4AckDeliveryState.DELIVERED
                }
                val next = when (delivery) {
                    InboxV4DeliveryResult.Delivered -> InboxV4AckDeliveryState.DELIVERED
                    InboxV4DeliveryResult.Uncertain -> InboxV4AckDeliveryState.UNCERTAIN
                    InboxV4DeliveryResult.Offline -> current.deliveryState
                }
                if (current.deliveryState != next) {
                    outbox[pending.ack.ackId] = current.copy(deliveryState = next)
                    persist()
                }
                outbox[pending.ack.ackId]?.deliveryState ?: next
            }
            when (nextState) {
                InboxV4AckDeliveryState.DELIVERED -> delivered++
                InboxV4AckDeliveryState.UNCERTAIN -> uncertain++
                InboxV4AckDeliveryState.PENDING -> Unit
            }
        }
        val stillPending = synchronized(this) { pendingAcks().size }
        return InboxV4ReplayResult(delivered, uncertain, stillPending)
    }

    @Synchronized
    fun markAckDelivery(ackId: String, state: InboxV4AckDeliveryState) {
        ensureWritable()
        val old = outbox[ackId] ?: error("Unknown ACK")
        require(state != InboxV4AckDeliveryState.PENDING || old.deliveryState == InboxV4AckDeliveryState.PENDING)
        require(old.deliveryState != InboxV4AckDeliveryState.DELIVERED || state == InboxV4AckDeliveryState.DELIVERED)
        if (old.deliveryState != state) {
            outbox[ackId] = old.copy(deliveryState = state)
            persist()
        }
    }

    private fun validateTransition(from: InboxV4ProposalState, to: InboxV4AckState) {
        val allowed = when (from) {
            InboxV4ProposalState.PENDING_ANDROID -> setOf(
                InboxV4AckState.APPLIED, InboxV4AckState.REJECTED, InboxV4AckState.STALE_VERSION,
                InboxV4AckState.EXPIRED, InboxV4AckState.VALIDATION_REJECTED, InboxV4AckState.UNCERTAIN,
            )
            InboxV4ProposalState.RECEIVED -> setOf(
                InboxV4AckState.APPLIED, InboxV4AckState.REJECTED, InboxV4AckState.STALE_VERSION,
                InboxV4AckState.EXPIRED, InboxV4AckState.VALIDATION_REJECTED, InboxV4AckState.UNCERTAIN,
            )
            InboxV4ProposalState.AWAITING_USER -> setOf(
                InboxV4AckState.APPLIED, InboxV4AckState.REJECTED, InboxV4AckState.STALE_VERSION,
                InboxV4AckState.EXPIRED, InboxV4AckState.VALIDATION_REJECTED, InboxV4AckState.UNCERTAIN,
            )
            InboxV4ProposalState.UNCERTAIN -> setOf(InboxV4AckState.RECONCILED)
            else -> emptySet()
        }
        require(to in allowed) { "Invalid proposal transition $from -> $to" }
    }

    private fun validateProofFor(proposal: InboxV4Proposal, proof: InboxV4ApplicationProof) {
        require(proof.planId == proposal.planId) { "Proof plan_id does not match proposal" }
        require(proof.appliedPlanVersion > proposal.baseVersion) { "Applied plan version must advance base_version" }
    }

    private fun persist() {
        ensureWritable()
        writeSnapshot(entries, outbox, quarantine, cursor)
    }

    private fun writeSnapshot(
        entryState: Map<String, Entry>,
        outboxState: Map<String, InboxV4OutboxAck>,
        quarantineState: List<InboxV4QuarantinedPayload>,
        cursorState: String?,
        retiredState: Map<String, RetiredEntry> = retiredEntries,
    ) {
        val root = JSONObject()
            .put("schema_version", LOCAL_STORAGE_SCHEMA)
            .put("cursor", cursorState ?: JSONObject.NULL)
            .put("entries", JSONArray().also { array ->
                entryState.values.forEach { entry ->
                    array.put(JSONObject()
                        .put("proposal", entry.proposal.toJsonObject())
                        .put("source_body", entry.sourceBody)
                        .put("application_proof", entry.applicationProof?.toJsonObject() ?: JSONObject.NULL)
                        .put("last_ack_sequence", entry.lastAckSequence))
                }
            })
            .put("acks", JSONArray().also { array ->
                outboxState.values.forEach { queued ->
                    array.put(JSONObject()
                        .put("ack", queued.ack.toJsonObject())
                        .put("delivery_state", queued.deliveryState.name))
                }
            })
            .put("retired", JSONArray().also { array ->
                retiredState.values.forEach { item ->
                    array.put(JSONObject()
                        .put("proposal_id", item.proposalId)
                        .put("idempotency_key", item.idempotencyKey)
                        .put("source_body_digest", item.sourceBodyDigest))
                }
            })
            .put("quarantine", JSONArray().also { array ->
                quarantineState.forEach { item ->
                    array.put(JSONObject()
                        .put("raw", item.raw)
                        .put("reason", item.reason)
                        .put("quarantined_at", item.quarantinedAt.toString()))
                }
            })
        storage.write(root.toString())
    }

    private fun restore(raw: String?) {
        if (raw.isNullOrBlank()) return
        try {
            val root = JSONObject(raw)
            val schema = root.opt("schema_version") as? Number
            if (schema?.toInt() != LOCAL_STORAGE_SCHEMA) throw InboxV4FormatException("Unsupported local storage schema")
            val oldFields = setOf("schema_version", "cursor", "entries", "acks", "quarantine")
            val currentFields = oldFields + "retired"
            val actualFields = root.keys().asSequence().toSet()
            require(actualFields == oldFields || actualFields == currentFields) { "Unexpected local fields" }
            cursor = when (val value = root.get("cursor")) {
                JSONObject.NULL -> null
                is String -> value
                else -> throw InboxV4FormatException("Invalid stored cursor")
            }
            if (cursor != null && (cursor!!.isBlank() || cursor!!.length > MAX_CURSOR_LENGTH)) {
                throw InboxV4FormatException("Invalid stored cursor")
            }
            val storedEntries = root.getJSONArray("entries")
            for (index in 0 until storedEntries.length()) {
                val item = storedEntries.getJSONObject(index)
                item.requireExactLocalFields(setOf("proposal", "source_body", "application_proof", "last_ack_sequence"))
                val proposalResult = InboxV4ProposalParser.parse(item.getJSONObject("proposal").toString())
                val proposal = (proposalResult as? InboxV4ProposalParseResult.Parsed)?.proposal
                    ?: throw InboxV4FormatException("Invalid persisted proposal")
                val proofValue = item.get("application_proof")
                val proof = if (proofValue == JSONObject.NULL) null else parseProof(proofValue as? JSONObject
                    ?: throw InboxV4FormatException("Invalid persisted application proof"))
                val entry = Entry(
                    proposal = proposal,
                    sourceBody = item.getString("source_body"),
                    applicationProof = proof,
                    lastAckSequence = item.getLong("last_ack_sequence"),
                )
                require(proposal.proposalId !in entries)
                require(entries.values.none { it.proposal.idempotencyKey == proposal.idempotencyKey })
                require(entry.lastAckSequence >= 0L)
                if (proof != null) validateProofFor(proposal, proof)
                entries[proposal.proposalId] = entry
            }
            val storedAcks = root.getJSONArray("acks")
            for (index in 0 until storedAcks.length()) {
                val item = storedAcks.getJSONObject(index)
                item.requireExactLocalFields(setOf("ack", "delivery_state"))
                val ack = InboxV4AckParser.parse(item.getJSONObject("ack").toString())
                require(ack.ackId !in ackLedger)
                val deliveryState = enumValue<InboxV4AckDeliveryState>(item.getString("delivery_state"), "delivery_state")
                ackLedger[ack.ackId] = ack
                outbox[ack.ackId] = InboxV4OutboxAck(ack, deliveryState)
            }
            if (root.has("retired")) {
                val storedRetired = root.getJSONArray("retired")
                for (index in 0 until storedRetired.length()) {
                    val item = storedRetired.getJSONObject(index)
                    item.requireExactLocalFields(setOf("proposal_id", "idempotency_key", "source_body_digest"))
                    val retired = RetiredEntry(
                        proposalId = item.getString("proposal_id").also { require(isCanonicalUuid(it)) },
                        idempotencyKey = item.getString("idempotency_key").also { require(isCanonicalUuid(it)) },
                        sourceBodyDigest = item.getString("source_body_digest").also {
                            require(it.matches(Regex("^[0-9a-f]{64}$")))
                        },
                    )
                    require(retired.proposalId !in entries && retired.proposalId !in retiredEntries)
                    require(entries.values.none { it.proposal.idempotencyKey == retired.idempotencyKey })
                    require(retiredEntries.values.none { it.idempotencyKey == retired.idempotencyKey })
                    retiredEntries[retired.proposalId] = retired
                }
            }
            require(ackLedger.values.all { ack ->
                ack.proposalId in entries || ack.proposalId in retiredEntries
            }) { "Stored ACK has no proposal ledger entry" }
            val storedQuarantine = root.getJSONArray("quarantine")
            for (index in 0 until storedQuarantine.length()) {
                val item = storedQuarantine.getJSONObject(index)
                quarantine += InboxV4QuarantinedPayload(
                    raw = item.getString("raw"),
                    reason = item.getString("reason"),
                    quarantinedAt = Instant.parse(item.getString("quarantined_at")),
                )
            }
            validateRestoredSequences()
        } catch (error: Exception) {
            entries.clear()
            retiredEntries.clear()
            ackLedger.clear()
            outbox.clear()
            quarantine.clear()
            cursor = null
            val reason = "Invalid local inbox state: ${error.message ?: error.javaClass.simpleName}"
            val location = storage.quarantine(raw, reason)
            quarantine += InboxV4QuarantinedPayload(raw, reason, now())
            if (location == null) {
                writesBlocked = true
            } else {
                runCatching { persist() }.onFailure { writesBlocked = true }
            }
        }
    }

    private fun validateRestoredSequences() {
        entries.values.forEach { entry ->
            val maxSequence = ackLedger.values.filter { it.proposalId == entry.proposal.proposalId }.maxOfOrNull { it.sequence } ?: 0L
            require(entry.lastAckSequence == maxSequence)
        }
        require(ackLedger.values.groupBy { it.proposalId to it.sequence }.values.all { it.size == 1 })
    }

    private fun Entry.toView() = InboxV4EntryView(proposal, applicationProof, lastAckSequence)

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun ensureWritable() {
        check(!writesBlocked) { "Inbox V4 state is quarantined and read-only" }
    }

    companion object {
        const val MAX_ENTRIES = 250
        private const val LOCAL_STORAGE_SCHEMA = 1
        private val ACTIVE_STATES = setOf(
            InboxV4ProposalState.PENDING_ANDROID,
            InboxV4ProposalState.RECEIVED,
            InboxV4ProposalState.AWAITING_USER,
        )
    }
}

private fun InboxV4AckState.toProposalState(): InboxV4ProposalState = InboxV4ProposalState.valueOf(name)

private fun InboxV4ApplicationProof.toJsonObject() = JSONObject()
    .put("plan_id", planId)
    .put("applied_plan_version", appliedPlanVersion)
    .put("revision_id", revisionId)
    .put("scope_hash", scopeHash)
    .put("effect_ids", JSONArray(effectIds))

private fun parseProof(json: JSONObject): InboxV4ApplicationProof {
    json.requireExactLocalFields(setOf("plan_id", "applied_plan_version", "revision_id", "scope_hash", "effect_ids"))
    val effects = json.getJSONArray("effect_ids")
    return InboxV4ApplicationProof(
        planId = json.getString("plan_id"),
        appliedPlanVersion = json.getLong("applied_plan_version"),
        revisionId = json.getString("revision_id"),
        scopeHash = json.getString("scope_hash"),
        effectIds = (0 until effects.length()).map { effects.getString(it) },
    )
}

private fun JSONObject.requireExactLocalFields(expected: Set<String>) {
    val actual = keys().asSequence().toSet()
    require(actual == expected) { "Unexpected local fields" }
}

private inline fun <reified T : Enum<T>> enumValue(value: String, field: String): T =
    enumValues<T>().firstOrNull { it.name == value } ?: throw InboxV4FormatException("Unknown $field")
