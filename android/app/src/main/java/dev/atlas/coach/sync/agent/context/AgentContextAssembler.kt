package dev.atlas.coach.sync.agent.context

import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.goals.GoalMetric
import dev.atlas.coach.goals.GoalProgressCalculator
import dev.atlas.coach.goals.GoalRecord
import dev.atlas.coach.goals.GoalStatus
import dev.atlas.coach.habits.Habit
import dev.atlas.coach.habits.HabitCheckIn
import dev.atlas.coach.location.LocationSummary
import dev.atlas.coach.rules.AppOpenEvent
import dev.atlas.coach.sync.DataScope
import dev.atlas.coach.sync.SyncDataCategory
import dev.atlas.coach.sync.SyncEvent
import dev.atlas.coach.sync.SyncEventType
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.coach.AgentConversationMessage
import dev.atlas.coach.usage.ObservationConsent
import dev.atlas.coach.usage.ObservationRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Collections

/** Read-only projection of existing local domains; no Activity or mutating repository is accepted. */
class AgentContextDomainViews(
    focusSessions: List<FocusSession> = emptyList(),
    habits: List<Habit> = emptyList(),
    habitCheckIns: List<HabitCheckIn> = emptyList(),
    usageConsent: ObservationConsent? = null,
    usageRecords: List<ObservationRecord> = emptyList(),
    contentActivity: List<SyncEvent> = emptyList(),
    coachMessages: List<AgentConversationMessage> = emptyList(),
    goals: List<GoalRecord> = emptyList(),
    val locationSummarySharingEnabled: Boolean = false,
    locationSummaries: List<LocationSummary> = emptyList(),
) {
    val usageConsent: ObservationConsent? = usageConsent?.copy(
        allowlistedPackageNames = Collections.unmodifiableSet(usageConsent.allowlistedPackageNames.toSet()),
    )
    val focusSessions: List<FocusSession> = immutableCopy(focusSessions)
    val habits: List<Habit> = immutableCopy(habits)
    val habitCheckIns: List<HabitCheckIn> = immutableCopy(habitCheckIns)
    val usageRecords: List<ObservationRecord> = immutableCopy(usageRecords)
    val contentActivity: List<SyncEvent> = immutableCopy(contentActivity)
    val coachMessages: List<AgentConversationMessage> = immutableCopy(coachMessages)
    val goals: List<GoalRecord> = immutableCopy(goals)
    val locationSummaries: List<LocationSummary> = immutableCopy(locationSummaries)

    private companion object {
        fun <T> immutableCopy(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
    }
}

/** Explicitly called migration for legacy opt-ins with no historical timestamp. */
object AgentContextConsentMigration {
    fun migrateLegacyConsentDatesToNow(
        legacyConsentedCategories: Set<String>,
        nowUtcMillis: Long,
    ): Map<String, Long> {
        require(nowUtcMillis > 0L) { "Consent migration requires the actual current time" }
        val valid = legacyConsentedCategories.intersect(AgentContextCategory.wireValueSet)
        return AgentContextCategory.wireValues.filter { it in valid }.associateWith { nowUtcMillis }
    }
}

/** Creates a minimized, serialized bridge snapshot from real ATLAS local domain records. */
class AgentContextAssembler {
    fun assemble(
        currentMandate: AgentMandate,
        consentAtUtcMillis: Map<String, Long>,
        domains: AgentContextDomainViews,
        nowUtcMillis: Long,
        includeExistingGoals: Boolean = false,
    ): AgentContextSnapshot {
        require(nowUtcMillis > 0L) { "Context assembly requires a real current time" }
        val mandateIsCurrent = !currentMandate.revoked && !currentMandate.suspended &&
            nowUtcMillis < currentMandate.expiresAtUtcMillis
        val authorized = if (mandateIsCurrent) {
            AgentContextCategory.wireValues.filter { it in currentMandate.sharedDataCategories }
        } else {
            emptyList()
        }
        val candidates = buildList {
            val validConsentDates = authorized.mapNotNull { category ->
                consentAtUtcMillis[category]?.takeIf { it > 0L && it <= nowUtcMillis }?.let { category to it }
            }.toMap()
            val focusConsent = validConsentDates[AgentContextCategory.FOCUS.wireValue]
            if (focusConsent != null) addFocusCandidates(domains.focusSessions, focusConsent, nowUtcMillis)

            val habitConsent = validConsentDates[AgentContextCategory.HABIT.wireValue]
            if (habitConsent != null) addHabitCandidates(domains.habits, domains.habitCheckIns, habitConsent, nowUtcMillis)

            val contentConsent = validConsentDates[AgentContextCategory.CONTENT.wireValue]
            if (contentConsent != null) addContentCandidates(domains.contentActivity, contentConsent, nowUtcMillis)

            val coachConsent = validConsentDates[AgentContextCategory.COACH_INTERACTION.wireValue]
            if (coachConsent != null) addCoachCandidates(domains.coachMessages, coachConsent, nowUtcMillis)

            val usageConsent = validConsentDates[AgentContextCategory.APP_USAGE_SUMMARY.wireValue]
            if (usageConsent != null) addUsageCandidate(
                domains.usageConsent, domains.usageRecords, usageConsent, nowUtcMillis,
            )

            val goalsConsent = validConsentDates[AgentContextCategory.GOALS.wireValue]
            if (goalsConsent != null) addGoalCandidates(
                domains.goals, domains.focusSessions, domains.habitCheckIns, goalsConsent, nowUtcMillis,
                includeExistingGoals,
            )

            val locationConsent = validConsentDates[AgentContextCategory.LOCATION_SUMMARY.wireValue]
            if (locationConsent != null && domains.locationSummarySharingEnabled) {
                addLocationCandidates(domains.locationSummaries, locationConsent, nowUtcMillis)
            }
        }
        return fitPayload(
            authorized,
            candidates,
            sourceUsageConsent = domains.usageConsent,
            locationSharingEnabledAtAssembly = domains.locationSummarySharingEnabled,
            includeExistingGoals = includeExistingGoals,
        )
    }

    private fun MutableList<Candidate>.addFocusCandidates(
        sessions: List<FocusSession>, consentAt: Long, now: Long,
    ) {
        val cutoff = maxOf(consentAt, (now - FOCUS_RETENTION_MILLIS).coerceAtLeast(0L))
        GoalProgressCalculator.latestFinishedSessions(sessions).asSequence()
            .filter { it.createdAtUtcMillis in cutoff..now }
            .mapNotNull { session ->
                val minutes = GoalProgressCalculator.completedWorkSeconds(session) / SECONDS_PER_MINUTE
                if (minutes <= 0L) return@mapNotNull null
                Candidate(
                    AgentContextCategory.FOCUS.wireValue,
                    "${Instant.ofEpochMilli(session.createdAtUtcMillis)} | completed | $minutes min",
                    session.createdAtUtcMillis,
                    PRIORITY_FOCUS,
                )
            }
            .forEach(::add)
    }

    private fun MutableList<Candidate>.addHabitCandidates(
        habits: List<Habit>, checkIns: List<HabitCheckIn>, consentAt: Long, now: Long,
    ) {
        val cutoff = maxOf(consentAt, (now - HABIT_RETENTION_MILLIS).coerceAtLeast(0L))
        val consentDate = Instant.ofEpochMilli(consentAt).atZone(ZoneOffset.UTC).toLocalDate()
        val knownHabitIds = habits.mapTo(hashSetOf(), Habit::id)
        val valid = checkIns.asSequence()
            .filter { it.habitId in knownHabitIds }
            .filter { it.createdAtUtcMillis in cutoff..now }
            .filter { !it.date.isBefore(consentDate) }
            .distinctBy { it.habitId to it.dateEpochDay }
            .toList()
        valid.groupBy { utcDate(it.createdAtUtcMillis) }
            .forEach { (date, dayCheckIns) ->
                add(Candidate(
                    AgentContextCategory.HABIT.wireValue,
                    "$date | ${dayCheckIns.size} validation(s) d'habitude",
                    dayCheckIns.minOf { it.createdAtUtcMillis },
                    PRIORITY_HABIT,
                ))
            }
    }

    private fun MutableList<Candidate>.addContentCandidates(events: List<SyncEvent>, consentAt: Long, now: Long) {
        val cutoff = maxOf(consentAt, (now - ACTIVITY_RETENTION_MILLIS).coerceAtLeast(0L))
        events.asSequence()
            .filter {
                it.category == SyncDataCategory.CONTENT && it.eventType == SyncEventType.CONTENT_OPENED &&
                    it.dataScope == DataScope.SHAREABLE && it.occurredAtUtcMillis in cutoff..now
            }
            .distinctBy(SyncEvent::clientEventId)
            .toList()
            .groupBy { utcDate(it.occurredAtUtcMillis) }
            .forEach { (date, dayEvents) ->
                add(Candidate(
                    AgentContextCategory.CONTENT.wireValue,
                    "$date | ${dayEvents.size} ouvertures de contenu (titres et URL omis)",
                    dayEvents.minOf { it.occurredAtUtcMillis },
                    PRIORITY_CONTENT,
                ))
            }
    }

    private fun MutableList<Candidate>.addCoachCandidates(
        messages: List<AgentConversationMessage>, consentAt: Long, now: Long,
    ) {
        val cutoff = maxOf(consentAt, (now - ACTIVITY_RETENTION_MILLIS).coerceAtLeast(0L))
        messages.asSequence()
            .filter { it.createdAtUtcMillis in cutoff..now }
            .distinctBy(AgentConversationMessage::id)
            .toList()
            .groupBy { utcDate(it.createdAtUtcMillis) }
            .forEach { (date, dayMessages) ->
                add(Candidate(
                    AgentContextCategory.COACH_INTERACTION.wireValue,
                    "$date | ${dayMessages.size} messages Coach (texte omis)",
                    dayMessages.minOf { it.createdAtUtcMillis },
                    PRIORITY_COACH,
                ))
            }
    }

    private fun MutableList<Candidate>.addUsageCandidate(
        usageConsent: ObservationConsent?, records: List<ObservationRecord>, consentAt: Long, now: Long,
    ) {
        if (usageConsent?.isExplicit != true) return
        val observationConsentAt = usageConsent.grantedAtUtc ?: return
        val cutoff = maxOf(consentAt, observationConsentAt, (now - USAGE_RETENTION_MILLIS).coerceAtLeast(0L))
        val validRecords = records.asSequence()
            .filter { it.packageName in usageConsent.allowlistedPackageNames }
            .filter { it.timestampUtc in cutoff..now }
            .distinctBy(ObservationRecord::eventId)
            .toList()
        if (validRecords.isEmpty()) return
        add(Candidate(
            AgentContextCategory.APP_USAGE_SUMMARY.wireValue,
            "24 h | ${validRecords.size} ouvertures observées | ${validRecords.map(ObservationRecord::packageName).distinct().size} applications",
            validRecords.minOf(ObservationRecord::timestampUtc),
            PRIORITY_USAGE,
        ))
    }

    private fun MutableList<Candidate>.addGoalCandidates(
        goals: List<GoalRecord>, sessions: List<FocusSession>, checkIns: List<HabitCheckIn>, consentAt: Long, now: Long,
        includeExistingGoals: Boolean,
    ) {
        val consentDate = Instant.ofEpochMilli(consentAt).atZone(ZoneOffset.UTC).toLocalDate()
        goals.asSequence()
            .filter { it.status == GoalStatus.ACTIVE }
            .filter { it.updatedAt <= now && (includeExistingGoals || it.updatedAt >= consentAt) }
            .distinctBy(GoalRecord::id)
            .forEach { goal ->
                val linkedSessions = GoalProgressCalculator.latestFinishedSessions(sessions).asSequence()
                    .filter { it.id in goal.linkedFocusSessionIds }
                    .filter { it.createdAtUtcMillis <= now &&
                        (includeExistingGoals || it.createdAtUtcMillis >= consentAt) }
                    .toList()
                val linkedCheckIns = checkIns.asSequence()
                    .filter { it.habitId in goal.linkedHabitIds }
                    .filter { it.createdAtUtcMillis <= now &&
                        (includeExistingGoals || it.createdAtUtcMillis >= consentAt) }
                    .filter { includeExistingGoals || !it.date.isBefore(consentDate) }
                    .distinctBy { it.habitId to it.dateEpochDay }
                    .toList()
                val progress = GoalProgressCalculator.calculate(goal, linkedSessions, linkedCheckIns)
                val metricLabel = when (goal.metric) {
                    GoalMetric.FOCUS_MINUTES -> "focus_minutes"
                    GoalMetric.HABIT_CHECKINS -> "habit_checkins"
                    GoalMetric.MANUAL -> "manual"
                }
                val title = sanitizeUntrusted(goal.title, MAX_GOAL_TITLE_CODE_POINTS)
                val summary = "$title | cible ${goal.targetValue} $metricLabel | validé ${progress.currentValue}"
                val sourceTimestamp = (listOf(goal.updatedAt) + linkedSessions.map(FocusSession::createdAtUtcMillis) +
                    linkedCheckIns.map(HabitCheckIn::createdAtUtcMillis)).minOrNull() ?: goal.updatedAt
                add(Candidate(
                    AgentContextCategory.GOALS.wireValue,
                    summary,
                    sourceTimestamp,
                    PRIORITY_GOALS,
                ))
            }
    }

    private fun MutableList<Candidate>.addLocationCandidates(
        summaries: List<LocationSummary>, consentAt: Long, now: Long,
    ) {
        val cutoff = maxOf(consentAt, (now - LOCATION_RETENTION_MILLIS).coerceAtLeast(0L))
        summaries.asSequence()
            .filter { it.measuredAtUtcMillis in cutoff..now }
            .distinctBy(LocationSummary::measuredAtUtcMillis)
            .forEach { summary ->
                // LocationSummary is already coarsened at the Disclosure boundary.
                val approximatePoint = "${summary.latitude}, ${summary.longitude}"
                add(Candidate(
                    AgentContextCategory.LOCATION_SUMMARY.wireValue,
                    "${Instant.ofEpochMilli(summary.measuredAtUtcMillis)} | zone approximative (~${summary.accuracyMeters.toInt()} m) | $approximatePoint",
                    summary.measuredAtUtcMillis,
                    PRIORITY_LOCATION,
                ))
            }
    }

    internal fun fitPayload(
        authorized: List<String>,
        candidates: List<Candidate>,
        sourceUsageConsent: ObservationConsent? = null,
        locationSharingEnabledAtAssembly: Boolean = false,
        includeExistingGoals: Boolean = false,
    ): AgentContextSnapshot {
        val selected = linkedMapOf<String, MutableList<Candidate>>()
        val sorted = candidates.sortedWith(
            compareByDescending<Candidate> { it.priority }
                .thenByDescending { it.timestampUtcMillis }
                .thenBy { AgentContextCategory.wireValues.indexOf(it.category) },
        )
        sorted.forEach { original ->
            if (original.category !in authorized) return@forEach
            val value = limitCodePoints(original.value, AgentContextSnapshot.MAX_ITEM_CODE_POINTS)
            if (AgentContextSnapshot.codePointLength(value) == 0) return@forEach
            val categoryItems = selected.getOrPut(original.category) { mutableListOf() }
            if (categoryItems.size >= AgentContextSnapshot.MAX_ITEMS_PER_CATEGORY) return@forEach
            val candidate = original.copy(value = value)
            categoryItems += candidate
            val payloadCategories = AgentContextCategory.wireValues.mapNotNull { category ->
                selected[category]?.takeIf { it.isNotEmpty() }
                    ?.let { category to it.sortedByDescending(Candidate::timestampUtcMillis).map(Candidate::value) }
            }.toMap(LinkedHashMap())
            if (AgentContextSnapshot.serializedUtf8Size(authorized, payloadCategories) > AgentContextSnapshot.MAX_CONTEXT_BYTES) {
                categoryItems.removeAt(categoryItems.lastIndex)
                if (categoryItems.isEmpty()) selected.remove(original.category)
            }
        }
        return snapshotFrom(authorized, selected, sourceUsageConsent, locationSharingEnabledAtAssembly, includeExistingGoals)
    }

    private fun snapshotFrom(
        authorized: List<String>,
        selected: Map<String, List<Candidate>>,
        sourceUsageConsent: ObservationConsent?,
        locationSharingEnabledAtAssembly: Boolean,
        includeExistingGoals: Boolean,
    ): AgentContextSnapshot {
        val items = linkedMapOf<String, List<String>>()
        val timestamps = linkedMapOf<String, List<Long>>()
        AgentContextCategory.wireValues.forEach { category ->
            val values = selected[category].orEmpty().sortedByDescending(Candidate::timestampUtcMillis)
            if (values.isNotEmpty()) {
                items[category] = values.map(Candidate::value)
                timestamps[category] = values.map(Candidate::timestampUtcMillis)
            }
        }
        return AgentContextSnapshot(
            authorized,
            items,
            timestamps,
            usageConsentAtAssembly = sourceUsageConsent,
            locationSharingEnabledAtAssembly = locationSharingEnabledAtAssembly,
            includesExistingGoalsAtAssembly = includeExistingGoals,
        )
    }

    private fun sanitizeUntrusted(value: String, maxCodePoints: Int): String =
        limitCodePoints(value.map { char -> if (char.isISOControl() || char == '\\') ' ' else char }.joinToString(""), maxCodePoints)
            .replace('|', '¦')
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun limitCodePoints(value: String, maxCodePoints: Int): String {
        if (AgentContextSnapshot.codePointLength(value) <= maxCodePoints) return value
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints))
    }

    private fun utcDate(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate()

    internal data class Candidate(
        val category: String,
        val value: String,
        val timestampUtcMillis: Long,
        val priority: Int,
    )

    companion object {
        const val FOCUS_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        const val HABIT_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1_000L
        const val USAGE_RETENTION_MILLIS = 24L * 60L * 60L * 1_000L
        const val LOCATION_RETENTION_MILLIS = 15L * 60L * 1_000L
        const val ACTIVITY_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1_000L

        private const val SECONDS_PER_MINUTE = 60L
        private const val MAX_GOAL_TITLE_CODE_POINTS = 110
        private const val PRIORITY_GOALS = 100
        private const val PRIORITY_FOCUS = 90
        private const val PRIORITY_HABIT = 80
        private const val PRIORITY_USAGE = 70
        private const val PRIORITY_LOCATION = 60
        private const val PRIORITY_CONTENT = 40
        private const val PRIORITY_COACH = 30
    }
}
