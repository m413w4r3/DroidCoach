package dev.atlas.coach.sync.agent.context

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastContextDisclosureAuditTest {
    @Test
    fun categoryPurgeRemovesItsValuesFromSessionPreviewAndKeepsOnlyMetadataDurable() {
        val payload = payload()
        val audit = requireNotNull(LastContextDisclosureAudit.fromLegacyPayload(payload, SENT_AT))

        val purged = JSONObject(requireNotNull(
            LastContextDisclosureAudit.purgePayloadCategory(payload, "goals"),
        ))
        val durableAuditJson = JSONObject(audit.toMap()).toString()

        assertFalse(purged.toString().contains("private goal title"))
        assertTrue(purged.toString().contains("focus summary"))
        assertEquals(listOf("focus"), purged.getJSONArray("consented_categories").strings())
        assertFalse(durableAuditJson.contains("private goal title"))
        assertEquals(listOf("focus", "goals"), audit.categoryNames)
    }

    @Test
    fun totalPayloadPurgeLeavesTheDisclosureAuditButNoValues() {
        val legacyPayload = payload()
        val audit = requireNotNull(LastContextDisclosureAudit.fromLegacyPayload(legacyPayload, SENT_AT))

        val purgedPayload = LastContextDisclosureAudit.purgeAllPayloadValues(legacyPayload)
        val auditAfterRecreation = LastContextDisclosureAudit.fromMap(audit.toMap())

        assertNull(purgedPayload)
        assertEquals(audit, auditAfterRecreation)
        assertFalse(JSONObject(audit.toMap()).toString().contains("private goal title"))
    }

    @Test
    fun v62PayloadMigratesToMetadataOnlyAndRecreatesWithoutValues() {
        val legacyPayload = payload()

        val migrated = requireNotNull(LastContextDisclosureAudit.fromLegacyPayload(legacyPayload, SENT_AT))
        val persisted = migrated.toMap()
        val recreated = requireNotNull(LastContextDisclosureAudit.fromMap(persisted))
        val encodedAudit = JSONObject(persisted).toString()

        assertEquals(SENT_AT, recreated.sentAtUtcMillis)
        assertEquals(listOf("focus", "goals"), recreated.categoryNames)
        assertEquals(LastContextDisclosureStatus.ATTEMPTED, recreated.status)
        assertFalse(encodedAudit.contains("private goal title"))
        assertFalse(encodedAudit.contains("focus summary"))
        assertEquals(recreated, LastContextDisclosureAudit.fromMap(recreated.toMap()))
    }

    private fun payload() = JSONObject()
        .put("consented_categories", JSONArray(listOf("focus", "goals")))
        .put("categories", JSONObject()
            .put("focus", JSONArray(listOf("focus summary")))
            .put("goals", JSONArray(listOf("private goal title"))))
        .toString()

    private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)

    private companion object {
        const val SENT_AT = 1_800_000_000_000L
    }
}
