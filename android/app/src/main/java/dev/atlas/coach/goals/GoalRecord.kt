package dev.atlas.coach.goals

enum class GoalMetric(val wireValue: String) {
    FOCUS_MINUTES("focus_minutes"),
    HABIT_CHECKINS("habit_checkins"),
    MANUAL("manual");

    companion object {
        fun fromWireValue(value: String): GoalMetric = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unknown goal metric: $value")
    }
}

enum class GoalStatus(val wireValue: String) {
    ACTIVE("active"),
    COMPLETED("completed"),
    PAUSED("paused"),
    ABANDONED("abandoned");

    companion object {
        fun fromWireValue(value: String): GoalStatus = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unknown goal status: $value")
    }
}

/** A local goal. Links are explicit IDs; no relationship is inferred from titles or timestamps. */
data class GoalRecord(
    val id: String,
    val title: String,
    val description: String,
    val metric: GoalMetric,
    val targetValue: Long,
    val dueAt: Long? = null,
    val status: GoalStatus = GoalStatus.ACTIVE,
    val nextAction: String? = null,
    val linkedFocusSessionIds: Set<String> = emptySet(),
    val linkedHabitIds: Set<String> = emptySet(),
    val createdAt: Long,
    val updatedAt: Long,
) {
    init {
        require(id.isNotBlank() && id.length <= MAX_ID_LENGTH) { "Goal ID must be non-blank and at most $MAX_ID_LENGTH characters" }
        require(title.isNotBlank() && title.length <= MAX_TITLE_LENGTH) { "Goal title must be non-blank and at most $MAX_TITLE_LENGTH characters" }
        require(description.length <= MAX_DESCRIPTION_LENGTH) { "Goal description is too long" }
        require(targetValue > 0L) { "Goal target must be positive" }
        require(metric != GoalMetric.MANUAL || targetValue == 1L) { "Manual goals use a single explicit confirmation" }
        require(dueAt == null || dueAt >= 0L) { "Goal dueAt must be non-negative" }
        require(createdAt >= 0L && updatedAt >= createdAt) { "Goal timestamps are invalid" }
        require(nextAction == null || nextAction.length <= MAX_NEXT_ACTION_LENGTH) { "Goal nextAction is too long" }
        require(linkedFocusSessionIds.none(String::isBlank) && linkedHabitIds.none(String::isBlank)) {
            "Linked IDs must not be blank"
        }
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "title" to title,
        "description" to description,
        "metric" to metric.wireValue,
        "targetValue" to targetValue,
        "dueAt" to dueAt,
        "status" to status.wireValue,
        "nextAction" to nextAction,
        "linkedFocusSessionIds" to linkedFocusSessionIds.sorted(),
        "linkedHabitIds" to linkedHabitIds.sorted(),
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
    )

    companion object {
        const val MAX_ID_LENGTH = 120
        const val MAX_TITLE_LENGTH = 120
        const val MAX_DESCRIPTION_LENGTH = 1_000
        const val MAX_NEXT_ACTION_LENGTH = 200

        fun fromMap(values: Map<String, Any?>): GoalRecord = GoalRecord(
            id = values.requiredString("id"),
            title = values.requiredString("title"),
            description = values.requiredString("description"),
            metric = GoalMetric.fromWireValue(values.requiredString("metric")),
            targetValue = values.requiredLong("targetValue"),
            dueAt = values.optionalLong("dueAt"),
            status = GoalStatus.fromWireValue(values.requiredString("status")),
            nextAction = values.optionalString("nextAction"),
            linkedFocusSessionIds = values.stringSet("linkedFocusSessionIds"),
            linkedHabitIds = values.stringSet("linkedHabitIds"),
            createdAt = values.requiredLong("createdAt"),
            updatedAt = values.requiredLong("updatedAt"),
        )
    }
}

private fun Map<String, Any?>.requiredString(key: String): String =
    this[key] as? String ?: throw IllegalArgumentException("Missing or invalid goal field: $key")

private fun Map<String, Any?>.optionalString(key: String): String? = when (val value = this[key]) {
    null -> null
    is String -> value
    else -> throw IllegalArgumentException("Invalid goal field: $key")
}

private fun Map<String, Any?>.requiredLong(key: String): Long = optionalLong(key)
    ?: throw IllegalArgumentException("Missing or invalid goal field: $key")

private fun Map<String, Any?>.optionalLong(key: String): Long? = when (val value = this[key]) {
    null -> null
    is Byte -> value.toLong()
    is Short -> value.toLong()
    is Int -> value.toLong()
    is Long -> value
    is Number -> value.toDouble().takeIf { it.isFinite() && it % 1.0 == 0.0 && it >= Long.MIN_VALUE && it <= Long.MAX_VALUE }
        ?.toLong() ?: throw IllegalArgumentException("Invalid goal field: $key")
    else -> throw IllegalArgumentException("Invalid goal field: $key")
}

private fun Map<String, Any?>.stringSet(key: String): Set<String> {
    val value = this[key] ?: return emptySet()
    val items = value as? List<*> ?: throw IllegalArgumentException("Invalid goal field: $key")
    val strings = items.map { it as? String ?: throw IllegalArgumentException("Invalid goal field: $key") }
    require(strings.none(String::isBlank)) { "Linked IDs must not be blank" }
    return strings.toSet()
}
