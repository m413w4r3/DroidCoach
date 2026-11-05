package dev.atlas.coach.sync.agent.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentConversationArchiveTest {
    @Test
    fun versionedArchiveRoundTripsMessagesAndConversationIdentity() {
        val messages = listOf(
            AgentConversationMessage(role = AgentMessageRole.USER, text = "Décale la séance", createdAtUtcMillis = 10L),
            AgentConversationMessage(
                role = AgentMessageRole.ASSISTANT,
                text = "Je propose dix minutes.",
                createdAtUtcMillis = 11L,
                diagnosticDetails = "État des outils : completed (2 appel(s)). Aucune proposition (0).",
            ),
        )
        val original = AgentConversationArchive(
            conversationId = "conversation-old",
            archivedAtUtcMillis = 12L,
            messages = messages,
        )

        val restored = AgentConversationArchive.fromMap(original.toMap())

        assertEquals(original, restored)
    }

    @Test
    fun unsupportedArchiveVersionIsIgnoredWithoutAffectingLegacyCurrentThread() {
        val invalid = mapOf(
            "schema_version" to AgentConversationArchive.CURRENT_SCHEMA_VERSION + 1,
            "conversation_id" to "old-id",
            "archived_at_utc_ms" to 12L,
            "messages" to emptyList<Any>(),
            "archive_id" to "archive-id",
        )

        assertNull(AgentConversationArchive.fromMap(invalid))
    }
}
