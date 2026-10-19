package dev.atlas.coach.sync.agent.dispatch

import dev.atlas.coach.sync.OutboxStorage
import dev.atlas.coach.sync.agent.AgentActionType
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.AgentVerificationContext
import dev.atlas.coach.sync.agent.MandateStore
import dev.atlas.coach.sync.agent.MandateVerifier
import dev.atlas.coach.sync.agent.ProposalState
import dev.atlas.coach.sync.agent.ProposalV2
import dev.atlas.coach.sync.agent.inbox.AgentInboxRepository
import org.json.JSONArray
import org.json.JSONObject

data class AgentActionOutcome(val applied: Boolean, val effect: String)

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
}

data class AgentEffectRecord(
    val proposalId: String,
    val idempotencyKey: String,
    val actionType: String,
    val state: ProposalState,
    val effect: String,
    val atUtcMillis: Long,
)

interface AgentEffectJournal {
    fun append(record: AgentEffectRecord)
    fun entries(): List<AgentEffectRecord>
    fun findByProposalId(id: String): AgentEffectRecord?
}

class InMemoryAgentEffectJournal : AgentEffectJournal {
    private val records = mutableListOf<AgentEffectRecord>()
    @Synchronized override fun append(record: AgentEffectRecord) {
        records.removeAll { it.proposalId == record.proposalId }
        records += record
    }
    @Synchronized override fun entries() = records.toList()
    @Synchronized override fun findByProposalId(id: String) = records.lastOrNull { it.proposalId == id }
}

class JsonAgentEffectJournal(private val storage: OutboxStorage) : AgentEffectJournal {
    private val records = mutableListOf<AgentEffectRecord>()

    init {
        val raw = storage.read()
        if (!raw.isNullOrBlank()) runCatching {
            val root = JSONObject(raw)
            require(root.getInt("schema_version") == SCHEMA_VERSION)
            val array = root.getJSONArray("records")
            for (index in 0 until array.length()) records += array.getJSONObject(index).toRecord()
        }.onFailure { records.clear() }
    }

    @Synchronized override fun append(record: AgentEffectRecord) {
        records.removeAll { it.proposalId == record.proposalId }
        records += record
        while (records.size > MAX_RECORDS) records.removeAt(0)
        persist()
    }

    @Synchronized override fun entries() = records.toList()
    @Synchronized override fun findByProposalId(id: String) = records.lastOrNull { it.proposalId == id }

    private fun persist() {
        storage.write(JSONObject().put("schema_version", SCHEMA_VERSION)
            .put("records", JSONArray().also { array -> records.forEach { array.put(it.toJson()) } }).toString())
    }

    companion object {
        const val MAX_RECORDS = 500
        private const val SCHEMA_VERSION = 1
    }
}

enum class DispatchStatus { EXECUTED, REFUSED, NEEDS_CONFIRMATION, ALREADY_HANDLED, NOT_FOUND }

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
    fun accept(proposalId: String): AgentDispatchResult = dispatch(proposalId, explicitConfirmation = true)

    fun dispatchPreauthorized(proposalId: String): AgentDispatchResult = dispatch(proposalId, explicitConfirmation = false)

    fun reject(proposalId: String): AgentDispatchResult {
        val entry = inbox.get(proposalId) ?: return AgentDispatchResult(DispatchStatus.NOT_FOUND, "Proposition introuvable.")
        val updated = inbox.update(proposalId, ProposalState.REJECTED, "Refusée par l'utilisateur")
            ?: return AgentDispatchResult(DispatchStatus.ALREADY_HANDLED, "Proposition déjà terminée.")
        record(updated.proposal, ProposalState.REJECTED, "Refusée par l'utilisateur")
        return AgentDispatchResult(DispatchStatus.REFUSED, "Proposition refusée.")
    }

    fun postpone(proposalId: String): AgentDispatchResult {
        val entry = inbox.get(proposalId) ?: return AgentDispatchResult(DispatchStatus.NOT_FOUND, "Proposition introuvable.")
        if (entry.proposal.state.terminal) return AgentDispatchResult(DispatchStatus.ALREADY_HANDLED, "Proposition déjà terminée.")
        inbox.postpone(proposalId, nowUtcMillis() + POSTPONE_MILLIS)
        record(entry.proposal, ProposalState.PENDING_CONFIRMATION, "Reportée par l'utilisateur")
        return AgentDispatchResult(DispatchStatus.NEEDS_CONFIRMATION, "Proposition reportée.")
    }

    fun edit(proposalId: String, parameters: Map<String, Any?>): AgentDispatchResult {
        val entry = inbox.get(proposalId) ?: return AgentDispatchResult(DispatchStatus.NOT_FOUND, "Proposition introuvable.")
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
        if (proposal.state == ProposalState.EXECUTED || journal.findByProposalId(proposalId)?.state == ProposalState.EXECUTED) {
            return AgentDispatchResult(DispatchStatus.ALREADY_HANDLED, "Action déjà planifiée ou appliquée.")
        }
        if (proposal.state.terminal) return AgentDispatchResult(DispatchStatus.ALREADY_HANDLED, "Proposition déjà terminée.")
        val mandate = mandates.current()
        val decision = verifier.verify(proposal, mandate, context(mandate))
        if (!decision.accepted) {
            inbox.update(proposalId, ProposalState.REJECTED, decision.reason)
            record(proposal, ProposalState.REJECTED, decision.reason)
            return AgentDispatchResult(DispatchStatus.REFUSED, "Action refusée par la politique : ${decision.reason}")
        }
        if (!explicitConfirmation && decision.requiresConfirmation) {
            return AgentDispatchResult(DispatchStatus.NEEDS_CONFIRMATION, "Une confirmation locale est nécessaire.")
        }
        inbox.update(proposalId, ProposalState.ACCEPTED, "Acceptée localement")
        val outcome = runCatching { runAllowlistedBranch(proposal) }.getOrElse {
            AgentActionOutcome(false, "Échec local; aucune action externe n'a été exécutée.")
        }
        val state = if (outcome.applied) ProposalState.EXECUTED else ProposalState.REJECTED
        inbox.update(proposalId, state, outcome.effect)
        record(proposal, state, outcome.effect)
        return if (outcome.applied) AgentDispatchResult(DispatchStatus.EXECUTED, outcome.effect)
        else AgentDispatchResult(DispatchStatus.REFUSED, "Action refusée par la politique : ${outcome.effect}")
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
        ))
    }

    companion object { private const val POSTPONE_MILLIS = 60L * 60L * 1_000L }
}

private fun AgentEffectRecord.toJson() = JSONObject()
    .put("proposal_id", proposalId).put("idempotency_key", idempotencyKey)
    .put("action_type", actionType).put("state", state.wireValue)
    .put("effect", effect).put("at_utc_ms", atUtcMillis)

private fun JSONObject.toRecord() = AgentEffectRecord(
    proposalId = getString("proposal_id"), idempotencyKey = getString("idempotency_key"),
    actionType = getString("action_type"),
    state = ProposalState.parse(getString("state")) ?: error("Invalid journal state"),
    effect = getString("effect"), atUtcMillis = getLong("at_utc_ms"),
)
