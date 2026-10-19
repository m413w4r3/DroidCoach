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
) {
    init {
        require(cardOrder.distinct().size == cardOrder.size && cardOrder.all { it in DEFAULT_CARD_ORDER })
    }

    fun completeCardOrder(): List<String> = cardOrder + DEFAULT_CARD_ORDER.filterNot(cardOrder::contains)

    fun toJson(): JSONObject = JSONObject()
        .put("schema_version", 1)
        .put("directive", directive?.toJson() ?: JSONObject.NULL)
        .put("focus_proposal", focusProposal?.toJson() ?: JSONObject.NULL)
        .put("content_recommendation", contentRecommendation?.toJson() ?: JSONObject.NULL)
        .put("card_order", JSONArray(cardOrder))
        .put("cancelled_focus_proposal_ids", JSONArray(cancelledFocusProposalIds.sorted()))

    companion object {
        val DEFAULT_CARD_ORDER = listOf("directive", "focus", "content")

        fun fromJson(raw: String?): AgentHomeState {
            if (raw.isNullOrBlank()) return AgentHomeState()
            return runCatching {
                val root = JSONObject(raw)
                require(root.getInt("schema_version") == 1)
                fun child(key: String) = root.get(key).takeUnless { it == JSONObject.NULL } as? JSONObject
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
                )
            }.getOrDefault(AgentHomeState())
        }
    }
}

data class AgentHomeDirective(
    val title: String,
    val body: String,
    val primaryAction: String,
    val focusProposalId: String?,
) {
    fun toJson() = JSONObject().put("title", title).put("body", body)
        .put("primary_action", primaryAction).put("focus_proposal_id", focusProposalId ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject) = AgentHomeDirective(
            json.getString("title"), json.getString("body"), json.getString("primary_action"),
            json.optString("focus_proposal_id").takeIf { it.isNotBlank() },
        )
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
) {
    fun toJson() = JSONObject()
        .put("proposal_id", proposalId).put("label", label)
        .put("focus_minutes", focusMinutes).put("break_minutes", breakMinutes).put("cycles", cycles)
        .put("state", state.name).put("start_at_utc_ms", startAtUtcMillis ?: JSONObject.NULL)
        .put("routine_id", routineId ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject) = AgentFocusProposalCard(
            proposalId = json.getString("proposal_id"), label = json.getString("label"),
            focusMinutes = json.getInt("focus_minutes"), breakMinutes = json.getInt("break_minutes"),
            cycles = json.getInt("cycles"),
            state = AgentFocusCardState.valueOf(json.getString("state")),
            startAtUtcMillis = json.optLong("start_at_utc_ms", 0L).takeIf { it > 0L },
            routineId = json.optString("routine_id").takeIf { it.isNotBlank() },
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
            contentId = json.optString("content_id").takeIf { it.isNotBlank() },
            url = json.optString("url").takeIf { it.isNotBlank() },
            title = json.getString("title"),
        )
    }
}
