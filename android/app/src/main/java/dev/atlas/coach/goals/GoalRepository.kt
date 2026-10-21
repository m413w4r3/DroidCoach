package dev.atlas.coach.goals

import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet

@ConsistentCopyVisibility
data class PreparedFocusLink internal constructor(
    val goalId: String,
    val focusSessionId: String,
    val linkedGoal: GoalRecord,
    val goalsSubdocument: Map<String, Any?>,
    internal val expectedRecords: List<GoalRecord>,
    internal val candidateRecords: List<GoalRecord>,
)

/**
 * Callback backed local repository. A future controller owns the outer LocalStateStore document
 * and injects how this versioned goals subdocument is read and written.
 */
class GoalRepository(
    private val readSubdocument: () -> Map<String, Any?>?,
    private val persistSubdocument: (Map<String, Any?>) -> Unit,
) {
    private var records: List<GoalRecord> = emptyList()

    init {
        val saved = readSubdocument()
        if (saved == null) {
            persistSubdocument(emptySubdocument())
        } else {
            records = immutableRecordList(decode(saved).values)
        }
    }

    @Synchronized
    fun all(): List<GoalRecord> = immutableList(records)

    @Synchronized
    fun get(id: String): GoalRecord? = records.firstOrNull { it.id == id }

    /** At most three active records are offered to the initial Coach surface. */
    @Synchronized
    fun activeGoalsForCoach(): List<GoalRecord> = records
        .filter { it.status == GoalStatus.ACTIVE }
        .sortedWith(compareBy<GoalRecord> { it.dueAt ?: Long.MAX_VALUE }.thenBy { it.createdAt }.thenBy { it.id })
        .take(MAX_ACTIVE_GOALS_FOR_COACH)

    @Synchronized
    fun add(goal: GoalRecord): GoalRecord {
        require(goal.status != GoalStatus.COMPLETED) { "A goal must be explicitly completed after creation" }
        require(records.none { it.id == goal.id }) { "A goal with this ID already exists" }
        commit(recordsById().apply { put(goal.id, goal) })
        return goal
    }

    /** Update content, links, or a non-completed lifecycle status; completion has dedicated paths. */
    @Synchronized
    fun update(goal: GoalRecord): GoalRecord {
        val previous = get(goal.id) ?: throw IllegalArgumentException("Unknown goal: ${goal.id}")
        require(previous.metric == goal.metric) { "A goal metric cannot change after creation" }
        require(goal.updatedAt >= previous.updatedAt) { "Goal updates cannot move backwards in time" }
        require(previous.status == GoalStatus.COMPLETED || goal.status != GoalStatus.COMPLETED) {
            "Use an explicit confirmation or calculated progress to complete a goal"
        }
        if (goal != previous) {
            commit(recordsById().apply { put(goal.id, goal) })
        }
        return goal
    }

    @Synchronized
    fun confirmManualCompletion(goalId: String, confirmedAtUtcMillis: Long): GoalRecord {
        val goal = get(goalId) ?: throw IllegalArgumentException("Unknown goal: $goalId")
        require(goal.metric == GoalMetric.MANUAL) { "Only manual goals use user confirmation" }
        require(goal.status != GoalStatus.ABANDONED) { "An abandoned goal cannot be completed" }
        require(confirmedAtUtcMillis >= goal.updatedAt) { "Confirmation time cannot precede the latest goal update" }
        if (goal.status == GoalStatus.COMPLETED) return goal
        return goal.copy(status = GoalStatus.COMPLETED, updatedAt = confirmedAtUtcMillis).also {
            commit(recordsById().apply { put(goalId, it) })
        }
    }

    @Synchronized
    fun completeFromProgress(goalId: String, progress: GoalProgress, completedAtUtcMillis: Long): GoalRecord {
        val goal = get(goalId) ?: throw IllegalArgumentException("Unknown goal: $goalId")
        require(goal.metric != GoalMetric.MANUAL) { "Manual goals require explicit user confirmation" }
        require(progress.goalId == goal.id && progress.metric == goal.metric && progress.targetValue == goal.targetValue) {
            "Progress does not belong to this goal"
        }
        require(progress.isComplete) { "Goal progress has not reached its target" }
        require(goal.status != GoalStatus.ABANDONED) { "An abandoned goal cannot be completed" }
        require(completedAtUtcMillis >= goal.updatedAt) { "Completion time cannot precede the latest goal update" }
        if (goal.status == GoalStatus.COMPLETED) return goal
        return goal.copy(status = GoalStatus.COMPLETED, updatedAt = completedAtUtcMillis).also {
            commit(recordsById().apply { put(goalId, it) })
        }
    }

    @Synchronized
    fun remove(goalId: String): Boolean {
        if (records.none { it.id == goalId }) return false
        commit(recordsById().apply { remove(goalId) })
        return true
    }

    /**
     * Creates a Goals candidate for the outer Focus transaction without persisting or publishing it.
     * Integrators compare [PreparedFocusLink.expectedRecords] with [all] before writing the root.
     */
    @Synchronized
    fun prepareFocusLink(
        goalId: String,
        focusSessionId: String,
        linkedAtUtcMillis: Long,
    ): PreparedFocusLink {
        require(goalId.isNotBlank()) { "Goal ID must not be blank" }
        require(focusSessionId.isNotBlank()) { "Focus session ID must not be blank" }
        val goal = get(goalId) ?: throw IllegalArgumentException("Unknown goal: $goalId")
        require(goal.status == GoalStatus.ACTIVE) { "Only active goals can be linked to a new Focus session" }
        require(goal.metric == GoalMetric.FOCUS_MINUTES) { "Only Focus-minute goals can be linked to a new Focus session" }
        require(records.none { other ->
            other.id != goalId && other.status == GoalStatus.ACTIVE && focusSessionId in other.linkedFocusSessionIds
        }) { "Focus session is already linked to another active goal" }

        val candidateGoal = if (focusSessionId in goal.linkedFocusSessionIds) {
            goal
        } else {
            goal.copy(
                linkedFocusSessionIds = goal.linkedFocusSessionIds + focusSessionId,
                updatedAt = maxOf(goal.updatedAt, linkedAtUtcMillis),
            )
        }
        val expectedRecords = immutableRecordList(records)
        val candidateRecords = immutableRecordList(records.map { current ->
            if (current.id == goalId) candidateGoal else current
        })
        return PreparedFocusLink(
            goalId = goalId,
            focusSessionId = focusSessionId,
            linkedGoal = candidateRecords.first { it.id == goalId },
            goalsSubdocument = encode(candidateRecords),
            expectedRecords = expectedRecords,
            candidateRecords = candidateRecords,
        )
    }

    /** Installs the already-committed immutable candidate using a single cache assignment. */
    @Synchronized
    fun publishCommittedFocusLink(prepared: PreparedFocusLink): GoalRecord {
        records = prepared.candidateRecords
        return prepared.linkedGoal
    }

    /** Publish the cache only after the owner confirms the outer document was stored. */
    private fun commit(nextRecords: LinkedHashMap<String, GoalRecord>) {
        val candidate = immutableRecordList(nextRecords.values)
        val subdocument = encode(candidate)
        persistSubdocument(subdocument)
        records = candidate
    }

    private fun emptySubdocument(): Map<String, Any?> = encode(emptyList())

    private fun encode(goals: List<GoalRecord>): Map<String, Any?> {
        val encodedGoals = goals.map { goal ->
            val fields = LinkedHashMap(goal.toMap())
            fields["linkedFocusSessionIds"] = immutableList(goal.linkedFocusSessionIds.sorted())
            fields["linkedHabitIds"] = immutableList(goal.linkedHabitIds.sorted())
            Collections.unmodifiableMap(fields)
        }
        return Collections.unmodifiableMap(
            linkedMapOf(
                "schema_version" to GOALS_SCHEMA_VERSION,
                "goals" to immutableList(encodedGoals),
            ),
        )
    }

    private fun recordsById(): LinkedHashMap<String, GoalRecord> =
        LinkedHashMap<String, GoalRecord>(records.size).apply { records.forEach { put(it.id, it) } }

    private fun immutableRecordList(source: Iterable<GoalRecord>): List<GoalRecord> =
        immutableList(source.map(::immutableGoal))

    private fun immutableGoal(goal: GoalRecord): GoalRecord = goal.copy(
        linkedFocusSessionIds = Collections.unmodifiableSet(LinkedHashSet(goal.linkedFocusSessionIds)),
        linkedHabitIds = Collections.unmodifiableSet(LinkedHashSet(goal.linkedHabitIds)),
    )

    private fun <T> immutableList(values: List<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))

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
