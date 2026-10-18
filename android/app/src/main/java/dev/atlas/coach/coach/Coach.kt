package dev.atlas.coach.coach

import java.util.UUID

fun interface CoachClock {
    fun nowUtcMillis(): Long
}

enum class DirectiveType(val contractValue: String) {
    START_FOCUS_SESSION("start_focus_session"),
    HABIT_CHECKIN("habit_checkin"),
    READ_CONTENT("read_content"),
    PROJECT_ACTION("project_action"),
}

enum class DirectiveChoice(val contractValue: String) {
    START("start"),
    DISCUSS("discuss"),
    ALTERNATIVE("alternative"),
    POSTPONE("postpone"),
    PAUSE("pause"),
}

enum class DirectiveState {
    PENDING, STARTED, IN_DISCUSSION, ALTERNATIVE_PROPOSED, POSTPONED, PAUSED,
}

/** Local, serializable proposal; timestamps are UTC epoch milliseconds. */
data class Directive(
    val id: String,
    val title: String,
    val reason: String,
    val projectId: String? = null,
    val estimatedMinutes: Int? = 10,
    val state: DirectiveState = DirectiveState.PENDING,
    val type: DirectiveType = DirectiveType.START_FOCUS_SESSION,
    val choices: List<DirectiveChoice> = DirectiveChoice.entries,
    val createdAtUtcMillis: Long,
    val expiresAtUtcMillis: Long,
    val requiresExplicitConfirmation: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "directive_id is required" }
        require(title.isNotBlank() && title.length <= 160) { "title must contain 1–160 characters" }
        require(reason.length <= 400) { "reason must be at most 400 characters" }
        require(estimatedMinutes == null || estimatedMinutes in 1..180) { "estimated_minutes must be 1–180" }
        require(choices.size >= 4 && choices.distinct().size == choices.size) { "At least four unique choices are required" }
        require(expiresAtUtcMillis >= createdAtUtcMillis) { "expires_at must not precede created_at" }
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "schema_version" to 1,
        "directive_id" to id,
        "type" to type.contractValue,
        "title" to title,
        "reason" to reason,
        "project_id" to projectId,
        "estimated_minutes" to estimatedMinutes,
        "choices" to choices.map { it.contractValue },
        "state" to state.name,
        "created_at_utc_ms" to createdAtUtcMillis,
        "expires_at_utc_ms" to expiresAtUtcMillis,
        "requires_explicit_confirmation" to requiresExplicitConfirmation,
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): Directive {
            require((map["schema_version"] as? Number)?.toInt() == 1) { "schema_version must be 1" }
            val choices = (map["choices"] as? List<*>)
                ?.map { value -> DirectiveChoice.entries.firstOrNull { it.contractValue == value }
                    ?: throw IllegalArgumentException("Invalid directive choice: $value") }
                ?: throw IllegalArgumentException("choices is required")
            return Directive(
                id = map.requiredString("directive_id"),
                title = map.requiredString("title"),
                reason = map.requiredString("reason"),
                projectId = map["project_id"] as? String,
                estimatedMinutes = (map["estimated_minutes"] as? Number)?.toInt(),
                state = map["state"]?.let { DirectiveState.valueOf(it as String) } ?: DirectiveState.PENDING,
                type = DirectiveType.entries.firstOrNull { it.contractValue == map["type"] }
                    ?: throw IllegalArgumentException("Invalid directive type"),
                choices = choices,
                createdAtUtcMillis = map.requiredLong("created_at_utc_ms"),
                expiresAtUtcMillis = map.requiredLong("expires_at_utc_ms"),
                requiresExplicitConfirmation = map["requires_explicit_confirmation"] as? Boolean ?: false,
            )
        }

        fun demo(nowUtcMillis: Long, id: String = "directive-demo-1") = Directive(
            id = id,
            title = "Lis/avance 10 min",
            reason = "Un petit pas suffit pour reprendre le fil.",
            projectId = "demo-project",
            estimatedMinutes = 10,
            createdAtUtcMillis = nowUtcMillis,
            expiresAtUtcMillis = nowUtcMillis + DAY_MILLIS,
        )
    }
}

enum class DirectiveEffect {
    FOCUS_REQUESTED, DISCUSSION_MESSAGE, ALTERNATIVE_PROPOSED, POSTPONED, COACH_PAUSED,
}

fun DirectiveEffect.displayLabel(): String = when (this) {
    DirectiveEffect.FOCUS_REQUESTED -> "Concentration demandée"
    DirectiveEffect.DISCUSSION_MESSAGE -> "Échange ouvert"
    DirectiveEffect.ALTERNATIVE_PROPOSED -> "Alternative proposée"
    DirectiveEffect.POSTPONED -> "Reportée"
    DirectiveEffect.COACH_PAUSED -> "Coach en pause"
}

data class DirectiveResult(
    val directiveId: String,
    val choice: DirectiveChoice,
    val message: String,
    val effect: DirectiveEffect,
    val createdAtUtcMillis: Long,
    val requestedDurationMinutes: Int? = null,
    val alternativeDirective: Directive? = null,
    val nextDueAtUtcMillis: Long? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "directiveId" to directiveId,
        "choice" to choice.contractValue,
        "message" to message,
        "effect" to effect.name,
        "createdAtUtcMillis" to createdAtUtcMillis,
        "requestedDurationMinutes" to requestedDurationMinutes,
        "alternativeDirective" to alternativeDirective?.toMap(),
        "nextDueAtUtcMillis" to nextDueAtUtcMillis,
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): DirectiveResult {
            @Suppress("UNCHECKED_CAST")
            val alternativeMap = map["alternativeDirective"] as? Map<String, Any?>
            return DirectiveResult(
                directiveId = map.requiredString("directiveId"),
                choice = DirectiveChoice.entries.firstOrNull { it.contractValue == map["choice"] }
                    ?: throw IllegalArgumentException("Invalid directive choice"),
                message = map.requiredString("message"),
                effect = DirectiveEffect.valueOf(map.requiredString("effect")),
                createdAtUtcMillis = map.requiredLong("createdAtUtcMillis"),
                requestedDurationMinutes = (map["requestedDurationMinutes"] as? Number)?.toInt(),
                alternativeDirective = alternativeMap?.let(Directive::fromMap),
                nextDueAtUtcMillis = (map["nextDueAtUtcMillis"] as? Number)?.toLong(),
            )
        }
    }
}

data class CoachJournalEntry(
    val id: String,
    val directiveId: String,
    val choice: DirectiveChoice,
    val message: String,
    val effect: DirectiveEffect,
    val createdAtUtcMillis: Long,
    val requestedDurationMinutes: Int? = null,
    val alternativeDirectiveId: String? = null,
    val nextDueAtUtcMillis: Long? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "directiveId" to directiveId,
        "choice" to choice.contractValue,
        "message" to message,
        "effect" to effect.name,
        "createdAtUtcMillis" to createdAtUtcMillis,
        "requestedDurationMinutes" to requestedDurationMinutes,
        "alternativeDirectiveId" to alternativeDirectiveId,
        "nextDueAtUtcMillis" to nextDueAtUtcMillis,
    )

    companion object {
        fun fromMap(map: Map<String, Any?>) = CoachJournalEntry(
            id = map.requiredString("id"),
            directiveId = map.requiredString("directiveId"),
            choice = DirectiveChoice.entries.firstOrNull { it.contractValue == map["choice"] }
                ?: throw IllegalArgumentException("Invalid directive choice"),
            message = map.requiredString("message"),
            effect = DirectiveEffect.valueOf(map.requiredString("effect")),
            createdAtUtcMillis = map.requiredLong("createdAtUtcMillis"),
            requestedDurationMinutes = (map["requestedDurationMinutes"] as? Number)?.toInt(),
            alternativeDirectiveId = map["alternativeDirectiveId"] as? String,
            nextDueAtUtcMillis = (map["nextDueAtUtcMillis"] as? Number)?.toLong(),
        )
    }
}

interface CoachRepository {
    fun currentDirective(): Directive
    fun findDirective(id: String): Directive?
    fun respond(id: String, choice: DirectiveChoice): DirectiveResult
    fun journal(): CoachJournal
    fun listJournal(): List<CoachJournalEntry>
    fun exportJournalCsv(): String
    fun isPaused(): Boolean
}

/** Snapshot used by the local Android adapter to preserve the offline coach across launches. */
data class CoachRepositoryState(
    val directives: List<Directive>,
    val responses: List<DirectiveResult>,
    val journalEntries: List<CoachJournalEntry>,
    val currentDirectiveId: String,
    val paused: Boolean,
)

/** Deterministic offline coach. Responses are proposals and never invoke Android actions. */
class MockCoachRepository(
    private val clock: CoachClock = CoachClock(System::currentTimeMillis),
    initialDirective: Directive = Directive.demo(clock.nowUtcMillis()),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : CoachRepository {
    private val directives = linkedMapOf(initialDirective.id to initialDirective)
    private val responses = linkedMapOf<Pair<String, DirectiveChoice>, DirectiveResult>()
    private val journal = mutableListOf<CoachJournalEntry>()
    private var currentId = initialDirective.id
    private var paused = false

    @Synchronized
    override fun currentDirective(): Directive = directives.getValue(currentId)

    @Synchronized
    override fun findDirective(id: String): Directive? = directives[id]

    @Synchronized
    override fun respond(id: String, choice: DirectiveChoice): DirectiveResult {
        val key = id to choice
        responses[key]?.let { return it }
        require(!paused) { "Le coach est en pause." }
        val directive = directives[id] ?: throw IllegalArgumentException("Directive inconnue : $id")
        require(choice in directive.choices) { "Cette réponse n'est pas proposée par la directive." }
        val now = clock.nowUtcMillis()
        val alternative = if (choice == DirectiveChoice.ALTERNATIVE) {
            Directive(
                id = "$id-alternative",
                title = "Lis/avance 5 min",
                reason = "Voici une étape plus courte pour commencer.",
                projectId = directive.projectId,
                estimatedMinutes = 5,
                createdAtUtcMillis = now,
                expiresAtUtcMillis = now + DAY_MILLIS,
            )
        } else null
        val nextDue = if (choice == DirectiveChoice.POSTPONE) now + POSTPONE_MILLIS else null
        val message = when (choice) {
            DirectiveChoice.START -> "Je prépare une session de ${directive.estimatedMinutes ?: 10} min."
            DirectiveChoice.DISCUSS -> "D'accord. Qu'est-ce qui rend cette étape difficile aujourd'hui ?"
            DirectiveChoice.ALTERNATIVE -> "Essayons une version plus courte : ${alternative!!.title}."
            DirectiveChoice.POSTPONE -> "C'est reporté à plus tard aujourd'hui."
            DirectiveChoice.PAUSE -> "Le coach est en pause. Vous pouvez le réactiver quand vous le souhaitez."
        }
        val effect = when (choice) {
            DirectiveChoice.START -> DirectiveEffect.FOCUS_REQUESTED
            DirectiveChoice.DISCUSS -> DirectiveEffect.DISCUSSION_MESSAGE
            DirectiveChoice.ALTERNATIVE -> DirectiveEffect.ALTERNATIVE_PROPOSED
            DirectiveChoice.POSTPONE -> DirectiveEffect.POSTPONED
            DirectiveChoice.PAUSE -> DirectiveEffect.COACH_PAUSED
        }
        val updated = when (choice) {
            DirectiveChoice.START -> directive.copy(state = DirectiveState.STARTED)
            DirectiveChoice.DISCUSS -> directive.copy(state = DirectiveState.IN_DISCUSSION)
            DirectiveChoice.ALTERNATIVE -> directive.copy(state = DirectiveState.ALTERNATIVE_PROPOSED)
            DirectiveChoice.POSTPONE -> directive.copy(state = DirectiveState.POSTPONED, expiresAtUtcMillis = nextDue!!)
            DirectiveChoice.PAUSE -> directive.copy(state = DirectiveState.PAUSED)
        }
        directives[id] = updated
        if (alternative != null) {
            directives[alternative.id] = alternative
            currentId = alternative.id
        }
        if (choice == DirectiveChoice.PAUSE) paused = true
        val result = DirectiveResult(
            directiveId = id,
            choice = choice,
            message = message,
            effect = effect,
            createdAtUtcMillis = now,
            requestedDurationMinutes = if (choice == DirectiveChoice.START) directive.estimatedMinutes ?: 10 else null,
            alternativeDirective = alternative,
            nextDueAtUtcMillis = nextDue,
        )
        responses[key] = result
        journal += CoachJournalEntry(
            id = newId(),
            directiveId = id,
            choice = choice,
            message = message,
            effect = effect,
            createdAtUtcMillis = now,
            requestedDurationMinutes = result.requestedDurationMinutes,
            alternativeDirectiveId = alternative?.id,
            nextDueAtUtcMillis = nextDue,
        )
        trimOldest(journal, MAX_JOURNAL_ENTRIES)
        trimOldest(responses, MAX_JOURNAL_ENTRIES)
        trimOldest(directives, MAX_JOURNAL_ENTRIES)
        return result
    }

    @Synchronized
    override fun journal(): CoachJournal = CoachJournal(journal.toList())

    @Synchronized
    override fun listJournal(): List<CoachJournalEntry> = journal().entries

    @Synchronized
    override fun exportJournalCsv(): String = journal().exportCsv()

    @Synchronized
    override fun isPaused(): Boolean = paused

    @Synchronized
    fun snapshotState(): CoachRepositoryState = CoachRepositoryState(
        directives = directives.values.toList(),
        responses = responses.values.toList(),
        journalEntries = journal.toList(),
        currentDirectiveId = currentId,
        paused = paused,
    )

    /** Restores a previously captured local state while preserving idempotent responses. */
    @Synchronized
    fun restoreState(state: CoachRepositoryState) {
        require(state.directives.isNotEmpty()) { "A coach snapshot must contain a directive" }
        require(state.directives.any { it.id == state.currentDirectiveId }) { "Current directive is missing" }
        val restoredDirectives = linkedMapOf<String, Directive>()
        state.directives.forEach { directive ->
            require(restoredDirectives.put(directive.id, directive) == null) { "Duplicate directive ID" }
        }
        val restoredResponses = linkedMapOf<Pair<String, DirectiveChoice>, DirectiveResult>()
        state.responses.forEach { result ->
            val key = result.directiveId to result.choice
            require(restoredResponses.put(key, result) == null) { "Duplicate coach response" }
        }
        val restoredJournal = state.journalEntries.takeLast(MAX_JOURNAL_ENTRIES)
        while (restoredDirectives.size > MAX_JOURNAL_ENTRIES) {
            val removable = restoredDirectives.keys.firstOrNull { it != state.currentDirectiveId }
                ?: break
            restoredDirectives.remove(removable)
        }
        trimOldest(restoredResponses, MAX_JOURNAL_ENTRIES)
        directives.clear()
        directives.putAll(restoredDirectives)
        journal.clear()
        journal.addAll(restoredJournal)
        responses.clear()
        responses.putAll(restoredResponses)
        currentId = state.currentDirectiveId
        paused = state.paused
    }

    @Synchronized
    fun resume() {
        paused = false
    }

    private fun <K, V> trimOldest(values: MutableMap<K, V>, maxSize: Int) {
        while (values.size > maxSize) values.remove(values.keys.first())
    }

    private fun <T> trimOldest(values: MutableList<T>, maxSize: Int) {
        while (values.size > maxSize) values.removeAt(0)
    }

    companion object {
        const val MAX_JOURNAL_ENTRIES = 200
    }
}

/** Immutable journal snapshot that S3 can store or export independently of the mock repository. */
data class CoachJournal(val entries: List<CoachJournalEntry>) {
    fun list(): List<CoachJournalEntry> = entries.toList()

    fun toMaps(): List<Map<String, Any?>> = entries.map(CoachJournalEntry::toMap)

    fun exportCsv(): String = buildString {
        appendLine("id,directive_id,choice,effect,message,created_at_utc_ms,requested_duration_minutes,alternative_directive_id,next_due_at_utc_ms")
        entries.forEach { entry ->
            append(csv(entry.id)).append(',').append(csv(entry.directiveId)).append(',')
                .append(entry.choice.contractValue).append(',').append(entry.effect.name).append(',')
                .append(csv(entry.message)).append(',').append(entry.createdAtUtcMillis).append(',')
                .append(entry.requestedDurationMinutes?.toString().orEmpty()).append(',')
                .append(entry.alternativeDirectiveId?.let(::csv).orEmpty()).append(',')
                .append(entry.nextDueAtUtcMillis?.toString().orEmpty()).appendLine()
        }
    }

    companion object {
        fun fromMaps(maps: List<Map<String, Any?>>) = CoachJournal(maps.map(CoachJournalEntry::fromMap))
    }
}

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
private const val POSTPONE_MILLIS = 60 * 60 * 1000L

private fun Map<String, Any?>.requiredString(key: String): String =
    this[key] as? String ?: throw IllegalArgumentException("$key must be a string")

private fun Map<String, Any?>.requiredLong(key: String): Long =
    (this[key] as? Number)?.toLong() ?: throw IllegalArgumentException("$key must be a number")

private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
