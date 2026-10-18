package dev.atlas.coach.rules

import java.time.LocalTime

data class AppOpenEvent(
    val eventId: String,
    val packageName: String,
    val timestampUtc: Long,
)

data class QuietHours(
    val start: LocalTime,
    val end: LocalTime,
) {
    /** Start is inclusive and end exclusive; equal endpoints disable the interval. */
    fun contains(time: LocalTime): Boolean = when {
        start == end -> false
        start < end -> !time.isBefore(start) && time.isBefore(end)
        else -> !time.isBefore(start) || time.isBefore(end)
    }
}

data class LocalPolicy(
    val ruleId: String = "app-open-frequency",
    val openThreshold: Int = 4,
    val windowMillis: Long = 25L * 60L * 1_000L,
    val cooldownMillis: Long = 15L * 60L * 1_000L,
    val quietHours: QuietHours? = null,
    val pauseUntil: Long? = null,
    val vitalPackageNames: Set<String> = emptySet(),
    val emergencyPauseMillis: Long = 30L * 60L * 1_000L,
) {
    init {
        require(ruleId.isNotBlank()) { "ruleId must not be blank" }
        require(openThreshold in 1..RuleRetention.MAX_HISTORY_RECORDS) { "openThreshold must be between 1 and the history limit" }
        require(windowMillis > 0L && cooldownMillis >= 0L) { "Invalid rule durations" }
        require(emergencyPauseMillis > 0L) { "emergencyPauseMillis must be positive" }
    }

    fun withEmergencyPause(nowUtc: Long): LocalPolicy = copy(
        pauseUntil = if (nowUtc > Long.MAX_VALUE - emergencyPauseMillis) {
            Long.MAX_VALUE
        } else {
            nowUtc + emergencyPauseMillis
        },
    )
}

data class Intervention(
    val interventionId: String,
    val eventId: String,
    val packageName: String,
    val ruleId: String,
    val createdAtUtc: Long,
    val cooldownUntilUtc: Long,
)

/** Exportable in-memory state; records contain no platform objects or personal content. */
data class RuleEvaluatorState(
    val processedEventIds: Set<String> = emptySet(),
    /** Event timestamps let the idempotency cache expire with the active rule window. */
    val processedEventTimestampsUtc: Map<String, Long> = emptyMap(),
    /** Keys use `ruleId + NUL + packageName` so counters remain isolated per rule and app. */
    val recentEventsByRuleAndPackage: Map<String, List<AppOpenEvent>> = emptyMap(),
    val cooldownUntilUtcByRuleAndPackage: Map<String, Long> = emptyMap(),
    val interventions: List<Intervention> = emptyList(),
)

object RuleRetention {
    const val MAX_PROCESSED_EVENT_IDS = 2_000
    const val MAX_HISTORY_RECORDS = 200
}
