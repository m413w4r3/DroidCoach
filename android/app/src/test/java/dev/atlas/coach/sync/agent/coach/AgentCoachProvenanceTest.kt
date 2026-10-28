package dev.atlas.coach.sync.agent.coach

import dev.atlas.coach.app.readableProvenanceLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentCoachProvenanceTest {
    @Test
    fun mapsMockConnectedAndUnavailableAgentStatusToReadableProvenance() {
        val mock = readableProvenanceLabel(listOf("agent_status:mock", "Adaptateur local déterministe"), "fallback")
        val connected = readableProvenanceLabel(listOf("agent_status:connected"), "fallback")
        val unavailable = readableProvenanceLabel(
            listOf("agent_status:unavailable", "Version de contexte ctx-test"),
            "fallback",
        )

        assertEquals("Démonstration locale (mock)", mock)
        assertEquals("OpenClaw via PC", connected)
        assertEquals("Agent indisponible", unavailable)
        assertFalse(mock.contains("agent_status"))
        assertFalse(connected.contains("connected"))
    }
}
