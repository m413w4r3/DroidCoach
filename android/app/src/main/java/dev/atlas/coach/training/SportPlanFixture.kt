package dev.atlas.coach.training

/** Synthetic contract fixture: a prescription exists, with no performed result. */
object SportPlanFixture {
    val json: String = """
        {
          "schema_version": 1,
          "local_date": "2026-10-26",
          "timezone": "Europe/Paris",
          "source": "FIXTURE",
          "sync_state": "FRESH",
          "routine_id": "12",
          "day_id": "34",
          "day_name": "Séance A",
          "planned": true,
          "exercises": [
            {
              "exercise_id": "56",
              "name": "Squat",
              "sets": 3,
              "reps": 8,
              "weight_kg": 40.0,
              "rest_seconds": 90
            }
          ],
          "performed": [],
          "generated_at": "2026-10-26T15:00:00Z",
          "limits": { "max_exercises": 50, "truncated": false }
        }
    """.trimIndent()
}
