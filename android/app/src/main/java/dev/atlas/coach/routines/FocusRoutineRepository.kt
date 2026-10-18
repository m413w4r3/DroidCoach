package dev.atlas.coach.routines

import java.time.ZoneId
import java.time.ZonedDateTime

/** Synchronous repository contract; the host persists [exportMaps] with its existing local state. */
interface FocusRoutineRepository {
    fun list(): List<FocusRoutine>
    fun find(id: String): FocusRoutine?
    fun create(
        name: String,
        schedule: RoutineSchedule,
        focusMinutes: Int = 25,
        preauthorizedStart: Boolean = false,
    ): FocusRoutine
    fun update(routine: FocusRoutine): Boolean
    fun delete(id: String): Boolean
    fun setEnabled(id: String, enabled: Boolean): FocusRoutine?
    fun nextTrigger(id: String, after: ZonedDateTime, zone: ZoneId): ZonedDateTime?
    fun proposals(now: ZonedDateTime, zone: ZoneId = now.zone): List<RoutineEvaluator.Proposal>
    fun exportMaps(): List<Map<String, Any?>>
}

/** In-memory implementation for the local demo and pure JVM tests. */
class InMemoryFocusRoutineRepository(initial: Collection<FocusRoutine> = emptyList()) : FocusRoutineRepository {
    private val routines = linkedMapOf<String, FocusRoutine>().apply {
        initial.forEach { put(it.id, it) }
    }

    override fun list(): List<FocusRoutine> = routines.values.sortedBy { it.name.lowercase() }

    override fun find(id: String): FocusRoutine? = routines[id]

    override fun create(
        name: String,
        schedule: RoutineSchedule,
        focusMinutes: Int,
        preauthorizedStart: Boolean,
    ): FocusRoutine {
        val routine = FocusRoutine.create(name, schedule, focusMinutes, preauthorizedStart)
        require(routines.putIfAbsent(routine.id, routine) == null)
        return routine
    }

    override fun update(routine: FocusRoutine): Boolean {
        if (routine.id !in routines) return false
        routines[routine.id] = routine
        return true
    }

    override fun delete(id: String): Boolean = routines.remove(id) != null

    override fun setEnabled(id: String, enabled: Boolean): FocusRoutine? {
        val current = routines[id] ?: return null
        return current.copy(isEnabled = enabled).also { routines[id] = it }
    }

    override fun nextTrigger(id: String, after: ZonedDateTime, zone: ZoneId): ZonedDateTime? =
        routines[id]?.takeIf { it.isEnabled }?.let { RoutineTimeCalculator.nextTrigger(it.schedule, after, zone) }

    override fun proposals(now: ZonedDateTime, zone: ZoneId): List<RoutineEvaluator.Proposal> =
        RoutineEvaluator.proposals(list(), now, zone)

    override fun exportMaps(): List<Map<String, Any?>> = list().map(FocusRoutine::toMap)

    fun restoreMaps(values: List<Map<String, Any?>>) {
        val restored = values.map(FocusRoutine::fromMap).associateBy { it.id }
        routines.clear()
        routines.putAll(restored)
    }
}

interface RoutineProposalScheduler {
    fun schedule(routine: FocusRoutine, after: ZonedDateTime, zone: ZoneId)
    fun cancel(routineId: String)
}

class FakeRoutineProposalScheduler : RoutineProposalScheduler {
    private val triggers = linkedMapOf<String, ZonedDateTime>()
    val scheduledTriggers: Map<String, ZonedDateTime> get() = triggers.toMap()

    override fun schedule(routine: FocusRoutine, after: ZonedDateTime, zone: ZoneId) {
        cancel(routine.id)
        if (!routine.isEnabled) return
        RoutineTimeCalculator.nextTrigger(routine.schedule, after, zone)?.let { triggers[routine.id] = it }
    }

    override fun cancel(routineId: String) {
        triggers.remove(routineId)
    }
}

/** Delivery interface shared by Android notification code and a JVM fake. */
interface RoutineProposalNotifier {
    fun notify(proposal: RoutineEvaluator.Proposal): Boolean
}

class FakeRoutineProposalNotifier : RoutineProposalNotifier {
    private val posted = mutableListOf<RoutineEvaluator.Proposal>()
    val proposals: List<RoutineEvaluator.Proposal> get() = posted.toList()

    override fun notify(proposal: RoutineEvaluator.Proposal): Boolean {
        posted += proposal
        return true
    }
}
