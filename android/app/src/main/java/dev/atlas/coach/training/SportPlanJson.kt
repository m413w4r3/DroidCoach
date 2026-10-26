package dev.atlas.coach.training

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime

sealed interface SportPlanParseResult {
    data class Valid(val plan: SportPlan) : SportPlanParseResult
    data class Invalid(val reason: String) : SportPlanParseResult
}

/** Strict, size-bounded JSON parser for sport-plan-v1. */
object SportPlanJson {
    private const val MAX_JSON_LENGTH = 131_072
    private const val MAX_EXERCISES = 50
    private const val MAX_SETS = 10
    private const val MAX_ID_LENGTH = 128
    private const val MAX_NAME_LENGTH = 120
    private const val MAX_TIMESTAMP_LENGTH = 40

    fun parse(json: String): SportPlanParseResult = try {
        if (json.length > MAX_JSON_LENGTH) invalid("json_too_large")
        val root = JSONObject(json)
        root.requireKeys(
            required = setOf(
                "schema_version", "local_date", "timezone", "source", "sync_state", "routine_id",
                "day_id", "day_name", "planned", "exercises", "performed", "generated_at", "limits",
            ),
        )
        if (root.requiredInt("schema_version") != 1) invalid("unsupported_schema_version")

        val exercises = root.requiredArray("exercises")
        if (exercises.length() > MAX_EXERCISES) invalid("too_many_exercises")
        val performed = root.requiredArray("performed")
        if (performed.length() > MAX_EXERCISES) invalid("too_many_results")
        val limits = root.requiredObject("limits").also {
            it.requireKeys(required = setOf("max_exercises", "truncated"))
            if (it.requiredInt("max_exercises") != MAX_EXERCISES) invalid("invalid_limits")
        }

        SportPlan(
            schemaVersion = 1,
            localDate = root.nullableString("local_date", 10)?.also(::requireDate),
            timezone = root.nullableString("timezone", 100),
            source = root.requiredEnum<SportPlanSource>("source"),
            syncState = root.requiredEnum<SyncState>("sync_state"),
            routineId = root.nullableString("routine_id", MAX_ID_LENGTH),
            dayId = root.nullableString("day_id", MAX_ID_LENGTH),
            dayName = root.nullableString("day_name", MAX_NAME_LENGTH),
            planned = root.requiredBoolean("planned"),
            exercises = exercises.objects().map(::parseExercise),
            performed = performed.objects().map(::parsePerformedWorkout),
            generatedAt = root.nullableString("generated_at", MAX_TIMESTAMP_LENGTH)?.also(::requireTimestamp),
            limits = SportPlanLimits(
                maxExercises = MAX_EXERCISES,
                truncated = limits.requiredBoolean("truncated"),
            ),
        ).let { SportPlanParseResult.Valid(it) }
    } catch (failure: SportPlanFailure) {
        SportPlanParseResult.Invalid(failure.reason)
    } catch (_: Exception) {
        SportPlanParseResult.Invalid("invalid_json")
    }

    private fun parseExercise(value: JSONObject): PlannedExercise {
        value.requireKeys(
            required = setOf("exercise_id", "sets", "reps"),
            optional = setOf("name", "weight_kg", "rest_seconds", "last_performance"),
        )
        val last = value.optionalObject("last_performance")?.let(::parseLastPerformance)
        return PlannedExercise(
            exerciseId = value.requiredString("exercise_id", MAX_ID_LENGTH),
            name = value.optionalString("name", MAX_NAME_LENGTH),
            sets = value.requiredInt("sets").also { if (it !in 1..MAX_SETS) invalid("invalid_sets") },
            reps = value.requiredInt("reps").also { if (it !in 0..1_000) invalid("invalid_reps") },
            weightKg = value.optionalNumber("weight_kg", 0.0, 1_000.0),
            restSeconds = value.optionalInt("rest_seconds", 0, 86_400),
            lastPerformance = last,
        )
    }

    private fun parseLastPerformance(value: JSONObject): LastPerformance {
        value.requireKeys(required = setOf("performed_at", "sets"))
        val performedAt = value.requiredString("performed_at", MAX_TIMESTAMP_LENGTH).also(::requireTimestamp)
        val sets = value.requiredArray("sets")
        if (sets.length() !in 1..MAX_SETS) invalid("invalid_performance_sets")
        return LastPerformance(performedAt, sets.objects().map { set ->
            set.requireKeys(required = setOf("reps"), optional = setOf("weight_kg"))
            SportPlanSet(
                reps = set.requiredInt("reps").also { if (it !in 0..1_000) invalid("invalid_reps") },
                weightKg = set.optionalNumber("weight_kg", 0.0, 1_000.0),
            )
        })
    }

    private fun parsePerformedWorkout(value: JSONObject): PerformedWorkout {
        value.requireKeys(
            required = setOf("result_id", "performed_at", "duration_minutes", "source", "verification"),
        )
        val performedAt = value.requiredString("performed_at", MAX_TIMESTAMP_LENGTH).also(::requireTimestamp)
        return PerformedWorkout(
            resultId = value.requiredString("result_id", MAX_ID_LENGTH),
            performedAt = performedAt,
            durationMinutes = value.nullableInt("duration_minutes", 0, 1_440),
            source = value.requiredEnum("source"),
            verification = value.requiredEnum("verification"),
        )
    }

    private fun requireDate(value: String) {
        try {
            if (LocalDate.parse(value).toString() != value) invalid("invalid_local_date")
        } catch (_: Exception) {
            invalid("invalid_local_date")
        }
    }

    private fun requireTimestamp(value: String) {
        try {
            OffsetDateTime.parse(value)
        } catch (_: Exception) {
            invalid("invalid_timestamp")
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { index ->
        optJSONObject(index) ?: invalid("invalid_array_item")
    }

    private fun JSONObject.requireKeys(required: Set<String>, optional: Set<String> = emptySet()) {
        val present = keys().asSequence().toSet()
        if (!present.containsAll(required) || present.any { it !in required && it !in optional }) {
            invalid("invalid_fields")
        }
    }

    private fun JSONObject.requiredObject(key: String): JSONObject =
        optJSONObject(key) ?: invalid("invalid_type")

    private fun JSONObject.optionalObject(key: String): JSONObject? {
        if (!has(key)) return null
        return when (val value = opt(key)) {
        JSONObject.NULL -> null
        is JSONObject -> value
        else -> invalid("invalid_type")
        }
    }

    private fun JSONObject.requiredArray(key: String): JSONArray =
        optJSONArray(key) ?: invalid("invalid_type")

    private fun JSONObject.requiredString(key: String, maxLength: Int): String =
        strictString(opt(key), maxLength) ?: invalid("invalid_string")

    private fun JSONObject.optionalString(key: String, maxLength: Int): String? {
        if (!has(key)) return null
        return when (val value = opt(key)) {
            JSONObject.NULL -> null
            else -> strictString(value, maxLength) ?: invalid("invalid_string")
        }
    }

    private fun JSONObject.nullableString(key: String, maxLength: Int): String? =
        optionalString(key, maxLength)

    private fun strictString(value: Any?, maxLength: Int): String? =
        (value as? String)?.takeIf { it.length <= maxLength && (maxLength > MAX_ID_LENGTH || it.isNotEmpty()) }

    private fun JSONObject.requiredInt(key: String): Int = strictInt(opt(key)) ?: invalid("invalid_integer")

    private fun JSONObject.optionalInt(key: String, minimum: Int, maximum: Int): Int? =
        nullableInt(key, minimum, maximum)

    private fun JSONObject.nullableInt(key: String, minimum: Int, maximum: Int): Int? {
        if (!has(key)) return null
        return when (val value = opt(key)) {
            JSONObject.NULL -> null
            else -> strictInt(value)?.takeIf { it in minimum..maximum } ?: invalid("invalid_integer")
        }
    }

    private fun strictInt(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        else -> null
    }

    private fun JSONObject.optionalNumber(key: String, minimum: Double, maximum: Double): Double? {
        if (!has(key)) return null
        return when (val value = opt(key)) {
            JSONObject.NULL -> null
            is Number -> value.toDouble().takeIf { it.isFinite() && it in minimum..maximum }
                ?: invalid("invalid_number")
            else -> invalid("invalid_number")
        }
    }

    private fun JSONObject.requiredBoolean(key: String): Boolean = when (val value = opt(key)) {
        is Boolean -> value
        else -> invalid("invalid_boolean")
    }

    private inline fun <reified T : Enum<T>> JSONObject.requiredEnum(key: String): T {
        val value = requiredString(key, 32)
        return enumValues<T>().firstOrNull { it.name == value } ?: invalid("invalid_enum")
    }

    private class SportPlanFailure(val reason: String) : RuntimeException()

    private fun invalid(reason: String): Nothing = throw SportPlanFailure(reason.take(200))
}
