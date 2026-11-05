package dev.atlas.coach.e2e

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V7_06DProposalOracleTest {
    @Test
    fun zeroProposalMetadataIsReportedAsProposalMissing() {
        assertParserError(
            "PROPOSAL_MISSING",
            zeroProposalMetadata("completed"),
        )
    }

    @Test
    fun oneProposalMetadataParsesAndRemainsEligibleForPassAssertions() {
        val proposalId = "00000000-0000-4000-8000-000000000201"
        val visibleReply = "La modification est prête à confirmer."
        assertFalse(visibleReply.contains("État des outils"))
        val metadata = V7_06BChatTestSupport.parseToolMetadata(proposalMetadata("completed", 1, proposalId))

        assertEquals("completed", metadata.toolState)
        assertEquals(2, metadata.toolCallCount)
        assertEquals(listOf(proposalId), metadata.proposalIds)
        assertEquals(1, metadata.proposalIds.size)
    }

    @Test
    fun nonCompletedToolStateIsRejectedWithAnExplicitCode() {
        val proposalId = "00000000-0000-4000-8000-000000000201"

        assertParserError(
            "TOOL_STATE_NOT_COMPLETED",
            proposalMetadata("failed", 1, proposalId),
        )
    }

    @Test
    fun statusAndProposalErrorsRemainDistinguishableByPriority() {
        assertParserError("TOOL_STATE_NOT_COMPLETED", zeroProposalMetadata("failed"))
        assertParserError("TOOL_STATE_NOT_COMPLETED", proposalMetadata("failed", 2, ""))
        assertParserError("PROPOSAL_MISSING", zeroProposalMetadata("completed"))
    }

    @Test
    fun inconsistentProposalCountsAndDuplicateIdsAreRejectedWithExplicitCodes() {
        val firstId = "00000000-0000-4000-8000-000000000201"
        val secondId = "00000000-0000-4000-8000-000000000202"

        assertParserError("PROPOSAL_COUNT_MISMATCH", proposalMetadata("completed", 2, firstId))
        assertParserError("PROPOSAL_COUNT_MISMATCH", proposalMetadata("completed", 1, "$firstId, $secondId"))
        assertParserError("PROPOSAL_ID_DUPLICATE", proposalMetadata("completed", 2, "$firstId, $firstId"))
    }

    @Test
    fun nonCanonicalProposalIdIsRejectedWithAnExplicitCode() {
        assertParserError("PROPOSAL_ID_INVALID", proposalMetadata("completed", 1, "not-a-uuid"))
    }

    @Test
    fun invalidIdPrecedesDuplicateDetectionWhenTheCountMatches() {
        assertParserError("PROPOSAL_ID_INVALID", proposalMetadata("completed", 2, "not-a-uuid, not-a-uuid"))
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
        val changeVariants = properties.getJSONObject("changes")
            .getJSONObject("items")
            .getJSONArray("oneOf")
        val rescheduleVariant = schemaVariantByConst(changeVariants, "op", "reschedule")
        val reversedVariants = JSONArray()
        for (index in changeVariants.length() - 1 downTo 0) {
            reversedVariants.put(changeVariants.getJSONObject(index))
        }
        val rescheduleVariantAfterReordering = schemaVariantByConst(reversedVariants, "op", "reschedule")
        assertEquals(rescheduleVariant.toString(), rescheduleVariantAfterReordering.toString())

        val rescheduleSchema = rescheduleVariant.getJSONObject("properties")
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

    private fun assertParserError(expectedCode: String, response: String) {
        val failure = try {
            V7_06BChatTestSupport.parseToolMetadata(response)
            throw AssertionError("expected parser error $expectedCode")
        } catch (failure: IllegalStateException) {
            failure
        }
        assertEquals(expectedCode, failure.message)
    }

    @Test
    fun visibleReplyIsNotAcceptedAsToolDiagnostics() {
        assertParserError("V4 tool metadata is missing from diagnostic details", "La modification est prête à confirmer.")
    }

    private fun zeroProposalMetadata(toolState: String): String =
        "État des outils : $toolState (2 appel(s)). Aucune proposition (0)."

    private fun proposalMetadata(toolState: String, proposalCount: Int, proposalIds: String): String =
        "État des outils : $toolState (2 appel(s)). Proposition(s) ($proposalCount) — identifiants : " +
            "$proposalIds. Contenu à synchroniser via la boîte de réception."

    private fun schemaVariantByConst(variants: JSONArray, propertyName: String, expected: String): JSONObject {
        val matchingVariants = (0 until variants.length())
            .map { variants.getJSONObject(it) }
            .filter { variant ->
                variant.optJSONObject("properties")
                    ?.optJSONObject(propertyName)
                    ?.optString("const") == expected
            }
        assertEquals("schema must have exactly one $propertyName=$expected branch", 1, matchingVariants.size)
        return matchingVariants.single()
    }
}
