package dev.atlas.coach.sync.agent.context

import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.usage.ObservationConsent
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Collections

/** The seven Android category keys supported by the V6.2 Context Bridge. */
enum class AgentContextCategory(val wireValue: String) {
    FOCUS("focus"),
    HABIT("habit"),
    CONTENT("content"),
    COACH_INTERACTION("coach_interaction"),
    APP_USAGE_SUMMARY("app_usage_summary"),
    GOALS("goals"),
    LOCATION_SUMMARY("location_summary");

    companion object {
        val wireValues: List<String> = entries.map(AgentContextCategory::wireValue)
        val wireValueSet: Set<String> = wireValues.toSet()
    }
}

/**
 * Immutable payload and provenance for one request. [categories] contains only non-empty lists;
 * [consentedCategories] records authorization independently, including categories with no data.
 */
class AgentContextSnapshot internal constructor(
    consentedCategories: List<String>,
    categories: Map<String, List<String>>,
    sourceTimestampsUtcMillis: Map<String, List<Long>>,
    private val usageConsentAtAssembly: ObservationConsent? = null,
    private val locationSharingEnabledAtAssembly: Boolean = false,
    private val includesExistingGoalsAtAssembly: Boolean = false,
) {
    val consentedCategories: List<String> = immutableList(consentedCategories)
    val categories: Map<String, List<String>> = immutableMap(
        categories.mapValues { (_, values) -> immutableList(values) },
    )
    private val sourceTimestampsUtcMillis: Map<String, List<Long>> = immutableMap(
        sourceTimestampsUtcMillis.mapValues { (_, values) -> immutableList(values) },
    )

    val availableCategories: Set<String> = Collections.unmodifiableSet(this.categories.keys.toSet())

    init {
        require(this.consentedCategories.all { it in AgentContextCategory.wireValueSet })
        require(this.consentedCategories.distinct().size == this.consentedCategories.size)
        require(this.categories.keys.all { it in this.consentedCategories })
        require(this.categories.keys.all { it in AgentContextCategory.wireValueSet })
        require(this.categories.values.all { values -> values.isNotEmpty() && values.size <= MAX_ITEMS_PER_CATEGORY })
        require(this.categories.values.flatten().all { codePointLength(it) <= MAX_ITEM_CODE_POINTS })
        require(this.sourceTimestampsUtcMillis.keys.containsAll(this.categories.keys))
        require(this.categories.all { (category, items) ->
            this.sourceTimestampsUtcMillis[category]?.size == items.size
        })
        require(serializedUtf8Size(consentedCategories, this.categories) <= MAX_CONTEXT_BYTES) {
            "Agent context exceeds the UTF-8 byte budget"
        }
    }

    /** Stable, JSON-ready payload shape for the existing chat/plan contract. */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "consented_categories" to consentedCategories.toList(),
        "categories" to categories.mapValues { (_, values) -> values.toList() },
    )

    /** Serialize this exact immutable payload; its UTF-8 representation is bounded to 8 KiB. */
    fun toJsonString(): String = serializePayload(consentedCategories, categories)

    fun preview(): AgentContextPreview = AgentContextPreview(
        authorizedCategories = consentedCategories,
        includedCategories = categories,
        serializedPayload = toJsonString(),
    )

    /**
     * Re-check the latest authorization and consent timestamps immediately before transport.
     * This only removes data from the original snapshot; newly granted categories need a rebuild.
     */
    fun minimizeFor(
        currentMandate: AgentMandate,
        currentConsentAtUtcMillis: Map<String, Long>,
        nowUtcMillis: Long,
        currentDomainConsent: AgentContextDomainConsent = AgentContextDomainConsent(),
    ): AgentContextSnapshot {
        require(nowUtcMillis > 0L) { "Context minimization requires a real current time" }
        val mandateIsCurrent = !currentMandate.revoked && !currentMandate.suspended &&
            nowUtcMillis < currentMandate.expiresAtUtcMillis
        val currentCategories = if (mandateIsCurrent) {
            currentMandate.sharedDataCategories.intersect(AgentContextCategory.wireValueSet)
        } else {
            emptySet()
        }
        val stillConsented = consentedCategories.filter { it in currentCategories }
        val retainedItems = linkedMapOf<String, List<String>>()
        val retainedTimestamps = linkedMapOf<String, List<Long>>()
        stillConsented.forEach { category ->
            val consentAt = currentConsentAtUtcMillis[category]?.takeIf { it > 0L } ?: return@forEach
            if (category == AgentContextCategory.APP_USAGE_SUMMARY.wireValue &&
                (currentDomainConsent.usageConsent?.isExplicit != true ||
                    currentDomainConsent.usageConsent != usageConsentAtAssembly)
            ) return@forEach
            if (category == AgentContextCategory.LOCATION_SUMMARY.wireValue &&
                (!locationSharingEnabledAtAssembly || !currentDomainConsent.locationSummarySharingEnabled)
            ) return@forEach
            val retention = retentionMillis(category)
            val domainConsentAt = when (category) {
                AgentContextCategory.APP_USAGE_SUMMARY.wireValue -> currentDomainConsent.usageConsent?.grantedAtUtc ?: consentAt
                else -> consentAt
            }
            val cutoff = maxOf(consentAt, domainConsentAt, (nowUtcMillis - retention).coerceAtLeast(0L))
            val values = categories[category].orEmpty()
            val timestamps = sourceTimestampsUtcMillis[category].orEmpty()
            val retained = values.indices.filter { index ->
                val at = timestamps[index]
                val existingGoalStateAllowed = category == AgentContextCategory.GOALS.wireValue &&
                    includesExistingGoalsAtAssembly && currentDomainConsent.includeExistingGoals
                (existingGoalStateAllowed || at >= cutoff) && at <= nowUtcMillis
            }
            if (retained.isNotEmpty()) {
                retainedItems[category] = retained.map(values::get)
                retainedTimestamps[category] = retained.map(timestamps::get)
            }
        }
        return AgentContextSnapshot(
            stillConsented,
            retainedItems,
            retainedTimestamps,
            usageConsentAtAssembly = usageConsentAtAssembly,
            locationSharingEnabledAtAssembly = locationSharingEnabledAtAssembly,
            includesExistingGoalsAtAssembly = includesExistingGoalsAtAssembly,
        )
    }

    internal fun sourceTimestamp(category: String, itemIndex: Int): Long? =
        sourceTimestampsUtcMillis[category]?.getOrNull(itemIndex)

    companion object {
        const val MAX_ITEMS_PER_CATEGORY = 10
        const val MAX_ITEM_CODE_POINTS = 200
        const val MAX_CONTEXT_BYTES = 8 * 1024

        internal fun codePointLength(value: String): Int = value.codePointCount(0, value.length)

        /** Measures the final JSON representation without constructing a validated snapshot. */
        internal fun serializedUtf8Size(
            consentedCategories: List<String>,
            categories: Map<String, List<String>>,
        ): Int = serializePayload(consentedCategories, categories).toByteArray(StandardCharsets.UTF_8).size

        private fun serializePayload(
            consentedCategories: List<String>,
            categories: Map<String, List<String>>,
        ): String {
            val payload = JSONObject()
                .put("consented_categories", JSONArray(consentedCategories))
            val values = JSONObject()
            categories.forEach { (category, items) -> values.put(category, JSONArray(items)) }
            payload.put("categories", values)
            return payload.toString()
        }

        internal fun retentionMillis(category: String): Long = when (category) {
            AgentContextCategory.FOCUS.wireValue -> AgentContextAssembler.FOCUS_RETENTION_MILLIS
            AgentContextCategory.HABIT.wireValue -> AgentContextAssembler.HABIT_RETENTION_MILLIS
            AgentContextCategory.CONTENT.wireValue,
            AgentContextCategory.COACH_INTERACTION.wireValue -> AgentContextAssembler.ACTIVITY_RETENTION_MILLIS
            AgentContextCategory.APP_USAGE_SUMMARY.wireValue -> AgentContextAssembler.USAGE_RETENTION_MILLIS
            AgentContextCategory.LOCATION_SUMMARY.wireValue -> AgentContextAssembler.LOCATION_RETENTION_MILLIS
            AgentContextCategory.GOALS.wireValue -> Long.MAX_VALUE
            else -> 0L
        }

        private fun <T> immutableList(values: List<T>): List<T> =
            Collections.unmodifiableList(values.toList())

        private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
            Collections.unmodifiableMap(LinkedHashMap(values))
    }
}

/** Fresh non-agent sharing switches that also gate locally collected UsageStats and location. */
data class AgentContextDomainConsent(
    val usageConsent: ObservationConsent? = null,
    val locationSummarySharingEnabled: Boolean = false,
    val includeExistingGoals: Boolean = false,
)

/** Human-readable preview data is derived from exactly the payload that will be serialized. */
data class AgentContextPreview(
    val authorizedCategories: List<String>,
    val includedCategories: Map<String, List<String>>,
    val serializedPayload: String,
) {
    val availableCategories: Set<String> get() = includedCategories.keys
}
