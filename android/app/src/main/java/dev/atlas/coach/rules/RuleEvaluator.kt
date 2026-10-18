package dev.atlas.coach.rules

import java.time.Instant
import java.time.ZoneId

/** Deterministic local rule evaluator. It proposes an intervention but never blocks an app. */
class RuleEvaluator(
    private val zoneId: ZoneId,
    initialState: RuleEvaluatorState = RuleEvaluatorState(),
) {
    private val processedEventTimestampsUtc = initialState.processedEventTimestampsUtc.toMutableMap().apply {
        initialState.processedEventIds.forEach { putIfAbsent(it, Long.MIN_VALUE) }
    }
    private val recentEventsByRuleAndPackage = initialState.recentEventsByRuleAndPackage
        .mapValuesTo(mutableMapOf()) { (_, events) -> events.toMutableList() }
    private val cooldownUntilUtcByRuleAndPackage = initialState.cooldownUntilUtcByRuleAndPackage.toMutableMap()
    private val interventions = initialState.interventions.toMutableList()

    init {
        trimProcessedIds()
        trimRuleKeys()
        trimInterventions()
    }

    @Synchronized
    fun evaluate(event: AppOpenEvent, policy: LocalPolicy): Intervention? {
        require(event.eventId.isNotBlank()) { "eventId must not be blank" }
        require(event.packageName.isNotBlank()) { "packageName must not be blank" }
        pruneExpired(event.timestampUtc, policy.windowMillis)
        if (processedEventTimestampsUtc.containsKey(event.eventId)) return null
        processedEventTimestampsUtc[event.eventId] = event.timestampUtc
        trimProcessedIds()

        val localTime = Instant.ofEpochMilli(event.timestampUtc).atZone(zoneId).toLocalTime()
        if (policy.allowlistedPackageNames != null && event.packageName !in policy.allowlistedPackageNames) return null
        if (event.packageName in policy.excludedPackageNames || event.packageName in policy.vitalPackageNames) return null
        if (SafetyDenylist.isProtected(event.packageName)) return null
        if (policy.quietHours?.contains(localTime) == true) return null
        if (policy.pauseUntil != null && event.timestampUtc < policy.pauseUntil) return null

        val cooldownKey = cooldownKey(policy.ruleId, event.packageName)
        val cooldownUntil = cooldownUntilUtcByRuleAndPackage[cooldownKey]
        if (cooldownUntil != null && event.timestampUtc < cooldownUntil) return null

        val window = recentEventsByRuleAndPackage.getOrPut(cooldownKey) { mutableListOf() }
        val cutoff = if (event.timestampUtc < Long.MIN_VALUE + policy.windowMillis) {
            Long.MIN_VALUE
        } else {
            event.timestampUtc - policy.windowMillis
        }
        window.removeAll { it.timestampUtc < cutoff }
        window.add(event)
        if (window.size > RuleRetention.MAX_HISTORY_RECORDS) {
            window.subList(0, window.size - RuleRetention.MAX_HISTORY_RECORDS).clear()
        }
        trimRuleKeys()
        if (window.size < policy.openThreshold) return null

        val nextCooldown = saturatingAdd(event.timestampUtc, policy.cooldownMillis)
        val intervention = Intervention(
            interventionId = "intervention:${event.eventId}",
            eventId = event.eventId,
            packageName = event.packageName,
            ruleId = policy.ruleId,
            createdAtUtc = event.timestampUtc,
            cooldownUntilUtc = nextCooldown,
        )
        cooldownUntilUtcByRuleAndPackage[cooldownKey] = nextCooldown
        // Re-arm on a fresh threshold after this intervention instead of firing on every later open.
        window.clear()
        interventions += intervention
        trimInterventions()
        return intervention
    }

    /** Drops event IDs outside the active idempotency window, including on launch restore. */
    @Synchronized
    fun pruneExpired(nowUtc: Long, retentionWindowMillis: Long) {
        val cutoff = if (nowUtc < Long.MIN_VALUE + retentionWindowMillis) {
            Long.MIN_VALUE
        } else {
            nowUtc - retentionWindowMillis
        }
        processedEventTimestampsUtc.entries.removeAll { it.value < cutoff }
        recentEventsByRuleAndPackage.entries.forEach { (_, events) -> events.removeAll { it.timestampUtc < cutoff } }
        recentEventsByRuleAndPackage.entries.removeAll { it.value.isEmpty() }
        cooldownUntilUtcByRuleAndPackage.entries.removeAll { it.value <= nowUtc }
        trimProcessedIds()
        trimRuleKeys()
    }

    private fun trimRuleKeys() {
        if (recentEventsByRuleAndPackage.size > RuleRetention.MAX_HISTORY_RECORDS) {
            recentEventsByRuleAndPackage.entries
                .sortedBy { (_, events) -> events.lastOrNull()?.timestampUtc ?: Long.MIN_VALUE }
                .take(recentEventsByRuleAndPackage.size - RuleRetention.MAX_HISTORY_RECORDS)
                .forEach { recentEventsByRuleAndPackage.remove(it.key) }
        }
        if (cooldownUntilUtcByRuleAndPackage.size > RuleRetention.MAX_HISTORY_RECORDS) {
            cooldownUntilUtcByRuleAndPackage.entries
                .sortedBy { it.value }
                .take(cooldownUntilUtcByRuleAndPackage.size - RuleRetention.MAX_HISTORY_RECORDS)
                .forEach { cooldownUntilUtcByRuleAndPackage.remove(it.key) }
        }
    }

    private fun trimProcessedIds() {
        if (processedEventTimestampsUtc.size > RuleRetention.MAX_PROCESSED_EVENT_IDS) {
            processedEventTimestampsUtc.entries
                .sortedBy { it.value }
                .take(processedEventTimestampsUtc.size - RuleRetention.MAX_PROCESSED_EVENT_IDS)
                .forEach { processedEventTimestampsUtc.remove(it.key) }
        }
    }

    private fun trimInterventions() {
        if (interventions.size > RuleRetention.MAX_HISTORY_RECORDS) {
            interventions.subList(0, interventions.size - RuleRetention.MAX_HISTORY_RECORDS).clear()
        }
    }

    @Synchronized
    fun snapshot(): RuleEvaluatorState = RuleEvaluatorState(
        processedEventIds = processedEventTimestampsUtc.keys.toSet(),
        processedEventTimestampsUtc = processedEventTimestampsUtc.toMap(),
        recentEventsByRuleAndPackage = recentEventsByRuleAndPackage.mapValues { (_, events) -> events.toList() },
        cooldownUntilUtcByRuleAndPackage = cooldownUntilUtcByRuleAndPackage.toMap(),
        interventions = interventions.takeLast(RuleRetention.MAX_HISTORY_RECORDS),
    )

    @Synchronized
    fun restore(state: RuleEvaluatorState) {
        processedEventTimestampsUtc.clear()
        processedEventTimestampsUtc.putAll(state.processedEventTimestampsUtc)
        state.processedEventIds.forEach { processedEventTimestampsUtc.putIfAbsent(it, Long.MIN_VALUE) }
        recentEventsByRuleAndPackage.clear()
        state.recentEventsByRuleAndPackage.forEach { (key, events) ->
            recentEventsByRuleAndPackage[key] = events.takeLast(RuleRetention.MAX_HISTORY_RECORDS).toMutableList()
        }
        cooldownUntilUtcByRuleAndPackage.clear()
        cooldownUntilUtcByRuleAndPackage.putAll(state.cooldownUntilUtcByRuleAndPackage)
        interventions.clear()
        interventions.addAll(state.interventions.takeLast(RuleRetention.MAX_HISTORY_RECORDS))
        trimProcessedIds()
        trimRuleKeys()
        trimInterventions()
    }

    private fun cooldownKey(ruleId: String, packageName: String): String = "$ruleId\u0000$packageName"

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
