package dev.atlas.coach.e2e

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V7_06DProposalOracleTest {
    @Test
    fun zeroProposalMetadataIsReportedAsProposalMissing() {
        val failure = try {
            V7_06BChatTestSupport.parseToolMetadata(
                "État des outils : completed (2 appel(s)). Aucune proposition (0).",
            )
            throw AssertionError("zero proposals must fail the oracle")
        } catch (failure: IllegalStateException) {
            failure
        }

        assertEquals("PROPOSAL_MISSING", failure.message)
    }

    @Test
    fun oneProposalMetadataParsesAndRemainsEligibleForPassAssertions() {
        val proposalId = "00000000-0000-4000-8000-000000000201"
        val metadata = V7_06BChatTestSupport.parseToolMetadata(
            "État des outils : completed (2 appel(s)). Proposition(s) (1) — identifiants : " +
                "$proposalId. Contenu à synchroniser via la boîte de réception.",
        )

        assertEquals("completed", metadata.toolState)
        assertEquals(2, metadata.toolCallCount)
        assertEquals(listOf(proposalId), metadata.proposalIds)
        assertEquals(1, metadata.proposalIds.size)
    }

    @Test
    fun syntheticPromptMatchesProposalContractWithoutBackendOwnedKey() {
        val schemaText = javaClass.getResourceAsStream("/tool-requests.schema.json")!!
            .bufferedReader()
            .use { it.readText() }
        val proposalSchema = JSONObject(schemaText)
            .getJSONObject("\$defs")
            .getJSONObject("atlas_propose_plan_revision_arguments")
        val properties = proposalSchema.getJSONObject("properties")
        val required = proposalSchema.getJSONArray("required").let { fields ->
            (0 until fields.length()).map(fields::getString)
        }
        assertTrue(properties.has("changes"))
        assertTrue(properties.has("justification"))
        assertTrue(required.containsAll(listOf("plan_id", "base_version", "changes", "justification")))
        val rescheduleSchema = properties.getJSONObject("changes")
            .getJSONObject("items")
            .getJSONArray("oneOf")
            .getJSONObject(0)
            .getJSONObject("properties")
        assertTrue(rescheduleSchema.has("op"))
        assertTrue(rescheduleSchema.has("activity_id"))
        assertTrue(rescheduleSchema.has("target_start_minute"))
        assertTrue(rescheduleSchema.has("duration_minutes"))

        val prompt = V7_06BChatTestSupport.syntheticCoachMessage(
            "2030-01-02",
            "00000000-0000-4000-8000-000000000201",
            2,
            "00000000-0000-4000-8000-000000000203",
            610,
            45,
            "synthetic-run",
        )
        assertTrue(prompt.contains("changes: [{\"op\": \"reschedule\""))
        assertTrue(prompt.contains("\"activity_id\": \"00000000-0000-4000-8000-000000000203\""))
        assertTrue(prompt.contains("\"target_start_minute\": 610"))
        assertTrue(prompt.contains("\"duration_minutes\": 45"))
        assertTrue(prompt.contains("justification:"))
        assertFalse(prompt.contains("idempotency_key"))
    }
}
