package dev.atlas.coach.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasStateRecoveryTest {
    @Test
    fun malformedPolicyFallsBackOnlyForPolicyAndMalformedRuleEventFallsBackOnlyForRules() {
        val goodFlags = mapOf("focus" to true, "habits" to false)
        val recovered = AtlasStateRecovery.recover(
            mapOf(
                "capabilities" to goodFlags,
                "policy" to mapOf("quietStart" to "not-a-time", "quietEnd" to "07:00"),
                "rules" to mapOf(
                    "recentEventsByRuleAndPackage" to mapOf(
                        "rule\u0000app" to listOf(mapOf("packageName" to "app", "timestampUtc" to 10L)),
                    ),
                ),
            ),
        )

        assertEquals(setOf("policy", "rules"), recovered.invalidSubstates)
        assertEquals(null, recovered.values["policy"])
        assertEquals(null, recovered.values["rules"])
        assertEquals(goodFlags, recovered.values["capabilities"])
    }

    @Test
    fun malformedRuleOpeningWithoutAnEventIdDoesNotEscapeRecovery() {
        val recovered = AtlasStateRecovery.recover(
            mapOf(
                "rules" to mapOf(
                    "recentEventsByRuleAndPackage" to mapOf(
                        "rule\u0000app" to listOf(mapOf("packageName" to "app", "timestampUtc" to 10L)),
                    ),
                ),
            ),
        )
        assertTrue(recovered.invalidSubstates.contains("rules"))
        assertEquals(null, recovered.values["rules"])
    }

    @Test
    fun malformedSessionFallsBackToNoSessionWithoutDiscardingOtherSections() {
        val recovered = AtlasStateRecovery.recover(
            mapOf("focus" to mapOf("status" to "running"), "capabilities" to mapOf("focus" to false)),
        )
        assertEquals(setOf("focus"), recovered.invalidSubstates)
        assertEquals(null, recovered.values["focus"])
        assertEquals(mapOf("focus" to false), recovered.values["capabilities"])
    }
}
