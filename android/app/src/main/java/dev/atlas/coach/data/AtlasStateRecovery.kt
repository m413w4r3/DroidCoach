package dev.atlas.coach.data

import dev.atlas.coach.coach.CoachJournalEntry
import dev.atlas.coach.coach.CoachRepositoryState
import dev.atlas.coach.coach.Directive
import dev.atlas.coach.coach.DirectiveResult
import dev.atlas.coach.coach.MockCoachRepository
import dev.atlas.coach.content.ContentCatalog
import dev.atlas.coach.focus.Clock
import dev.atlas.coach.focus.FocusEngine
import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.habits.HabitLinkPolicy
import dev.atlas.coach.habits.InMemoryHabitRepository
import dev.atlas.coach.rules.AppOpenEvent
import dev.atlas.coach.routines.FocusRoutine

/** Validates each persisted section independently before any live repository is mutated. */
data class RecoveredAtlasState(
    val values: Map<String, Any?>,
    val invalidSubstates: Set<String> = emptySet(),
)

object AtlasStateRecovery {
    fun recover(values: Map<String, Any?>): RecoveredAtlasState {
        val safe = values.toMutableMap()
        val invalid = linkedSetOf<String>()

        recoverSection(safe, invalid, "policy") { raw -> localPolicyFromStorage(raw.asStringMap()) }
        recoverSection(safe, invalid, "rules") { raw -> ruleEvaluatorStateFromStorage(raw.asStringMap()) }
        recoverSection(safe, invalid, "focus") { raw ->
            val session = FocusSession.fromMap(raw.asStringMap())
            val validationEngine = FocusEngine(object : Clock {
                override fun nowMillis() = session.updatedAtUtcMillis
                override fun monotonicMillis() = 0L
            })
            validationEngine.restore(session)
        }
        recoverSection(safe, invalid, "focusHistory", emptyList<Map<String, Any?>>()) { raw ->
            val sessions = (raw as? List<*>)?.map { entry ->
                val values = entry.asStringMap()
                val session = FocusSession.fromMap(values)
                require(session.status in setOf(dev.atlas.coach.focus.FocusStatus.FINISHED, dev.atlas.coach.focus.FocusStatus.CANCELLED))
                values
            } ?: throw IllegalArgumentException("focusHistory must be a list")
            sessions.take(100)
        }
        recoverSection(safe, invalid, "focusRoutines", emptyList<Map<String, Any?>>()) { raw ->
            val routines = (raw as? List<*>)?.map { entry ->
                val values = entry.asStringMap()
                FocusRoutine.fromMap(values)
                values
            } ?: throw IllegalArgumentException("focusRoutines must be a list")
            routines.take(100)
        }

        val habits = recoverSection(safe, invalid, "habits", emptyList<Map<String, Any?>>()) { raw ->
            val records = raw as? List<*> ?: throw IllegalArgumentException("habits must be a list")
            val mapped = records.map { it.asStringMap() }
            val validationRepository = InMemoryHabitRepository()
            validationRepository.restoreMaps(mapped)
            mapped
        }
        recoverSection(safe, invalid, "habitLink") { raw ->
            val repository = InMemoryHabitRepository()
            @Suppress("UNCHECKED_CAST")
            repository.restoreMaps(habits as List<Map<String, Any?>>)
            HabitLinkPolicy.fromMap(repository, raw.asStringMap())
        }

        recoverSection(safe, invalid, "coach") { raw ->
            val snapshot = coachSnapshot(raw.asStringMap())
            MockCoachRepository(initialDirective = snapshot.directives.first()).restoreState(snapshot)
        }
        recoverSection(safe, invalid, "content") { raw ->
            val items = (raw as? List<*>)?.map { it.asStringMap() }
                ?: throw IllegalArgumentException("content must be a list")
            ContentCatalog.fromMaps(items)
        }
        recoverSection(safe, invalid, "lastSimulatedEvent") { raw -> event(raw.asStringMap()) }
        recoverSection(safe, invalid, "capabilities") { raw ->
            val map = raw.asStringMap()
            require(map.values.all { it is Boolean }) { "capabilities must contain only booleans" }
        }
        recoverSection(safe, invalid, "launcherPins", emptyList<String>()) { raw ->
            (raw as? List<*>)?.map { it as? String ?: throw IllegalArgumentException("launcher pin must be a string") }
                ?.onEach { require(it.isNotBlank()) }
                ?: throw IllegalArgumentException("launcherPins must be a list")
        }

        return RecoveredAtlasState(safe, invalid)
    }

    private inline fun <T> recoverSection(
        state: MutableMap<String, Any?>,
        invalid: MutableSet<String>,
        key: String,
        fallback: T? = null,
        validate: (Any) -> T,
    ): T? {
        val raw = state[key] ?: return fallback
        return try {
            validate(raw as Any)
        } catch (_: Exception) {
            state[key] = fallback
            invalid += key
            fallback
        }
    }

    private fun coachSnapshot(values: Map<String, Any?>): CoachRepositoryState {
        val directives = values.requiredList("directives").map { Directive.fromMap(it.asStringMap()) }
        require(directives.isNotEmpty()) { "coach needs at least one directive" }
        val responses = values.optionalList("responses").orEmpty().map { DirectiveResult.fromMap(it.asStringMap()) }
        val journal = values.optionalList("journal").orEmpty().map { CoachJournalEntry.fromMap(it.asStringMap()) }
        val current = values["currentDirectiveId"] as? String ?: directives.first().id
        val paused = values["paused"] as? Boolean ?: false
        return CoachRepositoryState(directives, responses, journal, current, paused)
    }

    private fun event(values: Map<String, Any?>): AppOpenEvent = AppOpenEvent(
        eventId = values.requiredString("eventId").also { require(it.isNotBlank()) },
        packageName = values.requiredString("packageName").also { require(it.isNotBlank()) },
        timestampUtc = values.requiredNumber("timestampUtc").toLong(),
    )

    private fun Any?.asStringMap(): Map<String, Any?> = (this as? Map<*, *>)
        ?.entries
        ?.associate { (key, value) ->
            (key as? String ?: throw IllegalArgumentException("map key must be a string")) to value
        }
        ?: throw IllegalArgumentException("expected map")

    private fun Map<String, Any?>.requiredList(key: String): List<*> = this[key] as? List<*>
        ?: throw IllegalArgumentException("missing or invalid $key")

    private fun Map<String, Any?>.optionalList(key: String): List<*>? {
        val value = this[key] ?: return null
        return value as? List<*> ?: throw IllegalArgumentException("invalid $key")
    }

    private fun Map<String, Any?>.requiredString(key: String): String = this[key] as? String
        ?: throw IllegalArgumentException("missing or invalid $key")

    private fun Map<String, Any?>.requiredNumber(key: String): Number = this[key] as? Number
        ?: throw IllegalArgumentException("missing or invalid $key")
}
