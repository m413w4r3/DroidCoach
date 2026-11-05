package dev.atlas.coach.sync.agent.coach

import java.util.UUID

/** Versioned local snapshot of one completed or abandoned conversation thread. */
data class AgentConversationArchive(
    val conversationId: String,
    val archivedAtUtcMillis: Long,
    val messages: List<AgentConversationMessage>,
    val archiveId: String = UUID.randomUUID().toString(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    init {
        require(conversationId.isNotBlank())
        require(archiveId.isNotBlank())
        require(schemaVersion == CURRENT_SCHEMA_VERSION)
        require(messages.size <= MAX_MESSAGES_PER_ARCHIVE)
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "schema_version" to schemaVersion,
        "archive_id" to archiveId,
        "conversation_id" to conversationId,
        "archived_at_utc_ms" to archivedAtUtcMillis,
        "messages" to messages.map(AgentConversationMessage::toMap),
    )

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MAX_MESSAGES_PER_ARCHIVE = 80

        fun fromMap(values: Map<String, Any?>): AgentConversationArchive? = runCatching {
            if ((values["schema_version"] as? Number)?.toInt() != CURRENT_SCHEMA_VERSION) return null
            AgentConversationArchive(
                conversationId = values["conversation_id"] as? String ?: return null,
                archivedAtUtcMillis = (values["archived_at_utc_ms"] as? Number)?.toLong() ?: return null,
                messages = (values["messages"] as? List<*>)?.mapNotNull { raw ->
                    val message = raw as? Map<*, *> ?: return@mapNotNull null
                    @Suppress("UNCHECKED_CAST")
                    runCatching { AgentConversationMessage.fromMap(message as Map<String, Any?>) }.getOrNull()
                } ?: return null,
                archiveId = values["archive_id"] as? String ?: return null,
            )
        }.getOrNull()
    }
}
