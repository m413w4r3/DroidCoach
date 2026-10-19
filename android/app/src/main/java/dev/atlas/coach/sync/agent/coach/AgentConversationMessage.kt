package dev.atlas.coach.sync.agent.coach

import org.json.JSONObject
import java.util.UUID

enum class AgentMessageRole { USER, ASSISTANT }

data class AgentConversationMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: AgentMessageRole,
    val text: String,
    val createdAtUtcMillis: Long,
    val contextSources: List<String> = emptyList(),
) {
    init { require(text.isNotBlank() && text.length <= 4_000 && contextSources.size <= 12) }

    fun toMap() = mapOf(
        "id" to id, "role" to role.name, "text" to text,
        "created_at_utc_ms" to createdAtUtcMillis, "context_sources" to contextSources,
    )

    companion object {
        fun fromMap(values: Map<String, Any?>) = AgentConversationMessage(
            id = values["id"] as? String ?: UUID.randomUUID().toString(),
            role = AgentMessageRole.valueOf(values["role"] as? String ?: error("Missing role")),
            text = values["text"] as? String ?: error("Missing text"),
            createdAtUtcMillis = (values["created_at_utc_ms"] as? Number)?.toLong() ?: error("Missing timestamp"),
            contextSources = (values["context_sources"] as? List<*>)?.map { it as? String ?: error("Invalid context source") }.orEmpty(),
        )
    }
}
