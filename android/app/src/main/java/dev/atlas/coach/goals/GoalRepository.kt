package dev.atlas.coach.goals

/**
 * Callback backed local repository. A future controller owns the outer LocalStateStore document
 * and injects how this versioned goals subdocument is read and written.
 */
class GoalRepository(
    private val readSubdocument: () -> Map<String, Any?>?,
    private val persistSubdocument: (Map<String, Any?>) -> Unit,
) {
    private val records = linkedMapOf<String, GoalRecord>()

    init {
        val saved = readSubdocument()
        if (saved == null) {
            persistSubdocument(emptySubdocument())
        } else {
            records.putAll(decode(saved))
        }
    }

    @Synchronized
    fun all(): List<GoalRecord> = records.values.toList()

    @Synchronized
    fun get(id: String): GoalRecord? = records[id]

    /** At most three active records are offered to the initial Coach surface. */
    @Synchronized
    fun activeGoalsForCoach(): List<GoalRecord> = records.values
        .filter { it.status == GoalStatus.ACTIVE }
        .sortedWith(compareBy<GoalRecord> { it.dueAt ?: Long.MAX_VALUE }.thenBy { it.createdAt }.thenBy { it.id })
        .take(MAX_ACTIVE_GOALS_FOR_COACH)

    @Synchronized
    fun add(goal: GoalRecord): GoalRecord {
        require(goal.status != GoalStatus.COMPLETED) { "A goal must be explicitly completed after creation" }
        require(goal.id !in records) { "A goal with this ID already exists" }
        records[goal.id] = goal
        persistCurrent()
        return goal
    }

    /** Update content, links, or a non-completed lifecycle status; completion has dedicated paths. */
    @Synchronized
    fun update(goal: GoalRecord): GoalRecord {
        val previous = records[goal.id] ?: throw IllegalArgumentException("Unknown goal: ${goal.id}")
        require(previous.metric == goal.metric) { "A goal metric cannot change after creation" }
        require(goal.updatedAt >= previous.updatedAt) { "Goal updates cannot move backwards in time" }
        require(previous.status == GoalStatus.COMPLETED || goal.status != GoalStatus.COMPLETED) {
            "Use an explicit confirmation or calculated progress to complete a goal"
        }
        if (goal != previous) {
            records[goal.id] = goal
            persistCurrent()
        }
        return goal
    }

    @Synchronized
    fun confirmManualCompletion(goalId: String, confirmedAtUtcMillis: Long): GoalRecord {
        val goal = records[goalId] ?: throw IllegalArgumentException("Unknown goal: $goalId")
        require(goal.metric == GoalMetric.MANUAL) { "Only manual goals use user confirmation" }
        require(goal.status != GoalStatus.ABANDONED) { "An abandoned goal cannot be completed" }
        require(confirmedAtUtcMillis >= goal.updatedAt) { "Confirmation time cannot precede the latest goal update" }
        if (goal.status == GoalStatus.COMPLETED) return goal
        return goal.copy(status = GoalStatus.COMPLETED, updatedAt = confirmedAtUtcMillis).also {
            records[goalId] = it
            persistCurrent()
        }
    }

    @Synchronized
    fun completeFromProgress(goalId: String, progress: GoalProgress, completedAtUtcMillis: Long): GoalRecord {
        val goal = records[goalId] ?: throw IllegalArgumentException("Unknown goal: $goalId")
        require(goal.metric != GoalMetric.MANUAL) { "Manual goals require explicit user confirmation" }
        require(progress.goalId == goal.id && progress.metric == goal.metric && progress.targetValue == goal.targetValue) {
            "Progress does not belong to this goal"
        }
        require(progress.isComplete) { "Goal progress has not reached its target" }
        require(goal.status != GoalStatus.ABANDONED) { "An abandoned goal cannot be completed" }
        require(completedAtUtcMillis >= goal.updatedAt) { "Completion time cannot precede the latest goal update" }
        if (goal.status == GoalStatus.COMPLETED) return goal
        return goal.copy(status = GoalStatus.COMPLETED, updatedAt = completedAtUtcMillis).also {
            records[goalId] = it
            persistCurrent()
        }
    }

    @Synchronized
    fun remove(goalId: String): Boolean {
        if (records.remove(goalId) == null) return false
        persistCurrent()
        return true
    }

    private fun persistCurrent() = persistSubdocument(encode(records.values.toList()))

    private fun emptySubdocument(): Map<String, Any?> = mapOf(
        "schema_version" to GOALS_SCHEMA_VERSION,
        "goals" to emptyList<Map<String, Any?>>(),
    )

    private fun encode(goals: List<GoalRecord>): Map<String, Any?> = mapOf(
        "schema_version" to GOALS_SCHEMA_VERSION,
        "goals" to goals.map(GoalRecord::toMap),
    )

    private fun decode(document: Map<String, Any?>): Map<String, GoalRecord> {
        val version = (document["schema_version"] as? Number)?.toInt()
            ?: throw IllegalArgumentException("Goals subdocument is missing schema_version")
        require(version == GOALS_SCHEMA_VERSION) { "Unsupported goals subdocument version: $version" }
        val values = document["goals"] as? List<*>
            ?: throw IllegalArgumentException("Goals subdocument is missing goals")
        val parsed = linkedMapOf<String, GoalRecord>()
        values.forEach { value ->
            val fields = value as? Map<*, *> ?: throw IllegalArgumentException("Invalid goal record")
            val map = fields.entries.associate { (key, item) ->
                (key as? String ?: throw IllegalArgumentException("Invalid goal record key")) to item
            }
            val goal = GoalRecord.fromMap(map)
            require(parsed.put(goal.id, goal) == null) { "Duplicate goal ID: ${goal.id}" }
        }
        return parsed
    }

    companion object {
        const val GOALS_SCHEMA_VERSION = 1
        const val MAX_ACTIVE_GOALS_FOR_COACH = 3
    }
}
