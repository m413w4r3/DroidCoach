package dev.atlas.coach.training

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportPlanJsonTest {
    @Test
    fun parseValidFixture() {
        val result = SportPlanJson.parse(SportPlanFixture.json)

        assertTrue(result is SportPlanParseResult.Valid)
        val plan = (result as SportPlanParseResult.Valid).plan
        assertEquals(1, plan.schemaVersion)
        assertEquals("2026-10-26", plan.localDate)
        assertEquals("Europe/Paris", plan.timezone)
        assertEquals("12", plan.routineId)
        assertEquals("56", plan.exercises.single().exerciseId)
        assertEquals(3, plan.exercises.single().sets)
    }

    @Test
    fun rejectUnknownSchemaVersion() {
        val json = JSONObject(SportPlanFixture.json).put("schema_version", 2).toString()

        assertEquals("unsupported_schema_version", (SportPlanJson.parse(json) as SportPlanParseResult.Invalid).reason)
    }

    @Test
    fun rejectInvalidJson() {
        assertTrue(SportPlanJson.parse("not json").isInvalid())
        assertTrue(SportPlanJson.parse("{} trailing").isInvalid())
    }

    @Test
    fun rejectMissingRequiredField() {
        val root = JSONObject(SportPlanFixture.json).apply { remove("planned") }

        assertTrue(SportPlanJson.parse(root.toString()).isInvalid())
    }

    @Test
    fun rejectOversizedStrings() {
        val root = JSONObject(SportPlanFixture.json)
        root.getJSONArray("exercises").getJSONObject(0).put("name", "x".repeat(121))

        assertTrue(SportPlanJson.parse(root.toString()).isInvalid())
    }

    @Test
    fun fixtureHasNoPerformedSession() {
        val plan = (SportPlanJson.parse(SportPlanFixture.json) as SportPlanParseResult.Valid).plan

        assertTrue(plan.performed.isEmpty())
    }

    @Test
    fun plannedIsNotPerformed() {
        val plan = (SportPlanJson.parse(SportPlanFixture.json) as SportPlanParseResult.Valid).plan

        assertTrue(plan.planned)
        assertFalse(plan.performed.isNotEmpty())
    }

    private fun SportPlanParseResult.isInvalid(): Boolean = this is SportPlanParseResult.Invalid
}
