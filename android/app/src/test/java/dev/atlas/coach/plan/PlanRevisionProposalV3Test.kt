package dev.atlas.coach.plan

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanRevisionProposalV3Test {
    @Test
    fun parsesAValidV3ProposalAndNullProposalResponse() {
        val parsed = PlanRevisionProposalV3.parse(response().toString())

        assertEquals(3, parsed.schemaVersion)
        assertEquals("ok", parsed.agentStatus)
        assertEquals(1, parsed.proposal!!.parameters.activities.size)
        assertEquals("ref-1", parsed.proposal.parameters.activities.single().clientRef)
        assertEquals(listOf("ref-1" to "ref-2"), parsed.overlaps)
        assertEquals(null, PlanRevisionProposalV3.parse(response(proposal = null).toString()).proposal)
    }

    @Test
    fun rejectsUnknownFieldsAndDuplicateJsonKeys() {
        val unknown = response().put("unexpected", true)
        assertInvalid(unknown.toString())

        val duplicate = response().toString().replaceFirst(
            "\"schema_version\":3",
            "\"schema_version\":3,\"schema_version\":3",
        )
        assertInvalid(duplicate)
    }

    @Test
    fun rejectsSchemaTwoAndUnknownAction() {
        assertInvalid(response().put("schema_version", 2).toString())
        val unknownAction = response()
        unknownAction.getJSONObject("proposal").getJSONObject("proposal")
            .put("action_type", "plan.replace_week")
        assertInvalid(unknownAction.toString())
    }

    @Test
    fun rejectsExpiredAndOverlongTtl() {
        val expired = response()
        expired.getJSONObject("proposal").put("expires_at", "2026-10-26T10:00:00Z")
        assertInvalid(expired.toString())

        val overlong = response()
        overlong.getJSONObject("proposal").put("expires_at", "2026-10-27T10:00:01Z")
        assertInvalid(overlong.toString())
    }

    @Test
    fun rejectsNonCanonicalUuidAndIncorrectTypes() {
        val uuid = response()
        uuid.getJSONObject("proposal").put("proposal_id", PROPOSAL_ID.uppercase())
        assertInvalid(uuid.toString())

        val type = response()
        type.getJSONObject("proposal").getJSONObject("proposal").getJSONObject("parameters")
            .getJSONArray("activities").getJSONObject(0).put("start_minute", 540.0)
        assertInvalid(type.toString())
    }

    @Test
    fun rejectsDuplicateClientRefsAndInconsistentDateOrTimezone() {
        val duplicate = response(activities = JSONArray()
            .put(activity(activityId = ACTIVITY_ID))
            .put(activity(activityId = SECOND_ACTIVITY_ID)))
        assertInvalid(duplicate.toString())

        val duplicateId = response(activities = JSONArray()
            .put(activity(clientRef = "one", activityId = ACTIVITY_ID))
            .put(activity(clientRef = "two", activityId = ACTIVITY_ID)))
        assertInvalid(duplicateId.toString())

        val otherDate = response()
        otherDate.getJSONObject("proposal").getJSONObject("proposal").getJSONObject("parameters")
            .getJSONArray("activities").getJSONObject(0).put("local_date", "2026-10-27")
        assertInvalid(otherDate.toString())

        val otherZone = response()
        otherZone.getJSONObject("proposal").getJSONObject("proposal").getJSONObject("parameters")
            .getJSONArray("activities").getJSONObject(0).put("timezone", "UTC")
        assertInvalid(otherZone.toString())
    }

    @Test
    fun rejectsUnsupportedFocusAndTrainingReferencesExplicitly() {
        listOf("focus", "training_ref").forEach { field ->
            val value = response()
            value.getJSONObject("proposal").getJSONObject("proposal").getJSONObject("parameters")
                .getJSONArray("activities").getJSONObject(0).put(field, JSONObject())
            val error = assertThrows(PlanRevisionProposalParseException::class.java) {
                PlanRevisionProposalV3.parse(value.toString())
            }
            assertEquals("UNSUPPORTED_PROPOSAL_FIELD", error.code)
        }
    }

    @Test
    fun rejectsActivityCountAndTotalMinutesOverTheContract() {
        val tooMany = response(activities = JSONArray().also { array ->
            repeat(33) { index ->
                val id = "70000000-0000-4000-8000-${(index + 1).toString().padStart(12, '0')}"
                array.put(activity(clientRef = "ref-$index", activityId = id))
            }
        })
        assertInvalid(tooMany.toString())

        val overfull = response(activities = JSONArray()
            .put(activity(clientRef = "one", activityId = ACTIVITY_ID, duration = 800, start = 0))
            .put(activity(clientRef = "two", activityId = SECOND_ACTIVITY_ID, duration = 800, start = 0)))
        assertInvalid(overfull.toString())
    }

    @Test
    fun rejectsUnknownNestedFieldsAndNonCanonicalDate() {
        val unknown = response()
        unknown.getJSONObject("proposal").getJSONObject("proposal").getJSONObject("parameters")
            .getJSONArray("activities").getJSONObject(0).put("surprise", "value")
        assertInvalid(unknown.toString())

        val date = response()
        date.getJSONObject("proposal").getJSONObject("proposal").getJSONObject("parameters")
            .put("local_date", "2026-2-26")
        assertInvalid(date.toString())
    }

    private fun assertInvalid(raw: String) {
        assertThrows(PlanRevisionProposalParseException::class.java) {
            PlanRevisionProposalV3.parse(raw)
        }
    }

    private fun response(
        proposal: JSONObject? = proposalEnvelope(),
        activities: JSONArray = JSONArray().put(activity()),
    ): JSONObject {
        val candidate = proposal ?: return JSONObject()
            .put("schema_version", 3)
            .put("reply", "Réponse")
            .put("agent_status", "ok")
            .put("proposal", JSONObject.NULL)
            .put("overlaps", JSONArray())
        candidate.getJSONObject("proposal").getJSONObject("parameters").put("activities", activities)
        return JSONObject()
            .put("schema_version", 3)
            .put("reply", "Réponse")
            .put("agent_status", "ok")
            .put("proposal", candidate)
            .put("overlaps", JSONArray().put(JSONArray().put("ref-1").put("ref-2")))
    }

    private fun proposalEnvelope() = JSONObject()
        .put("schema_version", 3)
        .put("proposal_id", PROPOSAL_ID)
        .put("idempotency_key", "idempotency-1")
        .put("conversation_id", JSONObject.NULL)
        .put("mandate_id", "mandate-local")
        .put("mandate_version", 2)
        .put("created_at", "2026-10-26T10:00:00Z")
        .put("expires_at", "2026-10-27T10:00:00Z")
        .put("required_level", "confirm")
        .put("state", "proposed")
        .put("proposal", JSONObject()
            .put("action_type", "plan.revise_day")
            .put("reason", "Réorganiser le programme")
            .put("parameters", JSONObject()
                .put("plan_id", PLAN_ID)
                .put("base_version", 1)
                .put("local_date", PLAN_DATE)
                .put("timezone", "Europe/Paris")
                .put("summary", "Déplacer une activité")
                .put("activities", JSONArray())))

    private fun activity(
        clientRef: String = "ref-1",
        activityId: String? = ACTIVITY_ID,
        duration: Int = 50,
        start: Int = 540,
    ) = JSONObject()
        .put("client_ref", clientRef)
        .put("activity_id", activityId ?: JSONObject.NULL)
        .put("kind", "FOCUS")
        .put("title", "Préparer le rapport")
        .put("local_date", PLAN_DATE)
        .put("start_minute", start)
        .put("duration_minutes", duration)
        .put("timezone", "Europe/Paris")
        .put("priority", "IMPORTANT")
        .put("goal_id", GOAL_ID)
        .put("sub_goal_id", SUB_GOAL_ID)
        .put("training_ref", JSONObject.NULL)
        .put("focus", JSONObject.NULL)
        .put("notes", "Quelques notes")

    companion object {
        private const val PROPOSAL_ID = "60000000-0000-4000-8000-000000000001"
    }
}
