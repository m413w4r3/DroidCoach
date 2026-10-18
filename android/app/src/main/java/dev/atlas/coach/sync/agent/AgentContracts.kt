package dev.atlas.coach.sync.agent

import dev.atlas.coach.sync.OutboxStorage
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A future provider can return proposals only; this module has no Android action executor. */
interface CoachDecisionProvider {
    fun propose(context: String, mandate: AgentMandate): ProposedAction
}

interface AgentAdapter : CoachDecisionProvider

enum class ProposedActionType(val wireValue: String) {
    FOCUS_SESSION("focus_session"),
    SUGGEST_BREAK("suggest_break"),
    OPEN_CONTENT("open_content"),
}

data class ProposedAction(
    val type: String,
    val parameters: Map<String, String>,
    val justification: String,
)

data class AgentMandate(
    val id: String,
    val allowedActionTypes: Set<String>,
    val revoked: Boolean = false,
    val version: Long = 1L,
)

interface MandateStore {
    fun current(): AgentMandate
    fun revoke()
    fun replace(mandate: AgentMandate)
}

class LocalMandateStore(initial: AgentMandate) : MandateStore {
    @Volatile private var mandate = initial

    override fun current(): AgentMandate = mandate

    @Synchronized
    override fun revoke() {
        mandate = mandate.copy(revoked = true, version = mandate.version + 1)
    }

    @Synchronized
    override fun replace(mandate: AgentMandate) {
        require(mandate.id.isNotBlank())
        this.mandate = mandate.copy(revoked = false, version = this.mandate.version + 1)
    }
}

data class MandateDecision(val accepted: Boolean, val reason: String)

/** Deterministic local allowlist and parameter validation. Unknown types are always rejected. */
class MandateVerifier {
    fun verify(proposal: ProposedAction, mandate: AgentMandate): MandateDecision {
        if (mandate.revoked) return MandateDecision(false, "Mandat révoqué.")
        val type = ProposedActionType.entries.firstOrNull { it.wireValue == proposal.type }
            ?: return MandateDecision(false, "Type d'action hors liste blanche.")
        if (proposal.type !in mandate.allowedActionTypes) {
            return MandateDecision(false, "Action absente du mandat.")
        }
        if (proposal.justification.isBlank() || proposal.justification.length > 400) {
            return MandateDecision(false, "Justification invalide.")
        }
        val allowedParameters = when (type) {
            ProposedActionType.FOCUS_SESSION -> setOf("duration_minutes", "project_id")
            ProposedActionType.SUGGEST_BREAK -> setOf("duration_minutes")
            ProposedActionType.OPEN_CONTENT -> setOf("content_id")
        }
        if (proposal.parameters.keys.any { it !in allowedParameters }) {
            return MandateDecision(false, "Paramètre hors liste blanche.")
        }
        val duration = proposal.parameters["duration_minutes"]
        if (type != ProposedActionType.OPEN_CONTENT && duration == null) {
            return MandateDecision(false, "Durée requise.")
        }
        if (duration != null) {
            val parsed = duration.toIntOrNull()
                ?: return MandateDecision(false, "Durée invalide.")
            val range = if (type == ProposedActionType.SUGGEST_BREAK) 1..30 else 1..180
            if (parsed !in range) return MandateDecision(false, "Durée hors limites.")
        }
        val identifier = proposal.parameters[if (type == ProposedActionType.OPEN_CONTENT) "content_id" else "project_id"]
        if (identifier != null && (identifier.isBlank() || identifier.length > 80)) {
            return MandateDecision(false, "Identifiant de contenu invalide.")
        }
        if (type == ProposedActionType.OPEN_CONTENT && proposal.parameters["content_id"] == null) {
            return MandateDecision(false, "Identifiant de contenu requis.")
        }
        return MandateDecision(true, "Proposition autorisée par le mandat local.")
    }
}

data class ProposalRecord(
    val id: String,
    val createdAtUtcMillis: Long,
    val mandateId: String,
    val mandateVersion: Long,
    val proposal: ProposedAction,
    val validation: MandateDecision,
    val simulationMode: Boolean,
)

interface ProposalJournal {
    fun append(record: ProposalRecord)
    fun entries(): List<ProposalRecord>
}

class InMemoryProposalJournal : ProposalJournal {
    private val records = mutableListOf<ProposalRecord>()

    @Synchronized
    override fun append(record: ProposalRecord) { records += record }

    @Synchronized
    override fun entries(): List<ProposalRecord> = records.toList()
}

/** Durable local journal for proposals and their deterministic validation result. */
class JsonProposalJournal(private val storage: OutboxStorage) : ProposalJournal {
    private val records = mutableListOf<ProposalRecord>()

    init {
        val raw = storage.read()
        if (!raw.isNullOrBlank()) {
            val root = JSONObject(raw)
            require(root.getInt("schema_version") == SCHEMA_VERSION)
            val values = root.getJSONArray("records")
            for (index in 0 until values.length()) records += values.getJSONObject(index).toRecord()
        }
    }

    @Synchronized
    override fun append(record: ProposalRecord) {
        records += record
        persist()
    }

    @Synchronized
    override fun entries(): List<ProposalRecord> = records.toList()

    private fun persist() {
        storage.write(JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("records", JSONArray().also { array -> records.forEach { array.put(it.toJson()) } })
            .toString())
    }

    companion object { private const val SCHEMA_VERSION = 1 }
}

class ProposalWorkflow(
    private val provider: CoachDecisionProvider,
    private val mandates: MandateStore,
    private val verifier: MandateVerifier,
    private val journal: ProposalJournal,
    private val nowUtcMillis: () -> Long = System::currentTimeMillis,
    private val simulationMode: Boolean = true,
) {
    fun propose(context: String): ProposalRecord {
        require(context.length <= 4_000) { "Coach context is too long" }
        val mandate = mandates.current()
        val proposal = provider.propose(context, mandate)
        val record = ProposalRecord(
            id = UUID.randomUUID().toString(),
            createdAtUtcMillis = nowUtcMillis(),
            mandateId = mandate.id,
            mandateVersion = mandate.version,
            proposal = proposal,
            validation = verifier.verify(proposal, mandate),
            simulationMode = simulationMode,
        )
        journal.append(record)
        return record
    }
}

/** Stable mock for UI and contract tests; it ignores free-form text and only proposes focus. */
class DeterministicMockDecisionProvider : AgentAdapter {
    override fun propose(context: String, mandate: AgentMandate): ProposedAction = ProposedAction(
        type = ProposedActionType.FOCUS_SESSION.wireValue,
        parameters = mapOf("duration_minutes" to "10"),
        justification = "Une courte session est une prochaine étape simple.",
    )
}

private fun ProposalRecord.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("created_at_utc_ms", createdAtUtcMillis)
    .put("mandate_id", mandateId)
    .put("mandate_version", mandateVersion)
    .put("action", proposal.type)
    .put("parameters", JSONObject(proposal.parameters))
    .put("justification", proposal.justification)
    .put("accepted", validation.accepted)
    .put("validation_reason", validation.reason)
    .put("simulation_mode", simulationMode)

private fun JSONObject.toRecord(): ProposalRecord {
    val parametersJson = getJSONObject("parameters")
    val parameters = buildMap {
        val keys = parametersJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, parametersJson.getString(key))
        }
    }
    return ProposalRecord(
        id = getString("id"),
        createdAtUtcMillis = getLong("created_at_utc_ms"),
        mandateId = getString("mandate_id"),
        mandateVersion = getLong("mandate_version"),
        proposal = ProposedAction(getString("action"), parameters, getString("justification")),
        validation = MandateDecision(getBoolean("accepted"), getString("validation_reason")),
        simulationMode = getBoolean("simulation_mode"),
    )
}
