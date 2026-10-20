package dev.atlas.coach.sync.agent.dispatch

import dev.atlas.coach.sync.OutboxStorage
import dev.atlas.coach.sync.StoredStateQuarantine
import dev.atlas.coach.sync.agent.AgentActionType
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.AgentVerificationContext
import dev.atlas.coach.sync.agent.MandateStore
import dev.atlas.coach.sync.agent.MandateVerifier
import dev.atlas.coach.sync.agent.ProposalState
import dev.atlas.coach.sync.agent.ProposalV2
import dev.atlas.coach.sync.agent.inbox.AgentInboxEntry
import dev.atlas.coach.sync.agent.inbox.AgentInboxRepository
import org.json.JSONArray
import org.json.JSONObject

/** applied=false guarantees no local effect; an exception means the outcome may be ambiguous. */
data class AgentActionOutcome(
    val applied: Boolean,
    val effect: String,
    /** Keep the prepared journal entry open when a durable local commit failed. */
    val requiresReconciliation: Boolean = false,
)

/** Explicit, closed action port implemented by AtlasController. */
interface AgentActionPort {
    fun setHomeDirective(proposal: ProposalV2): AgentActionOutcome
    fun reorderHomeCards(proposal: ProposalV2): AgentActionOutcome
    fun proposeFocus(proposal: ProposalV2): AgentActionOutcome
    fun planFocus(proposal: ProposalV2): AgentActionOutcome
    fun startFocus(proposal: ProposalV2): AgentActionOutcome
    fun pauseFocus(proposal: ProposalV2): AgentActionOutcome
    fun suggestHabit(proposal: ProposalV2): AgentActionOutcome
    fun proposeAppPolicy(proposal: ProposalV2): AgentActionOutcome
    fun adjustAppPolicy(proposal: ProposalV2): AgentActionOutcome
    fun recommendContent(proposal: ProposalV2): AgentActionOutcome

    /** Must inspect durable local state; UNKNOWN deliberately blocks a replay after an interrupted effect. */
    fun reconcile(proposal: ProposalV2, idempotencyKey: String): AgentEffectReconciliation =
        AgentEffectReconciliation.UNKNOWN
}

enum class AgentEffectReconciliation { APPLIED, NOT_APPLIED, UNKNOWN }

enum class AgentEffectPhase { NONE, PREPARED, COMPLETE }

data class AgentEffectRecord(
    val proposalId: String,
    val idempotencyKey: String,
    val actionType: String,
    val state: ProposalState,
    val effect: String,
    val atUtcMillis: Long,
    val phase: AgentEffectPhase = if (state.terminal) AgentEffectPhase.COMPLETE else AgentEffectPhase.NONE,
) {
    init {
        require(proposalId.isNotBlank() && idempotencyKey.isNotBlank() && idempotencyKey.length <= 80)
        require(AgentActionType.parse(actionType) != null && effect.length <= 240 && atUtcMillis >= 0L)
        require(phase != AgentEffectPhase.COMPLETE || state.terminal)
    }
}

interface AgentEffectJournal {
    fun append(record: AgentEffectRecord)
    fun entries(): List<AgentEffectRecord>
    fun findByProposalId(id: String): AgentEffectRecord?
    fun findByIdempotencyKey(key: String): AgentEffectRecord?
    fun quarantineInfo(): StoredStateQuarantine? = null
}

/** Only successfully applied effects contribute to cooldowns; rejected and incomplete effects did not apply. */
fun AgentEffectJournal.cooldownHistoryByActionType(): Map<String, Long> = entries()
    .filter { it.phase == AgentEffectPhase.COMPLETE && it.state == ProposalState.EXECUTED }
    .groupBy { it.actionType }
    .mapValues { (_, records) -> records.maxOf { it.atUtcMillis } }

class InMemoryAgentEffectJournal : AgentEffectJournal {
    private val records = mutableListOf<AgentEffectRecord>()
    @Synchronized override fun append(record: AgentEffectRecord) {
        val byId = records.lastOrNull { it.proposalId == record.proposalId }
        val byKey = records.lastOrNull { it.idempotencyKey == record.idempotencyKey }
        require(byKey == null || byKey.proposalId == record.proposalId) { "Idempotency key already belongs to another proposal" }
        require(byId == null || byId.idempotencyKey == record.idempotencyKey && byId.actionType == record.actionType) {
            "Proposal identity changed in effect journal"
        }
        if (byId?.phase == AgentEffectPhase.COMPLETE) return
        records.removeAll { it.proposalId == record.proposalId }
        records += record
    }
    @Synchronized override fun entries() = records.toList()
    @Synchronized override fun findByProposalId(id: String) = records.lastOrNull { it.proposalId == id }
    @Synchronized override fun findByIdempotencyKey(key: String) = records.lastOrNull { it.idempotencyKey == key }
}

class JsonAgentEffectJournal(private val storage: OutboxStorage) : AgentEffectJournal {
    private val records = mutableListOf<AgentEffectRecord>()
    private var quarantine: StoredStateQuarantine? = null
    private var writesBlocked = false

    init {
        val raw = storage.read()
        if (!raw.isNullOrBlank()) runCatching {
            val root = JSONObject(raw)
            require(root.getInt("schema_version") in 1..SCHEMA_VERSION)
            val array = root.getJSONArray("records")
            for (index in 0 until array.length()) records += array.getJSONObject(index).toRecord()
            require(records.map(AgentEffectRecord::proposalId).distinct().size == records.size)
            require(records.map(AgentEffectRecord::idempotencyKey).distinct().size == records.size)
        }.onFailure { error ->
            records.clear()
            val reason = "${error.javaClass.simpleName}: invalid effect journal JSON or schema"
            val location = runCatching { storage.quarantine(raw, "agent-effects", reason) }.getOrNull()
            quarantine = StoredStateQuarantine("agent-effects", reason, location, System.currentTimeMillis())
            if (location == null) writesBlocked = true
            else runCatching { storage.write(serialize(emptyList())) }.onFailure { writesBlocked = true }
        }
    }

    @Synchronized override fun append(record: AgentEffectRecord) {
        ensureWritable()
        val old = records.lastOrNull { it.proposalId == record.proposalId }
        val byKey = records.lastOrNull { it.idempotencyKey == record.idempotencyKey }
        require(byKey == null || byKey.proposalId == record.proposalId) { "Idempotency key already belongs to another proposal" }
        require(old == null || old.idempotencyKey == record.idempotencyKey && old.actionType == record.actionType) {
            "Proposal identity changed in effect journal"
        }
        if (old?.phase == AgentEffectPhase.COMPLETE) return
        if (old == null) require(records.size < MAX_RECORDS) { "Effect journal capacity reached; dispatch is disabled" }
        val next = records.filterNot { it.proposalId == record.proposalId } + record
        storage.write(serialize(next))
        records.clear()
        records.addAll(next)
    }

    @Synchronized override fun entries() = records.toList()
    @Synchronized override fun findByProposalId(id: String) = records.lastOrNull { it.proposalId == id }
    @Synchronized override fun findByIdempotencyKey(key: String) = records.lastOrNull { it.idempotencyKey == key }
    @Synchronized override fun quarantineInfo() = quarantine

    private fun serialize(items: List<AgentEffectRecord>): String = JSONObject().put("schema_version", SCHEMA_VERSION)
        .put("records", JSONArray().also { array -> items.forEach { array.put(it.toJson()) } }).toString()

    private fun ensureWritable() {
        check(!writesBlocked) { "Agent effect journal is quarantined and read-only." }
    }

    companion object {
        const val MAX_RECORDS = 500
        private const val SCHEMA_VERSION = 1
    }
}

enum class DispatchStatus { EXECUTED, REFUSED, NEEDS_CONFIRMATION, NEEDS_RECONCILIATION, ALREADY_HANDLED, NOT_FOUND }

data class AgentDispatchResult(val status: DispatchStatus, val effect: String)

/** Revalidates at dispatch time, then executes one of ten fixed local branches. */
class ActionDispatcher(
    private val mandates: MandateStore,
    private val inbox: AgentInboxRepository,
    private val verifier: MandateVerifier,
    private val context: (AgentMandate) -> AgentVerificationContext,
    private val actions: AgentActionPort,
    private val journal: AgentEffectJournal,
    private val nowUtcMillis: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun accept(proposalId: String): AgentDispatchResult = dispatch(proposalId, explicitConfirmation = true)

    @Synchronized
    fun dispatchPreauthorized(proposalId: String): AgentDispatchResult = dispatch(proposalId, explicitConfirmation = false)

    @Synchronized
    fun reject(proposalId: String): AgentDispatchResult {
        val entry = inbox.get(proposalId) ?: return AgentDispatchResult(DispatchStatus.NOT_FOUND, "Proposition introuvable.")
        val prior = journal.findByProposalId(proposalId)
        if (prior?.phase == AgentEffectPhase.COMPLETE) {
            repairInboxFromJournal(entry, prior)
            return AgentDispatchResult(DispatchStatus.ALREADY_HANDLED, "Action déjà traitée.")
        }
        if (entry.proposal.state == ProposalState.ACCEPTED || prior?.phase == AgentEffectPhase.PREPARED) {
            return AgentDispatchResult(DispatchStatus.NEEDS_RECONCILIATION, "Effet déjà engagé; réconciliez son résultat avant de le refuser.")
        }
        val updated = inbox.update(proposalId, ProposalState.REJECTED, "Refusée par l'utilisateur")
            ?: return AgentDispatchResult(DispatchStatus.ALREADY_HANDLED, "Proposition déjà terminée.")
        record(updated.proposal, ProposalState.REJECTED, "Refusée par l'utilisateur")
        return AgentDispatchResult(DispatchStatus.REFUSED, "Proposition refusée.")
    }

    @Synchronized
    fun postpone(proposalId: String): AgentDispatchResult {
        val entry = inbox.get(proposalId) ?: return AgentDispatchResult(DispatchStatus.NOT_FOUND, "Proposition introuvable.")
        val prior = journal.findByProposalId(proposalId)
        if (prior?.phase == AgentEffectPhase.COMPLETE) {
            repairInboxFromJournal(entry, prior)
            return AgentDispatchResult(DispatchStatus.ALREADY_HANDLED, "Action déjà traitée.")
        }
        if (entry.proposal.state.terminal) return AgentDispatchResult(DispatchStatus.ALREADY_HANDLED, "Proposition déjà terminée.")
        val updated = inbox.postpone(proposalId, nowUtcMillis() + POSTPONE_MILLIS)
            ?: return AgentDispatchResult(DispatchStatus.ALREADY_HANDLED, "La proposition n'est plus en attente de confirmation.")
        record(updated.proposal, ProposalState.PENDING_CONFIRMATION, "Reportée par l'utilisateur")
        return AgentDispatchResult(DispatchStatus.NEEDS_CONFIRMATION, "Proposition reportée.")
    }

    @Synchronized
    fun edit(proposalId: String, parameters: Map<String, Any?>): AgentDispatchResult {
        val entry = inbox.get(proposalId) ?: return AgentDispatchResult(DispatchStatus.NOT_FOUND, "Proposition introuvable.")
        val prior = journal.findByProposalId(proposalId)
        if (prior?.phase == AgentEffectPhase.COMPLETE) {
            repairInboxFromJournal(entry, prior)
            return AgentDispatchResult(DispatchStatus.ALREADY_HANDLED, "Action déjà traitée.")
        }
        if (entry.proposal.state == ProposalState.ACCEPTED || prior?.phase == AgentEffectPhase.PREPARED) {
            return AgentDispatchResult(DispatchStatus.NEEDS_RECONCILIATION, "Effet en cours de réconciliation; modification bloquée.")
        }
        if (entry.proposal.state.terminal) return AgentDispatchResult(DispatchStatus.ALREADY_HANDLED, "Proposition déjà terminée.")
        val edited = runCatching { entry.proposal.copy(parameters = parameters) }
            .getOrElse { return AgentDispatchResult(DispatchStatus.REFUSED, "Paramètres modifiés invalides.") }
        val mandate = mandates.current()
        val decision = verifier.verify(edited, mandate, context(mandate))
        if (!decision.accepted) return AgentDispatchResult(DispatchStatus.REFUSED, "Action refusée par la politique : ${decision.reason}")
        if (!inbox.replace(edited)) return AgentDispatchResult(DispatchStatus.REFUSED, "La proposition a expiré ou changé d'état.")
        record(edited, ProposalState.PENDING_CONFIRMATION, "Modification enregistrée; confirmation requise")
        return AgentDispatchResult(DispatchStatus.NEEDS_CONFIRMATION, "Modification enregistrée; confirmez pour appliquer.")
    }

    private fun dispatch(proposalId: String, explicitConfirmation: Boolean): AgentDispatchResult {
        val entry = inbox.get(proposalId) ?: return AgentDispatchResult(DispatchStatus.NOT_FOUND, "Proposition introuvable.")
        val proposal = entry.proposal
        val journalRecord = journal.findByProposalId(proposalId)
        if (journalRecord != null && (journalRecord.idempotencyKey != proposal.idempotencyKey ||
                journalRecord.actionType != proposal.actionType)
        ) {
            return AgentDispatchResult(DispatchStatus.NEEDS_RECONCILIATION, "Identité de proposition différente du journal; effet bloqué.")
        }
        if (journalRecord == null) {
            val reusedKey = journal.findByIdempotencyKey(proposal.idempotencyKey)
            if (reusedKey != null) {
                return AgentDispatchResult(DispatchStatus.ALREADY_HANDLED, "Clé d'idempotence déjà consommée.")
            }
        }
        if (journalRecord?.phase == AgentEffectPhase.COMPLETE) {
            repairInboxFromJournal(entry, journalRecord)
            return AgentDispatchResult(DispatchStatus.ALREADY_HANDLED, "Action déjà traitée.")
        }
        if (journalRecord?.phase == AgentEffectPhase.PREPARED) return reconcilePrepared(entry, journalRecord)
        if (proposal.state == ProposalState.ACCEPTED) {
            return AgentDispatchResult(DispatchStatus.NEEDS_RECONCILIATION, "Action interrompue sans journal d'effet; aucune répétition automatique.")
        }
        if (proposal.state.terminal) return AgentDispatchResult(DispatchStatus.ALREADY_HANDLED, "Proposition déjà terminée.")
        val mandate = mandates.current()
        val decision = verifier.verify(proposal, mandate, context(mandate))
        if (!decision.accepted) {
            record(proposal, ProposalState.REJECTED, decision.reason)
            inbox.update(proposalId, ProposalState.REJECTED, decision.reason)
            return AgentDispatchResult(DispatchStatus.REFUSED, "Action refusée par la politique : ${decision.reason}")
        }
        if (!explicitConfirmation && decision.requiresConfirmation) {
            return AgentDispatchResult(DispatchStatus.NEEDS_CONFIRMATION, "Une confirmation locale est nécessaire.")
        }
        val prepared = AgentEffectRecord(
            proposalId = proposal.proposalId,
            idempotencyKey = proposal.idempotencyKey,
            actionType = proposal.actionType,
            state = ProposalState.ACCEPTED,
            effect = "Acceptée localement; effet à réconcilier",
            atUtcMillis = nowUtcMillis(),
            phase = AgentEffectPhase.PREPARED,
        )
        try {
            journal.append(prepared)
        } catch (_: Exception) {
            return AgentDispatchResult(DispatchStatus.REFUSED, "Journal local indisponible; aucun effet n'a été lancé.")
        }
        runCatching { inbox.update(proposalId, ProposalState.ACCEPTED, "Acceptée localement") }
        return runPrepared(proposal, prepared)
    }

    /** Explicit recovery entry point for process restart; unresolved effects remain blocked. */
    @Synchronized
    fun reconcileInterrupted(): List<AgentDispatchResult> {
        val results = mutableListOf<AgentDispatchResult>()
        val byId = inbox.all().associateBy { it.id }
        journal.entries().forEach { record ->
            val entry = byId[record.proposalId]
            if (entry == null) {
                if (record.phase == AgentEffectPhase.PREPARED) {
                    results += AgentDispatchResult(
                        DispatchStatus.NEEDS_RECONCILIATION,
                        "Proposition absente de l'inbox; effet préparé conservé dans le journal.",
                    )
                }
                return@forEach
            }
            when (record.phase) {
                AgentEffectPhase.COMPLETE -> repairInboxFromJournal(entry, record)
                AgentEffectPhase.PREPARED -> results += reconcilePrepared(entry, record)
                AgentEffectPhase.NONE -> Unit
            }
        }
        return results
    }

    private fun reconcilePrepared(entry: AgentInboxEntry, record: AgentEffectRecord): AgentDispatchResult {
        if (entry.proposal.idempotencyKey != record.idempotencyKey || entry.proposal.actionType != record.actionType) {
            return AgentDispatchResult(DispatchStatus.NEEDS_RECONCILIATION, "Journal et proposition incohérents; effet bloqué.")
        }
        val reconciliation = runCatching { actions.reconcile(entry.proposal, record.idempotencyKey) }
            .getOrDefault(AgentEffectReconciliation.UNKNOWN)
        if (entry.proposal.state.terminal) {
            return when (reconciliation) {
                AgentEffectReconciliation.APPLIED -> complete(
                    entry.proposal,
                    record,
                    AgentActionOutcome(true, "Effet local réconcilié avant l'annulation ou l'expiration."),
                )
                AgentEffectReconciliation.NOT_APPLIED -> {
                    val terminal = record.copy(
                        state = entry.proposal.state,
                        effect = entry.effect ?: record.effect,
                        atUtcMillis = nowUtcMillis(),
                        phase = AgentEffectPhase.COMPLETE,
                    )
                    runCatching { journal.append(terminal) }.fold(
                        onSuccess = { AgentDispatchResult(DispatchStatus.ALREADY_HANDLED, "Proposition déjà terminée.") },
                        onFailure = { AgentDispatchResult(DispatchStatus.NEEDS_RECONCILIATION, "Journal terminal indisponible; état conservé.") },
                    )
                }
                AgentEffectReconciliation.UNKNOWN -> AgentDispatchResult(
                    DispatchStatus.NEEDS_RECONCILIATION,
                    "Proposition terminée mais effet incertain; journal préparatoire conservé.",
                )
            }
        }
        return when (reconciliation) {
            AgentEffectReconciliation.APPLIED -> complete(entry.proposal, record, AgentActionOutcome(true, "Effet local réconcilié après interruption."))
            AgentEffectReconciliation.NOT_APPLIED -> runPrepared(entry.proposal, record)
            AgentEffectReconciliation.UNKNOWN -> AgentDispatchResult(
                DispatchStatus.NEEDS_RECONCILIATION,
                "Résultat local incertain; répétition bloquée pour éviter un doublon.",
            )
        }
    }

    private fun runPrepared(proposal: ProposalV2, record: AgentEffectRecord): AgentDispatchResult {
        val accepted = proposal.withState(ProposalState.ACCEPTED)
        val mandate = mandates.current()
        val decision = verifier.verify(accepted, mandate, context(mandate))
        if (!decision.accepted) {
            return complete(accepted, record, AgentActionOutcome(false, decision.reason))
        }
        val outcome = try {
            runAllowlistedBranch(accepted)
        } catch (_: Exception) {
            return AgentDispatchResult(
                DispatchStatus.NEEDS_RECONCILIATION,
                "Résultat local incertain; l'effet est journalisé avant reprise.",
            )
        }
        if (outcome.requiresReconciliation) {
            return AgentDispatchResult(
                DispatchStatus.NEEDS_RECONCILIATION,
                outcome.effect,
            )
        }
        return complete(accepted, record, outcome)
    }

    private fun complete(
        proposal: ProposalV2,
        prepared: AgentEffectRecord,
        outcome: AgentActionOutcome,
    ): AgentDispatchResult {
        val terminal = if (outcome.applied) ProposalState.EXECUTED else ProposalState.REJECTED
        val completed = prepared.copy(
            state = terminal,
            effect = outcome.effect.take(240),
            atUtcMillis = nowUtcMillis(),
            phase = AgentEffectPhase.COMPLETE,
        )
        try {
            journal.append(completed)
        } catch (_: Exception) {
            return AgentDispatchResult(
                DispatchStatus.NEEDS_RECONCILIATION,
                "Effet local lancé; écriture finale du journal en attente de réconciliation.",
            )
        }
        runCatching { inbox.update(proposal.proposalId, terminal, outcome.effect) }
        return if (outcome.applied) AgentDispatchResult(DispatchStatus.EXECUTED, outcome.effect)
        else AgentDispatchResult(DispatchStatus.REFUSED, "Action refusée par la politique : ${outcome.effect}")
    }

    private fun repairInboxFromJournal(entry: AgentInboxEntry, record: AgentEffectRecord) {
        if (!entry.proposal.state.terminal && record.state.terminal) {
            runCatching { inbox.update(entry.id, record.state, record.effect) }
        }
    }

    private fun runAllowlistedBranch(proposal: ProposalV2): AgentActionOutcome = when (proposal.actionType) {
        "home.set_directive" -> actions.setHomeDirective(proposal)
        "home.reorder_cards" -> actions.reorderHomeCards(proposal)
        "focus.propose" -> actions.proposeFocus(proposal)
        "focus.plan" -> actions.planFocus(proposal)
        "focus.start" -> actions.startFocus(proposal)
        "focus.pause" -> actions.pauseFocus(proposal)
        "habit.suggest" -> actions.suggestHabit(proposal)
        "app_policy.propose" -> actions.proposeAppPolicy(proposal)
        "app_policy.adjust" -> actions.adjustAppPolicy(proposal)
        "content.recommend" -> actions.recommendContent(proposal)
        else -> AgentActionOutcome(false, "Action inconnue.")
    }

    private fun record(proposal: ProposalV2, state: ProposalState, effect: String) {
        journal.append(AgentEffectRecord(
            proposalId = proposal.proposalId,
            idempotencyKey = proposal.idempotencyKey,
            actionType = proposal.actionType,
            state = state,
            effect = effect.take(240),
            atUtcMillis = nowUtcMillis(),
            phase = if (state.terminal) AgentEffectPhase.COMPLETE else AgentEffectPhase.NONE,
        ))
    }

    companion object { private const val POSTPONE_MILLIS = 60L * 60L * 1_000L }
}

private fun AgentEffectRecord.toJson() = JSONObject()
    .put("proposal_id", proposalId).put("idempotency_key", idempotencyKey)
    .put("action_type", actionType).put("state", state.wireValue)
    .put("effect", effect).put("at_utc_ms", atUtcMillis).put("phase", phase.name.lowercase())

private fun JSONObject.toRecord() = AgentEffectRecord(
    proposalId = getString("proposal_id"), idempotencyKey = getString("idempotency_key"),
    actionType = getString("action_type"),
    state = ProposalState.parse(getString("state")) ?: error("Invalid journal state"),
    effect = getString("effect"), atUtcMillis = getLong("at_utc_ms"),
    phase = optString("phase").takeIf { it.isNotBlank() }?.let { value ->
        AgentEffectPhase.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: error("Invalid journal phase")
    } ?: if (ProposalState.parse(getString("state"))?.terminal == true) AgentEffectPhase.COMPLETE else AgentEffectPhase.NONE,
)
