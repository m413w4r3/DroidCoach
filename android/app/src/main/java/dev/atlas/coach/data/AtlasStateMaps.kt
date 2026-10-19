package dev.atlas.coach.data

import dev.atlas.coach.rules.AppOpenEvent
import dev.atlas.coach.rules.Intervention
import dev.atlas.coach.rules.LocalPolicy
import dev.atlas.coach.rules.QuietHours
import dev.atlas.coach.rules.RuleRetention
import dev.atlas.coach.rules.RuleEvaluatorState
import java.time.LocalTime

fun LocalPolicy.toStorageMap(): Map<String, Any?> = mapOf(
    "ruleId" to ruleId,
    "openThreshold" to openThreshold,
    "windowMillis" to windowMillis,
    "cooldownMillis" to cooldownMillis,
    "quietStart" to quietHours?.start?.toString(),
    "quietEnd" to quietHours?.end?.toString(),
    "pauseUntil" to pauseUntil,
    "vitalPackageNames" to vitalPackageNames.sorted(),
    "emergencyPauseMillis" to emergencyPauseMillis,
    "allowlistedPackageNames" to allowlistedPackageNames.orEmpty().sorted(),
    "excludedPackageNames" to excludedPackageNames.sorted(),
)

fun localPolicyFromStorage(values: Map<String, Any?>): LocalPolicy {
    val quietStart = values.optionalString("quietStart")
    val quietEnd = values.optionalString("quietEnd")
    require((quietStart == null) == (quietEnd == null)) { "Quiet hours must have both endpoints" }
    val vitalPackages = values.optionalList("vitalPackageNames")?.map {
        it as? String ?: throw IllegalArgumentException("Invalid vital package name")
    }?.toSet().orEmpty()
    val allowlistedPackages = values.optionalList("allowlistedPackageNames")?.map {
        it as? String ?: throw IllegalArgumentException("Invalid allowlisted package name")
    }?.toSet().orEmpty()
    val excludedPackages = values.optionalList("excludedPackageNames")?.map {
        it as? String ?: throw IllegalArgumentException("Invalid excluded package name")
    }?.toSet().orEmpty()
    val quietHours = if (quietStart == null) null else QuietHours(
        LocalTime.parse(quietStart),
        LocalTime.parse(quietEnd!!),
    )
    return LocalPolicy(
        ruleId = values.optionalString("ruleId") ?: "app-open-frequency",
        openThreshold = values.optionalNumber("openThreshold")?.toInt() ?: 4,
        windowMillis = values.optionalNumber("windowMillis")?.toLong() ?: 25L * 60L * 1_000L,
        cooldownMillis = values.optionalNumber("cooldownMillis")?.toLong() ?: 15L * 60L * 1_000L,
        quietHours = quietHours,
        pauseUntil = values.optionalNumber("pauseUntil")?.toLong(),
        vitalPackageNames = vitalPackages,
        emergencyPauseMillis = values.optionalNumber("emergencyPauseMillis")?.toLong() ?: 30L * 60L * 1_000L,
        allowlistedPackageNames = allowlistedPackages,
        excludedPackageNames = excludedPackages,
    )
}

fun RuleEvaluatorState.toStorageMap(): Map<String, Any?> = mapOf(
    "processedEventIds" to processedEventIds.sorted(),
    "processedEventTimestampsUtc" to processedEventTimestampsUtc,
    "recentEventsByRuleAndPackage" to recentEventsByRuleAndPackage.mapValues { (_, events) -> events.map(AppOpenEvent::toStorageMap) },
    "cooldownUntilUtcByRuleAndPackage" to cooldownUntilUtcByRuleAndPackage,
    "interventions" to interventions.map(Intervention::toStorageMap),
)

fun ruleEvaluatorStateFromStorage(values: Map<String, Any?>): RuleEvaluatorState {
    val recentRaw = values.optionalMap("recentEventsByRuleAndPackage").orEmpty()
    val recent = recentRaw.mapValues { (_, rawEvents) ->
        (rawEvents as? List<*>)?.map { raw -> appOpenEventFromStorage(raw.requiredMap()) }
            ?: throw IllegalArgumentException("recent events must be a list")
    }
    val timestampsRaw = values.optionalMap("processedEventTimestampsUtc").orEmpty()
    val timestamps = timestampsRaw.mapValues { (_, raw) ->
        (raw as? Number)?.toLong() ?: throw IllegalArgumentException("Invalid processed event timestamp")
    }
    val legacyIds = values.optionalList("processedEventIds")?.map {
        it as? String ?: throw IllegalArgumentException("Invalid processed event ID")
    }?.toSet().orEmpty()
    val timestampsFromHistory = recent.values.flatten().associate { it.eventId to it.timestampUtc }
    val processedTimestamps = (legacyIds.associateWith { timestamps[it] ?: timestampsFromHistory[it] ?: Long.MIN_VALUE } + timestamps)
        .entries.sortedByDescending { it.value }
        .take(RuleRetention.MAX_PROCESSED_EVENT_IDS)
        .associate { it.key to it.value }
    val cooldownRaw = values.optionalMap("cooldownUntilUtcByRuleAndPackage").orEmpty()
    val cooldown = cooldownRaw.mapValues { (_, raw) ->
        (raw as? Number)?.toLong() ?: throw IllegalArgumentException("Invalid cooldown timestamp")
    }.entries.sortedByDescending { it.value }.take(RuleRetention.MAX_HISTORY_RECORDS).associate { it.key to it.value }
    val interventions = values.optionalList("interventions")?.map { raw ->
        interventionFromStorage(raw.requiredMap())
    }?.takeLast(RuleRetention.MAX_HISTORY_RECORDS).orEmpty()
    return RuleEvaluatorState(
        processedEventIds = processedTimestamps.keys,
        processedEventTimestampsUtc = processedTimestamps,
        recentEventsByRuleAndPackage = recent.entries
            .map { it.key to it.value.takeLast(RuleRetention.MAX_HISTORY_RECORDS) }
            .sortedByDescending { (_, events) -> events.lastOrNull()?.timestampUtc ?: Long.MIN_VALUE }
            .take(RuleRetention.MAX_HISTORY_RECORDS)
            .toMap(),
        cooldownUntilUtcByRuleAndPackage = cooldown,
        interventions = interventions,
    )
}

private fun AppOpenEvent.toStorageMap() = mapOf(
    "eventId" to eventId,
    "packageName" to packageName,
    "timestampUtc" to timestampUtc,
)

private fun appOpenEventFromStorage(values: Map<String, Any?>) = AppOpenEvent(
    eventId = values.requiredString("eventId").also { require(it.isNotBlank()) },
    packageName = values.requiredString("packageName").also { require(it.isNotBlank()) },
    timestampUtc = values.requiredNumber("timestampUtc").toLong(),
)

private fun Intervention.toStorageMap() = mapOf(
    "interventionId" to interventionId,
    "eventId" to eventId,
    "packageName" to packageName,
    "ruleId" to ruleId,
    "createdAtUtc" to createdAtUtc,
    "cooldownUntilUtc" to cooldownUntilUtc,
)

private fun interventionFromStorage(values: Map<String, Any?>) = Intervention(
    interventionId = values.requiredString("interventionId"),
    eventId = values.requiredString("eventId"),
    packageName = values.requiredString("packageName"),
    ruleId = values.requiredString("ruleId"),
    createdAtUtc = values.requiredNumber("createdAtUtc").toLong(),
    cooldownUntilUtc = values.requiredNumber("cooldownUntilUtc").toLong(),
)

private fun Any?.requiredMap(): Map<String, Any?> = (this as? Map<*, *>)
    ?.entries
    ?.associate { (key, value) ->
        (key as? String ?: throw IllegalArgumentException("Map key must be a string")) to value
    }
    ?: throw IllegalArgumentException("Expected a map")

private fun Map<String, Any?>.requiredString(key: String): String = this[key] as? String
    ?: throw IllegalArgumentException("Missing or invalid $key")

private fun Map<String, Any?>.requiredNumber(key: String): Number = this[key] as? Number
    ?: throw IllegalArgumentException("Missing or invalid $key")

private fun Map<String, Any?>.optionalString(key: String): String? {
    if (!containsKey(key) || this[key] == null) return null
    return this[key] as? String ?: throw IllegalArgumentException("Invalid $key")
}

private fun Map<String, Any?>.optionalNumber(key: String): Number? {
    if (!containsKey(key) || this[key] == null) return null
    return this[key] as? Number ?: throw IllegalArgumentException("Invalid $key")
}

private fun Map<String, Any?>.optionalList(key: String): List<*>? {
    if (!containsKey(key) || this[key] == null) return null
    return this[key] as? List<*> ?: throw IllegalArgumentException("Invalid $key")
}

private fun Map<String, Any?>.optionalMap(key: String): Map<String, Any?>? {
    if (!containsKey(key) || this[key] == null) return null
    return this[key].requiredMap()
}
