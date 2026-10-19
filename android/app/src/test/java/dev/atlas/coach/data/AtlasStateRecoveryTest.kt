package dev.atlas.coach.data

import dev.atlas.coach.focus.FocusJournalLink
import dev.atlas.coach.rules.LocalPolicy
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

    @Test
    fun oldDocumentsMigrateWithoutLinksAndNewSessionLinksAreValidatedIndependently() {
        val legacy = AtlasStateRecovery.recover(
            mapOf("policy" to LocalPolicy().toStorageMap(), "capabilities" to mapOf("focus" to true)),
        )
        assertTrue(legacy.invalidSubstates.isEmpty())
        assertEquals(emptyList<Map<String, Any?>>(), legacy.values["focusJournalLinks"])

        val link = FocusJournalLink(
            sessionId = "session-1",
            journalEntryId = "session-1",
            mockDirectiveId = "directive-1",
            projectId = null,
            habitId = "habit-1",
            completedWorkCycles = 2,
            linkedAtUtcMillis = 1_700_000_000_000L,
        )
        val valid = AtlasStateRecovery.recover(mapOf("focusJournalLinks" to listOf(link.toMap())))
        val restoredLink = FocusJournalLink.fromMap((valid.values["focusJournalLinks"] as List<*>).single() as Map<String, Any?>)
        assertEquals(link, restoredLink)

        val malformed = AtlasStateRecovery.recover(
            mapOf("focusJournalLinks" to listOf(mapOf("sessionId" to "broken")), "capabilities" to mapOf("focus" to true)),
        )
        assertEquals(setOf("focusJournalLinks"), malformed.invalidSubstates)
        assertEquals(mapOf("focus" to true), malformed.values["capabilities"])
    }
}
