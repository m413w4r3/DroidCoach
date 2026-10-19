package dev.atlas.coach.sync.agent

import org.json.JSONArray
import org.json.JSONObject

/** Read-only contract consumed by LauncherHomeScreen. Missing cards are appended, never hidden. */
data class AgentHomeState(
    val directive: AgentHomeDirective? = null,
    val focusProposal: AgentFocusProposalCard? = null,
    val contentRecommendation: AgentContentCard? = null,
    val cardOrder: List<String> = DEFAULT_CARD_ORDER,
    val cancelledFocusProposalIds: Set<String> = emptySet(),
    val backendObservation: AgentBackendObservation = AgentBackendObservation(),
    val quarantinedRaw: String? = null,
    val quarantineReason: String? = null,
) {
    init {
        require(cardOrder.distinct().size == cardOrder.size && cardOrder.all { it in DEFAULT_CARD_ORDER })
    }

    fun completeCardOrder(): List<String> = cardOrder + DEFAULT_CARD_ORDER.filterNot(cardOrder::contains)

    fun toJson(): JSONObject = JSONObject()
        .put("schema_version", 2)
        .put("directive", directive?.toJson() ?: JSONObject.NULL)
        .put("focus_proposal", focusProposal?.toJson() ?: JSONObject.NULL)
        .put("content_recommendation", contentRecommendation?.toJson() ?: JSONObject.NULL)
        .put("card_order", JSONArray(cardOrder))
        .put("cancelled_focus_proposal_ids", JSONArray(cancelledFocusProposalIds.sorted()))
        .put("backend_observation", backendObservation.toJson())
        .put("quarantined_raw", quarantinedRaw ?: JSONObject.NULL)
        .put("quarantine_reason", quarantineReason ?: JSONObject.NULL)

    fun backendStateAt(nowUtcMillis: Long): AgentBackendState = backendObservation.stateAt(nowUtcMillis)

    fun withBackendContactSucceeded(atUtcMillis: Long): AgentHomeState = copy(
        backendObservation = backendObservation.copy(
            state = AgentBackendState.REACHABLE,
            checkedAtUtcMillis = atUtcMillis,
            lastSuccessfulContactAtUtcMillis = atUtcMillis,
        ),
    )

    fun withBackendContactFailed(atUtcMillis: Long): AgentHomeState = copy(
        backendObservation = backendObservation.copy(
            state = AgentBackendState.UNAVAILABLE,
            checkedAtUtcMillis = atUtcMillis,
        ),
    )

    companion object {
        val DEFAULT_CARD_ORDER = listOf("directive", "focus", "content")

        fun fromJson(raw: String?): AgentHomeState {
            if (raw.isNullOrBlank()) return AgentHomeState()
            return runCatching {
                val root = JSONObject(raw)
                val schemaVersion = root.getInt("schema_version")
                require(schemaVersion in 1..2)
                fun child(key: String) = root.opt(key).takeUnless { it == JSONObject.NULL } as? JSONObject
                val orderJson = root.optJSONArray("card_order")
                val order = orderJson?.let { values -> (0 until values.length()).map { values.getString(it) } }
                    ?.filter { it in DEFAULT_CARD_ORDER }?.distinct().orEmpty()
                val cancelledJson = root.optJSONArray("cancelled_focus_proposal_ids")
                val cancelled = cancelledJson?.let { values -> (0 until values.length()).map { values.getString(it) }.toSet() }.orEmpty()
                AgentHomeState(
                    directive = child("directive")?.let(AgentHomeDirective::fromJson),
                    focusProposal = child("focus_proposal")?.let(AgentFocusProposalCard::fromJson),
                    contentRecommendation = child("content_recommendation")?.let(AgentContentCard::fromJson),
                    cardOrder = if (order.isEmpty()) DEFAULT_CARD_ORDER else order,
                    cancelledFocusProposalIds = cancelled,
                    backendObservation = child("backend_observation")?.let(AgentBackendObservation::fromJson)
                        ?: AgentBackendObservation(),
                    quarantinedRaw = root.optionalNonBlankString("quarantined_raw"),
                    quarantineReason = root.optionalNonBlankString("quarantine_reason"),
                )
            }.getOrElse { error ->
                AgentHomeState(
                    quarantinedRaw = raw,
                    quarantineReason = "${error.javaClass.simpleName}: invalid HOME agent state",
                )
            }
        }
    }
}

/** Apply a validated remote proposal while keeping its origin and source ID explicit. */
fun AgentHomeState.withRemoteDirective(proposal: ProposalV2): AgentHomeState = copy(
    directive = AgentHomeDirective(
        title = proposal.parameters.getValue("title") as String,
        body = proposal.parameters.getValue("body") as String,
        primaryAction = proposal.parameters.getValue("primary_action") as String,
        focusProposalId = proposal.parameters["focus_proposal_id"] as? String,
        provenance = DirectiveProvenance.REMOTE_AI,
        sourceProposalId = proposal.proposalId,
    ),
)

data class AgentHomeDirective(
    val title: String,
    val body: String,
    val primaryAction: String,
    val focusProposalId: String?,
    val provenance: DirectiveProvenance = DirectiveProvenance.REMOTE_AI,
    val sourceProposalId: String? = null,
) {
    fun toJson() = JSONObject().put("title", title).put("body", body)
        .put("primary_action", primaryAction).put("focus_proposal_id", focusProposalId ?: JSONObject.NULL)
        .put("provenance", provenance.wireValue)
        .put("source_proposal_id", sourceProposalId ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject) = AgentHomeDirective(
            json.getString("title"), json.getString("body"), json.getString("primary_action"),
            json.optionalNonBlankString("focus_proposal_id"),
            DirectiveProvenance.parse(json.optString("provenance")) ?: DirectiveProvenance.REMOTE_AI,
            json.optionalNonBlankString("source_proposal_id"),
        )
    }
}

private fun JSONObject.optionalNonBlankString(key: String): String? = when (val value = opt(key)) {
    null, JSONObject.NULL -> null
    is String -> value.takeIf { it.isNotBlank() }
    else -> error("Invalid $key")
}

enum class DirectiveProvenance(val wireValue: String) {
    REMOTE_AI("remote_ai"), LOCAL_DEMO("local_demo"), MANUAL_ENTRY("manual_entry");

    companion object { fun parse(value: String) = entries.firstOrNull { it.wireValue == value } }
}

enum class AgentBackendState { REACHABLE, UNAVAILABLE, UNVERIFIED }

/** Persisted verification data. A stored success is never considered live past the freshness TTL. */
data class AgentBackendObservation(
    val state: AgentBackendState = AgentBackendState.UNVERIFIED,
    val checkedAtUtcMillis: Long? = null,
    val lastSuccessfulContactAtUtcMillis: Long? = null,
) {
    init {
        require(checkedAtUtcMillis == null || checkedAtUtcMillis >= 0L)
        require(lastSuccessfulContactAtUtcMillis == null || lastSuccessfulContactAtUtcMillis >= 0L)
    }

    fun stateAt(nowUtcMillis: Long): AgentBackendState {
        val checkedAt = checkedAtUtcMillis ?: return AgentBackendState.UNVERIFIED
        if (nowUtcMillis < checkedAt || nowUtcMillis - checkedAt > FRESHNESS_TTL_MILLIS) {
            return AgentBackendState.UNVERIFIED
        }
        val lastSuccess = lastSuccessfulContactAtUtcMillis
        if (lastSuccess != null && (nowUtcMillis < lastSuccess || lastSuccess > checkedAt)) {
            return AgentBackendState.UNVERIFIED
        }
        if (state == AgentBackendState.REACHABLE) {
            if (lastSuccess == null || nowUtcMillis - lastSuccess > FRESHNESS_TTL_MILLIS) {
                return AgentBackendState.UNVERIFIED
            }
        }
        return state
    }

    fun toJson() = JSONObject()
        .put("state", state.name.lowercase())
        .put("checked_at_utc_ms", checkedAtUtcMillis ?: JSONObject.NULL)
        .put("last_successful_contact_utc_ms", lastSuccessfulContactAtUtcMillis ?: JSONObject.NULL)

    companion object {
        const val FRESHNESS_TTL_MILLIS = 5L * 60L * 1_000L

        fun fromJson(json: JSONObject): AgentBackendObservation {
            fun optionalLong(key: String): Long? = when (val value = json.opt(key)) {
                null, JSONObject.NULL -> null
                else -> json.getLong(key)
            }
            val stateValue = json.optString("state")
            return AgentBackendObservation(
                state = AgentBackendState.entries.firstOrNull { it.name.equals(stateValue, ignoreCase = true) }
                    ?: AgentBackendState.UNVERIFIED,
                checkedAtUtcMillis = optionalLong("checked_at_utc_ms"),
                lastSuccessfulContactAtUtcMillis = optionalLong("last_successful_contact_utc_ms"),
            )
        }
    }
}

enum class AgentFocusCardState { PROPOSED, PLANNED, RUNNING, PAUSED, CANCELLED, FINISHED }

data class AgentFocusProposalCard(
    val proposalId: String,
    val label: String,
    val focusMinutes: Int,
    val breakMinutes: Int,
    val cycles: Int,
    val state: AgentFocusCardState = AgentFocusCardState.PROPOSED,
    val startAtUtcMillis: Long? = null,
    val routineId: String? = null,
    val longBreakMinutes: Int = 15,
    val longBreakEveryCycles: Int = 4,
    val continuous: Boolean = false,
) {
    init {
        require(longBreakMinutes in 1..120)
        require(longBreakEveryCycles in 1..12)
    }

    fun toJson() = JSONObject()
        .put("proposal_id", proposalId).put("label", label)
        .put("focus_minutes", focusMinutes).put("break_minutes", breakMinutes).put("cycles", cycles)
        .put("state", state.name).put("start_at_utc_ms", startAtUtcMillis ?: JSONObject.NULL)
        .put("routine_id", routineId ?: JSONObject.NULL)
        .put("long_break_minutes", longBreakMinutes)
        .put("long_break_every_cycles", longBreakEveryCycles)
        .put("continuous", continuous)

    companion object {
        fun fromJson(json: JSONObject) = AgentFocusProposalCard(
            proposalId = json.getString("proposal_id"), label = json.getString("label"),
            focusMinutes = json.getInt("focus_minutes"), breakMinutes = json.getInt("break_minutes"),
            cycles = json.getInt("cycles"),
            state = AgentFocusCardState.valueOf(json.getString("state")),
            startAtUtcMillis = json.optLong("start_at_utc_ms", 0L).takeIf { it > 0L },
            routineId = json.optString("routine_id").takeIf { it.isNotBlank() },
            longBreakMinutes = json.optInt("long_break_minutes", 15),
            longBreakEveryCycles = json.optInt("long_break_every_cycles", 4),
            continuous = json.optBoolean("continuous", false),
        )
    }
}

data class AgentContentCard(
    val contentId: String?,
    val url: String?,
    val title: String,
) {
    fun toJson() = JSONObject().put("content_id", contentId ?: JSONObject.NULL)
        .put("url", url ?: JSONObject.NULL).put("title", title)

    companion object {
        fun fromJson(json: JSONObject) = AgentContentCard(
            contentId = json.optionalNonBlankString("content_id"),
            url = json.optionalNonBlankString("url"),
            title = json.getString("title"),
        )
    }
}
