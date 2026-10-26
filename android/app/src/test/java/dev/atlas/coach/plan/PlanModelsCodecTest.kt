package dev.atlas.coach.plan

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanModelsCodecTest {
    @Test
    fun completeDayPlanRoundTripsWithSchemaVersionThree() {
        val original = dayPlan(
            activities = listOf(activity().copy(actualMinutes = 40)),
            state = PlanState.APPROVED,
            approvedAt = NOW - 1_000L,
        )

        val json = PlanJsonCodec.encodeDayPlan(original)

        assertEquals(3, JSONObject(json).getInt("schema_version"))
        assertEquals(original, PlanJsonCodec.decodeDayPlan(json))
    }

    @Test
    fun trainingReferenceRoundTripsAsAnOptionalActivityField() {
        val reference = trainingReference()
        val original = dayPlan(activities = listOf(activity().copy(kind = ActivityKind.TRAINING, trainingRef = reference)))

        val restored = PlanJsonCodec.decodeDayPlan(PlanJsonCodec.encodeDayPlan(original))

        assertEquals(original, restored)
        assertEquals(reference, restored.activities.single().trainingRef)
    }

    @Test
    fun legacyActivityWithoutTrainingReferenceStillLoads() {
        val root = JSONObject(PlanJsonCodec.encodeDayPlan(dayPlan()))
        val activityJson = root.getJSONArray("activities").getJSONObject(0)
        activityJson.remove("training_ref")

        val restored = PlanJsonCodec.decodeDayPlan(root.toString())
        activityJson.put("training_ref", JSONObject.NULL)
        val explicitNull = PlanJsonCodec.decodeDayPlan(root.toString())

        assertEquals(null, restored.activities.single().trainingRef)
        assertEquals(null, explicitNull.activities.single().trainingRef)
    }

    @Test
    fun allActivityStatusesRemainDistinctThroughRoundTrip() {
        val activities = ActivityStatus.entries.mapIndexed { index, status ->
            activity(
                id = "20000000-0000-4000-8000-${(index + 10).toString().padStart(12, '0')}",
                status = status,
                scheduled = schedule(startMinute = 8 * 60 + index * 60),
            )
        }

        val restored = PlanJsonCodec.decodeDayPlan(PlanJsonCodec.encodeDayPlan(dayPlan(activities = activities)))

        assertEquals(ActivityStatus.entries.toList(), restored.activities.map { it.status })
        assertEquals(ActivityStatus.entries.size, restored.activities.map { it.status }.toSet().size)
    }

    @Test
    fun everyPlanChangeVariantRoundTrips() {
        val originalActivity = activity()
        val movedActivity = activity(
            id = SECOND_ACTIVITY_ID,
            scheduled = schedule(startMinute = 11 * 60),
        )
        val editedActivity = activity(
            id = THIRD_ACTIVITY_ID,
            title = "Relire le rapport",
            scheduled = schedule(startMinute = 13 * 60),
        )
        val beforeEdited = editedActivity.copy(title = "Écrire le rapport")
        val added = activity(
            id = "20000000-0000-4000-8000-000000000004",
            scheduled = schedule(startMinute = 15 * 60),
        )
        val removed = activity(
            id = "20000000-0000-4000-8000-000000000005",
            scheduled = schedule(startMinute = 16 * 60),
        )
        val original = PlanRevision(
            revisionId = REVISION_ID,
            planId = PLAN_ID,
            baseVersion = 1,
            newVersion = 2,
            origin = RevisionOrigin.RECONCILIATION,
            changes = listOf(
                PlanChange.SlotAdded(added),
                PlanChange.SlotRemoved(removed.activityId, removed),
                PlanChange.SlotMoved(movedActivity.activityId, schedule(), movedActivity.scheduledLocal),
                PlanChange.SlotEdited(editedActivity.activityId, beforeEdited, editedActivity),
                PlanChange.StatusChanged(originalActivity.activityId, ActivityStatus.PLANNED, ActivityStatus.DONE),
            ),
            summary = "Réconcilier les créneaux",
            createdAt = NOW - 2_000L,
            expiresAt = NOW + 2_000L,
            status = RevisionStatus.PROPOSED,
        )

        assertEquals(original, PlanJsonCodec.decodeRevision(PlanJsonCodec.encodeRevision(original)))
    }

    @Test
    fun confirmationRoundTripsIncludingConsumptionTimestamp() {
        val original = confirmationFor(
            revision = revisionFor(dayPlan(), listOf(activity(scheduled = schedule(10 * 60)))),
        ).copy(consumedAt = NOW + 1_000L)

        assertEquals(original, PlanJsonCodec.decodeConfirmation(PlanJsonCodec.encodeConfirmation(original)))
    }

    @Test
    fun unknownTopLevelJsonFieldIsRejected() {
        val json = JSONObject(PlanJsonCodec.encodeDayPlan(dayPlan())).put("surprise", true).toString()

        assertThrows(IllegalArgumentException::class.java) { PlanJsonCodec.decodeDayPlan(json) }
    }

    @Test
    fun unknownNestedActivityFieldIsRejected() {
        val root = JSONObject(PlanJsonCodec.encodeDayPlan(dayPlan()))
        root.getJSONArray("activities").getJSONObject(0).put("unmodeled", "value")

        assertThrows(IllegalArgumentException::class.java) { PlanJsonCodec.decodeDayPlan(root.toString()) }
    }

    @Test
    fun stringInsteadOfIntegerIsRejectedWithoutCoercion() {
        val root = JSONObject(PlanJsonCodec.encodeDayPlan(dayPlan())).put("version", "1")

        assertThrows(IllegalArgumentException::class.java) { PlanJsonCodec.decodeDayPlan(root.toString()) }
    }

    @Test
    fun decimalInsteadOfIntegerIsRejected() {
        val root = JSONObject(PlanJsonCodec.encodeDayPlan(dayPlan()))
        root.getJSONArray("activities").getJSONObject(0).getJSONObject("scheduled_local")
            .put("start_minute", 540.5)

        assertThrows(IllegalArgumentException::class.java) { PlanJsonCodec.decodeDayPlan(root.toString()) }
    }

    @Test
    fun nonCanonicalUuidIsRejected() {
        val root = JSONObject(PlanJsonCodec.encodeDayPlan(dayPlan())).put("plan_id", "1-1-1-1-1")

        assertThrows(IllegalArgumentException::class.java) { PlanJsonCodec.decodeDayPlan(root.toString()) }
    }

    @Test
    fun unknownSchemaVersionIsRejected() {
        val root = JSONObject(PlanJsonCodec.encodeDayPlan(dayPlan())).put("schema_version", 4)

        assertThrows(IllegalArgumentException::class.java) { PlanJsonCodec.decodeDayPlan(root.toString()) }
    }

    @Test
    fun invalidScheduleBoundsAndTimezoneAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { schedule(startMinute = 1440) }
        assertThrows(IllegalArgumentException::class.java) { schedule(durationMinutes = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            LocalSchedule(PLAN_DATE, 20, 30, "Mars/Olympus")
        }
    }

    @Test
    fun localDateMustBeCanonicalIsoDate() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalSchedule("2026-2-3", 20, 30, "Europe/Paris")
        }
    }

    @Test
    fun schemaVersionIsThreeForRevisionAndConfirmationDocuments() {
        val revision = revisionFor(dayPlan(), listOf(activity(scheduled = schedule(10 * 60))))
        val confirmation = confirmationFor(revision)

        assertEquals(3, JSONObject(PlanJsonCodec.encodeRevision(revision)).getInt("schema_version"))
        assertEquals(3, JSONObject(PlanJsonCodec.encodeConfirmation(confirmation)).getInt("schema_version"))
    }

    @Test
    fun distinctPlanStatusesAndRevisionStatusesAreNotAliases() {
        assertEquals(4, PlanState.entries.toSet().size)
        assertEquals(6, RevisionStatus.entries.toSet().size)
        assertNotEquals(ActivityStatus.DONE, ActivityStatus.MISSED)
        assertNotEquals(ActivityStatus.CANCELLED, ActivityStatus.POSTPONED)
        assertTrue(PlanJsonCodec.encodeDayPlan(dayPlan()).contains("\"schema_version\":3"))
    }
}
