package dev.atlas.coach.focus

sealed interface FocusHabitLinkCondition {
    fun isSatisfiedBy(session: FocusSession): Boolean

    /** Explicitly permits linking once any focus session is marked finished. */
    data object SessionFinished : FocusHabitLinkCondition {
        override fun isSatisfiedBy(session: FocusSession): Boolean = session.status == FocusStatus.FINISHED
    }

    /** Requires the finite plan to reach its configured final work cycle. */
    data object AllWorkCyclesCompleted : FocusHabitLinkCondition {
        override fun isSatisfiedBy(session: FocusSession): Boolean =
            session.status == FocusStatus.FINISHED && !session.continuous &&
                session.completedWorkCycles >= session.totalWorkCycles
    }

    data class AtLeastWorkCycles(val minimum: Int) : FocusHabitLinkCondition {
        init { require(minimum > 0) }
        override fun isSatisfiedBy(session: FocusSession): Boolean =
            session.status == FocusStatus.FINISHED && session.completedWorkCycles >= minimum
    }
}

/** One local journal row and its optional links, keyed by sessionId to prevent double counting. */
data class FocusJournalLink(
    val sessionId: String,
    val journalEntryId: String,
    val mockDirectiveId: String?,
    val projectId: String?,
    val habitId: String?,
    val completedWorkCycles: Int,
    val linkedAtUtcMillis: Long,
) {
    init {
        require(sessionId.isNotBlank() && journalEntryId == sessionId)
        require(projectId == null || projectId.isNotBlank())
        require(habitId == null || habitId.isNotBlank())
        require(completedWorkCycles >= 0 && linkedAtUtcMillis >= 0L)
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "sessionId" to sessionId,
        "journalEntryId" to journalEntryId,
        "mockDirectiveId" to mockDirectiveId,
        "projectId" to projectId,
        "habitId" to habitId,
        "completedWorkCycles" to completedWorkCycles,
        "linkedAtUtcMillis" to linkedAtUtcMillis,
    )

    companion object {
        fun fromMap(values: Map<String, Any?>): FocusJournalLink = FocusJournalLink(
            sessionId = values["sessionId"] as? String ?: error("Missing sessionId"),
            journalEntryId = values["journalEntryId"] as? String ?: error("Missing journalEntryId"),
            mockDirectiveId = values["mockDirectiveId"] as? String,
            projectId = values["projectId"] as? String,
            habitId = values["habitId"] as? String,
            completedWorkCycles = (values["completedWorkCycles"] as? Number)?.toInt()
                ?: error("Missing completedWorkCycles"),
            linkedAtUtcMillis = (values["linkedAtUtcMillis"] as? Number)?.toLong()
                ?: error("Missing linkedAtUtcMillis"),
        )
    }
}

interface FocusJournalLinkStore {
    fun find(sessionId: String): FocusJournalLink?
    fun all(): List<FocusJournalLink>
    /** Atomically inserts by sessionId; returns false when this session was already counted. */
    fun insertIfAbsent(link: FocusJournalLink): Boolean
}

class InMemoryFocusJournalLinkStore(initial: Collection<FocusJournalLink> = emptyList()) : FocusJournalLinkStore {
    private val links = linkedMapOf<String, FocusJournalLink>()

    init { initial.forEach { links.putIfAbsent(it.sessionId, it) } }

    override fun find(sessionId: String): FocusJournalLink? = links[sessionId]

    override fun all(): List<FocusJournalLink> = links.values.toList()

    @Synchronized
    override fun insertIfAbsent(link: FocusJournalLink): Boolean {
        if (links.containsKey(link.sessionId)) return false
        links[link.sessionId] = link
        return true
    }
}

sealed interface FocusLinkResult {
    data class Recorded(val link: FocusJournalLink) : FocusLinkResult
    data class AlreadyRecorded(val link: FocusJournalLink) : FocusLinkResult
    data object ConditionNotMet : FocusLinkResult
    data object HabitConfirmationRequired : FocusLinkResult
}

/**
 * Pure coordination boundary for the host's existing journal and habit policy. A caller only
 * supplies a habit after confirmation; the store's sessionId key makes retries idempotent.
 */
class FocusSessionLinker(private val store: FocusJournalLinkStore) {
    fun link(
        session: FocusSession,
        condition: FocusHabitLinkCondition,
        projectId: String? = null,
        habitId: String? = null,
        habitConfirmed: Boolean = false,
        linkedAtUtcMillis: Long,
    ): FocusLinkResult {
        if (!condition.isSatisfiedBy(session)) return FocusLinkResult.ConditionNotMet
        store.find(session.id)?.let { return FocusLinkResult.AlreadyRecorded(it) }
        if (habitId != null && !habitConfirmed) return FocusLinkResult.HabitConfirmationRequired
        val link = FocusJournalLink(
            sessionId = session.id,
            journalEntryId = session.id,
            mockDirectiveId = session.directiveId,
            projectId = projectId,
            habitId = habitId,
            completedWorkCycles = session.completedWorkCycles,
            linkedAtUtcMillis = linkedAtUtcMillis,
        )
        return if (store.insertIfAbsent(link)) FocusLinkResult.Recorded(link)
        else FocusLinkResult.AlreadyRecorded(checkNotNull(store.find(session.id)))
    }
}
